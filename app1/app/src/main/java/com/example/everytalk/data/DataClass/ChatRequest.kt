package com.example.everytalk.data.DataClass // 请确认包名是否正确

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Contextual

// 如果 ThinkingConfig 和 GenerationConfig 定义在其他文件，请确保这里有正确的 import
// import com.example.everytalk.data.DataClass.GenerationConfig // 如果它们在同一个包，则不需要显式导入

@Serializable
data class ChatRequest(
    @SerialName("messages")
    val messages: List<AbstractApiMessage>, // 保持 List<AbstractApiMessage> 以支持两种消息结构

    @SerialName("provider")
    val provider: String,

    @SerialName("api_address")
    val apiAddress: String?,

    @SerialName("api_key")
    val apiKey: String,

    @SerialName("model")
    val model: String,

    @SerialName("forceGoogleReasoningPrompt") // 这个参数您可以根据实际情况决定是否保留
    val forceGoogleReasoningPrompt: Boolean? = null,

    @SerialName("useWebSearch")
    val useWebSearch: Boolean? = null,


    @SerialName("generation_config") // <<< 新增此字段，用于封装生成相关参数
    val generationConfig: GenerationConfig? = null,

    @SerialName("tools")
    val tools: List<Map<String, @Contextual Any>>? = null,

    @SerialName("tool_choice")
    val toolChoice: @Contextual Any? = null,

    @SerialName("customModelParameters")
    val customModelParameters: Map<String, @Contextual Any>? = null, // 保持 Any 以增加灵活性

    @SerialName("customExtraBody")
    val customExtraBody: Map<String, @Contextual Any>? = null // 保持 Any
)