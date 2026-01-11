package com.android.everytalk.data.database.daos

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.database.entities.MessageEntity

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
        insertSession(session)
        clearMessagesForSession(session.id)
        insertMessages(messages)
    }
    
    @Transaction
    suspend fun saveLastOpenSession(session: ChatSessionEntity, messages: List<MessageEntity>) {
        saveSessionWithMessages(session, messages)
    }

    @Query("SELECT * FROM chat_sessions WHERE isImageGeneration = :isImageGen ORDER BY lastModifiedTimestamp DESC")
    suspend fun getAllSessions(isImageGen: Boolean): List<ChatSessionEntity>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesForSession(sessionId: String): List<MessageEntity>
    
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSessionPaged(sessionId: String): PagingSource<Int, MessageEntity>
    
    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun getMessageCountForSession(sessionId: String): Int
    
    @Query("DELETE FROM chat_sessions WHERE isImageGeneration = :isImageGen")
    suspend fun clearAllSessions(isImageGen: Boolean)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)
}