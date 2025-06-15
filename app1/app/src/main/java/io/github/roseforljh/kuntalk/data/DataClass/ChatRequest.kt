package io.github.roseforljh.kuntalk.data.DataClass
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
    val useWebSearch: Boolean? = null,

    @SerialName("generation_config")
    val generationConfig: GenerationConfig? = null,

    @SerialName("tools")
    val tools: List<Map<String, @Contextual Any>>? = null,

    @SerialName("tool_choice")
    val toolChoice: @Contextual Any? = null,

    @SerialName("qwen_enable_search")
    val qwenEnableSearch: Boolean? = null,

    @SerialName("customModelParameters")
    val customModelParameters: Map<String, @Contextual Any>? = null,

    @SerialName("customExtraBody")
    val customExtraBody: Map<String, @Contextual Any>? = null
)
