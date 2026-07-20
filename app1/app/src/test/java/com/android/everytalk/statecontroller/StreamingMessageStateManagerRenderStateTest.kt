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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    fun `流式公式从未闭合到闭合保持Markdown投影严格追加`() {
        val messageId = "math-monotonic"

        subject.startStreaming(messageId)
        subject.updateContent(messageId, "前文 ${'$'}x+1")
        val pending = subject.getCurrentRenderState(messageId)

        subject.updateContent(messageId, "前文 ${'$'}x+1${'$'} 后文")
        val closed = subject.getCurrentRenderState(messageId)

        assertEquals("前文 ", pending.preparedMessage.markdown)
        assertTrue(pending.preparedMessage.hasPendingFormula)
        assertTrue(closed.preparedMessage.markdown.startsWith(pending.preparedMessage.markdown))
        assertFalse(closed.preparedMessage.hasPendingFormula)
        assertEquals(listOf("x+1"), closed.preparedMessage.formulas.values.map { it.latex })
    }

    @Test
    fun `完成态恢复未闭合公式原文`() {
        val messageId = "math-pending-complete"
        val content = "前文 ${'$'}x+1"

        subject.startStreaming(messageId)
        subject.updateContent(messageId, content)
        assertEquals("前文 ", subject.getCurrentRenderState(messageId).preparedMessage.markdown)

        subject.finalizeMessage(messageId)
        val completed = subject.getCurrentRenderState(messageId)

        assertTrue(completed.isComplete)
        assertFalse(completed.isStreaming)
        assertEquals(content, completed.preparedMessage.markdown)
        assertTrue(completed.preparedMessage.hasPendingFormula)
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

    @Test
    fun `每次内容更新都会生成更高的公式内容版本`() {
        val messageId = "version-msg"

        subject.startStreaming(messageId)
        subject.updateContent(messageId, "公式：${'$'}x${'$'}")
        val first = subject.getCurrentRenderState(messageId).preparedMessage

        subject.updateContent(messageId, "公式：${'$'}x+1${'$'}")
        val second = subject.getCurrentRenderState(messageId).preparedMessage

        assertTrue(second.contentVersion > first.contentVersion)
        assertEquals(
            second.contentVersion,
            second.formulas.values.single().contentVersion,
        )
    }

    @Test
    fun `替换已提交前缀时必须按新内容重新解析`() {
        val messageId = "replacement-msg"
        val initial = "旧前缀\n\n${'$'}${'$'}x${'$'}${'$'}\n\n尾部"
        val replacement = "新前缀\n\n${'$'}${'$'}y${'$'}${'$'}\n\n尾部"

        subject.startStreaming(messageId)
        subject.updateContent(messageId, initial)
        subject.updateContent(messageId, replacement)

        val renderState = subject.getCurrentRenderState(messageId)

        assertEquals(replacement, renderState.content)
        assertEquals(listOf("y"), renderState.preparedMessage.formulas.values.map { it.latex })
    }

    @Test
    fun `未闭合代码块正文不应阻止流式刷新`() {
        val shouldDelayFlush = StreamingMessageStateManager::class.java.getDeclaredMethod(
            "shouldDelayFlush",
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
        )
        shouldDelayFlush.isAccessible = true

        val previous = "说明：\n```kotlin\n" + "val cachedLine = 0\n".repeat(16)
        val current = previous + "    println(\"最新一行\")\n"

        val shouldDelay = shouldDelayFlush.invoke(
            subject,
            previous,
            current,
            current.length - previous.length,
        ) as Boolean

        assertFalse(shouldDelay)
    }

    @Test
    fun `完整Markdown刷新频率随正文长度逐级降低`() {
        assertEquals(80L, resolveStreamingRenderFlushIntervalMs(0))
        assertEquals(80L, resolveStreamingRenderFlushIntervalMs(199))
        assertEquals(120L, resolveStreamingRenderFlushIntervalMs(200))
        assertEquals(150L, resolveStreamingRenderFlushIntervalMs(500))
        assertEquals(180L, resolveStreamingRenderFlushIntervalMs(2_000))
        assertEquals(220L, resolveStreamingRenderFlushIntervalMs(5_000))
    }

    @Test
    fun `并发追加与后台刷新不会丢失正文片段`() {
        val messageId = "concurrent-msg"
        val workerCount = 8
        val appendCount = 500
        val chunkSize = 4
        val executor = Executors.newFixedThreadPool(workerCount)

        subject.startStreaming(messageId)
        try {
            val futures = (0 until workerCount).map { workerIndex ->
                executor.submit {
                    val chunk = ('a' + workerIndex).toString().repeat(chunkSize)
                    repeat(appendCount) {
                        subject.appendText(messageId, chunk)
                    }
                }
            }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val finalContent = subject.finalizeMessage(messageId)

        assertEquals(workerCount * appendCount * chunkSize, finalContent.length)
        (0 until workerCount).forEach { workerIndex ->
            val expectedChar = 'a' + workerIndex
            assertEquals(appendCount * chunkSize, finalContent.count { it == expectedChar })
        }
        assertEquals(finalContent, subject.getCurrentRenderState(messageId).content)
    }
}
