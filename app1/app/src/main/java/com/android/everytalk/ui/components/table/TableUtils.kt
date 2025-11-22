package com.android.everytalk.ui.components.table

import androidx.compose.ui.unit.dp

/**
 * 表格工具类
 * 
 * 提供表格检测、解析和验证功能
 */
object TableUtils {
    
    // 预编译表格分隔行正则，避免在热点路径重复创建 Regex 实例
    // 改进的表格分隔行正则，支持更宽松的格式
    // 改进的表格分隔行正则，支持更宽松的格式
    private val TABLE_SEPARATOR_REGEX = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)+\\|?\\s*$")
    
    /**
     * 规范化表格行字符
     * 将全角符号转换为半角，以便统一处理
     */
    private fun normalizeTableChars(line: String): String {
        return line.replace('｜', '|')
            .replace('：', ':')
            .replace('－', '-')
    }

    /**
     * 检查是否为表格行
     */
    fun isTableLine(line: String): Boolean {
        // 移除 BOM 和首尾空白，并规范化字符
        val normalized = normalizeTableChars(line.replace("\uFEFF", "")).trim()
        
        // 快速检查：必须包含 |
        if (!normalized.contains('|')) return false
        
        // 检查是否为分隔行
        if (normalized.matches(TABLE_SEPARATOR_REGEX)) return true
        
        // 检查是否为数据行或表头
        // 规则：
        // 1. 必须包含 |
        // 2. 不能只包含表格符号（防止误判分隔行）
        // 3. 或者是分隔行（上面已经检查过了）
        
        // 简单的启发式检查：如果包含 | 且不是分隔行，我们暂时认为是潜在的表格行
        // 更严格的检查由 extractTableLines 和 ContentParser 的上下文逻辑处理
        return true
    }
    
    /**
     * 检查是否为表格分隔行
     */
    fun isTableSeparator(line: String): Boolean {
        // 移除 BOM 和首尾空白，并规范化字符
        val normalized = normalizeTableChars(line.replace("\uFEFF", "")).trim()
        return normalized.matches(TABLE_SEPARATOR_REGEX)
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
        // 同样需要规范化字符，确保全角｜也能被正确分割
        val normalized = normalizeTableChars(line.replace("\uFEFF", "")).trim()
        return normalized
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