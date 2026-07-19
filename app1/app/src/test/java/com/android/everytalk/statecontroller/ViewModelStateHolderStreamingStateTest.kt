package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.Message
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
}
