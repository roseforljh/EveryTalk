package com.android.everytalk.statecontroller.mcp.dispatch

import com.android.everytalk.data.mcp.McpTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolRegistryTest {

    @Test
    fun `context7 docs tools are classified as docs`() {
        val candidate = toMcpToolCandidate(
            serverId = "context7",
            serverName = "Context7",
            tool = McpTool(name = "query-docs", description = "Query library docs")
        )

        assertEquals(McpToolCategory.DOCS, candidate.category)
        assertTrue(candidate.capabilities.contains(McpCapability.LOOKUP_DOCS))
    }
}
