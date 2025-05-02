package com.example.app1.data.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable // Ensure it's serializable
data class ChatRequest(
    // Uses List<ApiMessage> now
    @SerialName("messages")
    val messages: List<ApiMessage>,

    @SerialName("provider")
    val provider: String, // e.g., "openai", "google"

    @SerialName("api_address")
    val apiAddress: String, // Field names match backend

    @SerialName("api_key")
    val apiKey: String,

    @SerialName("model")
    val model: String

    // Optional fields like temperature can be added later if needed
    // @SerialName("temperature")
    // val temperature: Float? = null
)