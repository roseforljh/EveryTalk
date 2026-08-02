package com.android.everytalk.data.database

import com.android.everytalk.data.DataClass.ContextUsageSnapshot
import com.android.everytalk.data.DataClass.ContextCompressionState
import com.android.everytalk.data.network.TokenUsage
import com.android.everytalk.data.network.TokenUsageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenUsageConverterTest {
    private val converters = Converters()

    @Test
    fun `usage JSON往返保留可空字段和来源`() {
        val usage = TokenUsage(
            inputTokens = 100,
            outputTokens = 30,
            reasoningTokens = null,
            cachedInputTokens = 20,
            totalTokens = 130,
            isFinal = true,
            source = TokenUsageSource.OPENAI_RESPONSES,
        )

        assertEquals(usage, converters.toTokenUsage(converters.fromTokenUsage(usage)))
    }

    @Test
    fun `旧消息和损坏usage保持为空`() {
        assertNull(converters.toTokenUsage(null))
        assertNull(converters.toTokenUsage("{损坏的JSON"))
    }

    @Test
    fun `上下文占用快照可以持久化和恢复`() {
        val snapshot = ContextUsageSnapshot(
            messageId = "ai-1",
            systemPromptTokens = 10,
            conversationTextTokens = 20,
            mediaTokens = 30,
            toolSchemaTokens = 40,
            protocolOverheadTokens = 5,
            reservedOutputTokens = 50,
            contextWindowTokens = 1_000,
        )

        assertEquals(
            snapshot,
            converters.toContextUsageSnapshot(converters.fromContextUsageSnapshot(snapshot)),
        )
        assertNull(converters.toContextUsageSnapshot("{损坏的JSON"))
    }

    @Test
    fun `压缩检查点和Responses权威状态可以持久化和恢复`() {
        val state = ContextCompressionState(
            configId = "config-1",
            provider = "OpenAI",
            channel = "codex",
            model = "gpt-5.6",
            summary = "较早会话摘要",
            summarizedThroughMessageId = "a1",
            summarizedPrefixFingerprint = "abcdef",
            windowNumber = 2,
            windowId = "window-2",
            previousWindowId = "window-1",
            openAiResponsesInputJson = "[{\"type\":\"compaction\",\"encrypted_content\":\"opaque\"}]",
            openAiResponsesThroughMessageId = "a2",
            openAiResponsesEstimatedTokens = 321,
        )

        assertEquals(
            state,
            converters.toContextCompressionState(converters.fromContextCompressionState(state)),
        )
        assertNull(converters.toContextCompressionState("{损坏的JSON"))
    }
}
