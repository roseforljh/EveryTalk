package com.android.everytalk.data.sync

import androidx.room.withTransaction
import com.android.everytalk.data.auth.SyncAccountBindingStore
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.SystemSettingEntity
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/**
 * 游标、epoch、限流截止与提示复用本地 system_settings；永不纳入同步 payload。
 * 所有控制状态由 Room 事务更新，杀进程后保持已确认批次与用户决定。
 */
internal class SyncStateStore(private val database: AppDatabase) {
    private val settings = database.settingsDao()

    suspend fun readCursor(): SyncCursorState = SyncCursorState(
        cursor = settings.getValue(KEY_CURSOR)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
        epoch = settings.getValue(KEY_EPOCH)?.toLongOrNull() ?: -1L,
    )

    suspend fun readSecretsEpoch(): Long = settings.getValue(KEY_SECRETS_EPOCH)?.toLongOrNull() ?: -1L

    suspend fun clearSecretsEpoch() = settings.deleteSetting(KEY_SECRETS_EPOCH)

    suspend fun recordSecretsEpoch(epoch: Long) {
        require(epoch >= 0)
        settings.insertSetting(SystemSettingEntity(KEY_SECRETS_EPOCH, epoch.toString()))
    }

    /**
     * 密钥代次变化后只丢弃本机的密文缓存和游标快照，不删除本地明文配置。
     * 游标归零是合同要求，随后由同步引擎重新读取配置记录和密钥墓碑。
     */
    suspend fun resetSecretsEpoch(epoch: Long) = database.withTransaction {
        require(epoch >= 0)
        settings.insertSetting(SystemSettingEntity(KEY_SECRETS_EPOCH, epoch.toString()))
        settings.deleteSetting(KEY_CURSOR)
        settings.deleteSetting(KEY_EPOCH)
        settings.getByPrefix("sync_secret_cache:").forEach { settings.deleteSetting(it.key) }
        for (row in settings.getAllUserSettings()) {
            if (row.kind == "crypto_keys") settings.deleteUserSetting(row.kind, row.key)
            else settings.upsertUserSettings(listOf(row.copy(value = stripAccountFields(syncJson.parseToJsonElement(row.value), false)?.toString() ?: "{}")))
        }
    }

    suspend fun saveCursor(cursor: Long, epoch: Long) {
        require(cursor >= 0 && epoch >= 0)
        settings.insertSetting(SystemSettingEntity(KEY_CURSOR, cursor.toString()))
        settings.insertSetting(SystemSettingEntity(KEY_EPOCH, epoch.toString()))
    }

    /**
     * 登录状态决定是否允许同步，旧版的 sync_enabled 只作为兼容数据保留。
     * 云端被清空后的暂停仍然有效，避免应用重启后未经用户选择再次上传。
     */
    suspend fun isEnabled(): Boolean =
        settings.getValue(KEY_EPOCH_DECISION) == null && settings.getValue(KEY_RESET_PAUSED) != "true"
    suspend fun isPausedAfterCloudReset(): Boolean = settings.getValue(KEY_RESET_PAUSED) == "true"

    /**
     * 登录后自动接管游客文字数据；已绑定其他账号时等待原有三选一，不隐式上传。
     * 自动模式只初始化一次：重启或重新登录保留游标、拒绝记录和已同步状态，避免反复全量上传。
     * 云端清空后的暂停是用户的数据保护决定，不能被登录恢复或应用重启重新开启。
     */
    suspend fun prepareAutomaticAccount(
        accountId: String,
        binding: SyncAccountBindingStore,
        canPrepare: () -> Boolean = { true },
    ): Boolean = SyncRuntimeLock.mutex.withLock {
        val previous = binding.readBoundAccountId()
        if (!canPrepare() || (previous != null && previous != accountId)) return@withLock false
        if (settings.getValue(KEY_ACCOUNT) == accountId &&
            (epochDecision() != null || isPausedAfterCloudReset())) return@withLock false
        if (!canPrepare()) return@withLock false
        database.withTransaction {
            if (settings.getValue(KEY_AUTOMATIC_ACCOUNT) != accountId || settings.getValue(KEY_ACCOUNT) != accountId) {
                prepareAccount(accountId, upload = true)
            }
        }
        if (!canPrepare()) return@withLock false
        if (previous == null) binding.bindTo(accountId)
        // 兼容旧版本已经失败的记录：只恢复明确可重试的错误，永久拒绝和用户主动保留本地的数据不动。
        retryRecoverableRejections()
        true
    }

