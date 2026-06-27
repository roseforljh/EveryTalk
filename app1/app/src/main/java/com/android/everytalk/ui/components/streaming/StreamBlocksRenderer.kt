package com.android.everytalk.ui.components.streaming

import android.content.ClipData
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.ui.components.ChatMarkdownTextStyle
import com.android.everytalk.ui.components.ProportionalAsyncImage
import com.android.everytalk.ui.components.content.CodeBlockCard
import com.android.everytalk.ui.components.markdown.MarkdownRenderer
import com.android.everytalk.ui.components.markdown.StableLatexRenderer
import com.android.everytalk.ui.components.math.MathInline
import com.android.everytalk.ui.components.table.InlineMarkdownParser
import com.android.everytalk.ui.components.table.TableRenderer
import com.android.everytalk.ui.components.table.TableUtils
import com.android.everytalk.ui.components.table.parseTableCellMarkdownParts
import com.android.everytalk.ui.components.table.stripMarkdownDestinationAngleBrackets
import java.util.Locale
import kotlinx.coroutines.launch

private sealed interface RenderSegment {
    val stableId: String

    data class InlineText(
        override val stableId: String,
        val text: String,
    ) : RenderSegment

    data class InlineParts(
        override val stableId: String,
        val parts: List<InlineRenderPart>,
    ) : RenderSegment

    data class BlockOnly(
        override val stableId: String,
        val block: StreamBlock,
    ) : RenderSegment
}

internal sealed interface InlineRenderPart {
    data class Text(val text: String) : InlineRenderPart
    data class Math(val block: StreamBlock.MathInline) : InlineRenderPart
}

internal data class InlinePartsTextModel(
    val annotatedText: AnnotatedString,
    val mathPlaceholders: List<InlineMathPlaceholder>,
)

internal data class InlineMathPlaceholder(
    val id: String,
    val latex: String,
    val width: TextUnit,
    val height: TextUnit,
)

internal enum class StreamingMarkdownRenderPath {
    PlainText,
    ComposeInlineMarkdown,
    ComposeBlockMarkdown,
    FullMarkdown,
}

internal enum class StreamMathRenderPath {
    RawText,
    NativeLatex,
}

internal enum class StreamBlockOnlyRenderPath {
    PlainText,
    NativeInlineParts,
    NativeLatex,
    CodeBlock,
}

internal fun resolveStreamMathRenderPath(block: StreamBlock.MathBlock): StreamMathRenderPath {
    return when (block.state) {
        MathBlockState.RENDERED -> StreamMathRenderPath.NativeLatex
        MathBlockState.RAW,
        MathBlockState.PARSING,
        MathBlockState.FAILED -> StreamMathRenderPath.RawText
    }
}

internal fun resolveBlockOnlyRenderPath(block: StreamBlock): StreamBlockOnlyRenderPath {
    return when (block) {
        is StreamBlock.MathInline -> when (block.state) {
            MathBlockState.RENDERED -> StreamBlockOnlyRenderPath.NativeInlineParts
            MathBlockState.RAW,
            MathBlockState.PARSING,
            MathBlockState.FAILED -> StreamBlockOnlyRenderPath.PlainText
        }
        is StreamBlock.MathBlock -> when (resolveStreamMathRenderPath(block)) {
            StreamMathRenderPath.RawText -> StreamBlockOnlyRenderPath.PlainText
            StreamMathRenderPath.NativeLatex -> StreamBlockOnlyRenderPath.NativeLatex
        }
        is StreamBlock.CodeBlock -> StreamBlockOnlyRenderPath.CodeBlock
        else -> StreamBlockOnlyRenderPath.PlainText
    }
}

internal fun shouldBuildStreamSegmentsForOutputType(messageOutputType: String): Boolean = true

internal fun streamMarkdownLongPressHandler(
    sender: Sender,
    onLongPress: (() -> Unit)?,
): ((Offset) -> Unit)? {
    if (sender == Sender.AI) return null
    return onLongPress?.let { callback ->
        { callback() }
    }
}

enum class NativeStreamingMarkdownBlockType {
    Paragraph,
    Heading,
    HorizontalRule,
    BlockQuote,
    ListBlock,
    CodeBlock,
    MathBlock,
    Table,
    Image,
}

data class NativeStreamingMarkdownBlock(
    val stableId: String,
    val type: NativeStreamingMarkdownBlockType,
    val start: Int = 0,
    val endExclusive: Int = 0,
    val text: String = "",
    val level: Int = 0,
    val ordered: Boolean = false,
    val items: List<String> = emptyList(),
    val listItems: List<NativeStreamingListItem> = emptyList(),
    val language: String? = null,
    val code: String = "",
    val tableLines: List<String> = emptyList(),
    val imageAlt: String = "",
    val imageUrl: String = "",
    val children: List<NativeStreamingMarkdownBlock> = emptyList(),
)

data class NativeStreamingListItem(
    val text: String,
    val level: Int = 0,
    val ordered: Boolean = false,
    val number: Int = 1,
    val children: List<NativeStreamingMarkdownBlock> = emptyList(),
)

internal data class ChatGptHeadingTextSpec(
    val fontSize: TextUnit,
    val lineHeight: TextUnit,
    val fontWeight: FontWeight,
    val colorAlpha: Float,
    val fontStyle: FontStyle?,
)

internal fun chatGptHeadingTextSpecForLevel(level: Int): ChatGptHeadingTextSpec {
    val headingLevel = level.coerceIn(1, 6)
    return ChatGptHeadingTextSpec(
        fontSize = ChatMarkdownTextStyle.headingFontSizeSp(headingLevel).sp,
        lineHeight = ChatMarkdownTextStyle.headingLineHeightSp(headingLevel).sp,
        fontWeight = if (headingLevel <= 5) FontWeight.W700 else FontWeight.Normal,
        colorAlpha = when (headingLevel) {
            2, 4 -> 0.7f
            5 -> 0.5f
            else -> 1f
        },
        fontStyle = if (headingLevel == 3) FontStyle.Italic else null,
    )
}

private const val CHATGPT_CODE_BLOCK_BACKGROUND_HEX = 0xFFCCCCCC
private const val CHATGPT_CODE_BLOCK_BACKGROUND_ALPHA = 0.5f
private const val CHATGPT_CODE_BLOCK_PADDING_DP = 16f
private const val MAX_NATIVE_BLOCK_QUOTE_DEPTH = 4

internal fun chatInlineCodeBackgroundColor(surfaceVariant: Color, isDark: Boolean): Color {
    return surfaceVariant.copy(
        alpha = if (isDark) {
            ChatMarkdownTextStyle.INLINE_CODE_BACKGROUND_DARK_ALPHA
        } else {
            ChatMarkdownTextStyle.INLINE_CODE_BACKGROUND_LIGHT_ALPHA
        },
    )
}

internal fun chatInlineCodeTextColor(isDark: Boolean): Color {
    return if (isDark) {
        Color(0xFFD1D5DB)
    } else {
        Color(0xFF4F5661)
    }
}

internal fun chatInlineCodeFontSize(baseFontSize: TextUnit): TextUnit {
    return if (baseFontSize == TextUnit.Unspecified) {
        TextUnit.Unspecified
    } else {
        baseFontSize * ChatMarkdownTextStyle.INLINE_CODE_RELATIVE_SIZE
    }
}

