package com.android.everytalk.data.network

import android.util.Log
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DirectClientLifecycleTest {

    @Before
    fun mockAndroidLog() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.d(any(), any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.i(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @After
    fun restoreAndroidLog() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `http errors emit one error terminal without stop`() = runBlocking {
        withHttpClient(
            status = 500,
            contentType = ContentType.Text.Plain.toString(),
            body = "x".repeat((MAX_ERROR_RESPONSE_BYTES + 1).toInt()),
        ) { client ->
            assertSingleErrorTerminal(
                GeminiDirectClient.streamChatDirect(client, request("Gemini", "Gemini")),
                "api_error",
            )
            assertSingleErrorTerminal(
                OpenAIDirectClient.streamChatDirect(client, request("OpenAI", "OpenAI")),
                "api_error",
            )
            assertSingleErrorTerminal(
                OpenAIResponsesClient.streamChatResponses(client, request("OpenAI", "OpenAI")),
                "api_error",
            )
        }
    }

    @Test
    fun `parse errors emit one error terminal without stop`() = runBlocking {
        withHttpClient(body = "data: {broken}\n\n") { client ->
            assertSingleErrorTerminal(
                GeminiDirectClient.streamChatDirect(client, request("Gemini", "Gemini")),
                "connection_failed",
            )
            assertSingleErrorTerminal(
                OpenAIDirectClient.streamChatDirect(client, request("OpenAI", "OpenAI")),
                "connection_failed",
            )
            assertSingleErrorTerminal(
                OpenAIResponsesClient.streamChatResponses(client, request("OpenAI", "OpenAI")),
                "connection_failed",
            )
        }
    }

    @Test
    fun `oversized sse event emits one error terminal without stop`() = runBlocking {
        val body = "data: ${"x".repeat((MAX_SSE_EVENT_BYTES + 1).toInt())}\n\n"
        withHttpClient(body = body) { client ->
            assertSingleErrorTerminal(
                OpenAIResponsesClient.streamChatResponses(client, request("OpenAI", "OpenAI")),
                "connection_failed",
            )
        }
    }

    @Test
    fun `tool chain inherits collection cancellation and skips remaining tools`() = runBlocking {
        val body = buildString {
            appendResponsesEvent("""{"type":"response.output_item.added","item":{"type":"function_call","call_id":"call-1","name":"first"}}""")
            appendResponsesEvent("""{"type":"response.function_call_arguments.done","call_id":"call-1","name":"first","arguments":"{}"}""")
            appendResponsesEvent("""{"type":"response.output_item.added","item":{"type":"function_call","call_id":"call-2","name":"second"}}""")
            appendResponsesEvent("""{"type":"response.function_call_arguments.done","call_id":"call-2","name":"second","arguments":"{}"}""")
            appendResponsesEvent("""{"type":"response.completed"}""")
            append("data: [DONE]\n\n")
        }
        val callCount = AtomicInteger()
        val firstStarted = CompletableDeferred<Unit>()
        val cancellationObserved = CompletableDeferred<Unit>()

        OpenAIResponsesClient.setMcpToolExecutor { _, _, _ ->
            when (callCount.incrementAndGet()) {
                1 -> {
                    firstStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancellationObserved.complete(Unit)
                    }
                }
                else -> JsonPrimitive("unexpected")
            }
        }

        try {
            withHttpClient(body = body) { client ->
                val collection = launch {
                    OpenAIResponsesClient.streamChatResponses(
                        client,
                        request("OpenAI", "OpenAI"),
                    ).collect()
                }

                withTimeout(5_000) { firstStarted.await() }
                collection.cancel()
                withTimeout(5_000) { cancellationObserved.await() }
                collection.join()

                assertTrue(collection.isCancelled)
                assertEquals(1, callCount.get())
            }
        } finally {
            OpenAIResponsesClient.setMcpToolExecutor(null)
        }
    }

    @Test
    fun `long responses stream preserves accumulated text`() = runBlocking {
        val deltas = List(4_000) { index -> "片段${index % 10}" }
        val expected = deltas.joinToString("")
        val body = buildString {
            deltas.forEach { delta ->
                appendResponsesEvent("""{"type":"response.output_text.delta","delta":"$delta"}""")
            }
            appendResponsesEvent("""{"type":"response.completed"}""")
            append("data: [DONE]\n\n")
        }

        withHttpClient(body = body) { client ->
            val events = OpenAIResponsesClient.streamChatResponses(
                client,
                request("OpenAI", "OpenAI"),
            ).toList()

            assertEquals(expected, events.filterIsInstance<AppStreamEvent.ContentFinal>().single().text)
            assertEquals(listOf("stop"), events.filterIsInstance<AppStreamEvent.Finish>().map { it.reason })
            assertFalse(events.any { it is AppStreamEvent.Error })
        }
    }

    private suspend fun assertSingleErrorTerminal(flow: Flow<AppStreamEvent>, expectedReason: String) {
        val events = flow.toList()
        assertEquals(2, events.size)
        assertTrue(events[0] is AppStreamEvent.Error)
        assertEquals(expectedReason, (events[1] as AppStreamEvent.Finish).reason)
        assertEquals(1, events.count { it is AppStreamEvent.Error })
        assertEquals(1, events.count { it is AppStreamEvent.Finish })
        assertFalse(events.filterIsInstance<AppStreamEvent.Finish>().any { it.reason == "stop" })
    }

    private fun StringBuilder.appendResponsesEvent(json: String) {
        append("data: ")
        append(json)
        append("\n\n")
    }

    private fun request(provider: String, channel: String) = ChatRequest(
        messages = listOf(SimpleTextApiMessage(role = "user", content = "hello")),
        provider = provider,
        channel = channel,
        apiAddress = "https://test.invalid",
        apiKey = "test-key",
        model = "test-model",
    )

    private suspend fun <T> withHttpClient(
        status: Int = 200,
        contentType: String = ContentType.Text.EventStream.toString(),
        body: String,
        block: suspend (HttpClient) -> T,
    ): T {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val client = HttpClient(MockEngine { _ ->
            respond(
                content = ByteReadChannel(bytes),
                status = HttpStatusCode.fromValue(status),
                headers = Headers.build {
                    append(HttpHeaders.ContentType, contentType)
                    append(HttpHeaders.ContentLength, bytes.size.toString())
                },
            )
        }) {
            expectSuccess = false
            install(HttpTimeout)
        }
        return try {
            block(client)
        } finally {
            client.close()
        }
    }
}
