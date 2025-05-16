package com.example.everytalk.data.DataClass

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Contextual // <-- 导入 Contextual (如果使用 Map<String, Any>)
// 或者如果你决定使用 JsonElement: import kotlinx.serialization.json.JsonElement

@Serializable // Ensure it's serializable
data class ChatRequest(
    @SerialName("messages")
    val messages: List<ApiMessage>, // 使用更新后的 ApiMessage

    @SerialName("provider")
    val provider: String, // e.g., "openai", "google"

    @SerialName("api_address")
    val apiAddress: String,

    @SerialName("api_key")
    val apiKey: String,

    @SerialName("model")
    val model: String,

    // --- 可选生成参数 ---
    @SerialName("temperature")
    val temperature: Float? = null, // 可空 Float

    @SerialName("top_p")
    val topP: Float? = null,        // 可空 Float，注意 SerialName 匹配后端

    @SerialName("max_tokens")
    val maxTokens: Int? = null,     // 可空 Int，注意 SerialName 匹配后端

    // --- Tool Calling 支持字段 ---
    @SerialName("tools")
    val tools: List<Map<String, @Contextual Any>>? = null, // 可空列表

    @SerialName("tool_config") // 注意 SerialName 匹配后端 snake_case
    val toolConfig: Map<String, @Contextual Any>? = null // 可空 Map
)