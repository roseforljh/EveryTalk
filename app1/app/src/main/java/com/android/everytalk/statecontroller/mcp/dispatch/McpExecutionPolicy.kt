package com.android.everytalk.statecontroller.mcp.dispatch

enum class BudgetDecision {
    ALLOW,
    STOP,
}

data class McpExecutionState(
    val toolCallsUsed: Int = 0,
    val roundTripsUsed: Int = 0,
    val sameCategoryCalls: Map<McpToolCategory, Int> = emptyMap(),
    val sameToolRetries: Map<String, Int> = emptyMap(),
)

fun evaluateToolBudget(
    state: McpExecutionState,
    budget: McpToolBudget,
    nextCategory: McpToolCategory,
    toolName: String? = null,
): BudgetDecision {
    if (state.toolCallsUsed >= budget.maxToolCalls) {
        return BudgetDecision.STOP
    }
    if (state.roundTripsUsed >= budget.maxRoundTrips) {
        return BudgetDecision.STOP
    }
    if ((state.sameCategoryCalls[nextCategory] ?: 0) >= budget.maxSameCategoryCalls) {
        return BudgetDecision.STOP
    }
    if (toolName != null && (state.sameToolRetries[toolName] ?: 0) >= budget.maxSameToolRetries) {
        return BudgetDecision.STOP
    }
    return BudgetDecision.ALLOW
}
