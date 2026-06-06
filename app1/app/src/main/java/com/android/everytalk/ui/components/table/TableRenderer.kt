package com.android.everytalk.ui.components.table

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import com.android.everytalk.ui.components.ChatMarkdownTextStyle
import com.android.everytalk.ui.components.markdown.MarkdownRenderer

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
    contentKey: String = "",
    onLongPress: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null
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
        val parsedColumnWidths = TableUtils.calculateColumnWidths(parsedHeaders, parsedDataRows)
        object {
            val headers = parsedHeaders
            val alignments = paddedAlignments
            val dataRows = parsedDataRows
            val columnWidths = parsedColumnWidths
        }
    }

    val headers = parsedTableData.headers
    val dataRows = parsedTableData.dataRows
    val columnWidths = parsedTableData.columnWidths
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

    val scrollState = rememberScrollState()
    val outlineColor = MaterialTheme.colorScheme.outline
    val headerBackgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val rowBackgroundColor = MaterialTheme.colorScheme.background
    val rowDividerColor = outlineColor.copy(alpha = 0.3f)
    val columnDividerColor = outlineColor.copy(alpha = 0.2f)
    Box(
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
        Column(
            modifier = Modifier
                .wrapContentWidth()
                .horizontalScroll(scrollState)
        ) {
            // 渲染表头
            Row(
                modifier = Modifier
                    .wrapContentWidth()
                    .background(headerBackgroundColor)
                    .padding(vertical = ChatMarkdownTextStyle.TABLE_ROW_VERTICAL_PADDING_DP.dp),
                verticalAlignment = Alignment.Top
            ) {
                headers.forEachIndexed { index, header ->
                    key("header_$index") {
                        TableCell(
                            content = header.trim(),
                            width = columnWidths.getOrElse(index) { 100.dp },
                            alignment = alignments[index],
                            style = headerStyle,
                            usePlainText = false,
                            contentKey = if (contentKey.isNotBlank()) "${contentKey}_th_$index" else "",
                            backgroundColor = headerBackgroundColor,
                            drawRightSeparator = index < headers.lastIndex,
                            separatorColor = columnDividerColor,
                            isHeader = true
                        )
                    }
                }
            }

            // 渲染数据行
            dataRows.forEachIndexed { rowIndex, row ->
                key("row_$rowIndex") {
                    Row(
                        modifier = Modifier
                            .wrapContentWidth()
                            .background(rowBackgroundColor)
                            .drawBehind {
                                val strokeWidth = 0.5.dp.toPx()
                                drawLine(
                                    color = rowDividerColor,
                                    start = androidx.compose.ui.geometry.Offset(0f, size.height - strokeWidth / 2f),
                                    end = androidx.compose.ui.geometry.Offset(size.width, size.height - strokeWidth / 2f),
                                    strokeWidth = strokeWidth
                                )
                            }
                            .padding(vertical = ChatMarkdownTextStyle.TABLE_ROW_VERTICAL_PADDING_DP.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        row.forEachIndexed { colIndex, cell ->
                            if (colIndex < columnWidths.size) {
                                key("cell_${rowIndex}_$colIndex") {
                                    TableCell(
                                        content = cell.trim(),
                                        width = columnWidths[colIndex],
                                        alignment = alignments[colIndex],
                                        style = cellStyle,
                                        usePlainText = usePlainTextCells,
                                        contentKey = if (contentKey.isNotBlank()) "${contentKey}_tr_${rowIndex}_td_$colIndex" else "",
                                        backgroundColor = rowBackgroundColor,
                                        drawRightSeparator = colIndex < headers.lastIndex,
                                        separatorColor = columnDividerColor
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

internal fun compactTableCellTextStyle(style: TextStyle): TextStyle {
    return style.copy(
        lineHeight = ChatMarkdownTextStyle.TABLE_CELL_LINE_HEIGHT_SP.sp,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )
}

/**
 * 表格单元格组件
 *
 * 参考 Open WebUI 的 MarkdownInlineTokens：
 * - 使用轻量级 InlineMarkdownParser 渲染内联 Markdown
 * - 纯 Compose AnnotatedString，无 AndroidView 开销
 * - 支持加粗、斜体、代码、删除线等格式
 * - 当检测到数学公式时，使用 MarkdownRenderer 渲染（支持 LaTeX）
 */
@Composable
private fun TableCell(
    content: String,
    width: androidx.compose.ui.unit.Dp,
    alignment: TableUtils.TableAlignment,
    style: TextStyle,
    usePlainText: Boolean,
    contentKey: String,
    backgroundColor: Color,
    drawRightSeparator: Boolean = false,
    separatorColor: Color = Color.Transparent,
    isHeader: Boolean = false
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    // 内联代码不使用背景色
    val codeBackground = Color.Transparent
    val isDark = isSystemInDarkTheme()
    val codeColor = if (isDark) Color(0xFFD1D5DB) else Color(0xFF4F5661)
    val codeFontSize = if (style.fontSize == TextUnit.Unspecified) {
        TextUnit.Unspecified
    } else {
        style.fontSize * ChatMarkdownTextStyle.INLINE_CODE_RELATIVE_SIZE
    }

    // 检测是否包含数学公式
    val containsMath = remember(content) {
        InlineMarkdownParser.containsMath(content)
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

    Box(
        modifier = Modifier
            .width(width)
            .drawBehind {
                drawRect(backgroundColor)
                if (drawRightSeparator) {
                    val strokeWidth = 0.5.dp.toPx()
                    drawLine(
                        color = separatorColor,
                        start = androidx.compose.ui.geometry.Offset(size.width - strokeWidth / 2f, 0f),
                        end = androidx.compose.ui.geometry.Offset(size.width - strokeWidth / 2f, size.height),
                        strokeWidth = strokeWidth
                    )
                }
            }
            .padding(horizontal = 12.dp),
        contentAlignment = boxAlignment
    ) {
        if (containsMath && !usePlainText) {
            // 包含数学公式：使用 MarkdownRenderer 渲染（支持 LaTeX）
            MarkdownRenderer(
                markdown = content,
                style = compactStyle,
                color = textColor,
                contentKey = contentKey,
                disableVerticalPadding = true
            )
        } else {
            // 普通文本或纯文本模式：使用 AnnotatedString 渲染
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
                modifier = Modifier.fillMaxWidth(), // 关键：填充以响应 TextAlign
                softWrap = !isHeader, // 强制表头不换行，而普通单元格允许换行
                overflow = TextOverflow.Clip
            )
        }
    }
}
