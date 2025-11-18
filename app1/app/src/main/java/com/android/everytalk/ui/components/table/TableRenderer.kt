package com.android.everytalk.ui.components.table

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.everytalk.ui.components.markdown.MarkdownRenderer

/**
 * 表格渲染器
 * 
 * 支持：
 * - 自动列宽计算
 * - 水平滚动
 * - Markdown单元格内容
 * - 流式渲染
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
    cellStyle: TextStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp)
) {
    if (lines.size < 2) return

    // 解析表头
    val headers = TableUtils.parseTableRow(lines[0])

    // 跳过分隔行，解析数据行
    val dataRows = lines.drop(2).map { TableUtils.parseTableRow(it) }

    // 计算列宽
    val columnWidths = TableUtils.calculateColumnWidths(headers, dataRows)

    // 根据表格规模决定渲染策略：单元格总量大时禁用单元格内Markdown/Math以避免递归渲染
    val totalCells = headers.size * dataRows.size
    // 🎯 优化：流式期间也允许渲染 Markdown，保持与流式结束后的样式一致，防止跳动。
    // 仅在单元格非常多时降级为纯文本。
    val usePlainTextCells = totalCells > 60 || !renderMarkdownInCells

    Column(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()) // 由外层统一提供水平滚动，保证表头与数据行滚动同步
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        // 渲染表头（使用轻量Text，避免复杂渲染）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(vertical = 8.dp)
        ) {
            headers.forEachIndexed { index, header ->
                val cellModifier = Modifier
                    .width(columnWidths[index])
                    .padding(horizontal = 12.dp)

                if (renderMarkdownInCells) {
                    // 头部单元格也走轻量 Markdown 渲染，保证 **加粗**、*斜体*、行内代码等能被正确解析
                    MarkdownRenderer(
                        markdown = header.trim(),
                        style = headerStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        isStreaming = false,
                        modifier = cellModifier
                    )
                } else {
                    Text(
                        text = header.trim(),
                        modifier = cellModifier,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // 渲染数据行：避免在嵌套滚动环境中使用 LazyColumn，防止“无限高度约束”崩溃
        // 依赖外部父级（消息列表）的垂直滚动，这里用普通 Column + forEach 渲染行
        dataRows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 0.5.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                    .padding(vertical = 8.dp)
            ) {
                row.forEachIndexed { index, cell ->
                    if (index < columnWidths.size) {
                        val cellModifier = Modifier
                            .width(columnWidths[index])
                            .padding(horizontal = 12.dp)

                        // 在单元格内启用轻量 Markdown 渲染（不引入额外滚动容器）
                        if (renderMarkdownInCells) {
                            MarkdownRenderer(
                                markdown = cell.trim(),
                                style = cellStyle,
                                color = MaterialTheme.colorScheme.onSurface,
                                isStreaming = false,
                                modifier = cellModifier
                            )
                        } else {
                            Text(
                                text = cell.trim(),
                                modifier = cellModifier,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}