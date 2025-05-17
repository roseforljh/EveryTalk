package com.example.everytalk.data.DataClass

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Contextual // 保持这个导入，因为你的 tools 和 toolConfig 字段使用了它

@Serializable
data class ChatRequest(
    @SerialName("messages")
    val messages: List<ApiMessage>, // 使用更新后的 ApiMessage

    @SerialName("provider")
    val provider: String, // e.g., "openai", "google"

    @SerialName("api_address")
    val apiAddress: String, // 通常也应该是可空的，除非你强制每个请求都有

    @SerialName("api_key")
    val apiKey: String,

    @SerialName("model")
    val model: String,

    // --- 新增：请求深度思考过程的标志 ---
    @SerialName("requestReasoning")
    val requestReasoning: Boolean? = null,

    // --- 联网搜索标志 ---
    @SerialName("useWebSearch") // 确保与后端 Pydantic 模型的 alias 匹配
    val useWebSearch: Boolean? = null, // 可空 Boolean

    // --- 可选生成参数 ---
    @SerialName("temperature")
    val temperature: Float? = null,

    @SerialName("top_p")
    val topP: Float? = null,

    @SerialName("max_tokens")
    val maxTokens: Int? = null,

    // --- Tool Calling 支持字段 ---
    // 注意：如果你的后端 Pydantic 模型对 tools 和 tool_config 的字段名是蛇形 (e.g., tool_choice)
    // 那么这里的 @SerialName 也应该匹配那个蛇形名，或者后端使用 alias。
    // 你当前的 toolConfig 使用了 @SerialName("tool_config")，这是正确的，如果后端是 tool_config。
    // 但你之前提到 tool_choice，所以这里需要确认后端期望的是 tool_config 还是 tool_choice。
    // 假设后端期望的是 tools 和 tool_config (或 tool_choice)，并且类型是 Map<String, Any>
    @SerialName("tools")
    val tools: List<Map<String, @Contextual Any>>? = null,

    @SerialName("tool_config") // 如果后端是 tool_choice，这里应该是 @SerialName("tool_choice")
    val toolConfig: Map<String, @Contextual Any>? = null
    // 或者如果是 OpenAI 的 tool_choice (可以是字符串或对象):
    // @SerialName("tool_choice")
    // val toolChoice: @Contextual Any? = null // Any 可以是 String 或 Map<String, Any>
    // 这需要更复杂的序列化器或 JsonElement
)

