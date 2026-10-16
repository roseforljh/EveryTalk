package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.ContextUsageDataSource
import com.android.everytalk.data.DataClass.ContextUsageSnapshot
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.TokenUsage
import com.android.everytalk.data.network.TokenUsageSource
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.aiContextUsageSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiHandlerTokenUsageTest {
    @Test
    fun `usage按AI消息隔离并允许最终值晚于结束事件`() {
        val first = Message(
            id = "ai-1",
            text = "第一条",
            sender = Sender.AI,
            contextUsageSnapshot = ContextUsageSnapshot(
                messageId = "ai-1",
                systemPromptTokens = 10,
                conversationTextTokens = 90,
                mediaTokens = 0,
                toolSchemaTokens = 0,
                protocolOverheadTokens = 0,
                reservedOutputTokens = 50,
                contextWindowTokens = 1_000,
            ),
        )
        val second = Message(id = "ai-2", text = "第二条", sender = Sender.AI)

        val firstPartial = applyMessageTokenUsage(
            first,
            AppStreamEvent.Usage(
                TokenUsage(inputTokens = 100, isFinal = false, source = TokenUsageSource.ANTHROPIC)
            ),
        )
        val secondFinal = applyMessageTokenUsage(
            second,
            AppStreamEvent.Usage(
                TokenUsage(inputTokens = 7, outputTokens = 2, isFinal = true, source = TokenUsageSource.OPENAI_CHAT)
            ),
        )
        val firstFinalAfterFinish = applyMessageTokenUsage(
            firstPartial,
            AppStreamEvent.Usage(
                TokenUsage(outputTokens = 30, isFinal = true, source = TokenUsageSource.ANTHROPIC)
            ),
        )

        assertEquals(100L, firstFinalAfterFinish.tokenUsage?.inputTokens)
        assertEquals(30L, firstFinalAfterFinish.tokenUsage?.outputTokens)
        assertEquals(7L, secondFinal.tokenUsage?.inputTokens)
        assertEquals(2L, secondFinal.tokenUsage?.outputTokens)
        assertEquals(ContextUsageDataSource.MEASURED, firstFinalAfterFinish.contextUsageSnapshot?.dataSource)
        assertEquals(0L, firstFinalAfterFinish.contextUsageSnapshot?.inputEstimateDifferenceTokens)
    }

    @Test
    fun `服务端没有usage时最终消息回退为估算来源`() {
        val message = Message(
            id = "ai-estimated",
            text = "回答",
            sender = Sender.AI,
            contextUsageSnapshot = ContextUsageSnapshot(
                messageId = "ai-estimated",
                systemPromptTokens = 10,
                conversationTextTokens = 90,
                mediaTokens = 0,
                toolSchemaTokens = 20,
                protocolOverheadTokens = 5,
                reservedOutputTokens = 50,
                contextWindowTokens = 1_000,
            ),
        )

        val completed = applyEstimatedTokenUsageFallback(message)

        assertEquals(125L, completed.tokenUsage?.inputTokens)
        assertEquals(TokenUsageSource.ESTIMATED, completed.tokenUsage?.source)
        assertEquals(true, completed.tokenUsage?.isFinal)
        assertNull(completed.tokenUsage?.totalTokens)
    }

    @Test
    fun `新请求序号不会继承上一轮usage`() {
        val previous = Message(
            id = "ai-agent",
            text = "",
            sender = Sender.AI,
            tokenUsage = TokenUsage(
                inputTokens = 100,
                outputTokens = 20,
                isFinal = true,
                source = TokenUsageSource.OPENAI_CHAT,
                requestOrdinal = 1,
            ),
        )

        val next = applyMessageTokenUsage(
            previous,
            AppStreamEvent.Usage(
                TokenUsage(
                    inputTokens = 110,
                    isFinal = false,
                    source = TokenUsageSource.OPENAI_CHAT,
                    requestOrdinal = 2,
                ),
            ),
        )

        assertEquals(110L, next.tokenUsage?.inputTokens)
        assertNull(next.tokenUsage?.outputTokens)
        assertEquals(2, next.tokenUsage?.requestOrdinal)
    }

    @Test
    fun `请求开始的压缩快照替换旧估算且后续实测覆盖估算`() {
        val message = Message(
            id = "ai-stopped", text = "正在回复", sender = Sender.AI,
            contextUsageSnapshot = ContextUsageSnapshot(
                messageId = "ai-stopped", systemPromptTokens = 0, conversationTextTokens = 923_210,
                mediaTokens = 0, toolSchemaTokens = 0, protocolOverheadTokens = 0,
                reservedOutputTokens = 512, contextWindowTokens = 1_000_000,
            ).withActiveContextOverride(923_210),
        )
        val requestStart = AppStreamEvent.AgentUsage(
            activeRequest = TokenUsage(
                inputTokens = 100_000, isFinal = false,
                source = TokenUsageSource.ESTIMATED, requestOrdinal = 2,
            ),
            activeContext = message.contextUsageSnapshot?.copy(
                conversationTextTokens = 100_000, activeContextTokensOverride = null,
            ),
            runInputTokens = 180_000, runOutputTokens = 200, runTotalTokens = 180_200,
            requestCount = 2, conversationTotalTokens = 180_200,
        )
        val interrupted = applyMessageTokenUsage(message, requestStart)
        assertEquals(100_000L, interrupted.contextUsageSnapshot?.estimatedInputTokens)
        assertNull(interrupted.contextUsageSnapshot?.activeContextTokensOverride)
        assertNull(interrupted.contextUsageSnapshot?.measuredInputTokens)
        assertEquals(ContextUsageDataSource.ESTIMATED, interrupted.contextUsageSnapshot?.dataSource)
        assertEquals(180_200L, interrupted.contextUsageSnapshot?.agentRunTotalTokens)
        assertEquals(100_000L, aiContextUsageSummary(interrupted, 180_200)?.currentContextTokens)

        val measured = applyMessageTokenUsage(
            interrupted,
            AppStreamEvent.Usage(TokenUsage(
                inputTokens = 98_000, outputTokens = 50, isFinal = true,
                source = TokenUsageSource.OPENAI_CHAT, requestOrdinal = 2,
            )),
        )
        assertEquals(98_000L, measured.contextUsageSnapshot?.measuredInputTokens)
        assertEquals(ContextUsageDataSource.MEASURED, measured.contextUsageSnapshot?.dataSource)
        assertEquals(98_000L, aiContextUsageSummary(measured, 180_200)?.currentContextTokens)
    }
}
