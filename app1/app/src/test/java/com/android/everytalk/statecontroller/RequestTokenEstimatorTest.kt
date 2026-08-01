package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.ModelTokenLimits
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.PartsApiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestTokenEstimatorTest {

    @Test
    fun `英文长文本按字符密度估算且分类合计等于总量`() {
        val estimate = RequestTokenEstimator.estimate(
            messages = listOf(
                SimpleTextApiMessage(
                    id = "user",
                    role = "user",
                    content = "a".repeat(4_000),
                )
            ),
            tools = null,
        )

        assertTrue(estimate.conversationTextTokens < 4_000)
        assertEquals(
            estimate.systemPromptTokens +
                estimate.conversationTextTokens +
                estimate.mediaTokens +
                estimate.toolSchemaTokens +
                estimate.protocolOverheadTokens,
            estimate.totalInputTokens,
        )
    }

    @Test
    fun `工具schema稳定化后计入独立分类`() {
        val first = mapOf<String, Any>(
            "type" to "function",
            "function" to mapOf(
                "name" to "lookup",
                "description" to "d".repeat(4_000),
                "parameters" to mapOf("type" to "object"),
            ),
        )
        val reordered = linkedMapOf<String, Any>(
            "function" to linkedMapOf(
                "parameters" to mapOf("type" to "object"),
                "description" to "d".repeat(4_000),
                "name" to "lookup",
            ),
            "type" to "function",
        )

        val firstEstimate = RequestTokenEstimator.estimate(emptyList(), listOf(first))
        val reorderedEstimate = RequestTokenEstimator.estimate(emptyList(), listOf(reordered))

        assertTrue(firstEstimate.toolSchemaTokens > 0)
        assertEquals(firstEstimate.toolSchemaTokens, reorderedEstimate.toolSchemaTokens)
    }

    @Test
    fun `媒体成本允许Provider覆盖`() {
        val message = PartsApiMessage(
            role = "user",
            parts = listOf(
                ApiContentPart.Text("描述图片"),
                ApiContentPart.FileUri("content://image", "image/png"),
            ),
        )

        val unknownProvider = RequestTokenEstimator.estimate(listOf(message), null)
        val knownProvider = RequestTokenEstimator.estimate(
            messages = listOf(message),
            tools = null,
            mediaTokenEstimator = { 777L },
        )

        assertEquals(4_096L, unknownProvider.mediaTokens)
        assertEquals(777L, knownProvider.mediaTokens)
    }

    @Test
    fun `CJK与十万级schema估算保持保守且不溢出`() {
        val asciiTokens = RequestTokenEstimator.estimateText("a".repeat(1_000))
        val cjkTokens = RequestTokenEstimator.estimateText("汉".repeat(1_000))
        val hugeTool = mapOf<String, Any>(
            "type" to "function",
            "function" to mapOf(
                "name" to "huge_tool",
                "description" to "字段：value, ".repeat(10_000),
                "parameters" to emptyMap<String, Any>(),
            ),
        )

        val estimate = RequestTokenEstimator.estimate(
            messages = listOf(SimpleTextApiMessage(role = "user", content = "")),
            tools = listOf(hugeTool),
        )

        assertTrue(cjkTokens > asciiTokens)
        assertTrue(estimate.toolSchemaTokens > 0L)
        assertTrue(estimate.totalInputTokens in 1 until Long.MAX_VALUE)
    }

    @Test
    fun `旧会话和未发送草稿可生成实时估算快照`() {
        val snapshot = estimateConversationDraftContextUsage(
            messages = listOf(
                Message(id = "u1", text = "旧问题", sender = Sender.User),
                Message(id = "a1", text = "旧回答", sender = Sender.AI),
            ),
            draftText = "新问题",
            systemPrompt = "系统规则",
            tools = listOf(mapOf("type" to "function", "name" to "search")),
            limits = ModelTokenLimits(maxOutputTokens = 100, maxContextTokens = 1_000),
        )
        val withoutTools = estimateConversationDraftContextUsage(
            messages = emptyList(),
            draftText = "新问题",
            systemPrompt = null,
            tools = null,
            limits = ModelTokenLimits(maxOutputTokens = 100, maxContextTokens = 1_000),
        )

        assertTrue(snapshot.systemPromptTokens > 0)
        assertTrue(snapshot.conversationTextTokens > 0)
        assertTrue(snapshot.toolSchemaTokens > 0)
        assertTrue(snapshot.toolSchemaTokens > withoutTools.toolSchemaTokens)
        assertEquals(100L, snapshot.reservedOutputTokens)
        assertEquals(1_000L, snapshot.contextWindowTokens)
    }
}
