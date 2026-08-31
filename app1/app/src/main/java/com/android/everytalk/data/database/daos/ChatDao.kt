package com.android.everytalk.data.database.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.android.everytalk.data.DataClass.MessageContentPart
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.database.entities.MessageEntity
import com.android.everytalk.data.database.entities.MessageStorageState
import com.android.everytalk.data.database.entities.LightweightMessageRow
import com.android.everytalk.data.database.entities.PendingMessageEntity
import com.android.everytalk.data.database.entities.RawMessageRow
import com.android.everytalk.models.SelectedMediaItem
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Upsert
    suspend fun insertSession(session: ChatSessionEntity)

    @Upsert
    suspend fun upsertMessages(messages: List<MessageEntity>)

    @Query("SELECT id, storageFingerprint FROM messages WHERE sessionId = :sessionId")
    suspend fun getMessageStorageStates(sessionId: String): List<MessageStorageState>

    @Query("DELETE FROM messages WHERE sessionId = :sessionId AND id IN (:messageIds)")
    suspend fun deleteMessagesByIds(sessionId: String, messageIds: List<String>)

    @Transaction
    suspend fun saveSessionWithMessages(
        session: ChatSessionEntity,
        messages: List<MessageEntity>,
        sessionChanged: Boolean = true,
    ) {
        // 先读取 ID 和摘要，只写新增或真实变化的消息。长会话追加一条消息时不会重写全部历史。
        val storedStates = getMessageStorageStates(session.id).associate { it.id to it.storageFingerprint }
        val incomingIds = messages.mapTo(hashSetOf()) { it.id }
        val changedMessages = messages.filter { storedStates[it.id] != it.storageFingerprint }
        val removedIds = storedStates.keys.filterNot(incomingIds::contains)

        // @Transaction 保证会话、变化消息和删除结果一起提交，任何一步失败都会保留旧数据。
        if (sessionChanged) insertSession(session)
        if (changedMessages.isNotEmpty()) upsertMessages(changedMessages)
        if (removedIds.isNotEmpty()) deleteMessagesByIds(session.id, removedIds)
    }
    
    @Query("SELECT * FROM chat_sessions WHERE isImageGeneration = :isImageGen ORDER BY lastModifiedTimestamp DESC")
    suspend fun getAllSessions(isImageGen: Boolean): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSession(sessionId: String): ChatSessionEntity?

    @Query("UPDATE chat_sessions SET title = :title WHERE id = :sessionId")
    suspend fun updateSessionTitle(sessionId: String, title: String)

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesForSession(sessionId: String): List<MessageEntity>

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
               contextCompressionState AS contextCompressionStateJson
        FROM messages
        WHERE sessionId IN (
            SELECT id FROM chat_sessions WHERE isImageGeneration = :isImageGen
        )
        ORDER BY sessionId ASC, timestamp ASC
        """
    )
    suspend fun getRawMessagesForMode(isImageGen: Boolean): List<RawMessageRow>

    @Query(
        """
        SELECT id, sessionId, text, sender, timestamp, isPlaceholderName
        FROM messages
        WHERE sessionId IN (
            SELECT id FROM chat_sessions WHERE isImageGeneration = :isImageGen
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

    @Query("DELETE FROM pending_messages WHERE id = :id AND status = 'PENDING'")
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

    @Query("DELETE FROM chat_sessions WHERE isImageGeneration = :isImageGen")
    suspend fun clearAllSessionRows(isImageGen: Boolean)

    @Transaction
    suspend fun clearAllSessions(isImageGen: Boolean) {
        if (!isImageGen) deleteAllPendingMessages()
        clearAllSessionRows(isImageGen)
    }

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSessionRow(sessionId: String)

    @Transaction
    suspend fun deleteSession(sessionId: String) {
        deletePendingMessagesForConversation(sessionId)
        deleteSessionRow(sessionId)
    }
}
