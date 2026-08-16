package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
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

    private fun Message.orderedContentText(): String = executionTrace
        .filterIsInstance<ExecutionTraceEvent.Content>()
        .joinToString("") { it.text }
}
