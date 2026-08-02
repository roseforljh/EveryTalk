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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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

        assertTrue(official.contains("\"prompt_cache_key\":\"et-cap-v1-"))
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
    fun `responses payload keeps user history unchanged and exposes capability protocol`() {
        val messages = listOf(
            SimpleTextApiMessage(id = "u1", role = "user", content = "第一轮财报分析"),
            SimpleTextApiMessage(id = "a1", role = "assistant", content = "第一轮回答"),
            SimpleTextApiMessage(id = "u2", role = "user", content = "继续"),
        )
        val payload = buildResponsesPayloadForTest(request(messages = messages))

        assertTrue(payload.contains("第一轮财报分析"))
        assertTrue(payload.contains("第一轮回答"))
        assertTrue(payload.contains("everytalk_select_capabilities"))
        assertFalse(payload.contains("ETD v="))
    }

    @Test
    fun `responses reasoning effort comes from model parameters and defaults to medium`() {
        val defaultPayload = buildResponsesPayloadForTest(request())
        val highPayload = buildResponsesPayloadForTest(
            request(
                generationConfig = GenerationConfig(
                    thinkingConfig = ThinkingConfig(reasoningEffort = "high"),
                )
            )
        )

        assertTrue(defaultPayload.contains("\"effort\":\"medium\""))
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

    private fun buildResponsesPayloadForTest(request: ChatRequest): String {
        val method = OpenAIResponsesClient::class.java.getDeclaredMethod(
            "buildResponsesPayload",
            ChatRequest::class.java,
            List::class.java
        )
        method.isAccessible = true
        return method.invoke(OpenAIResponsesClient, request, emptyList<JsonElement>()) as String
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
