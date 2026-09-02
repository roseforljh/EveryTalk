package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.AgentAssistantApiMessage
import com.android.everytalk.data.DataClass.AgentAssistantContentApiPart
import com.android.everytalk.data.DataClass.AgentToolCallApiPart
import com.android.everytalk.data.DataClass.AgentToolResultContentApiPart
import com.android.everytalk.data.DataClass.AgentToolResultApiMessage
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PiOpenAIResponsesMessageAdapterTest {
    @Test
    fun `旧纯文本assistant转换为标准Responses输出消息`() {
        val messages = listOf(SimpleTextApiMessage(id = "old-answer", role = "assistant", content = "旧回答"))

        val item = PiOpenAIResponsesMessageAdapter.buildInput(messages, request(messages)).single().jsonObject

        assertEquals("message", item.getValue("type").jsonPrimitive.content)
        assertEquals("assistant", item.getValue("role").jsonPrimitive.content)
        assertEquals("output_text", item.getValue("content").jsonArray.single().jsonObject
            .getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `Responses保留callId和itemId并给外来item生成fcId`() {
        val rawId = "call/1|foreign:item+value"
        val messages = listOf(
            AgentAssistantApiMessage(
                sourceProvider = "other",
                sourceEndpoint = "https://other.example",
                sourceModel = "other-model",
                toolCalls = listOf(AgentToolCallApiPart(rawId, "exec", JsonObject(emptyMap()))),
                contentParts = listOf(
                    AgentAssistantContentApiPart.ToolCall(
                        AgentToolCallApiPart(rawId, "exec", JsonObject(emptyMap())),
                    ),
                ),
            ),
            AgentToolResultApiMessage(
                toolCallId = rawId,
                toolName = "exec",
                content = JsonPrimitive("ok"),
            ),
        )

        val input = PiOpenAIResponsesMessageAdapter.buildInput(messages, request(messages))
        val call = input.first { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "function_call" }.jsonObject
        val output = input.first { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "function_call_output" }.jsonObject

        assertEquals("call_1", call.getValue("call_id").jsonPrimitive.content)
        assertTrue(call.getValue("id").jsonPrimitive.content.startsWith("fc_"))
        assertEquals("call_1", output.getValue("call_id").jsonPrimitive.content)
    }

    @Test
    fun `同源Responses配对ID保持原样`() {
        val rawId = "call_server_1|fc_server_1"
        val messages = listOf(
            AgentAssistantApiMessage(
                sourceProvider = "openai",
                sourceEndpoint = "https://api.openai.com/v1/responses",
                sourceModel = "gpt-5",
                sourceProtocol = ModelParameterProtocol.CODEX,
                toolCalls = listOf(AgentToolCallApiPart(rawId, "exec", JsonObject(emptyMap()))),
            ),
            AgentToolResultApiMessage(
                toolCallId = rawId,
                toolName = "exec",
                content = JsonPrimitive("ok"),
            ),
        )

        val input = PiOpenAIResponsesMessageAdapter.buildInput(messages, request(messages))
        val call = input.first { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "function_call" }.jsonObject
        val output = input.first {
            it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "function_call_output"
        }.jsonObject

        assertEquals("call_server_1", call.getValue("call_id").jsonPrimitive.content)
        assertEquals("fc_server_1", call.getValue("id").jsonPrimitive.content)
        assertEquals("call_server_1", output.getValue("call_id").jsonPrimitive.content)
    }

    @Test
    fun `同地址但来自Chat协议的itemId也按外来调用重建`() {
        val rawId = "call-1|fc_original-item"
        val messages = listOf(
            AgentAssistantApiMessage(
                sourceProvider = "openai",
                sourceEndpoint = "https://api.openai.com/v1/responses",
                sourceModel = "gpt-5",
                sourceProtocol = ModelParameterProtocol.OPENAI_COMPATIBLE,
                toolCalls = listOf(AgentToolCallApiPart(rawId, "exec", JsonObject(emptyMap()))),
            ),
        )

        val call = PiOpenAIResponsesMessageAdapter.buildInput(messages, request(messages))
            .map { it.jsonObject }
            .single { it["type"]?.jsonPrimitive?.contentOrNull == "function_call" }

        assertTrue(call.getValue("id").jsonPrimitive.content.startsWith("fc_"))
        assertTrue(call.getValue("id").jsonPrimitive.content != "fc_original-item")
    }

    @Test
    fun `Responses工具图片使用官方inputImage结构`() {
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

        val output = PiOpenAIResponsesMessageAdapter.buildInput(messages, request(messages))
            .first { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "function_call_output" }
            .jsonObject.getValue("output") as JsonArray

        assertEquals("input_image", output.single().jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("data:image/png;base64,AQID", output.single().jsonObject.getValue("image_url").jsonPrimitive.content)
    }

    private fun request(messages: List<com.android.everytalk.data.DataClass.AbstractApiMessage>) = ChatRequest(
        messages = messages,
        provider = "openai",
        channel = "responses",
        apiAddress = "https://api.openai.com/v1/responses",
        apiKey = "test",
        model = "gpt-5",
    )
}