@Composable
fun StreamBlocksRenderer(
    message: Message,
    blocks: List<StreamBlock>,
    committedBlocks: List<StreamBlock> = emptyList(),
    tailBlocks: List<StreamBlock> = emptyList(),
    committedBlocksHash: String = "",
    tailBlocksHash: String = "",
    nativeMarkdownBlocks: List<NativeStreamingMarkdownBlock> = emptyList(),
    committedNativeMarkdownBlocks: List<NativeStreamingMarkdownBlock> = emptyList(),
    tailNativeMarkdownBlocks: List<NativeStreamingMarkdownBlock> = emptyList(),
    nativeMarkdownBlocksHash: String = "",
    committedNativeMarkdownBlocksHash: String = "",
    tailNativeMarkdownBlocksHash: String = "",
    style: TextStyle,
    color: Color,
    messageOutputType: String,
    viewModel: AppViewModel? = null,
    isStreaming: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onImageClick: ((String) -> Unit)? = null,
    onCodePreviewRequested: ((String, String) -> Unit)? = null,
    onCodeCopied: (() -> Unit)? = null,
) {
    val hasNativeSplitBlocks = committedNativeMarkdownBlocks.isNotEmpty() || tailNativeMarkdownBlocks.isNotEmpty()
    val nativeBlocks = remember(
        nativeMarkdownBlocksHash,
        committedNativeMarkdownBlocksHash,
        tailNativeMarkdownBlocksHash,
        nativeMarkdownBlocks,
        committedNativeMarkdownBlocks,
        tailNativeMarkdownBlocks,
    ) {
        when {
            hasNativeSplitBlocks -> committedNativeMarkdownBlocks + tailNativeMarkdownBlocks
            else -> nativeMarkdownBlocks
        }
    }

    if (nativeBlocks.isNotEmpty()) {
        StreamTextSelectionContainer(enabled = message.sender == Sender.AI) {
            NativeMarkdownBlocksSegment(
                blocks = nativeBlocks,
                style = style,
                color = color,
                isStreaming = isStreaming,
                onCodePreviewRequested = onCodePreviewRequested,
                onCodeCopied = onCodeCopied,
                onImageClick = onImageClick,
            )
        }
        return
    }

    val hasSplitBlocks = committedBlocks.isNotEmpty() || tailBlocks.isNotEmpty()
    val shouldBuildSegments = shouldBuildStreamSegmentsForOutputType(messageOutputType)
    val committedSegments = remember(committedBlocksHash, committedBlocks, shouldBuildSegments) {
        if (!hasSplitBlocks || committedBlocks.isEmpty() || !shouldBuildSegments) {
            emptyList()
        } else {
            buildSegments(committedBlocks)
        }
    }
    val tailSegments = remember(tailBlocksHash, tailBlocks, shouldBuildSegments) {
        if (!hasSplitBlocks || tailBlocks.isEmpty() || !shouldBuildSegments) {
            emptyList()
        } else {
            buildSegments(tailBlocks)
        }
    }
    val fallbackSegments = remember(blocks, shouldBuildSegments) {
        if (blocks.isEmpty() || !shouldBuildSegments) {
            emptyList()
        } else {
            buildSegments(blocks)
        }
    }
    val segments = if (hasSplitBlocks) committedSegments + tailSegments else fallbackSegments
    val renderPhaseKey = if (hasSplitBlocks) {
        "$committedBlocksHash:$tailBlocksHash"
    } else {
        blocks.hashCode().toString()
    }

    if (segments.isEmpty()) {
        if (message.text.isNotBlank()) {
            StreamTextSelectionContainer(enabled = message.sender == Sender.AI) {
                PlainTextSegment(
                    text = message.text,
                    style = style,
                    color = color,
                )
            }
        }
        return
    }

    StreamTextSelectionContainer(enabled = message.sender == Sender.AI) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            segments.forEach { segment ->
                key(segment.stableId) {
                    when (segment) {
                        is RenderSegment.InlineText -> {
                            when (resolveStreamingMarkdownRenderPath(segment.text)) {
                                StreamingMarkdownRenderPath.PlainText -> {
                                    PlainTextSegment(
                                        text = segment.text,
                                        style = style,
                                        color = color,
                                    )
                                }
                                StreamingMarkdownRenderPath.ComposeInlineMarkdown -> {
                                    ComposeInlineMarkdownSegment(
                                        text = segment.text,
                                        style = style,
                                        color = color,
                                    )
                                }
                                StreamingMarkdownRenderPath.ComposeBlockMarkdown -> {
                                    val nativeBlocks = remember(segment.stableId, segment.text) {
                                        parseNativeStreamingMarkdownBlocks(
                                            text = segment.text,
                                            segmentId = segment.stableId,
                                        )
                                    }
                                    if (nativeBlocks != null) {
                                        NativeMarkdownBlocksSegment(
                                            blocks = nativeBlocks,
                                            style = style,
                                            color = color,
                                            isStreaming = isStreaming,
                                            onCodePreviewRequested = onCodePreviewRequested,
                                            onCodeCopied = onCodeCopied,
                                            onImageClick = onImageClick,
                                        )
                                    } else {
                                        PlainTextSegment(
                                            text = segment.text,
                                            style = style,
                                            color = color,
                                        )
                                    }
                                }
                                StreamingMarkdownRenderPath.FullMarkdown -> {
                                    val nativeBlocks = remember(segment.stableId, segment.text) {
                                        parseNativeStreamingMarkdownBlocks(
                                            text = segment.text,
                                            segmentId = segment.stableId,
                                        )
                                    }
                                    if (nativeBlocks != null) {
                                        NativeMarkdownBlocksSegment(
                                            blocks = nativeBlocks,
                                            style = style,
                                            color = color,
                                            isStreaming = isStreaming,
                                            onCodePreviewRequested = onCodePreviewRequested,
                                            onCodeCopied = onCodeCopied,
                                            onImageClick = onImageClick,
                                        )
                                    } else {
                                        FullMarkdownFallbackSegment(
                                            text = segment.text,
                                            stableId = segment.stableId,
                                            style = style,
                                            color = color,
                                            isStreaming = isStreaming,
                                            sender = message.sender,
                                            onLongPress = onLongPress,
                                            onImageClick = onImageClick,
                                        )
                                    }
                                }
                            }
                        }

                        is RenderSegment.InlineParts -> {
                            InlinePartsSegment(
                                parts = segment.parts,
                                style = style,
                                color = color,
                            )
                        }

                        is RenderSegment.BlockOnly -> {
                            when (val block = segment.block) {
                                is StreamBlock.MathInline -> when (resolveBlockOnlyRenderPath(block)) {
                                    StreamBlockOnlyRenderPath.PlainText -> {
                                        PlainTextSegment(
                                            text = block.text,
                                            style = style,
                                            color = color,
                                        )
                                    }
                                    StreamBlockOnlyRenderPath.NativeInlineParts -> {
                                        InlinePartsSegment(
                                            parts = listOf(InlineRenderPart.Math(block)),
                                            style = style,
                                            color = color,
                                        )
                                    }
                                    StreamBlockOnlyRenderPath.NativeLatex,
                                    StreamBlockOnlyRenderPath.CodeBlock -> {
                                        PlainTextSegment(
                                            text = block.text,
                                            style = style,
                                            color = color,
                                        )
                                    }
                                }

                                is StreamBlock.MathBlock -> when (resolveBlockOnlyRenderPath(block)) {
                                    StreamBlockOnlyRenderPath.PlainText -> {
                                        PlainTextSegment(
                                            text = block.text,
                                            style = style,
                                            color = color,
                                        )
                                    }
                                    StreamBlockOnlyRenderPath.NativeLatex -> {
                                        StableLatexRenderer(
                                            latex = block.text,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 6.dp),
                                            style = style,
                                            color = color,
                                            contentKey = block.stableId,
                                        )
                                    }
                                    StreamBlockOnlyRenderPath.CodeBlock,
                                    StreamBlockOnlyRenderPath.NativeInlineParts -> {
                                        PlainTextSegment(
                                            text = block.text,
                                            style = style,
                                            color = color,
                                        )
                                    }
                                }

                                is StreamBlock.CodeBlock -> {
                                    CodeBlockSegment(
                                        block = block,
                                        isStreaming = isStreaming,
                                        onCodePreviewRequested = onCodePreviewRequested,
                                        onCodeCopied = onCodeCopied,
                                    )
                                }

                                else -> {
                                    PlainTextSegment(
                                        text = block.text,
                                        style = style,
                                        color = color,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamTextSelectionContainer(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (enabled) {
        SelectionContainer {
            content()
        }
    } else {
        content()
    }
}

@Composable
private fun CodeBlockSegment(
    block: StreamBlock.CodeBlock,
    isStreaming: Boolean,
    onCodePreviewRequested: ((String, String) -> Unit)?,
    onCodeCopied: (() -> Unit)?,
) {
    val codeBlock = remember(block.text) {
        extractFencedCodeBlockContent(block.text)
    }
    CodeBlockCardSegment(
        language = codeBlock.language,
        code = codeBlock.code,
        isStreaming = isStreaming,
        onCodePreviewRequested = onCodePreviewRequested,
        onCodeCopied = onCodeCopied,
    )
}

@Composable
private fun CodeBlockCardSegment(
    language: String?,
    code: String,
    isStreaming: Boolean,
    onCodePreviewRequested: ((String, String) -> Unit)?,
    onCodeCopied: (() -> Unit)?,
) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    CodeBlockCard(
        language = language,
        code = code,
        modifier = Modifier.padding(vertical = 4.dp),
        isStreaming = isStreaming,
        onPreviewRequested = if (onCodePreviewRequested != null) {
            { onCodePreviewRequested(language.orEmpty(), code) }
        } else {
            null
        },
        onCopy = {
            coroutineScope.launch {
                clipboard.setClipEntry(
                    ClipEntry(
                        ClipData.newPlainText("code", code)
                    )
                )
                onCodeCopied?.invoke()
            }
        },
    )
}

@Composable
internal fun NativeMarkdownBlocksSegment(
    blocks: List<NativeStreamingMarkdownBlock>,
    style: TextStyle,
    color: Color,
    isStreaming: Boolean,
    onCodePreviewRequested: ((String, String) -> Unit)?,
    onCodeCopied: (() -> Unit)?,
    onImageClick: ((String) -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        blocks.forEachIndexed { index, block ->
            key(block.stableId) {
                when (block.type) {
                    NativeStreamingMarkdownBlockType.Paragraph -> {
                        NativeInlineText(
                            text = block.text,
                            style = style,
                            color = color,
                        )
                    }

                    NativeStreamingMarkdownBlockType.Heading -> {
                        NativeHeadingText(
                            text = block.text,
                            level = block.level,
                            color = color,
                        )
                    }

                    NativeStreamingMarkdownBlockType.HorizontalRule -> {
                        HorizontalDivider(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = ChatMarkdownTextStyle.HORIZONTAL_RULE_VERTICAL_PADDING_DP.dp),
                            thickness = ChatMarkdownTextStyle.HORIZONTAL_RULE_THICKNESS_DP.dp,
                            color = color.copy(alpha = ChatMarkdownTextStyle.HORIZONTAL_RULE_COLOR_ALPHA),
                        )
                    }

                    NativeStreamingMarkdownBlockType.BlockQuote -> {
                        NativeBlockQuote(
                            text = block.text,
                            children = block.children,
                            style = style,
                            color = color,
                            isStreaming = isStreaming,
                            onCodePreviewRequested = onCodePreviewRequested,
                            onCodeCopied = onCodeCopied,
                            onImageClick = onImageClick,
                        )
                    }

                    NativeStreamingMarkdownBlockType.ListBlock -> {
                        NativeListBlock(
                            items = block.items,
                            listItems = block.listItems,
                            ordered = block.ordered,
                            style = style,
                            color = color,
                            isStreaming = isStreaming,
                            onCodePreviewRequested = onCodePreviewRequested,
                            onCodeCopied = onCodeCopied,
                            onImageClick = onImageClick,
                        )
                    }

                    NativeStreamingMarkdownBlockType.CodeBlock -> {
                        ChatGptMarkdownCodeBlockSegment(
                            code = block.code,
                            style = style,
                            color = color,
                        )
                    }

                    NativeStreamingMarkdownBlockType.MathBlock -> {
                        StableLatexRenderer(
                            latex = block.text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            style = style,
                            color = color,
                            contentKey = block.stableId,
                        )
                    }

                    NativeStreamingMarkdownBlockType.Table -> {
                        TableRenderer(
                            lines = block.tableLines,
                            modifier = Modifier.fillMaxWidth(),
                            isStreaming = isStreaming,
                            contentKey = block.stableId,
                            headerStyle = style.copy(fontWeight = FontWeight.Bold),
                            cellStyle = style,
                            onImageClick = onImageClick,
                        )
                    }

                    NativeStreamingMarkdownBlockType.Image -> {
                        NativeMarkdownImage(
                            url = block.imageUrl,
                            alt = block.imageAlt,
                            onImageClick = onImageClick,
                        )
                    }
                }
            }
            val nextBlock = blocks.getOrNull(index + 1)
            if (nextBlock != null) {
                Spacer(
                    modifier = Modifier.height(
                        nativeMarkdownBlockSpacingAfter(block, nextBlock)
                    )
                )
            }
        }
    }
}

@Composable
private fun ChatGptMarkdownCodeBlockSegment(
    code: String,
    style: TextStyle,
    color: Color,
) {
    androidx.compose.material3.Text(
        text = code,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Color(CHATGPT_CODE_BLOCK_BACKGROUND_HEX).copy(
                    alpha = CHATGPT_CODE_BLOCK_BACKGROUND_ALPHA,
                )
            )
            .padding(CHATGPT_CODE_BLOCK_PADDING_DP.dp),
        style = compactBodyTextStyle(style, color).copy(
            fontFamily = FontFamily.Monospace,
        ),
        textAlign = TextAlign.Start,
    )
}

@Composable
private fun NativeMarkdownImage(
    url: String,
    alt: String,
    onImageClick: ((String) -> Unit)?,
) {
    val clickModifier = if (onImageClick != null) {
        Modifier.clickable { onImageClick(url) }
    } else {
        Modifier
    }
    ProportionalAsyncImage(
        model = url,
        contentDescription = alt.ifBlank { null },
        maxWidth = 320.dp,
        modifier = Modifier
            .padding(vertical = 4.dp)
            .then(clickModifier),
    )
}

@Composable
private fun NativeHeadingText(
    text: String,
    level: Int,
    color: Color,
) {
    val headingLevel = level.coerceIn(1, 6)
    val headingStyle = when (headingLevel) {
        1 -> MaterialTheme.typography.titleLarge
        2 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleSmall
    }
    val headingSpec = chatGptHeadingTextSpecForLevel(headingLevel)
    androidx.compose.material3.Text(
        text = text,
        style = headingStyle.copy(
            color = if (color == Color.Unspecified) {
                color
            } else {
                color.copy(alpha = headingSpec.colorAlpha)
            },
            fontSize = headingSpec.fontSize,
            lineHeight = headingSpec.lineHeight,
            fontWeight = headingSpec.fontWeight,
            fontStyle = headingSpec.fontStyle,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
    )
}

@Composable
private fun NativeBlockQuote(
    text: String,
    children: List<NativeStreamingMarkdownBlock>,
    style: TextStyle,
    color: Color,
    isStreaming: Boolean,
    onCodePreviewRequested: ((String, String) -> Unit)?,
    onCodeCopied: (() -> Unit)?,
    onImageClick: ((String) -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Spacer(modifier = Modifier.width(ChatMarkdownTextStyle.BLOCK_QUOTE_START_MARGIN_DP.dp))
        Box(
            modifier = Modifier
                .width(ChatMarkdownTextStyle.BLOCK_QUOTE_BAR_WIDTH_DP.dp)
                .fillMaxHeight()
                .background(
                    color = color.copy(alpha = ChatMarkdownTextStyle.BLOCK_QUOTE_BAR_COLOR_ALPHA),
                    shape = RoundedCornerShape(percent = 50),
                ),
        )
        Spacer(modifier = Modifier.width(ChatMarkdownTextStyle.BLOCK_QUOTE_END_MARGIN_DP.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = ChatMarkdownTextStyle.BLOCK_QUOTE_VERTICAL_CONTENT_PADDING_DP.dp),
        ) {
            if (children.isNotEmpty()) {
                NativeMarkdownBlocksSegment(
                    blocks = children,
                    style = style,
                    color = color,
                    isStreaming = isStreaming,
                    onCodePreviewRequested = onCodePreviewRequested,
                    onCodeCopied = onCodeCopied,
                    onImageClick = onImageClick,
                )
            } else {
                NativeInlineText(
                    text = text,
                    style = style,
                    color = color,
                )
            }
        }
    }
}

@Composable
private fun NativeListBlock(
    items: List<String>,
    listItems: List<NativeStreamingListItem>,
    ordered: Boolean,
    style: TextStyle,
    color: Color,
    isStreaming: Boolean,
    onCodePreviewRequested: ((String, String) -> Unit)?,
    onCodeCopied: (() -> Unit)?,
    onImageClick: ((String) -> Unit)?,
) {
    val rows = remember(items, listItems, ordered) {
        listItems.ifEmpty {
            items.mapIndexed { index, item ->
                NativeStreamingListItem(
                    text = item,
                    ordered = ordered,
                    number = index + 1,
                )
            }
        }
    }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val markerTextStyle = remember(style, color) {
        nativeListMarkerTextStyle(style, color)
    }
    val markerWidthByLevel = remember(rows, markerTextStyle, density, textMeasurer) {
        rows.groupBy { it.level.coerceAtLeast(0) }
            .mapValues { (level, levelRows) ->
                val unorderedWidth = levelRows
                    .filterNot { it.ordered }
                    .maxOfOrNull { nativeUnorderedListMarkerColumnWidthDp(level) }
                    ?: 0f
                val orderedTextWidth = levelRows
                    .filter { it.ordered }
                    .maxOfOrNull { item ->
                        with(density) {
                            textMeasurer.measure(
                                text = AnnotatedString(nativeListMarkerText(item)),
                                style = markerTextStyle,
                            ).size.width.toDp().value
                        }
                    }
                    ?: 0f
                val orderedWidth = if (orderedTextWidth > 0f) {
                    nativeOrderedListMarkerColumnWidthDp(orderedTextWidth)
                } else {
                    0f
                }
                maxOf(unorderedWidth, orderedWidth).dp
            }
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        rows.forEachIndexed { index, item ->
            val topSpacing = nativeListItemTopSpacing(rows, index)
            val level = item.level.coerceAtLeast(0)
            val markerWidth = markerWidthByLevel[level]
                ?: nativeUnorderedListMarkerColumnWidthDp(level).dp
            if (topSpacing > 0.dp) {
                Spacer(modifier = Modifier.height(topSpacing))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = nativeListNestedStartPaddingDp(level, markerWidthByLevel)
                    )
            ) {
                NativeListMarker(
                    item = item,
                    markerWidth = markerWidth,
                    style = style,
                    color = color,
                )
                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    NativeInlineText(
                        text = item.text,
                        style = style,
                        color = color,
                        lineHeightSp = ChatMarkdownTextStyle.LIST_ITEM_LINE_HEIGHT_SP,
                    )
                    if (item.children.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(ChatMarkdownTextStyle.SPACING_PARAGRAPH_DP.dp))
                        NativeMarkdownBlocksSegment(
                            blocks = item.children,
                            style = style,
                            color = color,
                            isStreaming = isStreaming,
                            onCodePreviewRequested = onCodePreviewRequested,
                            onCodeCopied = onCodeCopied,
                            onImageClick = onImageClick,
                        )
                    }
                }
            }
        }
    }
}

internal fun nativeListMarkerText(item: NativeStreamingListItem): String {
    if (!item.ordered) return ""

    return when (item.level.coerceAtLeast(0) % 4) {
        0 -> "${item.number}."
        1 -> "${nativeOrderedListAlphaMarker(item.number)}."
        2 -> "${item.number})"
        else -> "${nativeOrderedListAlphaMarker(item.number)})"
    }
}

private fun nativeOrderedListAlphaMarker(number: Int): String {
    val normalized = (number - 1).coerceAtLeast(0) % 26
    return ('a'.code + normalized).toChar().toString()
}

internal fun nativeUnorderedListMarkerColumnWidthDp(level: Int): Float {
    return ChatMarkdownTextStyle.LIST_MARKER_INDENT_DP +
        ChatMarkdownTextStyle.listBulletSizeDp(level) +
        ChatMarkdownTextStyle.LIST_CONTENTS_INDENT_DP
}

internal fun nativeOrderedListMarkerColumnWidthDp(markerTextWidthDp: Float): Float {
    return ChatMarkdownTextStyle.LIST_MARKER_INDENT_DP +
        markerTextWidthDp +
        ChatMarkdownTextStyle.LIST_CONTENTS_INDENT_DP
}

internal fun nativeListNestedStartPaddingDp(
    level: Int,
    markerWidthByLevel: Map<Int, Dp>,
): Dp {
    val safeLevel = level.coerceAtLeast(0)
    val padding = (0 until safeLevel).sumOf { ancestorLevel ->
        markerWidthByLevel[ancestorLevel]?.value?.toDouble()
            ?: nativeUnorderedListMarkerColumnWidthDp(ancestorLevel).toDouble()
    }
    return padding.toFloat().dp
}

internal fun nativeListItemTopSpacing(
    rows: List<NativeStreamingListItem>,
    index: Int,
): Dp {
    if (index <= 0 || index >= rows.size) return 0.dp
    val current = rows[index]
    val previous = rows[index - 1]
    return if (current.level > previous.level) {
        ChatMarkdownTextStyle.LIST_NESTED_TOP_SPACING_DP.dp
    } else {
        ChatMarkdownTextStyle.LIST_ITEM_SPACING_DP.dp
    }
}

@Composable
private fun NativeListMarker(
    item: NativeStreamingListItem,
    markerWidth: Dp,
    style: TextStyle,
    color: Color,
) {
    if (item.ordered) {
        androidx.compose.material3.Text(
            text = nativeListMarkerText(item),
            style = nativeListMarkerTextStyle(style, color),
            modifier = Modifier
                .width(markerWidth)
                .padding(
                    start = ChatMarkdownTextStyle.LIST_MARKER_INDENT_DP.dp,
                    end = ChatMarkdownTextStyle.LIST_CONTENTS_INDENT_DP.dp,
                ),
        )
    } else {
        val bulletSize = ChatMarkdownTextStyle.listBulletSizeDp(item.level).dp
        Box(modifier = Modifier.width(markerWidth)) {
            Canvas(
                modifier = Modifier
                    .padding(
                        start = ChatMarkdownTextStyle.LIST_BULLET_START_PADDING_DP.dp,
                        top = ChatMarkdownTextStyle.LIST_BULLET_TOP_PADDING_DP.dp,
                    )
                    .size(bulletSize)
            ) {
                if (ChatMarkdownTextStyle.listBulletFilled(item.level)) {
                    drawCircle(color = color)
                } else {
                    drawCircle(
                        color = color,
                        style = Stroke(width = 1.4.dp.toPx()),
                    )
                }
            }
        }
    }
}

internal fun nativeMarkdownBlockSpacingAfter(
    current: NativeStreamingMarkdownBlock,
    next: NativeStreamingMarkdownBlock,
): Dp {
    return when {
        next.type == NativeStreamingMarkdownBlockType.Heading ->
            ChatMarkdownTextStyle.SPACING_BEFORE_HEADING_DP.dp
        current.type == NativeStreamingMarkdownBlockType.HorizontalRule ->
            ChatMarkdownTextStyle.SPACING_AFTER_DIVIDER_DP.dp
        current.type == NativeStreamingMarkdownBlockType.Heading ->
            ChatMarkdownTextStyle.SPACING_AFTER_HEADING_DP.dp
        current.type == NativeStreamingMarkdownBlockType.Paragraph ->
            ChatMarkdownTextStyle.SPACING_PARAGRAPH_DP.dp
        current.type == NativeStreamingMarkdownBlockType.ListBlock ->
            ChatMarkdownTextStyle.SPACING_AFTER_LIST_DP.dp
        current.type == NativeStreamingMarkdownBlockType.BlockQuote ->
            ChatMarkdownTextStyle.SPACING_AFTER_QUOTE_DP.dp
        else -> ChatMarkdownTextStyle.SPACING_PARAGRAPH_DP.dp
    }
}

@Composable
private fun NativeInlineText(
    text: String,
    style: TextStyle,
    color: Color,
    lineHeightSp: Float = ChatMarkdownTextStyle.BODY_LINE_HEIGHT_SP,
) {
    val inlineParts = remember(text) {
        buildNativeInlinePartsForText(text)
    }
    if (inlineParts != null) {
        InlinePartsSegment(
            parts = inlineParts,
            style = style,
            color = color,
        )
    } else if (supportedInlineMarkdownPattern.containsMatchIn(text)) {
        ComposeInlineMarkdownSegment(
            text = text,
            style = style,
            color = color,
            lineHeightSp = lineHeightSp,
        )
    } else {
        PlainTextSegment(
            text = text,
            style = style,
            color = color,
            lineHeightSp = lineHeightSp,
        )
    }
}

internal fun buildNativeInlinePartsForText(text: String): List<InlineRenderPart>? {
    val blocks = StreamBlockParser.parse(text, "native-inline").blocks
    if (blocks.none { it is StreamBlock.MathInline }) return null

    val parts = mutableListOf<InlineRenderPart>()
    blocks.forEach { block ->
        when (block) {
            is StreamBlock.PlainText -> {
                if (block.text.isNotEmpty()) {
                    parts.add(InlineRenderPart.Text(block.text))
                }
            }
            is StreamBlock.MathInline -> {
                parts.add(InlineRenderPart.Math(block))
            }
            is StreamBlock.MathBlock,
            is StreamBlock.CodeBlock -> return null
        }
    }
    return parts.takeIf { it.isNotEmpty() }
}

@Composable
internal fun InlinePartsSegment(
    parts: List<InlineRenderPart>,
    style: TextStyle,
    color: Color,
) {
    val isDark = isSystemInDarkTheme()
    val codeBackground = chatInlineCodeBackgroundColor(MaterialTheme.colorScheme.surfaceVariant, isDark)
    val codeColor = chatInlineCodeTextColor(isDark)
    val codeFontSize = chatInlineCodeFontSize(style.fontSize)
    val model = remember(parts, color, codeBackground, codeColor, codeFontSize) {
        buildInlinePartsTextModel(
            parts = parts,
            baseColor = color,
            codeBackground = codeBackground,
            codeColor = codeColor,
            codeFontSize = codeFontSize,
        )
    }
    val inlineContent = remember(model.mathPlaceholders) {
        model.mathPlaceholders.associate { placeholder ->
            placeholder.id to InlineTextContent(
                placeholder = Placeholder(
                    width = placeholder.width,
                    height = placeholder.height,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                ),
            ) {
                MathInline(
                    latex = placeholder.latex,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 1.dp),
                )
            }
        }
    }
    androidx.compose.material3.Text(
        text = model.annotatedText,
        style = compactBodyTextStyle(style, color),
        textAlign = TextAlign.Start,
        inlineContent = inlineContent,
    )
}

internal fun buildInlinePartsTextModel(
    parts: List<InlineRenderPart>,
    baseColor: Color,
    codeBackground: Color,
    codeColor: Color,
    codeFontSize: TextUnit,
): InlinePartsTextModel {
    val builder = AnnotatedString.Builder()
    val mathPlaceholders = mutableListOf<InlineMathPlaceholder>()
    var mathIndex = 0

    parts.forEach { part ->
        when (part) {
            is InlineRenderPart.Text -> {
                if (part.text.isNotEmpty()) {
                    builder.append(
                        InlineMarkdownParser.parse(
                            text = part.text,
                            baseColor = baseColor,
                            codeBackground = codeBackground,
                            codeColor = codeColor,
                            codeFontSize = codeFontSize,
                        )
                    )
                }
            }

            is InlineRenderPart.Math -> {
                if (part.block.state == MathBlockState.RAW) {
                    builder.append(part.block.text)
                } else {
                    val id = "math-${mathIndex++}"
                    val latex = stripInlineMathDelimiters(part.block.text)
                    val placeholderSize = inlineMathPlaceholderSize(latex, codeFontSize)
                    builder.appendInlineContent(id = id, alternateText = "�")
                    mathPlaceholders.add(
                        InlineMathPlaceholder(
                            id = id,
                            latex = latex,
                            width = placeholderSize.width,
                            height = placeholderSize.height,
                        )
                    )
                }
            }
        }
    }

    return InlinePartsTextModel(
        annotatedText = builder.toAnnotatedString(),
        mathPlaceholders = mathPlaceholders,
    )
}

private data class InlineMathPlaceholderSize(
    val width: TextUnit,
    val height: TextUnit,
)

private fun inlineMathPlaceholderSize(latex: String, fontSize: TextUnit): InlineMathPlaceholderSize {
    val baseSize = if (fontSize == TextUnit.Unspecified) 16.sp else fontSize
    val widthFactor = (latex.length.coerceIn(2, 32) * 0.54f).coerceAtLeast(2.2f)
    return InlineMathPlaceholderSize(
        width = baseSize * widthFactor,
        height = baseSize * 1.55f,
    )
}

private fun stripInlineMathDelimiters(text: String): String {
    return when {
        text.startsWith("\\(") && text.endsWith("\\)") && text.length >= 4 ->
            text.substring(2, text.length - 2)
        text.startsWith("$") && text.endsWith("$") && !text.startsWith("$$") && text.length >= 2 ->
            text.substring(1, text.length - 1)
        else -> text
    }
}

@Composable
private fun PlainTextSegment(
    text: String,
    style: TextStyle,
    color: Color,
    lineHeightSp: Float = ChatMarkdownTextStyle.BODY_LINE_HEIGHT_SP,
) {
    androidx.compose.material3.Text(
        text = text,
        style = compactBodyTextStyle(style, color, lineHeightSp),
        textAlign = TextAlign.Start,
    )
}

@Composable
private fun ComposeInlineMarkdownSegment(
    text: String,
    style: TextStyle,
    color: Color,
    lineHeightSp: Float = ChatMarkdownTextStyle.BODY_LINE_HEIGHT_SP,
) {
    val isDark = isSystemInDarkTheme()
    val codeBackground = chatInlineCodeBackgroundColor(MaterialTheme.colorScheme.surfaceVariant, isDark)
    val codeColor = chatInlineCodeTextColor(isDark)
    val codeFontSize = chatInlineCodeFontSize(style.fontSize)
    val annotatedString = remember(text, color, codeBackground, codeColor, codeFontSize) {
        InlineMarkdownParser.parse(
            text = text,
            baseColor = color,
            codeBackground = codeBackground,
            codeColor = codeColor,
            codeFontSize = codeFontSize,
        )
    }
    androidx.compose.material3.Text(
        text = annotatedString,
        style = compactBodyTextStyle(style, color, lineHeightSp),
        textAlign = TextAlign.Start,
    )
}


@Composable
private fun FullMarkdownFallbackSegment(
    text: String,
    stableId: String,
    style: TextStyle,
    color: Color,
    isStreaming: Boolean,
    sender: Sender,
    onLongPress: (() -> Unit)?,
    onImageClick: ((String) -> Unit)?,
) {
    MarkdownRenderer(
        markdown = text,
        modifier = Modifier.fillMaxWidth(),
        style = style,
        color = color,
        isStreaming = isStreaming,
        onLongPress = streamMarkdownLongPressHandler(sender, onLongPress),
        onImageClick = onImageClick,
        sender = sender,
        contentKey = stableId,
        disableVerticalPadding = true,
    )
}

internal fun compactBodyTextStyle(
    style: TextStyle,
    color: Color,
    lineHeightSp: Float = ChatMarkdownTextStyle.BODY_LINE_HEIGHT_SP,
): TextStyle {
    return style.copy(
        color = color,
        lineHeight = lineHeightSp.sp,
        lineBreak = LineBreak.Simple,
        hyphens = Hyphens.None,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        textAlign = TextAlign.Start,
    )
}

private val supportedInlineMarkdownPattern = Regex(
    """(&(?:amp|lt|gt|quot|apos|nbsp|ndash|mdash|hellip|copy|reg|trade|bull|middot|ldquo|rdquo|lsquo|rsquo|minus|times|divide|plusmn|deg|le|ge|ne|rarr|larr|Alpha|Beta|Gamma|Delta|Epsilon|Zeta|Eta|Theta|Iota|Kappa|Lambda|Mu|Nu|Xi|Omicron|Pi|Rho|Sigma|Tau|Upsilon|Phi|Chi|Psi|Omega|alpha|beta|gamma|delta|epsilon|zeta|eta|theta|iota|kappa|lambda|mu|nu|xi|omicron|pi|rho|sigma|tau|upsilon|phi|chi|psi|omega|#\d+|#x[0-9A-Fa-f]+);|<https?://[^>\s]+>|https?://[^\s<>()\[\]{}]+(?:\([^\s<>()\[\]{}]*\)[^\s<>()\[\]{}]*)*|\[[^\]]+]\([^)]+?\)|\*\*|__|\*(?=[^\s*])|_(?=[^\s_])|~~|\+\+|==|\^\^|,,|`)"""
)

private val fullMarkdownOnlyPattern = Regex("""(!\[|^\s{0,3}#{1,6}\s+|^\s*[-*+]\s+|^\s*\d+[.)]\s+|^\s*>\s?|^\s{0,3}(-{3,}|\*{3,}|_{3,})\s*$)""", RegexOption.MULTILINE)

private val fencedCodeStartPattern = Regex("""^\s{0,3}(```|~~~)""", RegexOption.MULTILINE)
private val setextHeadingPattern = Regex("""^\S[^\n]*(?:\n|\r\n?)\s{0,3}(={3,}|-{3,})\s*$""", RegexOption.MULTILINE)
private val referenceLinkPattern = Regex("""!?\[[^\]]+]\[[^\]]*]|\[[^\]]+]:\s+\S+""", RegexOption.MULTILINE)
private val referenceDefinitionPattern = Regex("""^\s{0,3}\[[^\]]+]:\s+\S+""", RegexOption.MULTILINE)
private val referenceDefinitionLinePattern = Regex("""^\s{0,3}\[([^\]]+)]:\s+(\S+)(?:\s+.*)?$""")
private val referenceUsePattern = Regex("""(!?)\[([^\]]+)]\[([^\]]*)]""")
private val autolinkPattern = Regex("""<https?://[^>\s]+>""")
private val htmlTagPattern = Regex("""</?[A-Za-z][A-Za-z0-9:-]*(?:\s+[^>]*)?/?>""")
private val doubleQuotedAnchorHtmlTagPattern = Regex("""<a\b[^>]*\bhref\s*=\s*"([^"]+)"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
private val singleQuotedAnchorHtmlTagPattern = Regex("""<a\b[^>]*\bhref\s*=\s*'([^']+)'[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
private val genericAnchorHtmlTagPattern = Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
private val preCodeHtmlTagPattern = Regex("""<pre\b[^>]*>\s*<code\b([^>]*)>([\s\S]*?)</code>\s*</pre>""", RegexOption.IGNORE_CASE)
private val boldHtmlTagPattern = Regex("""<(strong|b)(?:\s+[^>]*)?>([\s\S]*?)</\1>""", RegexOption.IGNORE_CASE)
private val italicHtmlTagPattern = Regex("""<(em|i)(?:\s+[^>]*)?>([\s\S]*?)</\1>""", RegexOption.IGNORE_CASE)
private val codeHtmlTagPattern = Regex("""<code(?:\s+[^>]*)?>([\s\S]*?)</code>""", RegexOption.IGNORE_CASE)
private val strikeHtmlTagPattern = Regex("""<(s|del)(?:\s+[^>]*)?>([\s\S]*?)</\1>""", RegexOption.IGNORE_CASE)
private val underlineHtmlTagPattern = Regex("""<u(?:\s+[^>]*)?>([\s\S]*?)</u>""", RegexOption.IGNORE_CASE)
private val markHtmlTagPattern = Regex("""<mark(?:\s+[^>]*)?>([\s\S]*?)</mark>""", RegexOption.IGNORE_CASE)
private val supHtmlTagPattern = Regex("""<sup(?:\s+[^>]*)?>([\s\S]*?)</sup>""", RegexOption.IGNORE_CASE)
private val subHtmlTagPattern = Regex("""<sub(?:\s+[^>]*)?>([\s\S]*?)</sub>""", RegexOption.IGNORE_CASE)
private val kbdHtmlTagPattern = Regex("""<kbd(?:\s+[^>]*)?>([\s\S]*?)</kbd>""", RegexOption.IGNORE_CASE)
private val spanHtmlTagPattern = Regex("""<span(?:\s+[^>]*)?>([\s\S]*?)</span>""", RegexOption.IGNORE_CASE)
private val paragraphHtmlTagPattern = Regex("""<p(?:\s+[^>]*)?>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
private val divHtmlTagPattern = Regex("""<div(?:\s+[^>]*)?>([\s\S]*?)</div>""", RegexOption.IGNORE_CASE)
private val unorderedHtmlListPattern = Regex("""<ul(?:\s+[^>]*)?>([\s\S]*?)</ul>""", RegexOption.IGNORE_CASE)
private val orderedHtmlListPattern = Regex("""<ol\b([^>]*)>([\s\S]*?)</ol>""", RegexOption.IGNORE_CASE)
private val htmlListItemPattern = Regex("""<li\b([^>]*)>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE)
private val headingHtmlTagPattern = Regex("""<h([1-6])(?:\s+[^>]*)?>([\s\S]*?)</h\1>""", RegexOption.IGNORE_CASE)
private val blockQuoteHtmlTagPattern = Regex("""<blockquote(?:\s+[^>]*)?>([\s\S]*?)</blockquote>""", RegexOption.IGNORE_CASE)
private val imageHtmlTagPattern = Regex("""<img\b([^>]*?)\s*/?>""", RegexOption.IGNORE_CASE)
private val horizontalRuleHtmlTagPattern = Regex("""<hr\b[^>]*/?>""", RegexOption.IGNORE_CASE)
private val htmlAttributePattern = Regex("""([A-Za-z_:][A-Za-z0-9_:.-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""")
private val htmlLineBreakTagPattern = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
private val htmlEntityPattern = Regex("""&(?:[A-Za-z][A-Za-z0-9]+|#\d+|#x[0-9A-Fa-f]+);""")
private val supportedHtmlEntityPattern = Regex("""&(?:amp|lt|gt|quot|apos|nbsp|ndash|mdash|hellip|copy|reg|trade|bull|middot|ldquo|rdquo|lsquo|rsquo|minus|times|divide|plusmn|deg|le|ge|ne|rarr|larr|Alpha|Beta|Gamma|Delta|Epsilon|Zeta|Eta|Theta|Iota|Kappa|Lambda|Mu|Nu|Xi|Omicron|Pi|Rho|Sigma|Tau|Upsilon|Phi|Chi|Psi|Omega|alpha|beta|gamma|delta|epsilon|zeta|eta|theta|iota|kappa|lambda|mu|nu|xi|omicron|pi|rho|sigma|tau|upsilon|phi|chi|psi|omega|#\d+|#x[0-9A-Fa-f]+);""")
private val simpleDollarInlineMathPattern = Regex("""(?<!\\)\$([^$\r\n]{1,32})(?<!\\)\$""")
private val simpleEscapedInlineMathPattern = Regex("""\\\(([^)\r\n]{1,32})\\\)""")
private val simpleInlineMathSymbolReplacements = mapOf(
    "\\hbar" to "ℏ",
    "\\nabla^2" to "∇²",
    "\\nabla" to "∇",
    "\\partial" to "∂",
)
private val imageMarkdownPattern = Regex("""!\[([^\]]*)]\((<[^>\s]+>|(?:[^\s()]|\([^)]*\))+)(?:\s+(?:"[^"]*"|'[^']*'|\([^)]*\)))?\)""")
private val standaloneImageMarkdownLinePattern = Regex("""^\s*!\[([^\]]*)]\((<[^>\s]+>|(?:[^\s()]|\([^)]*\))+)(?:\s+(?:"[^"]*"|'[^']*'|\([^)]*\)))?\)\s*$""")

