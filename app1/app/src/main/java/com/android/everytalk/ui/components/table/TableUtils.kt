package com.android.everytalk.ui.components.table

import androidx.compose.ui.unit.dp

/**
 * 表格工具类
 * 
 * 提供表格检测、解析和验证功能
 */
object TableUtils {
    
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
        
        // 验证表格格式：至少需要表头、分隔行
        if (tableLines.size >= 2 && tableLines.getOrNull(1)?.let { isTableSeparator(it) } == true) {
            return Pair(tableLines, currentIndex)
        }
        
        // 如果不是有效的表格，返回空列表
        return Pair(emptyList(), startIndex)
    }
    
    /**
     * 解析表格行，提取单元格内容
     */
    fun parseTableRow(line: String): List<String> {
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
    fun calculateColumnWidths(
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
}