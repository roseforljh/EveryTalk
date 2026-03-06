package com.android.everytalk.ui.components.streaming

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.ui.components.EnhancedMarkdownText
import com.android.everytalk.ui.components.StableMarkdownText

private sealed interface RenderSegment {
    val stableId: String

    data class InlineText(
        override val stableId: String,
        val text: String,
    ) : RenderSegment

    data class BlockOnly(
        override val stableId: String,
        val block: StreamBlock,
    ) : RenderSegment
}

@Composable
fun StreamBlocksRenderer(
    message: Message,
    blocks: List<StreamBlock>,
    style: TextStyle,
    color: Color,
    messageOutputType: String,
    viewModel: AppViewModel,
    onLongPress: () -> Unit,
    onImageClick: ((String) -> Unit)? = null,
    onCodePreviewRequested: ((String, String) -> Unit)? = null,
    onCodeCopied: (() -> Unit)? = null,
) {
    val segments = remember(blocks, messageOutputType) {
        if (blocks.isEmpty() || messageOutputType == "code") {
            emptyList()
        } else {
            buildSegments(blocks)
        }
    }

    if (segments.isEmpty()) {
        EnhancedMarkdownText(
            message = message,
            style = style,
            color = color,
            isStreaming = false,
            messageOutputType = messageOutputType,
            onLongPress = { _ -> onLongPress() },
            onImageClick = onImageClick,
            onCodePreviewRequested = onCodePreviewRequested,
            onCodeCopied = onCodeCopied,
            viewModel = viewModel,
            disableStreamingSubscription = true,
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        segments.forEach { segment ->
            key(segment.stableId) {
                when (segment) {
                    is RenderSegment.InlineText -> {
                        SegmentMarkdown(
                            message = message,
                            segmentId = segment.stableId,
                            text = segment.text,
                            style = style,
                            color = color,
                            messageOutputType = messageOutputType,
                            viewModel = viewModel,
                            onLongPress = onLongPress,
                            onImageClick = onImageClick,
                            onCodePreviewRequested = onCodePreviewRequested,
                            onCodeCopied = onCodeCopied,
                        )
                    }

                    is RenderSegment.BlockOnly -> {
                        when (val block = segment.block) {
                            is StreamBlock.MathInline -> {
                                if (block.state == MathBlockState.RAW) {
                                    StableMarkdownText(
                                        markdown = block.text,
                                        style = style,
                                    )
                                } else {
                                    SegmentMarkdown(
                                        message = message,
                                        segmentId = block.stableId,
                                        text = block.text,
                                        style = style,
                                        color = color,
                                        messageOutputType = messageOutputType,
                                        viewModel = viewModel,
                                        onLongPress = onLongPress,
                                        onImageClick = onImageClick,
                                        onCodePreviewRequested = onCodePreviewRequested,
                                        onCodeCopied = onCodeCopied,
                                    )
                                }
                            }

                            is StreamBlock.MathBlock -> {
                                if (block.state == MathBlockState.RAW) {
                                    StableMarkdownText(
                                        markdown = block.text,
                                        style = style,
                                    )
                                } else {
                                    SegmentMarkdown(
                                        message = message,
                                        segmentId = block.stableId,
                                        text = block.text,
                                        style = style,
                                        color = color,
                                        messageOutputType = messageOutputType,
                                        viewModel = viewModel,
                                        onLongPress = onLongPress,
                                        onImageClick = onImageClick,
                                        onCodePreviewRequested = onCodePreviewRequested,
                                        onCodeCopied = onCodeCopied,
                                    )
                                }
                            }

                            else -> {
                                SegmentMarkdown(
                                    message = message,
                                    segmentId = block.stableId,
                                    text = block.text,
                                    style = style,
                                    color = color,
                                    messageOutputType = messageOutputType,
                                    viewModel = viewModel,
                                    onLongPress = onLongPress,
                                    onImageClick = onImageClick,
                                    onCodePreviewRequested = onCodePreviewRequested,
                                    onCodeCopied = onCodeCopied,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentMarkdown(
    message: Message,
    segmentId: String,
    text: String,
    style: TextStyle,
    color: Color,
    messageOutputType: String,
    viewModel: AppViewModel,
    onLongPress: () -> Unit,
    onImageClick: ((String) -> Unit)?,
    onCodePreviewRequested: ((String, String) -> Unit)?,
    onCodeCopied: (() -> Unit)?,
) {
    val segmentMessage = remember(segmentId, text) {
        message.copy(text = text)
    }

    EnhancedMarkdownText(
        message = segmentMessage,
        style = style,
        color = color,
        isStreaming = false,
        messageOutputType = messageOutputType,
        onLongPress = { _ -> onLongPress() },
        onImageClick = onImageClick,
        onCodePreviewRequested = onCodePreviewRequested,
        onCodeCopied = onCodeCopied,
        viewModel = viewModel,
        contentOverride = text,
        contentKeyOverride = "${message.id}:$segmentId",
        disableStreamingSubscription = true,
    )
}

private fun buildSegments(blocks: List<StreamBlock>): List<RenderSegment> {
    val result = mutableListOf<RenderSegment>()
    val inlineBuffer = StringBuilder()
    var inlineStartId: String? = null
    var inlineEndId: String? = null

    fun flushInline() {
        if (inlineBuffer.isEmpty()) return
        val stableId = buildString {
            append(inlineStartId ?: "inline")
            append(':')
            append(inlineEndId ?: inlineStartId ?: "tail")
        }
        result.add(RenderSegment.InlineText(stableId = stableId, text = inlineBuffer.toString()))
        inlineBuffer.setLength(0)
        inlineStartId = null
        inlineEndId = null
    }

    blocks.forEach { block ->
        when (block) {
            is StreamBlock.PlainText,
            is StreamBlock.MathInline -> {
                if (inlineStartId == null) inlineStartId = block.stableId
                inlineEndId = block.stableId
                inlineBuffer.append(block.text)
            }

            else -> {
                flushInline()
                result.add(RenderSegment.BlockOnly(stableId = block.stableId, block = block))
            }
        }
    }

    flushInline()
    return result
}
