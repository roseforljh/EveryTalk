package com.example.everytalk.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
// 移除第三方MarkdownText库的导入，改用EnhancedMarkdownText和Text组件
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

data class TableData(
    val headers: List<String>,
    val rows: List<List<String>>
)

@Composable
fun ComposeTable(
    tableData: TableData,
    modifier: Modifier = Modifier,
    delayMs: Long = 0L
) {
    val borderColor = MaterialTheme.colorScheme.outline
    val headerBackgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val evenRowColor = MaterialTheme.colorScheme.surface
    val oddRowColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    
    // 延迟渲染状态
    var shouldRender by remember { mutableStateOf(delayMs == 0L) }
    
    // 延迟渲染逻辑
    LaunchedEffect(tableData, delayMs) {
        if (delayMs > 0L) {
            shouldRender = false
            delay(delayMs)
            shouldRender = true
        } else {
            // 当delayMs为0时，立即显示
            shouldRender = true
        }
    }
    
    if (!shouldRender) {
        // 延迟渲染期间显示占位符
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(100.dp)
                .background(MaterialTheme.colorScheme.surface)
        )
        return
    }
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, borderColor)
    ) {
        // 表头
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerBackgroundColor)
                .padding(8.dp)
        ) {
            tableData.headers.forEachIndexed { index, header ->
                // 表格头部使用简单文本渲染，不包含代码块
                Text(
                    text = header,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                )
                
                // 添加分隔线（除了最后一列）
                if (index < tableData.headers.size - 1) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(borderColor)
                    )
                }
            }
        }
        
        // 表格数据行
        tableData.rows.forEachIndexed { rowIndex, row ->
            val backgroundColor = if (rowIndex % 2 == 0) evenRowColor else oddRowColor
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(backgroundColor)
                    .padding(8.dp)
            ) {
                row.forEachIndexed { cellIndex, cell ->
                    // 表格单元格内容，统一使用EnhancedMarkdownText进行渲染以支持内部Markdown格式
                    EnhancedMarkdownText(
                        markdown = cell,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                    )
                    
                    // 添加分隔线（除了最后一列）
                    if (cellIndex < row.size - 1) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(borderColor)
                        )
                    }
                }
            }
            
            // 添加行分隔线（除了最后一行）
            if (rowIndex < tableData.rows.size - 1) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(borderColor)
                )
            }
        }
    }
}

/**
 * 解析Markdown表格文本为TableData
 */
fun parseMarkdownTable(markdownTable: String): TableData? {
    val lines = markdownTable.trim().split("\n")
    if (lines.size < 3) return null // 至少需要表头、分隔符、一行数据
    
    // 解析表头
    val headers = lines[0].split("|").map { it.trim() }.filter { it.isNotEmpty() }
    if (headers.isEmpty()) return null
    
    // 跳过分隔符行（第二行）
    val dataRows = mutableListOf<List<String>>()
    
    for (i in 2 until lines.size) {
        val cells = lines[i].split("|").map { it.trim() }.filter { it.isNotEmpty() }
        if (cells.isNotEmpty()) {
            // 确保每行的列数与表头一致
            val paddedCells = cells.take(headers.size) + 
                List(maxOf(0, headers.size - cells.size)) { "" }
            dataRows.add(paddedCells)
        }
    }
    
    return TableData(headers, dataRows)
}