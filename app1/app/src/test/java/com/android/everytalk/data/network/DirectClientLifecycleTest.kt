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
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
            assertSingleErrorTerminal(
                AnthropicDirectClient.streamChatDirect(client, request("Anthropic", "Anthropic")),
                "api_error",
            )
        }
    }

    @Test
    fun `HTTP错误保留上游结构化上下文字段`() = runBlocking {
        val body =
            """{"error":{"message":"maximum context length exceeded","type":"invalid_request_error","code":"context_length_exceeded","param":"messages","max_context_tokens":8192}}"""
        withHttpClient(status = 400, body = body) { client ->
            val error = OpenAIDirectClient.streamChatDirect(
                client,
                request("OpenAI", "OpenAI"),
            ).toList().filterIsInstance<AppStreamEvent.Error>().single()

            assertEquals(400, error.upstreamStatus)
            assertEquals("context_length_exceeded", error.code)
            assertEquals("invalid_request_error", error.type)
            assertEquals("messages", error.parameter)
            assertEquals("maximum context length exceeded", error.rawMessage)
            assertEquals(8_192, error.maxContextTokens)
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
            assertSingleErrorTerminal(
                AnthropicDirectClient.streamChatDirect(client, request("Anthropic", "Anthropic")),
                "connection_failed",
            )
        }
    }

    @Test
    fun `OpenAI Chat原生引用发布网页来源事件`() = runBlocking {
        val body = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\n")
            append(
                "data: {\"choices\":[],\"citations\":[" +
                    "\"https://example.com/a\"," +
                    "{\"url\":\"https://example.com/b\",\"title\":\"B\"}]}\n\n"
            )
            append(
                "data: {\"choices\":[{\"delta\":{\"annotations\":[" +
                    "{\"type\":\"url_citation\",\"url_citation\":{" +
                    "\"url\":\"https://example.com/c\",\"title\":\"C\"}}]}}]}\n\n"
            )
            append(
                "data: {\"choices\":[],\"search_info\":{\"search_results\":[" +
                    "{\"url\":\"https://example.com/qwen\",\"title\":\"Qwen\"}]}}\n\n"
            )
            append("data: [DONE]\n\n")
        }

        withHttpClient(body = body) { client ->
            val sources = OpenAIDirectClient.streamChatDirect(
                client,
                request("OpenAI", "Grok"),
            ).toList()
                .filterIsInstance<AppStreamEvent.WebSearchResults>()
                .flatMap { it.results }

            assertEquals(
                listOf(
                    "https://example.com/a",
                    "https://example.com/b",
                    "https://example.com/c",
                    "https://example.com/qwen",
                ),
                sources.map { it.href },
            )
            assertEquals("B", sources[1].title)
            assertEquals("C", sources[2].title)
        }
    }

    @Test
    fun `OpenAI Chat首个短正文无需等待后续事件`() = runBlocking {
        val channel = ByteChannel(autoFlush = true)
        val firstContent = CompletableDeferred<String>()
        val parserJob = launch {
            OpenAIDirectClient.parseOpenAISSEStreamWithTools(
                channel = channel,
                onToolCall = {},
                emitEvent = { event ->
                    if (event is AppStreamEvent.Content && !firstContent.isCompleted) {
                        firstContent.complete(event.text)
                    }
                },
            )
        }

        try {
            channel.writeStringUtf8("data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n")

            assertEquals("你", withTimeout(500) { firstContent.await() })
        } finally {
            channel.close()
            parserJob.cancelAndJoin()
        }
    }

    @Test
    fun `OpenAI Responses原生注解发布网页来源事件`() = runBlocking {
        val body = buildString {
            appendResponsesEvent("""{"type":"response.output_text.delta","delta":"answer"}""")
            appendResponsesEvent(
                """{"type":"response.output_text.annotation.added","annotation":{"type":"url_citation","url":"https://example.com/annotation","title":"Annotation"}}"""
            )
            appendResponsesEvent(
                """{"type":"response.completed","response":{"output":[{"type":"message","content":[{"type":"output_text","annotations":[{"type":"url_citation","url_citation":{"url":"https://example.com/completed","title":"Completed"}}]}]}]}}"""
            )
            append("data: [DONE]\n\n")
        }

        withHttpClient(body = body) { client ->
            val sources = OpenAIResponsesClient.streamChatResponses(
                client,
                request("OpenAI", "Grok"),
            ).toList()
                .filterIsInstance<AppStreamEvent.WebSearchResults>()
                .flatMap { it.results }

            assertEquals(
                listOf(
                    "https://example.com/annotation",
                    "https://example.com/completed",
                ),
                sources.map { it.href },
            )
        }
    }

    @Test
    fun `Gemini grounding元数据发布网页来源事件`() = runBlocking {
        val body = buildString {
            append("data: ")
            append(
                """{"candidates":[{"content":{"parts":[{"text":"answer"}]},"groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://example.com/gemini","title":"Gemini"}}]}}]}"""
            )
            append("\n\ndata: [DONE]\n\n")
        }

        withHttpClient(body = body) { client ->
            val sources = GeminiDirectClient.streamChatDirect(
                client,
                request("Gemini", "Gemini"),
            ).toList()
                .filterIsInstance<AppStreamEvent.WebSearchResults>()
                .single()
                .results

            assertEquals(listOf("https://example.com/gemini"), sources.map { it.href })
            assertEquals("Gemini", sources.single().title)
            assertEquals(1, sources.single().index)
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
            assertEquals(listOf("turn_complete"), events.filterIsInstance<AppStreamEvent.Finish>().map { it.reason })
            assertFalse(events.any { it is AppStreamEvent.Error })
        }
    }

    @Test
    fun `OpenAI Responses使用completed完整正文修复缺失delta`() = runBlocking {
        val canonical = "## 具体流程\n\nhttps://api.resend.com/emails"
        val body = buildString {
            appendResponsesEvent(
                """{"type":"response.output_text.delta","delta":"##具体流程\nhttps://.resend.com/em"}"""
            )
            appendResponsesEvent(
                """{"type":"response.completed","response":{"output":[{"type":"message","content":[{"type":"output_text","text":"## 具体流程\n\nhttps://api.resend.com/emails"}]}]}}"""
            )
            append("data: [DONE]\n\n")
        }

        withHttpClient(body = body) { client ->
            val events = OpenAIResponsesClient.streamChatResponses(
                client,
                request("OpenAI", "OpenAI"),
            ).toList()

            assertEquals(canonical, events.filterIsInstance<AppStreamEvent.ContentFinal>().single().text)
        }
    }

    @Test
    fun `OpenAI Chat流将最终usage发布为统一事件`() = runBlocking {
        val body = buildString {
            append("data: ")
            append(
                """{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,"prompt_tokens_details":{"cached_tokens":30},"completion_tokens_details":{"reasoning_tokens":7}}}"""
            )
            append("\n\ndata: [DONE]\n\n")
        }

        withHttpClient(body = body) { client ->
            val usage = OpenAIDirectClient.streamChatDirect(
                client,
                request("OpenAI", "OpenAI"),
            ).toList().filterIsInstance<AppStreamEvent.Usage>().single().usage

            assertEquals(100L, usage.inputTokens)
            assertEquals(20L, usage.outputTokens)
            assertEquals(7L, usage.reasoningTokens)
            assertEquals(30L, usage.cachedInputTokens)
            assertEquals(120L, usage.totalTokens)
            assertTrue(usage.isFinal)
            assertEquals(TokenUsageSource.OPENAI_CHAT, usage.source)
        }
    }

    @Test
    fun `OpenAI Responses完成事件发布统一usage`() = runBlocking {
        val body = buildString {
            appendResponsesEvent(
                """{"type":"response.completed","response":{"usage":{"input_tokens":200,"output_tokens":30,"total_tokens":230,"input_tokens_details":{"cached_tokens":40,"cache_write_tokens":10},"output_tokens_details":{"reasoning_tokens":9}}}}"""
            )
            append("data: [DONE]\n\n")
        }

        withHttpClient(body = body) { client ->
            val usage = OpenAIResponsesClient.streamChatResponses(
                client,
                request("OpenAI", "OpenAI"),
            ).toList().filterIsInstance<AppStreamEvent.Usage>().single().usage

            assertEquals(200L, usage.inputTokens)
            assertEquals(30L, usage.outputTokens)
            assertEquals(9L, usage.reasoningTokens)
            assertEquals(40L, usage.cachedInputTokens)
            assertEquals(10L, usage.cacheWriteTokens)
            assertEquals(230L, usage.totalTokens)
            assertEquals(TokenUsageSource.OPENAI_RESPONSES, usage.source)
        }
    }

    @Test
    fun `Gemini usageMetadata发布统一usage`() = runBlocking {
        val body = buildString {
            append("data: ")
            append(
                """{"candidates":[{"content":{"parts":[{"text":"ok"}]}}],"usageMetadata":{"promptTokenCount":300,"candidatesTokenCount":40,"thoughtsTokenCount":11,"cachedContentTokenCount":50,"totalTokenCount":340}}"""
            )
            append("\n\ndata: [DONE]\n\n")
        }

        withHttpClient(body = body) { client ->
            val usage = GeminiDirectClient.streamChatDirect(
                client,
                request("Gemini", "Gemini"),
            ).toList().filterIsInstance<AppStreamEvent.Usage>().single().usage

            assertEquals(300L, usage.inputTokens)
            assertEquals(40L, usage.outputTokens)
            assertEquals(11L, usage.reasoningTokens)
            assertEquals(50L, usage.cachedInputTokens)
            assertEquals(340L, usage.totalTokens)
            assertEquals(TokenUsageSource.GEMINI, usage.source)
        }
    }

    @Test
    fun `Anthropic合并message start与delta usage`() = runBlocking {
        val body = buildString {
            append("data: ")
            append(
                """{"type":"message_start","message":{"usage":{"input_tokens":400,"cache_read_input_tokens":60,"cache_creation_input_tokens":20}}}"""
            )
            append("\n\ndata: ")
            append(
                """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":50}}"""
            )
            append("\n\ndata: {\"type\":\"message_stop\"}\n\n")
        }

        withHttpClient(body = body) { client ->
            val usageEvents = AnthropicDirectClient.streamChatDirect(
                client,
                request("Anthropic", "Anthropic"),
            ).toList().filterIsInstance<AppStreamEvent.Usage>()

            assertEquals(2, usageEvents.size)
            assertFalse(usageEvents.first().usage.isFinal)
            val usage = usageEvents.last().usage
            assertEquals(400L, usage.inputTokens)
            assertEquals(50L, usage.outputTokens)
            assertEquals(60L, usage.cachedInputTokens)
            assertEquals(20L, usage.cacheWriteTokens)
            assertTrue(usage.isFinal)
            assertEquals(TokenUsageSource.ANTHROPIC, usage.source)
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
