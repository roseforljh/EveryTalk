package com.example.everytalk.ui.components.table

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
    val usePlainTextCells = totalCells > 40 || isStreaming || !renderMarkdownInCells

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

                Text(
                    text = header.trim(),
                    modifier = cellModifier,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
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

                        // 为稳定与性能，大表格/流式阶段统一使用纯文本单元格，避免递归Markdown/Math
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