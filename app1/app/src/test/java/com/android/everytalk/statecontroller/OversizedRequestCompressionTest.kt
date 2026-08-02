package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.ModelTokenLimits
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.network.AppStreamEvent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OversizedRequestCompressionTest {
    @Test
    fun `超长中文首轮分块压缩后严格低于窗口`() = runTest {
        val limits = ModelTokenLimits(maxOutputTokens = 512, maxContextTokens = 4_096)
        val messages = listOf(
            SimpleTextApiMessage(
                id = "user-1",
                role = "user",
                content = "用户目标：检查代码\n" + "中".repeat(200_000),
            )
        )
        var started = 0
        var chunkCount = 0

        val compressed = compressOversizedLatestUserTurn(
            messages = messages,
            tools = null,
            limits = limits,
            onCompressionStarted = { started++ },
        ) { chunk, _ ->
            chunkCount++
            "保留片段 ${chunk.index} 的用户目标和关键定义"
        }

        val estimate = RequestTokenEstimator.estimate(compressed, null).totalInputTokens
        assertEquals(1, started)
        assertTrue(chunkCount > 1)
        assertTrue(estimate + limits.maxOutputTokens <= limits.maxContextTokens)
        assertTrue((compressed.single() as SimpleTextApiMessage).content.startsWith("[当前用户输入已自动压缩]"))
    }

    @Test
    fun `代码按行分块并保留完整原文`() {
        val source = (1..200).joinToString("\n") { line -> "fun method$line() = $line" }

        val chunks = splitTextForContextCompression(source, maxTokens = 80)

        assertTrue(chunks.size > 1)
        assertEquals(source, chunks.joinToString("") { it.text })
        assertTrue(chunks.all { RequestTokenEstimator.estimateText(it.text) <= 80L })
        assertEquals(1, chunks.first().startLine)
        assertEquals(200, chunks.last().endLine)
    }

    @Test
    fun `压缩文本时保留媒体附件且不重复`() = runTest {
        val media = ApiContentPart.InlineData(base64Data = "AQID", mimeType = "image/png")
        val messages = listOf(
            PartsApiMessage(
                id = "user-media",
                role = "user",
                parts = listOf(ApiContentPart.Text("代码".repeat(8_000)), media),
            )
        )

        val compressed = compressOversizedLatestUserTurn(
            messages = messages,
            tools = null,
            limits = ModelTokenLimits(maxOutputTokens = 256, maxContextTokens = 6_000),
        ) { _, _ -> "用户要求分析附件中的代码" }

        val parts = (compressed.single() as PartsApiMessage).parts
        assertEquals(1, parts.filterIsInstance<ApiContentPart.InlineData>().size)
        assertEquals(media, parts.filterIsInstance<ApiContentPart.InlineData>().single())
    }

    @Test
    fun `媒体和工具开销单独超限时返回明确原因`() = runTest {
        val messages = listOf(
            PartsApiMessage(
                id = "user-media-only",
                role = "user",
                parts = listOf(ApiContentPart.InlineData(base64Data = "AQID", mimeType = "image/png")),
            )
        )

        try {
            compressOversizedLatestUserTurn(
                messages = messages,
                tools = null,
                limits = ModelTokenLimits(maxOutputTokens = 200, maxContextTokens = 1_000),
            ) { _, _ -> "不应调用" }
            fail("应抛出上下文压缩错误")
        } catch (error: ContextCompressionException) {
            assertTrue(error.displayReason.contains("媒体附件"))
        }
    }

    @Test
    fun `压缩模型返回空内容时直接失败`() = runTest {
        try {
            compressTextWithChunks(
                sourceText = "待压缩内容".repeat(1_000),
                targetTokens = 200,
                limits = ModelTokenLimits(maxOutputTokens = 200, maxContextTokens = 2_000),
            ) { _, _ -> "" }
            fail("应抛出上下文压缩错误")
        } catch (error: ContextCompressionException) {
            assertTrue(error.displayReason.contains("未返回有效内容"))
        }
    }

    @Test
    fun `真实超窗错误会缩小分块后重新切分`() = runTest {
        var failedChunkTotal = 0
        var successfulChunkTotal = 0
        var firstAttempt = true

        val result = compressTextWithChunks(
            sourceText = "代码行\n".repeat(5_000),
            targetTokens = 500,
            limits = ModelTokenLimits(maxOutputTokens = 200, maxContextTokens = 1_200),
        ) { chunk, _ ->
            if (firstAttempt) {
                firstAttempt = false
                failedChunkTotal = chunk.total
                throw ContextCompressionException(
                    displayReason = "context_length_exceeded",
                    category = RequestErrorCategory.INPUT_CONTEXT_TOO_LONG,
                )
            }
            successfulChunkTotal = maxOf(successfulChunkTotal, chunk.total)
            "片段${chunk.index}摘要"
        }

        assertTrue(result.isNotBlank())
        assertTrue(successfulChunkTotal > failedChunkTotal)
    }

    @Test
    fun `残缺压缩流即使已有文本也判定失败`() = runTest {
        try {
            collectContextCompressionResponse(flowOf(AppStreamEvent.Content("部分摘要")))
            fail("残缺流不能生成摘要")
        } catch (error: ContextCompressionException) {
            assertTrue(error.displayReason.contains("完成前中断"))
            assertTrue(error.retryable)
        }
    }

    @Test
    fun `完整压缩流返回正文`() = runTest {
        val result = collectContextCompressionResponse(
            flowOf(
                AppStreamEvent.Content("有效摘要"),
                AppStreamEvent.Finish("stop"),
            )
        )

        assertEquals("有效摘要", result)
    }

    @Test
    fun `临时错误有限重试并使用指数退避`() = runTest {
        var attempts = 0
        val delays = mutableListOf<Long>()

        val result = runContextCompressionWithRetries(
            pause = { delays += it },
        ) {
            attempts++
            if (attempts < 3) {
                throw ContextCompressionException("临时网络错误", retryable = true)
            }
            "完成"
        }

        assertEquals("完成", result)
        assertEquals(3, attempts)
        assertEquals(2, delays.size)
        assertTrue(delays[0] in 400L..600L)
        assertTrue(delays[1] in 800L..1_200L)
    }

    @Test
    fun `限流压缩错误标记为可重试`() {
        val error = ContextCompressionException.from(
            AppStreamEvent.Error(
                message = "rate limited",
                upstreamStatus = 429,
                code = "rate_limit_exceeded",
            )
        )

        assertTrue(error.retryable)
    }
}
