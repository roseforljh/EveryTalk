package com.example.everytalk.data.DataClass // 请确认这是您正确的包名

import com.example.everytalk.model.SelectedMediaItem // 确保这个导入路径正确
import kotlinx.serialization.Serializable
// import kotlinx.serialization.Transient // Transient 不再需要，因为我们要序列化 attachments
import java.util.UUID

// Sender 枚举类保持不变
@Serializable
enum class Sender {
    User,   // 用户发送的消息
    AI,     // AI生成的消息
    System, // 系统消息 (例如，聊天标题或状态提示)
    Tool    // 工具执行结果的消息 (如果应用支持前端展示工具结果)
}

@Serializable
data class Message(
    val id: String = UUID.randomUUID().toString(), // 消息的唯一标识符
    val text: String, // 主要文本内容 (对于AI，这是最终答案；对于用户，这是输入)
    val sender: Sender, // 消息发送者 (User, AI, System, Tool)

    // --- 思考过程相关字段 ---
    val reasoning: String? = null, // AI的思考过程文本，由后端 "reasoning" 事件填充

    // --- 状态标记字段 ---
    val contentStarted: Boolean = false, // 标记主要文本内容(Message.text)是否已开始输出 (对于AI消息)
    val isError: Boolean = false, // 标记此消息是否表示一个错误

    // --- 工具调用相关 (如果适用) ---
    val name: String? = null, // 对于 role="tool" 的消息，这是工具的名称

    // --- 时间戳和UI辅助字段 ---
    val timestamp: Long = System.currentTimeMillis(), // 消息创建的时间戳
    val isPlaceholderName: Boolean = false, // 用于系统消息，指示其文本是否为占位符性质的聊天标题

    // --- Web搜索相关字段 ---
    val webSearchResults: List<WebSearchResult>? = null, // 存储从后端接收到的Web搜索结果

    val currentWebSearchStage: String? = null, // 当前Web搜索/分析阶段 (例如："web_indexing_started", "web_analysis_complete")

    /**
     * 存储与此消息关联的图片URL列表。
     * 这通常是 FileProvider URI 字符串，指向应用内部存储的文件。
     * 主要用于向后兼容或纯图片附件的快速访问。
     * 对于所有类型的附件（包括图片、文档等），应优先使用 `attachments` 字段。
     */
    val imageUrls: List<String>? = null,

    /**
     * 存储与此消息关联的结构化附件信息列表。
     * 每个 SelectedMediaItem 包含其类型、指向内部存储的 FileProvider Uri，
     * 以及可能的 displayName 和 mimeType。
     * 这个字段会被序列化并持久化。
     */
    val attachments: List<SelectedMediaItem>? = null // 移除了 @Transient
)