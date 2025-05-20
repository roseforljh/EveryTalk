package com.example.everytalk.data.DataClass // 请替换为您的实际包名

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Contextual // 用于 tools, toolChoice 以及新添加的 Map<String, Any> 字段

@Serializable // 确保它是可序列化的
data class ChatRequest(
    @SerialName("messages")
    val messages: List<ApiMessage>, // 消息列表

    @SerialName("provider")
    val provider: String, // AI提供商，例如："openai", "google"

    @SerialName("api_address")
    val apiAddress: String?, // API地址，对于某些提供商可能为空

    @SerialName("api_key")
    val apiKey: String, // API密钥

    @SerialName("model")
    val model: String, // 模型名称

    @SerialName("forceGoogleReasoningPrompt")
    val forceGoogleReasoningPrompt: Boolean? = null, // 可选布尔值，用于覆盖后端默认行为

    // --- 联网搜索标志 ---
    @SerialName("useWebSearch") // 确保与后端 Pydantic 模型的 alias "useWebSearch" 匹配
    val useWebSearch: Boolean? = null, // 可选布尔值

    // --- 可选生成参数 ---
    @SerialName("temperature")
    val temperature: Float? = null, // 温度参数

    @SerialName("top_p")
    val topP: Float? = null, // Top-P核心采样参数

    @SerialName("max_tokens")
    val maxTokens: Int? = null, // 最大生成Token数

    // --- 工具调用支持字段 ---
    @SerialName("tools")
    val tools: List<Map<String, @Contextual Any>>? = null, // OpenAI样式的工具列表

    @SerialName("tool_choice") // OpenAI样式的工具选择，可以是字符串或特定对象结构
    val toolChoice: @Contextual Any? = null, // 类型为 Any，因为它可以是 String 或 Map

    // --- 新增：允许客户端传递自定义的顶层参数 ---
    // 这些参数将直接添加到发送给AI服务提供商的请求JSON的顶层。
    // 例如，用于传递 SiliconFlow Qwen3 模型的 "enable_thinking": false
    @SerialName("customModelParameters")
    val customModelParameters: Map<String, Boolean>? = null,

    // --- 新增：允许客户端传递自定义的 extra_body 内容 ---
    // 这些参数将添加到请求JSON的 "extra_body" 字段中。
    // 例如，用于传递 DashScope Qwen3 模型的 "enable_thinking": false (如果DashScope需要它在extra_body中)
    @SerialName("customExtraBody")
    val customExtraBody: Map<String, Boolean>? = null
)