private val tableSeparatorPattern = Regex(
    """^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)+\|?\s*$"""
)

private val atxHeadingLinePattern = Regex("""^\s{0,3}(#{1,6})\s+(.+?)\s*#*\s*$""")
private val setextUnderlineLinePattern = Regex("""^\s{0,3}(={3,}|-{3,})\s*$""")
private val horizontalRuleLinePattern = Regex("""^\s{0,3}(-{3,}|\*{3,}|_{3,})\s*$""")
private val blockQuoteLinePattern = Regex("""^\s{0,3}>\s?(.*)$""")
private val unorderedListLinePattern = Regex("""^(\s{0,12})[-*+]\s+(.+)$""")
private val orderedListLinePattern = Regex("""^(\s{0,12})(\d+)[.)]\s+(.+)$""")
private val fencedCodeOpeningLinePattern = Regex("""^\s*([`~]{3,})([^\n`~]*)$""")

internal data class FencedCodeBlockContent(
    val language: String?,
    val code: String,
)

private data class NativeListLine(
    val indentColumns: Int,
    val ordered: Boolean,
    val number: Int,
    val text: String,
)

private data class NativeMathBlockDelimiter(
    val opening: String,
    val closing: String,
)

private data class NativeMathBlockMatch(
    val endIndex: Int,
)

