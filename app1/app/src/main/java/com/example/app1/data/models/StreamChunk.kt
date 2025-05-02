package com.example.app1.data.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable // Ensure it's serializable
data class StreamChunk(
    @SerialName("type") // "content", "reasoning", or "error"
    val type: String,

    @SerialName("text") // The text content of the chunk
    val text: String
)