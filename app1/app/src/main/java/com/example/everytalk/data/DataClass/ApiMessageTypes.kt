package com.example.everytalk.data.DataClass // 请确认包名是否正确

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * API消息的抽象基类。
 * kotlinx.serialization 在序列化 sealed class 时，默认会添加一个 "type" 字段作为辨别器，
 * 其值为子类的 @SerialName（如果提供）或完全限定名。
 */
@Serializable
sealed class AbstractApiMessage {
    abstract val role: String
    abstract val name: String? // 如果 'name' 字段对所有类型的消息都通用
}

/**
 * 用于非Gemini模型的简单文本消息结构。
 * 后端期望类似: { "role": "user", "content": "一些文本" }
 */
@Serializable
@SerialName("simple_text_message") // 用于序列化时的类型辨别器值
data class SimpleTextApiMessage(
    @SerialName("role")
    override val role: String,

    @SerialName("content") // 后端期望的字段名
    val content: String,

    @SerialName("name")
    override val name: String? = null
) : AbstractApiMessage()

/**
 * 用于Gemini模型的多模态消息结构。
 * 后端期望类似: { "role": "user", "parts": [ ... ] }
 */
@Serializable
@SerialName("parts_message") // 用于序列化时的类型辨别器值
data class PartsApiMessage(
    @SerialName("role")
    override val role: String,

    @SerialName("parts")
    val parts: List<ApiContentPart>, // 使用之前定义的 ApiContentPart

    @SerialName("name")
    override val name: String? = null
) : AbstractApiMessage()