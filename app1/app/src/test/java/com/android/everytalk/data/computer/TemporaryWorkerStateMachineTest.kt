package com.android.everytalk.data.computer

import org.junit.Assert.assertEquals
import org.junit.Test

class TemporaryWorkerStateMachineTest {
    @Test
    fun `active temporary worker can be claimed once`() {
        val machine = TemporaryWorkerStateMachine()
        val source = TemporaryWorkerDeployment("tmp-1", "https://example", "https://claim", Long.MAX_VALUE, TemporaryWorkerStatus.ACTIVE, "ws-1")
        val pending = machine.beginClaim(source)
        assertEquals(TemporaryWorkerStatus.CLAIM_PENDING, pending.claimStatus)
        assertEquals(TemporaryWorkerStatus.CLAIMED, machine.completeClaim(pending).claimStatus)
    }

    @Test
    fun `expired active worker becomes unusable`() {
        val machine = TemporaryWorkerStateMachine()
        val source = TemporaryWorkerDeployment("tmp-1", expiresAt = 1L, claimStatus = TemporaryWorkerStatus.ACTIVE, sourceWorkspaceId = "ws-1")
        assertEquals(TemporaryWorkerStatus.EXPIRED, machine.expire(source, now = 2L).claimStatus)
    }

    @Test
    fun `claim pending worker also expires and cannot be restored`() {
        val machine = TemporaryWorkerStateMachine()
        val source = TemporaryWorkerDeployment("tmp-1", expiresAt = 2L, claimStatus = TemporaryWorkerStatus.CLAIM_PENDING, sourceWorkspaceId = "ws-1")
        val expired = machine.expire(source, now = 3L)
        assertEquals(TemporaryWorkerStatus.EXPIRED, expired.claimStatus)
        assertEquals(TemporaryWorkerStatus.EXPIRED, machine.cancelClaim(source, now = 3L).claimStatus)
    }
}
