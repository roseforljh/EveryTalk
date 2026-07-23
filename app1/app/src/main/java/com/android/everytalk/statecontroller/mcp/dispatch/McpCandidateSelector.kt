package com.android.everytalk.statecontroller.mcp.dispatch

fun selectMcpCandidates(
    intent: QueryIntent,
    candidates: List<McpToolCandidate>,
): List<McpToolCandidate> {
    val enabledCandidates = candidates.filter { it.enabled }.distinctBy { it.toolName }
    val preferredCategories = when (intent) {
        QueryIntent.DOCS_LOOKUP -> setOf(McpToolCategory.DOCS, McpToolCategory.SEARCH)
        QueryIntent.REALTIME_INFO -> setOf(McpToolCategory.SEARCH, McpToolCategory.BROWSER)
        QueryIntent.WEB_CONTENT_READ -> setOf(McpToolCategory.BROWSER)
        QueryIntent.LOCAL_REASONING -> emptySet()
    }
    if (preferredCategories.isEmpty()) return enabledCandidates
    val (preferred, remaining) = enabledCandidates.partition { it.category in preferredCategories }
    return preferred + remaining
}
