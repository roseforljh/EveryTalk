package com.android.everytalk.statecontroller

import com.android.everytalk.statecontroller.mcp.dispatch.QueryIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSenderMcpGuidanceTest {

    @Test
    fun `realtime style query is recognized as external info candidate`() {
        assertTrue(looksLikeRealtimeOrNewsQuery("今天有什么热点新闻？"))
        assertTrue(looksLikeRealtimeOrNewsQuery("最近发生了什么大事"))
        assertTrue(looksLikeRealtimeOrNewsQuery("latest ai news today"))
        assertTrue(looksLikeRealtimeOrNewsQuery("英伟达股价最新情况"))
        assertTrue(looksLikeRealtimeOrNewsQuery("who won the latest match"))
        assertTrue(looksLikeRealtimeOrNewsQuery("现在北京天气怎么样"))
    }

    @Test
    fun `non realtime query does not trigger external info preference`() {
        assertFalse(looksLikeRealtimeOrNewsQuery("请解释一下 Kotlin 的 sealed class"))
        assertFalse(looksLikeRealtimeOrNewsQuery("帮我写一个快速排序"))
    }

    @Test
    fun `mcp guidance is empty when mcp disabled`() {
        assertTrue(
            buildMcpUsageGuidance(
                messageText = "今天有什么热点新闻？",
                isMcpEnabled = false,
                hasMcpTools = true
            ).isNullOrBlank()
        )
    }

    @Test
    fun `mcp guidance is empty when no mcp tools available`() {
        assertTrue(
            buildMcpUsageGuidance(
                messageText = "今天有什么热点新闻？",
                isMcpEnabled = true,
                hasMcpTools = false
            ).isNullOrBlank()
        )
    }

    @Test
    fun `mcp guidance includes proactive tool use instruction when enabled`() {
        val guidance = buildMcpUsageGuidance(
            messageText = "帮我查一下 OpenAI 最近的新闻",
            isMcpEnabled = true,
            hasMcpTools = true
        ).orEmpty()

        assertTrue(guidance.contains("必须积极主动地使用它们"))
        assertTrue(guidance.contains("必须先调用工具获取信息再回答"))
    }

    @Test
    fun `mcp guidance keeps normal query in auto decision mode`() {
        val guidance = buildMcpUsageGuidance(
            messageText = "解释一下协程取消传播",
            isMcpEnabled = true,
            hasMcpTools = true
        ).orEmpty()

        assertTrue(guidance.contains("纯粹是闲聊、创意写作、或完全不涉及外部信息时，才可以不调用工具"))
        assertFalse(guidance.contains("明显依赖最新外部信息"))
    }

    @Test
    fun `docs intent guidance prefers docs tools`() {
        val guidance = buildMcpUsageGuidance(
            messageText = "Ktor client bearer auth 怎么配",
            isMcpEnabled = true,
            hasMcpTools = true,
            dispatchIntent = QueryIntent.DOCS_LOOKUP
        ).orEmpty()

        assertTrue(guidance.contains("官方文档"))
        assertTrue(guidance.contains("文档类 MCP 工具"))
    }

    @Test
    fun `time guidance includes current date and timezone context`() {
        val guidance = buildCurrentTimeGuidance().orEmpty()

        assertTrue(guidance.contains("当前本地时间"))
        assertTrue(guidance.contains("时区"))
        assertTrue(guidance.contains("get_current_datetime"))
    }

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
