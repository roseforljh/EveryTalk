package com.example.everytalk.data.DataClass

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class Sender {
    User, AI, System, Tool
}

@Serializable
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val sender: Sender,
    val reasoning: String? = null,
    var contentStarted: Boolean = false,
    var isError: Boolean = false,
    var isCanceled: Boolean = false,
    val name: String? = null,
    var hasPendingToolCall: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val isPlaceholderName: Boolean = false,
    // --- 新增字段 ---
    val webSearchResults: List<WebSearchResult>? = null // 用于存储关联的网页搜索结果
)