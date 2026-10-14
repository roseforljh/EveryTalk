package com.android.everytalk.data.database.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.android.everytalk.BuildConfig
import com.android.everytalk.data.DataClass.MessageContentPart
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.database.entities.MessageEntity
import com.android.everytalk.data.database.entities.MessageStorageState
import com.android.everytalk.data.database.entities.LightweightMessageRow
import com.android.everytalk.data.database.entities.PendingMessageEntity
import com.android.everytalk.data.database.entities.RawMessageRow
import com.android.everytalk.data.database.entities.toEntity
import com.android.everytalk.data.database.entities.toMessage
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.data.database.entities.MessageDraftEntity
import com.android.everytalk.data.database.entities.SyncStatus
import com.android.everytalk.data.database.entities.isSyncableTextSession
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Upsert
    suspend fun writeSession(session: ChatSessionEntity)

    @Upsert
    suspend fun writeMessages(messages: List<MessageEntity>)

    @Query("SELECT id, storageFingerprint, uuid, updated_at, deleted, sync_status, ever_synced FROM messages WHERE sessionId = :sessionId")
    suspend fun getMessageStorageStates(sessionId: String): List<MessageStorageState>

    /** 内部读取包含删除记录，防止 Upsert 把旧快照复活。页面只能使用过滤查询。 */
    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId")
    suspend fun getStoredSession(sessionId: String): ChatSessionEntity?

    @Query("SELECT * FROM messages WHERE id = :id OR uuid = :uuid LIMIT 1")
    suspend fun getStoredMessage(id: String, uuid: String): MessageEntity?

    @Upsert
    suspend fun writeDrafts(drafts: List<MessageDraftEntity>)

    @Query("SELECT * FROM message_drafts WHERE sessionId = :sessionId")
    suspend fun getDrafts(sessionId: String): List<MessageDraftEntity>

    @Query("SELECT * FROM message_drafts WHERE sessionId = :sessionId AND id = :messageId LIMIT 1")
    suspend fun getDraft(sessionId: String, messageId: String): MessageDraftEntity?

    @Query("SELECT * FROM message_drafts")
    suspend fun getAllDrafts(): List<MessageDraftEntity>

    @Query("DELETE FROM message_drafts WHERE sessionId = :sessionId AND id IN (:messageIds)")
    suspend fun deleteDrafts(sessionId: String, messageIds: List<String>)

    /** 未上传过的消息没有服务器删除义务，直接清理；曾上传的消息留下不可逆删除标记。 */
    @Query("DELETE FROM messages WHERE sessionId = :sessionId AND id IN (:messageIds) AND ever_synced = 0 AND sync_status != 'SYNCED'")
    suspend fun deleteNeverSyncedMessages(sessionId: String, messageIds: List<String>)

    @Query("UPDATE messages SET deleted = 1, updated_at = :now, ever_synced = 1, sync_status = 'PENDING' WHERE sessionId = :sessionId AND id IN (:messageIds) AND deleted = 0 AND (ever_synced = 1 OR sync_status = 'SYNCED')")
    suspend fun markSyncedMessagesDeleted(sessionId: String, messageIds: List<String>, now: Long)

    @Transaction
    suspend fun deleteMessagesByIds(sessionId: String, messageIds: List<String>) {
        deleteNeverSyncedMessages(sessionId, messageIds)
        markSyncedMessagesDeleted(sessionId, messageIds, System.currentTimeMillis())
        deleteDrafts(sessionId, messageIds)
    }

    /** 更新会话时保留 UUID 与删除状态。消息身份变化不应重建会话。 */
    @Transaction
    suspend fun insertSession(session: ChatSessionEntity) {
        val stored = getStoredSession(session.id)
        if (stored?.deleted == true) return
        val changed = stored == null || stored.title != session.title || stored.lastModifiedTimestamp != session.lastModifiedTimestamp
        if (changed) writeSession(session.copy(
            uuid = stored?.uuid ?: session.uuid,
            updatedAt = System.currentTimeMillis(),
            syncStatus = if (isSyncableTextSession(session.id, session.isImageGeneration)) SyncStatus.PENDING else SyncStatus.LOCAL_ONLY,
        ))
    }

    /**
     * 普通保存、后台附件补写和草稿定稿共用一个正式消息边界。
     * 变化记录才读取完整旧行，保留原 UUID、同步状态和“曾上传”信息；未同步字段可以更新。
     */
    @Transaction
    suspend fun upsertMessages(messages: List<MessageEntity>, debug: Boolean = BuildConfig.DEBUG) {
        messages.forEach { incoming ->
            val session = getStoredSession(incoming.sessionId) ?: error("消息缺少所属会话")
            if (session.deleted) return@forEach
            val stored = getStoredMessage(incoming.id, incoming.uuid)
            if (stored != null) {
                // last-open 是设备恢复快照而非正式历史，允许更新但仍不可复活删除记录。
                val isSnapshot = session.id == "last_open_chat_v1" || session.id == "last_open_image_generation_v1"
                if (!isSnapshot && !acceptFormalMessageUpdate(stored, incoming, debug)) return@forEach
                if (isSnapshot && stored.deleted) return@forEach
                if (stored.storageFingerprint != incoming.storageFingerprint) writeMessages(listOf(incoming.copy(
                    id = stored.id, uuid = stored.uuid, syncStatus = stored.syncStatus, updatedAt = stored.updatedAt,
                    everSynced = stored.everSynced || stored.syncStatus == SyncStatus.SYNCED,
                )))
            } else {
                val draft = getDraft(incoming.sessionId, incoming.id)?.message
                writeMessages(listOf(incoming.copy(
                    uuid = draft?.uuid ?: incoming.uuid,
                    updatedAt = System.currentTimeMillis(),
                    syncStatus = if (isSyncableTextSession(session.id, session.isImageGeneration)) SyncStatus.PENDING else SyncStatus.LOCAL_ONLY,
                )))
            }
            deleteDrafts(incoming.sessionId, listOf(incoming.id))
        }
    }

    @Transaction
    suspend fun saveSessionWithMessages(
        session: ChatSessionEntity,
        messages: List<MessageEntity>,
        sessionChanged: Boolean = true,
        draftIds: Set<String> = emptySet(),
        debug: Boolean = BuildConfig.DEBUG,
    ) {
        // 先读取 ID 和摘要，只写新增或真实变化的消息。长会话追加一条消息时不会重写全部历史。
        if (getStoredSession(session.id)?.deleted == true) return
        val storedStates = getMessageStorageStates(session.id).associateBy { it.id }
        val incomingIds = messages.mapTo(hashSetOf()) { it.id }
        val changedMessages = messages.filter {
            (it.id !in draftIds || storedStates[it.id] != null) && storedStates[it.id]?.storageFingerprint != it.storageFingerprint
        }
        val removedIds = storedStates.values.filter { !it.deleted && it.id !in incomingIds }.map { it.id }

        // @Transaction 保证会话、变化消息和删除结果一起提交，任何一步失败都会保留旧数据。
        if (sessionChanged || getStoredSession(session.id) == null) insertSession(session)
        if (changedMessages.isNotEmpty()) upsertMessages(changedMessages, debug)
        if (removedIds.isNotEmpty()) deleteMessagesByIds(session.id, removedIds)
        val existingDrafts = getDrafts(session.id).associateBy { it.message.id }
        val drafts = messages.filter { it.id in draftIds && storedStates[it.id] == null }.map { message ->
            MessageDraftEntity(message.copy(uuid = existingDrafts[message.id]?.message?.uuid ?: message.uuid, syncStatus = SyncStatus.LOCAL_ONLY))
        }
        if (drafts.isNotEmpty()) writeDrafts(drafts)
        deleteDrafts(session.id, existingDrafts.keys.filterNot(incomingIds::contains))
    }
    
    @Query("SELECT * FROM chat_sessions WHERE deleted = 0 AND isImageGeneration = :isImageGen ORDER BY lastModifiedTimestamp DESC")
    suspend fun getAllSessions(isImageGen: Boolean): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions WHERE deleted = 0 AND id = :sessionId LIMIT 1")
    suspend fun getSession(sessionId: String): ChatSessionEntity?

    @Query("UPDATE chat_sessions SET title = :title, updated_at = :now, sync_status = CASE WHEN isImageGeneration = 0 AND id NOT IN ('last_open_chat_v1', 'last_open_image_generation_v1') THEN 'PENDING' ELSE 'LOCAL_ONLY' END WHERE id = :sessionId AND deleted = 0")
    suspend fun updateSessionTitle(sessionId: String, title: String, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM (SELECT * FROM messages WHERE deleted = 0 UNION ALL SELECT * FROM message_drafts) WHERE sessionId = :sessionId AND sessionId IN (SELECT id FROM chat_sessions WHERE deleted = 0) ORDER BY timestamp ASC")
    suspend fun getMessagesForSession(sessionId: String): List<MessageEntity>

    @Query("SELECT * FROM (SELECT * FROM messages WHERE deleted = 0 UNION ALL SELECT * FROM message_drafts) WHERE sessionId = :sessionId AND id = :messageId AND sessionId IN (SELECT id FROM chat_sessions WHERE deleted = 0) LIMIT 1")
    suspend fun getMessage(sessionId: String, messageId: String): MessageEntity?

    /** 后台 Agent 下载完成时，只更新原消息的附件，避免依赖当前打开的会话。 */
    @Transaction
    suspend fun appendDownloadedAttachment(sessionId: String, messageId: String, attachment: SelectedMediaItem.GenericFile): Boolean {
        val message = getMessage(sessionId, messageId) ?: return false
        if (message.attachments.none { it.id == attachment.id }) {
            val updated = message.toMessage().copy(attachments = message.attachments + attachment).toEntity(sessionId)
                .copy(uuid = message.uuid)
            if (getDraft(sessionId, messageId) != null) writeDrafts(listOf(MessageDraftEntity(updated)))
            else upsertMessages(listOf(updated))
        }
        return true
    }

    /** 启动加载历史前调用一次：有内容的残留草稿定稿为未完成回复，空占位清理，不留孤儿。 */
    @Transaction
    suspend fun recoverInterruptedDrafts() {
        getAllDrafts().forEach { draft ->
            val message = draft.message
            val session = getStoredSession(message.sessionId)
            val hasContent = message.text.isNotBlank() || !message.reasoning.isNullOrBlank() ||
                message.attachments.isNotEmpty() || !message.imageUrls.isNullOrEmpty()
            if (session != null && !session.deleted && hasContent && getStoredMessage(message.id, message.uuid) == null) {
                val recovered = message.toMessage().copy(
                    isIncomplete = true, executionStatus = "INTERRUPTED", executionFinishedAt = System.currentTimeMillis(),
                ).toEntity(message.sessionId).copy(uuid = message.uuid)
                upsertMessages(listOf(recovered))
            }
            deleteDrafts(message.sessionId, listOf(message.id))
        }
    }

    /** 仅用于文字同步白名单；会话删除只上传会话变更，不能再发送其子消息。 */
    @Query("SELECT * FROM messages WHERE sync_status = 'PENDING' AND sender IN ('User', 'AI', 'System') AND isPlaceholderName = 0 AND sessionId IN (SELECT id FROM chat_sessions WHERE deleted = 0 AND isImageGeneration = 0 AND id NOT IN ('last_open_chat_v1', 'last_open_image_generation_v1'))")
    suspend fun getPendingSyncMessages(): List<MessageEntity>

    @Query("SELECT * FROM chat_sessions WHERE sync_status = 'PENDING' AND isImageGeneration = 0 AND id NOT IN ('last_open_chat_v1', 'last_open_image_generation_v1')")
    suspend fun getPendingSyncSessions(): List<ChatSessionEntity>

    /** 服务器确认后持久化历史事实；只有当前未删除内容才可从 PENDING 变为 SYNCED。 */
    @Query("UPDATE messages SET ever_synced = 1, sync_status = CASE WHEN deleted = 0 THEN 'SYNCED' ELSE sync_status END WHERE uuid IN (:uuids)")
    suspend fun markMessagesSynced(uuids: List<String>)

    @Query(
        """
        SELECT id, sessionId, text, contentParts AS contentPartsJson, sender, reasoning, contentStarted, isError, name, timestamp,
               isPlaceholderName, webSearchResults AS webSearchResultsJson,
               currentWebSearchStage, imageUrls AS imageUrlsJson, attachments AS attachmentsJson,
               outputType, parts AS partsJson, executionStatus,
               executionSteps AS executionStepsJson,
               executionTrace AS executionTraceJson,
               executionFinishedAt,
               enabledToolIds AS enabledToolIdsJson, computerIdSnapshot, workspaceIdSnapshot,
               modelName, providerName,
               tokenUsage AS tokenUsageJson,
               contextUsageSnapshot AS contextUsageSnapshotJson,
               contextCompressionState AS contextCompressionStateJson, is_incomplete
        FROM (SELECT * FROM messages WHERE deleted = 0 UNION ALL SELECT * FROM message_drafts)
        WHERE sessionId IN (
            SELECT id FROM chat_sessions WHERE deleted = 0 AND isImageGeneration = :isImageGen
        )
        ORDER BY sessionId ASC, timestamp ASC
        """
    )
    suspend fun getRawMessagesForMode(isImageGen: Boolean): List<RawMessageRow>

    @Query(
        """
        SELECT id, sessionId, text, sender, timestamp, isPlaceholderName
        FROM (SELECT * FROM messages WHERE deleted = 0 UNION ALL SELECT * FROM message_drafts)
        WHERE sessionId IN (
            SELECT id FROM chat_sessions WHERE deleted = 0 AND isImageGeneration = :isImageGen
        )
        ORDER BY sessionId ASC, timestamp ASC
        """
    )
    suspend fun getLightweightMessagesForMode(isImageGen: Boolean): List<LightweightMessageRow>

    @Query(
        "SELECT * FROM pending_messages WHERE conversationId = :conversationId " +
            "ORDER BY queuePosition ASC, id ASC",
    )
    fun observePendingMessages(conversationId: String): Flow<List<PendingMessageEntity>>

    @Query(
        "SELECT COALESCE(MAX(queuePosition), -1) + 1 FROM pending_messages " +
            "WHERE conversationId = :conversationId",
    )
    suspend fun nextPendingQueuePosition(conversationId: String): Long

    @Insert
    suspend fun insertPendingMessage(message: PendingMessageEntity)

    /** 同一事务内分配位置并插入，快速连续发送也不会拿到相同顺序。 */
    @Transaction
    suspend fun enqueuePendingMessage(message: PendingMessageEntity) {
        insertPendingMessage(
            message.copy(queuePosition = nextPendingQueuePosition(message.conversationId)),
        )
    }

    @Query(
        """
        UPDATE pending_messages
        SET content = :content,
            composerText = :composerText,
            contentParts = :contentParts,
            attachments = :attachments,
            updatedAt = :updatedAt,
            status = 'PENDING'
        WHERE id = :id AND status = 'EDITING'
        """,
    )
    suspend fun updatePendingMessage(
        id: String,
        content: String,
        composerText: String,
        contentParts: List<MessageContentPart>,
        attachments: List<SelectedMediaItem>,
        updatedAt: Long,
    ): Int

    @Query("UPDATE pending_messages SET status = 'EDITING' WHERE id = :id AND status = 'PENDING'")
    suspend fun detachPendingMessageForEdit(id: String): Int

    @Query("UPDATE pending_messages SET status = 'PENDING' WHERE id = :id AND status = 'EDITING'")
    suspend fun cancelPendingMessageEdit(id: String): Int

    @Query("DELETE FROM pending_messages WHERE id = :id AND status IN ('PENDING', 'EDITING')")
    suspend fun deletePendingMessage(id: String): Int

    @Query("UPDATE pending_messages SET status = 'DISPATCHING' WHERE id = :id AND status = 'PENDING'")
    suspend fun claimPendingMessage(id: String): Int

    @Query("DELETE FROM pending_messages WHERE id = :id AND status = 'DISPATCHING'")
    suspend fun finishPendingDispatch(id: String): Int

    @Query("UPDATE pending_messages SET status = 'PENDING' WHERE id = :id AND status = 'DISPATCHING'")
    suspend fun restorePendingDispatch(id: String): Int

    @Query("DELETE FROM pending_messages WHERE status = 'DISPATCHING' AND id IN (SELECT id FROM messages)")
    suspend fun deletePersistedPendingDispatches()

    // 进程恢复时只能回滚未完成的派发。EDITING 是 Composer 的持久状态，必须保留，
    // 这样控制器才能恢复原 ID、原位置和输入内容，避免编辑项重新进入可发送队列。
    @Query("UPDATE pending_messages SET status = 'PENDING' WHERE status = 'DISPATCHING'")
    suspend fun restoreInterruptedPendingDispatches()

    /** 正式消息已落库的记录直接清掉，其余中断记录恢复为待发送。 */
    @Transaction
    suspend fun recoverPendingDispatches() {
        deletePersistedPendingDispatches()
        restoreInterruptedPendingDispatches()
    }

    @Query("UPDATE pending_messages SET conversationId = :newId WHERE conversationId = :oldId")
    suspend fun migratePendingConversationId(oldId: String, newId: String)

    @Query("DELETE FROM pending_messages WHERE conversationId = :conversationId")
    suspend fun deletePendingMessagesForConversation(conversationId: String)

    @Query("DELETE FROM pending_messages")
    suspend fun deleteAllPendingMessages()

    @Query("UPDATE chat_sessions SET deleted = 1, updated_at = :now, sync_status = CASE WHEN isImageGeneration = 0 AND id NOT IN ('last_open_chat_v1', 'last_open_image_generation_v1') THEN 'PENDING' ELSE 'LOCAL_ONLY' END WHERE isImageGeneration = :isImageGen AND deleted = 0")
    suspend fun clearAllSessionRows(isImageGen: Boolean, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM message_drafts WHERE sessionId IN (SELECT id FROM chat_sessions WHERE isImageGeneration = :isImageGen)")
    suspend fun clearDraftsForMode(isImageGen: Boolean)

    /** 已删除会话不再需要未上传的消息；曾同步的消息不逐条打删除标记。 */
    @Query("DELETE FROM messages WHERE sessionId IN (SELECT id FROM chat_sessions WHERE isImageGeneration = :isImageGen) AND ever_synced = 0 AND sync_status != 'SYNCED'")
    suspend fun clearNeverSyncedMessagesForMode(isImageGen: Boolean)

    @Transaction
    suspend fun clearAllSessions(isImageGen: Boolean) {
        if (!isImageGen) deleteAllPendingMessages()
        clearDraftsForMode(isImageGen)
        clearNeverSyncedMessagesForMode(isImageGen)
        clearAllSessionRows(isImageGen)
    }

    @Query("UPDATE chat_sessions SET deleted = 1, updated_at = :now, sync_status = CASE WHEN isImageGeneration = 0 AND id NOT IN ('last_open_chat_v1', 'last_open_image_generation_v1') THEN 'PENDING' ELSE 'LOCAL_ONLY' END WHERE id = :sessionId AND deleted = 0")
    suspend fun deleteSessionRow(sessionId: String, now: Long = System.currentTimeMillis())

    @Transaction
    suspend fun deleteSession(sessionId: String) {
        deletePendingMessagesForConversation(sessionId)
        deleteDrafts(sessionId, getDrafts(sessionId).map { it.message.id })
        deleteNeverSyncedMessages(sessionId, getMessageStorageStates(sessionId).map { it.id })
        deleteSessionRow(sessionId)
    }

    /** last-open 是设备缓存，清理时不能留下同固定 ID 的墓碑，否则下一次无法保存快照。 */
    @Query("DELETE FROM chat_sessions WHERE id = :sessionId AND id IN ('last_open_chat_v1', 'last_open_image_generation_v1')")
    suspend fun clearLastOpenSnapshot(sessionId: String)
}
