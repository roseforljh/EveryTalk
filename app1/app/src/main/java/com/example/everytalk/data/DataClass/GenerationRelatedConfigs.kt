package com.example.everytalk.data.DataClass
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