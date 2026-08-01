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
    val measuredInputTokens: Long? = null,
    val measuredOutputTokens: Long? = null,
    val measuredTotalTokens: Long? = null,
    val measuredUsageSource: TokenUsageSource? = null,
) {
    val estimatedInputTokens: Long
        get() = safeSum(
            systemPromptTokens,
            conversationTextTokens,
            mediaTokens,
            toolSchemaTokens,
            protocolOverheadTokens,
        )

    val displayedUsedTokens: Long
        get() = measuredTotalTokens ?: safeSum(estimatedInputTokens, reservedOutputTokens)

    val remainingTokens: Long
        get() = (contextWindowTokens - displayedUsedTokens).coerceAtLeast(0L)

    val inputEstimateDifferenceTokens: Long?
        get() = measuredInputTokens?.minus(estimatedInputTokens)

    val dataSource: ContextUsageDataSource
        get() = if (measuredTotalTokens != null) {
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
