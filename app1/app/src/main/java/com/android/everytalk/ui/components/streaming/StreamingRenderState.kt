package com.android.everytalk.ui.components.streaming

data class StreamingRenderState(
    val messageId: String,
    val content: String = "",
    val blocks: List<StreamBlock> = emptyList(),
    val hasPendingMath: Boolean = false,
    val blocksHash: String = "empty",
    val isStreaming: Boolean = false,
    val isComplete: Boolean = false,
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
    )
}
