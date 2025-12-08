package com.android.everytalk.data.DataClass
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
   val aspectRatio: String? = null,
   // 新增：严格会话隔离所需的会话ID（前端历史项ID）
   @SerialName("conversationId")
   val conversationId: String? = null,
   // 新增：无状态会话种子——把该会话最近若干轮（user/model 文本）一并发送，后端将用其恢复上下文记忆
   // 结构：[{ "role": "user"|"model", "text": "<纯文本>" }]
   @SerialName("history")
   val history: List<Map<String, String>>? = null,
   // 新增：Seedream 水印控制
   @SerialName("watermark")
   val watermark: Boolean? = false,
   // 新增：Gemini 3 Pro Image 专用尺寸档位（"1K"|"2K"|"4K"）
   // 仅对 gemini-3-pro-image-preview 模型生效，gemini-2.5-flash-image 固定为 1K
   @SerialName("gemini_image_size")
   val geminiImageSize: String? = null
)

@Serializable
data class ChatRequest(
    @SerialName("messages")
    val messages: List<AbstractApiMessage>,

    @SerialName("provider")
    val provider: String,

    @SerialName("channel")
    val channel: String,

    @SerialName("apiAddress")
    val apiAddress: String?,

    @SerialName("apiKey")
    val apiKey: String,

    @SerialName("model")
    val model: String,

    @SerialName("force_google_reasoning_prompt")
    val forceGoogleReasoningPrompt: Boolean? = null,

    @SerialName("use_web_search")
    val useWebSearch: Boolean? = null,

    @SerialName("generationConfig")
    val generationConfig: GenerationConfig? = null,

    @SerialName("tools")
    val tools: List<Map<String, @Contextual Any>>? = null,

    @SerialName("toolChoice")
    val toolChoice: @Contextual Any? = null,

    @SerialName("qwenEnableSearch")
    val qwenEnableSearch: Boolean? = null,

    @SerialName("customModelParameters")
    val customModelParameters: Map<String, @Contextual Any>? = null,

    @SerialName("customExtraBody")
    val customExtraBody: Map<String, @Contextual Any>? = null,

    @SerialName("imageGenRequest")
    val imageGenRequest: ImageGenRequest? = null,

    // 设备唯一标识符（用于速率限制）
    @SerialName("device_id")
    val deviceId: String? = null,

    // 代码执行工具控制：None=auto(智能判断), True=强制开启, False=强制关闭
    @SerialName("enableCodeExecution")
    val enableCodeExecution: Boolean? = null
)