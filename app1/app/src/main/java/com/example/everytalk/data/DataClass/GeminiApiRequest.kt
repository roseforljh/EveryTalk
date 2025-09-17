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
        val data: String, // Base64-encoded media data
        val videoMetadata: VideoMetadata? = null
    ) : Part()
    @Serializable
    data class FileUri(
        val fileUri: String,
        val videoMetadata: VideoMetadata? = null
    ) : Part()
}

@Serializable
data class VideoMetadata(
    val startOffset: String? = null,
    val endOffset: String? = null,
    val fps: Double? = null
)


@Serializable
data class SafetySetting(
    val category: String,
    val threshold: String
)