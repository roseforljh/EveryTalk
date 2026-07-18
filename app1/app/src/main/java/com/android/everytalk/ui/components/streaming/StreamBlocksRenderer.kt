package com.android.everytalk.ui.components.streaming

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.components.ChatMarkdownTextStyle
import com.android.everytalk.ui.components.ProportionalAsyncImage
import com.android.everytalk.ui.components.markdown.StableLatexRenderer
import com.android.everytalk.ui.components.math.MathInline
import com.android.everytalk.ui.components.table.InlineMarkdownParser
import com.android.everytalk.ui.components.table.TableRenderer
import com.android.everytalk.ui.components.table.TableUtils
import com.android.everytalk.ui.components.table.parseTableCellMarkdownParts
import com.android.everytalk.ui.components.table.stripMarkdownDestinationAngleBrackets
import java.util.Locale

internal sealed interface InlineRenderPart {
    data class Text(val text: String) : InlineRenderPart
    data class Math(val block: StreamBlock.MathInline) : InlineRenderPart
    data class Image(val alt: String, val url: String) : InlineRenderPart
}

internal data class InlinePartsTextModel(
    val annotatedText: AnnotatedString,
    val mathPlaceholders: List<InlineMathPlaceholder>,
    val imagePlaceholders: List<InlineImagePlaceholder>,
)

internal data class InlineMathPlaceholder(
    val id: String,
    val latex: String,
    val width: TextUnit,
    val height: TextUnit,
)

internal data class InlineImagePlaceholder(
    val id: String,
    val alt: String,
    val url: String,
    val width: TextUnit,
    val height: TextUnit,
)

internal enum class StreamingMarkdownRenderPath {
    PlainText,
    ComposeInlineMarkdown,
    ComposeBlockMarkdown,
}

internal enum class StreamMathRenderPath {
    RawText,
    NativeLatex,
}

