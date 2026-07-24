package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.PartsApiMessage
import kotlinx.serialization.json.JsonElement
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

        assertTrue(official.contains("\"prompt_cache_key\":\"et-v1-"))
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
    ): ChatRequest = ChatRequest(
        messages = listOf(PartsApiMessage(role = "user", parts = listOf(ApiContentPart.Text("hello")))),
        provider = "OpenAI",
        channel = "codex",
        apiAddress = apiAddress,
        apiKey = "test-key",
        model = model,
        tools = tools,
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
