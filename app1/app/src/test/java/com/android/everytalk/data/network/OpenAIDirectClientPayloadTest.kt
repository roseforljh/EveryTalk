package com.android.everytalk.data.network

import android.app.Application
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
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

        assertTrue(payload.contains("\"prompt_cache_key\":\"et-v1-"))
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
    fun `tool order should not change payload bytes`() {
        val alpha = tool("alpha")
        val beta = tool("beta")
        val first = buildPayload(request(tools = listOf(beta, alpha)))
        val second = buildPayload(request(tools = listOf(alpha, beta)))

        assertEquals(first, second)
    }

    private fun buildPayload(request: ChatRequest): String {
        val method = OpenAIDirectClient::class.java.getDeclaredMethod(
            "buildOpenAIPayload",
            ChatRequest::class.java,
        )
        method.isAccessible = true
        return method.invoke(OpenAIDirectClient, request) as String
    }

    private fun request(
        apiAddress: String = "https://api.openai.com",
        model: String = "gpt-5.4",
        tools: List<Map<String, Any>>? = null,
    ): ChatRequest = ChatRequest(
        messages = listOf(SimpleTextApiMessage(role = "user", content = "hello")),
        provider = "OpenAI",
        channel = "OpenAI",
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
