package com.example.app1.data.DataClass

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
    val name: String? = null, // 如果是ApiConfig.name相关的报错，请确保ApiConfig类中有name属性
    var hasPendingToolCall: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val isPlaceholderName: Boolean = false // 确保这个属性存在，用于重命名逻辑
)