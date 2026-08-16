package com.android.everytalk.data.network

import android.app.Application
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ContextCompressionState
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.ThinkingConfig
import com.android.everytalk.data.DataClass.ReasoningMode
import com.android.everytalk.data.DataClass.RequestContextManagement
import com.android.everytalk.data.DataClass.AgentAssistantApiMessage
import com.android.everytalk.data.DataClass.AgentToolCallApiPart
import com.android.everytalk.data.DataClass.AgentToolResultApiMessage
import com.android.everytalk.data.DataClass.ProviderTurnContinuation
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import com.android.everytalk.data.computer.ComputerToolCatalog
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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AnthropicDirectClientTest {
    @Test
    fun `payload uses top level system history image tools and required max tokens`() {
        val request = request(
            messages = listOf(
                SimpleTextApiMessage(role = "system", content = "只回答事实"),
                SimpleTextApiMessage(role = "user", content = "第一问"),
                SimpleTextApiMessage(role = "assistant", content = "第一答"),
                PartsApiMessage(
                    role = "user",
                    parts = listOf(
                        ApiContentPart.Text("看图"),
                        ApiContentPart.InlineData("aGVsbG8=", "image/png"),
                    ),
                ),
            ),
            generationConfig = GenerationConfig(maxOutputTokens = 4096),
            tools = listOf(
                mapOf(
                    "type" to "function",
                    "function" to mapOf(
                        "name" to "weather",
                        "description" to "查询天气",
                        "parameters" to mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
                    ),
                ),
            ),
        )

        val payload = Json.parseToJsonElement(AnthropicDirectClient.buildAnthropicPayload(request)).jsonObject

        assertEquals("claude-sonnet-4-5", payload.getValue("model").jsonPrimitive.content)
        assertEquals(4096, payload.getValue("max_tokens").jsonPrimitive.content.toInt())
        assertTrue(payload.getValue("system").jsonPrimitive.content.contains("只回答事实"))
        assertEquals(3, payload.getValue("messages").jsonArray.size)
        val lastContent = payload.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonArray
        assertEquals("image", lastContent.last().jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("image/png", lastContent.last().jsonObject.getValue("source").jsonObject.getValue("media_type").jsonPrimitive.content)
        val tool = payload.getValue("tools").jsonArray.single().jsonObject
        assertEquals("weather", tool.getValue("name").jsonPrimitive.content)
        assertNotNull(tool["input_schema"])
    }

    @Test
    fun `Computer工具按Anthropic input schema原样发送可选参数`() {
        val payload = Json.parseToJsonElement(
            AnthropicDirectClient.buildAnthropicPayload(
                request(tools = ComputerToolCatalog.definitions()),
            ),
        ).jsonObject
        val download = payload.getValue("tools").jsonArray
            .map { it.jsonObject }
            .first { it.getValue("name").jsonPrimitive.content == "download" }
        val schema = download.getValue("input_schema").jsonObject

        assertTrue(schema.getValue("properties").jsonObject.containsKey("suggested_name"))
        assertFalse(schema.getValue("required").jsonArray.any { it.jsonPrimitive.content == "suggested_name" })
        assertFalse(download.containsKey("parameters"))
    }

    @Test
    fun `thinking payload uses budget and omits sampling controls`() {
        val payload = Json.parseToJsonElement(
            AnthropicDirectClient.buildAnthropicPayload(
                request(
                    generationConfig = GenerationConfig(
                        temperature = 0.2f,
                        topP = 0.8f,
                        maxOutputTokens = 4096,
                        thinkingConfig = ThinkingConfig(
                            includeThoughts = true,
                            thinkingBudget = 2048,
                            reasoningMode = ReasoningMode.BUDGET,
                        ),
                    ),
                ),
            ),
        ).jsonObject

        assertEquals(2048, payload.getValue("thinking").jsonObject.getValue("budget_tokens").jsonPrimitive.content.toInt())
        assertFalse(payload.containsKey("temperature"))
        assertFalse(payload.containsKey("top_p"))
    }

    @Test
    fun `adaptive thinking payload uses configurable medium effort`() {
        val payload = Json.parseToJsonElement(
            AnthropicDirectClient.buildAnthropicPayload(
                request(
                    generationConfig = GenerationConfig(
                        thinkingConfig = ThinkingConfig(
                            includeThoughts = true,
                            reasoningMode = ReasoningMode.EFFORT,
                            reasoningEffort = "medium",
                        ),
                    ),
                ),
            ),
        ).jsonObject

        assertEquals("adaptive", payload.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("medium", payload.getValue("output_config").jsonObject.getValue("effort").jsonPrimitive.content)
    }

    @Test
    fun `sse parser emits thinking text and captures tool input with signature`() = runTest {
        val sse = buildString {
            event("""{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""")
            event("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"分析"}}""")
            event("""{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}""")
            event("""{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""")
            event("""{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"结果"}}""")
            event("""{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"tool-1","name":"weather","input":{}}}""")
            event("""{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"city\":\"北京\"}"}}""")
            event("""{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""")
            event("""{"type":"message_stop"}""")
        }
        val events = mutableListOf<AppStreamEvent>()

        val result = AnthropicDirectClient.parseAnthropicSse(ByteReadChannel(sse.toByteArray())) { events += it }

        assertEquals("分析", events.filterIsInstance<AppStreamEvent.Reasoning>().single().text)
        assertEquals("结果", events.filterIsInstance<AppStreamEvent.Content>().single().text)
        assertEquals(1, events.count { it is AppStreamEvent.ReasoningFinish })
        assertEquals("北京", result.toolCalls.single().input.getValue("city").jsonPrimitive.content)
        assertEquals("sig", result.assistantContent.first().jsonObject.getValue("signature").jsonPrimitive.content)
        assertEquals("tool_use", result.stopReason)
    }

    @Test
    fun `next tool turn restores native thinking signature`() {
        val request = request(
            messages = listOf(
                SimpleTextApiMessage(role = "user", content = "检查服务"),
                AgentAssistantApiMessage(
                    reasoning = "分析",
                    toolCalls = listOf(
                        AgentToolCallApiPart("tool-1", "weather", JsonObject(emptyMap())),
                    ),
                ),
            ),
        ).copy(
            localProviderContinuation = ProviderTurnContinuation(
                protocol = ModelParameterProtocol.ANTHROPIC,
                payloadJson = """[{"type":"thinking","thinking":"分析","signature":"sig"},{"type":"tool_use","id":"tool-1","name":"weather","input":{}}]""",
            )
        )

        val payload = Json.parseToJsonElement(AnthropicDirectClient.buildAnthropicPayload(request)).jsonObject
        val assistant = payload.getValue("messages").jsonArray.last().jsonObject
        val thinking = assistant.getValue("content").jsonArray.first().jsonObject

        assertEquals("sig", thinking.getValue("signature").jsonPrimitive.content)
    }

    @Test
    fun `无原生continuation时保留中立reasoning`() {
        val payload = Json.parseToJsonElement(
            AnthropicDirectClient.buildAnthropicPayload(
                request(
                    messages = listOf(
                        SimpleTextApiMessage(role = "user", content = "检查服务"),
                        AgentAssistantApiMessage(
                            reasoning = "分析",
                            toolCalls = listOf(AgentToolCallApiPart("tool-1", "weather", JsonObject(emptyMap()))),
                        ),
                    ),
                ),
            ),
        ).jsonObject
        val assistant = payload.getValue("messages").jsonArray.last().jsonObject

        assertEquals("分析", assistant.getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `anthropic原生压缩后的下一轮只追加未覆盖工具结果`() {
        val request = request(
            messages = listOf(
                SimpleTextApiMessage(role = "user", content = "检查服务"),
                AgentAssistantApiMessage(
                    id = "assistant:turn-1",
                    toolCalls = listOf(AgentToolCallApiPart("tool-1", "exec", JsonObject(emptyMap()))),
                ),
                AgentToolResultApiMessage(
                    toolCallId = "tool-1",
                    toolName = "exec",
                    content = JsonPrimitive("结果"),
                ),
            ),
        ).copy(
            localProviderContinuation = ProviderTurnContinuation(
                protocol = ModelParameterProtocol.ANTHROPIC,
                payloadJson = """[{"type":"compaction","content":"摘要","encrypted_content":"opaque"},{"type":"tool_use","id":"tool-1","name":"exec","input":{}}]""",
                compactedContextJson = """[{"role":"assistant","content":[{"type":"compaction","content":"摘要","encrypted_content":"opaque"},{"type":"tool_use","id":"tool-1","name":"exec","input":{}}]}]""",
                compactedThroughMessageId = "assistant:turn-1",
            ),
        )

        val messages = Json.parseToJsonElement(AnthropicDirectClient.buildAnthropicPayload(request))
            .jsonObject.getValue("messages").jsonArray

        assertEquals(listOf("assistant", "user"), messages.map { it.jsonObject.getValue("role").jsonPrimitive.content })
        assertEquals(
            "compaction",
            messages.first().jsonObject.getValue("content").jsonArray.first().jsonObject
                .getValue("type").jsonPrimitive.content,
        )
        assertEquals(
            "tool_result",
            messages.last().jsonObject.getValue("content").jsonArray.single().jsonObject
                .getValue("type").jsonPrimitive.content,
        )
    }

    @Test
    fun `sse parser preserves compaction block and encrypted metadata`() = runTest {
        val sse = buildString {
            event("""{"type":"content_block_start","index":0,"content_block":{"type":"compaction","content":null,"encrypted_content":null}}""")
            event("""{"type":"content_block_delta","index":0,"delta":{"type":"compaction_delta","content":"继续任务所需摘要","encrypted_content":"opaque-state"}}""")
            event("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")
            event("""{"type":"message_stop"}""")
        }

        val result = AnthropicDirectClient.parseAnthropicSse(ByteReadChannel(sse.toByteArray())) {}

        val compaction = result.assistantContent.single().jsonObject
        assertEquals("compaction", compaction.getValue("type").jsonPrimitive.content)
        assertEquals("继续任务所需摘要", compaction.getValue("content").jsonPrimitive.content)
        assertEquals("opaque-state", compaction.getValue("encrypted_content").jsonPrimitive.content)
    }

    @Test
    fun `null compaction content does not replace authoritative history`() = runTest {
        val body = buildString {
            event("""{"type":"content_block_start","index":0,"content_block":{"type":"compaction","content":null,"encrypted_content":null}}""")
            event("""{"type":"content_block_start","index":1,"content_block":{"type":"text","text":"继续回答"}}""")
            event("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")
            event("""{"type":"message_stop"}""")
        }
        val engine = MockEngine {
            respond(
                content = ByteReadChannel(body.toByteArray()),
                status = HttpStatusCode.OK,
                headers = Headers.build { append(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()) },
            )
        }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(HttpTimeout)
        }

        try {
            val events = AnthropicDirectClient.streamChatDirect(
                client,
                request(
                    contextManagement = RequestContextManagement(
                        configId = "config-1",
                        maxContextTokens = 200_000,
                        reservedOutputTokens = 8_192,
                        compactThresholdTokens = 180_000,
                        autoCompressionEnabled = true,
                    ),
                ),
            ).toList()

            assertTrue(events.none { it is AppStreamEvent.NativeContextCompaction })
            assertEquals("继续回答", events.filterIsInstance<AppStreamEvent.ContentFinal>().single().text)
        } finally {
            client.close()
        }
    }

    @Test
    fun `stream request uses official headers and emits one terminal event`() = runTest {
        var capturedUrl: String? = null
        var capturedApiKey: String? = null
        var capturedVersion: String? = null
        val body = buildString {
            event("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""")
            event("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}""")
            event("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")
            event("""{"type":"message_stop"}""")
        }
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            capturedApiKey = request.headers["x-api-key"]
            capturedVersion = request.headers["anthropic-version"]
            respond(
                content = ByteReadChannel(body.toByteArray()),
                status = HttpStatusCode.OK,
                headers = Headers.build { append(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()) },
            )
        }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(HttpTimeout)
        }

        try {
            val events = AnthropicDirectClient.streamChatDirect(client, request()).toList()

            assertEquals("https://api.anthropic.com/v1/messages", capturedUrl)
            assertEquals("test-key", capturedApiKey)
            assertEquals("2023-06-01", capturedVersion)
            assertEquals("你好", events.filterIsInstance<AppStreamEvent.ContentFinal>().single().text)
            assertEquals(listOf("end_turn"), events.filterIsInstance<AppStreamEvent.Finish>().map { it.reason })
        } finally {
            client.close()
        }
    }

    @Test
    fun `official request enables native compaction with configured threshold`() = runTest {
        var capturedBeta: String? = null
        var capturedPayload: String? = null
        val body = buildString {
            event("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"完成"}}""")
            event("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")
            event("""{"type":"message_stop"}""")
        }
        val engine = MockEngine { requestData ->
            capturedBeta = requestData.headers["anthropic-beta"]
            capturedPayload = (requestData.body as TextContent).text
            respond(
                content = ByteReadChannel(body.toByteArray()),
                status = HttpStatusCode.OK,
                headers = Headers.build { append(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()) },
            )
        }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(HttpTimeout)
        }

        try {
            AnthropicDirectClient.streamChatDirect(
                client,
                request(
                    contextManagement = RequestContextManagement(
                        configId = "config-1",
                        maxContextTokens = 200_000,
                        reservedOutputTokens = 8_192,
                        compactThresholdTokens = 180_000,
                        autoCompressionEnabled = true,
                    ),
                ),
            ).toList()

            assertEquals("compact-2026-01-12", capturedBeta)
            val contextManagement = Json.parseToJsonElement(checkNotNull(capturedPayload))
                .jsonObject.getValue("context_management").jsonObject
            val edit = contextManagement.getValue("edits").jsonArray.single().jsonObject
            assertEquals("compact_20260112", edit.getValue("type").jsonPrimitive.content)
            assertEquals(
                180_000L,
                edit.getValue("trigger").jsonObject.getValue("value").jsonPrimitive.content.toLong(),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun `native compaction emits canonical anthropic messages and active context size`() = runTest {
        val body = buildString {
            event("""{"type":"message_start","message":{"usage":{"input_tokens":23000,"output_tokens":0}}}""")
            event("""{"type":"content_block_start","index":0,"content_block":{"type":"compaction","content":null,"encrypted_content":null}}""")
            event("""{"type":"content_block_delta","index":0,"delta":{"type":"compaction_delta","content":"权威摘要","encrypted_content":"opaque-state"}}""")
            event("""{"type":"content_block_start","index":1,"content_block":{"type":"text","text":"完成"}}""")
            event(
                """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1000,"iterations":[{"type":"compaction","input_tokens":180000,"output_tokens":3500,"cache_read_input_tokens":10000,"cache_creation_input_tokens":2000},{"type":"message","input_tokens":23000,"output_tokens":1000,"cache_read_input_tokens":5000,"cache_creation_input_tokens":0}]}}""",
            )
            event("""{"type":"message_stop"}""")
        }
        val engine = MockEngine {
            respond(
                content = ByteReadChannel(body.toByteArray()),
                status = HttpStatusCode.OK,
                headers = Headers.build { append(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()) },
            )
        }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(HttpTimeout)
        }

        try {
            val events = AnthropicDirectClient.streamChatDirect(
                client,
                request(
                    contextManagement = RequestContextManagement(
                        configId = "config-1",
                        maxContextTokens = 200_000,
                        reservedOutputTokens = 8_192,
                        compactThresholdTokens = 180_000,
                        autoCompressionEnabled = true,
                    ),
                ),
            ).toList()

            val native = events.filterIsInstance<AppStreamEvent.NativeContextCompaction>().single()
            assertEquals(24_000L, native.estimatedTokens)
            val billedUsage = events.filterIsInstance<AppStreamEvent.Usage>().last().usage
            assertEquals(203_000L, billedUsage.inputTokens)
            assertEquals(4_500L, billedUsage.outputTokens)
            assertEquals(15_000L, billedUsage.cachedInputTokens)
            assertEquals(2_000L, billedUsage.cacheWriteTokens)
            assertEquals(207_500L, billedUsage.totalTokens)
            val canonicalMessage = Json.parseToJsonElement(native.inputJson).jsonArray.single().jsonObject
            assertEquals("assistant", canonicalMessage.getValue("role").jsonPrimitive.content)
            val content = canonicalMessage.getValue("content").jsonArray
            assertEquals("compaction", content.first().jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals("opaque-state", content.first().jsonObject.getValue("encrypted_content").jsonPrimitive.content)
            assertEquals("完成", content.last().jsonObject.getValue("text").jsonPrimitive.content)
        } finally {
            client.close()
        }
    }

    @Test
    fun `payload restores anthropic compaction window and appends only uncovered messages`() {
        val canonical = """[{"role":"assistant","content":[{"type":"compaction","content":"权威摘要","encrypted_content":"opaque-state"}]}]"""
        val restoredState = ContextCompressionState(
            configId = "config-1",
            provider = "Anthropic",
            channel = "Anthropic",
            model = "claude-sonnet-4-5",
            windowId = "window-1",
            anthropicMessagesJson = canonical,
            anthropicThroughMessageId = "assistant-old",
            anthropicEstimatedTokens = 24_000,
        )
        val payload = Json.parseToJsonElement(
            AnthropicDirectClient.buildAnthropicPayload(
                request(
                    messages = listOf(
                        SimpleTextApiMessage(id = "user-old", role = "user", content = "旧问题"),
                        SimpleTextApiMessage(id = "assistant-old", role = "assistant", content = "旧回答"),
                        SimpleTextApiMessage(id = "user-new", role = "user", content = "新问题"),
                    ),
                    contextManagement = RequestContextManagement(
                        configId = "config-1",
                        maxContextTokens = 200_000,
                        reservedOutputTokens = 8_192,
                        compactThresholdTokens = 180_000,
                        autoCompressionEnabled = true,
                        restoredState = restoredState,
                    ),
                ),
            ),
        ).jsonObject

        val messages = payload.getValue("messages").jsonArray
        assertEquals(2, messages.size)
        assertEquals(
            "compaction",
            messages.first().jsonObject.getValue("content").jsonArray
                .first().jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals(
            "新问题",
            messages.last().jsonObject.getValue("content").jsonArray
                .single().jsonObject.getValue("text").jsonPrimitive.content,
        )
    }

    @Test
    fun `unsupported native compaction retries same turn without beta fields`() = runTest {
        val payloads = mutableListOf<String>()
        val betaHeaders = mutableListOf<String?>()
        var requestCount = 0
        val successBody = buildString {
            event("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"降级成功"}}""")
            event("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")
            event("""{"type":"message_stop"}""")
        }
        val engine = MockEngine { requestData ->
            requestCount++
            payloads += (requestData.body as TextContent).text
            betaHeaders += requestData.headers["anthropic-beta"]
            if (requestCount == 1) {
                respond(
                    content = ByteReadChannel(
                        """{"type":"error","error":{"type":"invalid_request_error","message":"context_management: Extra inputs are not permitted"}}"""
                            .toByteArray(),
                    ),
                    status = HttpStatusCode.BadRequest,
                    headers = Headers.build { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) },
                )
            } else {
                respond(
                    content = ByteReadChannel(successBody.toByteArray()),
                    status = HttpStatusCode.OK,
                    headers = Headers.build { append(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()) },
                )
            }
        }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(HttpTimeout)
        }

        try {
            val events = AnthropicDirectClient.streamChatDirect(
                client,
                request(
                    model = "claude-fallback-test",
                    contextManagement = RequestContextManagement(
                        configId = "config-1",
                        maxContextTokens = 200_000,
                        reservedOutputTokens = 8_192,
                        compactThresholdTokens = 180_000,
                        autoCompressionEnabled = true,
                    ),
                ),
            ).toList()

            assertEquals(2, requestCount)
            assertEquals("compact-2026-01-12", betaHeaders.first())
            assertNull(betaHeaders.last())
            assertTrue(Json.parseToJsonElement(payloads.first()).jsonObject.containsKey("context_management"))
            assertFalse(Json.parseToJsonElement(payloads.last()).jsonObject.containsKey("context_management"))
            assertTrue(events.none { it is AppStreamEvent.Error })
            assertEquals("降级成功", events.filterIsInstance<AppStreamEvent.ContentFinal>().single().text)
        } finally {
            client.close()
        }
    }

    @Test
    fun `restored compaction survives unsupported trigger retry then resets native state`() = runTest {
        val payloads = mutableListOf<String>()
        val betaHeaders = mutableListOf<String?>()
        var requestCount = 0
        val canonical = """[{"role":"assistant","content":[{"type":"compaction","content":"权威摘要","encrypted_content":"opaque-state"}]}]"""
        val successBody = buildString {
            event("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"安全降级"}}""")
            event("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")
            event("""{"type":"message_stop"}""")
        }
        val engine = MockEngine { requestData ->
            requestCount++
            payloads += (requestData.body as TextContent).text
            betaHeaders += requestData.headers["anthropic-beta"]
            if (requestCount == 1) {
                respond(
                    content = ByteReadChannel(
                        """{"type":"error","error":{"type":"invalid_request_error","message":"context_management is unsupported"}}"""
                            .toByteArray(),
                    ),
                    status = HttpStatusCode.UnprocessableEntity,
                    headers = Headers.build { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) },
                )
            } else {
                respond(
                    content = ByteReadChannel(successBody.toByteArray()),
                    status = HttpStatusCode.OK,
                    headers = Headers.build { append(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()) },
                )
            }
        }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(HttpTimeout)
        }
        val restoredState = ContextCompressionState(
            configId = "config-1",
            provider = "Anthropic",
            channel = "Anthropic",
            model = "claude-restored-fallback-test",
            windowId = "window-1",
            anthropicMessagesJson = canonical,
            anthropicThroughMessageId = "assistant-old",
            anthropicEstimatedTokens = 24_000,
        )
        val chatRequest = request(
            model = "claude-restored-fallback-test",
            messages = listOf(
                SimpleTextApiMessage(id = "assistant-old", role = "assistant", content = "旧回答"),
                SimpleTextApiMessage(id = "user-new", role = "user", content = "新问题"),
            ),
            contextManagement = RequestContextManagement(
                configId = "config-1",
                maxContextTokens = 200_000,
                reservedOutputTokens = 8_192,
                compactThresholdTokens = 180_000,
                autoCompressionEnabled = true,
                restoredState = restoredState,
            ),
        )

        try {
            val events = AnthropicDirectClient.streamChatDirect(
                client,
                chatRequest,
            ).toList()

            assertEquals(2, requestCount)
            assertEquals(listOf("compact-2026-01-12", "compact-2026-01-12"), betaHeaders)
            assertTrue(Json.parseToJsonElement(payloads.first()).jsonObject.containsKey("context_management"))
            val retryPayload = Json.parseToJsonElement(payloads.last()).jsonObject
            assertFalse(retryPayload.containsKey("context_management"))
            assertEquals(
                "compaction",
                retryPayload.getValue("messages").jsonArray.first().jsonObject
                    .getValue("content").jsonArray.first().jsonObject
                    .getValue("type").jsonPrimitive.content,
            )
            val reset = events.filterIsInstance<AppStreamEvent.NativeContextCompaction>().single()
            assertTrue(reset.reset)
            assertEquals(NativeContextCompactionKind.ANTHROPIC_MESSAGES, reset.kind)
            assertEquals("安全降级", events.filterIsInstance<AppStreamEvent.ContentFinal>().single().text)

            val cachedFallbackEvents = AnthropicDirectClient.streamChatDirect(client, chatRequest).toList()
            assertEquals(3, requestCount)
            assertEquals("compact-2026-01-12", betaHeaders.last())
            assertFalse(Json.parseToJsonElement(payloads.last()).jsonObject.containsKey("context_management"))
            assertTrue(cachedFallbackEvents.filterIsInstance<AppStreamEvent.NativeContextCompaction>().single().reset)
        } finally {
            client.close()
        }
    }

    @Test
    fun `custom anthropic address omits native compaction beta fields`() = runTest {
        var capturedBeta: String? = null
        var capturedPayload: String? = null
        val body = buildString {
            event("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"完成"}}""")
            event("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")
            event("""{"type":"message_stop"}""")
        }
        val engine = MockEngine { requestData ->
            capturedBeta = requestData.headers["anthropic-beta"]
            capturedPayload = (requestData.body as TextContent).text
            respond(
                content = ByteReadChannel(body.toByteArray()),
                status = HttpStatusCode.OK,
                headers = Headers.build { append(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()) },
            )
        }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(HttpTimeout)
        }

        try {
            AnthropicDirectClient.streamChatDirect(
                client,
                request(
                    apiAddress = "https://proxy.example/v1/messages",
                    contextManagement = RequestContextManagement(
                        configId = "config-1",
                        maxContextTokens = 200_000,
                        reservedOutputTokens = 8_192,
                        compactThresholdTokens = 180_000,
                        autoCompressionEnabled = true,
                    ),
                ),
            ).toList()

            assertNull(capturedBeta)
            assertFalse(Json.parseToJsonElement(checkNotNull(capturedPayload)).jsonObject.containsKey("context_management"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `endpoint resolver accepts root v1 full path and direct marker`() {
        assertEquals("https://api.anthropic.com/v1/messages", AnthropicDirectClient.resolveMessagesUrl("https://api.anthropic.com"))
        assertEquals("https://proxy.example/v1/messages", AnthropicDirectClient.resolveMessagesUrl("https://proxy.example/v1"))
        assertEquals("https://proxy.example/custom", AnthropicDirectClient.resolveMessagesUrl("https://proxy.example/custom#"))
        assertEquals("https://proxy.example/v1/models", AnthropicDirectClient.resolveModelsUrl("https://proxy.example/v1/messages"))
    }

    private fun request(
        messages: List<com.android.everytalk.data.DataClass.AbstractApiMessage> = listOf(
            SimpleTextApiMessage(role = "user", content = "hello"),
        ),
        generationConfig: GenerationConfig? = null,
        tools: List<Map<String, Any>>? = null,
        contextManagement: RequestContextManagement? = null,
        model: String = "claude-sonnet-4-5",
        apiAddress: String = "https://api.anthropic.com",
    ) = ChatRequest(
        messages = messages,
        provider = "Anthropic",
        channel = "Anthropic",
        apiAddress = apiAddress,
        apiKey = "test-key",
        model = model,
        generationConfig = generationConfig,
        tools = tools,
        contextManagement = contextManagement,
    )

    private fun StringBuilder.event(json: String) {
        append("data: ")
        append(json)
        append("\n\n")
    }
}