internal fun resolveStreamMathRenderPath(block: StreamBlock.MathBlock): StreamMathRenderPath {
    return when (block.state) {
        MathBlockState.RENDERED -> StreamMathRenderPath.NativeLatex
        MathBlockState.RAW,
        MathBlockState.PARSING,
        MathBlockState.FAILED -> StreamMathRenderPath.RawText
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
    val textAlign: TextAlign = TextAlign.Start,
)

data class NativeStreamingListItem(
    val text: String,
    val level: Int = 0,
    val ordered: Boolean = false,
    val number: Int = 1,
    val children: List<NativeStreamingMarkdownBlock> = emptyList(),
    val textAlign: TextAlign = TextAlign.Start,
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
fun UnifiedMarkdownRenderer(
    markdown: String,
    contentKey: String,
    modifier: Modifier = Modifier,
    sender: Sender = Sender.AI,
    nativeMarkdownBlocks: List<NativeStreamingMarkdownBlock> = emptyList(),
    committedNativeMarkdownBlocks: List<NativeStreamingMarkdownBlock> = emptyList(),
    tailNativeMarkdownBlocks: List<NativeStreamingMarkdownBlock> = emptyList(),
    nativeMarkdownBlocksHash: String = "",
    committedNativeMarkdownBlocksHash: String = "",
    tailNativeMarkdownBlocksHash: String = "",
    style: TextStyle,
    color: Color,
    isStreaming: Boolean = false,
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
        markdown,
        contentKey,
    ) {
        when {
            hasNativeSplitBlocks -> committedNativeMarkdownBlocks + tailNativeMarkdownBlocks
            nativeMarkdownBlocks.isNotEmpty() -> nativeMarkdownBlocks
            else -> parseUnifiedStreamingMarkdownBlocks(markdown, contentKey)
        }
    }

    if (nativeBlocks.isEmpty()) return

    Box(modifier = modifier) {
        StreamTextSelectionContainer(enabled = sender == Sender.AI) {
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
                            textAlign = block.textAlign,
                            onImageClick = onImageClick,
                        )
                    }

                    NativeStreamingMarkdownBlockType.Heading -> {
                        NativeHeadingText(
                            text = block.text,
                            level = block.level,
                            color = color,
                            textAlign = block.textAlign,
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
                            textAlign = block.textAlign,
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
    textAlign: TextAlign = TextAlign.Start,
) {
    val headingLevel = level.coerceIn(1, 6)
    val headingStyle = when (headingLevel) {
        1 -> MaterialTheme.typography.titleLarge
        2 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleSmall
    }
    val headingSpec = chatGptHeadingTextSpecForLevel(headingLevel)
    val headingColor = if (color == Color.Unspecified) {
        color
    } else {
        color.copy(alpha = headingSpec.colorAlpha)
    }
    NativeInlineText(
        text = text,
        style = headingStyle.copy(
            color = headingColor,
            fontSize = headingSpec.fontSize,
            lineHeight = headingSpec.lineHeight,
            fontWeight = headingSpec.fontWeight,
            fontStyle = headingSpec.fontStyle,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
        color = headingColor,
        lineHeightSp = ChatMarkdownTextStyle.headingLineHeightSp(headingLevel),
        textAlign = textAlign,
    )
}

@Composable
private fun NativeBlockQuote(
    text: String,
    children: List<NativeStreamingMarkdownBlock>,
    style: TextStyle,
    color: Color,
    textAlign: TextAlign = TextAlign.Start,
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
                    textAlign = textAlign,
                    onImageClick = onImageClick,
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
                    textAlign = item.textAlign,
                    onImageClick = onImageClick,
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
    textAlign: TextAlign = TextAlign.Start,
    onImageClick: ((String) -> Unit)? = null,
) {
    val inlineParts = remember(text) {
        buildNativeInlinePartsForText(text)
    }
    if (inlineParts != null) {
        InlinePartsSegment(
            parts = inlineParts,
            style = style,
            color = color,
            textAlign = textAlign,
            onImageClick = onImageClick,
        )
    } else if (supportedInlineMarkdownPattern.containsMatchIn(text)) {
        ComposeInlineMarkdownSegment(
            text = text,
            style = style,
            color = color,
            lineHeightSp = lineHeightSp,
            textAlign = textAlign,
        )
    } else {
        PlainTextSegment(
            text = text,
            style = style,
            color = color,
            lineHeightSp = lineHeightSp,
            textAlign = textAlign,
        )
    }
}

internal fun buildNativeInlinePartsForText(text: String): List<InlineRenderPart>? {
    val blocks = StreamBlockParser.parse(text, "native-inline").blocks
    val hasInlineImage = hasImageMarkdownOutsideInlineCode(text)
    if (blocks.none { it is StreamBlock.MathInline } && !hasInlineImage) return null

    val parts = mutableListOf<InlineRenderPart>()
    fun appendTextAndImages(rawText: String) {
        var cursor = 0
        var appendedImage = false
        imageMarkdownMatchesOutsideInlineCode(rawText).forEach { image ->
            val before = rawText.substring(cursor, image.range.first)
            if (before.isNotEmpty()) {
                parts.add(InlineRenderPart.Text(before))
            }
            parts.add(
                InlineRenderPart.Image(
                    alt = image.groupValues[1],
                    url = stripMarkdownDestinationAngleBrackets(image.groupValues[2]),
                )
            )
            cursor = image.range.last + 1
            appendedImage = true
        }

        val after = if (appendedImage) rawText.substring(cursor) else rawText
        if (after.isNotEmpty()) {
            parts.add(InlineRenderPart.Text(after))
        }
    }

    blocks.forEach { block ->
        when (block) {
            is StreamBlock.PlainText -> {
                if (block.text.isNotEmpty()) {
                    appendTextAndImages(block.text)
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
    textAlign: TextAlign = TextAlign.Start,
    onImageClick: ((String) -> Unit)? = null,
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
    val inlineContent = remember(model.mathPlaceholders, model.imagePlaceholders) {
        val mathContent = model.mathPlaceholders.associate { placeholder ->
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
        val imageContent = model.imagePlaceholders.associate { placeholder ->
            placeholder.id to InlineTextContent(
                placeholder = Placeholder(
                    width = placeholder.width,
                    height = placeholder.height,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                ),
            ) {
                val imageModifier = if (onImageClick != null) {
                    Modifier.clickable { onImageClick(placeholder.url) }
                        .fillMaxSize()
                } else {
                    Modifier.fillMaxSize()
                }
                ProportionalAsyncImage(
                    model = placeholder.url,
                    contentDescription = placeholder.alt.ifBlank { null },
                    modifier = imageModifier,
                    maxWidth = 48.dp,
                    preserveAspectRatio = false,
                )
            }
        }
        mathContent + imageContent
    }
    androidx.compose.material3.Text(
        text = model.annotatedText,
        style = compactBodyTextStyle(style, color, textAlign = textAlign),
        textAlign = textAlign,
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
    val imagePlaceholders = mutableListOf<InlineImagePlaceholder>()
    var mathIndex = 0
    var imageIndex = 0

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

            is InlineRenderPart.Image -> {
                val id = "image-${imageIndex++}"
                val placeholderSize = inlineImagePlaceholderSize(codeFontSize)
                builder.appendInlineContent(id = id, alternateText = "�")
                imagePlaceholders.add(
                    InlineImagePlaceholder(
                        id = id,
                        alt = part.alt,
                        url = part.url,
                        width = placeholderSize.width,
                        height = placeholderSize.height,
                    )
                )
            }
        }
    }

    return InlinePartsTextModel(
        annotatedText = builder.toAnnotatedString(),
        mathPlaceholders = mathPlaceholders,
        imagePlaceholders = imagePlaceholders,
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

private fun inlineImagePlaceholderSize(fontSize: TextUnit): InlineMathPlaceholderSize {
    val baseSize = if (fontSize == TextUnit.Unspecified) 16.sp else fontSize
    return InlineMathPlaceholderSize(
        width = baseSize * 2.25f,
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
    textAlign: TextAlign = TextAlign.Start,
) {
    androidx.compose.material3.Text(
        text = text,
        style = compactBodyTextStyle(style, color, lineHeightSp, textAlign),
        textAlign = textAlign,
    )
}

@Composable
private fun ComposeInlineMarkdownSegment(
    text: String,
    style: TextStyle,
    color: Color,
    lineHeightSp: Float = ChatMarkdownTextStyle.BODY_LINE_HEIGHT_SP,
    textAlign: TextAlign = TextAlign.Start,
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
        style = compactBodyTextStyle(style, color, lineHeightSp, textAlign),
        textAlign = textAlign,
    )
}


internal fun compactBodyTextStyle(
    style: TextStyle,
    color: Color,
    lineHeightSp: Float = ChatMarkdownTextStyle.BODY_LINE_HEIGHT_SP,
    textAlign: TextAlign = TextAlign.Start,
): TextStyle {
    return style.copy(
        color = color,
        lineHeight = lineHeightSp.sp,
        lineBreak = LineBreak.Simple,
        hyphens = Hyphens.None,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        textAlign = textAlign,
    )
}

private val supportedInlineMarkdownPattern = Regex(
    """(&(?:amp|lt|gt|quot|apos|nbsp|ndash|mdash|hellip|copy|reg|trade|bull|middot|ldquo|rdquo|lsquo|rsquo|minus|times|divide|plusmn|deg|le|ge|ne|rarr|larr|Alpha|Beta|Gamma|Delta|Epsilon|Zeta|Eta|Theta|Iota|Kappa|Lambda|Mu|Nu|Xi|Omicron|Pi|Rho|Sigma|Tau|Upsilon|Phi|Chi|Psi|Omega|alpha|beta|gamma|delta|epsilon|zeta|eta|theta|iota|kappa|lambda|mu|nu|xi|omicron|pi|rho|sigma|tau|upsilon|phi|chi|psi|omega|#\d+|#x[0-9A-Fa-f]+);|<https?://[^>\s]+>|</?(?:font|tt|big|small|span|annotation)\b[^>]*>|https?://[^\s<>()\[\]{}]+(?:\([^\s<>()\[\]{}]*\)[^\s<>()\[\]{}]*)*|\[[^\]]+]\([^)]+?\)|\*\*|__|\*(?=[^\s*])|_(?=[^\s_])|~~|\+\+|==|\^\^|,,|`)"""
)

private val fullMarkdownOnlyPattern = Regex("""(^\s{0,3}#{1,6}\s+|^\s*[-*+]\s+|^\s*\d+[.)]\s+|^\s*>\s?|^\s{0,3}(-{3,}|\*{3,}|_{3,})\s*$)""", RegexOption.MULTILINE)

private val fencedCodeStartPattern = Regex("""^\s{0,3}(```|~~~)""", RegexOption.MULTILINE)
private val setextHeadingPattern = Regex("""^\S[^\n]*(?:\n|\r\n?)\s{0,3}(={3,}|-{3,})\s*$""", RegexOption.MULTILINE)
private val referenceLinkPattern = Regex("""!?\[[^\]]+]\[[^\]]*]|\[[^\]]+]:\s+\S+""", RegexOption.MULTILINE)
private val referenceDefinitionPattern = Regex("""^\s{0,3}\[[^\]]+]:\s+\S+""", RegexOption.MULTILINE)
private val referenceDefinitionLinePattern = Regex("""^\s{0,3}\[([^\]]+)]:\s+(\S+)(?:\s+(.*))?$""")
private val referenceDefinitionTitleLinePattern = Regex("""^\s{0,3}(?:"[^"]*"|'[^']*'|\([^)]*\))\s*$""")
private val referenceUsePattern = Regex("""(!?)\[([^\]]+)]\[([^\]]*)]""")
private val shortcutReferenceUsePattern = Regex("""(!?)\[([^\]]+)](?![\[(])""")
private val autolinkPattern = Regex("""<https?://[^>\s]+>""")
private val emailAutolinkPattern = Regex("""<[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}>""")
private val htmlTagPattern = Regex("""</?[A-Za-z][A-Za-z0-9:-]*(?:\s+[^>]*)?/?>""")
private val doubleQuotedAnchorHtmlTagPattern = Regex("""<a\b[^>]*\bhref\s*=\s*"([^"]+)"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
private val singleQuotedAnchorHtmlTagPattern = Regex("""<a\b[^>]*\bhref\s*=\s*'([^']+)'[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
private val genericAnchorHtmlTagPattern = Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
private val preCodeHtmlTagPattern = Regex("""<pre\b[^>]*>\s*<code\b([^>]*)>([\s\S]*?)</code>\s*</pre>""", RegexOption.IGNORE_CASE)
private val boldHtmlTagPattern = Regex("""<(strong|b)(?:\s+[^>]*)?>([\s\S]*?)</\1>""", RegexOption.IGNORE_CASE)
private val italicHtmlTagPattern = Regex("""<(em|i|cite|dfn)(?:\s+[^>]*)?>([\s\S]*?)</\1>""", RegexOption.IGNORE_CASE)
private val codeHtmlTagPattern = Regex("""<code(?:\s+[^>]*)?>([\s\S]*?)</code>""", RegexOption.IGNORE_CASE)
private val strikeHtmlTagPattern = Regex("""<(s|del|strike)(?:\s+[^>]*)?>([\s\S]*?)</\1>""", RegexOption.IGNORE_CASE)
private val underlineHtmlTagPattern = Regex("""<u(?:\s+[^>]*)?>([\s\S]*?)</u>""", RegexOption.IGNORE_CASE)
private val markHtmlTagPattern = Regex("""<mark(?:\s+[^>]*)?>([\s\S]*?)</mark>""", RegexOption.IGNORE_CASE)
private val supHtmlTagPattern = Regex("""<sup(?:\s+[^>]*)?>([\s\S]*?)</sup>""", RegexOption.IGNORE_CASE)
private val subHtmlTagPattern = Regex("""<sub(?:\s+[^>]*)?>([\s\S]*?)</sub>""", RegexOption.IGNORE_CASE)
private val kbdHtmlTagPattern = Regex("""<kbd(?:\s+[^>]*)?>([\s\S]*?)</kbd>""", RegexOption.IGNORE_CASE)
private val spanHtmlTagPattern = Regex("""<span\b([^>]*)>([\s\S]*?)</span>""", RegexOption.IGNORE_CASE)
private val htmlTextSpanTagPattern = Regex("""</?(?:font|tt|big|small|span|annotation)\b[^>]*>""", RegexOption.IGNORE_CASE)
private val paragraphHtmlTagPattern = Regex("""<p\b([^>]*)>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
private val divHtmlTagPattern = Regex("""<div\b([^>]*)>([\s\S]*?)</div>""", RegexOption.IGNORE_CASE)
private val nativeTextAlignMarkerPattern = Regex("""^<!--ET_ALIGN:(start|center|end)-->\s*""")
private val unorderedHtmlListPattern = Regex("""<ul(?:\s+[^>]*)?>([\s\S]*?)</ul>""", RegexOption.IGNORE_CASE)
private val unorderedHtmlListOpenTagPattern = Regex("""<ul(?:\s+[^>]*)?>""", RegexOption.IGNORE_CASE)
private val unorderedHtmlListTagPattern = Regex("""</?ul(?:\s+[^>]*)?>""", RegexOption.IGNORE_CASE)
private val orderedHtmlListPattern = Regex("""<ol\b([^>]*)>([\s\S]*?)</ol>""", RegexOption.IGNORE_CASE)
private val htmlListItemPattern = Regex("""<li\b([^>]*)>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE)
private val htmlListItemOpenTagPattern = Regex("""<li\b([^>]*)>""", RegexOption.IGNORE_CASE)
private val htmlListItemTagPattern = Regex("""</?li\b[^>]*>""", RegexOption.IGNORE_CASE)
private val headingHtmlTagPattern = Regex("""<h([1-6])\b([^>]*)>([\s\S]*?)</h\1>""", RegexOption.IGNORE_CASE)
private val blockQuoteHtmlTagPattern = Regex("""<blockquote\b([^>]*)>([\s\S]*?)</blockquote>""", RegexOption.IGNORE_CASE)
private val imageHtmlTagPattern = Regex("""<img\b([^>]*?)\s*/?>""", RegexOption.IGNORE_CASE)
private val horizontalRuleHtmlTagPattern = Regex("""<hr\b[^>]*/?>""", RegexOption.IGNORE_CASE)
private val htmlAttributePattern = Regex("""([A-Za-z_:][A-Za-z0-9_:.-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""")
private val htmlLineBreakTagPattern = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
private val htmlEntityPattern = Regex("""&(?:[A-Za-z][A-Za-z0-9]+|#\d+|#x[0-9A-Fa-f]+);""")
private val supportedHtmlEntityPattern = Regex("""&(?:amp|lt|gt|quot|apos|nbsp|ndash|mdash|hellip|copy|reg|trade|bull|middot|ldquo|rdquo|lsquo|rsquo|minus|times|divide|plusmn|deg|le|ge|ne|rarr|larr|Alpha|Beta|Gamma|Delta|Epsilon|Zeta|Eta|Theta|Iota|Kappa|Lambda|Mu|Nu|Xi|Omicron|Pi|Rho|Sigma|Tau|Upsilon|Phi|Chi|Psi|Omega|alpha|beta|gamma|delta|epsilon|zeta|eta|theta|iota|kappa|lambda|mu|nu|xi|omicron|pi|rho|sigma|tau|upsilon|phi|chi|psi|omega|#\d+|#x[0-9A-Fa-f]+);""")
private val supportedCssNamedColorPattern = Regex("""(?i)^(?:black|white|red|green|blue|yellow|cyan|aqua|magenta|fuchsia|darkgr[ae]y|gr[ae]y|lightgr[ae]y|lime|maroon|navy|olive|purple|silver|teal)$""")
private val simpleDollarInlineMathPattern = Regex("""(?<!\\)\$([^$\r\n]{1,32})(?<!\\)\$""")
private val simpleEscapedInlineMathPattern = Regex("""\\\(([^)\r\n]{1,32})\\\)""")
private val simpleInlineMathSymbolReplacements = mapOf(
    "\\hbar" to "ℏ",
    "\\nabla^2" to "∇²",
    "\\nabla" to "∇",
    "\\partial" to "∂",
)
private val imageMarkdownOpeningPattern = Regex("""!\[""")
private val imageMarkdownPattern = Regex("""!\[([^\]]*)]\((<[^>\s]+>|(?:[^\s()]|\([^)]*\))+)(?:\s+(?:"[^"]*"|'[^']*'|\([^)]*\)))?\)""")
private val standaloneImageMarkdownLinePattern = Regex("""^\s*!\[([^\]]*)]\((<[^>\s]+>|(?:[^\s()]|\([^)]*\))+)(?:\s+(?:"[^"]*"|'[^']*'|\([^)]*\)))?\)\s*$""")

private val tableSeparatorPattern = Regex(
    """^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)+\|?\s*$"""
)

private val atxHeadingLinePattern = Regex("""^\s{0,3}(#{1,6})\s+(.+?)\s*$""")
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
    val nativeText = prepareNativeMarkdownText(text) ?: return StreamingMarkdownRenderPath.ComposeBlockMarkdown
    if (hasUnsupportedNativeMarkdownForCompose(nativeText)) return StreamingMarkdownRenderPath.ComposeBlockMarkdown
    if (text != nativeText) return StreamingMarkdownRenderPath.ComposeBlockMarkdown
    if (hasNativeBlockMarkdownSyntax(nativeText)) return StreamingMarkdownRenderPath.ComposeBlockMarkdown
    if (hasInlineMathSyntax(nativeText)) return StreamingMarkdownRenderPath.ComposeBlockMarkdown
    if (hasFullMarkdownSyntax(nativeText)) return StreamingMarkdownRenderPath.ComposeBlockMarkdown
    return if (
        supportedInlineMarkdownPattern.containsMatchIn(nativeText) ||
        emailAutolinkPattern.containsMatchIn(nativeText)
    ) {
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
            isIndentedCodeBlockStart(lines, index) ||
            htmlLineBreakTagPattern.containsMatchIn(lines[index]) ||
            hasImageMarkdownOutsideInlineCode(lines[index]) ||
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
        if (hasSupportedHtmlSpanStyle(match.groupValues[1])) {
            match.value
        } else {
            match.groupValues[2]
        }
    }
    normalized = paragraphHtmlTagPattern.replace(normalized) { match ->
        "\n\n${normalizeHtmlBlockContent(match.groupValues[1], match.groupValues[2], preserveCssSpanStyle = true)}\n\n"
    }
    normalized = divHtmlTagPattern.replace(normalized) { match ->
        "\n\n${normalizeHtmlBlockContent(match.groupValues[1], match.groupValues[2], preserveCssSpanStyle = false)}\n\n"
    }
    normalized = normalizeUnorderedHtmlLists(normalized)
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
        val textAlignPrefix = htmlBlockTextAlignFromAttributes(match.groupValues[2])
            ?.let(::nativeTextAlignMarkerFor)
            .orEmpty()
        "\n\n${"#".repeat(level)} $textAlignPrefix${match.groupValues[3].trim()}\n\n"
    }
    normalized = blockQuoteHtmlTagPattern.replace(normalized) { match ->
        normalizeHtmlBlockQuote(match.groupValues[1], match.groupValues[2])
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

private fun normalizeHtmlBlockContent(
    rawAttributes: String,
    body: String,
    preserveCssSpanStyle: Boolean,
): String {
    val trimmedBody = body.trim()
    val content = if (preserveCssSpanStyle && hasSupportedHtmlSpanStyle(rawAttributes)) {
        "<span$rawAttributes>$trimmedBody</span>"
    } else {
        trimmedBody
    }
    val textAlign = htmlBlockTextAlignFromAttributes(rawAttributes) ?: return content
    return "${nativeTextAlignMarkerFor(textAlign)}$content"
}

private fun nativeTextAlignMarkerFor(textAlign: TextAlign): String {
    val value = when (textAlign) {
        TextAlign.Center -> "center"
        TextAlign.End -> "end"
        else -> "start"
    }
    return "<!--ET_ALIGN:$value-->"
}

private fun htmlBlockTextAlignFromAttributes(rawAttributes: String): TextAlign? {
    val style = parseHtmlAttributes(rawAttributes)["style"] ?: return null
    return parseHtmlCssDeclarations(style).firstNotNullOfOrNull { (name, value) ->
        if (name != "text-align") {
            null
        } else {
            when (value.trim().lowercase(Locale.ROOT)) {
                "start" -> TextAlign.Start
                "center" -> TextAlign.Center
                "end" -> TextAlign.End
                else -> null
            }
        }
    }
}

private fun extractNativeTextAlignMarker(text: String): Pair<TextAlign, String> {
    val match = nativeTextAlignMarkerPattern.find(text)
    if (match == null) return TextAlign.Start to text
    val textAlign = when (match.groupValues[1]) {
        "center" -> TextAlign.Center
        "end" -> TextAlign.End
        else -> TextAlign.Start
    }
    return textAlign to text.removeRange(match.range).trimStart()
}

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

private fun hasSupportedHtmlSpanStyle(rawAttributes: String): Boolean {
    val style = parseHtmlAttributes(rawAttributes)["style"] ?: return false
    return parseHtmlCssDeclarations(style).any { (name, value) ->
        when (name) {
            "color", "background", "background-color" -> isSupportedHtmlCssColor(value)
            "text-decoration" -> isAndroidHtmlLineThroughTextDecoration(value)
            else -> false
        }
    }
}

private fun parseHtmlCssDeclarations(rawStyle: String): List<Pair<String, String>> {
    return rawStyle.split(';').mapNotNull { declaration ->
        val separator = declaration.indexOf(':')
        if (separator <= 0) {
            null
        } else {
            val name = declaration.substring(0, separator).trim().lowercase(Locale.ROOT)
            val value = declaration.substring(separator + 1).trim()
            if (name.isEmpty() || value.isEmpty()) null else name to value
        }
    }
}

private fun isSupportedHtmlCssColor(rawValue: String): Boolean {
    val color = rawValue.trim().trimEnd(';').substringBefore(' ')
    val hex = color.removePrefix("#")
    return when {
        color.startsWith("#") && (hex.length == 3 || hex.length == 6 || hex.length == 8) ->
            hex.all { it.digitToIntOrNull(16) != null }
        supportedCssNamedColorPattern.matches(color) -> true
        parseAndroidHtmlColorInt(color)?.takeIf { it != -1 } != null -> true
        else -> false
    }
}

private fun isAndroidHtmlLineThroughTextDecoration(rawValue: String): Boolean {
    return rawValue.trim().substringBefore(' ').equals("line-through", ignoreCase = true)
}

private fun parseAndroidHtmlColorInt(rawColor: String): Int? {
    if (rawColor.isEmpty()) return null
    var sign = 1
    var index = 0
    var base = 10

    if (rawColor[index] == '-') {
        sign = -1
        index++
        if (index >= rawColor.length) return null
    }

    if (rawColor[index] == '0') {
        if (index == rawColor.lastIndex) return 0
        val next = rawColor[index + 1]
        if (next == 'x' || next == 'X') {
            index += 2
            base = 16
        } else {
            index++
            base = 8
        }
    } else if (rawColor[index] == '#') {
        index++
        base = 16
    }

    if (index >= rawColor.length) return null
    return rawColor.substring(index).toIntOrNull(base)?.let { it * sign }
}

private fun isSafeNativeImageUrl(url: String): Boolean {
    return url.startsWith("http://") || url.startsWith("https://")
}

private fun normalizeUnorderedHtmlLists(text: String): String {
    val builder = StringBuilder()
    var cursor = 0
    while (cursor < text.length) {
        val open = unorderedHtmlListOpenTagPattern.find(text, cursor) ?: break
        val close = findMatchingHtmlTag(text, open.range.last + 1, unorderedHtmlListTagPattern, "ul") ?: break
        builder.append(text.substring(cursor, open.range.first))
        val body = text.substring(open.range.last + 1, close.range.first)
        val listMarkdown = normalizeUnorderedHtmlListBody(body, level = 0)
        if (listMarkdown.isBlank()) {
            builder.append(body)
        } else {
            builder.append("\n\n").append(listMarkdown).append("\n\n")
        }
        cursor = close.range.last + 1
    }
    builder.append(text.substring(cursor))
    return builder.toString()
}

private fun normalizeUnorderedHtmlListBody(body: String, level: Int): String {
    val lines = mutableListOf<String>()
    var cursor = 0
    while (cursor < body.length) {
        val open = htmlListItemOpenTagPattern.find(body, cursor) ?: break
        val close = findMatchingHtmlTag(body, open.range.last + 1, htmlListItemTagPattern, "li") ?: break
        val itemBody = body.substring(open.range.last + 1, close.range.first)
        lines += normalizeUnorderedHtmlListItem(open.groupValues[1], itemBody, level)
        cursor = close.range.last + 1
    }
    return lines.filter { it.isNotBlank() }.joinToString("\n")
}

private fun normalizeUnorderedHtmlListItem(
    rawAttributes: String,
    itemBody: String,
    level: Int,
): List<String> {
    val textParts = mutableListOf<String>()
    val childLines = mutableListOf<String>()
    var cursor = 0
    while (cursor < itemBody.length) {
        val open = unorderedHtmlListOpenTagPattern.find(itemBody, cursor) ?: break
        val close = findMatchingHtmlTag(itemBody, open.range.last + 1, unorderedHtmlListTagPattern, "ul") ?: break
        textParts += itemBody.substring(cursor, open.range.first)
        val childBody = itemBody.substring(open.range.last + 1, close.range.first)
        normalizeUnorderedHtmlListBody(childBody, level + 1)
            .takeIf { it.isNotBlank() }
            ?.let(childLines::add)
        cursor = close.range.last + 1
    }
    textParts += itemBody.substring(cursor)

    val ownText = textParts.joinToString(" ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    val lines = mutableListOf<String>()
    if (ownText.isNotEmpty()) {
        lines += "${"  ".repeat(level)}- ${normalizeHtmlListItemText(rawAttributes, ownText)}"
    }
    lines += childLines
    return lines
}

private fun htmlListItemTextPrefix(rawAttributes: String): String {
    return htmlBlockTextAlignFromAttributes(rawAttributes)
        ?.let(::nativeTextAlignMarkerFor)
        .orEmpty()
}

private fun normalizeHtmlListItemText(rawAttributes: String, itemText: String): String {
    val textAlignPrefix = htmlListItemTextPrefix(rawAttributes)
    return if (hasSupportedHtmlSpanStyle(rawAttributes)) {
        "$textAlignPrefix<span$rawAttributes>$itemText</span>"
    } else {
        "$textAlignPrefix$itemText"
    }
}

private fun findMatchingHtmlTag(
    text: String,
    start: Int,
    tagPattern: Regex,
    tagName: String,
): MatchResult? {
    var depth = 1
    var cursor = start
    while (cursor < text.length) {
        val match = tagPattern.find(text, cursor) ?: return null
        val value = match.value.trimStart()
        val isClose = value.startsWith("</", ignoreCase = true)
        if (isClose) {
            depth--
            if (depth == 0) return match
        } else if (value.startsWith("<$tagName", ignoreCase = true)) {
            depth++
        }
        cursor = match.range.last + 1
    }
    return null
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
            "$number. ${normalizeHtmlListItemText(match.groupValues[1], itemText)}"
        } else {
            "- ${normalizeHtmlListItemText(match.groupValues[1], itemText)}"
        }
    }.toList()
    return if (items.isEmpty()) {
        body
    } else {
        "\n\n${items.joinToString("\n")}\n\n"
    }
}

private fun normalizeHtmlBlockQuote(rawAttributes: String, body: String): String {
    val textAlignPrefix = htmlBlockTextAlignFromAttributes(rawAttributes)
        ?.let(::nativeTextAlignMarkerFor)
        .orEmpty()
    val normalizedBody = "$textAlignPrefix${body.trim()}"
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
    if (url.isEmpty() || url.any { it.isWhitespace() || it.isISOControl() }) return false
    val schemeSeparator = url.indexOf(':')
    if (schemeSeparator < 0) return true
    if (schemeSeparator == 0) return false
    val scheme = url.substring(0, schemeSeparator)
    return scheme.matches(Regex("""[A-Za-z][A-Za-z0-9+.-]*"""))
}

private fun normalizeReferenceLinksForNativeMarkdown(text: String): String? {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    val lines = normalized.split('\n')
    val definitions = linkedMapOf<String, String>()
    val contentLines = mutableListOf<String>()

    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val definition = referenceDefinitionLinePattern.matchEntire(line)
        if (definition != null) {
            val key = normalizeReferenceKey(definition.groupValues[1])
            definitions[key] = stripMarkdownDestinationAngleBrackets(definition.groupValues[2])
            index++
            if (
                definition.groupValues[3].isBlank() &&
                index < lines.size &&
                referenceDefinitionTitleLinePattern.matches(lines[index])
            ) {
                index++
            }
        } else {
            contentLines.add(line)
            index++
        }
    }

    val content = contentLines.joinToString("\n").trimEnd()
    if (!referenceUsePattern.containsMatchIn(content) && !shortcutReferenceUsePattern.containsMatchIn(content)) {
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

    return shortcutReferenceUsePattern.replace(converted) { match ->
        val isImageReference = match.groupValues[1] == "!"
        val label = match.groupValues[2]
        val url = definitions[normalizeReferenceKey(label)]
        if (url == null) {
            match.value
        } else {
            if (isImageReference) "![$label]($url)" else "[$label]($url)"
        }
    }
}

private fun normalizeReferenceKey(value: String): String {
    return value.trim()
        .replace(Regex("""\s+"""), " ")
        .lowercase(Locale.ROOT)
}

private fun normalizeSimpleInlineMathSymbols(text: String): String {
    var normalized = replaceSimpleMathSymbolsOutsideInlineCode(text, simpleEscapedInlineMathPattern)
    normalized = replaceSimpleMathSymbolsOutsideInlineCode(normalized, simpleDollarInlineMathPattern)
    return normalized
}

private fun replaceSimpleMathSymbolsOutsideInlineCode(text: String, pattern: Regex): String {
    var builder: StringBuilder? = null
    var lastIndex = 0
    pattern.findAll(text).forEach { match ->
        if (isInsideInlineCodeSpan(text, match.range.first)) return@forEach
        val replacement = simpleInlineMathReplacement(match.groupValues[1]) ?: return@forEach
        val currentBuilder = builder ?: StringBuilder().also { builder = it }
        currentBuilder.append(text, lastIndex, match.range.first)
        currentBuilder.append(replacement)
        lastIndex = match.range.last + 1
    }
    val currentBuilder = builder ?: return text
    currentBuilder.append(text, lastIndex, text.length)
    return currentBuilder.toString()
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
    if (hasEscapedInlineMathDelimiterOutsideInlineCode(text)) return true
    var index = 0
    while (index < text.length) {
        if (
            text[index] == '$' &&
            (index == 0 || text[index - 1] != '\\') &&
            !isInsideInlineCodeSpan(text, index)
        ) {
            if (index + 1 < text.length && text[index + 1] == '$') {
                return true
            }
            val close = findClosingSingleDollarOutsideInlineCode(text, index + 1) ?: return false
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

private fun hasEscapedInlineMathDelimiterOutsideInlineCode(text: String): Boolean {
    var index = 0
    while (index + 1 < text.length) {
        if (
            text[index] == '\\' &&
            (text[index + 1] == '(' || text[index + 1] == '[') &&
            !isInsideInlineCodeSpan(text, index)
        ) {
            return true
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


private fun findClosingSingleDollarOutsideInlineCode(text: String, start: Int): Int? {
    var index = start
    while (index < text.length) {
        val char = text[index]
        if (char == '\n' || char == '\r') return null
        if (
            char == '$' &&
            (index == 0 || text[index - 1] != '\\') &&
            !isInsideInlineCodeSpan(text, index)
        ) {
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

private fun isIndentedCodeBlockStart(lines: List<String>, index: Int): Boolean {
    if (index !in lines.indices) return false
    return isIndentedCodeLine(lines[index]) && (index == 0 || lines[index - 1].isBlank())
}

private fun isIndentedCodeLine(line: String): Boolean {
    return line.startsWith("    ") || line.startsWith("\t")
}

private fun stripIndentedCodePrefix(line: String): String {
    return if (line.startsWith("\t")) line.drop(1) else line.drop(4)
}

private fun normalizeAtxHeadingText(rawText: String): String {
    val trimmed = rawText.trim()
    var hashStart = trimmed.length
    while (hashStart > 0 && trimmed[hashStart - 1] == '#') {
        hashStart--
    }
    if (hashStart == trimmed.length || hashStart == 0) return trimmed
    if (!trimmed[hashStart - 1].isWhitespace()) return trimmed
    return trimmed.substring(0, hashStart).trimEnd()
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

private fun nativeStreamingListItemFromLine(
    line: NativeListLine,
    level: Int,
): NativeStreamingListItem {
    val (textAlign, itemText) = extractNativeTextAlignMarker(
        normalizeHtmlLineBreakTags(line.text)
    )
    return NativeStreamingListItem(
        text = itemText,
        level = level,
        ordered = line.ordered,
        number = line.number,
        textAlign = textAlign,
    )
}

internal fun parseUnifiedStreamingMarkdownBlocks(
    text: String,
    segmentId: String = "segment",
    blockQuoteDepth: Int = 0,
): List<NativeStreamingMarkdownBlock> {
    if (text.isEmpty()) return emptyList()
    return parseNativeStreamingMarkdownBlocks(text, segmentId, blockQuoteDepth)
        ?: listOf(
            NativeStreamingMarkdownBlock(
                stableId = "$segmentId:native:paragraph:0",
                type = NativeStreamingMarkdownBlockType.Paragraph,
                start = 0,
                endExclusive = text.length,
                text = text,
            )
        )
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
        val (textAlign, alignedContent) = extractNativeTextAlignMarker(
            normalizeNativeParagraphLineBreaks(contentLines).trimEnd()
        )
        val content = alignedContent.trimEnd()
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
                textAlign = textAlign,
            ).also(blocks::add)
        }

        val mathBlocks = findNativeParagraphMathBlocks(content)
        if (mathBlocks == null) return

        if (mathBlocks.isNotEmpty()) {
            var cursor = 0
            mathBlocks.forEach { mathBlock ->
                appendParagraphFragment(
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
            appendParagraphFragment(
                fragment = content.substring(cursor),
                fragmentStart = cursor,
            )
            return
        }

        NativeStreamingMarkdownBlock(
            stableId = stableId(NativeStreamingMarkdownBlockType.Paragraph, lineStarts[startIndex]),
            type = NativeStreamingMarkdownBlockType.Paragraph,
            start = lineStarts[startIndex],
            endExclusive = lineEnd(endIndex),
            text = content,
            textAlign = textAlign,
        ).also(blocks::add)
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

        if (isIndentedCodeLine(line)) {
            val startIndex = index
            val codeLines = mutableListOf<String>()
            var endIndex = startIndex
            while (index < lines.size) {
                val current = lines[index]
                if (isIndentedCodeLine(current)) {
                    codeLines.add(stripIndentedCodePrefix(current))
                    endIndex = index
                    index++
                    continue
                }
                if (current.isBlank()) {
                    val blankStart = index
                    while (index < lines.size && lines[index].isBlank()) {
                        index++
                    }
                    if (index < lines.size && isIndentedCodeLine(lines[index])) {
                        repeat(index - blankStart) {
                            codeLines.add("")
                        }
                        endIndex = index - 1
                        continue
                    }
                    break
                }
                break
            }
            val rawBlock = lines.subList(startIndex, endIndex + 1).joinToString("\n")
            blocks.add(
                NativeStreamingMarkdownBlock(
                    stableId = stableId(NativeStreamingMarkdownBlockType.CodeBlock, lineStarts[startIndex]),
                    type = NativeStreamingMarkdownBlockType.CodeBlock,
                    start = lineStarts[startIndex],
                    endExclusive = lineEnd(endIndex),
                    text = rawBlock,
                    language = null,
                    code = codeLines.joinToString("\n"),
                )
            )
            continue
        }

        val atxHeading = atxHeadingLinePattern.matchEntire(line)
        if (atxHeading != null) {
            val (textAlign, headingText) = extractNativeTextAlignMarker(
                normalizeAtxHeadingText(atxHeading.groupValues[2])
            )
            blocks.add(
                NativeStreamingMarkdownBlock(
                    stableId = stableId(NativeStreamingMarkdownBlockType.Heading, lineStarts[index]),
                    type = NativeStreamingMarkdownBlockType.Heading,
                    start = lineStarts[index],
                    endExclusive = lineEnd(index),
                    text = headingText,
                    level = atxHeading.groupValues[1].length,
                    textAlign = textAlign,
                )
            )
            index++
            continue
        }

        if (index + 1 < lines.size) {
            val underline = setextUnderlineLinePattern.matchEntire(lines[index + 1])
            if (underline != null) {
                val (textAlign, headingText) = extractNativeTextAlignMarker(line.trim())
                blocks.add(
                    NativeStreamingMarkdownBlock(
                        stableId = stableId(NativeStreamingMarkdownBlockType.Heading, lineStarts[index]),
                        type = NativeStreamingMarkdownBlockType.Heading,
                        start = lineStarts[index],
                        endExclusive = lineEnd(index + 1),
                        text = headingText,
                        level = if (underline.groupValues[1].startsWith("=")) 1 else 2,
                        textAlign = textAlign,
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
                    val quoteTextWithMarker = normalizeHtmlLineBreakTags(quoteLines.joinToString("\n")).trimEnd()
                    val (textAlign, quoteText) = extractNativeTextAlignMarker(quoteTextWithMarker)
                    val quoteChildren = if (blockQuoteDepth < MAX_NATIVE_BLOCK_QUOTE_DEPTH && quoteText.isNotBlank()) {
                        parseUnifiedStreamingMarkdownBlocks(
                            text = quoteTextWithMarker,
                            segmentId = "$segmentId:quote:${lineStarts[startIndex]}",
                            blockQuoteDepth = blockQuoteDepth + 1,
                        )
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
                        textAlign = textAlign,
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
                nativeStreamingListItemFromLine(listLine, level = 0)
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
                        nativeStreamingListItemFromLine(next, level = level)
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
                            children = last.children + parseUnifiedStreamingMarkdownBlocks(
                                text = normalizedContinuation,
                                segmentId = "$segmentId:list-item:${lineStarts[index]}",
                                blockQuoteDepth = blockQuoteDepth,
                            ),
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
        if (
            line[index] == '$' &&
            (index == 0 || line[index - 1] != '\\') &&
            !isInsideInlineCodeSpan(line, index)
        ) {
            val close = findClosingSingleDollarOutsideInlineCode(line, index + 1) ?: return false
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
        if (close >= 0 && !isInsideInlineCodeSpan(line, index)) return true
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
        !isInsideInlineCodeSpan(text, match.range.first) &&
            !htmlLineBreakTagPattern.matches(match.value) &&
            !htmlTextSpanTagPattern.matches(match.value)
    }
}

private fun normalizeHtmlLineBreakTags(text: String): String {
    return htmlLineBreakTagPattern.replace(text, "\n")
}

private fun normalizeNativeParagraphLineBreaks(lines: List<String>): String {
    val builder = StringBuilder()
    var previousHardBreak = false

    lines.forEachIndexed { index, line ->
        if (index > 0) {
            builder.append(if (previousHardBreak) '\n' else ' ')
        }
        val hardBreak = hasMarkdownHardLineBreak(line)
        builder.append(normalizeHtmlLineBreakTags(stripMarkdownHardLineBreak(line, hardBreak)))
        previousHardBreak = hardBreak
    }

    return builder.toString()
}

private fun hasMarkdownHardLineBreak(line: String): Boolean {
    return line.endsWith("  ") || line.endsWith("\\")
}

private fun stripMarkdownHardLineBreak(line: String, hardBreak: Boolean): String {
    if (!hardBreak) return line
    return if (line.endsWith("\\")) {
        line.dropLast(1)
    } else {
        line.trimEnd(' ')
    }
}

private fun hasUnsupportedHtmlEntity(text: String): Boolean {
    return htmlEntityPattern.findAll(text).any { match ->
        !isInsideInlineCodeSpan(text, match.range.first) &&
            !supportedHtmlEntityPattern.matches(match.value)
    }
}

private fun hasUnsupportedImageMarkdown(text: String): Boolean {
    if (!text.contains("![")) return false

    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    if (normalized.lines().any { line ->
            hasImageOpeningOutsideInlineCode(line) &&
                TableUtils.isTableDataRow(line) &&
                TableUtils.parseTableRow(line).any { cell ->
                    hasImageOpeningOutsideInlineCode(cell) && parseTableCellMarkdownParts(cell) == null
                }
        }
    ) {
        return true
    }

    val openings = imageMarkdownOpeningPattern.findAll(text)
        .filterNot { opening -> isInsideInlineCodeSpan(text, opening.range.first) }
        .toList()
    if (openings.isEmpty()) return false

    val supportedRanges = imageMarkdownMatchesOutsideInlineCode(text).map { it.range }.toList()
    if (supportedRanges.isEmpty()) return true

    return openings.any { opening ->
        supportedRanges.none { range -> opening.range.first in range }
    }
}

private fun hasImageMarkdownOutsideInlineCode(text: String): Boolean {
    return imageMarkdownMatchesOutsideInlineCode(text).isNotEmpty()
}

private fun hasImageOpeningOutsideInlineCode(text: String): Boolean {
    return imageMarkdownOpeningPattern.findAll(text).any { opening ->
        !isInsideInlineCodeSpan(text, opening.range.first)
    }
}

private fun imageMarkdownMatchesOutsideInlineCode(text: String): List<MatchResult> {
    return imageMarkdownPattern.findAll(text)
        .filterNot { image -> isInsideInlineCodeSpan(text, image.range.first) }
        .toList()
}
