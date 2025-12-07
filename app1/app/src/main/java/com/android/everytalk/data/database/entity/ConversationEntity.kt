package com.android.everytalk.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 会话实体类 - 用于存储会话元数据
 * 
 * 每个会话包含一组消息，通过 conversationId 关联
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    
    /**
     * 会话类型：TEXT 或 IMAGE
     */
    val type: String,
    
    /**
     * 会话标题/名称（可选）
     */
    val title: String? = null,
    
    /**
     * 系统提示词（仅对文本模式有效）
     */
    val systemPrompt: String? = null,
    
    /**
     * 会话创建时间
     */
    val createdAt: Long = System.currentTimeMillis(),
    
    /**
     * 会话最后更新时间
     */
    val updatedAt: Long = System.currentTimeMillis(),
    
    /**
     * 是否置顶
     */
    val isPinned: Boolean = false,
    
    /**
     * 置顶顺序（越小越靠前）
     */
    val pinnedOrder: Int = 0
)

/**
 * 会话类型枚举
 */
object ConversationType {
    const val TEXT = "TEXT"
    const val IMAGE = "IMAGE"
}