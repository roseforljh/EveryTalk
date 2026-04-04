package com.android.everytalk.data.network

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable

@Serializable
data class ExternalWebSearchProviderConfig(
    val providerId: String,
    val apiKey: String = "",
)

enum class ExternalWebSearchProvider(
    val providerId: String,
    val displayName: String,
    val description: String,
    val baseUrl: String,
    val apiKeyPlaceholder: String,
    val accentColorHex: Long,
) {
    TAVILY(
        providerId = "tavily",
        displayName = "Tavily",
        description = "面向 AI 助手优化的实时联网搜索",
        baseUrl = "https://api.tavily.com/search",
        apiKeyPlaceholder = "tvly-xxxxxxxx",
        accentColorHex = 0xFF14B8A6,
    ),
    EXA(
        providerId = "exa",
        displayName = "Exa",
        description = "AI 原生语义搜索，适合高质量网页检索",
        baseUrl = "https://api.exa.ai/search",
        apiKeyPlaceholder = "exa_xxxxxxxxxxxx",
        accentColorHex = 0xFF6366F1,
    ),
    BOCHA(
        providerId = "bocha",
        displayName = "Bocha",
        description = "更适合中文场景的联网搜索服务",
        baseUrl = "https://api.bochaai.com/v1/web-search",
        apiKeyPlaceholder = "bocha-xxxxxxxx",
        accentColorHex = 0xFFF97316,
    ),
    SERPAPI(
        providerId = "serpapi",
        displayName = "SerpAPI",
        description = "聚合多搜索源，接入简单稳定",
        baseUrl = "https://serpapi.com/search.json",
        apiKeyPlaceholder = "serpapi_xxxxxxxxxxxx",
        accentColorHex = 0xFFEF4444,
    );

    val accentColor: Color
        get() = Color(accentColorHex)

    companion object {
        val defaultProvider: ExternalWebSearchProvider = TAVILY

        fun fromId(providerId: String?): ExternalWebSearchProvider? {
            if (providerId.isNullOrBlank()) return null
            return entries.firstOrNull { it.providerId.equals(providerId, ignoreCase = true) }
        }
    }
}
