package com.android.everytalk.ui.components.streaming

import android.content.ClipData
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.ui.components.ChatMarkdownTextStyle
import com.android.everytalk.ui.components.EnhancedMarkdownText
import com.android.everytalk.ui.components.StableMarkdownText
import com.android.everytalk.ui.components.content.CodeBlockCard
import com.android.everytalk.ui.components.table.InlineMarkdownParser
import com.android.everytalk.ui.components.table.TableUtils
import java.util.Locale
import kotlinx.coroutines.launch

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

internal enum class StreamingMarkdownRenderPath {
    PlainText,
    ComposeInlineMarkdown,
    FullMarkdown,
}

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
)

data class NativeStreamingListItem(
    val text: String,
    val level: Int = 0,
    val ordered: Boolean = false,
    val number: Int = 1,
)

internal data class ChatGptHeadingTextSpec(
    val fontSize: TextUnit,
    val lineHeight: TextUnit,
    val fontWeight: FontWeight,
)

internal fun chatGptHeadingTextSpecForLevel(level: Int): ChatGptHeadingTextSpec {
    val headingLevel = level.coerceIn(1, 6)
    return ChatGptHeadingTextSpec(
        fontSize = ChatMarkdownTextStyle.headingFontSizeSp(headingLevel).sp,
        lineHeight = ChatMarkdownTextStyle.headingLineHeightSp(headingLevel).sp,
        fontWeight = if (headingLevel <= 3) FontWeight.SemiBold else FontWeight.Normal,
    )
}

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
    viewModel: AppViewModel,
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
        messageOutputType,
    ) {
        when {
            messageOutputType == "code" -> emptyList()
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
            )
        }
        return
    }

    val hasSplitBlocks = committedBlocks.isNotEmpty() || tailBlocks.isNotEmpty()
    val committedSegments = remember(committedBlocksHash, committedBlocks, messageOutputType) {
        if (!hasSplitBlocks || committedBlocks.isEmpty() || messageOutputType == "code") {
            emptyList()
        } else {
            buildSegments(committedBlocks)
        }
    }
    val tailSegments = remember(tailBlocksHash, tailBlocks, messageOutputType) {
        if (!hasSplitBlocks || tailBlocks.isEmpty() || messageOutputType == "code") {
            emptyList()
        } else {
            buildSegments(tailBlocks)
        }
    }
    val fallbackSegments = remember(blocks, messageOutputType) {
        if (blocks.isEmpty() || messageOutputType == "code") {
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
        EnhancedMarkdownText(
            message = message,
            style = style,
            color = color,
            isStreaming = false,
            messageOutputType = messageOutputType,
            onLongPress = streamMarkdownLongPressHandler(message.sender, onLongPress),
            onImageClick = onImageClick,
            onCodePreviewRequested = onCodePreviewRequested,
            onCodeCopied = onCodeCopied,
            viewModel = viewModel,
            disableStreamingSubscription = true,
        )
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
                                        )
                                    } else {
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
                                            renderPhaseKey = renderPhaseKey,
                                            onCodePreviewRequested = onCodePreviewRequested,
                                            onCodeCopied = onCodeCopied,
                                        )
                                    }
                                }
                            }
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
                                            renderPhaseKey = renderPhaseKey,
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
                                            renderPhaseKey = renderPhaseKey,
                                            onCodePreviewRequested = onCodePreviewRequested,
                                            onCodeCopied = onCodeCopied,
                                        )
                                    }
                                }

                                is StreamBlock.CodeBlock -> {
                                    CodeBlockSegment(
                                        block = block,
                                        onCodePreviewRequested = onCodePreviewRequested,
                                        onCodeCopied = onCodeCopied,
                                    )
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
                                        renderPhaseKey = renderPhaseKey,
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
    onCodePreviewRequested: ((String, String) -> Unit)?,
    onCodeCopied: (() -> Unit)?,
) {
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val codeBlock = remember(block.text) {
        extractFencedCodeBlockContent(block.text)
    }
    CodeBlockCard(
        language = codeBlock.language,
        code = codeBlock.code,
        modifier = Modifier.padding(vertical = 4.dp),
        isStreaming = false,
        onPreviewRequested = if (onCodePreviewRequested != null) {
            { onCodePreviewRequested(codeBlock.language.orEmpty(), codeBlock.code) }
        } else {
            null
        },
        onCopy = {
            coroutineScope.launch {
                clipboard.setClipEntry(
                    ClipEntry(
                        ClipData.newPlainText("code", codeBlock.code)
                    )
                )
                onCodeCopied?.invoke()
            }
        },
    )
}

