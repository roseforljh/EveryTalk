package com.example.everytalk.data.DataClass

import kotlinx.serialization.Serializable

@Serializable
data class GeminiApiRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig? = null,
    val safetySettings: List<SafetySetting>? = null
)

@Serializable
data class Content(
    val parts: List<Part>,
    val role: String
)

@Serializable
sealed class Part {
    @Serializable
    data class Text(val text: String) : Part()
    @Serializable
    data class InlineData(
        val mimeType: String,
        val data: String // Base64-encoded audio data
    ) : Part()
    @Serializable
    data class FileUri(val fileUri: String) : Part()
}


@Serializable
data class SafetySetting(
    val category: String,
    val threshold: String
)