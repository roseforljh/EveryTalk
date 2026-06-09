package com.android.everytalk.data.network.parser

import com.android.everytalk.data.network.AppStreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamEventParserStatusTest {

    @Test
    fun `tool call parses backend progress text`() {
        val event = StreamEventParser.parseBackendStreamEvent(
            """
            {
              "type": "tool_call",
              "id": "call-1",
              "name": "web_search_exa",
              "status": "搜索网页 1/3：正在查询 EveryTalk",
              "argumentsObj": {"query": "EveryTalk"}
            }
            """.trimIndent()
        )

        assertTrue(event is AppStreamEvent.ToolCall)
        event as AppStreamEvent.ToolCall
        assertEquals("搜索网页 1/3：正在查询 EveryTalk", event.status)
    }

    @Test
    fun `status update parses backend progress text from status field`() {
        val event = StreamEventParser.parseBackendStreamEvent(
            """
            {
              "type": "status_update",
              "status": "搜索网页 3/5：正在整理结果"
            }
            """.trimIndent()
        )

        assertTrue(event is AppStreamEvent.StatusUpdate)
        event as AppStreamEvent.StatusUpdate
        assertEquals("搜索网页 3/5：正在整理结果", event.stage)
    }

    @Test
    fun `status update ignores non text status field and falls back to stage`() {
        val event = StreamEventParser.parseBackendStreamEvent(
            """
            {
              "type": "status_update",
              "status": {"raw": "object"},
              "stage": "搜索网页 4/5：正在生成摘要"
            }
            """.trimIndent()
        )

        assertTrue(event is AppStreamEvent.StatusUpdate)
        event as AppStreamEvent.StatusUpdate
        assertEquals("搜索网页 4/5：正在生成摘要", event.stage)
    }

    @Test
    fun `execution status update parses backend progress text`() {
        val event = StreamEventParser.parseBackendStreamEvent(
            """
            {
              "type": "execution_status_update",
              "status": "MCP 工具 2/4：正在读取官方文档"
            }
            """.trimIndent()
        )

        assertTrue(event is AppStreamEvent.ExecutionStatusUpdate)
        event as AppStreamEvent.ExecutionStatusUpdate
        assertEquals("MCP 工具 2/4：正在读取官方文档", event.status)
    }
}
