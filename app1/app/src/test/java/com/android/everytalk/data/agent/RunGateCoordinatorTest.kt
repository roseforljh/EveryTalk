package com.android.everytalk.data.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class RunGateCoordinatorTest {
    private val coordinator = RunGateCoordinator()

    @Test
    fun `capability 可用不能绕过 approval`() {
        assertFalse(coordinator.canExecute(ExecutionGateSnapshot(ApprovalGateState.WAITING, CapabilityGateState.AVAILABLE)))
    }

    @Test
    fun `approval 允许不能绕过 capability`() {
        assertFalse(coordinator.canExecute(ExecutionGateSnapshot(ApprovalGateState.ALLOWED, CapabilityGateState.WAITING)))
    }

    @Test
    fun `所有 Gate 满足后才执行`() {
        assertTrue(coordinator.canExecute(ExecutionGateSnapshot(ApprovalGateState.ALLOWED, CapabilityGateState.AVAILABLE)))
    }

    @Test
    fun `重试前置条件变化会要求重新规划`() {
        val original = ContinuationCheckpoint("r", "p", 1, 1, 1)
        val current = original.copy(resourceEpoch = 2)
        assertEquals(AgentContinuation.ReplanRequired("PRECONDITION_CHANGED"), AgentContinuation.RetryTool(original).validate(current))
    }
}
