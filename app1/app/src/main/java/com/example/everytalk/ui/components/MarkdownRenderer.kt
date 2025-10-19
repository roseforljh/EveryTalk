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
import com.example.everytalk.ui.components.math.MathAwareText

// 最小长度短路阈值：过短文本不做“格式修复”，直接渲染
private const val MARKDOWN_FIX_MIN_LEN = 20

/**
 * Markdown 渲染器（支持表格）
 */
@Composable
fun MarkdownRenderer(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    isStreaming: Boolean = false
) {
    val isDark = isSystemInDarkTheme()
    val textColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> MaterialTheme.colorScheme.onSurface
    }

    // 流式渲染策略（外部库优先）：
    // - 流式阶段：优先调用外部库 MarkdownText 渲染，跳过重型“格式修复”，仅保留长度兜底；
    // - 非流式阶段：执行一次格式修复后再用外部库渲染（保持高质量）。
    // 兜底：极端长文本在流式阶段回退为纯文本，避免阻塞。
    val isTooLongForStreaming = isStreaming && markdown.length > 1500
    if (isTooLongForStreaming) {
        // 避免极端长文本在流式阶段阻塞
        Text(
            text = markdown,
            style = style.copy(color = textColor),
            modifier = modifier
        )
        return
    }

    // 🎯 先做格式修复（仅非流式）；并对“很短文本”直接短路，减少CPU与日志
    val fixedMarkdown = if (isStreaming || markdown.length < MARKDOWN_FIX_MIN_LEN) {
        markdown
    } else {
        remember(markdown) {
            androidx.compose.runtime.derivedStateOf {
                try {
                    val fixed = MarkdownFormatFixer.fix(markdown)
                    // 限流日志：仅在 Debug 且文本较长时打印一次
                    if (com.example.everytalk.BuildConfig.DEBUG && markdown.length >= 80) {
                        android.util.Log.d(
                            "MarkdownRenderer",
                            "Fixed length: ${markdown.length} -> ${fixed.length}"
                        )
                    }
                    fixed
                } catch (e: Throwable) {
                    if (com.example.everytalk.BuildConfig.DEBUG) {
                        android.util.Log.e("MarkdownRenderer", "Fix failed, fallback to raw", e)
                    }
                    markdown
                }
            }
        }.value
    }

    
    // 内联代码样式（仅用于外部库渲染的行内 `code`；围栏代码块使用自定义 CodeBlock，不受此处影响）
    // 要求：背景纯透明，字体颜色随明暗模式自适配
    val inlineCodeBackground = Color.Transparent
    val inlineCodeTextColor = if (isDark) {
        Color(0xFF9CDCFE) // 夜间：浅蓝（提升可读性）
    } else {
        Color(0xFF005CC5) // 白天：深蓝（对比度良好）
    }

    // 直接交由外部库渲染内联代码（背景透明+按明暗主题的文字颜色）
    dev.jeziellago.compose.markdowntext.MarkdownText(
        markdown = fixedMarkdown,
        style = style.copy(color = textColor),
        modifier = modifier,
        syntaxHighlightColor = inlineCodeBackground,
        syntaxHighlightTextColor = inlineCodeTextColor
    )
}

/**
 * 表格渲染器
 */
@Composable
fun TableRenderer(
    lines: List<String>,
    modifier: Modifier = Modifier,
    renderMarkdownInCells: Boolean = true,
    isStreaming: Boolean = false,
    headerStyle: TextStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
    cellStyle: TextStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp)
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
                            // 在表格单元格内启用 Markdown 渲染（即使处于流式，也优先转换内联标记）
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
