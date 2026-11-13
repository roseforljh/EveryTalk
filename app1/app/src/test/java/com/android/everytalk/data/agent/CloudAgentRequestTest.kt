package com.android.everytalk.data.agent

import com.android.everytalk.data.DataClass.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** 请求合同回归：使用虚构地址和密钥，不访问供应商。 */
class CloudAgentRequestTest {
    private val id = "a1ec0101-29dd-4b90-8b64-192f2f037777"
    private fun request(channel: String = "OpenAI兼容") = ChatRequest(
        messages = listOf(SimpleTextApiMessage(role = "system", content = "系统"), SimpleTextApiMessage(role = "user", content = "你好")),
        provider = "测试供应商", channel = channel, apiAddress = "https://example.com/proxy/v1?tenant=test", apiKey = "test-only", model = "test-model",
    )
    private fun convert(request: ChatRequest) = cloudAgentInput(request, id, id, id, id, 0, 0, 123, "Asia/Shanghai", JsonArray(emptyList()))

    @Test fun `四协议保留代理前缀查询参数与完整自定义地址`() {
        for ((channel, suffix, api) in listOf(
            Triple("OpenAI兼容", "/chat/completions", "openai-completions"), Triple("Codex", "/responses", "openai-responses"),
            Triple("Anthropic", "/messages", "anthropic-messages"), Triple("Gemini", "/models/test-model:streamGenerateContent", "google-generative-ai"),
        )) {
            val base = if (channel == "Gemini") "https://example.com/proxy/v1beta" else "https://example.com/proxy/v1"
            val input = convert(request(channel).copy(apiAddress = "$base?tenant=test"))
            assertEquals("$base$suffix?tenant=test", input.getValue("endpoint").jsonPrimitive.content)
            assertEquals(base, input.getValue("model").jsonObject.getValue("baseUrl").jsonPrimitive.content)
            assertEquals(api, input.getValue("model").jsonObject.getValue("api").jsonPrimitive.content)
            assertEquals("https://example.com/custom?tenant=test", convert(request(channel).copy(apiAddress = "https://example.com/custom?tenant=test#")).getValue("endpoint").jsonPrimitive.content)
        }
        assertTrue(runCatching { convert(request().copy(apiAddress = "http://example.com")) }.isFailure)
    }

    @Test fun `Gemini采样和预算在generationConfig且兼容协议保留自定义程度`() {
        val input = convert(request("Gemini").copy(generationConfig = GenerationConfig(topP = 0.7f, maxOutputTokens = 4096,
            thinkingConfig = ThinkingConfig(includeThoughts = true, thinkingBudget = 2048, reasoningMode = ReasoningMode.BUDGET))))
        val extra = input.getValue("extraBody").jsonObject
        assertFalse(extra.containsKey("topP"))
        assertEquals(0.7f, extra.getValue("generationConfig").jsonObject.getValue("topP").jsonPrimitive.float)
        assertEquals(2048, input.getValue("thinkingBudgets").jsonObject.getValue("medium").jsonPrimitive.int)
        val compatible = convert(request().copy(customModelParameters = mapOf("reasoning_effort" to "high")))
        assertEquals("high", compatible.getValue("thinkingLevel").jsonPrimitive.content)
        val anthropic = convert(request("Anthropic").copy(generationConfig = GenerationConfig(thinkingConfig = ThinkingConfig(includeThoughts = true)), localAllowEmptyAnthropicThinkingSignature = true))
        assertTrue(anthropic.getValue("model").jsonObject.getValue("compat").jsonObject.getValue("forceAdaptiveThinking").jsonPrimitive.boolean)
    }

    @Test fun `有序签名工具配对图片和system按Pi合同输出`() {
        val request = request("Gemini")
        val call = AgentToolCallApiPart("call1", "clock", buildJsonObject {}, "call-signature", "namespace")
        val assistant = AgentAssistantApiMessage(contentParts = listOf(
            AgentAssistantContentApiPart.Reasoning("思考", "reason-signature"), AgentAssistantContentApiPart.Text("正文", "text-signature"), AgentAssistantContentApiPart.ToolCall(call)),
            sourceProvider = request.provider, sourceEndpoint = request.apiAddress, sourceModel = request.model, sourceProtocol = ModelParameterProtocol.GEMINI)
        val messages = convert(request.copy(messages = request.messages + assistant)).getValue("messages").jsonArray
        assertEquals("system", messages.first().jsonObject.getValue("role").jsonPrimitive.content)
        val parts = messages[2].jsonObject.getValue("content").jsonArray
        assertEquals(listOf("thinking", "text", "toolCall"), parts.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals("reason-signature", parts[0].jsonObject.getValue("thinkingSignature").jsonPrimitive.content)
        assertEquals("text-signature", parts[1].jsonObject.getValue("textSignature").jsonPrimitive.content)
        assertEquals("toolResult", messages.last().jsonObject.getValue("role").jsonPrimitive.content)
        assertTrue(messages.last().jsonObject.getValue("isError").jsonPrimitive.boolean)
        val image = PartsApiMessage(role = "user", parts = listOf(ApiContentPart.InlineData("aGVsbG8=", "image/png")))
        assertEquals("image", convert(request.copy(messages = listOf(image))).getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
    }
}
