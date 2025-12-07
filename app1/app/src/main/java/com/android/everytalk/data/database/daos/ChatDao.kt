package com.android.everytalk.data.database.daos

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
    
    // For single session (e.g., last open chat)
    @Transaction
    suspend fun saveLastOpenSession(session: ChatSessionEntity, messages: List<MessageEntity>) {
        // We only keep one "last open chat" per mode (text/image) in SharedPreferences.
        // In Room, we might just use a special ID for "last_open_text" and "last_open_image".
        // Or we use the regular table but with a special flag.
        // However, the caller will likely pass a session with a specific ID.
        // If we want to mimic "saveLastOpenChat", we just save it.
        saveSessionWithMessages(session, messages)
    }

    @Query("SELECT * FROM chat_sessions WHERE isImageGeneration = :isImageGen ORDER BY lastModifiedTimestamp DESC")
    suspend fun getAllSessions(isImageGen: Boolean): List<ChatSessionEntity>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesForSession(sessionId: String): List<MessageEntity>
    
    @Query("DELETE FROM chat_sessions WHERE isImageGeneration = :isImageGen")
    suspend fun clearAllSessions(isImageGen: Boolean)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)
}