    /**
     * 旧版把所有逐条拒绝都标为 LOCAL_ONLY，导致客户端修复后也永远不会再尝试。
     * 这里仅恢复客户端已经能修复或服务器要求重新协商的错误，避免把永久错误变成死循环。
     */
    private suspend fun retryRecoverableRejections() = database.withTransaction {
        settings.getByPrefix("sync_rejected:").forEach { row ->
            val parts = row.key.removePrefix("sync_rejected:").split(':', limit = 2)
            if (parts.size != 2) return@forEach
            val setting = if (parts[0] == "user_settings") {
                settings.getUserSettingByUuid(parts[1])
            } else null
            val isLegacyGroupOrPin = setting?.kind == "conversation_groups" ||
                (setting?.kind == "pinned_items" && setting.key == "text")
            val canRetry = row.value in RECOVERABLE_REJECTION_CODES ||
                (row.value == "INVALID_USER_SETTING_KIND" && setting?.kind in CURRENT_SETTING_KINDS)
            if (isLegacyGroupOrPin) {
                // 新版投影会从本地分组/置顶表生成 group_members/pinned_items，旧整表行本身不再上传。
                settings.deleteSetting(row.key)
                return@forEach
            }
            if (row.value == "MESSAGE_PAYLOAD_TOO_LARGE" && parts[0] == "messages") {
                // 旧版本把整条超限消息永久拒绝了。只自动补传一次新的核心字段；
                // 若核心或后续补充字段仍超限，保留失败提示，避免每次启动重复上传。
                val retryMarker = "sync_message_size_retry:${parts[1]}"
                if (settings.getValue(retryMarker) != "3") {
                    // 本版移除了显示片段中的重复正文/思考，旧超限记录最多再核对一次。
                    settings.insertSetting(SystemSettingEntity(retryMarker, "3"))
                    markPending(parts[0], parts[1])
                    settings.deleteSetting(row.key)
                }
                // 已核对仍超限时保留失败提示，不能把“停止重试”伪装成同步成功。
                return@forEach
            }
            if (row.value == "INVALID_SESSION_ID" && parts[0] == "messages") {
                val message = database.chatDao().getMessageByUuid(parts[1])
                val session = message?.let { database.chatDao().getStoredSession(it.sessionId) }
                val marker = "sync_message_session_retry:${parts[1]}"
                // 只修复旧补充请求漏带 sessionId；无合法父会话的数据仍保留拒绝，不能无限重试。
                if (session != null && isCanonicalUuid(session.uuid) && settings.getValue(marker) == null) {
                    markPending(parts[0], parts[1])
                    settings.insertSetting(SystemSettingEntity(marker, "1"))
                    settings.deleteSetting(row.key)
                }
                return@forEach
            }
            if (!canRetry) return@forEach
            markPending(parts[0], parts[1])
            // 记录不存在时也清掉提示，避免设置页永久显示幽灵错误。
            settings.deleteSetting(row.key)
        }
    }

    private suspend fun markPending(entity: String, id: String) {
        when (entity) {
            "sessions" -> database.chatDao().markSessionPendingByUuid(id)
            "messages" -> database.chatDao().markMessagePendingByUuid(id)
            "memories" -> database.memoryDao().markPendingByUuid(id)
            "user_settings" -> settings.markUserSettingPendingByUuid(id)
            "attachments" -> database.syncAttachmentDao().markPendingById(id)
        }
    }

