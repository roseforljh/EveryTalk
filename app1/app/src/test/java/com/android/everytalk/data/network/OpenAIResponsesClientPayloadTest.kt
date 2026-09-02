package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.DataClass.ThinkingConfig
import com.android.everytalk.data.DataClass.ContextCompressionState
import com.android.everytalk.data.DataClass.RequestContextManagement
import com.android.everytalk.data.DataClass.AgentAssistantApiMessage
import com.android.everytalk.data.DataClass.AgentToolCallApiPart
import com.android.everytalk.data.DataClass.AgentToolResultApiMessage
import com.android.everytalk.data.DataClass.ProviderTurnContinuation
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import com.android.everytalk.data.computer.ComputerToolCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIResponsesClientPayloadTest {
    @Test
    fun `responses image input uses string image_url`() {
        val request = ChatRequest(
            messages = listOf(
                PartsApiMessage(
                    role = "user",
                    parts = listOf(
                        ApiContentPart.Text("describe it"),
                        ApiContentPart.InlineData(
                            base64Data = "YWJj",
                            mimeType = "image/png"
                        )
                    )
                )
            ),
            provider = "OpenAI",
            channel = "OpenAI",
            apiAddress = "https://api.openai.com",
            apiKey = "test-key",
            model = "gpt-5.4"
        )

        val payload = buildResponsesPayloadForTest(request)

        assertTrue(payload.contains("\"type\":\"input_image\""))
        assertTrue(payload.contains("\"image_url\":\"data:image/png;base64,YWJj\""))
        assertFalse(payload.contains("\"image_url\":{\"url\""))
    }

    @Test
    fun `responses cache fields should be official endpoint only`() {
        val official = buildResponsesPayloadForTest(request("https://api.openai.com"))
        val compatible = buildResponsesPayloadForTest(request("https://example.com/v1"))

        assertTrue(official.contains("\"prompt_cache_key\":\"et-skill-v"))
        assertFalse(compatible.contains("prompt_cache_key"))
        assertFalse(official.contains("prompt_cache_options"))
        assertFalse(official.contains("prompt_cache_breakpoint"))
    }

    @Test
    fun `responses tool order should be deterministic`() {
        val alpha = tool("alpha")
        val beta = tool("beta")
        val first = buildResponsesPayloadForTest(request(tools = listOf(beta, alpha)))
        val second = buildResponsesPayloadForTest(request(tools = listOf(alpha, beta)))

        assertEquals(first, second)
    }

    @Test
    fun `responses不会对含可选参数的Computer工具强制严格模式`() {
        val payload = Json.parseToJsonElement(
            buildResponsesPayloadForTest(request(tools = ComputerToolCatalog.definitions()))
        ).jsonObject
        val download = payload.getValue("tools").jsonArray
            .map(JsonElement::jsonObject)
            .first { it.getValue("name").jsonPrimitive.content == "download" }
        val parameters = download.getValue("parameters").jsonObject

        assertFalse(download.getValue("strict").jsonPrimitive.content.toBoolean())
        assertTrue(parameters.getValue("properties").jsonObject.containsKey("suggested_name"))
        assertFalse(
            parameters.getValue("required").jsonArray
                .any { it.jsonPrimitive.content == "suggested_name" }
        )
    }

    @Test
    fun `responses payload keeps user history unchanged and exposes skill protocol`() {
        val messages = listOf(
            SimpleTextApiMessage(id = "u1", role = "user", content = "第一轮财报分析"),
            SimpleTextApiMessage(id = "a1", role = "assistant", content = "第一轮回答"),
            SimpleTextApiMessage(id = "u2", role = "user", content = "继续"),
        )
        val payload = buildResponsesPayloadForTest(request(messages = messages))

        assertTrue(payload.contains("第一轮财报分析"))
        assertTrue(payload.contains("第一轮回答"))
        assertTrue(payload.contains("load_skill"))
        assertFalse(payload.contains("ETD v="))
    }

    @Test
    fun `responses只在模型配置明确启用时发送reasoning`() {
        val noReasoningPayload = buildResponsesPayloadForTest(request())
        val highPayload = buildResponsesPayloadForTest(
            request(
                generationConfig = GenerationConfig(
                    thinkingConfig = ThinkingConfig(reasoningEffort = "high"),
                )
            )
        )

        assertFalse(noReasoningPayload.contains("\"reasoning\""))
        assertTrue(highPayload.contains("\"effort\":\"high\""))
    }

    @Test
    fun `responses使用官方最大输出字段`() {
        val payload = buildResponsesPayloadForTest(
            request(generationConfig = GenerationConfig(maxOutputTokens = 8192))
        )

        assertTrue(payload.contains("\"max_output_tokens\":8192"))
        assertFalse(payload.contains("\"max_completion_tokens\""))
    }

    @Test
    fun `官方Responses开启服务端压缩并关闭存储`() {
        val payload = Json.parseToJsonElement(
            buildResponsesPayloadForTest(
                request(contextManagement = contextManagement())
            )
        ).jsonObject

        assertFalse(payload.getValue("store").jsonPrimitive.content.toBoolean())
        val compaction = payload.getValue("context_management").jsonArray.single().jsonObject
        assertEquals("compaction", compaction.getValue("type").jsonPrimitive.content)
        assertEquals(90_000L, compaction.getValue("compact_threshold").jsonPrimitive.content.toLong())
    }

    @Test
    fun `兼容地址隔离OpenAI原生压缩字段`() {
        val payload = buildResponsesPayloadForTest(
            request(
                apiAddress = "https://compatible.example/v1",
                contextManagement = contextManagement(),
            )
        )

        assertFalse(payload.contains("context_management"))
        assertFalse(payload.contains("\"store\":false"))
    }

    @Test
    fun `恢复的compaction权威输入原样放在新消息之前`() {
        val authoritativeInput =
            "[{\"id\":\"cmp-1\",\"type\":\"compaction\",\"encrypted_content\":\"opaque-state\"}]"
        val state = ContextCompressionState(
            configId = "config-1",
            provider = "OpenAI",
            channel = "codex",
            model = "gpt-5.6",
            windowId = "window-1",
            openAiResponsesInputJson = authoritativeInput,
            openAiResponsesThroughMessageId = "assistant-old",
            openAiResponsesEstimatedTokens = 100,
        )
        val payload = Json.parseToJsonElement(
            buildResponsesPayloadForTest(
                request(contextManagement = contextManagement(state))
            )
        ).jsonObject
        val input = payload.getValue("input").jsonArray

        assertEquals("compaction", input[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("opaque-state", input[0].jsonObject.getValue("encrypted_content").jsonPrimitive.content)
        assertEquals("user", input[1].jsonObject.getValue("role").jsonPrimitive.content)
    }

    @Test
    fun `responses next tool turn restores native output items once`() {
        val continuation = """[{"id":"rs-1","type":"reasoning","encrypted_content":"opaque"},{"type":"function_call","call_id":"call-1","name":"exec","arguments":"{}"}]"""
        val payload = Json.parseToJsonElement(
            buildResponsesPayloadForTest(
                request(
                    messages = listOf(
                        SimpleTextApiMessage(role = "user", content = "检查服务"),
                        AgentAssistantApiMessage(
                            reasoning = "分析",
                            toolCalls = listOf(
                                AgentToolCallApiPart("call-1", "exec", JsonObject(emptyMap())),
                            ),
                        ),
                        com.android.everytalk.data.DataClass.AgentToolResultApiMessage(
                            toolCallId = "call-1",
                            toolName = "exec",
                            content = kotlinx.serialization.json.JsonPrimitive("结果"),
                        ),
                    ),
                ).copy(
                    localProviderContinuation = ProviderTurnContinuation(
                        protocol = ModelParameterProtocol.CODEX,
                        payloadJson = continuation,
                    )
                )
            )
        ).jsonObject
        val input = payload.getValue("input").jsonArray

        assertEquals(1, input.count { it.jsonObject["id"]?.jsonPrimitive?.content == "rs-1" })
        assertEquals(
            1,
            input.count {
                it.jsonObject["type"]?.jsonPrimitive?.content == "function_call" &&
                    it.jsonObject["call_id"]?.jsonPrimitive?.content == "call-1"
            },
        )
    }

    @Test
    fun `responses纯文本下一轮原样回放多个原生output item`() {
        val assistant = AgentAssistantApiMessage(
            id = "assistant-text",
            text = "前半后半",
            sourceProvider = "OpenAI",
            sourceEndpoint = "https://api.openai.com",
            sourceModel = "gpt-5.6",
            sourceProtocol = ModelParameterProtocol.CODEX,
        )
        val payload = Json.parseToJsonElement(
            buildResponsesPayloadForTest(
                request(
                    messages = listOf(
                        assistant,
                        SimpleTextApiMessage(role = "user", content = "继续"),
                    ),
                ).copy(
                    localProviderContinuation = ProviderTurnContinuation(
                        protocol = ModelParameterProtocol.CODEX,
                        assistantMessageId = assistant.id,
                        payloadJson = """[
                            {"id":"rs-1","type":"reasoning","encrypted_content":"opaque","summary":[]},
                            {"id":"msg-commentary","type":"message","role":"assistant","phase":"commentary","status":"completed","content":[{"type":"output_text","text":"前半","annotations":[]}]},
                            {"id":"msg-final","type":"message","role":"assistant","phase":"final_answer","status":"completed","content":[{"type":"output_text","text":"后半","annotations":[]}]}
                        ]""".trimIndent(),
                    ),
                ),
            ),
        ).jsonObject
        val input = payload.getValue("input").jsonArray

        assertEquals(1, input.count { it.jsonObject["id"]?.jsonPrimitive?.content == "rs-1" })
        assertEquals(1, input.count { it.jsonObject["id"]?.jsonPrimitive?.content == "msg-commentary" })
        assertEquals(1, input.count { it.jsonObject["id"]?.jsonPrimitive?.content == "msg-final" })
        assertFalse(input.any { it.jsonObject["id"]?.jsonPrimitive?.content?.startsWith("msg_pi_") == true })
    }

    @Test
    fun `中断工具调用后按Pi规则补失败output并保留配对call`() {
        val payload = Json.parseToJsonElement(
            buildResponsesPayloadForTest(
                request(
                    messages = listOf(
                        SimpleTextApiMessage(role = "user", content = "检查服务"),
                        AgentAssistantApiMessage(
                            id = "assistant:turn-1",
                            toolCalls = listOf(
                                AgentToolCallApiPart("call-interrupted", "exec", JsonObject(emptyMap())),
                            ),
                        ),
                        SimpleTextApiMessage(role = "user", content = "中断后继续"),
                    ),
                ).copy(
                    localProviderContinuation = ProviderTurnContinuation(
                        protocol = ModelParameterProtocol.CODEX,
                        payloadJson =
                            """[{"id":"rs-interrupted","type":"reasoning","encrypted_content":"opaque"},{"type":"function_call","call_id":"call-interrupted","name":"exec","arguments":"{}"}]""",
                    ),
                ),
            ),
        ).jsonObject
        val input = payload.getValue("input").jsonArray

        assertTrue(input.any {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call" &&
                it.jsonObject["call_id"]?.jsonPrimitive?.content == "call-interrupted"
        })
        assertTrue(input.any {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" &&
                it.jsonObject["call_id"]?.jsonPrimitive?.content == "call-interrupted" &&
                it.jsonObject["output"]?.jsonPrimitive?.content == "No result provided"
        })
        assertTrue(input.any { it.jsonObject["id"]?.jsonPrimitive?.content == "rs-interrupted" })
        assertTrue(input.any { item ->
            (item.jsonObject["content"] as? kotlinx.serialization.json.JsonArray).orEmpty().any { part ->
                part.jsonObject["text"]?.jsonPrimitive?.contentOrNull == "中断后继续"
            }
        })
    }

    @Test
    fun `旧Responses continuation不会覆盖当前工具回合`() {
        val payload = Json.parseToJsonElement(
            buildResponsesPayloadForTest(
                request(
                    messages = listOf(
                        AgentAssistantApiMessage(
                            id = "assistant-old",
                            toolCalls = listOf(AgentToolCallApiPart("call-old", "read_file", JsonObject(emptyMap()))),
                        ),
                        AgentToolResultApiMessage(
                            toolCallId = "call-old",
                            toolName = "read_file",
                            content = JsonPrimitive("old"),
                        ),
                        AgentAssistantApiMessage(
                            id = "assistant-current",
                            toolCalls = listOf(AgentToolCallApiPart("call-current", "exec", JsonObject(emptyMap()))),
                        ),
                        AgentToolResultApiMessage(
                            toolCallId = "call-current",
                            toolName = "exec",
                            content = JsonPrimitive("current"),
                        ),
                    ),
                ).copy(
                    localProviderContinuation = ProviderTurnContinuation(
                        protocol = ModelParameterProtocol.CODEX,
                        payloadJson =
                            """[{"type":"function_call","call_id":"call-old","name":"read_file","arguments":"{}"}]""",
                    ),
                ),
            ),
        ).jsonObject
        val input = payload.getValue("input").jsonArray

        assertEquals(1, input.count {
            it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "function_call" &&
                it.jsonObject["call_id"]?.jsonPrimitive?.contentOrNull == "call-old"
        })
        assertEquals(1, input.count {
            it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "function_call" &&
                it.jsonObject["call_id"]?.jsonPrimitive?.contentOrNull == "call-current"
        })
        assertEquals(1, input.count {
            it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "function_call_output" &&
                it.jsonObject["call_id"]?.jsonPrimitive?.contentOrNull == "call-current"
        })
    }

    @Test
    fun `responses无原生continuation时保留中立reasoning`() {
        val payload = Json.parseToJsonElement(
            buildResponsesPayloadForTest(
                request(
                    messages = listOf(
                        SimpleTextApiMessage(role = "user", content = "检查服务"),
                        AgentAssistantApiMessage(
                            reasoning = "分析",
                            toolCalls = listOf(AgentToolCallApiPart("call-1", "exec", JsonObject(emptyMap()))),
                        ),
                    ),
                ),
            ),
        ).jsonObject

        val input = payload.getValue("input").jsonArray
        assertEquals(1, input.count { item ->
            (item.jsonObject["content"] as? kotlinx.serialization.json.JsonArray).orEmpty().any { part ->
                part.jsonObject["text"]?.jsonPrimitive?.contentOrNull == "分析"
            }
        })
    }

    @Test
    fun `responses原生压缩后的下一轮不重复发送已覆盖历史`() {
        val compacted = """[{"id":"cmp-1","type":"compaction","encrypted_content":"opaque"},{"type":"function_call","call_id":"call-1","name":"exec","arguments":"{}"}]"""
        val request = request(
            messages = listOf(
                SimpleTextApiMessage(role = "user", content = "检查服务"),
                AgentAssistantApiMessage(
                    id = "assistant:turn-1",
                    toolCalls = listOf(AgentToolCallApiPart("call-1", "exec", JsonObject(emptyMap()))),
                ),
                com.android.everytalk.data.DataClass.AgentToolResultApiMessage(
                    toolCallId = "call-1",
                    toolName = "exec",
                    content = kotlinx.serialization.json.JsonPrimitive("结果"),
                ),
            ),
        ).copy(
            localProviderContinuation = ProviderTurnContinuation(
                protocol = ModelParameterProtocol.CODEX,
                payloadJson = """[{"type":"function_call","call_id":"call-1","name":"exec","arguments":"{}"}]""",
                compactedContextJson = compacted,
                compactedThroughMessageId = "assistant:turn-1",
            ),
        )

        val input = Json.parseToJsonElement(buildResponsesPayloadForTest(request))
            .jsonObject.getValue("input").jsonArray

        assertEquals(1, input.count { it.jsonObject["id"]?.jsonPrimitive?.content == "cmp-1" })
        assertEquals(
            1,
            input.count {
                it.jsonObject["type"]?.jsonPrimitive?.content == "function_call" &&
                    it.jsonObject["call_id"]?.jsonPrimitive?.content == "call-1"
            },
        )
        assertEquals(1, input.count { it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" })
        assertEquals(0, input.count { it.jsonObject["content"]?.jsonPrimitive?.contentOrNull == "检查服务" })
    }

    private fun buildResponsesPayloadForTest(request: ChatRequest): String {
        return OpenAIResponsesClient.buildResponsesPayload(request, emptyList())
    }

    private fun request(
        apiAddress: String = "https://api.openai.com",
        tools: List<Map<String, Any>>? = null,
        model: String = "gpt-5.6",
        messages: List<AbstractApiMessage> = listOf(
            PartsApiMessage(role = "user", parts = listOf(ApiContentPart.Text("hello"))),
        ),
        generationConfig: GenerationConfig? = null,
        contextManagement: RequestContextManagement? = null,
    ): ChatRequest = ChatRequest(
        messages = messages,
        provider = "OpenAI",
        channel = "codex",
        apiAddress = apiAddress,
        apiKey = "test-key",
        model = model,
        tools = tools,
        generationConfig = generationConfig,
        contextManagement = contextManagement,
    )

    private fun contextManagement(
        restoredState: ContextCompressionState? = null,
    ): RequestContextManagement = RequestContextManagement(
        configId = "config-1",
        maxContextTokens = 100_000,
        reservedOutputTokens = 10_000,
        compactThresholdTokens = 90_000,
        autoCompressionEnabled = true,
        restoredState = restoredState,
    )

    private fun tool(name: String): Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to name,
            "description" to name,
            "parameters" to mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
        ),
    )
}
