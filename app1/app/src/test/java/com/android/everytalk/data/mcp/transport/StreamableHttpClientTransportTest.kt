package com.android.everytalk.data.mcp.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StreamableHttpClientTransportTest {
    @Test
    fun `并发inline SSE保留原始响应ID不串单`() = runBlocking {
        val both = CompletableDeferred<Unit>()
        val count = java.util.concurrent.atomic.AtomicInteger()
        val client = HttpClient(MockEngine {
            if (count.incrementAndGet() == 2) both.complete(Unit)
            withTimeout(5000) { both.await() }
            // 单个 POST 也可带其他在途请求的响应：不得改写其 ID。
            respond("data: {\"jsonrpc\":\"2.0\",\"id\":99,\"result\":{}}\n\n",
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        })
        val transport = StreamableHttpClientTransport(client, "https://example.test/mcp")
        val ids = java.util.Collections.synchronizedList(mutableListOf<String>())
        transport.onMessage { if (it is JSONRPCResponse) ids += it.id.toString() }
        try {
            transport.start()
            coroutineScope {
                listOf(1, 2).map { id -> async {
                    transport.send(McpJson.decodeFromString<JSONRPCMessage>(
                        "{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"ping\"}"), options = null)
                } }.awaitAll()
            }
            assertEquals(2, ids.size)
            assertEquals(1, ids.distinct().size)
            assertEquals(RequestId.NumberId(99).toString(), ids.first())
        } finally { transport.close(); client.close() }
    }
}