    suspend fun epochDecision(): Long? = settings.getValue(KEY_EPOCH_DECISION)?.toLongOrNull()
    suspend fun requireEpochDecision(epoch: Long) = database.withTransaction {
        settings.insertSetting(SystemSettingEntity(KEY_EPOCH_DECISION, epoch.toString()))
        pauseAfterCloudReset()
        // 丢弃云端游标快照，但保留所有本机正文；用户同意后从 0 开始重新协调。
        saveCursor(0, epoch)
    }

    suspend fun retryAt(): Long = settings.getValue(KEY_RETRY_AT)?.toLongOrNull() ?: 0L
    suspend fun deferRetry(seconds: Long) {
        val now = System.currentTimeMillis()
        val millis = seconds.coerceIn(0, (Long.MAX_VALUE - now) / 1000) * 1000
        settings.insertSetting(SystemSettingEntity(KEY_RETRY_AT, (now + millis).toString()))
    }
    suspend fun clearRetry() = settings.deleteSetting(KEY_RETRY_AT)

    suspend fun markSyncCompleted() {
        settings.insertSetting(SystemSettingEntity(KEY_LAST_SYNC_AT, System.currentTimeMillis().toString()))
    }

    /** 删除请求可能已经到服务器，先持久化暂停；即使响应丢失或进程被杀也不能重新上传。 */
    suspend fun pauseAfterCloudReset() = database.withTransaction {
        settings.insertSetting(SystemSettingEntity(KEY_RESET_PAUSED, "true"))
    }

    /** 仅在用户明确选择重新上传时丢弃云端快照；下一次 pull 重新取得真实 epoch。 */
    suspend fun resumeAfterCloudReset() = database.withTransaction {
        settings.deleteSetting(KEY_EPOCH)
        settings.deleteSetting(KEY_CURSOR)
        markAllLocalDataPending()
    }

    /** 换账号前取消任务，事务内重置账号专属云端快照；API Key 和本地数据不受影响。 */
    suspend fun prepareAccount(accountId: String, upload: Boolean) = database.withTransaction {
        if (settings.getValue(KEY_ACCOUNT) != accountId) {
            val oldAccount = settings.getValue(KEY_ACCOUNT)
            if (oldAccount != null) {
                // 本机明文和已下载文件保留，密文 AAD、fileId 和 canonical id 都属于旧账号，不能转发。
                resetAccountReferences()
            }
            settings.insertSetting(SystemSettingEntity(KEY_ACCOUNT, accountId))
            settings.deleteSetting(KEY_SECRETS_EPOCH)
            settings.deleteSetting(KEY_EPOCH)
            settings.deleteSetting(KEY_EPOCH_DECISION)
            settings.insertSetting(SystemSettingEntity(KEY_CURSOR, "0"))
            settings.clearSyncRejections()
            settings.deleteSetting(KEY_LAST_SYNC_AT)
            clearRetry()
        }
        if (upload) markAllLocalDataPending() else {
            database.chatDao().keepAllTextLocal()
            database.memoryDao().keepAllLocal()
            settings.keepAllUserSettingsLocal()
        }
        settings.deleteSetting(KEY_EPOCH_DECISION)
        settings.deleteSetting(KEY_RESET_PAUSED)
        settings.insertSetting(SystemSettingEntity(KEY_AUTOMATIC_ACCOUNT, accountId))
    }

    /** epoch 提示选择上传才调用；生图、草稿、API/语音配置不进入队列。 */
    suspend fun markAllLocalDataPending() = database.withTransaction {
        database.chatDao().markAllTextSessionsPending()
        database.chatDao().markAllTextMessagesPending()
        database.memoryDao().markAllPending()
        settings.markAllUserSettingsPending()
        // 云端 epoch 变化后，旧的远端字段清单已经失效；重新上传必须从消息核心字段开始。
        settings.getByPrefix("sync_message_fields:").forEach { settings.deleteSetting(it.key) }
        listOf("sync_resource_source:", "sync_resource_failed:", "sync_resource_receive_failed:").forEach { prefix ->
            settings.getByPrefix(prefix).forEach { settings.deleteSetting(it.key) }
        }
        settings.insertSetting(SystemSettingEntity("sync_resource_republish", "true"))
        settings.clearSyncRejections()
        settings.deleteSetting(KEY_EPOCH_DECISION)
        settings.deleteSetting(KEY_RESET_PAUSED)
    }

