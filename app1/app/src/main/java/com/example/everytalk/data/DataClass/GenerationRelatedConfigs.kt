// 文件：GenerationRelatedConfigs.kt (或者放在 ChatRequest.kt 文件的顶部，但在 ChatRequest 类定义之外)
package com.example.everytalk.data.DataClass // 确保包名与你的项目结构一致

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ThinkingConfig(
    @SerialName("include_thoughts") val includeThoughts: Boolean? = null,
    @SerialName("thinking_budget") val thinkingBudget: Int? = null
)

@Serializable
data class GenerationConfig(
    @SerialName("temperature") val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    @SerialName("thinking_config") val thinkingConfig: ThinkingConfig? = null
)