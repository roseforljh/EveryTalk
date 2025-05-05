package com.example.app1.data.models

import com.example.app1.data.models.ApiMessage
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
    // 使用 @Contextual Any 允许 Map 包含任意类型的值。
    // 你需要在序列化器配置中注册 Any 的上下文序列化器，或者更倾向于使用 JsonElement。
    // 为了简单起见，如果你不需要强类型检查 Map 内容，可以考虑使用 JsonElement：
    // val tools: List<Map<String, kotlinx.serialization.json.JsonElement>>? = null
    // 或者，如果 Map 值类型已知且简单 (如 String, Int, Boolean, List, Map)，这可能直接工作。
    // 我们先尝试 @Contextual Any，如果遇到序列化问题，再考虑 JsonElement。
    val tools: List<Map<String, @Contextual Any>>? = null, // 可空列表

    @SerialName("tool_config") // 注意 SerialName 匹配后端 snake_case
    val toolConfig: Map<String, @Contextual Any>? = null // 可空 Map
)