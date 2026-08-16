package com.android.everytalk.data.network

import android.app.Application
import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.DataClass.AgentAssistantApiMessage
import com.android.everytalk.data.DataClass.AgentToolCallApiPart
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.computer.ComputerToolCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OpenAIDirectClientPayloadTest {

    @Test
    fun `official recent model should include cache key and usage`() {
        val payload = buildPayload(request(apiAddress = "https://api.openai.com", model = "gpt-5.6"))

        assertTrue(payload.contains("\"prompt_cache_key\":\"et-skill-v"))
        assertTrue(payload.contains("\"stream_options\":{\"include_usage\":true}"))
        assertFalse(payload.contains("prompt_cache_options"))
        assertFalse(payload.contains("prompt_cache_breakpoint"))
    }

    @Test
    fun `compatible endpoint should not receive OpenAI cache fields`() {
        val payload = buildPayload(request(apiAddress = "https://example.com/v1", model = "gpt-5.4"))

        assertFalse(payload.contains("prompt_cache_key"))
        assertFalse(payload.contains("stream_options"))
    }

    @Test
    fun `官方和兼容接口使用各自的最大输出字段`() {
        val generationConfig = GenerationConfig(maxOutputTokens = 8192)
        val official = buildPayload(
            request(apiAddress = "https://api.openai.com/v1", generationConfig = generationConfig)
        )
        val compatible = buildPayload(
            request(apiAddress = "https://example.com/v1", generationConfig = generationConfig)
        )

        assertTrue(official.contains("\"max_completion_tokens\":8192"))
        assertFalse(official.contains("\"max_tokens\""))
        assertTrue(compatible.contains("\"max_tokens\":8192"))
        assertFalse(compatible.contains("\"max_completion_tokens\""))
    }

    @Test
    fun `tool order should not change payload bytes`() {
        val alpha = tool("alpha")
        val beta = tool("beta")
        val first = buildPayload(request(tools = listOf(beta, alpha)))
        val second = buildPayload(request(tools = listOf(alpha, beta)))

        assertEquals(first, second)
    }

    @Test
    fun `Chat Completions保留Computer工具可选参数且不强开严格模式`() {
        val payload = Json.parseToJsonElement(
            buildPayload(request(tools = ComputerToolCatalog.definitions())),
        ).jsonObject
        val download = payload.getValue("tools").jsonArray
            .map { it.jsonObject }
            .first {
                it.getValue("function").jsonObject.getValue("name").jsonPrimitive.content == "download"
            }
        val function = download.getValue("function").jsonObject
        val schema = function.getValue("parameters").jsonObject

        assertTrue(schema.getValue("properties").jsonObject.containsKey("suggested_name"))
        assertFalse(schema.getValue("required").jsonArray.any { it.jsonPrimitive.content == "suggested_name" })
        assertFalse(function.containsKey("strict"))
    }

    @Test
    fun `chat payload keeps user history unchanged and exposes skill protocol`() {
        val messages = listOf(
            SimpleTextApiMessage(id = "u1", role = "user", content = "第一轮财报分析"),
            SimpleTextApiMessage(id = "a1", role = "assistant", content = "第一轮回答"),
            SimpleTextApiMessage(id = "u2", role = "user", content = "继续"),
        )
        val payload = buildPayload(request(messages = messages))

        assertTrue(payload.contains("第一轮财报分析"))
        assertTrue(payload.contains("第一轮回答"))
        assertTrue(payload.contains("load_skill"))
        assertFalse(payload.contains("ETD v="))
    }

    @Test
    fun `compatible payload merges typed custom model parameters`() {
        val payload = buildPayload(
            request(
                customModelParameters = mapOf(
                    "reasoning_effort" to JsonPrimitive("medium"),
                    "thinking_budget" to JsonPrimitive(4096),
                    "enable_thinking" to JsonPrimitive(true),
                )
            )
        )

        assertTrue(payload.contains("\"reasoning_effort\":\"medium\""))
        assertTrue(payload.contains("\"thinking_budget\":4096"))
        assertTrue(payload.contains("\"enable_thinking\":true"))
    }

    @Test
    fun `compatible agent history preserves reasoning content for next tool turn`() {
        val payload = buildPayload(
            request(
                apiAddress = "https://api.deepseek.com/v1",
                model = "deepseek-reasoner",
                messages = listOf(
                    SimpleTextApiMessage(role = "user", content = "检查服务"),
                    AgentAssistantApiMessage(
                        reasoning = "需要先读取状态",
                        toolCalls = listOf(
                            AgentToolCallApiPart("call-1", "exec", JsonObject(emptyMap())),
                        ),
                    ),
                ),
            )
        )

        assertTrue(payload.contains("\"reasoning_content\":\"需要先读取状态\""))
    }

    @Test
    fun `official Chat Completions不发送第三方reasoning content字段`() {
        val payload = buildPayload(
            request(
                apiAddress = "https://api.openai.com/v1",
                model = "gpt-5.6",
                messages = listOf(
                    SimpleTextApiMessage(role = "user", content = "检查服务"),
                    AgentAssistantApiMessage(
                        reasoning = "需要先读取状态",
                        toolCalls = listOf(
                            AgentToolCallApiPart("call-1", "exec", JsonObject(emptyMap())),
                        ),
                    ),
                ),
            )
        )

        assertFalse(payload.contains("reasoning_content"))
        assertTrue(payload.contains("\"tool_calls\""))
    }

    private fun buildPayload(request: ChatRequest): String {
        return OpenAIDirectClient.buildOpenAIPayload(request)
    }

    private fun request(
        apiAddress: String = "https://api.openai.com",
        model: String = "gpt-5.4",
        tools: List<Map<String, Any>>? = null,
        messages: List<AbstractApiMessage> = listOf(SimpleTextApiMessage(role = "user", content = "hello")),
        customModelParameters: Map<String, Any>? = null,
        generationConfig: GenerationConfig? = null,
    ): ChatRequest = ChatRequest(
        messages = messages,
        provider = "OpenAI",
        channel = "OpenAI",
        apiAddress = apiAddress,
        apiKey = "test-key",
        model = model,
        tools = tools,
        customModelParameters = customModelParameters,
        generationConfig = generationConfig,
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