private data class NativeParagraphMathBlock(
    val start: Int,
    val endExclusive: Int,
    val text: String,
)

internal fun resolveStreamingMarkdownRenderPath(text: String): StreamingMarkdownRenderPath {
    if (text.isEmpty()) return StreamingMarkdownRenderPath.PlainText
    val nativeText = prepareNativeMarkdownText(text) ?: return StreamingMarkdownRenderPath.FullMarkdown
    if (hasUnsupportedNativeMarkdownForCompose(nativeText)) return StreamingMarkdownRenderPath.FullMarkdown
    if (text != nativeText) return StreamingMarkdownRenderPath.ComposeBlockMarkdown
    if (hasNativeBlockMarkdownSyntax(nativeText)) return StreamingMarkdownRenderPath.ComposeBlockMarkdown
    if (hasInlineMathSyntax(nativeText)) return StreamingMarkdownRenderPath.ComposeBlockMarkdown
    if (hasFullMarkdownSyntax(nativeText)) return StreamingMarkdownRenderPath.FullMarkdown
    return if (supportedInlineMarkdownPattern.containsMatchIn(nativeText)) {
        StreamingMarkdownRenderPath.ComposeInlineMarkdown
    } else {
        StreamingMarkdownRenderPath.PlainText
    }
}

private fun hasFullMarkdownSyntax(text: String): Boolean {
    return hasMarkdownTable(text) ||
        hasInlineMathSyntax(text) ||
        fullMarkdownOnlyPattern.containsMatchIn(text) ||
        fencedCodeStartPattern.containsMatchIn(text) ||
        setextHeadingPattern.containsMatchIn(text) ||
        referenceDefinitionPattern.containsMatchIn(text) ||
        hasUnsupportedHtmlTag(text) ||
        hasUnsupportedHtmlEntity(text) ||
        hasUnsupportedImageMarkdown(text)
}

