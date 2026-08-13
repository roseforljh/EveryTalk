package com.android.everytalk.data.DataClass

import com.android.everytalk.data.network.TokenUsage
import com.android.everytalk.data.network.TokenUsageSource
import kotlinx.serialization.Serializable

@Serializable
enum class ContextUsageDataSource {
    ESTIMATED,
    MEASURED,
}

/** 同一次模型请求的发送前估算和服务端最终实测。 */
@Serializable
data class ContextUsageSnapshot(
    val messageId: String,
    val configId: String? = null,
    val systemPromptTokens: Long,
    val conversationTextTokens: Long,
    val mediaTokens: Long,
    val toolSchemaTokens: Long,
    val protocolOverheadTokens: Long,
    val reservedOutputTokens: Long,
    val contextWindowTokens: Long,
    val inputCalibrationTokens: Long = 0L,
    val measuredInputTokens: Long? = null,
    val measuredOutputTokens: Long? = null,
    val measuredTotalTokens: Long? = null,
    val measuredUsageSource: TokenUsageSource? = null,
    val activeContextTokensOverride: Long? = null,
    val agentRunInputTokens: Long? = null,
    val agentRunOutputTokens: Long? = null,
    val agentRunTotalTokens: Long? = null,
    val agentRequestCount: Int? = null,
    val conversationLifetimeTokens: Long? = null,
) {
    val uncalibratedEstimatedInputTokens: Long
        get() = safeSum(
            systemPromptTokens,
            conversationTextTokens,
            mediaTokens,
            toolSchemaTokens,
            protocolOverheadTokens,
        )

    val estimatedInputTokens: Long
        get() = safeAddSigned(
            uncalibratedEstimatedInputTokens,
            inputCalibrationTokens,
        ).coerceAtLeast(0L)

    val displayedUsedTokens: Long
        get() = activeContextTokensOverride
            ?: measuredTotalTokens
            ?: safeSum(estimatedInputTokens, reservedOutputTokens)

    val remainingTokens: Long
        get() = (contextWindowTokens - displayedUsedTokens).coerceAtLeast(0L)

    val inputEstimateDifferenceTokens: Long?
        get() = measuredInputTokens?.minus(estimatedInputTokens)

    val dataSource: ContextUsageDataSource
        get() = if (
            measuredTotalTokens != null &&
            measuredUsageSource != null &&
            measuredUsageSource != TokenUsageSource.ESTIMATED
        ) {
            ContextUsageDataSource.MEASURED
        } else {
            ContextUsageDataSource.ESTIMATED
        }

    fun withFinalUsage(usage: TokenUsage): ContextUsageSnapshot {
        if (!usage.isFinal) return this
        val measuredTotal = usage.totalTokens ?: safeSumNullable(
            usage.inputTokens,
            usage.outputTokens,
        )
        return copy(
            measuredInputTokens = usage.inputTokens,
            measuredOutputTokens = usage.outputTokens,
            measuredTotalTokens = measuredTotal,
            measuredUsageSource = usage.source,
        )
    }

    fun withActiveContextOverride(tokens: Long?): ContextUsageSnapshot = copy(
        activeContextTokensOverride = tokens?.coerceAtLeast(0L),
    )

    fun withAgentUsage(
        usage: TokenUsage,
        runInputTokens: Long,
        runOutputTokens: Long,
        runTotalTokens: Long,
        requestCount: Int,
        conversationTotalTokens: Long,
    ): ContextUsageSnapshot = withFinalUsage(usage).copy(
        agentRunInputTokens = runInputTokens.coerceAtLeast(0L),
        agentRunOutputTokens = runOutputTokens.coerceAtLeast(0L),
        agentRunTotalTokens = runTotalTokens.coerceAtLeast(0L),
        agentRequestCount = requestCount.coerceAtLeast(0),
        conversationLifetimeTokens = conversationTotalTokens.coerceAtLeast(0L),
    )
}

private fun safeSum(vararg values: Long): Long = values.fold(0L) { total, value ->
    if (value > Long.MAX_VALUE - total) Long.MAX_VALUE else total + value
}

private fun safeSumNullable(left: Long?, right: Long?): Long? = when {
    left == null -> right
    right == null -> left
    left > Long.MAX_VALUE - right -> Long.MAX_VALUE
    else -> left + right
}

private fun safeAddSigned(left: Long, right: Long): Long = when {
    right > 0L && left > Long.MAX_VALUE - right -> Long.MAX_VALUE
    right < 0L && left < Long.MIN_VALUE - right -> Long.MIN_VALUE
    else -> left + right
}
