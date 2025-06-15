package io.github.roseforljh.kuntalk.data.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface ChatDao {
    @Transaction
    @Query("SELECT * FROM conversations ORDER BY last_updated DESC")
    suspend fun getAllConversationsWithMessages(): List<ConversationWithMessages>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Query("DELETE FROM conversations WHERE conversation_id = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Query("DELETE FROM conversations")
    suspend fun clearAllConversations()
}