package com.android.everytalk.data.database.dao

import androidx.room.*
import com.android.everytalk.data.database.entity.ConversationGroupEntity
import kotlinx.coroutines.flow.Flow

/**
 * 会话分组数据访问对象
 */
@Dao
interface ConversationGroupDao {
    
    /**
     * 插入或更新分组
     */
    @Upsert
    suspend fun upsertGroup(group: ConversationGroupEntity)
    
    /**
     * 批量插入或更新分组
     */
    @Upsert
    suspend fun upsertGroups(groups: List<ConversationGroupEntity>)
    
    /**
     * 获取所有分组
     */
    @Query("SELECT * FROM conversation_groups ORDER BY createdAt ASC")
    fun getAllGroups(): Flow<List<ConversationGroupEntity>>
    
    /**
     * 获取所有分组（一次性查询）
     */
    @Query("SELECT * FROM conversation_groups ORDER BY createdAt ASC")
    suspend fun getAllGroupsOnce(): List<ConversationGroupEntity>
    
    /**
     * 根据名称获取分组
     */
    @Query("SELECT * FROM conversation_groups WHERE groupName = :groupName")
    suspend fun getGroupByName(groupName: String): ConversationGroupEntity?
    
    /**
     * 删除分组
     */
    @Delete
    suspend fun deleteGroup(group: ConversationGroupEntity)
    
    /**
     * 根据名称删除分组
     */
    @Query("DELETE FROM conversation_groups WHERE groupName = :groupName")
    suspend fun deleteGroupByName(groupName: String)
    
    /**
     * 删除所有分组
     */
    @Query("DELETE FROM conversation_groups")
    suspend fun deleteAllGroups()
    
    /**
     * 获取分组数量
     */
    @Query("SELECT COUNT(*) FROM conversation_groups")
    suspend fun getGroupCount(): Int
    
    /**
     * 更新分组的会话ID列表
     */
    @Query("UPDATE conversation_groups SET conversationIdsJson = :conversationIdsJson, updatedAt = :updatedAt WHERE groupName = :groupName")
    suspend fun updateGroupConversations(groupName: String, conversationIdsJson: String, updatedAt: Long = System.currentTimeMillis())
}