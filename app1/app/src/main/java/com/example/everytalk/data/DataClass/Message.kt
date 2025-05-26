// Message.kt
package com.example.everytalk.data.DataClass // 请确认这是您正确的包名

import kotlinx.serialization.Serializable // 确保您有kotlinx.serialization的依赖和插件配置
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
    var text: String, // 主要文本内容 (对于AI，这是最终答案；对于用户，这是输入)
    val sender: Sender, // 消息发送者 (User, AI, System, Tool)

    // --- 思考过程相关字段 ---
    var reasoning: String? = null, // AI的思考过程文本，由后端 "reasoning" 事件填充
    // var isReasoningExpanded: Boolean = false, // UI状态：思考过程是否展开 (通常在ViewModel中管理此类UI状态)

    // --- 状态标记字段 ---
    var contentStarted: Boolean = false, // 标记主要文本内容(Message.text)是否已开始输出 (对于AI消息)
    var isError: Boolean = false, // 标记此消息是否表示一个错误
    // var isCanceled: Boolean = false, // 标记API调用是否被取消 (如果需要跟踪)

    // --- 工具调用相关 (如果适用) ---
    val name: String? = null, // 对于 role="tool" 的消息，这是工具的名称

    // --- 时间戳和UI辅助字段 ---
    val timestamp: Long = System.currentTimeMillis(), // 消息创建的时间戳
    val isPlaceholderName: Boolean = false, // 用于系统消息，指示其文本是否为占位符性质的聊天标题

    // --- Web搜索相关字段 ---
    val webSearchResults: List<WebSearchResult>? = null, // 存储从后端接收到的Web搜索结果

    var currentWebSearchStage: String? = null, // 当前Web搜索/分析阶段 (例如："web_indexing_started", "web_analysis_complete")
    val htmlContent: String? = null,

    /**
     * 存储与此消息关联的图片URL列表。
     * 这可以是上传到服务器后的HTTP/HTTPS URL，
     * 或者是本地设备上的 content:// URI 字符串。
     */
    val imageUrls: List<String>? = null // 设置为可空，并且默认为 null
)

