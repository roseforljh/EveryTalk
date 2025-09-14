package com.example.everytalk.data.DataClass
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Contextual

@Serializable
data class ImageGenRequest(
   @SerialName("model")
   val model: String,
   @SerialName("prompt")
   val prompt: String,
   @SerialName("image_size")
   val imageSize: String?,
   @SerialName("batch_size")
   val batchSize: Int?,
   @SerialName("num_inference_steps")
   val numInferenceSteps: Int?,
   @SerialName("guidance_scale")
   val guidanceScale: Float?,
   @SerialName("apiAddress")
   val apiAddress: String,
   @SerialName("apiKey")
   val apiKey: String,
   @SerialName("provider")
   val provider: String? = null
)

@Serializable
data class ChatRequest(
    @SerialName("messages")
    val messages: List<AbstractApiMessage>,

    @SerialName("provider")
    val provider: String,

    @SerialName("channel")
    val channel: String,

    @SerialName("api_address")
    val apiAddress: String?,

    @SerialName("api_key")
    val apiKey: String,

    @SerialName("model")
    val model: String,

    @SerialName("force_google_reasoning_prompt")
    val forceGoogleReasoningPrompt: Boolean? = null,

    @SerialName("use_web_search")
    val useWebSearch: Boolean? = null,

    @SerialName("generation_config")
    val generationConfig: GenerationConfig? = null,

    @SerialName("tools")
    val tools: List<Map<String, @Contextual Any>>? = null,

    @SerialName("tool_choice")
    val toolChoice: @Contextual Any? = null,

    @SerialName("qwen_enable_search")
    val qwenEnableSearch: Boolean? = null,

    @SerialName("custom_model_parameters")
    val customModelParameters: Map<String, @Contextual Any>? = null,

    @SerialName("custom_extra_body")
    val customExtraBody: Map<String, @Contextual Any>? = null,

    @SerialName("image_gen_request")
    val imageGenRequest: ImageGenRequest? = null
)