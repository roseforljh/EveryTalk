package com.example.app1.data.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable // Ensure it's serializable
data class ApiMessage(
    @SerialName("role") // Match backend JSON key
    val role: String, // "user" or "assistant" (map from UI Sender)

    @SerialName("content") // Match backend JSON key
    val content: String
)