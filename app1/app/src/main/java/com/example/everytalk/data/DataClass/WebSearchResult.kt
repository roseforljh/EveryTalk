package com.example.everytalk.data.DataClass

import kotlinx.serialization.Serializable

@Serializable
data class WebSearchResult(
    val index: Int,
    val title: String,
    val href: String,
    val snippet: String
) {
    companion object {
        fun fromMap(map: Map<String, Any?>): WebSearchResult? {
            return try {
                WebSearchResult(
                    index = (map["index"] as? Number)?.toInt() ?: 0,
                    title = map["title"] as? String ?: "N/A",
                    href = map["href"] as? String ?: "N/A",
                    snippet = map["snippet"] as? String ?: "N/A"
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}