@Composable
private fun NativeMarkdownBlocksSegment(
    blocks: List<NativeStreamingMarkdownBlock>,
    style: TextStyle,
    color: Color,
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
                                .padding(vertical = 6.dp),
                            color = color.copy(alpha = 0.22f),
                        )
                    }

                    NativeStreamingMarkdownBlockType.BlockQuote -> {
                        NativeBlockQuote(
                            text = block.text,
                            style = style,
                            color = color,
                        )
                    }

                    NativeStreamingMarkdownBlockType.ListBlock -> {
                        NativeListBlock(
                            items = block.items,
                            listItems = block.listItems,
                            ordered = block.ordered,
                            style = style,
                            color = color,
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
            color = color,
            fontSize = headingSpec.fontSize,
            lineHeight = headingSpec.lineHeight,
            fontWeight = headingSpec.fontWeight,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
    )
}

@Composable
private fun NativeBlockQuote(
    text: String,
    style: TextStyle,
    color: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .heightIn(min = 20.dp)
                .background(color.copy(alpha = 0.35f)),
        )
        Spacer(modifier = Modifier.width(8.dp))
        NativeInlineText(
            text = text,
            style = style,
            color = color.copy(alpha = 0.88f),
        )
    }
}

@Composable
private fun NativeListBlock(
    items: List<String>,
    listItems: List<NativeStreamingListItem>,
    ordered: Boolean,
    style: TextStyle,
    color: Color,
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
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        rows.forEachIndexed { index, item ->
            val topSpacing = nativeListItemTopSpacing(rows, index)
            if (topSpacing > 0.dp) {
                Spacer(modifier = Modifier.height(topSpacing))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = (ChatMarkdownTextStyle.LIST_NESTED_INDENT_DP *
                            item.level.coerceAtLeast(0)).dp
                    )
            ) {
                NativeListMarker(
                    item = item,
                    style = style,
                    color = color,
                )
                NativeInlineText(
                    text = item.text,
                    style = style,
                    color = color,
                )
            }
        }
    }
}

internal fun nativeListItemTopSpacing(
    rows: List<NativeStreamingListItem>,
    index: Int,
): Dp {
    if (index <= 0 || index >= rows.size) return 0.dp
    val current = rows[index]
    return if (current.level <= 0) {
        ChatMarkdownTextStyle.LIST_TOP_LEVEL_ITEM_SPACING_DP.dp
    } else {
        ChatMarkdownTextStyle.LIST_NESTED_TOP_SPACING_DP.dp
    }
}

