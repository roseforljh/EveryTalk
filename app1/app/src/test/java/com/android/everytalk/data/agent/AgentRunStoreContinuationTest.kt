package com.android.everytalk.data.agent

import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import com.android.everytalk.data.DataClass.ProviderTurnContinuation
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.database.daos.AgentDao
import com.android.everytalk.data.database.entities.ProviderContinuationStateEntity
import com.android.everytalk.data.database.entities.AgentContextSnapshotEntity
import com.android.everytalk.data.database.entities.AgentRequestEntity
import com.android.everytalk.data.database.entities.AgentRunEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentRunStoreContinuationTest {
    private val dao = mockk<AgentDao>(relaxed = true)
    private val store = AgentRunStore(dao)
    private val request = ChatRequest(
        messages = emptyList(),
        provider = "OpenAI",
        channel = "OpenAI Chat Completions",
        apiAddress = "https://example.test/v1",
        apiKey = "secret",
        model = "model-1",
    )

    init {
        coEvery { dao.hasFinalAssistantForRequest(any()) } returns true
    }

    @Test
    fun `匹配全部身份和指纹时恢复continuation`() = runBlocking {
        val continuation = ProviderTurnContinuation(
            ModelParameterProtocol.OPENAI_COMPATIBLE,
            "{\"id\":\"assistant-1\"}",
            assistantMessageId = "assistant:request-1",
        )
        coEvery { dao.getContinuationState(any(), any(), any(), any(), any(), any()) } returns state(
            opaqueStateJson = Json.encodeToString(ProviderTurnContinuation.serializer(), continuation),
        )

        val restored = store.loadContinuation("session-1", "config-1", request, "system-1", "tools-1", null)

        assertEquals(continuation, restored)
        coVerify(exactly = 0) { dao.deleteContinuationState(any()) }
    }

    @Test
    fun `系统提示工具schema或压缩点变化时删除continuation`() = runBlocking {
        coEvery { dao.getContinuationState(any(), any(), any(), any(), any(), any()) } returns state()

        assertNull(store.loadContinuation("session-1", "config-1", request, "changed", "tools-1", null))
        assertNull(store.loadContinuation("session-1", "config-1", request, "system-1", "changed", null))
        assertNull(store.loadContinuation("session-1", "config-1", request, "system-1", "tools-1", "compaction-2"))

        coVerify(exactly = 3) { dao.deleteContinuationState("state-1") }
    }

    @Test
    fun `损坏JSON或载荷协议不一致时删除continuation`() = runBlocking {
        coEvery { dao.getContinuationState(any(), any(), any(), any(), any(), any()) } returns state(
            opaqueStateJson = "not-json",
        )
        assertNull(store.loadContinuation("session-1", "config-1", request, "system-1", "tools-1", null))

        val wrongProtocol = ProviderTurnContinuation(ModelParameterProtocol.ANTHROPIC, "{}")
        coEvery { dao.getContinuationState(any(), any(), any(), any(), any(), any()) } returns state(
            opaqueStateJson = Json.encodeToString(ProviderTurnContinuation.serializer(), wrongProtocol),
        )
        assertNull(store.loadContinuation("session-1", "config-1", request, "system-1", "tools-1", null))

        coVerify(exactly = 2) { dao.deleteContinuationState("state-1") }
    }

    @Test
    fun `continuation找不到所属完整Assistant时删除`() = runBlocking {
        coEvery { dao.getContinuationState(any(), any(), any(), any(), any(), any()) } returns state()
        coEvery { dao.hasFinalAssistantForRequest("request-1") } returns false

        assertNull(store.loadContinuation("session-1", "config-1", request, "system-1", "tools-1", null))

        coVerify(exactly = 1) { dao.deleteContinuationState("state-1") }
    }

    @Test
    fun `协议配置端点或模型变化时查询不到旧continuation`() = runBlocking {
        coEvery { dao.getContinuationState(any(), any(), any(), any(), any(), any()) } returns null

        assertNull(store.loadContinuation("session-1", "config-2", request.copy(apiAddress = "https://other.test", model = "model-2"), "system-1", "tools-1", null))
        assertNull(store.loadContinuation("session-1", "config-2", request.copy(channel = "Anthropic", provider = "Anthropic"), "system-1", "tools-1", null))

        coVerify(exactly = 1) {
            dao.getContinuationState(
                "session-1",
                "config-2",
                ModelParameterProtocol.OPENAI_COMPATIBLE.name,
                "OpenAI",
                "https://other.test",
                "model-2",
            )
        }
        coVerify(exactly = 1) {
            dao.getContinuationState(
                "session-1",
                "config-2",
                ModelParameterProtocol.ANTHROPIC.name,
                "Anthropic",
                "https://example.test/v1",
                "model-1",
            )
        }
    }

    @Test
    fun `恢复快照保留请求参数但不保存APIKey`() = runBlocking {
        val run = com.android.everytalk.data.database.entities.AgentRunEntity(
            id = "run-1",
            sessionId = "session-1",
            userMessageId = "user-1",
            visibleAssistantMessageId = "assistant-1",
            configIdSnapshot = "config-1",
            requestSnapshotJson = kotlinx.serialization.json.Json.encodeToString(
                AgentRequestSnapshot.serializer(),
                AgentRequestSnapshot(
                    messages = emptyList(),
                    provider = "OpenAI",
                    channel = "OpenAI Chat Completions",
                    apiAddress = "https://example.test/v1",
                    model = "model-1",
                    forceGoogleReasoningPrompt = true,
                ),
            ),
            status = AgentRunStatus.WAITING_APPROVAL.name,
            currentRequestOrdinal = 1,
            terminalReason = null,
            createdAt = 1L,
            updatedAt = 1L,
        )

        val restored = store.restoreChatRequest(run, "fresh-secret")

        assertEquals("fresh-secret", restored?.apiKey)
        assertEquals(true, restored?.forceGoogleReasoningPrompt)
        org.junit.Assert.assertFalse(run.requestSnapshotJson.orEmpty().contains("fresh-secret"))
    }

    @Test
    fun `恢复请求关联旧请求并递增attempt`() = runBlocking {
        val run = AgentRunEntity(
            id = "run-retry",
            sessionId = "session-1",
            userMessageId = "user-1",
            visibleAssistantMessageId = "assistant-1",
            configIdSnapshot = "config-1",
            requestSnapshotJson = null,
            status = AgentRunStatus.MODEL_CONTINUATION_PENDING.name,
            currentRequestOrdinal = 1,
            terminalReason = null,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val interrupted = AgentRequestEntity(
            id = "request-old",
            runId = run.id,
            ordinal = 1,
            purpose = AgentRequestPurpose.AGENT_TURN.name,
            modelTurnOrdinal = 1,
            attempt = 2,
            retryOfRequestId = null,
            provider = "OpenAI",
            endpoint = "https://example.test/v1",
            model = "model-1",
            payloadFingerprint = "old",
            status = AgentRequestStatus.INTERRUPTED.name,
            finishReason = "connection_failed",
            startedAt = 1L,
            firstEventAt = null,
            finishedAt = 2L,
        )
        val snapshot = AgentContextSnapshotEntity(
            requestId = "request-new",
            systemPromptTokens = 0,
            conversationTextTokens = 0,
            mediaTokens = 0,
            toolSchemaTokens = 0,
            protocolOverheadTokens = 0,
            estimatedPromptTokens = 0,
            reservedOutputTokens = 0,
            contextWindowTokens = 8_192,
            activeContextTokens = 0,
            calibrationTokens = 0,
            compactionId = null,
            transcriptFingerprint = "new",
            source = "TEST",
        )

        val created = store.createRequest(
            run = run,
            ordinal = 2,
            purpose = AgentRequestPurpose.AGENT_TURN,
            modelTurnOrdinal = 1,
            provider = "OpenAI",
            endpoint = "https://example.test/v1",
            model = "model-1",
            transcriptFingerprint = "new",
            snapshot = snapshot,
            retryOfRequest = interrupted,
        )

        assertEquals(3, created.attempt)
        assertEquals("request-old", created.retryOfRequestId)
    }

    @Test
    fun `新恢复快照分块落库并可完整还原`() = runBlocking {
        val runSlot = slot<com.android.everytalk.data.database.entities.AgentRunEntity>()
        val chunksSlot = slot<List<com.android.everytalk.data.database.entities.AgentRunSnapshotChunkEntity>>()
        coEvery {
            dao.startRunSupersedingWaitingApprovals(
                capture(runSlot),
                capture(chunksSlot),
                any(),
            )
        } returns Unit
        val largeText = "含 Emoji 😀 " + "x".repeat(2_500_000)
        val largeRequest = request.copy(
            messages = listOf(
                SimpleTextApiMessage(
                    id = "user-large",
                    role = "user",
                    content = largeText,
                ),
            ),
        )

        val run = store.createRun(
            sessionId = "session-1",
            userMessageId = "user-large",
            visibleAssistantMessageId = "assistant-large",
            configIdSnapshot = "config-1",
            request = largeRequest,
        )

        assertNull(run.requestSnapshotJson)
        org.junit.Assert.assertTrue(chunksSlot.captured.size > 1)
        org.junit.Assert.assertTrue(chunksSlot.captured.all { it.payload.length <= 65_537 })
        coEvery { dao.getRunSnapshotChunkPage(run.id, any(), any()) } answers {
            val afterChunkIndex = invocation.args[1] as Int
            val limit = invocation.args[2] as Int
            chunksSlot.captured.filter { it.chunkIndex > afterChunkIndex }.take(limit)
        }

        // 新 Store 模拟进程重启，确保走真实分页读取，不能命中创建时的内存缓存。
        val restored = AgentRunStore(dao).restoreChatRequest(run, "fresh-secret")

        assertEquals(largeText, (restored?.messages?.single() as? SimpleTextApiMessage)?.content)
        assertEquals("fresh-secret", restored?.apiKey)
        coVerify(atLeast = 5) { dao.getRunSnapshotChunkPage(run.id, any(), 8) }
    }

    private fun state(opaqueStateJson: String = Json.encodeToString(
        ProviderTurnContinuation.serializer(),
        ProviderTurnContinuation(
            protocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
            payloadJson = "{}",
            assistantMessageId = "assistant:request-1",
        ),
    )) = ProviderContinuationStateEntity(
        id = "state-1",
        sessionId = "session-1",
        configId = "config-1",
        protocol = ModelParameterProtocol.OPENAI_COMPATIBLE.name,
        provider = request.provider,
        endpoint = request.apiAddress.orEmpty(),
        model = request.model,
        systemPromptFingerprint = "system-1",
        toolSchemaFingerprint = "tools-1",
        summarizedThroughItemId = null,
        opaqueStateJson = opaqueStateJson,
        updatedAt = 1L,
    )
}
