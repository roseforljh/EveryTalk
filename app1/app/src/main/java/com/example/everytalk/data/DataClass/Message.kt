package com.example.everytalk.data.DataClass // 请确认这是您正确的包名

import kotlinx.serialization.Serializable // 确保您有kotlinx.serialization的依赖和插件配置
import java.util.UUID

// Sender 枚举类保持不变
@Serializable
enum class Sender {
    User, AI, System, Tool // Tool 是您原有的，这里保持
}




@Serializable
data class Message(
    val id: String = UUID.randomUUID().toString(),
    var text: String, // 您原来的 text 字段，设为 var 以便后续更新
    val sender: Sender,
    // 以下字段根据您原来 Message.kt 中的定义保留或调整
    val reasoning: String? = null,
    var contentStarted: Boolean = false, // 标记主要文本内容是否已开始输出
    var isError: Boolean = false,
    // var isCanceled: Boolean = false, // 您原来的字段，如果需要请保留
    val name: String? = null, // 您原来的字段
    // var hasPendingToolCall: Boolean = false, // 您原来的字段，如果需要请保留
    val timestamp: Long = System.currentTimeMillis(),
    val isPlaceholderName: Boolean = false, // 您原来的字段，用于系统消息标题

    // --- 这是您之前为Web搜索结果添加的字段 ---
    val webSearchResults: List<WebSearchResult>? = null,

    // --- 新增字段，用于存储从后端接收到的搜索/分析阶段 ---
    var currentWebSearchStage: String? = null // 例如："web_indexing_started", "web_analysis_started", "web_analysis_complete"
)