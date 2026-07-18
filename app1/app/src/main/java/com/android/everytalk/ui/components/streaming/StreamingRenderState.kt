package com.android.everytalk.ui.components.streaming

data class StreamingRenderState(
    val messageId: String,
    val content: String = "",
    val blocks: List<StreamBlock> = emptyList(),
    val committedBlocks: List<StreamBlock> = emptyList(),
    val tailBlocks: List<StreamBlock> = emptyList(),
    val nativeMarkdownBlocks: List<NativeStreamingMarkdownBlock> = emptyList(),
    val committedNativeMarkdownBlocks: List<NativeStreamingMarkdownBlock> = emptyList(),
    val tailNativeMarkdownBlocks: List<NativeStreamingMarkdownBlock> = emptyList(),
    val hasPendingMath: Boolean = false,
    val blocksHash: String = "empty",
    val committedBlocksHash: String = "empty",
    val tailBlocksHash: String = "empty",
    val nativeMarkdownBlocksHash: String = "empty",
    val committedNativeMarkdownBlocksHash: String = "empty",
    val tailNativeMarkdownBlocksHash: String = "empty",
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
    val split = splitBlocksForRender(parseResult.blocks, isComplete)
    val nativeMarkdownBlocks = parseUnifiedStreamingMarkdownBlocks(content, messageId)
    val nativeSplit = splitNativeBlocksForRender(nativeMarkdownBlocks, isComplete)
    return StreamingRenderState(
        messageId = messageId,
        content = content,
        blocks = parseResult.blocks,
        committedBlocks = split.committedBlocks,
        tailBlocks = split.tailBlocks,
        nativeMarkdownBlocks = nativeMarkdownBlocks,
        committedNativeMarkdownBlocks = nativeSplit.committedBlocks,
        tailNativeMarkdownBlocks = nativeSplit.tailBlocks,
        hasPendingMath = parseResult.hasPendingMath,
        blocksHash = parseResult.blocksHash,
        committedBlocksHash = hashBlocks(split.committedBlocks, includePending = false),
        tailBlocksHash = hashBlocks(split.tailBlocks, includePending = parseResult.hasPendingMath),
        nativeMarkdownBlocksHash = hashNativeBlocks(nativeMarkdownBlocks),
        committedNativeMarkdownBlocksHash = hashNativeBlocks(nativeSplit.committedBlocks),
        tailNativeMarkdownBlocksHash = hashNativeBlocks(nativeSplit.tailBlocks),
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
    val split = splitBlocksForRender(allBlocks, isComplete)
    val nativeMarkdownBlocks = parseUnifiedStreamingMarkdownBlocks(content, messageId)
    val nativeSplit = splitNativeBlocksForRender(nativeMarkdownBlocks, isComplete)

    val state = StreamingRenderState(
        messageId = messageId,
        content = content,
        blocks = allBlocks,
        committedBlocks = split.committedBlocks,
        tailBlocks = split.tailBlocks,
        nativeMarkdownBlocks = nativeMarkdownBlocks,
        committedNativeMarkdownBlocks = nativeSplit.committedBlocks,
        tailNativeMarkdownBlocks = nativeSplit.tailBlocks,
        hasPendingMath = hasPendingMath,
        blocksHash = hashBlocks(allBlocks, includePending = hasPendingMath),
        committedBlocksHash = hashBlocks(split.committedBlocks, includePending = false),
        tailBlocksHash = hashBlocks(split.tailBlocks, includePending = hasPendingMath),
        nativeMarkdownBlocksHash = hashNativeBlocks(nativeMarkdownBlocks),
        committedNativeMarkdownBlocksHash = hashNativeBlocks(nativeSplit.committedBlocks),
        tailNativeMarkdownBlocksHash = hashNativeBlocks(nativeSplit.tailBlocks),
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

private data class RenderBlockSplit(
    val committedBlocks: List<StreamBlock>,
    val tailBlocks: List<StreamBlock>,
)

private data class NativeRenderBlockSplit(
    val committedBlocks: List<NativeStreamingMarkdownBlock>,
    val tailBlocks: List<NativeStreamingMarkdownBlock>,
)

private fun splitBlocksForRender(blocks: List<StreamBlock>, isComplete: Boolean): RenderBlockSplit {
    if (blocks.isEmpty()) return RenderBlockSplit(emptyList(), emptyList())
    if (isComplete) return RenderBlockSplit(committedBlocks = blocks, tailBlocks = emptyList())
    if (blocks.size == 1) return RenderBlockSplit(committedBlocks = emptyList(), tailBlocks = blocks)
    return RenderBlockSplit(
        committedBlocks = blocks.dropLast(1),
        tailBlocks = blocks.takeLast(1),
    )
}

private fun splitNativeBlocksForRender(
    blocks: List<NativeStreamingMarkdownBlock>,
    isComplete: Boolean,
): NativeRenderBlockSplit {
    if (blocks.isEmpty()) return NativeRenderBlockSplit(emptyList(), emptyList())
    if (isComplete) return NativeRenderBlockSplit(committedBlocks = blocks, tailBlocks = emptyList())
    if (blocks.size == 1) return NativeRenderBlockSplit(committedBlocks = emptyList(), tailBlocks = blocks)
    return NativeRenderBlockSplit(
        committedBlocks = blocks.dropLast(1),
        tailBlocks = blocks.takeLast(1),
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

private fun hashNativeBlocks(blocks: List<NativeStreamingMarkdownBlock>): String {
    if (blocks.isEmpty()) return "empty"
    val hashSource = buildString {
        blocks.forEach {
            append(it.type.name); append('|')
            append(it.text.hashCode()); append('|')
            append(it.items.hashCode()); append('|')
            append(it.listItems.hashCode()); append('|')
            append(it.children.hashCode()); append('|')
            append(it.textAlign); append('|')
            append(it.start); append('|')
            append(it.endExclusive); append(';')
        }
    }
    return hashSource.hashCode().toString()
}
