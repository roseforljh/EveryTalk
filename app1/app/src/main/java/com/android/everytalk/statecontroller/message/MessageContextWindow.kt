package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.ModelTokenLimits

/**
 * 按完整对话轮次从旧到新裁剪历史，并始终保留系统消息和最新一轮。
 *
 * ponytail: 各渠道和模型没有统一 tokenizer，公共估算器使用分字符类型的保守系数。
 */
internal fun trimMessagesToContextWindow(
    messages: List<AbstractApiMessage>,
    limits: ModelTokenLimits,
    tools: List<Map<String, Any>>? = null,
    mediaTokenEstimator: (ApiContentPart) -> Long = { 4_096L },
    inputTokenCalibration: Long = 0L,
    additionalContextTokens: Long = 0L,
): List<AbstractApiMessage> {
    if (messages.isEmpty()) return messages

    val inputBudget = (limits.maxContextTokens - limits.maxOutputTokens).toLong()
    val selectedIndexes = messages.indices
        .filterTo(mutableSetOf()) { messages[it].role.equals("system", ignoreCase = true) }
    val conversationTurns = mutableListOf<MutableList<Int>>()
    var currentTurn = mutableListOf<Int>()

    messages.indices.forEach { index ->
        val message = messages[index]
        if (message.role.equals("system", ignoreCase = true)) return@forEach
        if (message.role.equals("user", ignoreCase = true) && currentTurn.isNotEmpty()) {
            conversationTurns += currentTurn
            currentTurn = mutableListOf()
        }
        currentTurn += index
    }
    if (currentTurn.isNotEmpty()) conversationTurns += currentTurn

    conversationTurns.lastOrNull()?.let(selectedIndexes::addAll)
    var usedTokens = RequestTokenEstimator.estimate(
        messages = emptyList(),
        tools = tools,
        mediaTokenEstimator = mediaTokenEstimator,
        additionalContextTokens = additionalContextTokens,
    ).totalInputTokens + inputTokenCalibration.coerceAtLeast(0L) + selectedIndexes.sumOf {
        RequestTokenEstimator.estimateMessageTokens(messages[it], mediaTokenEstimator)
    }
    for (turn in conversationTurns.dropLast(1).asReversed()) {
        val turnTokens = turn.sumOf {
            RequestTokenEstimator.estimateMessageTokens(messages[it], mediaTokenEstimator)
        }
        if (usedTokens + turnTokens > inputBudget) break
        selectedIndexes += turn
        usedTokens += turnTokens
    }

    return messages.filterIndexed { index, _ -> index in selectedIndexes }
}

internal fun estimatedApiMessageTokens(message: AbstractApiMessage): Int {
    return RequestTokenEstimator.estimateMessageTokens(message)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
}
