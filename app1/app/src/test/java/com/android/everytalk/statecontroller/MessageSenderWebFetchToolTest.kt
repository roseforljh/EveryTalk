package com.android.everytalk.statecontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MessageSenderWebFetchToolTest {

    @Test
    fun `webfetch tool is always injected when not already present`() {
        val tools = appendBuiltInWebFetchToolIfNeeded(tools = emptyList())

        assertEquals(1, tools.size)
        val function = tools.single()["function"] as Map<*, *>
        assertEquals(BUILT_IN_WEBFETCH_TOOL_NAME, function["name"])
        val parameters = function["parameters"] as Map<*, *>
        assertEquals("object", parameters["type"])
        assertEquals(listOf("url"), parameters["required"])
    }

    @Test
    fun `existing webfetch tool avoids duplicate`() {
        val existingWebFetchTool = builtInWebFetchToolDefinition()

        val tools = appendBuiltInWebFetchToolIfNeeded(tools = listOf(existingWebFetchTool))

        assertEquals(1, tools.size)
        assertSame(existingWebFetchTool, tools.single())
    }

    @Test
    fun `other tools are preserved when webfetch is appended`() {
        val existingTools = listOf(
            mapOf("type" to "function", "function" to mapOf("name" to "custom_tool"))
        )

        val tools = appendBuiltInWebFetchToolIfNeeded(tools = existingTools)

        assertEquals(2, tools.size)
        assertEquals("custom_tool", (tools[0]["function"] as Map<*, *>)["name"])
        assertEquals(BUILT_IN_WEBFETCH_TOOL_NAME, (tools[1]["function"] as Map<*, *>)["name"])
    }
}
