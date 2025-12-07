package com.android.everytalk.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 会话分组实体类 - 用于存储会话分组信息
 * 
 * 每个分组包含一个名称和关联的会话ID列表
 */
@Entity(tableName = "conversation_groups")
data class ConversationGroupEntity(
    @PrimaryKey
    val groupName: String,
    
    /**
     * 关联的会话ID列表（JSON序列化存储）
     */
    val conversationIdsJson: String,
    
    /**
     * 分组创建时间
     */
    val createdAt: Long = System.currentTimeMillis(),
    
    /**
     * 分组最后更新时间
     */
    val updatedAt: Long = System.currentTimeMillis()
)