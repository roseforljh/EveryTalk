package com.android.everytalk.data.network

import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLoopContextGuardTest {
    @Test
    fun `单个最新工具输出过大时保留头尾和截断标记`() {
        val text = "HEAD" + "中".repeat(2_000) + "TAIL"
        val truncated = truncateToolOutput(text, maxTokens = 100)

        assertTrue(truncated.startsWith("HEAD"))
        assertTrue(truncated.endsWith("TAIL"))
        assertTrue(truncated.contains("工具输出已截断"))
        assertTrue(estimateToolLoopTextTokens(truncated) <= 100L)
    }

    @Test
    fun `工具前正文保留正文类型并排在工具调用之前`() = runTest {
        val emitted = mutableListOf<AppStreamEvent>()
        val buffer = ToolRoundContentBuffer { emitted += it }
        val toolCall = AppStreamEvent.ToolCall(
            id = "call-1",
            name = "exec",
            argumentsObj = JsonObject(emptyMap()),
        )

        buffer.accept(AppStreamEvent.Content("我先检查服务器："))
        buffer.accept(toolCall)
        buffer.finish(hasToolCalls = true)

        assertEquals(
            listOf(AppStreamEvent.Content("我先检查服务器："), toolCall),
            emitted,
        )
    }

    @Test
    fun `最终轮正文仍作为最终回答输出`() = runTest {
        val emitted = mutableListOf<AppStreamEvent>()
        val buffer = ToolRoundContentBuffer { emitted += it }

        buffer.accept(AppStreamEvent.Content("最终结论"))
        buffer.finish(hasToolCalls = false)

        assertEquals(listOf(AppStreamEvent.Content("最终结论")), emitted)
    }

    @Test
    fun `正文增量到达后立即流式输出`() = runTest {
        val emitted = mutableListOf<AppStreamEvent>()
        val buffer = ToolRoundContentBuffer { emitted += it }
        val first = AppStreamEvent.Content("a".repeat(40))
        val second = AppStreamEvent.Content("b".repeat(40))

        buffer.accept(first)
        assertEquals(listOf(first), emitted)
        buffer.accept(second)

        assertEquals(listOf(first, second), emitted)
    }
}
