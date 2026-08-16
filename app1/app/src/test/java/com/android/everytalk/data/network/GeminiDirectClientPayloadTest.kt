package com.android.everytalk.data.network

import android.app.Application
import com.android.everytalk.data.DataClass.AgentAssistantApiMessage
import com.android.everytalk.data.DataClass.AgentToolCallApiPart
import com.android.everytalk.data.DataClass.AgentToolResultApiMessage
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import com.android.everytalk.data.DataClass.ProviderTurnContinuation
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.computer.ComputerToolCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GeminiDirectClientPayloadTest {
    @Test
    fun `无原生continuation时保留中立reasoning`() {
        val request = ChatRequest(
            messages = listOf(
                SimpleTextApiMessage(role = "user", content = "检查服务"),
                AgentAssistantApiMessage(
                    reasoning = "分析",
                    toolCalls = listOf(AgentToolCallApiPart("call-1", "exec", JsonObject(emptyMap()))),
                ),
            ),
            provider = "Google", channel = "Gemini", apiAddress = "https://generativelanguage.googleapis.com",
            apiKey = "test-key", model = "gemini-test",
        )

        val payload = Json.parseToJsonElement(GeminiDirectClient.buildGeminiPayload(request)).jsonObject
        val modelParts = payload.getValue("contents").jsonArray.last().jsonObject.getValue("parts").jsonArray

        assertEquals("分析", modelParts.first().jsonObject.getValue("text").jsonPrimitive.content)
    }

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

    @Test
    fun `并行工具结果合并为一条user消息并原样带回调用ID`() {
        val request = ChatRequest(
            messages = listOf(
                SimpleTextApiMessage(role = "user", content = "检查服务"),
                AgentAssistantApiMessage(
                    toolCalls = listOf(
                        AgentToolCallApiPart("call-1", "exec", JsonObject(emptyMap())),
                        AgentToolCallApiPart("call-2", "read_file", JsonObject(emptyMap())),
                    ),
                ),
                AgentToolResultApiMessage(
                    toolCallId = "call-1",
                    toolName = "exec",
                    content = JsonPrimitive("ok"),
                ),
                AgentToolResultApiMessage(
                    toolCallId = "call-2",
                    toolName = "read_file",
                    content = JsonPrimitive("content"),
                ),
            ),
            provider = "Google", channel = "Gemini", apiAddress = "https://generativelanguage.googleapis.com",
            apiKey = "test-key", model = "gemini-test",
        )

        val contents = Json.parseToJsonElement(GeminiDirectClient.buildGeminiPayload(request))
            .jsonObject.getValue("contents").jsonArray
        val modelCalls = contents[1].jsonObject.getValue("parts").jsonArray
            .map { it.jsonObject.getValue("functionCall").jsonObject.getValue("id").jsonPrimitive.content }
        val resultContent = contents.last().jsonObject
        val resultIds = resultContent.getValue("parts").jsonArray
            .map { it.jsonObject.getValue("functionResponse").jsonObject.getValue("id").jsonPrimitive.content }

        assertEquals(listOf("call-1", "call-2"), modelCalls)
        assertEquals("user", resultContent.getValue("role").jsonPrimitive.content)
        assertEquals(listOf("call-1", "call-2"), resultIds)
    }

    @Test
    fun `本地补位调用ID不会冒充Gemini服务端ID回传`() {
        val request = ChatRequest(
            messages = listOf(
                SimpleTextApiMessage(role = "user", content = "检查服务"),
                AgentAssistantApiMessage(
                    toolCalls = listOf(
                        AgentToolCallApiPart("fc_local_test", "exec", JsonObject(emptyMap())),
                    ),
                ),
                AgentToolResultApiMessage(
                    toolCallId = "fc_local_test",
                    toolName = "exec",
                    content = JsonPrimitive("ok"),
                ),
            ),
            provider = "Google", channel = "Gemini", apiAddress = "https://generativelanguage.googleapis.com",
            apiKey = "test-key", model = "gemini-test",
        )

        val contents = Json.parseToJsonElement(GeminiDirectClient.buildGeminiPayload(request))
            .jsonObject.getValue("contents").jsonArray
        val functionCall = contents[1].jsonObject.getValue("parts").jsonArray.single()
            .jsonObject.getValue("functionCall").jsonObject
        val functionResponse = contents.last().jsonObject.getValue("parts").jsonArray.single()
            .jsonObject.getValue("functionResponse").jsonObject

        assertFalse(functionCall.containsKey("id"))
        assertFalse(functionResponse.containsKey("id"))
    }

    @Test
    fun `Computer工具使用完整JSONSchema且保留可选参数`() {
        val request = ChatRequest(
            messages = listOf(SimpleTextApiMessage(role = "user", content = "下载文件")),
            provider = "Google", channel = "Gemini", apiAddress = "https://generativelanguage.googleapis.com",
            apiKey = "test-key", model = "gemini-test",
            tools = ComputerToolCatalog.definitions(),
        )

        val declaration = Json.parseToJsonElement(GeminiDirectClient.buildGeminiPayload(request))
            .jsonObject.getValue("tools").jsonArray.single().jsonObject
            .getValue("functionDeclarations").jsonArray
            .map { it.jsonObject }
            .first { it.getValue("name").jsonPrimitive.content == "download" }
        val schema = declaration.getValue("parametersJsonSchema").jsonObject

        assertTrue(schema.getValue("properties").jsonObject.containsKey("suggested_name"))
        assertFalse(schema.getValue("required").jsonArray.any { it.jsonPrimitive.content == "suggested_name" })
        assertFalse(declaration.containsKey("parameters"))
    }
}