private fun hasNativeBlockMarkdownSyntax(text: String): Boolean {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    val lines = normalized.lines()
    return lines.indices.any { index ->
        TableUtils.isValidTableStart(lines, index) ||
            htmlLineBreakTagPattern.containsMatchIn(lines[index]) ||
            imageMarkdownPattern.containsMatchIn(lines[index]) ||
            standaloneImageMarkdownLinePattern.matches(lines[index]) ||
            atxHeadingLinePattern.matches(lines[index]) ||
            fencedCodeOpeningLinePattern.matches(lines[index]) ||
            horizontalRuleLinePattern.matches(lines[index]) ||
            blockQuoteLinePattern.matches(lines[index]) ||
            unorderedListLinePattern.matches(lines[index]) ||
            orderedListLinePattern.matches(lines[index]) ||
            (
                index + 1 < lines.size &&
                    lines[index].isNotBlank() &&
                    setextUnderlineLinePattern.matches(lines[index + 1])
            )
    }
}

private fun prepareNativeMarkdownText(text: String): String? {
    var normalized = normalizeSimpleInlineHtmlTags(text)
    if (referenceLinkPattern.containsMatchIn(normalized)) {
        normalized = normalizeReferenceLinksForNativeMarkdown(normalized) ?: return null
    }
    normalized = normalizeSimpleInlineMathSymbols(normalized)
    return normalized
}

