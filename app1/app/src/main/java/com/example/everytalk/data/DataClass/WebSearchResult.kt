package com.example.everytalk.data.DataClass // Adjust package as needed

import kotlinx.serialization.Serializable

@Serializable
data class WebSearchResult(
    val index: Int,
    val title: String,
    val href: String,
    val snippet: String
)