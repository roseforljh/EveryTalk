package com.android.everytalk.data.network

import android.app.Application
import com.android.everytalk.data.DataClass.AgentAssistantApiMessage
import com.android.everytalk.data.DataClass.AgentAssistantContentApiPart
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
        val modelParts = payload.getValue("contents").jsonArray
            .map { it.jsonObject }
            .last { it.getValue("role").jsonPrimitive.content == "model" }
            .getValue("parts").jsonArray

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
    fun `普通文本轮也恢复原生空签名块`() {
        val request = ChatRequest(
            messages = listOf(
                SimpleTextApiMessage(role = "user", content = "总结"),
                AgentAssistantApiMessage(id = "assistant-text", text = "完成"),
                SimpleTextApiMessage(role = "user", content = "继续"),
            ),
            provider = "Google",
            channel = "Gemini",
            apiAddress = "https://generativelanguage.googleapis.com",
            apiKey = "test-key",
            model = "gemini-3.7-flash",
            localProviderContinuation = ProviderTurnContinuation(
                protocol = ModelParameterProtocol.GEMINI,
                assistantMessageId = "assistant-text",
                payloadJson =
                    """{"role":"model","parts":[{"text":"","thoughtSignature":"ZW1wdHktc2ln"},{"text":"完成"}]}""",
            ),
        )

        val contents = Json.parseToJsonElement(GeminiDirectClient.buildGeminiPayload(request))
            .jsonObject.getValue("contents").jsonArray
        val modelParts = contents[1].jsonObject.getValue("parts").jsonArray

        assertEquals("", modelParts.first().jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(
            "ZW1wdHktc2ln",
            modelParts.first().jsonObject.getValue("thoughtSignature").jsonPrimitive.content,
        )
    }

    @Test
    fun `并行工具结果按调用顺序合并并原样带回调用ID`() {
        val request = ChatRequest(
            messages = listOf(
                SimpleTextApiMessage(role = "user", content = "检查服务"),
                AgentAssistantApiMessage(
                    toolCalls = listOf(
                        AgentToolCallApiPart("call-1", "get_current_datetime", JsonObject(emptyMap())),
                        AgentToolCallApiPart("call-2", "webfetch", JsonObject(emptyMap())),
                    ),
                ),
                AgentToolResultApiMessage(
                    toolCallId = "call-2",
                    toolName = "get_current_datetime",
                    content = JsonPrimitive("content"),
                ),
                AgentToolResultApiMessage(
                    toolCallId = "call-1",
                    toolName = "webfetch",
                    content = JsonPrimitive("ok"),
                ),
            ),
            provider = "Google", channel = "Gemini", apiAddress = "https://generativelanguage.googleapis.com",
            apiKey = "test-key", model = "gemini-test",
            localProviderContinuation = ProviderTurnContinuation(
                protocol = ModelParameterProtocol.GEMINI,
                payloadJson =
                    """{"role":"model","parts":[{"thought":true,"text":"分析","thoughtSignature":"sig"},{"functionCall":{"id":"call-1","name":"get_current_datetime","args":{}}},{"functionCall":{"id":"call-2","name":"webfetch","args":{}}}]}""",
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
        assertEquals(listOf("call-1", "call-2"), resultIds)
        assertEquals(listOf("get_current_datetime", "webfetch"), resultNames)
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
    fun `工具摘要与真实内容名字不同时按真实调用修正结果`() {
        val mcpToolName = "mcp_686a8a1a60fb12a1e9a97e319918c03c_web_search_exa"
        val request = ChatRequest(
            messages = listOf(
                SimpleTextApiMessage(role = "user", content = "搜索网页"),
                AgentAssistantApiMessage(
                    toolCalls = listOf(
                        AgentToolCallApiPart("call-1", "webfetch", JsonObject(emptyMap())),
                    ),
                    contentParts = listOf(
                        AgentAssistantContentApiPart.ToolCall(
                            AgentToolCallApiPart("call-1", mcpToolName, JsonObject(emptyMap())),
                        ),
                    ),
                ),
                AgentToolResultApiMessage(
                    toolCallId = "call-1",
                    toolName = "webfetch",
                    content = JsonPrimitive("ok"),
                ),
            ),
            provider = "Google",
            channel = "Gemini",
            apiAddress = "https://generativelanguage.googleapis.com",
            apiKey = "test-key",
            model = "gemini-3.7-flash",
        )

        val contents = Json.parseToJsonElement(GeminiDirectClient.buildGeminiPayload(request))
            .jsonObject.getValue("contents").jsonArray
        val callName = contents[1].jsonObject.getValue("parts").jsonArray.single().jsonObject
            .getValue("functionCall").jsonObject.getValue("name").jsonPrimitive.content
        val resultName = contents.last().jsonObject.getValue("parts").jsonArray.single().jsonObject
            .getValue("functionResponse").jsonObject.getValue("name").jsonPrimitive.content

        assertEquals(mcpToolName, callName)
        assertEquals(mcpToolName, resultName)
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

        assertTrue(contents.any { content ->
            content.jsonObject.getValue("parts").jsonArray.any { part ->
                part.jsonObject["functionCall"]?.jsonObject?.get("name")?.jsonPrimitive?.content == "exec"
            }
        })
        assertFalse(contents.any { content ->
            content.jsonObject.getValue("parts").jsonArray.any { part ->
                part.jsonObject["functionCall"]?.jsonObject?.get("name")?.jsonPrimitive?.content == "edit"
            }
        })
        assertTrue(contents.any { content ->
            content.jsonObject.getValue("parts").jsonArray.any { part ->
                part.jsonObject["functionResponse"]?.jsonObject?.get("id")?.jsonPrimitive?.content == "call-current"
            }
        })
        assertTrue(contents.any { content ->
            content.jsonObject.getValue("parts").jsonArray.any { part ->
                part.jsonObject["text"]?.jsonPrimitive?.content == "继续"
            }
        })
    }

    @Test
    fun `多轮工具历史全部保留原生调用结果和各轮签名`() {
        val request = ChatRequest(
            messages = listOf(
                SimpleTextApiMessage(role = "user", content = "先读取再修改"),
                AgentAssistantApiMessage(
                    id = "assistant-old",
                    toolCalls = listOf(
                        AgentToolCallApiPart(
                            "call-old",
                            "read_file",
                            JsonObject(mapOf("path" to JsonPrimitive("first-script"))),
                            thoughtSignature = "b2xkLXNpZw==",
                        ),
                    ),
                    sourceProvider = "Google",
                    sourceEndpoint = "https://generativelanguage.googleapis.com",
                    sourceModel = "gemini-3.7-flash",
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
            .map { it.jsonObject }
            .single { part ->
                part["functionCall"]?.jsonObject?.get("id")?.jsonPrimitive?.content == "call-current"
            }
            .jsonObject

        assertEquals(listOf("call-old", "call-current"), functionCalls.map {
            it.jsonObject.getValue("id").jsonPrimitive.content
        })
        assertEquals(listOf("call-old", "call-current"), functionResponses.map {
            it.jsonObject.getValue("id").jsonPrimitive.content
        })
        assertEquals("current-sig", signedPart.getValue("thoughtSignature").jsonPrimitive.content)
        assertTrue(contents.toString().contains("b2xkLXNpZw=="))
        assertFalse(contents.toString().contains("历史工具调用"))
        assertFalse(contents.toString().contains("历史工具结果"))
    }

    @Test
    fun `同模型恢复空思考签名块且跨模型剥离签名`() {
        val contentParts = listOf(
            AgentAssistantContentApiPart.Reasoning("", "c2lnbmF0dXJl"),
            AgentAssistantContentApiPart.ToolCall(
                AgentToolCallApiPart("call-1", "exec", JsonObject(emptyMap()), "dG9vbC1zaWc="),
            ),
        )
        fun payload(model: String) = Json.parseToJsonElement(
            GeminiDirectClient.buildGeminiPayload(
                ChatRequest(
                    messages = listOf(
                        SimpleTextApiMessage(role = "user", content = "继续"),
                        AgentAssistantApiMessage(
                            id = "assistant-1",
                            toolCalls = listOf(AgentToolCallApiPart("call-1", "exec", JsonObject(emptyMap()))),
                            contentParts = contentParts,
                            sourceProvider = "Google",
                            sourceEndpoint = "https://generativelanguage.googleapis.com",
                            sourceModel = "gemini-3.7-flash",
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
                    model = model,
                )
            )
        ).jsonObject.getValue("contents").jsonArray

        val sameModel = payload("gemini-3.7-flash").toString()
        val otherModel = payload("gemini-3.8-flash").toString()

        assertTrue(sameModel.contains("c2lnbmF0dXJl"))
        assertTrue(sameModel.contains("dG9vbC1zaWc="))
        assertFalse(otherModel.contains("thoughtSignature"))
        assertTrue(otherModel.contains("functionCall"))
        assertTrue(otherModel.contains("functionResponse"))
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
