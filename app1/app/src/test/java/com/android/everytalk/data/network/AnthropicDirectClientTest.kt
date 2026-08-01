package com.android.everytalk.data.network

import android.app.Application
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.GenerationConfig
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.ThinkingConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun `thinking payload uses budget and omits sampling controls`() {
        val payload = Json.parseToJsonElement(
            AnthropicDirectClient.buildAnthropicPayload(
                request(
                    generationConfig = GenerationConfig(
                        temperature = 0.2f,
                        topP = 0.8f,
                        maxOutputTokens = 4096,
                        thinkingConfig = ThinkingConfig(includeThoughts = true, thinkingBudget = 2048),
                    ),
                ),
            ),
        ).jsonObject

        assertEquals(2048, payload.getValue("thinking").jsonObject.getValue("budget_tokens").jsonPrimitive.content.toInt())
        assertFalse(payload.containsKey("temperature"))
        assertFalse(payload.containsKey("top_p"))
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
    ) = ChatRequest(
        messages = messages,
        provider = "Anthropic",
        channel = "Anthropic",
        apiAddress = "https://api.anthropic.com",
        apiKey = "test-key",
        model = "claude-sonnet-4-5",
        generationConfig = generationConfig,
        tools = tools,
    )

    private fun StringBuilder.event(json: String) {
        append("data: ")
        append(json)
        append("\n\n")
    }
}
