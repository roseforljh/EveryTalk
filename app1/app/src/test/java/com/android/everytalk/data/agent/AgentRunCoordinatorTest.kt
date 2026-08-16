package com.android.everytalk.data.agent

import com.android.everytalk.data.database.entities.AgentRunEntity
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunCoordinatorTest {
    private val run = AgentRunEntity(
        id = "run-1",
        sessionId = "session-1",
        userMessageId = "user-1",
        visibleAssistantMessageId = "assistant-1",
        configIdSnapshot = null,
        requestSnapshotJson = null,
        status = AgentRunStatus.WAITING_REMOTE_EXECUTION.name,
        currentRequestOrdinal = 1,
        terminalReason = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun `首次运行按消息登记时恢复器仍识别为活跃`() {
        val activeJob = Job()

        assertTrue(
            isAgentRunActive(
                activeJobs = mapOf("message:${run.visibleAssistantMessageId}" to activeJob),
                run = run,
            )
        )

        activeJob.cancel()
        assertFalse(
            isAgentRunActive(
                activeJobs = mapOf("message:${run.visibleAssistantMessageId}" to activeJob),
                run = run,
            )
        )
    }

    @Test
    fun `只有使用过VPS且整个Run终止才发送最终通知`() {
        assertTrue(shouldNotifyAgentRunTerminal(AgentRunStatus.COMPLETED, computerExecutionCount = 2))
        assertTrue(shouldNotifyAgentRunTerminal(AgentRunStatus.FAILED, computerExecutionCount = 1))
        assertFalse(shouldNotifyAgentRunTerminal(AgentRunStatus.COMPLETED, computerExecutionCount = 0))
        assertFalse(shouldNotifyAgentRunTerminal(AgentRunStatus.WAITING_MODEL, computerExecutionCount = 1))
    }

    @Test
    fun `恢复失败逐步退避且最长一分钟`() {
        assertEquals(2_000L, agentResumeRetryDelayMillis(1))
        assertEquals(5_000L, agentResumeRetryDelayMillis(2))
        assertEquals(15_000L, agentResumeRetryDelayMillis(3))
        assertEquals(30_000L, agentResumeRetryDelayMillis(4))
        assertEquals(60_000L, agentResumeRetryDelayMillis(20))
    }
}
