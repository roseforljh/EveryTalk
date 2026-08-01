package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.ModelTokenLimits
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageContextWindowTest {
    @Test
    fun `超出窗口时保留系统消息和最新用户消息`() {
        val messages = listOf(
            SimpleTextApiMessage(id = "system", role = "system", content = "系统提示"),
            SimpleTextApiMessage(id = "very-old-user", role = "user", content = "更早的问题"),
            SimpleTextApiMessage(id = "very-old-ai", role = "assistant", content = "更早的回答"),
            SimpleTextApiMessage(id = "old-user", role = "user", content = "旧问题".repeat(200)),
            SimpleTextApiMessage(id = "old-ai", role = "assistant", content = "旧回答".repeat(200)),
            SimpleTextApiMessage(id = "new-user", role = "user", content = "最新问题"),
        )

        val trimmed = trimMessagesToContextWindow(
            messages = messages,
            limits = ModelTokenLimits(maxOutputTokens = 40, maxContextTokens = 120),
        )

        assertEquals(listOf("system", "new-user"), trimmed.map { it.id })
    }

    @Test
    fun `窗口足够时保留完整历史`() {
        val messages = listOf(
            SimpleTextApiMessage(id = "u1", role = "user", content = "第一轮"),
            SimpleTextApiMessage(id = "a1", role = "assistant", content = "回答"),
            SimpleTextApiMessage(id = "u2", role = "user", content = "第二轮"),
        )

        val trimmed = trimMessagesToContextWindow(
            messages = messages,
            limits = ModelTokenLimits(maxOutputTokens = 100, maxContextTokens = 1000),
        )

        assertEquals(messages, trimmed)
        assertTrue(estimatedApiMessageTokens(messages.first()) > messages.first().content.length)
        assertFalse(trimmed.isEmpty())
    }

    @Test
    fun `大型工具schema会减少可保留的历史轮次`() {
        val messages = listOf(
            SimpleTextApiMessage(id = "system", role = "system", content = "系统提示"),
            SimpleTextApiMessage(id = "old-user", role = "user", content = "old question ".repeat(30)),
            SimpleTextApiMessage(id = "old-ai", role = "assistant", content = "old answer ".repeat(30)),
            SimpleTextApiMessage(id = "new-user", role = "user", content = "最新问题"),
        )
        val limits = ModelTokenLimits(maxOutputTokens = 50, maxContextTokens = 700)
        val largeTool = mapOf<String, Any>(
            "type" to "function",
            "function" to mapOf(
                "name" to "large_tool",
                "description" to "schema ".repeat(400),
                "parameters" to mapOf("type" to "object"),
            ),
        )

        val withoutTools = trimMessagesToContextWindow(messages, limits)
        val withTools = trimMessagesToContextWindow(messages, limits, tools = listOf(largeTool))

        assertEquals(messages.map { it.id }, withoutTools.map { it.id })
        assertEquals(listOf("system", "new-user"), withTools.map { it.id })
    }
}
