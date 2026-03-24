package com.android.everytalk.ui.components.table

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        fontSize = 14.sp
    ),
    cellStyle: TextStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
    contentKey: String = "",
    onLongPress: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null
) {
    if (lines.size < 2) return

    // 使用 remember 缓存解析结果，避免重复解析
    val (headers, dataRows, columnWidths) = remember(lines) {
        val parsedHeaders = TableUtils.parseTableRow(lines[0])
        val parsedDataRows = lines.drop(2).map { TableUtils.parseTableRow(it) }
        val parsedColumnWidths = TableUtils.calculateColumnWidths(parsedHeaders, parsedDataRows)
        Triple(parsedHeaders, parsedDataRows, parsedColumnWidths)
    }

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

    val cornerRadius = 12.dp
    val tableShape = RoundedCornerShape(cornerRadius)
    val scrollState = rememberScrollState()
    val outlineColor = MaterialTheme.colorScheme.outline
    val headerBackgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val rowBackgroundColor = MaterialTheme.colorScheme.background
    val rowDividerColor = outlineColor.copy(alpha = 0.3f)
    val columnDividerColor = outlineColor.copy(alpha = 0.2f)
    Box(
        modifier = modifier
            .wrapContentWidth()
            .clip(tableShape)
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
            .border(1.dp, outlineColor, tableShape)
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
                    .padding(vertical = 8.dp)
            ) {
                headers.forEachIndexed { index, header ->
                    key("header_$index") {
                        TableCell(
                            content = header.trim(),
                            width = columnWidths.getOrElse(index) { 100.dp },
                            style = headerStyle,
                            usePlainText = false,
                            contentKey = if (contentKey.isNotBlank()) "${contentKey}_th_$index" else "",
                            backgroundColor = headerBackgroundColor,
                            drawRightSeparator = index < headers.lastIndex,
                            separatorColor = columnDividerColor
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
                            .padding(vertical = 8.dp)
                    ) {
                        row.forEachIndexed { colIndex, cell ->
                            if (colIndex < columnWidths.size) {
                                key("cell_${rowIndex}_$colIndex") {
                                    TableCell(
                                        content = cell.trim(),
                                        width = columnWidths[colIndex],
                                        style = cellStyle,
                                        usePlainText = usePlainTextCells,
                                        contentKey = if (contentKey.isNotBlank()) "${contentKey}_tr_${rowIndex}_td_$colIndex" else "",
                                        backgroundColor = rowBackgroundColor,
                                        drawRightSeparator = colIndex < row.lastIndex,
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
    style: TextStyle,
    usePlainText: Boolean,
    contentKey: String,
    backgroundColor: Color,
    drawRightSeparator: Boolean = false,
    separatorColor: Color = Color.Transparent
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    // 内联代码不使用背景色
    val codeBackground = Color.Transparent

    // 检测是否包含数学公式
    val containsMath = remember(content) {
        InlineMarkdownParser.containsMath(content)
    }

    Box(
        modifier = Modifier
            .requiredWidthIn(min = width)
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
        contentAlignment = Alignment.CenterStart
    ) {
        if (containsMath && !usePlainText) {
            // 包含数学公式：使用 MarkdownRenderer 渲染（支持 LaTeX）
            MarkdownRenderer(
                markdown = content,
                style = style,
                color = textColor,
                contentKey = contentKey,
                disableVerticalPadding = true
            )
        } else {
            // 普通文本或纯文本模式：使用 AnnotatedString 渲染
            val annotatedText = remember(content, usePlainText) {
                if (usePlainText || !InlineMarkdownParser.containsInlineMarkdown(content)) {
                    androidx.compose.ui.text.AnnotatedString(content)
                } else {
                    InlineMarkdownParser.parse(
                        text = content,
                        baseColor = Color.Unspecified,
                        codeBackground = codeBackground
                    )
                }
            }

            Text(
                text = annotatedText,
                style = style,
                color = textColor,
                overflow = TextOverflow.Clip
            )
        }
    }
}