package com.android.everytalk.statecontroller.mcp.dispatch

import org.junit.Assert.assertEquals
import org.junit.Test

class McpFailureRecoveryTest {

    @Test
    fun `auth errors trip circuit immediately`() {
        val action = classifyMcpFailure("401 unauthorized", isEmptyResult = false)
        assertEquals(McpRecoveryAction.TRIP_SERVER, action)
    }

    @Test
    fun `timeout chooses failover when backup exists`() {
        val action = nextRecoveryAction(
            failureType = McpFailureType.TIMEOUT,
            hasSameCategoryBackup = true,
            retryCount = 1,
        )
        assertEquals(McpRecoveryAction.FAILOVER, action)
    }
}
