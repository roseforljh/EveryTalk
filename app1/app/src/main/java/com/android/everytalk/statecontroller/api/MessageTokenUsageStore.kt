package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.TokenUsage
import com.android.everytalk.data.network.TokenUsageSource
import java.util.concurrent.ConcurrentHashMap

/** 每条 AI 消息只保存最近一次真实模型请求，Run 累计由 Agent 表单独聚合。 */
internal class MessageTokenUsageStore {
    private val activeUsage = ConcurrentHashMap<String, TokenUsage>()

    fun apply(message: Message, event: AppStreamEvent.Usage): Message {
        val previous = activeUsage[message.id]
        val startsNewRequest = event.usage.requestOrdinal != null &&
            previous?.requestOrdinal != null &&
            event.usage.requestOrdinal != previous.requestOrdinal
        val base = previous.takeUnless { startsNewRequest }
        val current = mergeRequestUsage(base, event.usage)
        activeUsage[message.id] = current
        return message.copy(
            tokenUsage = current,
            contextUsageSnapshot = message.contextUsageSnapshot?.withFinalUsage(current),
        )
    }

    fun apply(message: Message, event: AppStreamEvent.AgentUsage): Message = message.copy(
        tokenUsage = event.activeRequest,
        contextUsageSnapshot = (event.activeContext ?: message.contextUsageSnapshot)?.withAgentUsage(
            usage = event.activeRequest,
            runInputTokens = event.runInputTokens,
            runOutputTokens = event.runOutputTokens,
            runTotalTokens = event.runTotalTokens,
            requestCount = event.requestCount,
            conversationTotalTokens = event.conversationTotalTokens,
        ),
    )

    fun remove(messageId: String) {
        activeUsage.remove(messageId)
    }

    fun clear() {
        activeUsage.clear()
    }

    private fun mergeRequestUsage(current: TokenUsage?, incoming: TokenUsage): TokenUsage = TokenUsage(
        inputTokens = incoming.inputTokens ?: current?.inputTokens,
        outputTokens = incoming.outputTokens ?: current?.outputTokens,
        reasoningTokens = incoming.reasoningTokens ?: current?.reasoningTokens,
        cachedInputTokens = incoming.cachedInputTokens ?: current?.cachedInputTokens,
        cacheWriteTokens = incoming.cacheWriteTokens ?: current?.cacheWriteTokens,
        totalTokens = incoming.totalTokens ?: current?.totalTokens,
        isFinal = incoming.isFinal,
        source = incoming.source,
        requestOrdinal = incoming.requestOrdinal ?: current?.requestOrdinal,
    )
}

internal fun applyEstimatedTokenUsageFallback(message: Message): Message {
    if (message.tokenUsage != null) return message
    val snapshot = message.contextUsageSnapshot ?: return message
    return message.copy(
        tokenUsage = TokenUsage(
            inputTokens = snapshot.estimatedInputTokens,
            isFinal = true,
            source = TokenUsageSource.ESTIMATED,
        )
    )
}