private fun normalizeSimpleInlineHtmlTags(text: String): String {
    var normalized = text
    normalized = normalizePreCodeHtmlTags(normalized)
    normalized = normalizeImageHtmlTags(normalized)
    normalized = horizontalRuleHtmlTagPattern.replace(normalized, "\n\n---\n\n")
    normalized = normalizeAnchorHtmlTags(normalized, doubleQuotedAnchorHtmlTagPattern)
    normalized = normalizeAnchorHtmlTags(normalized, singleQuotedAnchorHtmlTagPattern)
    normalized = normalizeGenericAnchorHtmlTags(normalized)
    normalized = boldHtmlTagPattern.replace(normalized) { match ->
        "**${match.groupValues[2]}**"
    }
    normalized = italicHtmlTagPattern.replace(normalized) { match ->
        "*${match.groupValues[2]}*"
    }
    normalized = codeHtmlTagPattern.replace(normalized) { match ->
        "`${match.groupValues[1]}`"
    }
    normalized = strikeHtmlTagPattern.replace(normalized) { match ->
        "~~${match.groupValues[2]}~~"
    }
    normalized = underlineHtmlTagPattern.replace(normalized) { match ->
        "++${match.groupValues[1]}++"
    }
    normalized = markHtmlTagPattern.replace(normalized) { match ->
        "==${match.groupValues[1]}=="
    }
    normalized = supHtmlTagPattern.replace(normalized) { match ->
        "^^${match.groupValues[1]}^^"
    }
    normalized = subHtmlTagPattern.replace(normalized) { match ->
        ",,${match.groupValues[1]},,"
    }
    normalized = kbdHtmlTagPattern.replace(normalized) { match ->
        "`${match.groupValues[1]}`"
    }
    normalized = spanHtmlTagPattern.replace(normalized) { match ->
        match.groupValues[1]
    }
    normalized = paragraphHtmlTagPattern.replace(normalized) { match ->
        "\n\n${match.groupValues[1].trim()}\n\n"
    }
    normalized = divHtmlTagPattern.replace(normalized) { match ->
        "\n\n${match.groupValues[1].trim()}\n\n"
    }
    normalized = unorderedHtmlListPattern.replace(normalized) { match ->
        normalizeHtmlListItems(match.groupValues[1], ordered = false)
    }
    normalized = orderedHtmlListPattern.replace(normalized) { match ->
        val attributes = parseHtmlAttributes(match.groupValues[1])
        val startNumber = attributes["start"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        normalizeHtmlListItems(
            body = match.groupValues[2],
            ordered = true,
            startNumber = startNumber,
        )
    }
    normalized = headingHtmlTagPattern.replace(normalized) { match ->
        val level = match.groupValues[1].toIntOrNull()?.coerceIn(1, 6) ?: 1
        "\n\n${"#".repeat(level)} ${match.groupValues[2].trim()}\n\n"
    }
    normalized = blockQuoteHtmlTagPattern.replace(normalized) { match ->
        normalizeHtmlBlockQuote(match.groupValues[1])
    }
    return normalized
}

private fun normalizePreCodeHtmlTags(text: String): String {
    return preCodeHtmlTagPattern.replace(text) { match ->
        val attributes = parseHtmlAttributes(match.groupValues[1])
        val language = resolveHtmlCodeLanguage(attributes)
        val code = decodeBasicHtmlCodeEntities(match.groupValues[2]).trim('\n', '\r')
        val fence = if (code.contains("```")) "~~~" else "```"
        "\n\n$fence$language\n$code\n$fence\n\n"
    }
}

private fun resolveHtmlCodeLanguage(attributes: Map<String, String>): String {
    attributes["data-lang"]?.trim()?.ifNotBlank()?.let { return it }
    attributes["language"]?.trim()?.ifNotBlank()?.let { return it }
    val classNames = attributes["class"].orEmpty().split(Regex("""\s+"""))
    return classNames.firstNotNullOfOrNull { className ->
        when {
            className.startsWith("language-") -> className.removePrefix("language-").ifNotBlank()
            className.startsWith("lang-") -> className.removePrefix("lang-").ifNotBlank()
            else -> null
        }
    }.orEmpty()
}

private fun String.ifNotBlank(): String? = takeIf { it.isNotBlank() }

private fun decodeBasicHtmlCodeEntities(text: String): String {
    return text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
}

private fun normalizeImageHtmlTags(text: String): String {
    return imageHtmlTagPattern.replace(text) { match ->
        val attributes = parseHtmlAttributes(match.groupValues[1])
        val url = attributes["src"].orEmpty()
        if (isSafeNativeImageUrl(url)) {
            val alt = attributes["alt"].orEmpty()
            "![$alt]($url)"
        } else {
            match.value
        }
    }
}

private fun nativeListMarkerTextStyle(
    style: TextStyle,
    color: Color,
): TextStyle {
    return style.copy(
        color = color,
        lineHeight = ChatMarkdownTextStyle.LIST_ITEM_LINE_HEIGHT_SP.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
}

private fun parseHtmlAttributes(rawAttributes: String): Map<String, String> {
    if (rawAttributes.isBlank()) return emptyMap()
    return htmlAttributePattern.findAll(rawAttributes).associate { match ->
        val name = match.groupValues[1].lowercase(Locale.ROOT)
        val value = match.groupValues[2]
            .ifEmpty { match.groupValues[3] }
            .ifEmpty { match.groupValues[4] }
        name to value
    }
}

private fun isSafeNativeImageUrl(url: String): Boolean {
    return url.startsWith("http://") || url.startsWith("https://")
}

private fun normalizeHtmlListItems(
    body: String,
    ordered: Boolean,
    startNumber: Int = 1,
): String {
    var nextNumber = startNumber
    val items = htmlListItemPattern.findAll(body).mapNotNull { match ->
        val attributes = parseHtmlAttributes(match.groupValues[1])
        val itemText = match.groupValues[2].trim()
        if (itemText.isEmpty()) {
            null
        } else if (ordered) {
            val number = attributes["value"]?.toIntOrNull()?.coerceAtLeast(1) ?: nextNumber
            nextNumber = number + 1
            "$number. $itemText"
        } else {
            "- $itemText"
        }
    }.toList()
    return if (items.isEmpty()) {
        body
    } else {
        "\n\n${items.joinToString("\n")}\n\n"
    }
}

private fun normalizeHtmlBlockQuote(body: String): String {
    val normalizedBody = body.trim()
    if (normalizedBody.isEmpty()) return ""
    val quoted = normalizedBody
        .lines()
        .joinToString("\n") { line ->
            if (line.isBlank()) ">" else "> ${line.trim()}"
        }
    return "\n\n$quoted\n\n"
}

private fun normalizeGenericAnchorHtmlTags(text: String): String {
    return genericAnchorHtmlTagPattern.replace(text) { match ->
        val attributes = parseHtmlAttributes(match.groupValues[1])
        val url = attributes["href"].orEmpty()
        val label = match.groupValues[2]
        if (isSafeNativeLinkUrl(url)) {
            "[$label]($url)"
        } else {
            match.value
        }
    }
}

private fun normalizeAnchorHtmlTags(text: String, pattern: Regex): String {
    return pattern.replace(text) { match ->
        val url = match.groupValues[1]
        val label = match.groupValues[2]
        if (isSafeNativeLinkUrl(url)) {
            "[$label]($url)"
        } else {
            match.value
        }
    }
}

private fun isSafeNativeLinkUrl(url: String): Boolean {
    return url.startsWith("http://") || url.startsWith("https://")
}

private fun normalizeReferenceLinksForNativeMarkdown(text: String): String? {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    val lines = normalized.split('\n')
    val definitions = linkedMapOf<String, String>()
    val contentLines = mutableListOf<String>()

    lines.forEach { line ->
        val definition = referenceDefinitionLinePattern.matchEntire(line)
        if (definition != null) {
            val key = normalizeReferenceKey(definition.groupValues[1])
            definitions[key] = definition.groupValues[2]
        } else {
            contentLines.add(line)
        }
    }

    val content = contentLines.joinToString("\n").trimEnd()
    if (!referenceUsePattern.containsMatchIn(content)) {
        return content
    }

    val converted = referenceUsePattern.replace(content) { match ->
        val isImageReference = match.groupValues[1] == "!"
        val label = match.groupValues[2]
        val referenceId = match.groupValues[3].ifBlank { label }
        val url = definitions[normalizeReferenceKey(referenceId)]
        if (url == null) {
            match.value
        } else {
            if (isImageReference) "![$label]($url)" else "[$label]($url)"
        }
    }

    return converted
}

private fun normalizeReferenceKey(value: String): String {
    return value.trim().lowercase(Locale.ROOT)
}

private fun normalizeSimpleInlineMathSymbols(text: String): String {
    var normalized = simpleEscapedInlineMathPattern.replace(text) { match ->
        simpleInlineMathReplacement(match.groupValues[1]) ?: match.value
    }
    normalized = simpleDollarInlineMathPattern.replace(normalized) { match ->
        simpleInlineMathReplacement(match.groupValues[1]) ?: match.value
    }
    return normalized
}

private fun simpleInlineMathReplacement(body: String): String? {
    return simpleInlineMathSymbolReplacements[body.trim()]
}

private fun hasMarkdownTable(text: String): Boolean {
    val lines = text.lines()
    if (lines.size < 2) return false
    return lines.windowed(2).any { (header, separator) ->
        header.contains('|') && tableSeparatorPattern.matches(separator)
    }
}

private fun hasInlineMathSyntax(text: String): Boolean {
    if (text.contains("\\(") || text.contains("\\[")) return true
    var index = 0
    while (index < text.length) {
        if (text[index] == '$' && (index == 0 || text[index - 1] != '\\')) {
            if (index + 1 < text.length && text[index + 1] == '$') {
                return true
            }
            val close = findClosingSingleDollar(text, index + 1) ?: return false
            val body = text.substring(index + 1, close)
            if (!isCurrencyLikeDollarBody(body)) {
                return true
            }
            index = close + 1
            continue
        }
        index++
    }
    return false
}

private fun nativeMathBlockDelimiterForLine(line: String): NativeMathBlockDelimiter? {
    val trimmed = line.trim()
    return when {
        trimmed.startsWith("$$") -> NativeMathBlockDelimiter(opening = "$$", closing = "$$")
        trimmed.startsWith("\\[") -> NativeMathBlockDelimiter(opening = "\\[", closing = "\\]")
        else -> null
    }
}

private fun collectNativeMathBlock(
    lines: List<String>,
    startIndex: Int,
    delimiter: NativeMathBlockDelimiter,
): NativeMathBlockMatch? {
    if (startIndex !in lines.indices) return null
    val firstLine = lines[startIndex].trim()
    if (!firstLine.startsWith(delimiter.opening)) return null

    if (hasNativeMathBlockClosing(firstLine, delimiter, delimiter.opening.length)) {
        return NativeMathBlockMatch(endIndex = startIndex)
    }

    for (index in startIndex + 1 until lines.size) {
        if (hasNativeMathBlockClosing(lines[index].trim(), delimiter, startSearch = 0)) {
            return NativeMathBlockMatch(endIndex = index)
        }
    }
    return null
}

private fun hasNativeMathBlockClosing(
    line: String,
    delimiter: NativeMathBlockDelimiter,
    startSearch: Int,
): Boolean {
    val close = line.indexOf(delimiter.closing, startSearch)
    return close >= 0 &&
        line.substring(close + delimiter.closing.length).trim().isEmpty()
}

private fun findNativeParagraphMathBlocks(content: String): List<NativeParagraphMathBlock>? {
    val parsed = StreamBlockParser.parse(content, "native-paragraph-math").blocks
    val mathBlocks = parsed.filterIsInstance<StreamBlock.MathBlock>()
    if (mathBlocks.isEmpty()) return emptyList()
    if (mathBlocks.any { it.state == MathBlockState.RAW }) return null
    if (parsed.any { it is StreamBlock.CodeBlock }) return null

    return mathBlocks.map { block ->
        if (isInsideInlineCodeSpan(content, block.start)) return null
        NativeParagraphMathBlock(
            start = block.start,
            endExclusive = block.endExclusive,
            text = block.text,
        )
    }
}

private fun isInsideInlineCodeSpan(text: String, offset: Int): Boolean {
    var index = 0
    var openBacktickRun = 0
    while (index < offset && index < text.length) {
        if (text[index] == '`') {
            var runLength = 1
            while (index + runLength < text.length && text[index + runLength] == '`') {
                runLength++
            }
            openBacktickRun = if (openBacktickRun == 0) {
                runLength
            } else if (openBacktickRun == runLength) {
                0
            } else {
                openBacktickRun
            }
            index += runLength
        } else {
            index++
        }
    }
    return openBacktickRun != 0
}


private fun findClosingSingleDollar(text: String, start: Int): Int? {
    var index = start
    while (index < text.length) {
        val char = text[index]
        if (char == '\n' || char == '\r') return null
        if (char == '$' && (index == 0 || text[index - 1] != '\\')) {
            return index
        }
        index++
    }
    return null
}

private fun isCurrencyLikeDollarBody(body: String): Boolean {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.contains('\\')) return false
    return trimmed.any { it.isDigit() } &&
        trimmed.any { it.isLetter() } &&
        !trimmed.any { it in charArrayOf('+', '=', '^', '_', '*', '/') }
}

internal fun extractFencedCodeBlockContent(text: String): FencedCodeBlockContent {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    val lines = normalized.split('\n')
    val opening = lines.firstOrNull()?.let { fencedCodeOpeningLinePattern.matchEntire(it) }
    if (opening != null) {
        val marker = opening.groupValues[1]
        val language = opening.groupValues[2].trim().ifBlank { null }
        val bodyLines = lines.drop(1).toMutableList()
        if (bodyLines.isNotEmpty() && isFenceClosingLineForMarker(bodyLines.last(), marker)) {
            bodyLines.removeAt(bodyLines.lastIndex)
        }
        return FencedCodeBlockContent(
            language = language,
            code = bodyLines.joinToString("\n"),
        )
    }
    return FencedCodeBlockContent(language = null, code = normalized)
}

private fun isFenceClosingLineForMarker(line: String, marker: String): Boolean {
    val trimmed = line.trimStart()
    val markerChar = marker.first()
    var markerLength = 0
    while (markerLength < trimmed.length && trimmed[markerLength] == markerChar) {
        markerLength++
    }
    return markerLength >= marker.length && trimmed.substring(markerLength).isBlank()
}

private fun matchNativeListLine(line: String): NativeListLine? {
    val ordered = orderedListLinePattern.matchEntire(line)
    if (ordered != null) {
        val indent = ordered.groupValues[1]
        return NativeListLine(
            indentColumns = markdownIndentColumns(indent),
            ordered = true,
            number = ordered.groupValues[2].toIntOrNull() ?: 1,
            text = ordered.groupValues[3].trim(),
        )
    }

    val unordered = unorderedListLinePattern.matchEntire(line) ?: return null
    val indent = unordered.groupValues[1]
    return NativeListLine(
        indentColumns = markdownIndentColumns(indent),
        ordered = false,
        number = 1,
        text = unordered.groupValues[2].trim(),
    )
}

private fun markdownIndentColumns(indent: String): Int {
    return indent.sumOf { char -> if (char == '\t') 4 else 1 }
}

private fun resolveNativeListLineLevel(
    indentColumns: Int,
    indentStack: MutableList<Int>,
    previousLevel: Int,
): Int {
    val existingLevel = indentStack.indexOf(indentColumns)
    if (existingLevel >= 0) {
        while (indentStack.size > existingLevel + 1) {
            indentStack.removeAt(indentStack.lastIndex)
        }
        return existingLevel
    }

    val previousIndent = indentStack.getOrNull(previousLevel) ?: indentStack.lastOrNull() ?: 0
    if (indentColumns > previousIndent) {
        val level = previousLevel + 1
        while (indentStack.size > level) {
            indentStack.removeAt(indentStack.lastIndex)
        }
        indentStack.add(indentColumns)
        return level
    }

    val parentLevel = indentStack.indexOfLast { it < indentColumns }
    val level = if (parentLevel >= 0) parentLevel + 1 else 0
    while (indentStack.size > level) {
        indentStack.removeAt(indentStack.lastIndex)
    }
    if (level == 0) {
        if (indentStack.isEmpty()) {
            indentStack.add(indentColumns)
        } else {
            indentStack[0] = indentColumns
        }
    } else {
        indentStack.add(indentColumns)
    }
    return level
}

private fun isNativeListContinuation(line: String, itemLevel: Int): Boolean {
    if (line.isBlank()) return false
    val indentColumns = line.takeWhile { it == ' ' || it == '\t' }
        .sumOf { char -> if (char == '\t') 4 else 1 }
    return indentColumns >= (itemLevel + 1) * 4
}

private fun stripNativeListContinuation(line: String, itemLevel: Int): String {
    val columnsToDrop = (itemLevel + 1) * 4
    var index = 0
    var columns = 0
    while (index < line.length && columns < columnsToDrop) {
        val char = line[index]
        if (char != ' ' && char != '\t') break
        columns += if (char == '\t') 4 else 1
        index++
    }
    return line.drop(index).trim()
}

private fun appendNativeListContinuation(text: String, continuation: String): String {
    if (continuation.isBlank()) return text
    if (text.isBlank()) return continuation
    return "$text $continuation"
}

internal fun parseNativeStreamingMarkdownBlocks(
    text: String,
    segmentId: String = "segment",
    blockQuoteDepth: Int = 0,
): List<NativeStreamingMarkdownBlock>? {
    if (text.isEmpty()) return emptyList()
    val nativeText = prepareNativeMarkdownText(text) ?: return null
    if (hasUnsupportedNativeMarkdownForCompose(nativeText)) return null

    val normalized = nativeText.replace("\r\n", "\n").replace('\r', '\n')
    val lines = normalized.split('\n')
    val lineStarts = buildList {
        var offset = 0
        lines.forEach { line ->
            add(offset)
            offset += line.length + 1
        }
    }
    val blocks = mutableListOf<NativeStreamingMarkdownBlock>()
    var index = 0

    fun lineEnd(lineIndex: Int): Int = lineStarts[lineIndex] + lines[lineIndex].length

    fun stableId(type: NativeStreamingMarkdownBlockType, startOffset: Int): String =
        "$segmentId:native:${type.name.lowercase(Locale.ROOT)}:$startOffset"

    fun appendParagraph(startIndex: Int, contentLines: List<String>) {
        val content = normalizeHtmlLineBreakTags(contentLines.joinToString("\n")).trimEnd()
        if (content.isEmpty()) return
        val endIndex = startIndex + contentLines.size - 1
        val paragraphStartOffset = lineStarts[startIndex]

        fun appendParagraphFragment(fragment: String, fragmentStart: Int) {
            val trimmed = fragment.trim()
            if (trimmed.isEmpty()) return
            NativeStreamingMarkdownBlock(
                stableId = stableId(NativeStreamingMarkdownBlockType.Paragraph, paragraphStartOffset + fragmentStart),
                type = NativeStreamingMarkdownBlockType.Paragraph,
                start = paragraphStartOffset + fragmentStart,
                endExclusive = paragraphStartOffset + fragmentStart + fragment.length,
                text = trimmed,
            ).also(blocks::add)
        }

        fun appendParagraphTextAndImages(fragment: String, fragmentStart: Int) {
            var cursor = 0
            var appendedImage = false
            imageMarkdownPattern.findAll(fragment).forEach { image ->
                appendParagraphFragment(
                    fragment = fragment.substring(cursor, image.range.first),
                    fragmentStart = fragmentStart + cursor,
                )
                blocks.add(
                    NativeStreamingMarkdownBlock(
                        stableId = stableId(
                            NativeStreamingMarkdownBlockType.Image,
                            paragraphStartOffset + fragmentStart + image.range.first
                        ),
                        type = NativeStreamingMarkdownBlockType.Image,
                        start = paragraphStartOffset + fragmentStart + image.range.first,
                        endExclusive = paragraphStartOffset + fragmentStart + image.range.last + 1,
                        text = image.value,
                        imageAlt = image.groupValues[1],
                        imageUrl = stripMarkdownDestinationAngleBrackets(image.groupValues[2]),
                    )
                )
                cursor = image.range.last + 1
                appendedImage = true
            }

            if (appendedImage) {
                appendParagraphFragment(
                    fragment = fragment.substring(cursor),
                    fragmentStart = fragmentStart + cursor,
                )
            } else {
                appendParagraphFragment(
                    fragment = fragment,
                    fragmentStart = fragmentStart,
                )
            }
        }

        val mathBlocks = findNativeParagraphMathBlocks(content)
        if (mathBlocks == null) return

        if (mathBlocks.isNotEmpty()) {
            var cursor = 0
            mathBlocks.forEach { mathBlock ->
                appendParagraphTextAndImages(
                    fragment = content.substring(cursor, mathBlock.start),
                    fragmentStart = cursor,
                )
                blocks.add(
                    NativeStreamingMarkdownBlock(
                        stableId = stableId(NativeStreamingMarkdownBlockType.MathBlock, paragraphStartOffset + mathBlock.start),
                        type = NativeStreamingMarkdownBlockType.MathBlock,
                        start = paragraphStartOffset + mathBlock.start,
                        endExclusive = paragraphStartOffset + mathBlock.endExclusive,
                        text = mathBlock.text,
                    )
                )
                cursor = mathBlock.endExclusive
            }
            appendParagraphTextAndImages(
                fragment = content.substring(cursor),
                fragmentStart = cursor,
            )
            return
        }

        if (imageMarkdownPattern.containsMatchIn(content)) {
            appendParagraphTextAndImages(content, fragmentStart = 0)
        } else {
            NativeStreamingMarkdownBlock(
                stableId = stableId(NativeStreamingMarkdownBlockType.Paragraph, lineStarts[startIndex]),
                type = NativeStreamingMarkdownBlockType.Paragraph,
                start = lineStarts[startIndex],
                endExclusive = lineEnd(endIndex),
                text = content,
            ).also(blocks::add)
        }
    }

    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank()) {
            index++
            continue
        }

        val image = standaloneImageMarkdownLinePattern.matchEntire(line)
        if (image != null) {
            blocks.add(
                NativeStreamingMarkdownBlock(
                    stableId = stableId(NativeStreamingMarkdownBlockType.Image, lineStarts[index]),
                    type = NativeStreamingMarkdownBlockType.Image,
                    start = lineStarts[index],
                    endExclusive = lineEnd(index),
                    text = line.trim(),
                    imageAlt = image.groupValues[1],
                    imageUrl = stripMarkdownDestinationAngleBrackets(image.groupValues[2]),
                )
            )
            index++
            continue
        }

        if (TableUtils.isValidTableStart(lines, index)) {
            val startIndex = index
            val (tableLines, nextIndex) = TableUtils.extractTableLines(lines, index)
            blocks.add(
                NativeStreamingMarkdownBlock(
                    stableId = stableId(NativeStreamingMarkdownBlockType.Table, lineStarts[startIndex]),
                    type = NativeStreamingMarkdownBlockType.Table,
                    start = lineStarts[startIndex],
                    endExclusive = lineEnd(nextIndex - 1),
                    text = tableLines.joinToString("\n"),
                    tableLines = tableLines,
                )
            )
            index = nextIndex
            continue
        }

        val nativeMathBlockDelimiter = nativeMathBlockDelimiterForLine(line)
        if (nativeMathBlockDelimiter != null) {
            val startIndex = index
            val mathBlock = collectNativeMathBlock(lines, startIndex, nativeMathBlockDelimiter) ?: return null
            val endIndex = mathBlock.endIndex
            blocks.add(
                NativeStreamingMarkdownBlock(
                    stableId = stableId(NativeStreamingMarkdownBlockType.MathBlock, lineStarts[startIndex]),
                    type = NativeStreamingMarkdownBlockType.MathBlock,
                    start = lineStarts[startIndex],
                    endExclusive = lineEnd(endIndex),
                    text = lines.subList(startIndex, endIndex + 1).joinToString("\n").trim(),
                )
            )
            index = endIndex + 1
            continue
        }

        val fencedCode = fencedCodeOpeningLinePattern.matchEntire(line)
        if (fencedCode != null) {
            val startIndex = index
            val marker = fencedCode.groupValues[1]
            index++
            while (index < lines.size && !isFenceClosingLineForMarker(lines[index], marker)) {
                index++
            }
            if (index < lines.size) {
                index++
            }

            val endIndex = (index - 1).coerceAtLeast(startIndex)
            val rawBlock = lines.subList(startIndex, index).joinToString("\n")
            val codeBlock = extractFencedCodeBlockContent(rawBlock)
            blocks.add(
                NativeStreamingMarkdownBlock(
                    stableId = stableId(NativeStreamingMarkdownBlockType.CodeBlock, lineStarts[startIndex]),
                    type = NativeStreamingMarkdownBlockType.CodeBlock,
                    start = lineStarts[startIndex],
                    endExclusive = lineEnd(endIndex),
                    text = rawBlock,
                    language = codeBlock.language,
                    code = codeBlock.code,
                )
            )
            continue
        }

        val atxHeading = atxHeadingLinePattern.matchEntire(line)
        if (atxHeading != null) {
            blocks.add(
                NativeStreamingMarkdownBlock(
                    stableId = stableId(NativeStreamingMarkdownBlockType.Heading, lineStarts[index]),
                    type = NativeStreamingMarkdownBlockType.Heading,
                    start = lineStarts[index],
                    endExclusive = lineEnd(index),
                    text = atxHeading.groupValues[2].trim(),
                    level = atxHeading.groupValues[1].length,
                )
            )
            index++
            continue
        }

        if (index + 1 < lines.size) {
            val underline = setextUnderlineLinePattern.matchEntire(lines[index + 1])
            if (underline != null) {
                blocks.add(
                    NativeStreamingMarkdownBlock(
                        stableId = stableId(NativeStreamingMarkdownBlockType.Heading, lineStarts[index]),
                        type = NativeStreamingMarkdownBlockType.Heading,
                        start = lineStarts[index],
                        endExclusive = lineEnd(index + 1),
                        text = line.trim(),
                        level = if (underline.groupValues[1].startsWith("=")) 1 else 2,
                    )
                )
                index += 2
                continue
            }
        }

        if (horizontalRuleLinePattern.matches(line)) {
            blocks.add(
                NativeStreamingMarkdownBlock(
                    stableId = stableId(NativeStreamingMarkdownBlockType.HorizontalRule, lineStarts[index]),
                    type = NativeStreamingMarkdownBlockType.HorizontalRule,
                    start = lineStarts[index],
                    endExclusive = lineEnd(index),
                )
            )
            index++
            continue
        }

        val quote = blockQuoteLinePattern.matchEntire(line)
        if (quote != null) {
            val startIndex = index
            val quoteLines = mutableListOf(quote.groupValues[1])
            index++
            while (index < lines.size) {
                val nextQuote = blockQuoteLinePattern.matchEntire(lines[index]) ?: break
                quoteLines.add(nextQuote.groupValues[1])
                index++
            }
            blocks.add(
                run {
                    val quoteText = normalizeHtmlLineBreakTags(quoteLines.joinToString("\n")).trimEnd()
                    val quoteChildren = if (blockQuoteDepth < MAX_NATIVE_BLOCK_QUOTE_DEPTH && quoteText.isNotBlank()) {
                        parseNativeStreamingMarkdownBlocks(
                            text = quoteText,
                            segmentId = "$segmentId:quote:${lineStarts[startIndex]}",
                            blockQuoteDepth = blockQuoteDepth + 1,
                        ).orEmpty()
                    } else {
                        emptyList()
                    }
                    NativeStreamingMarkdownBlock(
                        stableId = stableId(NativeStreamingMarkdownBlockType.BlockQuote, lineStarts[startIndex]),
                        type = NativeStreamingMarkdownBlockType.BlockQuote,
                        start = lineStarts[startIndex],
                        endExclusive = lineEnd(index - 1),
                        text = quoteText,
                        children = quoteChildren,
                    )
                }
            )
            continue
        }

        val listLine = matchNativeListLine(line)
        if (listLine != null) {
            val startIndex = index
            val listIndentColumns = mutableListOf(listLine.indentColumns)
            val listItems = mutableListOf(
                NativeStreamingListItem(
                    text = normalizeHtmlLineBreakTags(listLine.text),
                    level = 0,
                    ordered = listLine.ordered,
                    number = listLine.number,
                )
            )
            index++
            var sawBlankBeforeListContinuation = false
            while (index < lines.size) {
                val nextLine = lines[index]
                if (nextLine.isBlank()) {
                    index++
                    sawBlankBeforeListContinuation = true
                    continue
                }

                val next = matchNativeListLine(nextLine)
                if (next != null) {
                    sawBlankBeforeListContinuation = false
                    val level = resolveNativeListLineLevel(
                        indentColumns = next.indentColumns,
                        indentStack = listIndentColumns,
                        previousLevel = listItems.lastOrNull()?.level ?: 0,
                    )
                    listItems.add(
                        NativeStreamingListItem(
                            text = normalizeHtmlLineBreakTags(next.text),
                            level = level,
                            ordered = next.ordered,
                            number = next.number,
                        )
                    )
                    index++
                    continue
                }

                val last = listItems.lastOrNull()
                if (last != null && isNativeListContinuation(nextLine, last.level)) {
                    val continuation = stripNativeListContinuation(nextLine, last.level)
                    val normalizedContinuation = normalizeHtmlLineBreakTags(continuation)
                    listItems[listItems.lastIndex] = if (sawBlankBeforeListContinuation) {
                        last.copy(
                            children = last.children + parseNativeStreamingMarkdownBlocks(
                                text = normalizedContinuation,
                                segmentId = "$segmentId:list-item:${lineStarts[index]}",
                                blockQuoteDepth = blockQuoteDepth,
                            ).orEmpty(),
                        )
                    } else {
                        last.copy(
                            text = appendNativeListContinuation(last.text, normalizedContinuation)
                        )
                    }
                    sawBlankBeforeListContinuation = false
                    index++
                    continue
                }

                if (startsNativeMarkdownBlock(nextLine)) break
                break
            }
            blocks.add(
                NativeStreamingMarkdownBlock(
                    stableId = stableId(NativeStreamingMarkdownBlockType.ListBlock, lineStarts[startIndex]),
                    type = NativeStreamingMarkdownBlockType.ListBlock,
                    start = lineStarts[startIndex],
                    endExclusive = lineEnd(index - 1),
                    ordered = listLine.ordered,
                    items = listItems.map { it.text },
                    listItems = listItems,
                )
            )
            continue
        }

        val paragraphStart = index
        val paragraphLines = mutableListOf<String>()
        while (index < lines.size) {
            val current = lines[index]
            if (current.isBlank() || startsNativeMarkdownBlock(current)) break
            if (index + 1 < lines.size && setextUnderlineLinePattern.matches(lines[index + 1])) break
            paragraphLines.add(current)
            index++
        }
        appendParagraph(paragraphStart, paragraphLines)
    }

    return blocks
}

