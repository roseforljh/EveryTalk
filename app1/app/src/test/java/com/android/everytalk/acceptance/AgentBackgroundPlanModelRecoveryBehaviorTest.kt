package com.android.everytalk.acceptance

import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ModelTokenLimits
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.agent.AgentLoop
import com.android.everytalk.data.agent.AgentLoopRequest
import com.android.everytalk.data.agent.AgentRequestPurpose
import com.android.everytalk.data.agent.AgentRequestStatus
import com.android.everytalk.data.agent.AgentRunStatus
import com.android.everytalk.data.agent.AgentRunStore
import com.android.everytalk.data.database.entities.AgentRequestEntity
import com.android.everytalk.data.database.entities.AgentRunEntity
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.ModelTurnTransport
import com.android.everytalk.data.network.NetworkUtils
import io.mockk.coEvery
import io.mockk.mockk
import io.ktor.http.HttpStatusCode
import java.net.SocketException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 验证模型网络中断会保存并恢复原 AgentRun，而不会被页面生命周期永久终止。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentBackgroundPlanModelRecoveryBehaviorTest {
    @Before
    fun clearKoinBeforeTest() {
        stopKoin()
    }

    @After
    fun clearKoinAfterTest() {
        stopKoin()
    }

    @Test
    fun `Socket连接中断必须产生结构化可重试错误`() {
        val result = NetworkUtils.handleConnectionError(
            SocketException("Software caused connection abort"),
            "OpenAI",
        )

        assertEquals("connection_aborted", result.error.code)
        assertEquals("retryable_network", result.error.type)
        assertEquals("connection_failed", result.finish.reason)
    }

    @Test
    fun `暂时性HTTP错误必须产生结构化可重试错误`() = runTest {
        listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable).forEach { status ->
            val result = NetworkUtils.handleApiError(
                statusCode = status,
                errorBody = """{"error":{"message":"temporary failure"}}""",
                apiName = "Gemini",
            )

            assertEquals(status.value, result.error.upstreamStatus)
            assertEquals("retryable_network", result.error.type)
        }
    }

    @Test
    fun `暂时性HTTP错误后原Run必须等待后台续写而不是永久失败`() = runTest {
        val statuses = mutableListOf<AgentRunStatus>()
        val run = runEntity()
        val request = chatRequest()
        val requestEntity = requestEntity(run)
        val store = mockk<AgentRunStore>(relaxed = true)
        val retryableError = NetworkUtils.handleApiError(
            statusCode = HttpStatusCode.ServiceUnavailable,
            errorBody = """{"error":{"message":"temporary failure"}}""",
            apiName = "Gemini",
        )

        coEvery { store.expandTranscript(run.sessionId, any()) } returns request.messages
        coEvery { store.appendRunTranscript(run.id, any()) } returns request.messages
        coEvery { store.latestCompaction(run.sessionId) } returns null
        coEvery { store.completedCompactionCount(run.id) } returns 0
        coEvery { store.nextModelTurnOrdinal(run.id) } returns 1
        coEvery { store.finalExecutedToolCalls(run.id) } returns emptyList()
        coEvery { store.loadContinuation(any(), any(), any(), any(), any(), any()) } returns null
        coEvery { store.createRequest(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns requestEntity
        coEvery { store.updateRequest(any(), any(), any(), any(), any(), any()) } answers {
            firstArg<AgentRequestEntity>().copy(status = secondArg<AgentRequestStatus>().name)
        }
        coEvery { store.updateRunStatus(any(), any(), any(), any()) } answers {
            val status = secondArg<AgentRunStatus>()
            statuses += status
            firstArg<AgentRunEntity>().copy(status = status.name)
        }

        val loop = AgentLoop(
            runStore = store,
            modelTransport = ModelTurnTransport {
                flowOf(
                    retryableError.error,
                    retryableError.finish,
                )
            },
        )

        loop.run(
            AgentLoopRequest(
                request = request,
                sessionId = run.sessionId,
                userMessageId = run.userMessageId,
                visibleAssistantMessageId = run.visibleAssistantMessageId,
                tokenLimits = ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 8_192),
                existingRun = run,
            )
        ).toList()

        assertTrue("连接中断后必须保存 MODEL_CONTINUATION_PENDING", AgentRunStatus.MODEL_CONTINUATION_PENDING in statuses)
        assertFalse("可重试网络中断不能把 AgentRun 永久标记 FAILED", AgentRunStatus.FAILED in statuses)
    }

    private fun chatRequest() = ChatRequest(
        messages = listOf(SimpleTextApiMessage(role = "user", content = "检查 VPS 项目")),
        provider = "OpenAI",
        channel = "OpenAI兼容",
        apiAddress = "https://example.test",
        apiKey = "test-key",
        model = "test-model",
    )

    private fun runEntity() = AgentRunEntity(
        id = "run-1",
        sessionId = "session-1",
        userMessageId = "user-1",
        visibleAssistantMessageId = "assistant-1",
        configIdSnapshot = "config-1",
        requestSnapshotJson = null,
        status = AgentRunStatus.MODEL_CONTINUATION_PENDING.name,
        currentRequestOrdinal = 0,
        terminalReason = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun requestEntity(run: AgentRunEntity) = AgentRequestEntity(
        id = "request-1",
        runId = run.id,
        ordinal = 1,
        purpose = AgentRequestPurpose.AGENT_TURN.name,
        modelTurnOrdinal = 1,
        attempt = 1,
        retryOfRequestId = null,
        provider = "OpenAI",
        endpoint = "https://example.test",
        model = "test-model",
        payloadFingerprint = "fingerprint",
        status = AgentRequestStatus.PREPARED.name,
        finishReason = null,
        startedAt = null,
        firstEventAt = null,
        finishedAt = null,
    )
}
