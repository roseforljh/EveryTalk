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
import io.ktor.http.content.TextContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun `OpenAI Chat工具图片不生成虚假用户消息`() = runBlocking {
        val firstResponse = buildString {
            append("data: ")
            append(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-image","function":{"name":"image-tool","arguments":"{}"}},{"index":1,"id":"call-tail","function":{"name":"tail-tool","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
            )
            append("\n\ndata: [DONE]\n\n")
        }
        val finalResponse = "data: {\"choices\":[{\"delta\":{\"content\":\"完成\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        val requestCount = AtomicInteger()
        var followUpPayload: String? = null
        val client = HttpClient(MockEngine { request ->
            val body = if (requestCount.incrementAndGet() == 1) {
                firstResponse
            } else {
                followUpPayload = (request.body as TextContent).text
                finalResponse
            }
            respond(
                content = ByteReadChannel(body.toByteArray(Charsets.UTF_8)),
                headers = Headers.build { append(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()) },
            )
        }) {
            expectSuccess = false
            install(HttpTimeout)
        }
        OpenAIDirectClient.setMcpToolExecutor { name, _, _ ->
            if (name == "image-tool") {
                JsonObject(
                    mapOf(
                        "content" to JsonPrimitive("网页正文"),
                        "_images" to JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "base64" to JsonPrimitive("aGVsbG8="),
                                        "mimeType" to JsonPrimitive("image/png"),
                                    )
                                )
                            )
                        ),
                    )
                )
            } else {
                JsonPrimitive("尾部工具结果")
            }
        }

        try {
            val events = OpenAIDirectClient.streamChatDirect(client, request("OpenAI", "OpenAI")).toList()
            val messages = Json.parseToJsonElement(checkNotNull(followUpPayload))
                .jsonObject.getValue("messages").jsonArray.map { it.jsonObject }
            val history = messages.takeLast(3)

            assertEquals(listOf("assistant", "tool", "tool"), history.map { it.getValue("role").jsonPrimitive.content })
            assertEquals("call-image", history[1].getValue("tool_call_id").jsonPrimitive.content)
            assertEquals("call-tail", history[2].getValue("tool_call_id").jsonPrimitive.content)
            assertEquals(1, messages.count { it["role"]?.jsonPrimitive?.content == "user" })
            assertFalse(history[1].getValue("content").jsonPrimitive.content.contains("_images"))
            assertFalse(checkNotNull(followUpPayload).contains("aGVsbG8="))
            assertFalse(events.any { it is AppStreamEvent.Error })
            assertEquals(2, requestCount.get())
        } finally {
            OpenAIDirectClient.setMcpToolExecutor(null)
            client.close()
        }
    }

    @Test
    fun `OpenAI Responses工具图片保留调用归属`() = runBlocking {
        val firstResponse = buildString {
            appendResponsesEvent("""{"type":"response.output_item.added","item":{"type":"function_call","call_id":"call-image","name":"image-tool"}}""")
            appendResponsesEvent("""{"type":"response.function_call_arguments.done","call_id":"call-image","name":"image-tool","arguments":"{}"}""")
            appendResponsesEvent("""{"type":"response.completed"}""")
            append("data: [DONE]\n\n")
        }
        val finalResponse = "data: {\"type\":\"response.completed\"}\n\ndata: [DONE]\n\n"
        val requestCount = AtomicInteger()
        var followUpPayload: String? = null
        val client = HttpClient(MockEngine { request ->
            val body = if (requestCount.incrementAndGet() == 1) {
                firstResponse
            } else {
                followUpPayload = (request.body as TextContent).text
                finalResponse
            }
            respond(
                content = ByteReadChannel(body.toByteArray(Charsets.UTF_8)),
                headers = Headers.build { append(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()) },
            )
        }) {
            expectSuccess = false
            install(HttpTimeout)
        }
        OpenAIResponsesClient.setMcpToolExecutor { _, _, _ ->
            JsonObject(
                mapOf(
                    "content" to JsonPrimitive("网页正文"),
                    "_images" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "base64" to JsonPrimitive("aGVsbG8="),
                                    "mimeType" to JsonPrimitive("image/png"),
                                )
                            )
                        )
                    ),
                )
            )
        }

        try {
            val events = OpenAIResponsesClient.streamChatResponses(client, request("OpenAI", "OpenAI")).toList()
            val input = Json.parseToJsonElement(checkNotNull(followUpPayload))
                .jsonObject.getValue("input").jsonArray.map { it.jsonObject }
            val output = input.single { it["type"]?.jsonPrimitive?.content == "function_call_output" }

            assertEquals("call-image", output.getValue("call_id").jsonPrimitive.content)
            val parts = output.getValue("output").jsonArray.map { it.jsonObject }
            assertEquals(listOf("input_text", "input_image"), parts.map { it.getValue("type").jsonPrimitive.content })
            assertFalse(parts[0].getValue("text").jsonPrimitive.content.contains("_images"))
            assertEquals("data:image/png;base64,aGVsbG8=", parts[1].getValue("image_url").jsonPrimitive.content)
            assertFalse(events.any { it is AppStreamEvent.Error })
            assertEquals(2, requestCount.get())
        } finally {
            OpenAIResponsesClient.setMcpToolExecutor(null)
            client.close()
        }
    }

    @Test
    fun `Gemini工具图片嵌套在对应functionResponse中`() = runBlocking {
        val firstResponse = buildString {
            append("data: ")
            append(
                """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"image-tool","args":{}}},{"functionCall":{"name":"tail-tool","args":{}}}]},"finishReason":"STOP"}]}"""
            )
            append("\n\ndata: [DONE]\n\n")
        }
        val finalResponse =
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"完成\"}]},\"finishReason\":\"STOP\"}]}\n\ndata: [DONE]\n\n"
        val requestCount = AtomicInteger()
        var followUpPayload: String? = null
        val client = HttpClient(MockEngine { request ->
            val body = if (requestCount.incrementAndGet() == 1) {
                firstResponse
            } else {
                followUpPayload = (request.body as TextContent).text
                finalResponse
            }
            respond(
                content = ByteReadChannel(body.toByteArray(Charsets.UTF_8)),
                headers = Headers.build { append(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()) },
            )
        }) {
            expectSuccess = false
            install(HttpTimeout)
        }
        GeminiDirectClient.setMcpToolExecutor { name, _, _ ->
            if (name == "image-tool") {
                JsonObject(
                    mapOf(
                        "content" to JsonPrimitive("网页正文"),
                        "_images" to JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "base64" to JsonPrimitive("data:image/png;base64,aGVsbG8="),
                                        "mimeType" to JsonPrimitive("image/png"),
                                    )
                                )
                            )
                        ),
                    )
                )
            } else {
                JsonPrimitive("尾部工具结果")
            }
        }

        try {
            val events = GeminiDirectClient.streamChatDirect(client, request("Gemini", "Gemini")).toList()
            val contents = Json.parseToJsonElement(checkNotNull(followUpPayload))
                .jsonObject.getValue("contents").jsonArray.map { it.jsonObject }
            val toolMessage = contents.last()
            val parts = toolMessage.getValue("parts").jsonArray.map { it.jsonObject }

            assertEquals("user", toolMessage.getValue("role").jsonPrimitive.content)
            assertEquals(2, parts.size)
            assertFalse(parts.any { it.containsKey("inlineData") })
            val imageResponse = parts[0].getValue("functionResponse").jsonObject
            assertEquals("image-tool", imageResponse.getValue("name").jsonPrimitive.content)
            assertFalse(imageResponse.getValue("response").toString().contains("_images"))
            val imageData = imageResponse.getValue("parts").jsonArray.single()
                .jsonObject.getValue("inlineData").jsonObject
            assertEquals("image/png", imageData.getValue("mimeType").jsonPrimitive.content)
            assertEquals("aGVsbG8=", imageData.getValue("data").jsonPrimitive.content)
            assertEquals(
                "tail-tool",
                parts[1].getValue("functionResponse").jsonObject.getValue("name").jsonPrimitive.content,
            )
            assertFalse(events.any { it is AppStreamEvent.Error })
            assertEquals(2, requestCount.get())
        } finally {
            GeminiDirectClient.setMcpToolExecutor(null)
            client.close()
        }
    }

    @Test
    fun `Anthropic工具图片嵌套在对应toolResult中`() = runBlocking {
        val firstResponse = buildString {
            appendResponsesEvent(
                """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tool-image","name":"image-tool","input":{}}}"""
            )
            appendResponsesEvent(
                """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tool-tail","name":"tail-tool","input":{}}}"""
            )
            appendResponsesEvent("""{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""")
            appendResponsesEvent("""{"type":"message_stop"}""")
        }
        val finalResponse = buildString {
            appendResponsesEvent(
                """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""
            )
            appendResponsesEvent(
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"完成"}}"""
            )
            appendResponsesEvent("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")
            appendResponsesEvent("""{"type":"message_stop"}""")
        }
        val requestCount = AtomicInteger()
        var followUpPayload: String? = null
        val client = HttpClient(MockEngine { request ->
            val body = if (requestCount.incrementAndGet() == 1) {
                firstResponse
            } else {
                followUpPayload = (request.body as TextContent).text
                finalResponse
            }
            respond(
                content = ByteReadChannel(body.toByteArray(Charsets.UTF_8)),
                headers = Headers.build { append(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()) },
            )
        }) {
            expectSuccess = false
            install(HttpTimeout)
        }
        AnthropicDirectClient.setMcpToolExecutor { name, _, _ ->
            if (name == "image-tool") {
                JsonObject(
                    mapOf(
                        "content" to JsonPrimitive("网页正文"),
                        "_images" to JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "base64" to JsonPrimitive("data:image/png;base64,aGVsbG8="),
                                        "mimeType" to JsonPrimitive("image/png"),
                                    )
                                )
                            )
                        ),
                    )
                )
            } else {
                JsonPrimitive("尾部工具结果")
            }
        }

        try {
            val events = AnthropicDirectClient.streamChatDirect(client, request("Anthropic", "Anthropic")).toList()
            val messages = Json.parseToJsonElement(checkNotNull(followUpPayload))
                .jsonObject.getValue("messages").jsonArray.map { it.jsonObject }
            val toolMessage = messages.last()
            val toolResults = toolMessage.getValue("content").jsonArray.map { it.jsonObject }

            assertEquals("user", toolMessage.getValue("role").jsonPrimitive.content)
            assertEquals(2, toolResults.size)
            val imageResult = toolResults[0]
            assertEquals("tool_result", imageResult.getValue("type").jsonPrimitive.content)
            assertEquals("tool-image", imageResult.getValue("tool_use_id").jsonPrimitive.content)
            val content = imageResult.getValue("content").jsonArray.map { it.jsonObject }
            assertEquals(listOf("text", "image"), content.map { it.getValue("type").jsonPrimitive.content })
            assertFalse(content[0].getValue("text").jsonPrimitive.content.contains("_images"))
            val source = content[1].getValue("source").jsonObject
            assertEquals("image/png", source.getValue("media_type").jsonPrimitive.content)
            assertEquals("aGVsbG8=", source.getValue("data").jsonPrimitive.content)
            assertEquals("tool-tail", toolResults[1].getValue("tool_use_id").jsonPrimitive.content)
            assertFalse(events.any { it is AppStreamEvent.Error })
            assertEquals(2, requestCount.get())
        } finally {
            AnthropicDirectClient.setMcpToolExecutor(null)
            client.close()
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
