package com.example.app1.data.network

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable // Ensure it's serializable
data class ApiConfig(
    val address: String,
    val key: String,
    val model: String,
    val provider: String, // Added field
    val id: String = UUID.randomUUID().toString() // Keep unique ID
)