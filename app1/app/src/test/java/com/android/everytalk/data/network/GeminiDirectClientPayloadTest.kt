package com.android.everytalk.data.network

import android.app.Application
import com.android.everytalk.data.DataClass.AgentAssistantApiMessage
import com.android.everytalk.data.DataClass.AgentToolCallApiPart
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import com.android.everytalk.data.DataClass.ProviderTurnContinuation
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GeminiDirectClientPayloadTest {
    @Test
    fun `next tool turn restores Gemini thought signature`() {
        val request = ChatRequest(
            messages = listOf(
                SimpleTextApiMessage(role = "user", content = "检查服务"),
                AgentAssistantApiMessage(
                    reasoning = "分析",
                    toolCalls = listOf(
                        AgentToolCallApiPart("call-1", "exec", JsonObject(emptyMap())),
                    ),
                ),
            ),
            provider = "Google",
            channel = "Gemini",
            apiAddress = "https://generativelanguage.googleapis.com",
            apiKey = "test-key",
            model = "gemini-test",
            localProviderContinuation = ProviderTurnContinuation(
                protocol = ModelParameterProtocol.GEMINI,
                payloadJson = """{"role":"model","parts":[{"thought":true,"text":"分析","thoughtSignature":"sig"},{"functionCall":{"name":"exec","args":{}}}]}""",
            ),
        )

        val payload = Json.parseToJsonElement(GeminiDirectClient.buildGeminiPayload(request)).jsonObject
        val modelContent = payload.getValue("contents").jsonArray.last().jsonObject
        val thought = modelContent.getValue("parts").jsonArray.first().jsonObject

        assertEquals("sig", thought.getValue("thoughtSignature").jsonPrimitive.content)
    }
}
