package com.android.everytalk.data.network.openclaw

import com.android.everytalk.data.network.AppStreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawEventMapperTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun `maps connect pending event to status update`() {
        val event = OpenClawEventMapper.mapChatEvent(
            """
            {
              "event": "connect.result",
              "data": {
                "type": "pairing.pending",
                "deviceId": "device-1"
              }
            }
            """.trimIndent(),
            json
        )

        assertTrue(event is AppStreamEvent.StatusUpdate)
        assertEquals("pairing_pending:device-1", (event as AppStreamEvent.StatusUpdate).stage)
    }

    @Test
    fun `maps connect approved event to connected status update`() {
        val event = OpenClawEventMapper.mapChatEvent(
            """
            {
              "event": "connect.result",
              "data": {
                "type": "pairing.approved"
              }
            }
            """.trimIndent(),
            json
        )

        assertTrue(event is AppStreamEvent.StatusUpdate)
        assertEquals("connected", (event as AppStreamEvent.StatusUpdate).stage)
    }

    @Test
    fun `maps text delta event to content`() {
        val event = OpenClawEventMapper.mapChatEvent(
            """
            {
              "event": "chat",
              "data": {
                "type": "delta",
                "text": "hello"
              }
            }
            """.trimIndent(),
            json
        )

        assertTrue(event is AppStreamEvent.Content)
        assertEquals("hello", (event as AppStreamEvent.Content).text)
    }

    @Test
    fun `maps completed event to finish`() {
        val event = OpenClawEventMapper.mapChatEvent(
            """
            {
              "event": "chat",
              "data": {
                "type": "done",
                "reason": "completed"
              }
            }
            """.trimIndent(),
            json
        )

        assertTrue(event is AppStreamEvent.Finish)
        assertEquals("completed", (event as AppStreamEvent.Finish).reason)
    }

    @Test
    fun `maps error event to error`() {
        val event = OpenClawEventMapper.mapChatEvent(
            """
            {
              "event": "chat",
              "data": {
                "type": "error",
                "message": "gateway failed"
              }
            }
            """.trimIndent(),
            json
        )

        assertTrue(event is AppStreamEvent.Error)
        assertEquals("gateway failed", (event as AppStreamEvent.Error).message)
    }

    @Test
    fun `maps history event to status update`() {
        val event = OpenClawEventMapper.mapChatEvent(
            """
            {
              "event": "chat.history",
              "data": {
                "type": "history",
                "messages": {}
              }
            }
            """.trimIndent(),
            json
        )

        assertTrue(event is AppStreamEvent.StatusUpdate)
        assertEquals("history_loaded:0", (event as AppStreamEvent.StatusUpdate).stage)
    }

    @Test
    fun `maps subscribed event to status update`() {
        val event = OpenClawEventMapper.mapChatEvent(
            """
            {
              "event": "chat.subscribed",
              "data": {
                "type": "subscribed"
              }
            }
            """.trimIndent(),
            json
        )

        assertTrue(event is AppStreamEvent.StatusUpdate)
        assertEquals("subscribed", (event as AppStreamEvent.StatusUpdate).stage)
    }

    @Test
    fun `maps agent assistant delta to content`() {
        val event = OpenClawEventMapper.mapChatEvent(
            """
            {
              "type": "event",
              "event": "agent",
              "payload": {
                "runId": "run-1",
                "stream": "assistant",
                "data": {
                  "text": "hello from agent",
                  "delta": " from delta"
                }
              }
            }
            """.trimIndent(),
            json
        )

        assertTrue(event is AppStreamEvent.Content)
        assertEquals(" from delta", (event as AppStreamEvent.Content).text)
    }

    @Test
    fun `maps chat final state to finish`() {
        val event = OpenClawEventMapper.mapChatEvent(
            """
            {
              "type": "event",
              "event": "chat",
              "payload": {
                "data": {
                  "state": "final",
                  "reason": "completed"
                }
              }
            }
            """.trimIndent(),
            json
        )

        assertTrue(event is AppStreamEvent.Finish)
        assertEquals("completed", (event as AppStreamEvent.Finish).reason)
    }

    @Test
    fun `maps agent run id status when assistant delta is blank`() {
        val event = OpenClawEventMapper.mapChatEvent(
            """
            {
              "type": "event",
              "event": "agent",
              "payload": {
                "runId": "run-42",
                "stream": "assistant",
                "data": {
                  "delta": ""
                }
              }
            }
            """.trimIndent(),
            json
        )

        assertTrue(event is AppStreamEvent.StatusUpdate)
        assertEquals("agent_run:run-42", (event as AppStreamEvent.StatusUpdate).stage)
    }

    @Test
    fun `ignores chat delta snapshot nested in payload data to avoid duplicate append`() {
        val event = OpenClawEventMapper.mapChatEvent(
            """
            {
              "type": "event",
              "event": "chat",
              "payload": {
                "data": {
                  "state": "delta",
                  "message": {
                    "role": "assistant",
                    "content": [
                      {"type": "text", "text": "full snapshot"}
                    ]
                  }
                }
              }
            }
            """.trimIndent(),
            json
        )

        assertNull(event)
    }

    @Test
    fun `ignores chat delta snapshot to avoid duplicate append`() {
        val event = OpenClawEventMapper.mapChatEvent(
            """
            {
              "type": "event",
              "event": "chat",
              "payload": {
                "state": "delta",
                "message": {
                  "role": "assistant",
                  "content": [
                    {"type": "text", "text": "full snapshot"}
                  ]
                }
              }
            }
            """.trimIndent(),
            json
        )

        assertNull(event)
    }

    @Test
    fun `ignores connect challenge event instead of finishing stream`() {
        val event = OpenClawEventMapper.mapChatEvent(
            """
            {
              "type": "event",
              "event": "connect.challenge",
              "payload": {
                "nonce": "34722e1f-27dc-4111-9c6e-7a72ad94baf2",
                "ts": 1773594855274
              }
            }
            """.trimIndent(),
            json
        )

        assertNull(event)
    }

    @Test
    fun `maps tool call event to tool call stream event`() {
        val event = OpenClawEventMapper.mapChatEvent(
            """
            {
              "event": "chat",
              "data": {
                "type": "tool.call",
                "id": "call-1",
                "name": "fs.write",
                "arguments": {
                  "path": "/tmp/demo.txt",
                  "content": "hello"
                }
              }
            }
            """.trimIndent(),
            json
        )

        assertTrue(event is AppStreamEvent.ToolCall)
        event as AppStreamEvent.ToolCall
        assertEquals("call-1", event.id)
        assertEquals("fs.write", event.name)
        assertEquals("/tmp/demo.txt", event.argumentsObj["path"]?.jsonPrimitive?.content)
    }

    @Test
    fun `maps tool result event to content summary`() {
        val event = OpenClawEventMapper.mapChatEvent(
            """
            {
              "event": "chat",
              "data": {
                "type": "tool.result",
                "name": "fs.write",
                "status": "ok",
                "summary": "已修改 /tmp/demo.txt"
              }
            }
            """.trimIndent(),
            json
        )

        assertTrue(event is AppStreamEvent.Content)
        assertEquals("[工具结果] fs.write: 已修改 /tmp/demo.txt", (event as AppStreamEvent.Content).text)
    }

    @Test
    fun `maps remote control progress event to status update`() {
        val event = OpenClawEventMapper.mapChatEvent(
            """
            {
              "event": "chat",
              "data": {
                "type": "run.progress",
                "message": "正在修改 /workspace/app/main.kt"
              }
            }
            """.trimIndent(),
            json
        )

        assertTrue(event is AppStreamEvent.StatusUpdate)
        assertEquals("正在修改 /workspace/app/main.kt", (event as AppStreamEvent.StatusUpdate).stage)
    }

    @Test
    fun `returns null for unknown chat event`() {
        val event = OpenClawEventMapper.mapChatEvent(
            """
            {
              "event": "chat",
              "data": {
                "type": "unknown.event"
              }
            }
            """.trimIndent(),
            json
        )

        assertNull(event)
    }
}