    /** 换账号只清账号专属快照，下一轮从本机明文重新加密和上传资源。 */
    private suspend fun resetAccountReferences() {
        for (row in settings.getAllUserSettings()) {
            if (row.kind == "crypto_keys") settings.deleteUserSetting(row.kind, row.key)
            else settings.upsertUserSettings(listOf(row.copy(uuid = com.android.everytalk.data.database.entities.localSyncUuid("user_settings", "${row.kind}:${row.key}"),
                value = stripAccountFields(syncJson.parseToJsonElement(row.value), true)?.toString() ?: "{}")))
        }
        database.openHelper.writableDatabase.execSQL("DELETE FROM sync_attachments")
        listOf("sync_resource_meta:", "sync_private_applied:", "sync_secret_cache:", "sync_resource_restored:").forEach { prefix ->
            settings.getByPrefix(prefix).forEach { settings.deleteSetting(it.key) }
        }
    }

    private fun stripAccountFields(value: JsonElement, removeFileRefs: Boolean): JsonElement? = when {
        value is JsonObject && value.containsKey("\$enc") -> null
        value is JsonObject -> JsonObject(value.mapNotNull { (name, child) ->
            if (removeFileRefs && name in setOf("fileRefs", "fileManifest", "manifestRef")) null
            else stripAccountFields(child, removeFileRefs)?.let { name to it }
        }.toMap())
        value is JsonArray -> JsonArray(value.mapNotNull { stripAccountFields(it, removeFileRefs) })
        else -> value
    }

    suspend fun rejectEpochUpload() = database.withTransaction {
        settings.deleteSetting(KEY_EPOCH_DECISION)
        pauseAfterCloudReset()
    }

    /** 三选一清空范围：文字会话、文字置顶/分组及记忆；不清密钥、生图和设备设置。 */
    suspend fun clearSyncableLocalData() = database.withTransaction {
        database.chatDao().clearTextSyncData()
        database.chatDao().deleteAllPendingMessages()
        database.memoryDao().clearSyncData()
        settings.clearPinnedItems(false)
        settings.clearTextGroupsForSync()
        settings.clearUserSettingsForSync()
        settings.clearSyncRejections()
    }

    companion object {
        const val KEY_ENABLED = "sync_enabled"
        const val KEY_CURSOR = "sync_server_cursor"
        const val KEY_EPOCH = "sync_server_epoch"
        const val KEY_EPOCH_DECISION = "sync_epoch_decision_required"
        const val KEY_ACCOUNT = "sync_account_id"
        const val KEY_RETRY_AT = "sync_retry_at"
        const val KEY_LAST_SYNC_AT = "sync_last_success_at"
        const val KEY_AUTOMATIC_ACCOUNT = "sync_automatic_account_id"
        const val KEY_RESET_PAUSED = "sync_paused_after_cloud_reset"
        const val KEY_SECRETS_EPOCH = "sync_secrets_epoch"

        /** 这些错误在客户端修复映射或重新拉取密钥代次后可以安全重试。 */
        private val RECOVERABLE_REJECTION_CODES = setOf("SECRETS_EPOCH_MISMATCH")
        private val CURRENT_SETTING_KINDS = setOf(
            "api_configs", "selected_models", "web_search_configs", "voice_configs",
            "voice_llm_platforms", "voice_custom_models", "mcp_servers", "skills",
            "skill_variables", "computers", "computer_workspaces", "conversation_settings",
            "memory_switches", "group_members", "pinned_items", "crypto_keys",
        )
    }
}

