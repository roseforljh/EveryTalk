package com.android.everytalk.statecontroller

import com.android.everytalk.util.debug.PerformanceMonitor
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StreamingMessageStateManagerRenderStateTest {

    private lateinit var subject: StreamingMessageStateManager

    @Before
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.v(any(), any()) } returns 0

        mockkObject(PerformanceMonitor)
        justRun { PerformanceMonitor.recordStateFlowFlush(any(), any(), any()) }

        subject = StreamingMessageStateManager()
    }

    @After
    fun tearDown() {
        subject.cleanup()
        unmockkAll()
    }

    @Test
    fun `updateContent同步生成结构化数学块`() {
        val messageId = "math-msg"

        subject.startStreaming(messageId)
        subject.updateContent(messageId, "证明：\\(x+1\\)")

        val renderState = subject.getCurrentRenderState(messageId)
        assertTrue(renderState.isStreaming)
        assertFalse(renderState.isComplete)
        assertEquals("证明：\\(x+1\\)", renderState.content)
        assertTrue(renderState.blocks.any { it is com.android.everytalk.ui.components.streaming.StreamBlock.MathInline })
    }

    @Test
    fun `finalizeMessage会把渲染状态标记为完成`() {
        val messageId = "done-msg"

        subject.startStreaming(messageId)
        subject.updateContent(messageId, "${'$'}${'$'}x^2${'$'}${'$'}")
        subject.finalizeMessage(messageId)

        val renderState = subject.getCurrentRenderState(messageId)
        assertFalse(renderState.isStreaming)
        assertTrue(renderState.isComplete)
        assertEquals("${'$'}${'$'}x^2${'$'}${'$'}", renderState.content)
        assertFalse(renderState.hasPendingMath)
    }
}
