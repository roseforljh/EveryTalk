package com.android.everytalk.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenUsageAccumulatorTest {

    @Test
    fun `非最终usage被最终值覆盖重复最终值不重复累计`() {
        val accumulator = TokenUsageAccumulator()
        val partial = TokenUsage(
            inputTokens = 100,
            cachedInputTokens = 20,
            isFinal = false,
            source = TokenUsageSource.ANTHROPIC,
        )
        val final = TokenUsage(
            outputTokens = 30,
            isFinal = true,
            source = TokenUsageSource.ANTHROPIC,
        )

        assertFalse(accumulator.update(partial).isFinal)
        val completed = accumulator.update(final)
        val duplicate = accumulator.update(final)

        assertEquals(100L, completed.inputTokens)
        assertEquals(30L, completed.outputTokens)
        assertEquals(20L, completed.cachedInputTokens)
        assertTrue(completed.isFinal)
        assertEquals(completed, duplicate)
    }

    @Test
    fun `新一轮usage累加到同一AI消息`() {
        val accumulator = TokenUsageAccumulator()
        accumulator.update(
            TokenUsage(inputTokens = 100, outputTokens = 20, isFinal = true, source = TokenUsageSource.OPENAI_CHAT)
        )
        accumulator.update(
            TokenUsage(inputTokens = 50, isFinal = false, source = TokenUsageSource.OPENAI_CHAT)
        )
        val total = accumulator.update(
            TokenUsage(outputTokens = 10, isFinal = true, source = TokenUsageSource.OPENAI_CHAT)
        )

        assertEquals(150L, total.inputTokens)
        assertEquals(30L, total.outputTokens)
    }

    @Test
    fun `同一轮更新后的最终usage替换旧值而不重复累计`() {
        val accumulator = TokenUsageAccumulator()
        accumulator.update(
            TokenUsage(inputTokens = 100, isFinal = false, source = TokenUsageSource.GEMINI)
        )
        accumulator.update(
            TokenUsage(outputTokens = 20, totalTokens = 120, isFinal = true, source = TokenUsageSource.GEMINI)
        )
        val revised = accumulator.update(
            TokenUsage(outputTokens = 25, totalTokens = 125, isFinal = true, source = TokenUsageSource.GEMINI)
        )

        assertEquals(100L, revised.inputTokens)
        assertEquals(25L, revised.outputTokens)
        assertEquals(125L, revised.totalTokens)
    }
}
