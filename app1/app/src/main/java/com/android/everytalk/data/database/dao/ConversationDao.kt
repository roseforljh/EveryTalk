package com.android.everytalk.data.database.dao

import androidx.room.*
import com.android.everytalk.data.database.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

/**
 * 会话数据访问对象
 */
@Dao
interface ConversationDao {
    
    /**
     * 插入或更新会话
     */
    @Upsert
    suspend fun upsertConversation(conversation: ConversationEntity)
    
    /**
     * 批量插入或更新会话
     */
    @Upsert
    suspend fun upsertConversations(conversations: List<ConversationEntity>)
    
    /**
     * 获取所有会话（按更新时间降序）
     */
    @Query("SELECT * FROM conversations WHERE type = :type ORDER BY isPinned DESC, pinnedOrder ASC, updatedAt DESC")
    fun getAllConversationsByType(type: String): Flow<List<ConversationEntity>>
    
    /**
     * 获取所有会话（一次性查询）
     */
    @Query("SELECT * FROM conversations WHERE type = :type ORDER BY isPinned DESC, pinnedOrder ASC, updatedAt DESC")
    suspend fun getAllConversationsByTypeOnce(type: String): List<ConversationEntity>
    
    /**
     * 根据ID获取会话
     */
    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversationById(id: String): ConversationEntity?
    
    /**
     * 删除会话
     */
    @Delete
    suspend fun deleteConversation(conversation: ConversationEntity)
    
    /**
     * 根据ID删除会话
     */
    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversationById(id: String)
    
    /**
     * 删除所有指定类型的会话
     */
    @Query("DELETE FROM conversations WHERE type = :type")
    suspend fun deleteAllConversationsByType(type: String)
    
    /**
     * 获取会话数量
     */
    @Query("SELECT COUNT(*) FROM conversations WHERE type = :type")
    suspend fun getConversationCount(type: String): Int
    
    /**
     * 更新会话时间戳
     */
    @Query("UPDATE conversations SET updatedAt = :timestamp WHERE id = :id")
    suspend fun updateConversationTimestamp(id: String, timestamp: Long = System.currentTimeMillis())
    
    /**
     * 更新置顶状态
     */
    @Query("UPDATE conversations SET isPinned = :isPinned, pinnedOrder = :pinnedOrder WHERE id = :id")
    suspend fun updatePinnedStatus(id: String, isPinned: Boolean, pinnedOrder: Int)
    
    /**
     * 获取所有置顶的会话ID
     */
    @Query("SELECT id FROM conversations WHERE type = :type AND isPinned = 1")
    suspend fun getPinnedConversationIds(type: String): List<String>
    
    /**
     * 批量更新置顶状态
     */
    @Query("UPDATE conversations SET isPinned = 1 WHERE id IN (:ids)")
    suspend fun pinConversations(ids: List<String>)
    
    /**
     * 取消所有置顶
     */
    @Query("UPDATE conversations SET isPinned = 0, pinnedOrder = 0 WHERE type = :type")
    suspend fun unpinAllConversations(type: String)
}