private fun startsNativeMarkdownBlock(line: String): Boolean {
    return atxHeadingLinePattern.matches(line) ||
        standaloneImageMarkdownLinePattern.matches(line) ||
        nativeMathBlockDelimiterForLine(line) != null ||
        fencedCodeOpeningLinePattern.matches(line) ||
        horizontalRuleLinePattern.matches(line) ||
        blockQuoteLinePattern.matches(line) ||
        unorderedListLinePattern.matches(line) ||
        orderedListLinePattern.matches(line)
}

private fun hasUnsupportedNativeMarkdownForCompose(text: String): Boolean {
    return hasUnsupportedNativeMathForCompose(text) ||
        referenceDefinitionPattern.containsMatchIn(text) ||
        hasUnsupportedHtmlTag(text) ||
        hasUnsupportedHtmlEntity(text) ||
        hasUnsupportedImageMarkdown(text)
}

private fun hasUnsupportedNativeMathForCompose(text: String): Boolean {
    if (!hasInlineMathSyntax(text)) return false

    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    val lines = normalized.lines()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (!lineHasMathSyntaxForNativeGate(line) || TableUtils.isTableDataRow(line)) {
            index++
            continue
        }

        val delimiter = nativeMathBlockDelimiterForLine(line)
        if (delimiter != null) {
            val mathBlock = collectNativeMathBlock(lines, index, delimiter)
            if (mathBlock != null) {
                index = mathBlock.endIndex + 1
                continue
            }
        }

        if (lineHasBlockMathSyntaxForNativeGate(line)) {
            val paragraphMathBlocks = findNativeParagraphMathBlocks(line)
            if (paragraphMathBlocks != null && paragraphMathBlocks.isNotEmpty()) {
                index++
                continue
            }
            return true
        }
        index++
    }
    return false
}

