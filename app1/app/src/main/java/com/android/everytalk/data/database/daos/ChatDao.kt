package com.android.everytalk.data.database.daos

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.database.entities.MessageEntity
import com.android.everytalk.data.database.entities.MessageStorageState
import com.android.everytalk.data.database.entities.LightweightMessageRow
import com.android.everytalk.data.database.entities.RawMessageRow

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
    
    @Query("DELETE FROM chat_sessions WHERE isImageGeneration = :isImageGen")
    suspend fun clearAllSessions(isImageGen: Boolean)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)
}
