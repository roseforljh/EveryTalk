package com.android.everytalk.data.network

import com.android.everytalk.data.DataClass.RequestContextManagement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
