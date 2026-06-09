package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.PartsApiMessage
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertFalse
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

    private fun buildResponsesPayloadForTest(request: ChatRequest): String {
        val method = OpenAIResponsesClient::class.java.getDeclaredMethod(
            "buildResponsesPayload",
            ChatRequest::class.java,
            List::class.java
        )
        method.isAccessible = true
        return method.invoke(OpenAIResponsesClient, request, emptyList<JsonElement>()) as String
    }
}
