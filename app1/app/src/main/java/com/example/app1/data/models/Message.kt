package com.example.app1.data.models

import kotlinx.serialization.Serializable // <-- 导入 Serializable
import java.util.UUID

@Serializable // <-- 为 Sender 添加注解
enum class Sender { User, AI, System }

@Serializable // <-- 为 Message 添加注解
// UI 数据模型 - 不直接发送到后端 API
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val text: String,          // 用于显示和 API 转换的内容
    val sender: Sender,        // 用于显示和 API 转换的角色
    val reasoning: String?,    // DeepSeek/UI 特有的思考过程
    var contentStarted: Boolean = false, // UI 状态标志 - 保持为 var
    var isError: Boolean = false, // 可选：错误消息标志 - 保持为 var
    // 如果以后需要按时间排序历史记录，可以添加时间戳，并确保它是可序列化的
    // val timestamp: Long = System.currentTimeMillis()
)