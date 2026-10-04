package com.android.everytalk.data.network

import android.app.Application
import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.AgentAssistantApiMessage
import com.android.everytalk.data.DataClass.AgentToolCallApiPart
import com.android.everytalk.data.DataClass.AgentToolResultApiMessage
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ModelTokenLimits
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.agent.AgentContextManager
import com.android.everytalk.data.agent.AgentRunStore
import com.android.everytalk.data.agent.ExecutionCheckpoint
import com.android.everytalk.data.database.daos.AgentDao
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import io.ktor.utils.io.ByteReadChannel
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 从 Agent 上下文准备一路检查到真实协议 JSON 和入账，避免只测缓存辅助函数。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PromptCacheIntegrationTest {
    private val baseRequest = ChatRequest(
        messages = listOf(
            SimpleTextApiMessage(id = "rules", role = "system", content = "仅修改用户指定文件"),
            SimpleTextApiMessage(id = "question", role = "user", content = "检查构建结果"),
            AgentAssistantApiMessage(
                id = "assistant-1",
                text = "读取构建结果",
                toolCalls = listOf(AgentToolCallApiPart("call-1", "read_result", JsonObject(emptyMap()))),
            ),
            AgentToolResultApiMessage(
                id = "result-1", toolCallId = "call-1", toolName = "read_result",
                content = JsonPrimitive("构建成功"),
            ),
        ),
        provider = "OpenAI", channel = "OpenAI兼容", model = "gpt-5.6",
        apiAddress = "https://api.openai.com/v1", apiKey = "test-key",
    )

    private fun prepared(turn: Int, history: List<AbstractApiMessage> = baseRequest.messages): ChatRequest {
        val request = baseRequest.copy(messages = history + SimpleTextApiMessage(
            id = "computer-session-state", role = "system", content = "已运行 $turn 秒",
        ))
        val prepared = AgentContextManager().prepare(
            requestId = "request-$turn", request = request,
            limits = ModelTokenLimits(maxContextTokens = 128_000, maxOutputTokens = 8192),
            executionCheckpoint = ExecutionCheckpoint(currentGoal = "检查构建", currentStep = "准备第 $turn 轮"),
        )
        return request.copy(messages = prepared.messages)
    }

    @Test
    fun `上一轮状态冻结后成为下一轮Chat和Responses请求的完整前缀`() {
        val first = prepared(1)
        val frozen = SystemPromptInjector.freezeRuntimeContext(first.messages, "request-1")
        assertEquals(2, frozen.size)
        val languageProbe = SystemPromptInjector.smartInjectSystemPrompt(
            frozen + SimpleTextApiMessage(id = "new-user", role = "user", content = "继续检查"),
        )
        assertTrue((languageProbe.first() as SimpleTextApiMessage).content.contains("# 核心规则"))
        val nextAssistant = AgentAssistantApiMessage(
            id = "assistant-2",
            text = "再次读取构建结果",
            toolCalls = listOf(AgentToolCallApiPart("call-2", "read_result", JsonObject(emptyMap()))),
        )
        val nextResult = AgentToolResultApiMessage(
            id = "result-2", toolCallId = "call-2", toolName = "read_result",
            content = JsonPrimitive("第二次构建成功"),
        )
        val second = prepared(2, baseRequest.messages + frozen + nextAssistant + nextResult)
        listOf(
            "Chat" to { request: ChatRequest -> OpenAIDirectClient.buildOpenAIPayload(request) },
            "Responses" to { request: ChatRequest -> OpenAIResponsesClient.buildResponsesPayload(request, emptyList()) },
        ).forEach { (protocol, build) ->
            val key = if (protocol == "Chat") "messages" else "input"
            val firstMessages = Json.parseToJsonElement(build(first)).jsonObject.getValue(key).jsonArray
            val secondMessages = Json.parseToJsonElement(build(second)).jsonObject.getValue(key).jsonArray
            assertTrue(protocol, secondMessages.size > firstMessages.size)
            firstMessages.forEachIndexed { index, message ->
                assertEquals("$protocol 消息 $index", message, secondMessages[index])
            }
            assertTrue(protocol, secondMessages.toString().contains("准备第 2 轮"))
        }
    }

    @Test
    fun `四种协议的动态状态变化都保留稳定历史前缀和工具结果`() {
        val builders: List<Pair<String, (ChatRequest) -> String>> = listOf(
            "Chat" to { OpenAIDirectClient.buildOpenAIPayload(it) },
            "Responses" to { OpenAIResponsesClient.buildResponsesPayload(it, emptyList()) },
            "Anthropic" to { AnthropicDirectClient.buildAnthropicPayload(it.copy(channel = "Anthropic", model = "claude-sonnet-4-5")) },
            "Gemini" to { GeminiDirectClient.buildGeminiPayload(it.copy(channel = "Gemini", model = "gemini-2.5-pro")) },
        )
        builders.forEach { (protocol, build) ->
            val first = build(prepared(1))
            val second = build(prepared(2))
            val runtimeMarker = "[EveryTalk Runtime Context"
            assertTrue(protocol, first.contains(runtimeMarker))
            assertEquals(protocol, first.substringBefore(runtimeMarker), second.substringBefore(runtimeMarker))
            assertTrue(protocol, first.indexOf("构建成功") < first.indexOf(runtimeMarker))
            assertTrue(protocol, second.contains("准备第 2 轮"))
            assertTrue(protocol, second.contains("已运行 2 秒"))
            assertFalse(protocol, second.contains("准备第 1 轮"))
            val firstJson = Json.parseToJsonElement(first).jsonObject
            val secondJson = Json.parseToJsonElement(second).jsonObject
            listOf("tools", "system", "instructions", "systemInstruction", "prompt_cache_key").forEach { key ->
                assertEquals("$protocol $key", firstJson[key], secondJson[key])
            }
        }
    }

    @Test
    fun `Claude缓存断点在工具结果末尾而非每轮替换的状态快照`() {
        val payload = Json.parseToJsonElement(AnthropicDirectClient.buildAnthropicPayload(prepared(1))).jsonObject
        val messages = payload.getValue("messages").jsonArray
        val result = messages.dropLast(2).last().jsonObject.getValue("content").jsonArray.last().jsonObject
        assertEquals("tool_result", result.getValue("type").jsonPrimitive.content)
        assertEquals("ephemeral", result.getValue("cache_control").jsonObject.getValue("type").jsonPrimitive.content)
        messages.takeLast(2).forEach { assertFalse(it.toString().contains("cache_control")) }
        assertFalse(payload.containsKey("cache_control"))
    }

    @Test
    fun `DeepSeek命中量入账且缓存未命中不能被当成缓存写入`() = runTest {
        val usage = parseChatUsage("""{"prompt_tokens":1000,"completion_tokens":20,"prompt_cache_hit_tokens":800,"prompt_cache_miss_tokens":200}""")
        assertEquals(800L, usage.cachedInputTokens)
        assertNull(usage.cacheWriteTokens)
        val dao = mockk<AgentDao>(relaxed = true)
        val row = AgentRunStore(dao).saveUsage("request-usage", usage)
        assertEquals(1000L, row.promptTokens)
        assertEquals(200L, row.freshInputTokens)
        assertEquals(1020L, row.requestTotalTokens)
        coVerify(exactly = 1) { dao.upsertUsage(row) }
    }

    @Test
    fun `标准缓存零值优先且缺失缓存统计保持未知`() = runTest {
        assertEquals(0L, parseChatUsage("""{"prompt_tokens":100,"prompt_tokens_details":{"cached_tokens":0},"prompt_cache_hit_tokens":90}""").cachedInputTokens)
        assertNull(parseChatUsage("""{"prompt_tokens":100}""").cachedInputTokens)
    }

    @Test
    fun `Claude流式合并只累加一次缓存量且入账拆分互斥`() = runTest {
        val body = """
            data: {"type":"message_start","message":{"usage":{"input_tokens":100,"cache_read_input_tokens":800,"cache_creation_input_tokens":200}}}

            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":20}}

            data: {"type":"message_delta","usage":{"output_tokens":30}}

            data: {"type":"message_stop"}

        """.trimIndent()
        val events = mutableListOf<AppStreamEvent>()
        AnthropicDirectClient.parseAnthropicSse(ByteReadChannel(body.toByteArray())) { events += it }
        val usage = events.filterIsInstance<AppStreamEvent.Usage>().last().usage
        assertEquals(1100L, usage.inputTokens)
        val row = AgentRunStore(mockk(relaxed = true)).saveUsage("request-anthropic", usage)
        assertEquals(100L, row.freshInputTokens)
        assertEquals(800L, row.cacheReadTokens)
        assertEquals(200L, row.cacheWriteTokens)
        assertEquals(1130L, row.requestTotalTokens)
    }

    @Test
    fun `兼容接口拒绝用量参数后下次独立请求自动降级`() = runTest {
        val request = baseRequest.copy(apiAddress = "https://usage-unsupported.test/v1")
        val bodies = mutableListOf<JsonObject>()
        val engine = MockEngine { outgoing ->
            val payload = Json.parseToJsonElement((outgoing.body as TextContent).text).jsonObject
            bodies += payload
            if (payload.containsKey("stream_options")) respond(
                """{"error":{"message":"Unknown parameter: stream_options"}}""", HttpStatusCode.BadRequest,
            ) else respond(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }
        val client = HttpClient(engine) { install(HttpTimeout) }
        try {
            val first = OpenAIDirectClient.streamSingleTurn(client, request).toList()
            assertEquals("stream_usage_unsupported", first.filterIsInstance<AppStreamEvent.Error>().single().code)
            assertEquals(1, bodies.size)
            val second = OpenAIDirectClient.streamSingleTurn(client, request).toList()
            assertEquals("stop", second.filterIsInstance<AppStreamEvent.Finish>().single().reason)
            assertEquals(2, bodies.size)
            assertFalse(bodies.last().containsKey("stream_options"))
        } finally { client.close() }
    }

    private suspend fun parseChatUsage(json: String): TokenUsage {
        val events = mutableListOf<AppStreamEvent>()
        OpenAIDirectClient.parseOpenAISSEStreamWithTools(
            ByteReadChannel(("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: {\"choices\":[],\"usage\":$json}\n\ndata: [DONE]\n\n").toByteArray()),
            onToolCall = {}, emitEvent = { events += it },
        )
        return events.filterIsInstance<AppStreamEvent.Usage>().single().usage
    }
}
