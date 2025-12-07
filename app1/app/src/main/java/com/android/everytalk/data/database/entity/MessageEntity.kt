package com.android.everytalk.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 消息实体类 - 用于存储聊天消息
 * 
 * 与 ConversationEntity 通过 conversationId 关联
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE  // 删除会话时自动删除关联消息
        )
    ],
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["timestamp"])
    ]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,
    
    /**
     * 关联的会话ID
     */
    val conversationId: String,
    
    /**
     * 消息文本内容
     */
    val text: String,
    
    /**
     * 发送者类型：USER, AI, SYSTEM, TOOL
     */
    val sender: String,
    
    /**
     * AI 推理/思考过程（可选）
     */
    val reasoning: String? = null,
    
    /**
     * 内容是否已开始（用于流式响应）
     */
    val contentStarted: Boolean = false,
    
    /**
     * 是否为错误消息
     */
    val isError: Boolean = false,
    
    /**
     * 消息名称（可选）
     */
    val name: String? = null,
    
    /**
     * 消息时间戳
     */
    val timestamp: Long = System.currentTimeMillis(),
    
    /**
     * 是否为占位符名称
     */
    val isPlaceholderName: Boolean = false,
    
    /**
     * 网页搜索结果（JSON序列化）
     */
    val webSearchResultsJson: String? = null,
    
    /**
     * 当前网页搜索阶段
     */
    val currentWebSearchStage: String? = null,
    
    /**
     * 图片URL列表（JSON序列化）
     */
    val imageUrlsJson: String? = null,
    
    /**
     * 附件信息（JSON序列化）
     */
    val attachmentsJson: String? = null,
    
    /**
     * 输出类型
     */
    val outputType: String = "general",
    
    /**
     * Markdown解析后的parts（JSON序列化）
     */
    val partsJson: String? = null,
    
    /**
     * 执行状态
     */
    val executionStatus: String? = null,
    
    /**
     * 消息在会话中的顺序
     */
    val orderIndex: Int = 0
)

/**
 * 发送者类型常量
 */
object SenderType {
    const val USER = "USER"
    const val AI = "AI"
    const val SYSTEM = "SYSTEM"
    const val TOOL = "TOOL"
}