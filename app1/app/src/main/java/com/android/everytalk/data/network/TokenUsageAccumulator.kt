package com.android.everytalk.data.network

/**
 * 汇总同一条 AI 消息在工具循环中的 usage。
 *
 * 同一轮请求内，最终事件覆盖临时事件中已返回的字段；新的非最终事件才会开启下一轮请求。
 */
class TokenUsageAccumulator {
    private var completedPreviousRounds: TokenUsage? = null
    private var currentUsage: TokenUsage? = null
    private var currentRoundFinalized = false

    fun update(incoming: TokenUsage): TokenUsage {
        if (!incoming.isFinal) {
            if (currentRoundFinalized) {
                currentUsage?.let { completedRound ->
                    completedPreviousRounds = combine(
                        completedPreviousRounds,
                        completedRound,
                        isFinal = true,
                    )
                }
                currentUsage = null
                currentRoundFinalized = false
            }
            currentUsage = mergeCurrent(currentUsage, incoming)
            return combine(completedPreviousRounds, currentUsage!!, isFinal = false)
        }

        val finalizedRound = mergeCurrent(currentUsage, incoming).copy(isFinal = true)
        currentUsage = finalizedRound
        currentRoundFinalized = true
        return combine(completedPreviousRounds, finalizedRound, isFinal = true)
    }

    private fun mergeCurrent(current: TokenUsage?, incoming: TokenUsage): TokenUsage = TokenUsage(
        inputTokens = incoming.inputTokens.nonNegativeOrNull() ?: current?.inputTokens,
        outputTokens = incoming.outputTokens.nonNegativeOrNull() ?: current?.outputTokens,
        reasoningTokens = incoming.reasoningTokens.nonNegativeOrNull() ?: current?.reasoningTokens,
        cachedInputTokens = incoming.cachedInputTokens.nonNegativeOrNull() ?: current?.cachedInputTokens,
        cacheWriteTokens = incoming.cacheWriteTokens.nonNegativeOrNull() ?: current?.cacheWriteTokens,
        totalTokens = incoming.totalTokens.nonNegativeOrNull() ?: current?.totalTokens,
        isFinal = incoming.isFinal,
        source = incoming.source,
    )

    private fun combine(
        completed: TokenUsage?,
        current: TokenUsage,
        isFinal: Boolean,
    ): TokenUsage {
        if (completed == null) return current.copy(isFinal = isFinal)
        return TokenUsage(
            inputTokens = addKnown(completed.inputTokens, current.inputTokens),
            outputTokens = addKnown(completed.outputTokens, current.outputTokens),
            reasoningTokens = addKnown(completed.reasoningTokens, current.reasoningTokens),
            cachedInputTokens = addKnown(completed.cachedInputTokens, current.cachedInputTokens),
            cacheWriteTokens = addKnown(completed.cacheWriteTokens, current.cacheWriteTokens),
            totalTokens = addKnown(completed.totalTokens, current.totalTokens),
            isFinal = isFinal,
            source = current.source,
        )
    }

    private fun addKnown(left: Long?, right: Long?): Long? = when {
        left == null -> right
        right == null -> left
        left > Long.MAX_VALUE - right -> Long.MAX_VALUE
        else -> left + right
    }

    private fun Long?.nonNegativeOrNull(): Long? = this?.coerceAtLeast(0L)
}
