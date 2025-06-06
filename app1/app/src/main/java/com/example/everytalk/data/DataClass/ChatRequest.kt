package com.example.everytalk.data.DataClass // 请确认包名是否正确

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Contextual

@Serializable
data class ChatRequest(
    @SerialName("messages")
    val messages: List<AbstractApiMessage>,

    @SerialName("provider")
    val provider: String,

    @SerialName("api_address")
    val apiAddress: String?,

    @SerialName("api_key")
    val apiKey: String,

    @SerialName("model")
    val model: String,

    @SerialName("forceGoogleReasoningPrompt")
    val forceGoogleReasoningPrompt: Boolean? = null,

    @SerialName("useWebSearch")
    val useWebSearch: Boolean? = null, // 用于通用的联网搜索标志

    @SerialName("generation_config")
    val generationConfig: GenerationConfig? = null,

    @SerialName("tools")
    val tools: List<Map<String, @Contextual Any>>? = null,

    @SerialName("tool_choice")
    val toolChoice: @Contextual Any? = null, // 如果 tool_choice 也是复杂对象，可能也需要具体类型或多态

    @SerialName("qwen_enable_search")
    val qwenEnableSearch: Boolean? = null,

    @SerialName("customModelParameters") // 用于其他模型的、未显式定义的自定义参数
    val customModelParameters: Map<String, @Contextual Any>? = null,

    @SerialName("customExtraBody")
    val customExtraBody: Map<String, @Contextual Any>? = null
)