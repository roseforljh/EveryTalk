package com.android.everytalk.data

import com.android.everytalk.data.DataClass.ContextUsageDataSource
import com.android.everytalk.data.DataClass.ContextUsageSnapshot
import com.android.everytalk.data.network.TokenUsage
import com.android.everytalk.data.network.TokenUsageSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextUsageSnapshotTest {
    @Test
    fun `估算分类合计与最终实测保持同一次请求口径`() {
        val estimated = ContextUsageSnapshot(
            messageId = "ai-1",
            systemPromptTokens = 10,
            conversationTextTokens = 20,
            mediaTokens = 30,
            toolSchemaTokens = 40,
            protocolOverheadTokens = 5,
            reservedOutputTokens = 50,
            contextWindowTokens = 200,
        )

        assertEquals(105L, estimated.estimatedInputTokens)
        assertEquals(155L, estimated.displayedUsedTokens)
        assertEquals(45L, estimated.remainingTokens)
        assertEquals(ContextUsageDataSource.ESTIMATED, estimated.dataSource)

        val measured = estimated.withFinalUsage(
            TokenUsage(
                inputTokens = 100,
                outputTokens = 20,
                cachedInputTokens = 25,
                totalTokens = 120,
                isFinal = true,
                source = TokenUsageSource.OPENAI_CHAT,
            )
        )

        assertEquals(120L, measured.displayedUsedTokens)
        assertEquals(80L, measured.remainingTokens)
        assertEquals(-5L, measured.inputEstimateDifferenceTokens)
        assertEquals(ContextUsageDataSource.MEASURED, measured.dataSource)
    }

    @Test
    fun `估算兜底不会显示为实测`() {
        val snapshot = ContextUsageSnapshot(
            messageId = "ai-1",
            systemPromptTokens = 10,
            conversationTextTokens = 20,
            mediaTokens = 0,
            toolSchemaTokens = 0,
            protocolOverheadTokens = 5,
            reservedOutputTokens = 10,
            contextWindowTokens = 1_000,
        ).withFinalUsage(
            TokenUsage(
                inputTokens = 35,
                isFinal = true,
                source = TokenUsageSource.ESTIMATED,
            )
        )

        assertEquals(ContextUsageDataSource.ESTIMATED, snapshot.dataSource)
    }
}
