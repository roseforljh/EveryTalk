package com.example.everytalk.data.DataClass

import kotlinx.serialization.Serializable

@Serializable
data class ImageUrl(
    val url: String
)

@Serializable
data class Timings(
    val inference: Int
)

@Serializable
data class ImageGenerationResponse(
    val images: List<ImageUrl>,
    val text: String? = null,
    val timings: Timings,
    val seed: Int
)