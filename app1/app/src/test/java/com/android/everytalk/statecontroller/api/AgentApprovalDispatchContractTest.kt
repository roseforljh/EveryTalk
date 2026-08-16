package com.android.everytalk.statecontroller.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentApprovalDispatchContractTest {
    @Test
    fun `审批点击直接交给Room校验且不依赖瞬时UI列表`() {
        val source = sourceFile().readText(Charsets.UTF_8)
        val handler = source.substringAfter("fun respondToAgentApproval(")
            .substringBefore("fun respondToSkillSecretApproval(")

        assertTrue(handler.contains("agentRunStore.decideApproval(runId, approvalRequestId, decision)"))
        assertFalse(handler.contains("current == null && currentAgent == null && currentSecret == null"))
    }

    @Test
    fun `审批刷新只保留同会话最新Run并终止旧气泡计时`() {
        val source = sourceFile().readText(Charsets.UTF_8)
        val refresh = source.substringAfter("private suspend fun refreshPendingAgentApprovals()")
            .substringBefore("private suspend fun resumeDecidedAgentRuns()")

        assertTrue(refresh.contains("waitingRuns.distinctBy { it.sessionId }"))
        assertTrue(refresh.contains("AgentTerminalReasons.SUPERSEDED_BY_NEW_RUN"))
        assertTrue(refresh.contains("\"已由新消息取代\""))
    }

    private fun sourceFile(): File {
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/statecontroller/api/ApiHandler.kt"),
            File("app/src/main/java/com/android/everytalk/statecontroller/api/ApiHandler.kt"),
            File("app1/app/src/main/java/com/android/everytalk/statecontroller/api/ApiHandler.kt"),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile))
    }
}
