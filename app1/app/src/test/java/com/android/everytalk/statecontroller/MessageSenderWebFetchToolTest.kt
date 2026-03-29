package com.android.everytalk.statecontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSenderWebFetchToolTest {

    @Test
    fun `url bearing prompt injects built in webfetch tool`() {
        val tools = appendBuiltInWebFetchToolIfNeeded(
            messageText = "请阅读 https://example.com/article",
            tools = emptyList(),
        )

        assertEquals(1, tools.size)
        val function = tools.single()["function"] as Map<*, *>
        assertEquals(BUILT_IN_WEBFETCH_TOOL_NAME, function["name"])
        val parameters = function["parameters"] as Map<*, *>
        assertEquals("object", parameters["type"])
        assertEquals(listOf("url"), parameters["required"])
    }

    @Test
    fun `prompt without url keeps tool list unchanged`() {
        val existingTools = listOf(
            mapOf(
                "type" to "function",
                "function" to mapOf("name" to "custom_tool")
            )
        )

        val tools = appendBuiltInWebFetchToolIfNeeded(
            messageText = "这是普通消息，没有链接",
            tools = existingTools,
        )

        assertSame(existingTools, tools)
        assertFalse(shouldExposeBuiltInWebFetchTool("这是普通消息，没有链接"))
    }

    @Test
    fun `existing webfetch tool avoids duplicate collision`() {
        val existingWebFetchTool = builtInWebFetchToolDefinition()

        val tools = appendBuiltInWebFetchToolIfNeeded(
            messageText = "请总结 http://example.com",
            tools = listOf(existingWebFetchTool),
        )

        assertEquals(1, tools.size)
        assertSame(existingWebFetchTool, tools.single())
    }

    @Test
    fun `http and https urls are both recognized`() {
        assertTrue(shouldExposeBuiltInWebFetchTool("http://example.com"))
        assertTrue(shouldExposeBuiltInWebFetchTool("https://example.com"))
    }
}
