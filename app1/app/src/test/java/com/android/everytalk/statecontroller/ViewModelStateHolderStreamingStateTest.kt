package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.agent.AgentRunStatus
import com.android.everytalk.data.database.entities.AgentRunEntity
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.util.debug.PerformanceMonitor
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ViewModelStateHolderStreamingStateTest {

    private lateinit var stateHolder: ViewModelStateHolder
    private lateinit var scope: TestScope

    @Before
    fun setUp() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        mockkObject(PerformanceMonitor)
        justRun { PerformanceMonitor.recordBufferFlush(any(), any(), any()) }
        justRun { PerformanceMonitor.recordStateFlowFlush(any(), any(), any()) }
        every { PerformanceMonitor.enabled } returns false

        scope = TestScope(StandardTestDispatcher())
        stateHolder = ViewModelStateHolder().also { it.initializeBufferScope(scope) }
    }

    @After
    fun tearDown() {
        stateHolder.streamingMessageStateManager.cleanup()
        unmockkAll()
    }

    @Test
    fun `切换会话清空引用与分支草稿但迁移到稳定ID时保留`() {
        val quote = com.android.everytalk.data.DataClass.MessageQuoteReference(
            sourceConversationId = "a", sourceMessageId = "m", quotedText = "片段",
        )
        fun fillDrafts() {
            stateHolder.composerQuoteReferences.add(quote)
            stateHolder.forkQuoteReferences.add(quote)
            stateHolder.forkSelectedMediaItems.add(
                com.android.everytalk.models.SelectedMediaItem.Audio(id = "audio-1", mimeType = "audio/3gpp")
            )
            stateHolder._forkComposerText.value = "草稿"
        }
        stateHolder.setCurrentConversationId("a")
        fillDrafts()
        stateHolder.setCurrentConversationId("b")
        assertTrue(stateHolder.composerQuoteReferences.isEmpty())
        assertTrue(stateHolder.forkQuoteReferences.isEmpty())
        assertTrue(stateHolder.forkSelectedMediaItems.isEmpty())
        assertEquals("", stateHolder._forkComposerText.value)

        // 首条消息落库后临时 ID 迁到稳定 ID，仍是同一会话，草稿不能丢。
        stateHolder.setCurrentConversationId("new_chat_1")
        fillDrafts()
        stateHolder.migratedConversationIds["new_chat_1"] = "stable_1"
        stateHolder.setCurrentConversationId("stable_1")
        assertEquals(1, stateHolder.composerQuoteReferences.size)
        assertEquals("草稿", stateHolder._forkComposerText.value)
    }

    @Test
    fun `思考分片按批提交且终态同步不丢尾部`() {
        val messageId = "reasoning-batched"
        stateHolder.createStreamingBuffer(messageId)
        stateHolder.messages.add(Message(id = messageId, text = "", sender = Sender.AI))

        stateHolder.queueReasoningChunk(messageId, "甲")
        stateHolder.queueReasoningChunk(messageId, "乙")
        stateHolder.queueReasoningChunk(messageId, "丙")

        // 首片立即可见，后续分片在批次窗口内合并，不逐 token 改消息列表。
        assertEquals("甲", stateHolder.getStreamingReasoning(messageId).value)
        stateHolder.syncStreamingMessageToList(messageId)
        assertEquals("甲乙丙", stateHolder.getStreamingReasoning(messageId).value)
        assertEquals("甲乙丙", stateHolder.messages.single().reasoning)
        val reasoningTrace = stateHolder.messages.single().executionTrace
            .filterIsInstance<ExecutionTraceEvent.Reasoning>()
        assertEquals(listOf("甲乙丙"), reasoningTrace.map { it.text })
    }

    @Test
    fun `reasoning chunks update only their dedicated state flow`() {
        val messageId = "reasoning-only-flow"
        stateHolder.createStreamingBuffer(messageId)
        stateHolder.messages.add(
            Message(
                id = messageId,
                text = "",
                sender = Sender.AI,
                reasoning = "第一段",
            )
        )

        stateHolder.appendReasoningToMessage(messageId, "第一段")
        stateHolder.appendReasoningToMessage(messageId, "第二段")

        assertEquals("第一段", stateHolder.messages.single().reasoning)
        assertEquals("第一段第二段", stateHolder.getStreamingReasoning(messageId).value)
    }

    @Test
    fun `terminal sync persists reasoning even when answer text is empty`() {
        val messageId = "reasoning-terminal"
        stateHolder.createStreamingBuffer(messageId)
        stateHolder.messages.add(
            Message(
                id = messageId,
                text = "",
                sender = Sender.AI,
                reasoning = "第一段",
            )
        )
        stateHolder.appendReasoningToMessage(messageId, "第一段")
        stateHolder.appendReasoningToMessage(messageId, "第二段")

        stateHolder.syncStreamingMessageToList(messageId)

        assertEquals("第一段第二段", stateHolder.messages.single().reasoning)
        assertFalse(stateHolder.messages.single().contentStarted)
    }

    @Test
    fun `snapshot sync does not finalize an active stream`() {
        val messageId = "snapshot-stream"
        stateHolder.createStreamingBuffer(messageId)
        stateHolder.messages.add(Message(id = messageId, text = "", sender = Sender.AI))

        stateHolder.appendContentToMessage(messageId, "A")
        stateHolder.syncStreamingSnapshotToList(messageId)

        assertEquals("A", stateHolder.messages.single().text)
        assertTrue(stateHolder.streamingMessageStateManager.isStreaming(messageId))

        stateHolder.appendContentToMessage(messageId, "B")
        stateHolder.syncStreamingMessageToList(messageId)

        assertEquals("AB", stateHolder.messages.single().text)
        assertFalse(stateHolder.streamingMessageStateManager.isStreaming(messageId))
    }

    @Test
    fun `ordered content trace follows the existing streaming buffer cadence`() {
        val messageId = "ordered-buffered-trace"
        stateHolder.createStreamingBuffer(messageId)
        stateHolder.messages.add(Message(id = messageId, text = "", sender = Sender.AI))

        // 第一段沿用现有首帧立即刷新的规则，保证用户立刻看到内容。
        stateHolder.appendContentToMessage(messageId, "A")
        assertEquals("A", stateHolder.messages.single().orderedContentText())

        // 第二段先留在缓冲区，不能重新退化成每个 token 都改消息列表。
        stateHolder.appendContentToMessage(messageId, "B")
        assertEquals("A", stateHolder.messages.single().orderedContentText())

        stateHolder.flushStreamingBuffer(messageId)
        assertEquals("AB", stateHolder.messages.single().orderedContentText())
    }

    @Test
    fun `后台续写重新挂接界面并在真正结束后归位`() {
        val messageId = "resumed-agent-message"
        stateHolder.messages.add(Message(id = messageId, text = "已有内容", sender = Sender.AI))

        assertTrue(stateHolder.attachTextAgentUi(messageId))
        assertTrue(stateHolder._isTextApiCalling.value)
        assertEquals(messageId, stateHolder._currentTextStreamingAiMessageId.value)

        stateHolder.messages.add(Message(id = "other-message", text = "", sender = Sender.AI))
        assertFalse(stateHolder.attachTextAgentUi("other-message"))
        assertEquals(messageId, stateHolder._currentTextStreamingAiMessageId.value)

        stateHolder.detachTextAgentUi(messageId)

        assertFalse(stateHolder._isTextApiCalling.value)
        assertNull(stateHolder._currentTextStreamingAiMessageId.value)
    }

    @Test
    fun `同步历史只用完整消息而不被摘要账本二次投影覆盖`() {
        val baseline = prepareRestoration()
        val archived = run(AgentRunStatus.COMPLETED).copy(terminalReason = "SYNC_HISTORY_ONLY")
        assertFalse(stateHolder.reconcileAgentRun(archived, fullTrace(), baseline, false))
        assertEquals(baseline, stateHolder.messages.single())
    }

    @Test
    fun `后台完成后补齐半截正文并清除停止按钮`() {
        val message = prepareRestoration()
        assertTrue(stateHolder.reconcileAgentRun(run(AgentRunStatus.COMPLETED), fullTrace(), message, false))
        assertEquals("完整正文", stateHolder.messages.single().text)
        assertEquals("完整思考", stateHolder.messages.single().reasoning)
        assertEquals("完整正文", stateHolder.messages.single().orderedContentText())
        assertFalse(stateHolder._isTextApiCalling.value)
        assertNull(stateHolder._currentTextStreamingAiMessageId.value)
    }

    @Test
    fun `读取期间新内容和用户停止不会被旧快照覆盖`() {
        val baseline = prepareRestoration()
        stateHolder.messages[0] = baseline.copy(text = "更新的正文")
        assertFalse(stateHolder.reconcileAgentRun(run(AgentRunStatus.COMPLETED), fullTrace(), baseline, false))
        assertEquals("更新的正文", stateHolder.messages.single().text)
        stateHolder.messages[0] = baseline.copy(executionStatus = "任务已取消", executionFinishedAt = 3L)
        stateHolder.detachTextAgentUi(baseline.id)
        assertFalse(stateHolder.reconcileAgentRun(run(AgentRunStatus.MODEL_CONTINUATION_PENDING), fullTrace(), baseline, false))
        assertFalse(stateHolder._isTextApiCalling.value)
    }

    @Test
    fun `旧任务的终态不能清除新回复或跨会话写入`() {
        val baseline = prepareRestoration()
        stateHolder.detachTextAgentUi(baseline.id)
        stateHolder.messages.add(Message(id = "new", text = "", sender = Sender.AI))
        stateHolder.attachTextAgentUi("new")
        assertTrue(stateHolder.reconcileAgentRun(run(AgentRunStatus.COMPLETED), fullTrace(), baseline, false))
        assertEquals("new", stateHolder._currentTextStreamingAiMessageId.value)
        val updated = stateHolder.messages[0]
        stateHolder._currentConversationId.value = "other-session"
        assertFalse(stateHolder.reconcileAgentRun(run(AgentRunStatus.CANCELLED), emptyList(), updated, false))
        assertTrue(stateHolder.messages.isEmpty())
        assertEquals(updated, stateHolder.textMessagesForConversation("session-1")[0])
    }

    @Test
    fun `模型重连等待不能被当成完成而孤立本地任务必须显示等待确认`() {
        val baseline = prepareRestoration()
        stateHolder.reconcileAgentRun(run(AgentRunStatus.MODEL_CONTINUATION_PENDING), fullTrace(), baseline, false)
        assertTrue(stateHolder._isTextApiCalling.value)
        assertEquals("正在恢复回复...", stateHolder.messages.single().executionStatus)
        val updated = stateHolder.messages.single()
        stateHolder.reconcileAgentRun(run(AgentRunStatus.STREAMING_MODEL), fullTrace(), updated, false)
        assertFalse(stateHolder._isTextApiCalling.value)
        assertEquals("等待恢复确认", stateHolder.messages.single().executionStatus)
        assertNull(stateHolder.messages.single().executionFinishedAt)
    }

    @Test
    fun `执行器活跃时旧持久内容不能覆盖实时增量`() {
        val baseline = prepareRestoration().copy(text = "实时更新")
        stateHolder.messages[0] = baseline
        stateHolder.reconcileAgentRun(run(AgentRunStatus.STREAMING_MODEL), fullTrace(), baseline, true)
        assertEquals("实时更新", stateHolder.messages.single().text)
        assertTrue(stateHolder._isTextApiCalling.value)
    }

    @Test
    fun `停止或崩溃封存的部分回复不能被晚到Agent账本改写`() {
        val partial = prepareRestoration().copy(text = "已生成的部分", reasoning = "部分思考", isIncomplete = true, executionFinishedAt = 3)
        stateHolder.messages[0] = partial
        assertTrue(stateHolder.reconcileAgentRun(run(AgentRunStatus.CANCELLED), fullTrace(), partial, false))
        assertEquals(partial.text, stateHolder.messages.single().text)
        assertEquals(partial.reasoning, stateHolder.messages.single().reasoning)
        assertTrue(stateHolder.messages.single().isIncomplete)
    }

    @Test
    fun `失败任务恢复后聊天正文和过程状态均包含原因且重复恢复不叠加`() {
        val baseline = prepareRestoration()
        val failedRun = run(AgentRunStatus.FAILED).copy(terminalReason = "SSH连接超时")
        assertTrue(stateHolder.reconcileAgentRun(failedRun, fullTrace(), baseline, false))
        val restored = stateHolder.messages.single()
        assertTrue(restored.isError)
        assertEquals("任务失败：SSH连接超时", restored.executionStatus)
        assertTrue(restored.text.contains("SSH连接超时"))
        assertFalse(stateHolder._isTextApiCalling.value)
        assertTrue(stateHolder.reconcileAgentRun(failedRun, emptyList(), restored, false))
        assertEquals(restored.text, stateHolder.messages.single().text)
    }

    @Test
    fun `没有过程记录的失败任务也在正文显示原因`() {
        val baseline = prepareRestoration()
        stateHolder.reconcileAgentRun(
            run(AgentRunStatus.FAILED).copy(terminalReason = "模型服务返回 HTTP 403"),
            emptyList(), baseline, false,
        )
        assertTrue(stateHolder.messages.single().text.contains("HTTP 403"))
    }

    @Test
    fun `旧任务未记录终态原因时保留实时错误且收尾不能清掉`() {
        val baseline = prepareRestoration().copy(isError = true, executionStatus = "请求超时，请重试")
        stateHolder.messages[0] = baseline
        stateHolder.reconcileAgentRun(run(AgentRunStatus.FAILED), fullTrace(), baseline, false)
        stateHolder.clearMessageStatus(baseline.id, false)
        assertEquals("请求超时，请重试", stateHolder.messages.single().executionStatus)

        stateHolder.messages[0] = baseline.copy(isError = false, executionStatus = "正在执行")
        stateHolder.clearMessageStatus(baseline.id, false)
        assertNull(stateHolder.messages.single().executionStatus)
    }

    @Test
    fun `账本的HTTP摘要不能覆盖服务商的具体错误说明`() {
        val detail = "网络错误：HTTP 400：Function call is missing a thought_signature"
        val baseline = prepareRestoration().copy(isError = true, executionStatus = detail)
        stateHolder.messages[0] = baseline
        stateHolder.reconcileAgentRun(
            run(AgentRunStatus.FAILED).copy(terminalReason = "HTTP 400"),
            fullTrace(), baseline, false,
        )
        assertEquals(detail, stateHolder.messages.single().executionStatus)
        assertTrue(stateHolder.messages.single().text.contains("thought_signature"))
    }

    @Test
    fun `切换会话保留准备阶段列表和活跃流快照并按任务身份收尾`() {
        stateHolder.setCurrentConversationId("A")
        val a = stateHolder.messages
        a.add(Message(id = "a", text = "实时A", sender = Sender.AI))
        val jobA = Job()
        stateHolder.registerTextJob("A", "a", jobA)
        stateHolder.setCurrentConversationId("B")
        val b = stateHolder.messages
        b.add(Message(id = "b", text = "实时B", sender = Sender.AI))
        val jobB = Job()
        stateHolder.registerTextJob("B", "b", jobB)
        a.add(Message(id = "prepared-a", text = "准备完成", sender = Sender.User))
        assertEquals(listOf("b"), b.map { it.id })
        stateHolder.loadTextConversationMessages("A", listOf(Message(id = "a", text = "旧快照", sender = Sender.AI)))
        assertEquals(listOf("实时A", "准备完成"), a.map { it.text })
        stateHolder.completeTextJob("A", "a", jobA)
        assertTrue(stateHolder._isTextApiCalling.value)
        assertEquals("b", stateHolder._currentTextStreamingAiMessageId.value)
        assertTrue(jobB.isActive)
        stateHolder.setCurrentConversationId("A")
        stateHolder.restoreTextConversationUi()
        assertTrue(stateHolder.messages === a)
        assertEquals(listOf("实时A", "准备完成"), stateHolder.messages.map { it.text })
        stateHolder.setCurrentConversationId("B")
        stateHolder.restoreTextConversationUi()
        val replacement = Job()
        stateHolder.registerTextJob("B", "b-new", replacement)
        stateHolder.completeTextJob("B", "b", jobB)
        assertTrue(stateHolder.textApiJob === replacement)
        assertEquals("b-new", stateHolder._currentTextStreamingAiMessageId.value)
        jobA.cancel()
        jobB.cancel()
        replacement.cancel()
    }

    @Test
    fun `未登记消息按实际列表找回且未知消息不回落当前会话`() {
        stateHolder.setCurrentConversationId("A")
        val a = stateHolder.messages
        a.add(Message(id = "a", text = "A", sender = Sender.AI))
        stateHolder.setCurrentConversationId("B")
        stateHolder.messages.add(Message(id = "b", text = "B", sender = Sender.AI))
        assertTrue(stateHolder.textMessagesForMessage("a") === a)
        assertEquals("A", stateHolder.textMessageConversationIds["a"])
        assertTrue(stateHolder.textMessagesForMessage("missing").isEmpty())
        assertEquals(listOf("b"), stateHolder.messages.map { it.id })
        stateHolder.clearTextConversationCache("A")
        assertTrue(stateHolder.textMessagesForMessage("a").isEmpty())
    }

    @Test
    fun `新会话首次落库迁移ID时列表和运行登记跟随且旧ID可解析`() {
        stateHolder.setCurrentConversationId("new_chat_1")
        val list = stateHolder.messages
        list.add(Message(id = "u1", text = "问题", sender = Sender.User))
        val job = Job()
        stateHolder.registerTextJob("new_chat_1", "ai-1", job)
        stateHolder.migratedConversationIds["new_chat_1"] = "u1"
        stateHolder.migrateTextConversation("new_chat_1", "u1")
        assertEquals("u1", stateHolder._currentConversationId.value)
        assertTrue(stateHolder.messages === list)
        assertTrue(stateHolder.textApiJobs["u1"] === job)
        assertEquals("u1", stateHolder.resolveConversationId("new_chat_1"))
        stateHolder.completeTextJob("new_chat_1", "ai-1", job)
        assertTrue(stateHolder.textApiJobs.isEmpty())
        assertTrue(!stateHolder._isTextApiCalling.value)
        job.cancel()
    }

    private fun prepareRestoration(): Message {
        stateHolder._currentConversationId.value = "session-1"
        val message = Message(id = "assistant-1", text = "完整", sender = Sender.AI)
        stateHolder.messages.add(message)
        stateHolder.attachTextAgentUi(message.id)
        return message
    }

    private fun fullTrace(): List<ExecutionTraceEvent> = listOf(
        ExecutionTraceEvent.Reasoning("完整思考", 1L),
        ExecutionTraceEvent.Content("完整正文", 2L),
    )

    private fun run(status: AgentRunStatus) = AgentRunEntity(
        id = "run-1", sessionId = "session-1", userMessageId = "user-1",
        visibleAssistantMessageId = "assistant-1", configIdSnapshot = null,
        requestSnapshotJson = null, status = status.name, currentRequestOrdinal = 1,
        terminalReason = null, createdAt = 1L, updatedAt = 2L,
    )

    private fun Message.orderedContentText(): String = executionTrace
        .filterIsInstance<ExecutionTraceEvent.Content>()
        .joinToString("") { it.text }
}
