package com.android.everytalk.data.database.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.database.entities.MessageEntity
import com.android.everytalk.data.database.entities.RawMessageRow

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun clearMessagesForSession(sessionId: String)

    @Transaction
    suspend fun saveSessionWithMessages(session: ChatSessionEntity, messages: List<MessageEntity>) {
        // 数据库保存会话和消息。
        // @Transaction 保证下面三步要么全部成功，要么全部回滚，避免会话已更新但消息只保存一半。
        insertSession(session)
        // 先清空该会话旧消息，再写入当前完整消息列表，保证本地历史记录和界面状态一致。
        clearMessagesForSession(session.id)
        insertMessages(messages)
    }
    
    @Query("SELECT * FROM chat_sessions WHERE isImageGeneration = :isImageGen ORDER BY lastModifiedTimestamp DESC")
    suspend fun getAllSessions(isImageGen: Boolean): List<ChatSessionEntity>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesForSession(sessionId: String): List<MessageEntity>

    @Query(
        """
        SELECT id, sessionId, text, sender, reasoning, contentStarted, isError, name, timestamp,
               isPlaceholderName, webSearchResults AS webSearchResultsJson,
               currentWebSearchStage, imageUrls AS imageUrlsJson, attachments AS attachmentsJson,
               outputType, parts AS partsJson, executionStatus, modelName, providerName
        FROM messages
        WHERE sessionId IN (
            SELECT id FROM chat_sessions WHERE isImageGeneration = :isImageGen
        )
        ORDER BY sessionId ASC, timestamp ASC
        """
    )
    suspend fun getRawMessagesForMode(isImageGen: Boolean): List<RawMessageRow>
    
    @Query("DELETE FROM chat_sessions WHERE isImageGeneration = :isImageGen")
    suspend fun clearAllSessions(isImageGen: Boolean)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)
}
