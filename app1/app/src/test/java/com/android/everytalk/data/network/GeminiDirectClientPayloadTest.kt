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
                AgentToolResultApiMessage(
                    toolCallId = "call-1",
                    toolName = "exec",
                    content = JsonPrimitive("ok"),
                ),
            ),
            provider = "Google",
            channel = "Gemini",
            apiAddress = "https://generativelanguage.googleapis.com",
            apiKey = "test-key",
            model = "gemini-test",
            localProviderContinuation = ProviderTurnContinuation(
                protocol = ModelParameterProtocol.GEMINI,
                payloadJson = """{"role":"model","parts":[{"thought":true,"text":"分析","thoughtSignature":"sig"},{"functionCall":{"id":"call-1","name":"exec","args":{}}}]}""",
            ),
        )

        val payload = Json.parseToJsonElement(GeminiDirectClient.buildGeminiPayload(request)).jsonObject
        val modelContent = payload.getValue("contents").jsonArray
            .map { it.jsonObject }
            .last { it.getValue("role").jsonPrimitive.content == "model" }
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
                    toolCallId = "call-2",
                    toolName = "exec",
                    content = JsonPrimitive("content"),
                ),
                AgentToolResultApiMessage(
                    toolCallId = "call-1",
                    toolName = "read_file",
                    content = JsonPrimitive("ok"),
                ),
            ),
            provider = "Google", channel = "Gemini", apiAddress = "https://generativelanguage.googleapis.com",
            apiKey = "test-key", model = "gemini-test",
            localProviderContinuation = ProviderTurnContinuation(
                protocol = ModelParameterProtocol.GEMINI,
                payloadJson =
                    """{"role":"model","parts":[{"thought":true,"text":"分析","thoughtSignature":"sig"},{"functionCall":{"id":"call-1","name":"exec","args":{}}},{"functionCall":{"id":"call-2","name":"read_file","args":{}}}]}""",
            ),
        )

        val contents = Json.parseToJsonElement(GeminiDirectClient.buildGeminiPayload(request))
            .jsonObject.getValue("contents").jsonArray
        val modelCalls = contents[1].jsonObject.getValue("parts").jsonArray
            .mapNotNull { part ->
                part.jsonObject["functionCall"]?.jsonObject?.get("id")?.jsonPrimitive?.content
            }
        val resultContent = contents.last().jsonObject
        val resultIds = resultContent.getValue("parts").jsonArray
            .map { it.jsonObject.getValue("functionResponse").jsonObject.getValue("id").jsonPrimitive.content }
        val resultNames = resultContent.getValue("parts").jsonArray
            .map { it.jsonObject.getValue("functionResponse").jsonObject.getValue("name").jsonPrimitive.content }

        assertEquals(listOf("call-1", "call-2"), modelCalls)
        assertEquals("user", resultContent.getValue("role").jsonPrimitive.content)
        assertEquals(listOf("call-2", "call-1"), resultIds)
        assertEquals(listOf("read_file", "exec"), resultNames)
    }

    @Test
    fun `工具结果名称按Gemini调用ID校正`() {
        val request = ChatRequest(
            messages = listOf(
                SimpleTextApiMessage(role = "user", content = "修改文件"),
                AgentAssistantApiMessage(
                    toolCalls = listOf(AgentToolCallApiPart("call-1", "edit", JsonObject(emptyMap()))),
                ),
                AgentToolResultApiMessage(
                    toolCallId = "call-1",
                    toolName = "exec",
                    content = JsonPrimitive("ok"),
                ),
            ),
            provider = "Google",
            channel = "Gemini",
            apiAddress = "https://generativelanguage.googleapis.com",
            apiKey = "test-key",
            model = "gemini-3.7-flash",
            localProviderContinuation = ProviderTurnContinuation(
                protocol = ModelParameterProtocol.GEMINI,
                payloadJson =
                    """{"role":"model","parts":[{"functionCall":{"id":"call-1","name":"edit","args":{}},"thoughtSignature":"sig"}]}""",
            ),
        )

        val response = Json.parseToJsonElement(GeminiDirectClient.buildGeminiPayload(request))
            .jsonObject.getValue("contents").jsonArray.last().jsonObject
            .getValue("parts").jsonArray.single().jsonObject
            .getValue("functionResponse").jsonObject

        assertEquals("call-1", response.getValue("id").jsonPrimitive.content)
        assertEquals("edit", response.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `旧continuation不会替换当前工具调用`() {
        val request = ChatRequest(
            messages = listOf(
                SimpleTextApiMessage(role = "user", content = "执行命令"),
                AgentAssistantApiMessage(
                    toolCalls = listOf(AgentToolCallApiPart("call-current", "exec", JsonObject(emptyMap()))),
                ),
                AgentToolResultApiMessage(
                    toolCallId = "call-current",
                    toolName = "exec",
                    content = JsonPrimitive("ok"),
                ),
                SimpleTextApiMessage(role = "user", content = "继续"),
            ),
            provider = "Google",
            channel = "Gemini",
            apiAddress = "https://generativelanguage.googleapis.com",
            apiKey = "test-key",
            model = "gemini-3.7-flash",
            localProviderContinuation = ProviderTurnContinuation(
                protocol = ModelParameterProtocol.GEMINI,
                payloadJson =
                    """{"role":"model","parts":[{"functionCall":{"id":"call-old","name":"edit","args":{}},"thoughtSignature":"old-sig"}]}""",
            ),
        )

        val contents = Json.parseToJsonElement(GeminiDirectClient.buildGeminiPayload(request))
            .jsonObject.getValue("contents").jsonArray

        assertFalse(contents.any { content ->
            content.jsonObject.getValue("parts").jsonArray.any { part ->
                part.jsonObject["functionCall"]?.jsonObject?.get("name")?.jsonPrimitive?.content == "edit"
            }
        })
        assertFalse(contents.any { content ->
            content.jsonObject.getValue("parts").jsonArray.any { part ->
                part.jsonObject["functionResponse"] != null
            }
        })
        assertTrue(contents.any { content ->
            content.jsonObject.getValue("parts").jsonArray.any { part ->
                part.jsonObject["text"]?.jsonPrimitive?.content == "继续"
            }
        })
    }

    @Test
    fun `多轮工具历史只保留最新原生签名回合`() {
        val request = ChatRequest(
            messages = listOf(
                SimpleTextApiMessage(role = "user", content = "先读取再修改"),
                AgentAssistantApiMessage(
                    id = "assistant-old",
                    toolCalls = listOf(AgentToolCallApiPart("call-old", "read_file", JsonObject(emptyMap()))),
                ),
                AgentToolResultApiMessage(
                    toolCallId = "call-old",
                    toolName = "read_file",
                    content = JsonPrimitive("old result"),
                ),
                AgentAssistantApiMessage(
                    id = "assistant-current",
                    toolCalls = listOf(AgentToolCallApiPart("call-current", "edit", JsonObject(emptyMap()))),
                ),
                AgentToolResultApiMessage(
                    toolCallId = "call-current",
                    toolName = "edit",
                    content = JsonPrimitive("new result"),
                ),
            ),
            provider = "Google",
            channel = "Gemini",
            apiAddress = "https://generativelanguage.googleapis.com",
            apiKey = "test-key",
            model = "gemini-3.7-flash",
            localProviderContinuation = ProviderTurnContinuation(
                protocol = ModelParameterProtocol.GEMINI,
                payloadJson =
                    """{"role":"model","parts":[{"functionCall":{"id":"call-current","name":"edit","args":{}},"thoughtSignature":"current-sig"}]}""",
            ),
        )

        val contents = Json.parseToJsonElement(GeminiDirectClient.buildGeminiPayload(request))
            .jsonObject.getValue("contents").jsonArray
        val functionCalls = contents.flatMap { content ->
            content.jsonObject.getValue("parts").jsonArray.mapNotNull { it.jsonObject["functionCall"] }
        }
        val functionResponses = contents.flatMap { content ->
            content.jsonObject.getValue("parts").jsonArray.mapNotNull { it.jsonObject["functionResponse"] }
        }
        val signedPart = contents.flatMap { it.jsonObject.getValue("parts").jsonArray }
            .single { it.jsonObject["thoughtSignature"] != null }
            .jsonObject

        assertEquals(1, functionCalls.size)
        assertEquals("call-current", functionCalls.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(1, functionResponses.size)
        assertEquals("call-current", functionResponses.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("current-sig", signedPart.getValue("thoughtSignature").jsonPrimitive.content)
        assertFalse(contents.toString().contains("历史工具调用"))
        assertFalse(contents.toString().contains("历史工具结果"))
        assertFalse(contents.toString().contains("call-old"))
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
