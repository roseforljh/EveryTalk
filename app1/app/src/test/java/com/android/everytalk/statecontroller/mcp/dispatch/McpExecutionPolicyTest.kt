package com.android.everytalk.statecontroller.mcp.dispatch

import org.junit.Assert.assertEquals
import org.junit.Test

class McpExecutionPolicyTest {

    @Test
    fun `default model led strategy uses bounded mobile budget`() {
        val strategy = McpDispatchStrategy.modelLedDefault()

        assertEquals(DispatchMode.MODEL_LED, strategy.mode)
        assertEquals(2, strategy.budget.maxToolCalls)
        assertEquals(3, strategy.budget.maxRoundTrips)
        assertEquals(2, strategy.budget.maxSameCategoryCalls)
        assertEquals(1, strategy.budget.maxSameToolRetries)
    }

    @Test
    fun `policy stops when max tool calls reached`() {
        val state = McpExecutionState(
            toolCallsUsed = 2,
            sameCategoryCalls = mapOf(McpToolCategory.DOCS to 2),
        )

        val result = evaluateToolBudget(
            state = state,
            budget = McpDispatchStrategy.modelLedDefault().budget,
            nextCategory = McpToolCategory.DOCS,
        )

        assertEquals(BudgetDecision.STOP, result)
    }
}
