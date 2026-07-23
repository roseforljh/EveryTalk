package com.android.everytalk.data.mcp.transport

import com.android.everytalk.data.network.ResponseBodyTooLargeException
import io.ktor.client.HttpClient
import io.ktor.utils.io.ByteReadChannel
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class SseClientTransportTest {

    @Test
    fun `parser handles open endpoint error and multiline message events`() = runBlocking {
        val source = ByteReadChannel(
            """
            : heartbeat

            event: open

            event: endpoint
            data: /messages

            event: error
            data: unavailable

            id: 7
            event: message
            data: {"jsonrpc":"2.0",
            data: "method":"ping"}

            """.trimIndent().toByteArray(),
        )
        val events = mutableListOf<McpSseEvent>()

        collectMcpSseEvents(source) { events += it }

        assertEquals(4, events.size)
        assertEquals("open", events[0].event)
        assertNull(events[0].data)
        assertEquals(McpSseEvent(null, "endpoint", "/messages"), events[1])
        assertEquals(McpSseEvent(null, "error", "unavailable"), events[2])
        assertEquals(
            McpSseEvent("7", "message", "{\"jsonrpc\":\"2.0\",\n\"method\":\"ping\"}"),
            events[3],
        )
    }

    @Test
    fun `collector rejects multiline event over byte limit`() = runBlocking {
        val source = ByteReadChannel("data:a\ndata:b\n\n".toByteArray())

        try {
            collectMcpSseEvents(source, maxEventBytes = 8) {}
            fail("应拒绝累计字节超过上限的 SSE 事件")
        } catch (_: ResponseBodyTooLargeException) {
        }
    }

    @Test
    fun `close is idempotent and closed transport cannot restart`() = runBlocking {
        val transport = SseClientTransport(
            client = mockk<HttpClient>(relaxed = true),
            urlString = "https://example.com/sse",
        )

        transport.close()
        transport.close()

        try {
            transport.start()
            fail("关闭后的传输不应再次启动")
        } catch (_: IllegalStateException) {
        }
    }
}
