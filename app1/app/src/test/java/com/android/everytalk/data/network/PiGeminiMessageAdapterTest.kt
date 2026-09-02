package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.AgentAssistantApiMessage
import com.android.everytalk.data.DataClass.AgentAssistantContentApiPart
import com.android.everytalk.data.DataClass.AgentToolCallApiPart
import com.android.everytalk.data.DataClass.AgentToolResultContentApiPart
import com.android.everytalk.data.DataClass.AgentToolResultApiMessage
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import com.android.everytalk.data.DataClass.ProviderTurnContinuation
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PiGeminiMessageAdapterTest {
    @Test
    fun `固定当前Pi上游提交`() {
        assertEquals(
            "b8b873b9872db04a938fb4357b5e8e824ddc051c",
            PiGeminiMessageAdapter.UPSTREAM_COMMIT,
        )
    }

    @Test
    fun `工具ID版本判断与Pi一致`() {
        mapOf(
            "gemini-2.5-flash" to false,
            "gemini-3.6-flash" to true,
            "gemini-live-3.0-flash" to true,
            "claude-sonnet-4-5" to true,
            "gpt-oss-120b" to true,
            "gemini-test" to false,
        ).forEach { (model, expected) ->
            assertEquals(model, expected, PiGeminiMessageAdapter.requiresToolCallId(model))
        }
    }

    @Test
    fun `Gemini三代工具轮JSON与Pi金标一致`() {
        val messages = listOf(
            SimpleTextApiMessage(role = "user", content = "Hi"),
            AgentAssistantApiMessage(
                toolCalls = listOf(
                    AgentToolCallApiPart(
                        id = "call_1",
                        name = "bash",
                        arguments = buildJsonObject { put("command", JsonPrimitive("echo hi")) },
                    ),
                    AgentToolCallApiPart(
                        id = "call_2",
                        name = "bash",
                        arguments = buildJsonObject { put("command", JsonPrimitive("false")) },
                    ),
                ),
            ),
            AgentToolResultApiMessage(
                toolCallId = "call_1",
                toolName = "bash",
                content = JsonPrimitive("hi"),
            ),
            AgentToolResultApiMessage(
                toolCallId = "call_2",
                toolName = "bash",
                content = JsonPrimitive("boom"),
                isError = true,
            ),
        )
        val request = ChatRequest(
            messages = messages,
            provider = "Google",
            channel = "Gemini",
            apiAddress = "https://generativelanguage.googleapis.com",
            apiKey = "test-key",
            model = "gemini-3.7-flash",
        )
        val expected = Json.parseToJsonElement(
            """
            [
              {"role":"user","parts":[{"text":"Hi"}]},
              {"role":"model","parts":[
                {"functionCall":{"id":"call_1","name":"bash","args":{"command":"echo hi"}}},
                {"functionCall":{"id":"call_2","name":"bash","args":{"command":"false"}}}
              ]},
              {"role":"user","parts":[
                {"functionResponse":{"name":"bash","response":{"output":"hi"},"id":"call_1"}},
                {"functionResponse":{"name":"bash","response":{"error":"boom"},"id":"call_2"}}
              ]}
            ]
            """.trimIndent()
        )

        assertEquals(expected, PiGeminiMessageAdapter.buildContents(messages, request))
    }

    @Test
    fun `Gemini三代工具ID按Pi规则清洗并限制64字符`() {
        val rawId = "call.with:invalid/chars-" + "x".repeat(80)
        val messages = listOf(
            AgentAssistantApiMessage(
                toolCalls = listOf(AgentToolCallApiPart(rawId, "bash", JsonObject(emptyMap()))),
            ),
            AgentToolResultApiMessage(
                toolCallId = rawId,
                toolName = "bash",
                content = JsonPrimitive("ok"),
            ),
        )
        val request = ChatRequest(
            messages = messages,
            provider = "Google",
            channel = "Gemini",
            apiAddress = "https://generativelanguage.googleapis.com",
            apiKey = "test-key",
            model = "gemini-3.7-flash",
        )

        val json = PiGeminiMessageAdapter.buildContents(messages, request).toString()
        val normalized = rawId.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)

        assertTrue(json.contains(normalized))
        assertTrue(normalized.length <= 64)
    }

    @Test
    fun `同源Gemini工具ID按Pi规则原样回放并保持调用结果配对`() {
        val rawId = "signed.call/id"
        val assistant = AgentAssistantApiMessage(
            toolCalls = listOf(AgentToolCallApiPart(rawId, "bash", JsonObject(emptyMap()), "c2ln")),
            sourceProvider = "Google",
            sourceEndpoint = "https://generativelanguage.googleapis.com",
            sourceModel = "gemini-3.7-flash",
            sourceProtocol = ModelParameterProtocol.GEMINI,
        )
        val messages = listOf(
            assistant,
            AgentToolResultApiMessage(
                toolCallId = rawId,
                toolName = "bash",
                content = JsonPrimitive("ok"),
            ),
        )
        val request = ChatRequest(
            messages = messages,
            provider = "Google",
            channel = "Gemini",
            apiAddress = "https://generativelanguage.googleapis.com",
            apiKey = "test-key",
            model = "gemini-3.7-flash",
        )

        val contents = PiGeminiMessageAdapter.buildContents(messages, request)
        val callId = contents[0].jsonObject.getValue("parts").jsonArray[0].jsonObject
            .getValue("functionCall").jsonObject.getValue("id").jsonPrimitive.content
        val responseId = contents[1].jsonObject.getValue("parts").jsonArray[0].jsonObject
            .getValue("functionResponse").jsonObject.getValue("id").jsonPrimitive.content

        assertEquals(rawId, callId)
        assertEquals(rawId, responseId)
    }

    @Test
    fun `无签名空白Assistant块按Pi规则删除`() {
        val messages = listOf(
            AgentAssistantApiMessage(
                contentParts = listOf(
                    AgentAssistantContentApiPart.Text("   "),
                    AgentAssistantContentApiPart.Reasoning("\n\t"),
                    AgentAssistantContentApiPart.ToolCall(
                        AgentToolCallApiPart("call_1", "bash", JsonObject(emptyMap())),
                    ),
                ),
                toolCalls = listOf(AgentToolCallApiPart("call_1", "bash", JsonObject(emptyMap()))),
            ),
        )
        val request = ChatRequest(
            messages = messages,
            provider = "Google",
            channel = "Gemini",
            apiAddress = "https://generativelanguage.googleapis.com",
            apiKey = "test-key",
            model = "gemini-3.7-flash",
        )

        val parts = PiGeminiMessageAdapter.buildContents(messages, request)
            .map { it.jsonObject }
            .single { it.getValue("role").jsonPrimitive.content == "model" }
            .getValue("parts")

        assertEquals(1, (parts as kotlinx.serialization.json.JsonArray).size)
    }

    @Test
    fun `同地址同模型但协议不同不能回放Gemini签名`() {
        val messages = listOf(
            AgentAssistantApiMessage(
                contentParts = listOf(
                    AgentAssistantContentApiPart.Text("完成", "c2lnbmF0dXJl"),
                ),
                sourceProvider = "Google",
                sourceEndpoint = "https://proxy.example.com",
                sourceModel = "gemini-3.7-flash",
                sourceProtocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
            ),
        )
        val request = ChatRequest(
            messages = messages,
            provider = "Google",
            channel = "Gemini",
            apiAddress = "https://proxy.example.com",
            apiKey = "test-key",
            model = "gemini-3.7-flash",
        )

        val json = PiGeminiMessageAdapter.buildContents(messages, request).toString()

        assertTrue("thoughtSignature" !in json)
    }

    @Test
    fun `模型变化后不能用原生continuation覆盖中立工具调用`() {
        val assistant = AgentAssistantApiMessage(
            id = "assistant-1",
            toolCalls = listOf(
                AgentToolCallApiPart(
                    "call-1",
                    "exec",
                    buildJsonObject { put("command", JsonPrimitive("current")) },
                ),
            ),
            sourceProvider = "Google",
            sourceEndpoint = "https://generativelanguage.googleapis.com",
            sourceModel = "gemini-3.6-flash",
            sourceProtocol = ModelParameterProtocol.GEMINI,
        )
        val request = ChatRequest(
            messages = listOf(assistant),
            provider = "Google",
            channel = "Gemini",
            apiAddress = "https://generativelanguage.googleapis.com",
            apiKey = "test-key",
            model = "gemini-3.7-flash",
            localProviderContinuation = ProviderTurnContinuation(
                protocol = ModelParameterProtocol.GEMINI,
                assistantMessageId = assistant.id,
                payloadJson =
                    """{"role":"model","parts":[{"functionCall":{"id":"call-1","name":"exec","args":{"command":"stale"}},"thoughtSignature":"c2ln"}]}""",
            ),
        )

        val part = PiGeminiMessageAdapter.buildContents(listOf(assistant), request)
            .map { it.jsonObject }
            .single { it.getValue("role").jsonPrimitive.content == "model" }
            .getValue("parts").jsonArray.single().jsonObject

        assertEquals(
            "current",
            part.getValue("functionCall").jsonObject.getValue("args").jsonObject
                .getValue("command").jsonPrimitive.content,
        )
        assertTrue("thoughtSignature" !in part)
    }

    @Test
    fun `Pi消息转换为缺失工具结果补充明确失败结果`() {
        val messages = listOf(
            SimpleTextApiMessage(role = "user", content = "执行"),
            AgentAssistantApiMessage(
                id = "assistant-1",
                toolCalls = listOf(
                    AgentToolCallApiPart("call-1", "bash", JsonObject(emptyMap())),
                    AgentToolCallApiPart("call-2", "read_file", JsonObject(emptyMap())),
                ),
                contentParts = listOf(
                    AgentAssistantContentApiPart.ToolCall(
                        AgentToolCallApiPart("call-1", "bash", JsonObject(emptyMap())),
                    ),
                    AgentAssistantContentApiPart.ToolCall(
                        AgentToolCallApiPart("call-2", "read_file", JsonObject(emptyMap())),
                    ),
                ),
            ),
            AgentToolResultApiMessage(
                toolCallId = "call-1",
                toolName = "wrong-name",
                content = JsonPrimitive("ok"),
            ),
        )
        val request = ChatRequest(
            messages = messages,
            provider = "Google",
            channel = "Gemini",
            apiAddress = "https://generativelanguage.googleapis.com",
            apiKey = "test-key",
            model = "gemini-3.7-flash",
        )

        val transformed = PiMessageTransformer.transformForGemini(messages, request)
        val results = transformed.filterIsInstance<AgentToolResultApiMessage>()

        assertEquals(listOf("call-1", "call-2"), results.map { it.toolCallId })
        assertEquals("bash", results[0].toolName)
        assertEquals("read_file", results[1].toolName)
        assertTrue(results[1].isError)
        assertEquals("No result provided", results[1].content.jsonPrimitive.content)
    }

    @Test
    fun `Gemini三代把工具图片放进functionResponse parts`() {
        val messages = listOf(
            AgentAssistantApiMessage(
                toolCalls = listOf(AgentToolCallApiPart("call-1", "camera", JsonObject(emptyMap()))),
            ),
            AgentToolResultApiMessage(
                toolCallId = "call-1",
                toolName = "camera",
                content = JsonPrimitive(""),
                contentBlocks = listOf(AgentToolResultContentApiPart.Image("AQID", "image/png")),
            ),
        )
        val request = ChatRequest(
            messages = messages,
            provider = "Google",
            channel = "Gemini",
            apiAddress = "https://generativelanguage.googleapis.com",
            apiKey = "test-key",
            model = "gemini-3.7-flash",
        )

        val response = PiGeminiMessageAdapter.buildContents(messages, request)[1].jsonObject
            .getValue("parts").jsonArray.single().jsonObject
            .getValue("functionResponse").jsonObject

        assertEquals("(see attached image)", response.getValue("response").jsonObject.getValue("output").jsonPrimitive.content)
        assertEquals("AQID", response.getValue("parts").jsonArray.single().jsonObject
            .getValue("inlineData").jsonObject.getValue("data").jsonPrimitive.content)
    }

    @Test
    fun `Gemini二代把工具图片放进独立user turn`() {
        val messages = listOf(
            AgentAssistantApiMessage(
                toolCalls = listOf(AgentToolCallApiPart("call-1", "camera", JsonObject(emptyMap()))),
            ),
            AgentToolResultApiMessage(
                toolCallId = "call-1",
                toolName = "camera",
                content = JsonPrimitive(""),
                contentBlocks = listOf(AgentToolResultContentApiPart.Image("AQID", "image/png")),
            ),
        )
        val request = ChatRequest(
            messages = messages,
            provider = "Google",
            channel = "Gemini",
            apiAddress = "https://generativelanguage.googleapis.com",
            apiKey = "test-key",
            model = "gemini-2.5-flash",
        )

        val contents = PiGeminiMessageAdapter.buildContents(messages, request)
        val functionResponse = contents[1].jsonObject.getValue("parts").jsonArray.single().jsonObject
            .getValue("functionResponse").jsonObject

        assertTrue("id" !in functionResponse)
        assertTrue("parts" !in functionResponse)
        assertEquals("AQID", contents[2].jsonObject.getValue("parts").jsonArray[1].jsonObject
            .getValue("inlineData").jsonObject.getValue("data").jsonPrimitive.content)
    }

    @Test
    fun `Pi清理孤立代理字符但保留合法emoji`() {
        val broken = "A" + '\uD83D' + "B🙈C" + '\uDC00'

        assertEquals("AB🙈C", broken.piSanitizeSurrogates())
    }

    @Test
    fun `文本模型把连续工具图片降级成一个Pi占位文本`() {
        val result = AgentToolResultApiMessage(
            toolCallId = "call-1",
            toolName = "camera",
            content = JsonPrimitive(""),
            contentBlocks = listOf(
                AgentToolResultContentApiPart.Image("AQID", "image/png"),
                AgentToolResultContentApiPart.Image("BAUG", "image/png"),
            ),
        )
        val request = ChatRequest(
            messages = listOf(result),
            provider = "Google",
            channel = "Gemini",
            apiAddress = "https://generativelanguage.googleapis.com",
            apiKey = "test-key",
            model = "text-only",
            localInputModalities = setOf("text"),
        )

        val transformed = PiMessageTransformer.transform(listOf(result), request)
            .single() as AgentToolResultApiMessage

        assertEquals(
            listOf("(tool image omitted: model does not support images)"),
            transformed.contentBlocks.filterIsInstance<AgentToolResultContentApiPart.Text>().map { it.text },
        )
        assertTrue(transformed.contentBlocks.none { it is AgentToolResultContentApiPart.Image })
    }

    @Test
    fun `最终Payload边界递归清理嵌套空Part`() {
        val payload = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("functionResponse", buildJsonObject {
                                put("name", JsonPrimitive("camera"))
                                put("response", buildJsonObject { put("output", JsonPrimitive("ok")) })
                                put("parts", buildJsonArray {
                                    add(buildJsonObject {})
                                    add(buildJsonObject {
                                        put("inlineData", buildJsonObject {
                                            put("mimeType", JsonPrimitive("image/png"))
                                            put("data", JsonPrimitive("AQID"))
                                        })
                                    })
                                })
                            })
                        })
                    })
                })
            })
        }

        val nestedParts = PiGeminiMessageAdapter.normalizePayload(payload)
            .getValue("contents").jsonArray.single().jsonObject
            .getValue("parts").jsonArray.single().jsonObject
            .getValue("functionResponse").jsonObject
            .getValue("parts").jsonArray

        assertEquals(1, nestedParts.size)
        assertTrue(nestedParts.single().jsonObject.containsKey("inlineData"))
    }

    @Test
    fun `长历史末尾的每个Gemini Part都初始化且只初始化一个data字段`() {
        val payload = buildJsonObject {
            put("contents", buildJsonArray {
                repeat(53) { index ->
                    add(buildJsonObject {
                        put("role", if (index % 2 == 0) "user" else "model")
                        put("parts", buildJsonArray { add(buildJsonObject { put("text", "turn-$index") }) })
                    })
                }
                add(buildJsonObject {
                    put("role", "model")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", "done") })
                        add(buildJsonObject {})
                        add(buildJsonObject { put("thought", true) })
                        add(buildJsonObject { put("thoughtSignature", "c2ln") })
                        add(buildJsonObject { put("text", JsonNull) })
                        add(buildJsonObject { put("functionCall", buildJsonObject { put("name", "exec") }) })
                    })
                })
            })
        }

        val normalized = PiGeminiMessageAdapter.normalizePayload(payload)
        val dataFields = setOf(
            "text", "inlineData", "fileData", "functionCall", "functionResponse",
            "executableCode", "codeExecutionResult",
        )
        normalized.getValue("contents").jsonArray.forEach { content ->
            content.jsonObject.getValue("parts").jsonArray.forEach { part ->
                assertEquals(1, part.jsonObject.keys.count(dataFields::contains))
            }
        }
    }

    @Test
    fun `最终Payload删除非法签名和清理后为空的Content`() {
        val payload = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("model"))
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("thoughtSignature", JsonPrimitive("not-base64"))
                        })
                    })
                })
                add(buildJsonObject {
                    put("role", JsonPrimitive("model"))
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("text", JsonPrimitive("保留正文"))
                            put("thoughtSignature", JsonPrimitive(1234))
                        })
                    })
                })
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", JsonPrimitive("继续")) })
                    })
                })
            })
        }

        val contents = PiGeminiMessageAdapter.normalizePayload(payload)
            .getValue("contents").jsonArray

        assertEquals(2, contents.size)
        assertEquals("保留正文", contents.first().jsonObject.getValue("parts").jsonArray.single()
            .jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("继续", contents.last().jsonObject.getValue("parts").jsonArray.single()
            .jsonObject.getValue("text").jsonPrimitive.content)
        assertTrue("thoughtSignature" !in contents.toString())
    }

    @Test
    fun `Gemini转换保留相邻用户消息边界`() {
        val request = ChatRequest(
            messages = emptyList(),
            provider = "Google",
            channel = "Gemini",
            apiAddress = "https://generativelanguage.googleapis.com",
            apiKey = "test-key",
            model = "gemini-3.7-flash",
        )

        val contents = PiGeminiMessageAdapter.buildContents(
            listOf(
                SimpleTextApiMessage(role = "user", content = "第一条"),
                SimpleTextApiMessage(role = "user", content = "第二条"),
            ),
            request,
        )

        assertEquals(2, contents.size)
    }
}
