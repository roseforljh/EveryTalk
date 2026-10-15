package com.android.everytalk.data.sync

import androidx.room.withTransaction
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.ModelParameters
import com.android.everytalk.data.DataClass.ModalityType
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID
import com.android.everytalk.data.network.ExternalWebSearchProviderConfig

internal interface SyncStore {
    suspend fun pendingBatch(maxItems: Int = 500): List<PendingChangeSnapshot>
    suspend fun reservePush(snapshots: List<PendingChangeSnapshot>)
    suspend fun applyPush(response: PushResponse, snapshots: List<PendingChangeSnapshot>)
    suspend fun applyPullPage(response: PullResponse)
}

/**
 * 同步在独立事务中应用远端记录，不调用本地 save/upsert 边界，防止拉取制造 PENDING。
 * 页面旧 ID 与远端 UUID 分开；消息提前到达时创建 SYNCED 的占位会话。
 */
internal class RoomSyncStore(
    private val database: AppDatabase,
    private val secrets: SyncSecretManager? = null,
    private val userId: String? = null,
) : SyncStore {
    /** 合同规定 messages.payload 的默认上限；超限时只丢弃可选补充字段，不改本地原文。 */
    private val maxMessagePayloadBytes = 65536
    private val optionalMessageFieldsForSize = listOf(
        "reasoning",
        "contextUsageSnapshot",
        "tokenUsage",
        "enabledToolIds",
        "executionFinishedAt",
    )
    private val chat = database.chatDao()
    private val memory = database.memoryDao()
    private val settings = database.settingsDao()
    private val attachments = database.syncAttachmentDao()
    private val state = SyncStateStore(database)

    /**
     * 同步密码输入成功后重新应用已经拉取的配置。
     * 这里只写入实际配置表，不修改 user_settings 的同步状态，因此不会产生回传。
     */
    suspend fun restoreUnlockedSettings() = database.withTransaction {
        settings.getAllUserSettings().filter { !it.deleted }.forEach { restoreSetting(it) }
        projectSettings()
        SyncLocalChanges.notifyChanged()
    }

    private suspend fun restoreSetting(setting: UserSettingEntity) {
        val value = runCatching { syncJson.parseToJsonElement(setting.value).jsonObject }.getOrNull() ?: return
        when (setting.kind) {
            "selected_models" -> {
                val localKey = when (setting.key) {
                    "text" -> "selected_api_config_id"
                    "image" -> "selected_image_gen_config_id"
                    "voice" -> "selected_voice_config_id"
                    "web_search" -> "selected_external_web_search_provider_id"
                    else -> return
                }
                val selected = value[if (setting.key == "web_search") "providerId" else "configId"]?.jsonPrimitive?.contentOrNull ?: return
                settings.insertSetting(SystemSettingEntity(localKey, selected))
            }
            "memory_switches" -> {
                value["memoryEnabled"]?.jsonPrimitive?.contentOrNull?.let { settings.insertSetting(SystemSettingEntity("memory_enabled", it)) }
                value["autoCaptureEnabled"]?.jsonPrimitive?.contentOrNull?.let { settings.insertSetting(SystemSettingEntity("memory_auto_capture_enabled", it)) }
            }
            "web_search_configs" -> restoreWebSearch(setting, value)
            "api_configs" -> restoreApiConfig(setting, value)
            "voice_configs" -> restoreVoiceConfig(setting, value)
            "mcp_servers" -> restoreMcp(setting, value)
            "skills" -> restoreSkill(setting, value)
            "conversation_settings" -> Unit // DataPersistenceManager 在打开会话时读取该同步记录。
            "computers", "computer_workspaces", "voice_llm_platforms", "voice_custom_models", "skill_variables" -> Unit
        }
    }

    private suspend fun restoreApiConfig(setting: UserSettingEntity, value: JsonObject) {
        val old = database.apiConfigDao().getConfigs(value["imageConfig"]?.jsonPrimitive?.booleanOrNull == true).firstOrNull { it.id == setting.key }
        val modelParameters = value["modelParameters"]?.jsonObject?.let { params ->
            (old?.modelParameters ?: ModelParameters()).copy(
                reasoningEffort = params["reasoningEffort"]?.jsonPrimitive?.contentOrNull ?: (old?.modelParameters ?: ModelParameters()).reasoningEffort,
                thinkingBudget = params["thoughtBudget"]?.jsonPrimitive?.intOrNull ?: (old?.modelParameters ?: ModelParameters()).thinkingBudget,
                maxContextTokens = params["contextLimit"]?.jsonPrimitive?.intOrNull ?: (old?.modelParameters ?: ModelParameters()).maxContextTokens,
                autoContextCompressionEnabled = params["autoCompressionEnabled"]?.jsonPrimitive?.booleanOrNull ?: (old?.modelParameters ?: ModelParameters()).autoContextCompressionEnabled,
                autoContextCompressionThresholdPercent = params["autoCompressionThreshold"]?.jsonPrimitive?.intOrNull ?: (old?.modelParameters ?: ModelParameters()).autoContextCompressionThresholdPercent,
            )
        } ?: old?.modelParameters ?: ModelParameters()
        val key = decryptedString(setting, value, "apiKey", "/apiKey") ?: old?.key ?: ""
        database.apiConfigDao().insertConfigs(listOf(ApiConfigEntity(
            id = setting.key, address = value["address"]?.jsonPrimitive?.contentOrNull ?: old?.address.orEmpty(), key = key,
            model = value["model"]?.jsonPrimitive?.contentOrNull ?: old?.model.orEmpty(), provider = value["provider"]?.jsonPrimitive?.contentOrNull ?: old?.provider.orEmpty(),
            name = value["name"]?.jsonPrimitive?.contentOrNull ?: old?.name.orEmpty(), channel = value["channel"]?.jsonPrimitive?.contentOrNull ?: old?.channel.orEmpty(),
            isValid = old?.isValid ?: true, modalityType = value["modality"]?.jsonPrimitive?.contentOrNull?.let { runCatching { ModalityType.valueOf(it) }.getOrNull() } ?: old?.modalityType ?: ModalityType.TEXT,
            temperature = value["temperature"]?.jsonPrimitive?.floatOrNull ?: old?.temperature ?: 0.7f, topP = value["topP"]?.jsonPrimitive?.floatOrNull ?: old?.topP,
            maxTokens = value["outputLimit"]?.jsonPrimitive?.intOrNull ?: old?.maxTokens, defaultUseWebSearch = value["defaultUseWebSearch"]?.jsonPrimitive?.booleanOrNull ?: old?.defaultUseWebSearch,
            imageSize = value["imageSize"]?.jsonPrimitive?.contentOrNull ?: old?.imageSize, numInferenceSteps = value["inferenceSteps"]?.jsonPrimitive?.intOrNull ?: old?.numInferenceSteps,
            guidanceScale = value["guidanceScale"]?.jsonPrimitive?.floatOrNull ?: old?.guidanceScale, modelParameters = modelParameters,
            toolsJson = old?.toolsJson, enableCodeExecution = value["codeExecutionEnabled"]?.jsonPrimitive?.booleanOrNull ?: old?.enableCodeExecution,
            isImageGenConfig = value["imageConfig"]?.jsonPrimitive?.booleanOrNull ?: old?.isImageGenConfig ?: false,
        )))
    }

    private suspend fun restoreWebSearch(setting: UserSettingEntity, value: JsonObject) {
        val rows = settings.getValue("external_web_search_configs")?.let { runCatching { syncJson.decodeFromString<List<ExternalWebSearchProviderConfig>>(it) }.getOrNull() }.orEmpty().toMutableList()
        val old = rows.firstOrNull { it.providerId == setting.key }
        val apiKey = decryptedString(setting, value, "apiKey", "/apiKey") ?: old?.apiKey.orEmpty()
        val next = ExternalWebSearchProviderConfig(setting.key, apiKey)
        rows.removeAll { it.providerId == setting.key }; rows += next
        settings.insertSetting(SystemSettingEntity("external_web_search_configs", syncJson.encodeToString(rows)))
    }

    private suspend fun restoreVoiceConfig(setting: UserSettingEntity, value: JsonObject) {
        val old = database.voiceConfigDao().getAll().firstOrNull { it.id == setting.key }
        fun text(name: String, fallback: String = "") = value[name]?.jsonPrimitive?.contentOrNull ?: fallback
        database.voiceConfigDao().insertAll(listOf(VoiceBackendConfigEntity(
            id = setting.key, name = text("name", old?.name.orEmpty()), provider = text("provider", old?.provider.orEmpty()),
            sttPlatform = text("sttPlatform", old?.sttPlatform.orEmpty()), sttApiKey = decryptedString(setting, value, "sttApiKey", "/sttApiKey") ?: old?.sttApiKey.orEmpty(), sttApiUrl = text("sttEndpoint", old?.sttApiUrl.orEmpty()), sttModel = text("sttModel", old?.sttModel.orEmpty()),
            chatPlatform = text("chatPlatform", old?.chatPlatform.orEmpty()), chatApiKey = decryptedString(setting, value, "chatApiKey", "/chatApiKey") ?: old?.chatApiKey.orEmpty(), chatApiUrl = text("chatEndpoint", old?.chatApiUrl.orEmpty()), chatModel = text("chatModel", old?.chatModel.orEmpty()),
            ttsPlatform = text("ttsPlatform", old?.ttsPlatform.orEmpty()), ttsApiKey = decryptedString(setting, value, "ttsApiKey", "/ttsApiKey") ?: old?.ttsApiKey.orEmpty(), ttsApiUrl = text("ttsEndpoint", old?.ttsApiUrl.orEmpty()), ttsModel = text("ttsModel", old?.ttsModel.orEmpty()),
            voiceName = text("voiceName", old?.voiceName.orEmpty()), useRealtimeStreaming = value["realtimeStreaming"]?.jsonPrimitive?.booleanOrNull ?: old?.useRealtimeStreaming ?: false,
            isValid = old?.isValid ?: true, createdAt = value["createdAt"]?.jsonPrimitive?.longOrNull ?: old?.createdAt ?: System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
        )))
    }

    private suspend fun restoreMcp(setting: UserSettingEntity, value: JsonObject) {
        val old = database.mcpConfigDao().getConfigById(setting.key)
        val headers = value["headers"]?.let { decryptedJson(setting, it, "/headers")?.toString() } ?: old?.headers ?: "[]"
        database.mcpConfigDao().insertConfig(McpServerConfigEntity(setting.key, value["name"]?.jsonPrimitive?.contentOrNull ?: old?.name.orEmpty(), value["endpoint"]?.jsonPrimitive?.contentOrNull ?: old?.url.orEmpty(), value["transport"]?.jsonPrimitive?.contentOrNull ?: old?.transportType.orEmpty(), value["enabled"]?.jsonPrimitive?.booleanOrNull ?: old?.enabled ?: true, headers))
    }

    private suspend fun restoreSkill(setting: UserSettingEntity, value: JsonObject) {
        val old = database.skillDao().getInstallation(setting.key)
        database.skillDao().upsertInstallation(SkillInstallationEntity(setting.key, value["name"]?.jsonPrimitive?.contentOrNull ?: old?.name.orEmpty(), old?.description ?: "", value["sourceType"]?.jsonPrimitive?.contentOrNull ?: old?.sourceType.orEmpty(), old?.sourceRepository, old?.sourcePath, value["contentHash"]?.jsonPrimitive?.contentOrNull ?: old?.currentHash.orEmpty(), value["enabled"]?.jsonPrimitive?.booleanOrNull ?: old?.enabled ?: true, value["invocationMode"]?.jsonPrimitive?.contentOrNull ?: old?.invocationMode.orEmpty(), old?.updateHash, value["installedAt"]?.jsonPrimitive?.longOrNull ?: old?.createdAt ?: System.currentTimeMillis(), System.currentTimeMillis(), old?.lastUsedAt, old?.useCount ?: 0L, value["packageId"]?.jsonPrimitive?.contentOrNull ?: old?.packageId.orEmpty(), value["packageName"]?.jsonPrimitive?.contentOrNull ?: old?.packageName.orEmpty()))
    }

    private suspend fun decryptedString(setting: UserSettingEntity, value: JsonObject, name: String, pointer: String): String? =
        value[name]?.let { element -> if (element is JsonPrimitive) element.contentOrNull else decryptedJson(setting, element, pointer)?.jsonPrimitive?.contentOrNull }

    private suspend fun decryptedJson(setting: UserSettingEntity, value: JsonElement, pointer: String): JsonElement? =
        if (value is JsonObject && value.containsKey("\$enc") && secrets != null && userId != null) secrets.decryptField(userId, setting.kind, setting.key, pointer, state.readSecretsEpoch().coerceAtLeast(0L), value) else value

    override suspend fun pendingBatch(maxItems: Int): List<PendingChangeSnapshot> {
        require(maxItems in 1..500)
        SyncSettingsProjector(
            database = database,
            secrets = secrets,
            userId = userId,
            secretsEpoch = state.readSecretsEpoch().coerceAtLeast(0L),
        ).refresh()
        // 若整批都是超限记录，继续取后面的合法条目，不能让坏数据饿死后续历史。
        while (true) {
        val snapshots = ArrayList<PendingChangeSnapshot>(maxItems)
        chat.getPendingSyncSessions(maxItems).forEach {
            snapshots += PendingChangeSnapshot(SyncPayloadMapper.session(it), SyncEntity.SESSIONS, it.id, it.updatedAt)
        }
        if (snapshots.size < maxItems) chat.getPendingSyncMessages(maxItems - snapshots.size).forEach {
            val session = chat.getStoredSession(it.sessionId) ?: return@forEach
            snapshots += fitMessageSnapshot(PendingChangeSnapshot(
                SyncPayloadMapper.message(it, session.uuid), SyncEntity.MESSAGES, it.id, it.updatedAt,
            ))
        }
        if (snapshots.size < maxItems) memory.getPendingSyncMemories(maxItems - snapshots.size).forEach {
            snapshots += PendingChangeSnapshot(SyncPayloadMapper.memory(it,
                sourceSessionUuid(it.sourceConversationId), sourceMessageUuid(it.sourceMessageId)),
                SyncEntity.MEMORIES, it.id, it.clientModifiedAt)
        }
        if (snapshots.size < maxItems) settings.getPendingUserSettings(maxItems - snapshots.size).forEach {
            snapshots += PendingChangeSnapshot(SyncPayloadMapper.userSetting(it), SyncEntity.USER_SETTINGS, it.uuid, it.updatedAt)
        }
        if (snapshots.size < maxItems) attachments.getPending(maxItems - snapshots.size).forEach {
            snapshots += PendingChangeSnapshot(SyncPayloadMapper.attachment(it), SyncEntity.ATTACHMENTS, it.id, it.updatedAt)
        }
        // 用序列化后的 UTF-8 字节计数，同时限制总请求和单条消息，不能仅按条数估算。
        val batch = ArrayList<PendingChangeSnapshot>()
        var bytes = 14 // {"changes":[]} 的固定开销加余量。
        for (snapshot in snapshots) {
            val size = syncJson.encodeToString(PushChange.serializer(), snapshot.change).toByteArray().size
            val payloadBytes = snapshot.change.payload.toString().toByteArray().size
            if (size > 1024 * 1024 - 14 || (snapshot.entity == SyncEntity.MESSAGES && payloadBytes > maxMessagePayloadBytes)) {
                reject(snapshot, "MESSAGE_PAYLOAD_TOO_LARGE")
                continue
            }
            if (bytes + size + 1 > 1024 * 1024) break
            batch += snapshot
            bytes += size + 1
        }
        if (batch.isNotEmpty() || snapshots.isEmpty()) return batch
        }
    }

    /**
     * 思考过程和统计字段属于可选补充字段。它们加起来超过服务器上限时，
     * 按顺序从网络 payload 移除，保留本地完整数据和消息正文；后续仍可补传未发送的字段。
     * 若核心正文本身就超限，继续走明确的 MESSAGE_PAYLOAD_TOO_LARGE 拒绝路径。
     */
    private fun fitMessageSnapshot(snapshot: PendingChangeSnapshot): PendingChangeSnapshot {
        if (snapshot.entity != SyncEntity.MESSAGES || snapshot.change.deleted) return snapshot
        var payload = snapshot.change.payload
        if (payload.toString().toByteArray().size <= maxMessagePayloadBytes) return snapshot
        for (field in optionalMessageFieldsForSize) {
            if (!payload.containsKey(field)) continue
            payload = JsonObject(payload.filterKeys { it != field })
            if (payload.toString().toByteArray().size <= maxMessagePayloadBytes) break
        }
        return snapshot.copy(change = snapshot.change.copy(payload = payload))
    }

    private fun fitMessageChange(change: PushChange): PushChange =
        fitMessageSnapshot(PendingChangeSnapshot(change, SyncEntity.MESSAGES, change.id, 0L)).change

    /** 来源记录允许先后乱序到达：cloud: 前缀携带 UUID，不能因为尚未拉到正文丢掉来源。 */
    private suspend fun sourceSessionUuid(id: String?): String? = id?.let {
        chat.getStoredSession(it)?.takeIf { s -> !s.isImageGeneration }?.uuid
            ?: it.takeIf { value -> value.startsWith("cloud:") }?.removePrefix("cloud:")
    }
    private suspend fun sourceMessageUuid(id: String?): String? = id?.let {
        chat.getStoredMessage(it, "")?.uuid ?: it.takeIf { value -> value.startsWith("cloud:") }?.removePrefix("cloud:")
    }

    override suspend fun reservePush(snapshots: List<PendingChangeSnapshot>) {
        chat.reserveUploadingMessages(snapshots.filter { it.entity == SyncEntity.MESSAGES }.map { it.change.id })
    }

    /** 以时间版本和实际白名单内容双重比对；同毫秒写入的新修改也不会被旧响应确认。 */
    private suspend fun stillCurrent(s: PendingChangeSnapshot): Boolean = when (s.entity) {
        SyncEntity.SESSIONS -> chat.getSessionByUuid(s.change.id)?.let {
            it.updatedAt == s.version && SyncPayloadMapper.session(it) == s.change
        } ?: false
        SyncEntity.MESSAGES -> chat.getMessageByUuid(s.change.id)?.let {
            val session = chat.getStoredSession(it.sessionId)
            it.updatedAt == s.version && session != null && fitMessageChange(SyncPayloadMapper.message(it, session.uuid)) == s.change
        } ?: false
        SyncEntity.MEMORIES -> memory.getByUuid(s.change.id)?.let {
            it.clientModifiedAt == s.version && SyncPayloadMapper.memory(it,
                sourceSessionUuid(it.sourceConversationId), sourceMessageUuid(it.sourceMessageId)) == s.change
        } ?: false
        SyncEntity.USER_SETTINGS -> settings.getUserSettingByUuid(s.change.id)?.let {
            it.updatedAt == s.version && SyncPayloadMapper.userSetting(it) == s.change
        } ?: false
        SyncEntity.ATTACHMENTS -> attachments.get(s.change.id)?.let {
            it.updatedAt == s.version && SyncPayloadMapper.attachment(it) == s.change
        } ?: false
    }

    override suspend fun applyPush(response: PushResponse, snapshots: List<PendingChangeSnapshot>) = database.withTransaction {
        // 缺结果、重复 index 或陌生 status 属于合同错误：保留待同步，不误标成功。
        require(response.results.size == snapshots.size && response.results.map { it.index }.toSet() == snapshots.indices.toSet())
        response.results.forEach { result ->
            val s = snapshots[result.index]
            require(result.status in setOf("accepted", "ignored", "unchanged", "rejected"))
            if (result.status == "rejected") {
                val code = requireNotNull(result.error).code
                if (code == "session_deleted" && s.entity == SyncEntity.MESSAGES) {
                    chat.discardMessageByUuid(s.change.id)
                    settings.deleteSetting(rejectionKey(s))
                } else if (code == "SECRETS_EPOCH_MISMATCH") {
                    // 保留 PENDING，下一轮先 pull 最新密钥代次或重新计算可选字段后再试。
                    // 这类错误不能进入永久失败列表，也不能让有效变更被整批阻塞。
                    settings.deleteSetting(rejectionKey(s))
                } else reject(s, code)
                return@forEach
            }
            require(result.id == s.change.id && result.entity == s.change.entity && result.serverSeq != null)
            val unchanged = stillCurrent(s)
            if (s.entity == SyncEntity.USER_SETTINGS && result.canonicalId != null) {
                validateUuid(result.canonicalId)
                settings.getUserSettingByUuid(s.change.id)?.let {
                    // canonical_id 必须采用，即使内容已经有新修改；新内容保持 PENDING。
                    settings.upsertUserSettings(listOf(it.copy(uuid = result.canonicalId)))
                }
                // 未发布版本可能还留有整表 conversation_groups；同步身份已经切换到
                // group_members 时同步更新兼容行，避免同一逻辑设置出现两个本地 UUID。
                if (s.change.payload["kind"]?.jsonPrimitive?.content == "group_members") {
                    val groupName = s.change.payload["value"]?.jsonObject?.get("groupName")?.jsonPrimitive?.contentOrNull
                    if (groupName != null) adoptLegacyGroupCanonical(groupName, result.canonicalId)
                }
            }
            if (unchanged) when (s.entity) {
                SyncEntity.SESSIONS -> chat.markSessionSyncedIfVersion(s.change.id, s.version)
                SyncEntity.MESSAGES -> chat.markMessageSyncedIfVersion(s.change.id, s.version)
                SyncEntity.MEMORIES -> memory.markSyncedIfVersion(s.change.id, s.version)
                SyncEntity.USER_SETTINGS -> settings.markUserSettingSyncedIfVersion(result.canonicalId ?: s.change.id, s.version)
                SyncEntity.ATTACHMENTS -> attachments.markSyncedIfVersion(s.change.id, s.version)
            }
            settings.deleteSetting(rejectionKey(s))
        }
    }

    private suspend fun reject(snapshot: PendingChangeSnapshot, code: String) = database.withTransaction {
        if (!stillCurrent(snapshot)) return@withTransaction
        when (snapshot.entity) {
            SyncEntity.SESSIONS -> chat.markSessionRejectedIfVersion(snapshot.change.id, snapshot.version)
            SyncEntity.MESSAGES -> chat.markMessageRejectedIfVersion(snapshot.change.id, snapshot.version)
            SyncEntity.MEMORIES -> memory.markRejectedIfVersion(snapshot.change.id, snapshot.version)
            SyncEntity.USER_SETTINGS -> settings.markUserSettingRejectedIfVersion(snapshot.change.id, snapshot.version)
            SyncEntity.ATTACHMENTS -> attachments.markRejectedIfVersion(snapshot.change.id, snapshot.version)
        }
        settings.insertSetting(SystemSettingEntity(rejectionKey(snapshot), code))
    }

    /** 一页记录和 next_cursor 原子提交；任何写入失败都不推进游标，不静默丢弃数据。 */
    override suspend fun applyPullPage(response: PullResponse) {
      database.withTransaction {
        response.changes.sortedBy { it.serverSeq }.forEach { record ->
            validateUuid(record.id)
            require(record.serverSeq > 0)
            when (record.entity) {
                "sessions" -> applySession(record)
                "messages" -> applyMessage(record)
                "memories" -> applyMemory(record)
                "user_settings" -> applyUserSetting(record)
                "attachments" -> applyAttachment(record)
                else -> error("未知同步实体")
            }
        }
        projectSettings()
        state.saveCursor(response.nextCursor, response.epoch)
      }
      if (response.changes.isNotEmpty()) SyncLocalChanges.notifyChanged()
    }

    /** 服务端裁剪未来时间；本地比较也用服务端 updated_at 作为上限，不能用设备当前时间。 */
    private fun modified(record: SyncRecord): Long {
        val value = SyncPayloadMapper.long(record.payload, "clientModifiedAt")
        require(value in 0..9007199254740991L)
        return record.updatedAt?.let { minOf(value, Instant.parse(it).toEpochMilli()) } ?: value
    }

    private suspend fun applySession(record: SyncRecord) {
        require(!SyncPayloadMapper.boolean(record.payload, "isImageGeneration"))
        val old = chat.getSessionByUuid(record.id)
        val version = modified(record)
        if (old?.deleted == true && !record.deleted) return
        // 删除会话是不可逆事实，无论本地改标题的时间都必须清理消息。
        if (!record.deleted && old?.syncStatus == SyncStatus.PENDING && old.updatedAt > version) return
        val candidate = ChatSessionEntity(
            id = old?.id ?: "cloud:${record.id}",
            creationTimestamp = SyncPayloadMapper.long(record.payload, "creationTimestamp", old?.creationTimestamp ?: 0),
            lastModifiedTimestamp = SyncPayloadMapper.long(record.payload, "lastModifiedTimestamp", old?.lastModifiedTimestamp ?: version),
            isImageGeneration = false,
            title = if (record.payload.containsKey("title")) SyncPayloadMapper.string(record.payload, "title") else old?.title,
            uuid = record.id, updatedAt = version, deleted = record.deleted, syncStatus = SyncStatus.SYNCED,
        )
        if (old == null) chat.insertRemoteSession(candidate) else chat.updateRemoteSession(candidate)
        if (record.deleted) {
            chat.deletePendingMessagesForConversation(candidate.id)
            chat.deleteDrafts(candidate.id, chat.getDrafts(candidate.id).map { it.message.id })
            chat.deleteMessagesForRemoteDeletedSession(candidate.id)
        }
    }

    private suspend fun applyMessage(record: SyncRecord) {
        if (record.deleted) {
            chat.markRemoteMessageDeleted(record.id)
            // 没有内容的墓碑也必须保留，避免乱序重放把消息复活。
            settings.insertSetting(SystemSettingEntity("sync_message_deleted:${record.id}", "true"))
            return
        }
        if (settings.getValue("sync_message_deleted:${record.id}") != null) return
        val sessionUuid = requireNotNull(SyncPayloadMapper.string(record.payload, "sessionId"))
        validateUuid(sessionUuid)
        val timestamp = SyncPayloadMapper.long(record.payload, "timestamp")
        val session = chat.getSessionByUuid(sessionUuid) ?: ChatSessionEntity(
            id = "cloud:$sessionUuid", creationTimestamp = timestamp, lastModifiedTimestamp = timestamp,
            isImageGeneration = false, uuid = sessionUuid, updatedAt = 0, syncStatus = SyncStatus.SYNCED,
        ).also { chat.insertRemoteSession(it) }
        if (session.deleted) return
        val sender = Sender.valueOf(requireNotNull(SyncPayloadMapper.string(record.payload, "sender")))
        require(sender in setOf(Sender.User, Sender.AI, Sender.System))
        val message = Message(
            id = "cloud:${record.id}", text = SyncPayloadMapper.string(record.payload, "text").orEmpty(), sender = sender,
            reasoning = SyncPayloadMapper.string(record.payload, "reasoning"), contentStarted = true,
            isError = SyncPayloadMapper.boolean(record.payload, "isError"), name = SyncPayloadMapper.string(record.payload, "name"),
            timestamp = timestamp, outputType = SyncPayloadMapper.string(record.payload, "outputType") ?: "general",
            modelName = SyncPayloadMapper.string(record.payload, "modelName"), providerName = SyncPayloadMapper.string(record.payload, "providerName"),
            conversationId = session.id,
        ).toEntity(session.id).copy(uuid = record.id, updatedAt = timestamp, syncStatus = SyncStatus.SYNCED, everSynced = true)
        chat.insertRemoteMessage(message)
    }

    private suspend fun applyMemory(record: SyncRecord) {
        val old = memory.getByUuid(record.id)
        val version = modified(record)
        if (old?.deleted == true && !record.deleted) return
        if (!record.deleted && old?.syncStatus == SyncStatus.PENDING && old.clientModifiedAt > version) return
        val candidate = MemoryEntity(
            id = old?.id ?: "cloud:${record.id}",
            category = SyncPayloadMapper.string(record.payload, "category") ?: old?.category.orEmpty(),
            content = SyncPayloadMapper.string(record.payload, "content") ?: old?.content.orEmpty(),
            origin = SyncPayloadMapper.string(record.payload, "origin") ?: old?.origin.orEmpty(),
            sourceConversationId = SyncPayloadMapper.string(record.payload, "sourceConversationId")?.let { validateUuid(it); chat.getSessionByUuid(it)?.id ?: "cloud:$it" },
            sourceMessageId = SyncPayloadMapper.string(record.payload, "sourceMessageId")?.let { validateUuid(it); chat.getMessageByUuid(it)?.id ?: "cloud:$it" },
            createdAt = SyncPayloadMapper.long(record.payload, "createdAt", old?.createdAt ?: version),
            updatedAt = version, enabled = SyncPayloadMapper.boolean(record.payload, "enabled", old?.enabled ?: true),
            uuid = record.id, syncUpdatedAt = version, deleted = record.deleted, syncStatus = SyncStatus.SYNCED,
        )
        if (old == null) memory.insertRemote(candidate) else memory.updateRemote(candidate)
    }

    private suspend fun applyUserSetting(record: SyncRecord) {
        val kind = requireNotNull(SyncPayloadMapper.string(record.payload, "kind"))
        val key = requireNotNull(SyncPayloadMapper.string(record.payload, "key"))
        require(kind in REGISTERED_SETTING_KINDS && key.toByteArray().size in 1..1024)
        val old = settings.getUserSetting(kind, key)
        val version = modified(record)
        if (kind == "group_members") {
            val groupName = record.payload["value"]?.jsonObject?.get("groupName")?.jsonPrimitive?.contentOrNull
            val legacy = groupName?.let { settings.getUserSetting("conversation_groups", it) }
            if (legacy != null && legacy.uuid == record.id) {
                // 旧版整表行已经是这条成员记录的同步身份，避免同 UUID 的兼容行被 REPLACE 掉。
                return
            }
            if (legacy?.syncStatus == SyncStatus.PENDING && legacy.updatedAt > version) {
                adoptLegacyGroupCanonical(groupName, record.id)
                return
            }
        }
        if (old?.syncStatus == SyncStatus.PENDING && old.updatedAt > version) {
            // 内容保留本地较新版本，但始终采用服务器逻辑键的 canonical id。
            if (kind == "group_members") {
                val groupName = record.payload["value"]?.jsonObject?.get("groupName")?.jsonPrimitive?.contentOrNull
                if (groupName != null) adoptLegacyGroupCanonical(groupName, record.id)
            } else {
                settings.upsertUserSettings(listOf(old.copy(uuid = record.id)))
            }
            return
        }
        val value = if (record.deleted) "{}" else requireNotNull(record.payload["value"]).toString()
        settings.upsertUserSettings(listOf(UserSettingEntity(
            kind, key, value, record.id, version, record.deleted, SyncStatus.SYNCED,
        )))
        if (record.deleted && kind == UserSettingKind.CONVERSATION_GROUPS) settings.deleteConversationGroup(key)
    }

    /**
     * 兼容尚未发布版本留下的整表分组设置。
     * 新版逐条 group_members 才是同步实体；旧行只保留本地内容和 canonical 身份，
     * 删除临时投影行，下一次投影会按旧行状态重新生成逐条记录，避免唯一 UUID 冲突。
     */
    private suspend fun adoptLegacyGroupCanonical(groupName: String, canonicalId: String) {
        settings.getUserSetting("conversation_groups", groupName)?.let { legacy ->
            settings.getAllUserSettings()
                .filter { it.kind == "group_members" && it.uuid == canonicalId }
                .forEach { settings.deleteUserSetting(it.kind, it.key) }
            settings.upsertUserSettings(listOf(legacy.copy(uuid = canonicalId)))
        }
    }

    /** 每页完成后重建展示投影，解决设置先于会话到达；不调用本地保存逻辑。 */
    private suspend fun projectSettings() {
        val synced = settings.getSyncableUserSettings()
        val textPinned = synced.filter { it.kind == "pinned_items" }.mapNotNull {
            chat.getSessionByUuid(it.key)?.takeIf { session -> !session.deleted }?.id
        }
        val imagePinned = settings.getPinnedIds(true)
        settings.replacePinnedItems(false, (textPinned + settings.getPinnedIds(false).filter {
            it.startsWith("cloud:")
        }).distinct().map { PinnedItemEntity(it, false) })
        val imageGroups = settings.getConversationGroups().filter { group ->
            group.conversationIds.any { id -> chat.getStoredSession(id)?.isImageGeneration == true }
        }
        val legacyGroups = synced.filter { it.kind == "conversation_groups" }.mapNotNull { setting ->
            val rawIds = runCatching { syncJson.parseToJsonElement(setting.value) }.getOrNull()
            val uuids = when (rawIds) {
                is JsonArray -> rawIds.mapNotNull { it.jsonPrimitive.contentOrNull }
                is JsonObject -> rawIds["conversationIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                else -> emptyList()
            }
            val ids = uuids.mapNotNull { uuid ->
                chat.getSessionByUuid(uuid)?.takeIf { !it.deleted }?.id
            }
            if (ids.isEmpty() && uuids.isNotEmpty()) null
            else ConversationGroupEntity(setting.key, ids)
        }
        val grouped = synced.filter { it.kind == "group_members" }.mapNotNull { setting ->
            val value = runCatching { syncJson.parseToJsonElement(setting.value).jsonObject }.getOrNull() ?: return@mapNotNull null
            val conversationUuid = value["conversationId"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val localId = chat.getSessionByUuid(conversationUuid)?.takeIf { !it.deleted }?.id ?: return@mapNotNull null
            setting.key.substringBeforeLast('.').let { encoded ->
                runCatching { String(java.util.Base64.getUrlDecoder().decode(encoded)) to localId }.getOrNull()
            }
        }.groupBy({ it.first }, { it.second })
        settings.replaceConversationGroups((imageGroups + legacyGroups + grouped.map { (name, ids) ->
            ConversationGroupEntity(name, ids.distinct())
        }).distinctBy { it.groupName })
    }

    private fun rejectionKey(snapshot: PendingChangeSnapshot) = "sync_rejected:${snapshot.change.entity}:${snapshot.change.id}"
    private fun validateUuid(id: String) { require(UUID.fromString(id).toString() == id.lowercase()) }

    private suspend fun applyAttachment(record: SyncRecord) {
        val old = attachments.get(record.id)
        if (record.deleted) {
            attachments.upsert(
                (old ?: SyncAttachmentEntity(record.id, "", "", "", "application/octet-stream", 1, "0".repeat(64), kind = "file"))
                    .copy(deleted = true, syncStatus = SyncStatus.SYNCED),
            )
            return
        }
        val payload = record.payload
        val sessionId = requireNotNull(SyncPayloadMapper.string(payload, "sessionId"))
        val messageId = requireNotNull(SyncPayloadMapper.string(payload, "messageId"))
        validateUuid(sessionId); validateUuid(messageId)
        val size = SyncPayloadMapper.long(payload, "size")
        val sha256 = requireNotNull(SyncPayloadMapper.string(payload, "sha256"))
        require(size >= 1 && sha256.matches(Regex("[0-9a-f]{64}")))
        attachments.upsert(SyncAttachmentEntity(
            id = record.id,
            sessionId = sessionId,
            messageId = messageId,
            name = SyncPayloadMapper.string(payload, "name").orEmpty(),
            mime = SyncPayloadMapper.string(payload, "mime").orEmpty(),
            size = size,
            sha256 = sha256,
            fileId = payload["fileId"]?.jsonPrimitive?.contentOrNull,
            kind = SyncPayloadMapper.string(payload, "kind") ?: "file",
            position = SyncPayloadMapper.long(payload, "position").toInt().coerceAtLeast(0),
            updatedAt = modified(record),
            syncStatus = SyncStatus.SYNCED,
        ))
    }

    private companion object {
        val REGISTERED_SETTING_KINDS = setOf(
            "api_configs", "selected_models", "web_search_configs", "voice_configs",
            "voice_llm_platforms", "voice_custom_models", "mcp_servers", "skills",
            "skill_variables", "computers", "computer_workspaces", "conversation_settings",
            "memory_switches", "group_members", "pinned_items", "crypto_keys",
            "conversation_groups", // 仅兼容未发布的旧本地数据，新的记录使用 group_members。
        )
    }
}

