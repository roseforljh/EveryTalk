package com.example.everytalk.data.DataClass

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**

 * Represents a single SSE chunk from OpenAI-compatible chat completions API.
 */
@Serializable
data class OpenAiStreamChunk(
    @SerialName("id")
    val id: String? = null,
    @SerialName("object")
    val obj: String? = null, // "object" is a keyword, so use 'obj'
    @SerialName("created")
    val created: Long? = null,
    @SerialName("model")
    val model: String? = null,
    @SerialName("choices")
    val choices: List<OpenAiChoice>? = null,
    @SerialName("system_fingerprint")
    val systemFingerprint: String? = null
)

@Serializable
data class OpenAiChoice(
    @SerialName("index")
    val index: Int? = null,
    @SerialName("delta")
    val delta: OpenAiDelta? = null,
    @SerialName("logprobs")
    val logprobs: JsonObject? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class OpenAiDelta(
    @SerialName("role")
    val role: String? = null,
    @SerialName("content")
    val content: String? = null,
    // 如果你的模型流有这字段才需要
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenAiToolCall>? = null
)

@Serializable
data class OpenAiToolCall(
    @SerialName("index")
    val index: Int? = null,
    @SerialName("id")
    val id: String? = null,
    @SerialName("type")
    val type: String? = null, // e.g., "function"
    @SerialName("function")
    val function: OpenAiFunctionCall? = null
)

@Serializable
data class OpenAiFunctionCall(
    @SerialName("name")
    val name: String? = null,
    @SerialName("arguments")
    val arguments: String? = null // Usually a raw JSON string
)


