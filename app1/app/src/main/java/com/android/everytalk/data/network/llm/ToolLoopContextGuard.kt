package com.android.everytalk.data.network

import kotlinx.serialization.json.JsonElement

internal const val TOOL_CONTEXT_COMPRESSION_STATUS = "正在压缩上下文"

/**
 * 保留模型返回的原始事件类型和顺序。
 * Content/Text 即使出现在工具前也是正式正文，禁止再自动改成 Reasoning。
 */
internal class ToolRoundContentBuffer(
    private val emitEvent: suspend (AppStreamEvent) -> Unit,
) {
    private var pendingFinalContent: AppStreamEvent.ContentFinal? = null
    private var toolCallObserved = false
    private var streamedContentObserved = false

    suspend fun accept(event: AppStreamEvent) {
        when (event) {
            is AppStreamEvent.Content,
            is AppStreamEvent.Text,
            -> {
                streamedContentObserved = true
                emitEvent(event)
            }
            is AppStreamEvent.ContentFinal -> pendingFinalContent = event
            is AppStreamEvent.ToolCall -> {
                toolCallObserved = true
                emitEvent(event)
            }
            else -> emitEvent(event)
        }
    }

    suspend fun finish(hasToolCalls: Boolean) {
        val isToolRound = hasToolCalls || toolCallObserved
        val finalContent = pendingFinalContent
        when {
            // 部分兼容接口没有增量正文，只在结束事件给完整文字。
            // 转成普通正文事件，确保工具轮里的过程描述不会被丢掉。
            !streamedContentObserved && !finalContent?.text.isNullOrBlank() -> {
                emitEvent(AppStreamEvent.Content(finalContent.text))
            }
            !isToolRound -> finalContent?.let { emitEvent(it) }
        }
        pendingFinalContent = null
        streamedContentObserved = false
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
