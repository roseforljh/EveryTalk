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
        stateHolder._currentConversationId.value = "other-session"
        val updated = stateHolder.messages[0]
        assertFalse(stateHolder.reconcileAgentRun(run(AgentRunStatus.CANCELLED), emptyList(), updated, false))
        assertEquals(updated, stateHolder.messages[0])
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
