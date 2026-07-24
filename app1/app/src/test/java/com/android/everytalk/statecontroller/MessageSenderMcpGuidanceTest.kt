package com.android.everytalk.statecontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSenderMcpGuidanceTest {

    @Test
    fun `docs request prioritizes docs without removing other tools`() {
        val tools = prepareMcpDispatch(
            messageText = "Room 2.8 migration 怎么写",
            allCandidates = listOf(
                com.android.everytalk.statecontroller.mcp.dispatch.toMcpToolCandidate(
                    serverName = "Context7",
                    tool = com.android.everytalk.data.mcp.McpTool("query-docs", "Query docs")
                ),
                com.android.everytalk.statecontroller.mcp.dispatch.toMcpToolCandidate(
                    serverName = "Exa",
                    tool = com.android.everytalk.data.mcp.McpTool("web_search_exa", "Search web")
                ),
                com.android.everytalk.statecontroller.mcp.dispatch.toMcpToolCandidate(
                    serverName = "Firecrawl",
                    tool = com.android.everytalk.data.mcp.McpTool("crawl_page", "Read page")
                ),
                com.android.everytalk.statecontroller.mcp.dispatch.toMcpToolCandidate(
                    serverName = "InternalService",
                    tool = com.android.everytalk.data.mcp.McpTool("internal-service-call", "Business service")
                )
            )
        ).tools

        val names = tools.map { functionDef -> ((functionDef["function"] as Map<*, *>)["name"] as String) }
        assertEquals(listOf("query-docs", "web_search_exa"), names.take(2))
        assertTrue("internal-service-call" in names)
    }

    @Test
    fun `general request keeps enabled custom mcp tools available`() {
        val tools = prepareMcpDispatch(
            messageText = "帮我创建一个客户工单",
            allCandidates = listOf(
                com.android.everytalk.statecontroller.mcp.dispatch.toMcpToolCandidate(
                    serverName = "InternalService",
                    tool = com.android.everytalk.data.mcp.McpTool("create_ticket", "Create customer ticket")
                )
            )
        ).tools

        assertTrue(tools.any { functionDef -> ((functionDef["function"] as Map<*, *>)["name"] as? String) == "create_ticket" })
    }

    @Test
    fun `built in time tool definition uses expected tool name`() {
        val tool = builtInCurrentTimeToolDefinition()
        val function = tool["function"] as Map<*, *>

        assertEquals(BUILT_IN_CURRENT_TIME_TOOL_NAME, function["name"])
    }
}
