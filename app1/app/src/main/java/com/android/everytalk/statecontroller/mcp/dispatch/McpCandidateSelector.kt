package com.android.everytalk.statecontroller.mcp.dispatch

data class McpDispatchPlan(
    val intent: McpDispatchIntent,
    val exposedTools: List<McpToolCandidate>,
    val hiddenToolsCount: Int,
    val budget: McpToolBudget,
)

fun selectMcpCandidates(
    intent: McpDispatchIntent,
    candidates: List<McpToolCandidate>,
    strategy: McpDispatchStrategy,
): McpDispatchPlan {
    val filtered = when (intent.primaryIntent) {
        QueryIntent.DOCS_LOOKUP -> candidates.filter {
            it.category == McpToolCategory.DOCS || it.category == McpToolCategory.SEARCH
        }
        QueryIntent.REALTIME_INFO -> candidates.filter {
            it.category == McpToolCategory.SEARCH || it.category == McpToolCategory.BROWSER
        }
        QueryIntent.WEB_CONTENT_READ -> candidates.filter {
            it.category == McpToolCategory.BROWSER
        }
        QueryIntent.LOCAL_REASONING -> candidates
        else -> candidates
    }.sortedByDescending { it.reliabilityScore }

    return McpDispatchPlan(
        intent = intent,
        exposedTools = filtered,
        hiddenToolsCount = (candidates.size - filtered.size).coerceAtLeast(0),
        budget = strategy.budget,
    )
}
