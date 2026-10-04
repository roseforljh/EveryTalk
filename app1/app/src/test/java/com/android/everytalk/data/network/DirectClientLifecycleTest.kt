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
import kotlinx.serialization.json.Json
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
    fun `工具参数尚未完整时三种流协议立即发布编写状态`() = runBlocking {
        val starts = mapOf(
            "OpenAI" to """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"exec","arguments":"{\"command\":"}}]}}]}""",
            "Responses" to """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","id":"item-1","call_id":"call-1","name":"exec","arguments":""}}""",
            "Anthropic" to """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call-1","name":"exec","input":{}}}""",
        )
        val endings = mapOf(
            "OpenAI" to listOf(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"pwd\"}"}}]}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            ),
            "Responses" to listOf(
                """{"type":"response.function_call_arguments.delta","output_index":0,"item_id":"item-1","delta":"{\"command\":\"pwd\"}"}""",
                """{"type":"response.completed"}""",
            ),
            "Anthropic" to listOf(
                """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"command\":\"pwd\"}"}}""",
                """{"type":"content_block_stop","index":0}""",
                """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""",
                """{"type":"message_stop"}""",
            ),
        )
        for ((protocol, start) in starts) {
            val channel = ByteChannel(autoFlush = true)
            val events = mutableListOf<AppStreamEvent>()
            val writing = CompletableDeferred<Unit>()
            val client = HttpClient(MockEngine {
                respond(channel, headers = Headers.build {
                    append(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
                })
            })
            val job = launch {
                val flow = when (protocol) {
                    "OpenAI" -> OpenAIDirectClient.streamChatDirect(client, request("OpenAI", "OpenAI"))
                    "Responses" -> OpenAIResponsesClient.streamChatResponses(client, request("OpenAI", "OpenAI"))
                    else -> AnthropicDirectClient.streamChatDirect(client, request("Anthropic", "Anthropic"))
                }
                flow.collect { event ->
                    events += event
                    if (event is AppStreamEvent.ExecutionStatusUpdate && event.status == TOOL_CALL_WRITING_STATUS) {
                        writing.complete(Unit)
                    }
                }
            }
            try {
                channel.writeStringUtf8("data: $start\n\n")
                withTimeout(5_000) { writing.await() }
                assertTrue("$protocol 还应等待剩余参数", job.isActive)
                assertTrue("$protocol 不得提前发布完整调用", events.none { it is AppStreamEvent.ToolCall })

                endings.getValue(protocol).forEach { channel.writeStringUtf8("data: $it\n\n") }
                channel.close()
                withTimeout(5_000) { job.join() }
                assertEquals(protocol, 1, events.count {
                    it is AppStreamEvent.ExecutionStatusUpdate && it.status == TOOL_CALL_WRITING_STATUS
                })
                assertEquals(protocol, "pwd", events.filterIsInstance<AppStreamEvent.ToolCall>()
                    .single().argumentsObj["command"]?.jsonPrimitive?.content)
                assertTrue(protocol, events.none { it is AppStreamEvent.Error })
            } finally {
                channel.close()
                job.cancelAndJoin()
                client.close()
            }
        }
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
    fun `Gemini流解析丢弃空思考块并保留工具调用签名`() = runBlocking {
        val body =
            """data: {"candidates":[{"content":{"role":"model","parts":[{"thought":true,"text":"","thoughtSignature":"cmVhc29uaW5nLXNpZw=="},{"functionCall":{"id":"call-1","name":"exec","args":{}},"thoughtSignature":"dG9vbC1zaWc="}]},"finishReason":"STOP"}]}

"""
        withHttpClient(body = body) { client ->
            val events = GeminiDirectClient.streamChatDirect(client, request("Google", "Gemini")).toList()

            val toolCall = events.filterIsInstance<AppStreamEvent.ToolCall>().single()
            val continuation = events.filterIsInstance<AppStreamEvent.ProviderContinuation>().single()
            assertTrue(events.filterIsInstance<AppStreamEvent.Reasoning>().isEmpty())
            assertEquals("dG9vbC1zaWc=", toolCall.thoughtSignature)
            assertFalse(continuation.payloadJson.contains("cmVhc29uaW5nLXNpZw=="))
            assertTrue(continuation.payloadJson.contains("dG9vbC1zaWc="))
            assertEquals("tool_use", events.filterIsInstance<AppStreamEvent.Finish>().single().reason)
        }
    }

    @Test
    fun `Gemini跨SSE分片合并文本并保留首个签名`() = runBlocking {
        val body = buildString {
            append("data: ")
            append("""{"candidates":[{"content":{"role":"model","parts":[{"thought":true,"text":"分析","thoughtSignature":"dGhpbmstc2ln"}]}}]}""")
            append("\n\ndata: ")
            append("""{"candidates":[{"content":{"role":"model","parts":[{"thought":true,"text":"完成"},{"text":"结果","thoughtSignature":"dGV4dC1zaWc="}]}}]}""")
            append("\n\ndata: ")
            append("""{"candidates":[{"content":{"role":"model","parts":[{"text":"已生成"}]},"finishReason":"STOP"}]}""")
            append("\n\n")
        }
        withHttpClient(body = body) { client ->
            val continuation = GeminiDirectClient.streamChatDirect(client, request("Google", "Gemini"))
                .toList().filterIsInstance<AppStreamEvent.ProviderContinuation>().single()
            val parts = Json.parseToJsonElement(continuation.payloadJson).jsonObject
                .getValue("parts").jsonArray.map { it.jsonObject }

            assertEquals(2, parts.size)
            assertEquals("分析完成", parts[0].getValue("text").jsonPrimitive.content)
            assertEquals("dGhpbmstc2ln", parts[0].getValue("thoughtSignature").jsonPrimitive.content)
            assertEquals("结果已生成", parts[1].getValue("text").jsonPrimitive.content)
            assertEquals("dGV4dC1zaWc=", parts[1].getValue("thoughtSignature").jsonPrimitive.content)
        }
    }

    @Test
    fun `Gemini流中的签名单独碎片不会进入下一轮Continuation`() = runBlocking {
        val body = buildString {
            append("data: ")
            append("""{"candidates":[{"content":{"role":"model","parts":[{"thoughtSignature":"c2ln"}]}}]}""")
            append("\n\ndata: ")
            append("""{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"id":"call-1","name":"exec","args":{}}}]} ,"finishReason":"STOP"}]}""")
            append("\n\n")
        }
        withHttpClient(body = body) { client ->
            val continuation = GeminiDirectClient.streamChatDirect(client, request("Google", "Gemini"))
                .toList().filterIsInstance<AppStreamEvent.ProviderContinuation>().single()
            val parts = Json.parseToJsonElement(continuation.payloadJson).jsonObject
                .getValue("parts").jsonArray.map { it.jsonObject }

            assertEquals(1, parts.size)
            assertTrue(parts.single().containsKey("functionCall"))
            assertTrue(parts.none { it.keys == setOf("thoughtSignature") })
        }
    }

    @Test
    fun `Gemini同一回复的重复工具ID会被拆成两个ID`() = runBlocking {
        val body =
            """data: {"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"id":"same-id","name":"first","args":{}}},{"functionCall":{"id":"same-id","name":"second","args":{}}}]},"finishReason":"STOP"}]}

"""
        withHttpClient(body = body) { client ->
            val calls = GeminiDirectClient.streamChatDirect(client, request("Google", "Gemini"))
                .toList().filterIsInstance<AppStreamEvent.ToolCall>()

            assertEquals(2, calls.size)
            assertEquals("same-id", calls[0].id)
            assertTrue(calls[1].id.startsWith("fc_local_"))
            assertTrue(calls[0].id != calls[1].id)
        }
    }

    @Test
    fun `Gemini最大输出长度映射成length`() = runBlocking {
        val body =
            """data: {"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"id":"call-1","name":"exec","args":{}}} ]},"finishReason":"MAX_TOKENS"}]}

"""
        withHttpClient(body = body) { client ->
            val events = GeminiDirectClient.streamChatDirect(client, request("Google", "Gemini")).toList()

            assertEquals("length", events.filterIsInstance<AppStreamEvent.Finish>().single().reason)
        }
    }

    @Test
    fun `Gemini异常终止原因按Pi语义返回错误`() = runBlocking {
        val body =
            """data: {"candidates":[{"content":{"role":"model","parts":[]} ,"finishReason":"MALFORMED_FUNCTION_CALL"}]}

"""
        withHttpClient(body = body) { client ->
            val events = GeminiDirectClient.streamChatDirect(client, request("Google", "Gemini")).toList()

            assertEquals("malformed_function_call", events.filterIsInstance<AppStreamEvent.Error>().single().code)
            assertEquals("error", events.filterIsInstance<AppStreamEvent.Finish>().single().reason)
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
            append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
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
    fun `OpenAI Chat流保留Pi可回放的reasoning字段名`() = runBlocking {
        val events = mutableListOf<AppStreamEvent>()
        val channel = ByteReadChannel(
            "data: {\"choices\":[{\"delta\":{\"reasoning\":\"分析\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n",
        )

        OpenAIDirectClient.parseOpenAISSEStreamWithTools(
            channel = channel,
            onToolCall = {},
            emitEvent = events::add,
        )

        val reasoning = events.filterIsInstance<AppStreamEvent.Reasoning>().single()
        assertEquals("分析", reasoning.text)
        assertEquals("reasoning", reasoning.thoughtSignature)
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
                """{"candidates":[{"content":{"parts":[{"text":"answer"}]},"finishReason":"STOP","groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://example.com/gemini","title":"Gemini"}}]}}]}"""
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
            assertEquals(listOf("stop"), events.filterIsInstance<AppStreamEvent.Finish>().map { it.reason })
            assertFalse(events.any { it is AppStreamEvent.Error })
        }
    }

    @Test
    fun `三个Provider把输出上限统一映射为length`() = runBlocking {
        val openAiBody = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n"
        withHttpClient(body = openAiBody) { client ->
            val reason = OpenAIDirectClient.streamChatDirect(client, request("OpenAI", "OpenAI"))
                .toList().filterIsInstance<AppStreamEvent.Finish>().single().reason
            assertEquals("length", reason)
        }

        val anthropicBody = buildString {
            append("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"}}\n\n")
            append("data: {\"type\":\"message_stop\"}\n\n")
        }
        withHttpClient(body = anthropicBody) { client ->
            val reason = AnthropicDirectClient.streamChatDirect(client, request("Anthropic", "Anthropic"))
                .toList().filterIsInstance<AppStreamEvent.Finish>().single().reason
            assertEquals("length", reason)
        }

        val responsesBody = buildString {
            appendResponsesEvent(
                """{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}}""",
            )
            append("data: [DONE]\n\n")
        }
        withHttpClient(body = responsesBody) { client ->
            val reason = OpenAIResponsesClient.streamChatResponses(client, request("OpenAI", "OpenAI"))
                .toList().filterIsInstance<AppStreamEvent.Finish>().single().reason
            assertEquals("length", reason)
        }
    }

    @Test
    fun `OpenAI Responses的incomplete终止事件保留回放签名`() = runBlocking {
        val body = buildString {
            appendResponsesEvent(
                """{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[{"id":"rs-1","type":"reasoning","encrypted_content":"opaque"},{"id":"msg-1","type":"message","role":"assistant","phase":"commentary","content":[{"type":"output_text","text":"partial","annotations":[]}]}]}}""",
            )
            append("data: [DONE]\n\n")
        }

        withHttpClient(body = body) { client ->
            val events = OpenAIResponsesClient.streamChatResponses(client, request("OpenAI", "OpenAI")).toList()
            val reasoningSignature = events.filterIsInstance<AppStreamEvent.Reasoning>()
                .single { it.signatureOnlyUpdate }
            val messageSignature = events.filterIsInstance<AppStreamEvent.Content>()
                .single { it.signatureOnlyUpdate }

            assertTrue(reasoningSignature.thoughtSignature.orEmpty().contains("\"id\":\"rs-1\""))
            assertTrue(messageSignature.thoughtSignature.orEmpty().contains("\"id\":\"msg-1\""))
            assertEquals("partial", events.filterIsInstance<AppStreamEvent.ContentFinal>().single().text)
            assertEquals("length", events.filterIsInstance<AppStreamEvent.Finish>().single().reason)
        }
    }

    @Test
    fun `OpenAI Responses按outputIndex合并缺少ID的参数分片`() = runBlocking {
        val body = buildString {
            appendResponsesEvent(
                """{"type":"response.output_item.added","output_index":0,"item":{"id":"fc-1","type":"function_call","call_id":"call-1","name":"exec","arguments":""}}""",
            )
            appendResponsesEvent(
                """{"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\"command\":\"pwd\"}"}""",
            )
            appendResponsesEvent(
                """{"type":"response.output_item.done","output_index":0,"item":{"id":"fc-1","type":"function_call","call_id":"call-1","name":"exec"}}""",
            )
            appendResponsesEvent("""{"type":"response.completed","response":{"output":[]}}""")
            append("data: [DONE]\n\n")
        }

        withHttpClient(body = body) { client ->
            val call = OpenAIResponsesClient.streamChatResponses(client, request("OpenAI", "OpenAI"))
                .toList()
                .filterIsInstance<AppStreamEvent.ToolCall>()
                .single()

            assertEquals("call-1|fc-1", call.id)
            assertEquals("exec", call.name)
            assertEquals("pwd", call.argumentsObj.getValue("command").jsonPrimitive.content)
        }
    }

    @Test
    fun `OpenAI Chat缺少最终finishReason时禁止把半截工具调用当成功`() = runBlocking {
        val body = buildString {
            append(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\"," +
                    "\"function\":{\"name\":\"exec\",\"arguments\":\"{\\\"command\\\":\"}}]}}]}\n\n",
            )
            append("data: [DONE]\n\n")
        }

        withHttpClient(body = body) { client ->
            val events = OpenAIDirectClient.streamChatDirect(client, request("OpenAI", "OpenAI")).toList()

            assertTrue(events.any { it is AppStreamEvent.Error })
            assertTrue(events.none { it is AppStreamEvent.Finish && it.reason == "tool_use" })
        }
    }

    @Test
    fun `OpenAI Chat在明确收到DONE但缺失finish_reason时对纯文本流有边界兼容正常结束`() = runBlocking {
        val body = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"你好，EveryTalk！\"}}]}\n\n")
            append("data: [DONE]\n\n")
        }

        withHttpClient(body = body) { client ->
            val events = OpenAIDirectClient.streamChatDirect(client, request("OpenAI", "OpenAI")).toList()

            assertTrue(events.none { it is AppStreamEvent.Error })
            val finalContent = events.filterIsInstance<AppStreamEvent.ContentFinal>().single()
            assertEquals("你好，EveryTalk！", finalContent.text)
            val finish = events.filterIsInstance<AppStreamEvent.Finish>().single()
            assertEquals("stop", finish.reason)
        }
    }

    @Test
    fun `OpenAI Chat未收到DONE且缺失finish_reason的裸EOF必须抛出异常`() = runBlocking {
        val body = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"半截文本\"}}]}\n\n")
            // 没有 [DONE]，也没有 finish_reason，直接结束流（模拟连接截断裸 EOF）
        }

        withHttpClient(body = body) { client ->
            val events = OpenAIDirectClient.streamChatDirect(client, request("OpenAI", "OpenAI")).toList()

            assertTrue(events.any { it is AppStreamEvent.Error })
            // 客户端仍会发送错误终态供界面收尾，禁止的是成功终态和完整正文事件。
            assertTrue(events.none { it is AppStreamEvent.ContentFinal })
            assertTrue(events.filterIsInstance<AppStreamEvent.Finish>().none { it.reason == "stop" || it.reason == "tool_use" })
        }
    }

    @Test
    fun `OpenAI Chat明确收到DONE且缺少finish_reason但工具参数完整时允许正常执行工具`() = runBlocking {
        val body = buildString {
            append(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-valid\"," +
                    "\"function\":{\"name\":\"exec\",\"arguments\":\"{\\\"command\\\":\\\"ls\\\"}\"}}]}}]}\n\n",
            )
            append("data: [DONE]\n\n")
        }

        withHttpClient(body = body) { client ->
            val events = OpenAIDirectClient.streamChatDirect(client, request("OpenAI", "OpenAI")).toList()

            assertTrue(events.none { it is AppStreamEvent.Error })
            val call = events.filterIsInstance<AppStreamEvent.ToolCall>().single()
            assertEquals("call-valid", call.id)
            assertEquals("exec", call.name)
            assertEquals("ls", call.argumentsObj.getValue("command").jsonPrimitive.content)
            val finish = events.filterIsInstance<AppStreamEvent.Finish>().single()
            assertEquals("tool_use", finish.reason)
        }
    }

    @Test
    fun `OpenAI Chat缺失finish_reason且工具缺少id时禁止执行`() = runBlocking {
        val body = buildString {
            append(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0," +
                    "\"function\":{\"name\":\"exec\",\"arguments\":\"{\\\"command\\\":\\\"ls\\\"}\"}}]}}]}\n\n",
            )
            append("data: [DONE]\n\n")
        }

        withHttpClient(body = body) { client ->
            val events = OpenAIDirectClient.streamChatDirect(client, request("OpenAI", "OpenAI")).toList()

            assertTrue(events.any { it is AppStreamEvent.Error })
            assertTrue(events.none { it is AppStreamEvent.Finish && it.reason == "tool_use" })
        }
    }

    @Test
    fun `OpenAI Chat缺失finish_reason且工具缺少name时禁止执行`() = runBlocking {
        val body = buildString {
            append(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\"," +
                    "\"function\":{\"arguments\":\"{\\\"command\\\":\\\"ls\\\"}\"}}]}}]}\n\n",
            )
            append("data: [DONE]\n\n")
        }

        withHttpClient(body = body) { client ->
            val events = OpenAIDirectClient.streamChatDirect(client, request("OpenAI", "OpenAI")).toList()

            assertTrue(events.any { it is AppStreamEvent.Error })
            assertTrue(events.none { it is AppStreamEvent.Finish && it.reason == "tool_use" })
        }
    }

    @Test
    fun `OpenAI Chat缺失finish_reason且工具参数为非对象JSON时禁止执行`() = runBlocking {
        val body = buildString {
            append(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\"," +
                    "\"function\":{\"name\":\"exec\",\"arguments\":\"[\\\"ls\\\"]\"}}]}}]}\n\n",
            )
            append("data: [DONE]\n\n")
        }

        withHttpClient(body = body) { client ->
            val events = OpenAIDirectClient.streamChatDirect(client, request("OpenAI", "OpenAI")).toList()

            assertTrue(events.any { it is AppStreamEvent.Error })
            assertTrue(events.none { it is AppStreamEvent.Finish && it.reason == "tool_use" })
        }
    }

    @Test
    fun `OpenAI Chat流式工具id和名称晚到时仍合并为同一调用`() = runBlocking {
        val body = buildString {
            append(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0," +
                    "\"function\":{\"arguments\":\"{\\\"command\\\":\"}}]}}]}\n\n",
            )
            append(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-real\"," +
                    "\"function\":{\"name\":\"exec\",\"arguments\":\"\\\"pwd\\\"}\"}}]}," +
                    "\"finish_reason\":\"tool_calls\"}]}\n\n",
            )
            append("data: [DONE]\n\n")
        }

        withHttpClient(body = body) { client ->
            val call = OpenAIDirectClient.streamChatDirect(client, request("OpenAI", "OpenAI"))
                .toList()
                .filterIsInstance<AppStreamEvent.ToolCall>()
                .single()

            assertEquals("call-real", call.id)
            assertEquals("exec", call.name)
            assertEquals("pwd", call.argumentsObj.getValue("command").jsonPrimitive.content)
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
                """{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":300,"candidatesTokenCount":40,"thoughtsTokenCount":11,"cachedContentTokenCount":50,"totalTokenCount":340}}"""
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
            assertEquals(480L, usage.inputTokens)
            assertEquals(480L, usageEvents.first().usage.inputTokens)
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
