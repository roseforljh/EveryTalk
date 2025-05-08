// File: app/src/main/java/com/example/app1/data/models/Message.kt
package com.example.app1.data.models

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
    var isCanceled: Boolean = false, // 标记消息是否被取消
    val name: String? = null,
    var hasPendingToolCall: Boolean = false,
    val timestamp: Long = System.currentTimeMillis() // 添加 Long 类型的 timestamp，并提供默认值
)
