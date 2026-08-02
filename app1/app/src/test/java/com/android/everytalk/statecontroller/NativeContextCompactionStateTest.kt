package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.ContextCompressionState
import com.android.everytalk.data.DataClass.ContextUsageSnapshot
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.network.AppStreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeContextCompactionStateTest {
    @Test
    fun `原生权威状态合并时保留本地摘要并同步圆环占用`() {
        val message = message()
        val event = nativeEvent(inputJson = "[{\"type\":\"compaction\"}]", estimatedTokens = 321)

        val merged = mergeNativeContextCompaction(message, event)
        val state = checkNotNull(merged.contextCompressionState)

        assertEquals("本地摘要", state.summary)
        assertEquals(2L, state.windowNumber)
        assertEquals("window-1", state.previousWindowId)
        assertEquals(message.id, state.openAiResponsesThroughMessageId)
        assertEquals(321L, state.openAiResponsesEstimatedTokens)
        assertEquals(321L, merged.contextUsageSnapshot?.displayedUsedTokens)
    }

    @Test
    fun `原生能力降级只清除权威状态并保留本地检查点`() {
        val merged = mergeNativeContextCompaction(
            message(),
            nativeEvent(inputJson = "", estimatedTokens = 0, reset = true),
        )
        val state = checkNotNull(merged.contextCompressionState)

        assertEquals("本地摘要", state.summary)
        assertNull(state.openAiResponsesInputJson)
        assertNull(state.openAiResponsesThroughMessageId)
        assertEquals(0L, state.openAiResponsesEstimatedTokens)
        assertNull(merged.contextUsageSnapshot?.activeContextTokensOverride)
    }

    private fun message(): Message = Message(
        id = "assistant-current",
        text = "",
        sender = Sender.AI,
        contextUsageSnapshot = ContextUsageSnapshot(
            messageId = "assistant-current",
            configId = "config-1",
            systemPromptTokens = 10,
            conversationTextTokens = 100,
            mediaTokens = 0,
            toolSchemaTokens = 0,
            protocolOverheadTokens = 5,
            reservedOutputTokens = 20,
            contextWindowTokens = 1_000,
            measuredInputTokens = 700,
            measuredOutputTokens = 100,
            measuredTotalTokens = 800,
        ),
        contextCompressionState = ContextCompressionState(
            configId = "config-1",
            provider = "OpenAI",
            channel = "codex",
            model = "gpt-5.6",
            summary = "本地摘要",
            summarizedThroughMessageId = "assistant-old",
            summarizedPrefixFingerprint = "fingerprint",
            windowNumber = 1,
            windowId = "window-1",
            openAiResponsesInputJson = "[{\"type\":\"compaction\"}]",
            openAiResponsesThroughMessageId = "assistant-old",
            openAiResponsesEstimatedTokens = 500,
        ),
    )

    private fun nativeEvent(
        inputJson: String,
        estimatedTokens: Long,
        reset: Boolean = false,
    ) = AppStreamEvent.NativeContextCompaction(
        inputJson = inputJson,
        configId = "config-1",
        provider = "OpenAI",
        channel = "codex",
        model = "gpt-5.6",
        compactionItemId = "cmp-2",
        estimatedTokens = estimatedTokens,
        reset = reset,
    )
}
