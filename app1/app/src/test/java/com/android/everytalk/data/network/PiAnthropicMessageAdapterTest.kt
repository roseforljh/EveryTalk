package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.AgentAssistantApiMessage
import com.android.everytalk.data.DataClass.AgentAssistantContentApiPart
import com.android.everytalk.data.DataClass.AgentToolResultContentApiPart
import com.android.everytalk.data.DataClass.AgentToolResultApiMessage
import com.android.everytalk.data.DataClass.AgentToolCallApiPart
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ModelParameterProtocol
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PiAnthropicMessageAdapterTest {
    @Test
    fun `同源Anthropic工具ID保持原样`() {
        val id = "toolu_server_1"
        val messages = listOf(
            AgentAssistantApiMessage(
                sourceProvider = "Anthropic",
                sourceEndpoint = "https://api.anthropic.com",
                sourceModel = "claude-sonnet-4-6",
                sourceProtocol = ModelParameterProtocol.ANTHROPIC,
                toolCalls = listOf(AgentToolCallApiPart(id, "exec", JsonObject(emptyMap()))),
            ),
            AgentToolResultApiMessage(
                toolCallId = id,
                toolName = "exec",
                content = JsonPrimitive("ok"),
            ),
        )

        val output = PiAnthropicMessageAdapter.buildMessages(messages, request(messages))
        val toolUseId = output.first().getValue("content").jsonArray.single().jsonObject
            .getValue("id").jsonPrimitive.content
        val toolResultId = output.last().getValue("content").jsonArray.single().jsonObject
            .getValue("tool_use_id").jsonPrimitive.content

        assertEquals(id, toolUseId)
        assertEquals(id, toolResultId)
    }

    @Test
    fun `同源redacted thinking按opaque块回放`() {
        val message = AgentAssistantApiMessage(
            sourceProvider = "anthropic",
            sourceEndpoint = "https://api.anthropic.com",
            sourceModel = "claude-sonnet-4-5",
            contentParts = listOf(
                AgentAssistantContentApiPart.Reasoning(
                    text = "",
                    thoughtSignature = "opaque-redacted",
                    redacted = true,
                ),
            ),
        )

        val block = PiAnthropicMessageAdapter.buildMessages(listOf(message), request(listOf(message)))
            .single().getValue("content").jsonArray.single().jsonObject

        assertEquals("redacted_thinking", block.getValue("type").jsonPrimitive.content)
        assertEquals("opaque-redacted", block.getValue("data").jsonPrimitive.content)
    }

    @Test
    fun `兼容端点允许空thinking signature`() {
        val message = AgentAssistantApiMessage(
            sourceProvider = "anthropic",
            sourceEndpoint = "https://api.anthropic.com",
            sourceModel = "claude-sonnet-4-5",
            contentParts = listOf(AgentAssistantContentApiPart.Reasoning("分析")),
        )
        val request = request(listOf(message)).copy(localAllowEmptyAnthropicThinkingSignature = true)

        val block = PiAnthropicMessageAdapter.buildMessages(listOf(message), request)
            .single().getValue("content").jsonArray.single().jsonObject

        assertEquals("thinking", block.getValue("type").jsonPrimitive.content)
        assertEquals("", block.getValue("signature").jsonPrimitive.content)
    }

    @Test
    fun `工具图片转换成Anthropic toolResult内容块`() {
        val result = AgentToolResultApiMessage(
            toolCallId = "call-1",
            toolName = "camera",
            content = JsonPrimitive(""),
            contentBlocks = listOf(AgentToolResultContentApiPart.Image("AQID", "image/png")),
        )

        val block = PiAnthropicMessageAdapter.buildMessages(listOf(result), request(listOf(result)))
            .single().getValue("content").jsonArray.single().jsonObject
        val content = block.getValue("content").jsonArray

        assertEquals("image", content.first().jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("AQID", content.first().jsonObject.getValue("source").jsonObject
            .getValue("data").jsonPrimitive.content)
        assertTrue(content.any { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull == "(see attached image)" })
    }

    @Test
    fun `Anthropic工具结果中的非法图片类型降级为文本`() {
        val result = AgentToolResultApiMessage(
            toolCallId = "call-1",
            toolName = "render",
            content = JsonPrimitive(""),
            contentBlocks = listOf(AgentToolResultContentApiPart.Image("PHN2Zz4=", "image/svg+xml")),
        )

        val block = PiAnthropicMessageAdapter.buildMessages(listOf(result), request(listOf(result)))
            .single().getValue("content").jsonArray.single().jsonObject

        assertEquals(
            "[unsupported tool image type: image/svg+xml]",
            block.getValue("content").jsonPrimitive.content,
        )
    }

    private fun request(messages: List<com.android.everytalk.data.DataClass.AbstractApiMessage>) = ChatRequest(
        messages = messages,
        provider = "anthropic",
        channel = "anthropic",
        apiAddress = "https://api.anthropic.com",
        apiKey = "test",
        model = "claude-sonnet-4-5",
    )
}
