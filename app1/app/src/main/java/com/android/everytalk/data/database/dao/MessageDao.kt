package com.android.everytalk.data.database.dao

import androidx.room.*
import com.android.everytalk.data.database.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * 消息数据访问对象
 */
@Dao
interface MessageDao {
    
    /**
     * 插入或更新消息
     */
    @Upsert
    suspend fun upsertMessage(message: MessageEntity)
    
    /**
     * 批量插入或更新消息
     */
    @Upsert
    suspend fun upsertMessages(messages: List<MessageEntity>)
    
    /**
     * 获取会话中的所有消息（按顺序）
     */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY orderIndex ASC, timestamp ASC")
    fun getMessagesByConversation(conversationId: String): Flow<List<MessageEntity>>
    
    /**
     * 获取会话中的所有消息（一次性查询）
     */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY orderIndex ASC, timestamp ASC")
    suspend fun getMessagesByConversationOnce(conversationId: String): List<MessageEntity>
    
    /**
     * 根据ID获取消息
     */
    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessageById(id: String): MessageEntity?
    
    /**
     * 删除消息
     */
    @Delete
    suspend fun deleteMessage(message: MessageEntity)
    
    /**
     * 根据ID删除消息
     */
    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessageById(id: String)
    
    /**
     * 删除会话中的所有消息
     */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesByConversation(conversationId: String)
    
    /**
     * 获取会话中的消息数量
     */
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun getMessageCount(conversationId: String): Int
    
    /**
     * 获取会话中的最后一条消息
     */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY orderIndex DESC, timestamp DESC LIMIT 1")
    suspend fun getLastMessage(conversationId: String): MessageEntity?
    
    /**
     * 获取会话中的第一条用户消息
     */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND sender = 'USER' ORDER BY orderIndex ASC, timestamp ASC LIMIT 1")
    suspend fun getFirstUserMessage(conversationId: String): MessageEntity?
    
    /**
     * 更新消息文本
     */
    @Query("UPDATE messages SET text = :text, timestamp = :timestamp WHERE id = :id")
    suspend fun updateMessageText(id: String, text: String, timestamp: Long = System.currentTimeMillis())
    
    /**
     * 更新消息的图片URL
     */
    @Query("UPDATE messages SET imageUrlsJson = :imageUrlsJson, timestamp = :timestamp WHERE id = :id")
    suspend fun updateMessageImageUrls(id: String, imageUrlsJson: String?, timestamp: Long = System.currentTimeMillis())
    
    /**
     * 获取所有包含图片的消息（用于图片文件引用检查）
     */
    @Query("SELECT * FROM messages WHERE imageUrlsJson IS NOT NULL AND imageUrlsJson != ''")
    suspend fun getAllMessagesWithImages(): List<MessageEntity>
    
    /**
     * 获取最大的orderIndex
     */
    @Query("SELECT MAX(orderIndex) FROM messages WHERE conversationId = :conversationId")
    suspend fun getMaxOrderIndex(conversationId: String): Int?
}