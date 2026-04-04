package com.android.everytalk.statecontroller

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

        assertTrue(guidance.contains("主动判断当前问题是否需要借助 MCP 工具"))
        assertTrue(guidance.contains("优先考虑调用最合适的 MCP 工具后再回答"))
    }

    @Test
    fun `mcp guidance keeps normal query in auto decision mode`() {
        val guidance = buildMcpUsageGuidance(
            messageText = "解释一下协程取消传播",
            isMcpEnabled = true,
            hasMcpTools = true
        ).orEmpty()

        assertTrue(guidance.contains("如果问题可以仅凭已有知识准确回答，则无需调用 MCP"))
        assertFalse(guidance.contains("明显依赖最新外部信息"))
    }

    @Test
    fun `time guidance includes current date and timezone context`() {
        val guidance = buildCurrentTimeGuidance().orEmpty()

        assertTrue(guidance.contains("当前本地时间"))
        assertTrue(guidance.contains("时区"))
        assertTrue(guidance.contains("如果用户询问今天"))
    }

    @Test
    fun `built in time tool definition uses expected tool name`() {
        val tool = builtInCurrentTimeToolDefinition()
        val function = tool["function"] as Map<*, *>

        assertEquals(BUILT_IN_CURRENT_TIME_TOOL_NAME, function["name"])
    }
}
