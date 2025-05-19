package com.example.everytalk.data.DataClass

import kotlinx.serialization.Serializable

@Serializable
data class WebSearchResult( // 我先定义一个WebSearchResult结构，后端发送的是 index, title, href, snippet
    val index: Int,
    val title: String,
    val href: String,
    val snippet: String
) {
    companion object {
        // 一个辅助函数，用于从Map转换，方便ApiHandler中使用
        fun fromMap(map: Map<String, Any?>): WebSearchResult? {
            return try {
                WebSearchResult(
                    index = (map["index"] as? Number)?.toInt() ?: 0,
                    title = map["title"] as? String ?: "N/A",
                    href = map["href"] as? String ?: "N/A",
                    snippet = map["snippet"] as? String ?: "N/A"
                )
            } catch (_: Exception) {
                // Log.e("WebSearchResult", "Failed to parse from map: $map", e) // 可以在这里添加日志
                null
            }
        }
    }
}