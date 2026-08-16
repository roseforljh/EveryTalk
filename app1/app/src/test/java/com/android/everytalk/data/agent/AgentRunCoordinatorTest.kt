package com.android.everytalk.data.agent

import com.android.everytalk.data.database.entities.AgentRunEntity
import kotlinx.coroutines.Job
import java.io.File
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

    @Test
    fun `前台Agent事件使用挂起发送保持单通道顺序`() {
        val source = agentRunCoordinatorSource()
        val firstRunCollector = source.substringAfter("agentLoop.run(request).collect { event ->")
            .substringBefore("notifyTerminalRun")

        assertTrue(firstRunCollector.contains("send(event)"))
        assertFalse(firstRunCollector.contains("trySend(event)"))
    }

    private fun agentRunCoordinatorSource(): String {
        val relativePath = "data/agent/AgentRunCoordinator.kt"
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/$relativePath"),
            File("app/src/main/java/com/android/everytalk/$relativePath"),
            File("app1/app/src/main/java/com/android/everytalk/$relativePath"),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "找不到 $relativePath"
        }.readText(Charsets.UTF_8)
    }
}