private fun lineHasMathSyntaxForNativeGate(line: String): Boolean {
    return lineHasInlineMathSyntax(line) ||
        lineHasEscapedInlineMathSyntax(line) ||
        lineHasBlockMathSyntaxForNativeGate(line)
}

private fun lineHasBlockMathSyntaxForNativeGate(line: String): Boolean {
    return lineHasDollarBlockMathSyntax(line) || lineHasEscapedBlockMathSyntax(line)
}

private fun lineHasDollarBlockMathSyntax(line: String): Boolean {
    var index = 0
    while (index <= line.length - 2) {
        if (line.startsWith("$$", index) && (index == 0 || line[index - 1] != '\\')) {
            val close = line.indexOf("$$", index + 2)
            if (close >= 0) return true
            return false
        }
        index++
    }
    return false
}

private fun lineHasInlineMathSyntax(line: String): Boolean {
    var index = 0
    while (index < line.length) {
        if (line[index] == '$' && (index == 0 || line[index - 1] != '\\')) {
            val close = findClosingSingleDollar(line, index + 1) ?: return false
            val body = line.substring(index + 1, close)
            if (!isCurrencyLikeDollarBody(body)) return true
            index = close + 1
            continue
        }
        index++
    }
    return false
}

private fun lineHasEscapedInlineMathSyntax(line: String): Boolean {
    var index = line.indexOf("\\(")
    while (index >= 0) {
        val close = line.indexOf("\\)", index + 2)
        if (close >= 0) return true
        index = line.indexOf("\\(", index + 2)
    }
    return false
}

private fun lineHasEscapedBlockMathSyntax(line: String): Boolean {
    var index = line.indexOf("\\[")
    while (index >= 0) {
        val close = line.indexOf("\\]", index + 2)
        if (close >= 0) return true
        index = line.indexOf("\\[", index + 2)
    }
    return false
}

private fun hasUnsupportedHtmlTag(text: String): Boolean {
    return htmlTagPattern.findAll(text).any { match ->
        !htmlLineBreakTagPattern.matches(match.value)
    }
}

private fun normalizeHtmlLineBreakTags(text: String): String {
    return htmlLineBreakTagPattern.replace(text, "\n")
}

private fun hasUnsupportedHtmlEntity(text: String): Boolean {
    return htmlEntityPattern.findAll(text).any { match ->
        !supportedHtmlEntityPattern.matches(match.value)
    }
}

private fun hasUnsupportedImageMarkdown(text: String): Boolean {
    if (!text.contains("![")) return false

    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    if (normalized.lines().any { line ->
            line.contains("![") &&
                TableUtils.isTableDataRow(line) &&
                TableUtils.parseTableRow(line).any { cell ->
                    cell.contains("![") && parseTableCellMarkdownParts(cell) == null
                }
        }
    ) {
        return true
    }

    val supportedRanges = imageMarkdownPattern.findAll(text).map { it.range }.toList()
    if (supportedRanges.isEmpty()) return true

    return Regex("""!\[""").findAll(text).any { opening ->
        supportedRanges.none { range -> opening.range.first in range }
    }
}

internal fun isTableCellEmbeddedMathBlock(blocks: List<StreamBlock>, index: Int): Boolean {
    return blocks.getOrNull(index) is StreamBlock.MathBlock && isTableCellEmbeddedMathToken(blocks, index)
}

internal fun isTableCellEmbeddedMathToken(blocks: List<StreamBlock>, index: Int): Boolean {
    val block = blocks.getOrNull(index)
    if (block !is StreamBlock.MathBlock && block !is StreamBlock.MathInline) return false
    val before = collectSameLineBeforeBlock(blocks, index)
    val after = collectSameLineAfterBlock(blocks, index)
    if (!before.contains('|') || !after.contains('|')) return false

    val line = before + block.text + after
    return TableUtils.isTableDataRow(line)
}

private fun collectSameLineBeforeBlock(blocks: List<StreamBlock>, index: Int): String {
    val out = StringBuilder()
    for (cursor in index - 1 downTo 0) {
        val text = blocks[cursor].text
        val lineBreak = text.lastIndexOf('\n')
        if (lineBreak >= 0) {
            out.insert(0, text.substring(lineBreak + 1))
            break
        }
        out.insert(0, text)
    }
    return out.toString()
}

private fun collectSameLineAfterBlock(blocks: List<StreamBlock>, index: Int): String {
    val out = StringBuilder()
    for (cursor in index + 1 until blocks.size) {
        val text = blocks[cursor].text
        val lineBreak = text.indexOf('\n')
        if (lineBreak >= 0) {
            out.append(text.substring(0, lineBreak))
            break
        }
        out.append(text)
    }
    return out.toString()
}

private fun buildSegments(blocks: List<StreamBlock>): List<RenderSegment> {
    val result = mutableListOf<RenderSegment>()
    val inlineParts = mutableListOf<InlineRenderPart>()
    var inlineStartId: String? = null
    var inlineEndId: String? = null

    fun flushInline() {
        if (inlineParts.isEmpty()) return
        val stableId = buildString {
            append(inlineStartId ?: "inline")
            append(':')
            append(inlineEndId ?: inlineStartId ?: "tail")
        }
        val singleTextPart = inlineParts.singleOrNull() as? InlineRenderPart.Text
        if (singleTextPart != null) {
            result.add(RenderSegment.InlineText(stableId = stableId, text = singleTextPart.text))
        } else {
            result.add(RenderSegment.InlineParts(stableId = stableId, parts = inlineParts.toList()))
        }
        inlineParts.clear()
        inlineStartId = null
        inlineEndId = null
    }

    fun markInlineRange(block: StreamBlock) {
        if (inlineStartId == null) inlineStartId = block.stableId
        inlineEndId = block.stableId
    }

    fun appendInlineText(block: StreamBlock) {
        val text = block.text
        if (text.isEmpty()) return
        markInlineRange(block)
        val last = inlineParts.lastOrNull()
        if (last is InlineRenderPart.Text) {
            inlineParts[inlineParts.lastIndex] = last.copy(text = last.text + text)
        } else {
            inlineParts.add(InlineRenderPart.Text(text))
        }
    }

    blocks.forEachIndexed { index, block ->
        when (block) {
            is StreamBlock.PlainText -> {
                appendInlineText(block)
            }

            is StreamBlock.MathInline -> {
                if (isTableCellEmbeddedMathToken(blocks, index)) {
                    appendInlineText(block)
                } else {
                    markInlineRange(block)
                    inlineParts.add(InlineRenderPart.Math(block))
                }
            }

            is StreamBlock.MathBlock -> {
                if (isTableCellEmbeddedMathToken(blocks, index)) {
                    appendInlineText(block)
                } else {
                    flushInline()
                    result.add(RenderSegment.BlockOnly(stableId = block.stableId, block = block))
                }
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
