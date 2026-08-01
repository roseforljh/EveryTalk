package com.android.everytalk.data.DataClass
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ThinkingConfig(
    @SerialName("include_thoughts") val includeThoughts: Boolean? = null,
    @SerialName("thinking_budget") val thinkingBudget: Int? = null,
    @SerialName("thinking_level") val thinkingLevel: String? = null,
    @SerialName("reasoning_mode") val reasoningMode: ReasoningMode = ReasoningMode.EFFORT,
    @SerialName("reasoning_effort") val reasoningEffort: String = DEFAULT_REASONING_EFFORT,
)

@Serializable
data class GenerationConfig(
    @SerialName("temperature") val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    @SerialName("thinking_config") val thinkingConfig: ThinkingConfig? = null
)
