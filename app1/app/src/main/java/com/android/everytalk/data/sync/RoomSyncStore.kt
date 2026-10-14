package com.android.everytalk.data.sync

import androidx.room.withTransaction
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID

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
internal class RoomSyncStore(private val database: AppDatabase) : SyncStore {
    private val chat = database.chatDao()
    private val memory = database.memoryDao()
    private val settings = database.settingsDao()
    private val state = SyncStateStore(database)

    override suspend fun pendingBatch(maxItems: Int): List<PendingChangeSnapshot> {
        require(maxItems in 1..500)
        // 若整批都是超限记录，继续取后面的合法条目，不能让坏数据饿死后续历史。
        while (true) {
        val snapshots = ArrayList<PendingChangeSnapshot>(maxItems)
        chat.getPendingSyncSessions(maxItems).forEach {
            snapshots += PendingChangeSnapshot(SyncPayloadMapper.session(it), SyncEntity.SESSIONS, it.id, it.updatedAt)
        }
        if (snapshots.size < maxItems) chat.getPendingSyncMessages(maxItems - snapshots.size).forEach {
            val session = chat.getStoredSession(it.sessionId) ?: return@forEach
            snapshots += PendingChangeSnapshot(SyncPayloadMapper.message(it, session.uuid), SyncEntity.MESSAGES, it.id, it.updatedAt)
        }
        if (snapshots.size < maxItems) memory.getPendingSyncMemories(maxItems - snapshots.size).forEach {
            snapshots += PendingChangeSnapshot(SyncPayloadMapper.memory(it,
                sourceSessionUuid(it.sourceConversationId), sourceMessageUuid(it.sourceMessageId)),
                SyncEntity.MEMORIES, it.id, it.clientModifiedAt)
        }
        if (snapshots.size < maxItems) settings.getPendingUserSettings(maxItems - snapshots.size).forEach {
            snapshots += PendingChangeSnapshot(SyncPayloadMapper.userSetting(it), SyncEntity.USER_SETTINGS, it.uuid, it.updatedAt)
        }
        // 用序列化后的 UTF-8 字节计数，同时限制总请求和单条消息，不能仅按条数估算。
        val batch = ArrayList<PendingChangeSnapshot>()
        var bytes = 14 // {"changes":[]} 的固定开销加余量。
        for (snapshot in snapshots) {
            val size = syncJson.encodeToString(PushChange.serializer(), snapshot.change).toByteArray().size
            val payloadBytes = snapshot.change.payload.toString().toByteArray().size
            if (size > 1024 * 1024 - 14 || (snapshot.entity == SyncEntity.MESSAGES && payloadBytes > 65536)) {
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
            it.updatedAt == s.version && session != null && SyncPayloadMapper.message(it, session.uuid) == s.change
        } ?: false
        SyncEntity.MEMORIES -> memory.getByUuid(s.change.id)?.let {
            it.clientModifiedAt == s.version && SyncPayloadMapper.memory(it,
                sourceSessionUuid(it.sourceConversationId), sourceMessageUuid(it.sourceMessageId)) == s.change
        } ?: false
        SyncEntity.USER_SETTINGS -> settings.getUserSettingByUuid(s.change.id)?.let {
            it.updatedAt == s.version && SyncPayloadMapper.userSetting(it) == s.change
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
            }
            if (unchanged) when (s.entity) {
                SyncEntity.SESSIONS -> chat.markSessionSyncedIfVersion(s.change.id, s.version)
                SyncEntity.MESSAGES -> chat.markMessageSyncedIfVersion(s.change.id, s.version)
                SyncEntity.MEMORIES -> memory.markSyncedIfVersion(s.change.id, s.version)
                SyncEntity.USER_SETTINGS -> settings.markUserSettingSyncedIfVersion(result.canonicalId ?: s.change.id, s.version)
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
        require(kind in setOf(UserSettingKind.CONVERSATION_GROUPS, UserSettingKind.PINNED_ITEMS) && key.toByteArray().size in 1..512)
        val old = settings.getUserSetting(kind, key)
        val version = modified(record)
        if (old?.syncStatus == SyncStatus.PENDING && old.updatedAt > version) {
            // 内容保留本地较新版本，但始终采用服务器逻辑键的 canonical id。
            settings.upsertUserSettings(listOf(old.copy(uuid = record.id)))
            return
        }
        val value = if (record.deleted) "[]" else syncJson.encodeToString(ListSerializer(String.serializer()), SyncPayloadMapper.conversationIds(record.payload))
        settings.upsertUserSettings(listOf(UserSettingEntity(
            kind, key, value, record.id, version, record.deleted, SyncStatus.SYNCED,
        )))
        if (record.deleted && kind == UserSettingKind.CONVERSATION_GROUPS) settings.deleteConversationGroup(key)
        if (record.deleted && kind == UserSettingKind.PINNED_ITEMS) settings.clearPinnedItems(false)
    }

    /** 每页完成后重建展示投影，解决设置先于会话到达；不调用本地保存逻辑。 */
    private suspend fun projectSettings() {
        settings.getSyncableUserSettings().forEach { setting ->
            val uuidIds = syncJson.decodeFromString(ListSerializer(String.serializer()), setting.value)
            val ids = uuidIds.mapNotNull { chat.getSessionByUuid(it)?.takeIf { s -> !s.deleted }?.id }
            if (setting.kind == UserSettingKind.CONVERSATION_GROUPS) {
                val imageIds = settings.getConversationGroups().find { it.groupName == setting.key }?.conversationIds.orEmpty()
                    .filter { chat.getStoredSession(it)?.isImageGeneration == true }
                settings.insertConversationGroups(listOf(ConversationGroupEntity(setting.key, (imageIds + ids).distinct())))
            } else settings.replacePinnedItems(false, ids.map { PinnedItemEntity(it, false) })
        }
    }

    private fun rejectionKey(snapshot: PendingChangeSnapshot) = "sync_rejected:${snapshot.change.entity}:${snapshot.change.id}"
    private fun validateUuid(id: String) { require(UUID.fromString(id).toString() == id.lowercase()) }
}

