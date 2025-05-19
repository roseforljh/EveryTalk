package com.example.everytalk.data.DataClass // 请替换为您的实际包名

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Contextual // 用于 tools 和 toolChoice 字段

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

    // 用于控制Google提供商是否强制启用引导式推理提示的标志。
    // 对应后端Pydantic模型中的 `forceGoogleReasoningPrompt` (当 provider='google' 时生效)。
    // 对于 provider='openai' 且模型名称包含 'gemini' 的情况，后端会自动处理该逻辑，前端通常无需为此标志传递特定值。
    // 设置为 null 将允许后端根据其内部的启发式规则（例如模型名称是否包含 "pro" 或 "thinking"）来决定是否启用。
    // 设置为 true 会强制启用，设置为 false 会强制禁用（仅对Google provider）。
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
    val toolChoice: @Contextual Any? = null // 类型为 Any，因为它可以是 String 或 Map
)