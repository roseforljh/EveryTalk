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
   // 兼容旧的尺寸字段（部分后端仍使用）
   @SerialName("image_size")
   val imageSize: String? = null,
   @SerialName("batch_size")
   val batchSize: Int? = null,
   @SerialName("num_inference_steps")
   val numInferenceSteps: Int? = null,
   @SerialName("guidance_scale")
   val guidanceScale: Float? = null,
   @SerialName("apiAddress")
   val apiAddress: String,
   @SerialName("apiKey")
   val apiKey: String,
   @SerialName("provider")
   val provider: String? = null,
   // 新增：Gemini generate_content 可选配置
   // 指定仅输出图片
   @SerialName("response_modalities")
   val responseModalities: List<String>? = null,
   // 指定宽高比，示例："16:9"
   @SerialName("aspect_ratio")
   val aspectRatio: String? = null
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