package com.android.everytalk.statecontroller.mcp.dispatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpIntentClassifierTest {

    @Test
    fun `classifies docs lookup intent`() {
        val result = classifyMcpIntent("Next.js 15 cacheComponents 怎么配置")

        assertEquals(QueryIntent.DOCS_LOOKUP, result.primaryIntent)
        assertTrue(result.shouldPreferMcp)
    }

    @Test
    fun `classifies realtime intent`() {
        val result = classifyMcpIntent("今天英伟达股价多少")

        assertEquals(QueryIntent.REALTIME_INFO, result.primaryIntent)
    }

    @Test
    fun `classifies url read intent`() {
        val result = classifyMcpIntent("帮我读一下这个网页 https://example.com")

        assertEquals(QueryIntent.WEB_CONTENT_READ, result.primaryIntent)
    }

    @Test
    fun `classifies local reasoning intent`() {
        val result = classifyMcpIntent("解释一下 Kotlin sealed class")

        assertEquals(QueryIntent.LOCAL_REASONING, result.primaryIntent)
        assertFalse(result.shouldPreferMcp)
    }
}
