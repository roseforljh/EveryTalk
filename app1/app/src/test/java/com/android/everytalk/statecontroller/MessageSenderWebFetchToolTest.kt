package com.android.everytalk.statecontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSenderWebFetchToolTest {

    @Test
    fun `explicit web reading prompt with url injects built in webfetch tool`() {
        val tools = appendBuiltInWebFetchToolIfNeeded(
            messageText = "请阅读这个链接并总结内容 https://example.com/article",
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
    fun `bare url does not expose webfetch tool`() {
        val tools = appendBuiltInWebFetchToolIfNeeded(
            messageText = "https://example.com/article",
            tools = emptyList(),
        )

        assertTrue(tools.isEmpty())
        assertTrue(containsHttpUrl("https://example.com/article"))
        assertFalse(hasExplicitWebReadIntent("https://example.com/article"))
        assertFalse(shouldExposeBuiltInWebFetchTool("https://example.com/article"))
    }

    @Test
    fun `shared link without explicit read intent does not expose webfetch tool`() {
        val tools = appendBuiltInWebFetchToolIfNeeded(
            messageText = "分享个链接给你 https://example.com/article",
            tools = emptyList(),
        )

        assertTrue(tools.isEmpty())
        assertFalse(shouldExposeBuiltInWebFetchTool("分享个链接给你 https://example.com/article"))
    }

    @Test
    fun `negative intent with url does not expose webfetch tool`() {
        val tools = appendBuiltInWebFetchToolIfNeeded(
            messageText = "先别打开这个链接，直接回答 https://example.com/article",
            tools = emptyList(),
        )

        assertTrue(tools.isEmpty())
        assertFalse(hasExplicitWebReadIntent("先别打开这个链接，直接回答 https://example.com/article"))
    }

    @Test
    fun `existing webfetch tool avoids duplicate collision`() {
        val existingWebFetchTool = builtInWebFetchToolDefinition()

        val tools = appendBuiltInWebFetchToolIfNeeded(
            messageText = "请总结这个链接 http://example.com",
            tools = listOf(existingWebFetchTool),
        )

        assertEquals(1, tools.size)
        assertSame(existingWebFetchTool, tools.single())
    }

    @Test
    fun `http and https urls can be recognized as candidates`() {
        assertTrue(containsHttpUrl("http://example.com"))
        assertTrue(containsHttpUrl("https://example.com"))
    }

    @Test
    fun `english explicit read intent also exposes webfetch tool`() {
        assertTrue(shouldExposeBuiltInWebFetchTool("Please summarize this link https://example.com/article"))
    }
}
