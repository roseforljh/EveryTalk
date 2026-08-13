package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.TokenUsage
import com.android.everytalk.data.network.TokenUsageSource
/**
 * 将单次 Usage 事件投影到 Message。上一事件已经保存在 Message.tokenUsage，
 * 因此这里保持纯函数，不再维护一份会随进程和清理时机漂移的旁路 Map。
 */
internal fun applyMessageTokenUsage(message: Message, event: AppStreamEvent.Usage): Message {
    val previous = message.tokenUsage
    val startsNewRequest = event.usage.requestOrdinal != null &&
        previous?.requestOrdinal != null &&
        event.usage.requestOrdinal != previous.requestOrdinal
    val current = mergeRequestUsage(previous.takeUnless { startsNewRequest }, event.usage)
    return message.copy(
        tokenUsage = current,
        contextUsageSnapshot = message.contextUsageSnapshot?.withFinalUsage(current),
    )
}

/** AgentUsage 已包含 Room 聚合出的三套最终口径，直接覆盖当前消息投影。 */
internal fun applyMessageTokenUsage(message: Message, event: AppStreamEvent.AgentUsage): Message = message.copy(
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
