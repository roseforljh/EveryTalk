package com.android.everytalk.ui.components.streaming

data class StreamingRenderState(
    val messageId: String,
    val content: String = "",
    val blocks: List<StreamBlock> = emptyList(),
    val hasPendingMath: Boolean = false,
    val blocksHash: String = "empty",
    val isStreaming: Boolean = false,
    val isComplete: Boolean = false,
    val codeBlockRanges: List<IntRange> = emptyList(),
    val mathRanges: List<IntRange> = emptyList(),
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
): StreamingRenderState {
    val parseResult = StreamBlockParser.parse(content, messageId)
    return StreamingRenderState(
        messageId = messageId,
        content = content,
        blocks = parseResult.blocks,
        hasPendingMath = parseResult.hasPendingMath,
        blocksHash = parseResult.blocksHash,
        isStreaming = isStreaming,
        isComplete = isComplete,
        codeBlockRanges = parseResult.blocks
            .filterIsInstance<StreamBlock.CodeBlock>()
            .map { it.start until it.endExclusive },
        mathRanges = parseResult.blocks
            .filter { it is StreamBlock.MathBlock || it is StreamBlock.MathInline }
            .map { it.start until it.endExclusive },
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
): Pair<StreamingRenderState, IncrementalParseCache> {
    if (content.length < cache.committedEndOffset || content.isEmpty()) {
        val state = buildStreamingRenderState(messageId, content, isStreaming, isComplete)
        val newCache = buildCacheFromBlocks(state.blocks, content.length)
        return state to newCache
    }

    val tailContent = content.substring(cache.committedEndOffset)
    val tailResult = StreamBlockParser.parseWithOffset(
        tailContent, messageId, cache.committedEndOffset, cache.lastBlockIndex
    )

    val allBlocks = cache.committedBlocks + tailResult.blocks
    val hasPendingMath = tailResult.hasPendingMath

    val hashSource = buildString {
        allBlocks.forEach {
            append(it.type.name); append('|')
            append(it.text.hashCode()); append('|')
            append(it.start); append('|')
            append(it.endExclusive); append(';')
        }
        append("pending=").append(hasPendingMath)
    }

    val state = StreamingRenderState(
        messageId = messageId,
        content = content,
        blocks = allBlocks,
        hasPendingMath = hasPendingMath,
        blocksHash = hashSource.hashCode().toString(),
        isStreaming = isStreaming,
        isComplete = isComplete,
        codeBlockRanges = allBlocks
            .filterIsInstance<StreamBlock.CodeBlock>()
            .map { it.start until it.endExclusive },
        mathRanges = allBlocks
            .filter { it is StreamBlock.MathBlock || it is StreamBlock.MathInline }
            .map { it.start until it.endExclusive },
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
