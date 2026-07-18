package com.android.everytalk.ui.components.table

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import com.android.everytalk.ui.components.ChatMarkdownTextStyle
import com.android.everytalk.ui.components.ProportionalAsyncImage
import com.android.everytalk.ui.components.math.MathBlock
import com.android.everytalk.ui.components.math.MathInline
import com.android.everytalk.ui.components.streaming.InlineRenderPart
import com.android.everytalk.ui.components.streaming.MathBlockState
import com.android.everytalk.ui.components.streaming.StreamBlock
import com.android.everytalk.ui.components.streaming.StreamBlockParser
import com.android.everytalk.ui.components.streaming.buildInlinePartsTextModel
import com.android.everytalk.ui.components.streaming.resolveStreamMathRenderPath
import com.android.everytalk.ui.components.streaming.StreamMathRenderPath

/**
 * 表格渲染器（优化版，参考 RikkaHub DataTable）
 *
 * 核心优化策略：
 * - 使用稳定的 key() 避免不必要的重组
 * - 使用纯 Compose Text 渲染单元格，避免 AndroidView 开销
 * - 使用 remember 缓存解析结果
 * - 支持水平滚动
 */
@Composable
fun TableRenderer(
    lines: List<String>,
    modifier: Modifier = Modifier,
    renderMarkdownInCells: Boolean = true,
    isStreaming: Boolean = false,
    headerStyle: TextStyle = MaterialTheme.typography.bodyMedium.copy(
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = ChatMarkdownTextStyle.TABLE_CELL_LINE_HEIGHT_SP.sp
    ),
    cellStyle: TextStyle = MaterialTheme.typography.bodySmall.copy(
        fontSize = 13.sp,
        lineHeight = ChatMarkdownTextStyle.TABLE_CELL_LINE_HEIGHT_SP.sp
    ),
    onLongPress: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    onImageClick: ((String) -> Unit)? = null,
) {
    if (lines.size < 2) return

    // 使用 remember 缓存解析结果，避免重复解析
    val parsedTableData = remember(lines) {
        val parsedHeaders = TableUtils.parseTableRow(lines[0])
        val parsedAlignments = if (lines.size > 1) TableUtils.parseAlignments(lines[1]) else emptyList()
        val paddedAlignments = List(parsedHeaders.size) { index ->
            parsedAlignments.getOrElse(index) { TableUtils.TableAlignment.CENTER }
        }
        val parsedDataRows = lines.drop(2).map {
            TableUtils.padRowCells(TableUtils.parseTableRow(it), parsedHeaders.size)
        }
        object {
            val headers = parsedHeaders
            val alignments = paddedAlignments
            val dataRows = parsedDataRows
        }
    }

    val headers = parsedTableData.headers
    val dataRows = parsedTableData.dataRows
    val alignments = parsedTableData.alignments

    // 根据表格规模决定渲染策略：大表格默认降级为纯文本，但若检测到 Markdown 语法仍保留解析
    // 避免出现 **bold**、`code` 等在单元格中原样显示的问题
    val totalCells = headers.size * dataRows.size
    val hasMarkdownSyntaxInCells = remember(lines) {
        lines.drop(2).any { rowLine ->
            TableUtils.parseTableRow(rowLine).any { cell ->
                InlineMarkdownParser.containsInlineMarkdown(cell) || InlineMarkdownParser.containsMath(cell)
            }
        }
    }
    val usePlainTextCells = !renderMarkdownInCells || (totalCells > 40 && !hasMarkdownSyntaxInCells)
    val outlineColor = MaterialTheme.colorScheme.outline
    val headerBackgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val rowBackgroundColor = MaterialTheme.colorScheme.background
    val rowDividerColor = outlineColor.copy(alpha = 0.3f)
    val headerDividerColor = rowDividerColor
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(onLongPress) {
                if (onLongPress != null) {
                    detectTapGestures(
                        onLongPress = { offset ->
                            onLongPress(offset)
                        },
                        onTap = { /* no-op */ }
                    )
                }
            }
            .background(headerBackgroundColor)
    ) {
        val density = LocalDensity.current
        val columnWidth = with(density) {
            TableUtils.calculateChatGptEqualColumnWidthPx(
                maxTableWidthPx = maxWidth.toPx(),
                columnCount = headers.size,
                borderStrokeWidthPx = ChatMarkdownTextStyle.TABLE_BORDER_STROKE_WIDTH_DP.dp.toPx(),
            ).toDp()
        }

        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // 渲染表头
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBackgroundColor)
                    .drawBehind {
                        val strokeWidth = ChatMarkdownTextStyle.TABLE_BORDER_STROKE_WIDTH_DP.dp.toPx()
                        drawLine(
                            color = headerDividerColor,
                            start = androidx.compose.ui.geometry.Offset(0f, size.height - strokeWidth / 2f),
                            end = androidx.compose.ui.geometry.Offset(size.width, size.height - strokeWidth / 2f),
                            strokeWidth = strokeWidth
                        )
                    }
                    .padding(vertical = ChatMarkdownTextStyle.TABLE_ROW_VERTICAL_PADDING_DP.dp),
                verticalAlignment = Alignment.Top
            ) {
                headers.forEachIndexed { index, header ->
                    key("header_$index") {
                        TableCell(
                            content = header.trim(),
                            width = columnWidth,
                            alignment = alignments[index],
                            style = headerStyle,
                            usePlainText = false,
                            backgroundColor = headerBackgroundColor,
                            isHeader = true,
                            onImageClick = onImageClick,
                        )
                    }
                }
            }

            // 渲染数据行
            dataRows.forEachIndexed { rowIndex, row ->
                key("row_$rowIndex") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(rowBackgroundColor)
                            .drawBehind {
                                if (rowIndex < dataRows.lastIndex) {
                                    val strokeWidth = ChatMarkdownTextStyle.TABLE_BORDER_STROKE_WIDTH_DP.dp.toPx()
                                    drawLine(
                                        color = rowDividerColor,
                                        start = androidx.compose.ui.geometry.Offset(0f, size.height - strokeWidth / 2f),
                                        end = androidx.compose.ui.geometry.Offset(size.width, size.height - strokeWidth / 2f),
                                        strokeWidth = strokeWidth
                                    )
                                }
                            }
                            .padding(vertical = ChatMarkdownTextStyle.TABLE_ROW_VERTICAL_PADDING_DP.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        row.forEachIndexed { colIndex, cell ->
                            key("cell_${rowIndex}_$colIndex") {
                                TableCell(
                                    content = cell.trim(),
                                    width = columnWidth,
                                    alignment = alignments[colIndex],
                                    style = cellStyle,
                                    usePlainText = usePlainTextCells,
                                    backgroundColor = rowBackgroundColor,
                                    onImageClick = onImageClick,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun compactTableCellTextStyle(style: TextStyle): TextStyle {
    return style.copy(
        lineHeight = ChatMarkdownTextStyle.TABLE_CELL_LINE_HEIGHT_SP.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )
}

internal fun shouldRenderTableCellNativeInlineMath(
    content: String,
    usePlainText: Boolean,
): Boolean {
    if (usePlainText) return false
    if (!InlineMarkdownParser.containsMath(content)) return false
    val blocks = StreamBlockParser.parse(content, "table-cell").blocks
    return blocks.any { it is StreamBlock.MathInline } &&
        blocks.none { it is StreamBlock.MathBlock || it is StreamBlock.CodeBlock }
}

internal fun shouldRenderTableCellNativeBlockMath(
    content: String,
    usePlainText: Boolean,
): Boolean {
    if (usePlainText) return false
    if (!InlineMarkdownParser.containsMath(content)) return false
    val block = StreamBlockParser.parse(content.trim(), "table-cell").blocks.singleOrNull()
    return block is StreamBlock.MathBlock && block.state == MathBlockState.RENDERED
}

internal fun shouldRenderTableCellNativeMixedMath(
    content: String,
    usePlainText: Boolean,
): Boolean {
    if (usePlainText) return false
    if (!InlineMarkdownParser.containsMath(content)) return false
    val blocks = StreamBlockParser.parse(content, "table-cell").blocks
    if (blocks.none { it is StreamBlock.MathBlock || it is StreamBlock.MathInline }) {
        return false
    }
    if (blocks.size > 1) return true

    return when (val block = blocks.singleOrNull()) {
        is StreamBlock.MathBlock -> resolveStreamMathRenderPath(block) == StreamMathRenderPath.RawText
        is StreamBlock.MathInline -> block.state == MathBlockState.RAW
        else -> false
    }
}

/**
 * 表格单元格组件
 *
 * 参考 Open WebUI 的 MarkdownInlineTokens：
 * - 使用轻量级 InlineMarkdownParser 渲染内联 Markdown
 * - 纯 Compose AnnotatedString，无 AndroidView 开销
 * - 支持加粗、斜体、代码、删除线等格式
 * - 当检测到数学公式时，优先使用原生 inline/block/mixed 路径渲染
 */
@Composable
private fun TableCell(
    content: String,
    width: androidx.compose.ui.unit.Dp,
    alignment: TableUtils.TableAlignment,
    style: TextStyle,
    usePlainText: Boolean,
    backgroundColor: Color,
    isHeader: Boolean = false,
    onImageClick: ((String) -> Unit)? = null,
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val codeBackground = Color.Transparent
    val isDark = isSystemInDarkTheme()
    val codeColor = if (isDark) Color(0xFFD1D5DB) else Color(0xFF4F5661)
    val codeFontSize = if (style.fontSize == TextUnit.Unspecified) {
        TextUnit.Unspecified
    } else {
        style.fontSize * ChatMarkdownTextStyle.INLINE_CODE_RELATIVE_SIZE
    }

    val boxAlignment = when (alignment) {
        TableUtils.TableAlignment.LEFT -> Alignment.TopStart
        TableUtils.TableAlignment.CENTER -> Alignment.TopCenter
        TableUtils.TableAlignment.RIGHT -> Alignment.TopEnd
        TableUtils.TableAlignment.START -> Alignment.TopStart
    }

    val textAlign = when (alignment) {
        TableUtils.TableAlignment.LEFT -> TextAlign.Left
        TableUtils.TableAlignment.CENTER -> TextAlign.Center
        TableUtils.TableAlignment.RIGHT -> TextAlign.Right
        TableUtils.TableAlignment.START -> TextAlign.Start
    }
    val compactStyle = compactTableCellTextStyle(style).copy(textAlign = textAlign)
    val image = remember(content, usePlainText) {
        if (usePlainText) null else parseTableCellImageMarkdown(content)
    }
    val mixedParts = remember(content, usePlainText) {
        if (usePlainText) null else parseTableCellMarkdownParts(content)
    }

    Box(
        modifier = Modifier
            .width(width)
            .drawBehind {
                drawRect(backgroundColor)
            }
            .padding(horizontal = ChatMarkdownTextStyle.TABLE_CELL_PADDING_DP.dp),
        contentAlignment = boxAlignment
    ) {
        if (image != null) {
            val imageClickModifier = if (onImageClick != null) {
                Modifier.clickable { onImageClick(image.url) }
            } else {
                Modifier
            }
            ProportionalAsyncImage(
                model = image.url,
                contentDescription = image.alt.ifBlank { null },
                maxWidth = maxOf(width - ChatMarkdownTextStyle.TABLE_CELL_CONTENT_MAX_WIDTH_INSET_DP.dp, 56.dp),
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .then(imageClickModifier),
            )
        } else if (mixedParts != null) {
            TableCellMarkdownParts(
                parts = mixedParts,
                width = width,
                compactStyle = compactStyle,
                textColor = textColor,
                codeColor = codeColor,
                codeFontSize = codeFontSize,
                textAlign = textAlign,
                alignment = alignment,
                onImageClick = onImageClick,
            )
        } else if (shouldRenderTableCellNativeInlineMath(content, usePlainText)) {
            TableCellNativeInlineMath(
                content = content,
                compactStyle = compactStyle,
                textColor = textColor,
                codeBackground = codeBackground,
                codeColor = codeColor,
                codeFontSize = codeFontSize,
                textAlign = textAlign,
                isHeader = isHeader,
            )
        } else if (shouldRenderTableCellNativeBlockMath(content, usePlainText)) {
            TableCellNativeBlockMath(
                content = content,
            )
        } else if (shouldRenderTableCellNativeMixedMath(content, usePlainText)) {
            TableCellNativeMixedMath(
                content = content,
                width = width,
                compactStyle = compactStyle,
                textColor = textColor,
                codeColor = codeColor,
                codeFontSize = codeFontSize,
                textAlign = textAlign,
                alignment = alignment,
            )
        } else {
            val annotatedText = remember(content, usePlainText, codeColor, codeFontSize) {
                if (usePlainText || !InlineMarkdownParser.containsInlineMarkdown(content)) {
                    androidx.compose.ui.text.AnnotatedString(content)
                } else {
                    InlineMarkdownParser.parse(
                        text = content,
                        baseColor = Color.Unspecified,
                        codeBackground = codeBackground,
                        codeColor = codeColor,
                        codeFontSize = codeFontSize,
                    )
                }
            }

            Text(
                text = annotatedText,
                style = compactStyle,
                color = textColor,
                textAlign = textAlign,
                modifier = Modifier.fillMaxWidth(),
                softWrap = !isHeader,
                overflow = TextOverflow.Clip
            )
        }
    }
}

@Composable
private fun TableCellNativeBlockMath(
    content: String,
) {
    val block = remember(content) {
        StreamBlockParser.parse(content.trim(), "table-cell").blocks.singleOrNull() as? StreamBlock.MathBlock
    } ?: return

    MathBlock(
        latex = stripTableCellMathDelimiters(block.text),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    )
}

@Composable
private fun TableCellNativeMixedMath(
    content: String,
    width: androidx.compose.ui.unit.Dp,
    compactStyle: TextStyle,
    textColor: Color,
    codeColor: Color,
    codeFontSize: TextUnit,
    textAlign: TextAlign,
    alignment: TableUtils.TableAlignment,
) {
    val blocks = remember(content) {
        StreamBlockParser.parse(content, "table-cell").blocks
    }
    val horizontalAlignment = when (alignment) {
        TableUtils.TableAlignment.LEFT,
        TableUtils.TableAlignment.START -> Alignment.Start
        TableUtils.TableAlignment.CENTER -> Alignment.CenterHorizontally
        TableUtils.TableAlignment.RIGHT -> Alignment.End
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = horizontalAlignment,
    ) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) {
                Spacer(modifier = Modifier.height(6.dp))
            }
            when (block) {
                is StreamBlock.PlainText -> {
                    val text = block.text.trim()
                    if (text.isNotEmpty()) {
                        val annotatedText = remember(text, codeColor, codeFontSize) {
                            if (InlineMarkdownParser.containsInlineMarkdown(text)) {
                                InlineMarkdownParser.parse(
                                    text = text,
                                    baseColor = Color.Unspecified,
                                    codeBackground = Color.Transparent,
                                    codeColor = codeColor,
                                    codeFontSize = codeFontSize,
                                )
                            } else {
                                androidx.compose.ui.text.AnnotatedString(text)
                            }
                        }
                        Text(
                            text = annotatedText,
                            style = compactStyle,
                            color = textColor,
                            textAlign = textAlign,
                            modifier = Modifier.fillMaxWidth(),
                            overflow = TextOverflow.Clip,
                        )
                    }
                }
                is StreamBlock.MathInline -> {
                    if (block.state == MathBlockState.RENDERED) {
                        MathInline(
                            latex = stripTableCellMathDelimiters(block.text),
                            modifier = Modifier
                                .width(maxOf(width - ChatMarkdownTextStyle.TABLE_CELL_CONTENT_MAX_WIDTH_INSET_DP.dp, 56.dp))
                                .padding(vertical = 2.dp),
                        )
                    } else {
                        TableCellRawMathText(
                            text = block.text,
                            compactStyle = compactStyle,
                            textColor = textColor,
                            textAlign = textAlign,
                        )
                    }
                }
                is StreamBlock.MathBlock -> {
                    when (resolveStreamMathRenderPath(block)) {
                        StreamMathRenderPath.KaTeX -> {
                            MathBlock(
                                latex = stripTableCellMathDelimiters(block.text),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                            )
                        }
                        StreamMathRenderPath.RawText -> {
                            TableCellRawMathText(
                                text = block.text,
                                compactStyle = compactStyle,
                                textColor = textColor,
                                textAlign = textAlign,
                            )
                        }
                    }
                }
                is StreamBlock.CodeBlock -> {
                    val text = block.text.trim()
                    if (text.isNotEmpty()) {
                        TableCellCodeText(
                            text = text,
                            compactStyle = compactStyle,
                            textColor = codeColor,
                            textAlign = textAlign,
                        )
                    }
                }
            }
        }
    }
}
@Composable
private fun TableCellCodeText(
    text: String,
    compactStyle: TextStyle,
    textColor: Color,
    textAlign: TextAlign,
) {
    Text(
        text = text,
        style = compactStyle.copy(fontFamily = FontFamily.Monospace),
        color = textColor,
        textAlign = textAlign,
        modifier = Modifier.fillMaxWidth(),
        overflow = TextOverflow.Clip,
    )
}

@Composable
private fun TableCellRawMathText(
    text: String,
    compactStyle: TextStyle,
    textColor: Color,
    textAlign: TextAlign,
) {
    Text(
        text = text,
        style = compactStyle,
        color = textColor,
        textAlign = textAlign,
        modifier = Modifier.fillMaxWidth(),
        overflow = TextOverflow.Clip,
    )
}

private fun stripTableCellMathDelimiters(text: String): String {
    return when {
        text.startsWith("\\[") && text.endsWith("\\]") && text.length >= 4 ->
            text.substring(2, text.length - 2)
        text.startsWith("$$") && text.endsWith("$$") && text.length >= 4 ->
            text.substring(2, text.length - 2)
        text.startsWith("\\(") && text.endsWith("\\)") && text.length >= 4 ->
            text.substring(2, text.length - 2)
        text.startsWith("$") && text.endsWith("$") && !text.startsWith("$$") && text.length >= 2 ->
            text.substring(1, text.length - 1)
        else -> text
    }
}

@Composable
private fun TableCellNativeInlineMath(
    content: String,
    compactStyle: TextStyle,
    textColor: Color,
    codeBackground: Color,
    codeColor: Color,
    codeFontSize: TextUnit,
    textAlign: TextAlign,
    isHeader: Boolean,
) {
    val parts = remember(content) {
        tableCellInlineParts(content)
    }
    val model = remember(parts, textColor, codeBackground, codeColor, codeFontSize) {
        buildInlinePartsTextModel(
            parts = parts,
            baseColor = textColor,
            codeBackground = codeBackground,
            codeColor = codeColor,
            codeFontSize = codeFontSize,
        )
    }
    val inlineContent = remember(model.mathPlaceholders) {
        model.mathPlaceholders.associate { placeholder ->
            placeholder.id to InlineTextContent(
                placeholder = androidx.compose.ui.text.Placeholder(
                    width = placeholder.width,
                    height = placeholder.height,
                    placeholderVerticalAlign = androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter,
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
    Text(
        text = model.annotatedText,
        style = compactStyle,
        color = textColor,
        textAlign = textAlign,
        modifier = Modifier.fillMaxWidth(),
        softWrap = !isHeader,
        overflow = TextOverflow.Clip,
        inlineContent = inlineContent,
    )
}

private fun tableCellInlineParts(content: String): List<InlineRenderPart> {
    return StreamBlockParser.parse(content, "table-cell").blocks.mapNotNull { block ->
        when (block) {
            is StreamBlock.PlainText -> InlineRenderPart.Text(block.text)
            is StreamBlock.MathInline -> InlineRenderPart.Math(block)
            else -> null
        }
    }
}

@Composable
private fun TableCellMarkdownParts(
    parts: List<TableCellMarkdownPart>,
    width: androidx.compose.ui.unit.Dp,
    compactStyle: TextStyle,
    textColor: Color,
    codeColor: Color,
    codeFontSize: TextUnit,
    textAlign: TextAlign,
    alignment: TableUtils.TableAlignment,
    onImageClick: ((String) -> Unit)?,
) {
    val horizontalAlignment = when (alignment) {
        TableUtils.TableAlignment.LEFT,
        TableUtils.TableAlignment.START -> Alignment.Start
        TableUtils.TableAlignment.CENTER -> Alignment.CenterHorizontally
        TableUtils.TableAlignment.RIGHT -> Alignment.End
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = horizontalAlignment,
    ) {
        parts.forEachIndexed { index, part ->
            if (index > 0) {
                Spacer(modifier = Modifier.height(6.dp))
            }
            when (part) {
                is TableCellMarkdownPart.Text -> {
                    val annotatedText = remember(part.text, codeColor, codeFontSize) {
                        if (InlineMarkdownParser.containsInlineMarkdown(part.text)) {
                            InlineMarkdownParser.parse(
                                text = part.text,
                                baseColor = Color.Unspecified,
                                codeBackground = Color.Transparent,
                                codeColor = codeColor,
                                codeFontSize = codeFontSize,
                            )
                        } else {
                            androidx.compose.ui.text.AnnotatedString(part.text)
                        }
                    }
                    Text(
                        text = annotatedText,
                        style = compactStyle,
                        color = textColor,
                        textAlign = textAlign,
                        modifier = Modifier.fillMaxWidth(),
                        overflow = TextOverflow.Clip,
                    )
                }
                is TableCellMarkdownPart.Image -> {
                    val imageClickModifier = if (onImageClick != null) {
                        Modifier.clickable { onImageClick(part.url) }
                    } else {
                        Modifier
                    }
                    ProportionalAsyncImage(
                        model = part.url,
                        contentDescription = part.alt.ifBlank { null },
                        maxWidth = maxOf(width - ChatMarkdownTextStyle.TABLE_CELL_CONTENT_MAX_WIDTH_INSET_DP.dp, 56.dp),
                        modifier = imageClickModifier,
                    )
                }
            }
        }
    }
}
