package com.android.everytalk.ui.components.table

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
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

    // 根据表格规模决定渲染策略：单元格总量大时禁用单元格内Markdown以避免递归渲染
    // 参考 RikkaHub：使用纯 Compose Text 而非 AndroidView，大幅减少重组开销
    val totalCells = headers.size * dataRows.size
    val usePlainTextCells = totalCells > 40 || !renderMarkdownInCells

    val cornerRadius = 12.dp
    val tableShape = RoundedCornerShape(cornerRadius)

    // 使用 ScrollState 来支持水平滚动
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
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
            .horizontalScroll(scrollState)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, tableShape)
    ) {
        // 渲染表头
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(vertical = 8.dp)
        ) {
            headers.forEachIndexed { index, header ->
                // 使用稳定的 key 避免重组
                key("header_$index") {
                    TableCell(
                        content = header.trim(),
                        width = columnWidths.getOrElse(index) { 100.dp },
                        style = headerStyle,
                        usePlainText = true, // 表头始终使用纯文本，避免复杂渲染
                        contentKey = if (contentKey.isNotBlank()) "${contentKey}_th_$index" else ""
                    )
                }
            }
        }

        // 渲染数据行
        dataRows.forEachIndexed { rowIndex, row ->
            // 使用稳定的 key 避免重组
            key("row_$rowIndex") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 0.5.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                        .padding(vertical = 8.dp)
                ) {
                    row.forEachIndexed { colIndex, cell ->
                        if (colIndex < columnWidths.size) {
                            // 使用稳定的 key 避免重组
                            key("cell_${rowIndex}_$colIndex") {
                                TableCell(
                                    content = cell.trim(),
                                    width = columnWidths[colIndex],
                                    style = cellStyle,
                                    usePlainText = usePlainTextCells,
                                    contentKey = if (contentKey.isNotBlank()) "${contentKey}_tr_${rowIndex}_td_$colIndex" else ""
                                )
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
 */
@Composable
private fun TableCell(
    content: String,
    width: androidx.compose.ui.unit.Dp,
    style: TextStyle,
    usePlainText: Boolean,
    contentKey: String
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant

    // 使用 remember 缓存解析结果
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

    Box(
        modifier = Modifier
            .width(width)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = annotatedText,
            style = style,
            color = textColor,
            maxLines = 10,
            overflow = TextOverflow.Ellipsis
        )
    }
}