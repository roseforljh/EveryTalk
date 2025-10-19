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
import com.example.everytalk.ui.components.math.MathAwareText

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
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        // 渲染表头
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
                    MathAwareText(
                        text = header.trim(),
                        style = headerStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = cellModifier,
                        isStreaming = false
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
        
        // 渲染数据行
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
                        if (renderMarkdownInCells) {
                            // 在表格单元格内启用 Markdown 渲染
                            MathAwareText(
                                text = cell.trim(),
                                style = cellStyle,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = cellModifier,
                                isStreaming = false
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