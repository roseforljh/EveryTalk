package com.example.everytalk.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Markdown 渲染器（支持表格）
 */
@Composable
fun MarkdownRenderer(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified
) {
    val isDark = isSystemInDarkTheme()
    val textColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> MaterialTheme.colorScheme.onSurface
    }

    // 先做轻量格式修复（保留 $ 数学语法）
    val fixedMarkdown = remember(markdown) {
        MarkdownFormatFixer.fix(markdown, keepMathSyntax = true)
    }
    
    // 检查是否包含数学公式
    if (hasMathFormulas(fixedMarkdown)) {
        // 使用数学公式渲染器
        ContentWithMathFormulas(
            text = fixedMarkdown,
            modifier = modifier,
            style = style,
            color = textColor
        )
    } else {
        // 使用标准 Markdown 渲染
        val codeBackgroundColor = if (isDark) {
            Color(0xFF2D2D2D)
        } else {
            Color(0xFFF5F5F5)
        }
        
        val codeTextColor = if (isDark) {
            Color(0xFFE06C75)
        } else {
            Color(0xFFD73A49)
        }
        
        dev.jeziellago.compose.markdowntext.MarkdownText(
            markdown = fixedMarkdown,
            style = style,
            modifier = modifier,
            syntaxHighlightColor = codeBackgroundColor,
            syntaxHighlightTextColor = codeTextColor
        )
    }
}

/**
 * 表格渲染器
 */
@Composable
fun TableRenderer(
    lines: List<String>,
    modifier: Modifier = Modifier
) {
    if (lines.size < 2) return
    
    // 解析表头
    val headers = parseTableRow(lines[0])
    
    // 跳过分隔行，解析数据行
    val dataRows = lines.drop(2).map { parseTableRow(it) }
    
    // 计算列宽
    val columnWidths = calculateColumnWidths(headers, dataRows)
    
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
                Text(
                    text = header.trim(),
                    modifier = Modifier
                        .width(columnWidths[index])
                        .padding(horizontal = 12.dp),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
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
                        Text(
                            text = cell.trim(),
                            modifier = Modifier
                                .width(columnWidths[index])
                                .padding(horizontal = 12.dp),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

/**
 * 解析表格行，提取单元格内容
 */
private fun parseTableRow(line: String): List<String> {
    // 移除首尾的 | 符号，然后按 | 分割
    return line.trim()
        .removePrefix("|")
        .removeSuffix("|")
        .split("|")
        .map { it.trim() }
}

/**
 * 计算每列的宽度
 */
private fun calculateColumnWidths(
    headers: List<String>,
    dataRows: List<List<String>>
): List<androidx.compose.ui.unit.Dp> {
    val columnCount = headers.size
    val widths = MutableList(columnCount) { 100.dp }
    
    // 基于内容长度计算宽度
    headers.forEachIndexed { index, header ->
        var maxLength = header.length
        dataRows.forEach { row ->
            if (index < row.size) {
                maxLength = maxOf(maxLength, row[index].length)
            }
        }
        // 每个字符约8dp，最小100dp，最大300dp
        widths[index] = (maxLength * 8).dp.coerceIn(100.dp, 300.dp)
    }
    
    return widths
}

/**
 * 检查是否为表格行
 */
fun isTableLine(line: String): Boolean {
    val trimmed = line.trim()
    // 表格行必须包含至少两个 | 符号
    val pipeCount = trimmed.count { it == '|' }
    if (pipeCount < 2) return false
    
    // 检查是否为分隔行（包含 - 和 | 的组合）
    val isSeparator = trimmed.matches(Regex("^\\s*\\|?\\s*[-:]+\\s*(\\|\\s*[-:]+\\s*)+\\|?\\s*$"))
    
    // 检查是否为数据行（包含 | 分隔的内容）
    val isDataRow = trimmed.contains("|") && !trimmed.all { it == '|' || it == '-' || it == ':' || it.isWhitespace() }
    
    return isSeparator || isDataRow
}

/**
 * 检查是否为表格分隔行
 */
fun isTableSeparator(line: String): Boolean {
    val trimmed = line.trim()
    return trimmed.matches(Regex("^\\s*\\|?\\s*[-:]+\\s*(\\|\\s*[-:]+\\s*)+\\|?\\s*$"))
}

/**
 * 提取连续的表格行
 */
fun extractTableLines(lines: List<String>, startIndex: Int): Pair<List<String>, Int> {
    val tableLines = mutableListOf<String>()
    var currentIndex = startIndex
    
    // 收集连续的表格行
    while (currentIndex < lines.size && isTableLine(lines[currentIndex])) {
        tableLines.add(lines[currentIndex])
        currentIndex++
    }
    
    // 验证表格格式：至少需要表头、分隔行和一行数据
    if (tableLines.size >= 2 && tableLines.getOrNull(1)?.let { isTableSeparator(it) } == true) {
        return Pair(tableLines, currentIndex)
    }
    
    // 如果不是有效的表格，返回空列表
    return Pair(emptyList(), startIndex)
}

/**
 * 渲染Markdown内容
 */
@Composable
fun RenderMarkdownContent(
    content: String,
    modifier: Modifier = Modifier
) {
    val lines = content.lines()
    var currentIndex = 0
    
    Column(modifier = modifier) {
        while (currentIndex < lines.size) {
            val line = lines[currentIndex]
            
            // 检查是否为表格开始
            if (isTableLine(line)) {
                val (tableLines, nextIndex) = extractTableLines(lines, currentIndex)
                
                if (tableLines.isNotEmpty()) {
                    // 渲染表格
                    Spacer(modifier = Modifier.height(8.dp))
                    TableRenderer(
                        lines = tableLines,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    currentIndex = nextIndex
                    continue
                }
            }
            
            // 渲染普通文本行
            if (line.isNotBlank()) {
                Text(
                    text = line,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            } else {
                Spacer(modifier = Modifier.height(4.dp))
            }
            
            currentIndex++
        }
    }
}