@Composable
private fun NativeListMarker(
    item: NativeStreamingListItem,
    style: TextStyle,
    color: Color,
) {
    val markerWidth = ChatMarkdownTextStyle.LIST_MARKER_WIDTH_DP.dp
    if (item.ordered) {
        androidx.compose.material3.Text(
            text = "${item.number}.",
            style = style.copy(
                color = color,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            modifier = Modifier.width(markerWidth),
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
) {
    if (supportedInlineMarkdownPattern.containsMatchIn(text)) {
        ComposeInlineMarkdownSegment(
            text = text,
            style = style,
            color = color,
        )
    } else {
        PlainTextSegment(
            text = text,
            style = style,
            color = color,
        )
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
    onLongPress: (() -> Unit)?,
    onImageClick: ((String) -> Unit)?,
    renderPhaseKey: String,
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
        onLongPress = streamMarkdownLongPressHandler(message.sender, onLongPress),
        onImageClick = onImageClick,
        onCodePreviewRequested = onCodePreviewRequested,
        onCodeCopied = onCodeCopied,
        viewModel = viewModel,
        contentOverride = text,
        contentKeyOverride = "${message.id}:$segmentId:$renderPhaseKey",
        disableStreamingSubscription = true,
    )
}

@Composable
private fun PlainTextSegment(
    text: String,
    style: TextStyle,
    color: Color,
) {
    androidx.compose.material3.Text(
        text = text,
        style = style.copy(
            color = color,
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        ),
    )
}

@Composable
private fun ComposeInlineMarkdownSegment(
    text: String,
    style: TextStyle,
    color: Color,
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
        style = style.copy(
            color = color,
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        ),
    )
}

private val supportedInlineMarkdownPattern = Regex(
    """(\*\*|__|\*(?=[^\s*])|_(?=[^\s_])|~~|`)"""
)

private val fullMarkdownOnlyPattern = Regex("""(!\[|\]\(|^\s{0,3}#{1,6}\s+|^\s*[-*+]\s+|^\s*\d+[.)]\s+|^\s*>\s?|^\s{0,3}(-{3,}|\*{3,}|_{3,})\s*$)""", RegexOption.MULTILINE)

private val fencedCodeStartPattern = Regex("""^\s{0,3}(```|~~~)""", RegexOption.MULTILINE)
private val setextHeadingPattern = Regex("""^\S[^\n]*(?:\n|\r\n?)\s{0,3}(={3,}|-{3,})\s*$""", RegexOption.MULTILINE)
private val referenceLinkPattern = Regex("""!?\[[^\]]+]\[[^\]]*]|\[[^\]]+]:\s+\S+""", RegexOption.MULTILINE)
private val autolinkPattern = Regex("""<https?://[^>\s]+>""")
private val htmlTagPattern = Regex("""</?[A-Za-z][A-Za-z0-9:-]*(?:\s+[^>]*)?/?>""")
private val htmlEntityPattern = Regex("""&(?:[A-Za-z][A-Za-z0-9]+|#\d+|#x[0-9A-Fa-f]+);""")
private val markdownLinkPattern = Regex("""!?\[[^\]]+]\([^)]+?\)""")

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
    val level: Int,
    val ordered: Boolean,
    val number: Int,
    val text: String,
)

internal fun resolveStreamingMarkdownRenderPath(text: String): StreamingMarkdownRenderPath {
    if (text.isEmpty()) return StreamingMarkdownRenderPath.PlainText
    if (hasFullMarkdownSyntax(text)) return StreamingMarkdownRenderPath.FullMarkdown
    return if (supportedInlineMarkdownPattern.containsMatchIn(text)) {
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
        referenceLinkPattern.containsMatchIn(text) ||
        autolinkPattern.containsMatchIn(text) ||
        htmlTagPattern.containsMatchIn(text) ||
        htmlEntityPattern.containsMatchIn(text) ||
        markdownLinkPattern.containsMatchIn(text)
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
    var dollarCount = 0
    var index = 0
    while (index < text.length) {
        if (text[index] == '$' && (index == 0 || text[index - 1] != '\\')) {
            dollarCount++
        }
        index++
    }
    return dollarCount > 0
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
        return NativeListLine(
            level = markdownIndentLevel(ordered.groupValues[1]),
            ordered = true,
            number = ordered.groupValues[2].toIntOrNull() ?: 1,
            text = ordered.groupValues[3].trim(),
        )
    }

    val unordered = unorderedListLinePattern.matchEntire(line) ?: return null
    return NativeListLine(
        level = markdownIndentLevel(unordered.groupValues[1]),
        ordered = false,
        number = 1,
        text = unordered.groupValues[2].trim(),
    )
}

private fun markdownIndentLevel(indent: String): Int {
    val columns = indent.sumOf { char -> if (char == '\t') 4 else 1 }
    return (columns / 4).coerceAtLeast(0)
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
): List<NativeStreamingMarkdownBlock>? {
    if (text.isEmpty()) return emptyList()
    if (hasUnsupportedNativeMarkdownForCompose(text)) return null

    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
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
        val content = contentLines.joinToString("\n").trimEnd()
        if (content.isEmpty()) return
        val endIndex = startIndex + contentLines.size - 1
        blocks.add(
            NativeStreamingMarkdownBlock(
                stableId = stableId(NativeStreamingMarkdownBlockType.Paragraph, lineStarts[startIndex]),
                type = NativeStreamingMarkdownBlockType.Paragraph,
                start = lineStarts[startIndex],
                endExclusive = lineEnd(endIndex),
                text = content,
            )
        )
    }

    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank()) {
            index++
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
                NativeStreamingMarkdownBlock(
                    stableId = stableId(NativeStreamingMarkdownBlockType.BlockQuote, lineStarts[startIndex]),
                    type = NativeStreamingMarkdownBlockType.BlockQuote,
                    start = lineStarts[startIndex],
                    endExclusive = lineEnd(index - 1),
                    text = quoteLines.joinToString("\n").trimEnd(),
                )
            )
            continue
        }

        val listLine = matchNativeListLine(line)
        if (listLine != null) {
            val startIndex = index
            val listItems = mutableListOf(
                NativeStreamingListItem(
                    text = listLine.text,
                    level = listLine.level,
                    ordered = listLine.ordered,
                    number = listLine.number,
                )
            )
            index++
            while (index < lines.size) {
                val nextLine = lines[index]
                val next = matchNativeListLine(nextLine)
                if (next != null) {
                    listItems.add(
                        NativeStreamingListItem(
                            text = next.text,
                            level = next.level,
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
                    listItems[listItems.lastIndex] = last.copy(
                        text = appendNativeListContinuation(last.text, continuation)
                    )
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
        horizontalRuleLinePattern.matches(line) ||
        blockQuoteLinePattern.matches(line) ||
        unorderedListLinePattern.matches(line) ||
        orderedListLinePattern.matches(line)
}

private fun hasUnsupportedNativeMarkdownForCompose(text: String): Boolean {
    return hasMarkdownTable(text) ||
        hasInlineMathSyntax(text) ||
        hasNativeHeadingSyntax(text) ||
        fencedCodeStartPattern.containsMatchIn(text) ||
        referenceLinkPattern.containsMatchIn(text) ||
        autolinkPattern.containsMatchIn(text) ||
        htmlTagPattern.containsMatchIn(text) ||
        htmlEntityPattern.containsMatchIn(text) ||
        markdownLinkPattern.containsMatchIn(text)
}

private fun hasNativeHeadingSyntax(text: String): Boolean {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
    return lines.indices.any { index ->
        atxHeadingLinePattern.matches(lines[index]) ||
            (
                index + 1 < lines.size &&
                    lines[index].isNotBlank() &&
                    setextUnderlineLinePattern.matches(lines[index + 1])
            )
    }
}

internal fun isTableCellEmbeddedMathBlock(blocks: List<StreamBlock>, index: Int): Boolean {
    val block = blocks.getOrNull(index) as? StreamBlock.MathBlock ?: return false
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

    blocks.forEachIndexed { index, block ->
        when (block) {
            is StreamBlock.PlainText,
            is StreamBlock.MathInline -> {
                if (inlineStartId == null) inlineStartId = block.stableId
                inlineEndId = block.stableId
                inlineBuffer.append(block.text)
            }

            is StreamBlock.MathBlock -> {
                if (isTableCellEmbeddedMathBlock(blocks, index)) {
                    if (inlineStartId == null) inlineStartId = block.stableId
                    inlineEndId = block.stableId
                    inlineBuffer.append(block.text)
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
