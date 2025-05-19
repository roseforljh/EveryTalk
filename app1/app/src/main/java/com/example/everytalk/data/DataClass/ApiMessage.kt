package com.example.everytalk.data.DataClass

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable // Ensure it's serializable
data class ApiMessage(
    @SerialName("role") // Match backend JSON key
    val role: String, // Can be "user", "assistant", "system", or "tool"

    @SerialName("content") // Match backend JSON key
    val content: String?, // Make content nullable for flexibility (e.g., assistant initiating tool call might not have text content)

    @SerialName("name") // Match the expected extra field name for tool role
    val name: String? = null // Nullable: Only relevant and non-null when role is "tool"
)