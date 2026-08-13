package com.android.everytalk.data.network

import kotlinx.serialization.json.JsonElement

internal const val TOOL_CONTEXT_COMPRESSION_STATUS = "正在压缩上下文"
private const val TOOL_ROUND_CLASSIFICATION_WINDOW_CHARS = 64

/**
 * 暂存单轮模型正文，等本轮是否调用工具明确后再决定去向。
 * 工具轮正文属于执行说明，会转换成 reasoning；最终轮正文仍按 Content 输出。
 */
internal class ToolRoundContentBuffer(
    private val emitEvent: suspend (AppStreamEvent) -> Unit,
) {
    private val pendingContent = mutableListOf<AppStreamEvent>()
    private var pendingFinalContent: AppStreamEvent.ContentFinal? = null
    private var toolCallObserved = false
    private var contentCommitted = false
    private var pendingContentChars = 0

    suspend fun accept(event: AppStreamEvent) {
        when (event) {
            is AppStreamEvent.Content,
            is AppStreamEvent.Text,
            -> if (toolCallObserved) {
                emitAsReasoning(event)
            } else if (contentCommitted) {
                emitEvent(event)
            } else {
                pendingContent += event
                pendingContentChars += event.textLength()
                // ponytail: 只保留 64 字符分类窗口。工具轮的常见短前导语仍进入执行过程，
                // 长正文会尽快下发；若供应商先输出超长前导语再调工具，前 64 字符会作为正文。
                if (pendingContentChars >= TOOL_ROUND_CLASSIFICATION_WINDOW_CHARS) {
                    flushContent(asReasoning = false)
                    contentCommitted = true
                }
            }
            is AppStreamEvent.ContentFinal -> pendingFinalContent = event
            is AppStreamEvent.ToolCall -> {
                flushContent(asReasoning = true)
                toolCallObserved = true
                emitEvent(event)
            }
            else -> emitEvent(event)
        }
    }

    suspend fun finish(hasToolCalls: Boolean) {
        val isToolRound = hasToolCalls || toolCallObserved
        flushContent(asReasoning = isToolRound)
        if (!isToolRound) pendingFinalContent?.let { emitEvent(it) }
        pendingFinalContent = null
    }

    private suspend fun flushContent(asReasoning: Boolean) {
        pendingContent.forEach { event ->
            if (asReasoning) emitAsReasoning(event) else emitEvent(event)
        }
        pendingContent.clear()
        pendingContentChars = 0
    }

    private suspend fun emitAsReasoning(event: AppStreamEvent) {
        val text = when (event) {
            is AppStreamEvent.Content -> event.text
            is AppStreamEvent.Text -> event.text
            else -> return
        }
        if (text.isNotEmpty()) emitEvent(AppStreamEvent.Reasoning(text))
    }

    private fun AppStreamEvent.textLength(): Int = when (this) {
        is AppStreamEvent.Content -> text.length
        is AppStreamEvent.Text -> text.length
        else -> 0
    }
}

internal fun estimateToolLoopJsonTokens(element: JsonElement): Long =
    estimateToolLoopTextTokens(element.toString())

internal fun estimateToolLoopTextTokens(text: String): Long {
    var compactAscii = 0L
    var structuralAscii = 0L
    var nonAscii = 0L
    text.forEach { character ->
        when {
            character.code > 0x7f -> nonAscii++
            character.isLetterOrDigit() || character.isWhitespace() -> compactAscii++
            else -> structuralAscii++
        }
    }
    return compactAscii.ceilDiv(4L) + structuralAscii.ceilDiv(2L) + nonAscii
}

internal fun truncateToolOutput(text: String, maxTokens: Long): String {
    if (estimateToolLoopTextTokens(text) <= maxTokens) return text
    val marker = "\n…工具输出已截断…\n"
    if (estimateToolLoopTextTokens(marker) > maxTokens) return marker.trim()

    var low = 0
    var high = text.length
    var best = marker
    while (low <= high) {
        val bodyChars = low + (high - low) / 2
        val headChars = bodyChars / 2
        val candidate = text.take(headChars) + marker + text.takeLast(bodyChars - headChars)
        if (estimateToolLoopTextTokens(candidate) <= maxTokens) {
            best = candidate
            low = bodyChars + 1
        } else {
            high = bodyChars - 1
        }
    }
    return best
}

private fun Long.ceilDiv(divisor: Long): Long =
    if (this == 0L) 0L else (this + divisor - 1L) / divisor
