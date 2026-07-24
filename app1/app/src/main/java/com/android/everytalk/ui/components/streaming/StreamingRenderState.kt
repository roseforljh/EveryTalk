package com.android.everytalk.ui.components.streaming
import com.android.everytalk.statecontroller.*

data class StreamingRenderState(
    val messageId: String,
    val content: String = "",
    val preparedMessage: PreparedMessage = PreparedMessage(
        markdown = "",
        formulas = emptyMap(),
        hasPendingFormula = false,
        contentVersion = 0L,
    ),
    val blocks: List<StreamBlock> = emptyList(),
    val hasPendingMath: Boolean = false,
    val blocksHash: String = "empty",
    val isStreaming: Boolean = false,
    val isComplete: Boolean = false,
)

/**
 * 增量解析缓存：记录已提交（关闭）的 blocks 和对应的内容偏移量，
 * 后续只需从 committedEndOffset 开始重新解析尾部内容。
 */
data class IncrementalParseCache(
    val committedBlocks: List<StreamBlock> = emptyList(),
    val committedEndOffset: Int = 0,
    val lastContentLength: Int = 0,
    val lastBlockIndex: Int = 0,
)

internal fun buildStreamingRenderState(
    messageId: String,
    content: String,
    isStreaming: Boolean,
    isComplete: Boolean,
    contentVersion: Long = contentVersionForRendering(content),
): StreamingRenderState {
    val parseResult = StreamBlockParser.parse(content, messageId)
    val preparedMessage = StreamBlockParser.prepareMessage(
        content = content,
        blocks = parseResult.blocks,
        hasPendingFormula = parseResult.hasPendingMath,
        contentVersion = contentVersion,
        includePendingMathRaw = !isStreaming || isComplete,
    )
    return StreamingRenderState(
        messageId = messageId,
        content = content,
        preparedMessage = preparedMessage,
        blocks = parseResult.blocks,
        hasPendingMath = parseResult.hasPendingMath,
        blocksHash = parseResult.blocksHash,
        isStreaming = isStreaming,
        isComplete = isComplete,
    )
}

/**
 * 增量构建 StreamingRenderState：复用已提交的 blocks，只重新解析尾部变化部分。
 */
internal fun buildStreamingRenderStateIncremental(
    messageId: String,
    content: String,
    isStreaming: Boolean,
    isComplete: Boolean,
    cache: IncrementalParseCache,
    contentVersion: Long = contentVersionForRendering(content),
): Pair<StreamingRenderState, IncrementalParseCache> {
    if (content.length < cache.committedEndOffset || content.isEmpty()) {
        val state = buildStreamingRenderState(
            messageId = messageId,
            content = content,
            isStreaming = isStreaming,
            isComplete = isComplete,
            contentVersion = contentVersion,
        )
        val newCache = buildCacheFromBlocks(state.blocks, content.length)
        return state to newCache
    }

    val tailContent = content.substring(cache.committedEndOffset)
    val tailResult = StreamBlockParser.parseWithOffset(
        tailContent, messageId, cache.committedEndOffset, cache.lastBlockIndex
    )

    val allBlocks = cache.committedBlocks + tailResult.blocks
    val hasPendingMath = tailResult.hasPendingMath
    val preparedMessage = StreamBlockParser.prepareMessage(
        content = content,
        blocks = allBlocks,
        hasPendingFormula = hasPendingMath,
        contentVersion = contentVersion,
        includePendingMathRaw = !isStreaming || isComplete,
    )
    val state = StreamingRenderState(
        messageId = messageId,
        content = content,
        preparedMessage = preparedMessage,
        blocks = allBlocks,
        hasPendingMath = hasPendingMath,
        blocksHash = hashBlocks(allBlocks, includePending = hasPendingMath),
        isStreaming = isStreaming,
        isComplete = isComplete,
    )

    val newCache = buildCacheFromBlocks(allBlocks, content.length)
    return state to newCache
}

private fun buildCacheFromBlocks(blocks: List<StreamBlock>, contentLength: Int): IncrementalParseCache {
    if (blocks.isEmpty()) return IncrementalParseCache(lastContentLength = contentLength)

    // 除最后一个 block 外，所有已关闭的 block 都视为 committed
    val committed = if (blocks.size > 1) blocks.dropLast(1) else emptyList()
    val committedEnd = committed.lastOrNull()?.endExclusive ?: 0

    return IncrementalParseCache(
        committedBlocks = committed,
        committedEndOffset = committedEnd,
        lastContentLength = contentLength,
        lastBlockIndex = committed.size,
    )
}

private fun hashBlocks(blocks: List<StreamBlock>, includePending: Boolean): String {
    if (blocks.isEmpty()) return if (includePending) "empty:pending" else "empty"
    val hashSource = buildString {
        blocks.forEach {
            append(it.type.name); append('|')
            append(it.text.hashCode()); append('|')
            append(it.start); append('|')
            append(it.endExclusive); append(';')
        }
        append("pending=").append(includePending)
    }
    return hashSource.hashCode().toString()
}
