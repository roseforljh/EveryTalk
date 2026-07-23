package com.android.everytalk.statecontroller.mcp.dispatch

private val DOCS_KEYWORDS = listOf(
    "sdk", "api", "framework", "library", "docs", "documentation", "migration", "upgrade",
    "compose", "ktor", "room", "retrofit", "next.js", "react", "tailwind",
    "配置", "文档", "官方文档", "怎么配", "怎么配置", "迁移", "升级", "参数", "接口"
)

private val REALTIME_KEYWORDS = listOf(
    "今天", "最新", "最近", "实时", "当前", "现在", "新闻", "热点", "股价", "天气", "比分", "排名",
    "today", "latest", "recent", "real-time", "realtime", "news", "stock", "weather", "score"
)

fun classifyMcpIntent(messageText: String): QueryIntent {
    val normalizedText = messageText.lowercase()

    return when {
        DOCS_KEYWORDS.any { it in normalizedText } -> QueryIntent.DOCS_LOOKUP

        REALTIME_KEYWORDS.any { it in normalizedText } -> QueryIntent.REALTIME_INFO

        "http://" in normalizedText || "https://" in normalizedText -> QueryIntent.WEB_CONTENT_READ

        else -> QueryIntent.LOCAL_REASONING
    }
}
