package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.RequestContextManagement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLoopContextGuardTest {
    private val management = RequestContextManagement(
        configId = "config-1",
        maxContextTokens = 2_000,
        reservedOutputTokens = 200,
        compactThresholdTokens = 1_000,
        autoCompressionEnabled = true,
    )
    private val usage = TokenUsage(
        totalTokens = 900,
        isFinal = true,
        source = TokenUsageSource.OPENAI_CHAT,
        requestOrdinal = 1,
    )

    @Test
    fun `OpenAI Chat优先缩减旧工具输出`() {
        val history = mutableListOf(
            JsonObject(mapOf("role" to JsonPrimitive("tool"), "content" to JsonPrimitive("旧".repeat(800)))),
            JsonObject(mapOf("role" to JsonPrimitive("tool"), "content" to JsonPrimitive("新".repeat(800)))),
        )

        assertTrue(compactOpenAIChatToolHistoryIfNeeded(history, management, usage))
        assertEquals(TRUNCATED_TOOL_OUTPUT_TEXT, history[0].getValue("content").jsonPrimitive.content)
        assertTrue(history[1].getValue("content").jsonPrimitive.content.contains("新"))
    }

    @Test
    fun `OpenAI Responses优先缩减旧工具输出`() {
        val history = mutableListOf<JsonElement>(
            responseOutput("call-old", "旧".repeat(800)),
            responseOutput("call-new", "新".repeat(800)),
        )

        assertTrue(compactResponsesToolHistoryIfNeeded(history, management, usage))
        val old = history[0].jsonObject
        assertEquals("call-old", old.getValue("call_id").jsonPrimitive.content)
        assertEquals(TRUNCATED_TOOL_OUTPUT_TEXT, old.getValue("output").jsonPrimitive.content)
    }

    @Test
    fun `Gemini保留函数响应结构并缩减旧结果`() {
        val history = mutableListOf(
            geminiResponse("old", "旧".repeat(800)),
            geminiResponse("new", "新".repeat(800)),
        )

        assertTrue(compactGeminiToolHistoryIfNeeded(history, management, usage))
        val result = history[0].getValue("parts").let { it as JsonArray }[0].jsonObject
            .getValue("functionResponse").jsonObject
            .getValue("response").jsonObject
            .getValue("result").jsonPrimitive.content
        assertEquals(TRUNCATED_TOOL_OUTPUT_TEXT, result)
    }

    @Test
    fun `Anthropic保留tool_result对应关系并缩减旧结果`() {
        val history = mutableListOf(
            anthropicResult("tool-old", "旧".repeat(800)),
            anthropicResult("tool-new", "新".repeat(800)),
        )

        assertTrue(compactAnthropicToolHistoryIfNeeded(history, management, usage))
        val block = history[0].getValue("content").let { it as JsonArray }[0].jsonObject
        assertEquals("tool-old", block.getValue("tool_use_id").jsonPrimitive.content)
        assertEquals(TRUNCATED_TOOL_OUTPUT_TEXT, block.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `单个最新工具输出过大时保留头尾和截断标记`() {
        val text = "HEAD" + "中".repeat(2_000) + "TAIL"
        val truncated = truncateToolOutput(text, maxTokens = 100)

        assertTrue(truncated.startsWith("HEAD"))
        assertTrue(truncated.endsWith("TAIL"))
        assertTrue(truncated.contains("工具输出已截断"))
        assertTrue(estimateToolLoopTextTokens(truncated) <= 100L)
    }

    @Test
    fun `百万上下文配置错误时仍限制工具输出历史`() {
        val oversizedManagement = RequestContextManagement(
            configId = "opencrab-gpt-5-6-luna",
            maxContextTokens = 1_050_000,
            reservedOutputTokens = 128_000,
            compactThresholdTokens = 945_000,
            autoCompressionEnabled = false,
        )
        val logShapedUsage = TokenUsage(
            totalTokens = 445_686,
            isFinal = true,
            source = TokenUsageSource.OPENAI_CHAT,
            requestOrdinal = 4,
        )
        val largeHtmlPage = "<div class=\"item\">content</div>".repeat(3_200)
        val history = MutableList(5) { index ->
            JsonObject(
                mapOf(
                    "role" to JsonPrimitive("tool"),
                    "tool_call_id" to JsonPrimitive("attachment-page-$index"),
                    "content" to JsonPrimitive(largeHtmlPage),
                )
            )
        }

        assertTrue(compactOpenAIChatToolHistoryIfNeeded(history, oversizedManagement, logShapedUsage))
        assertTrue(
            history.dropLast(1).any {
                it.getValue("content").jsonPrimitive.content == TRUNCATED_TOOL_OUTPUT_TEXT
            }
        )
        assertTrue(history.sumOf(::estimateToolLoopJsonTokens) <= 64_000L)
    }

    @Test
    fun `工具轮过渡正文进入思考并排在工具调用之前`() = runTest {
        val emitted = mutableListOf<AppStreamEvent>()
        val buffer = ToolRoundContentBuffer { emitted += it }
        val toolCall = AppStreamEvent.ToolCall(
            id = "call-1",
            name = "exec",
            argumentsObj = JsonObject(emptyMap()),
        )

        buffer.accept(AppStreamEvent.Content("我先检查服务器："))
        buffer.accept(toolCall)
        buffer.finish(hasToolCalls = true)

        assertEquals(
            listOf(AppStreamEvent.Reasoning("我先检查服务器："), toolCall),
            emitted,
        )
    }

    @Test
    fun `最终轮正文仍作为最终回答输出`() = runTest {
        val emitted = mutableListOf<AppStreamEvent>()
        val buffer = ToolRoundContentBuffer { emitted += it }

        buffer.accept(AppStreamEvent.Content("最终结论"))
        buffer.finish(hasToolCalls = false)

        assertEquals(listOf(AppStreamEvent.Content("最终结论")), emitted)
    }

    @Test
    fun `最终轮长正文在轮次结束前开始流式输出`() = runTest {
        val emitted = mutableListOf<AppStreamEvent>()
        val buffer = ToolRoundContentBuffer { emitted += it }
        val first = AppStreamEvent.Content("a".repeat(40))
        val second = AppStreamEvent.Content("b".repeat(40))

        buffer.accept(first)
        assertTrue(emitted.isEmpty())
        buffer.accept(second)

        assertEquals(listOf(first, second), emitted)
    }

    private fun responseOutput(callId: String, output: String): JsonObject = JsonObject(
        mapOf(
            "type" to JsonPrimitive("function_call_output"),
            "call_id" to JsonPrimitive(callId),
            "output" to JsonPrimitive(output),
        )
    )

    private fun geminiResponse(name: String, output: String): JsonObject = JsonObject(
        mapOf(
            "role" to JsonPrimitive("user"),
            "parts" to JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "functionResponse" to JsonObject(
                                mapOf(
                                    "name" to JsonPrimitive(name),
                                    "response" to JsonObject(mapOf("result" to JsonPrimitive(output))),
                                )
                            )
                        )
                    )
                )
            ),
        )
    )

    private fun anthropicResult(toolUseId: String, output: String): JsonObject = JsonObject(
        mapOf(
            "role" to JsonPrimitive("user"),
            "content" to JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("tool_result"),
                            "tool_use_id" to JsonPrimitive(toolUseId),
                            "content" to JsonPrimitive(output),
                        )
                    )
                )
            ),
        )
    )
}
