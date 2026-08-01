package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.network.AppStreamEvent
import com.android.everytalk.data.network.TokenUsageAccumulator
import com.android.everytalk.data.network.TokenUsage
import com.android.everytalk.data.network.TokenUsageSource
import java.util.concurrent.ConcurrentHashMap

/** 按 AI 消息隔离 usage，避免并发流和工具循环互相污染。 */
internal class MessageTokenUsageStore {
    private val accumulators = ConcurrentHashMap<String, TokenUsageAccumulator>()

    fun apply(message: Message, event: AppStreamEvent.Usage): Message {
        val usage = accumulators
            .computeIfAbsent(message.id) { TokenUsageAccumulator() }
            .update(event.usage)
        return message.copy(
            tokenUsage = usage,
            contextUsageSnapshot = message.contextUsageSnapshot?.withFinalUsage(usage),
        )
    }

    fun remove(messageId: String) {
        accumulators.remove(messageId)
    }

    fun clear() {
        accumulators.clear()
    }
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
