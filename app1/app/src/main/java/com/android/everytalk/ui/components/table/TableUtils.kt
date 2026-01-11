package com.android.everytalk.ui.components.table

import androidx.compose.ui.unit.dp

/**
 * 表格工具类
 *
 * 参考 Cherry Studio 的实现思路，提供严格的表格检测、解析和验证功能。
 *
 * Markdown 表格规范：
 * 1. 表头行：至少2个单元格，由 | 分隔
 * 2. 分隔行：紧跟表头行，格式为 | --- | --- | 或 | :--- | :--- | 等
 * 3. 数据行：与表头行格式一致
 */
object TableUtils {

    // 表格分隔行正则：匹配 | :---: | --- | :--- | 等格式
    // 支持左对齐(:---)、右对齐(---:)、居中对齐(:---:)
    private val TABLE_SEPARATOR_REGEX = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)+\\|?\\s*$")

    // 表格数据行正则：至少包含一个 | 分隔符，且两侧都有内容（非空白）
    // 格式：| cell1 | cell2 | 或 cell1 | cell2
    private val TABLE_DATA_ROW_REGEX = Regex("^\\s*\\|?.+\\|.+\\|?\\s*$")

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
     * 检查是否为有效的表格数据行或表头行
     *
     * 严格检查：
     * 1. 必须包含至少2个单元格（由 | 分隔）
     * 2. 不能是分隔行
     * 3. 单元格内容可以为空，但必须有结构
     */
    fun isTableDataRow(line: String): Boolean {
        val normalized = normalizeTableChars(line.replace("\uFEFF", "")).trim()

        // 快速检查：必须包含 |
        if (!normalized.contains('|')) return false

        // 排除分隔行
        if (isTableSeparator(normalized)) return false

        // 解析单元格
        val cells = parseCells(normalized)

        // 至少需要2个单元格才是有效的表格行
        return cells.size >= 2
    }

    /**
     * 检查是否为表格行（分隔行或数据行）
     */
    fun isTableLine(line: String): Boolean {
        val normalized = normalizeTableChars(line.replace("\uFEFF", "")).trim()

        // 快速检查：必须包含 |
        if (!normalized.contains('|')) return false

        // 检查是否为分隔行
        if (isTableSeparator(normalized)) return true

        // 检查是否为数据行
        return isTableDataRow(normalized)
    }

    /**
     * 检查是否为表格分隔行
     */
    fun isTableSeparator(line: String): Boolean {
        val normalized = normalizeTableChars(line.replace("\uFEFF", "")).trim()
        return normalized.matches(TABLE_SEPARATOR_REGEX)
    }

    /**
     * 解析单元格内容
     */
    private fun parseCells(normalized: String): List<String> {
        return normalized
            .removePrefix("|")
            .removeSuffix("|")
            .split("|")
            .map { it.trim() }
    }

    /**
     * 检查是否为有效的表格起始（表头行 + 分隔行）
     *
     * 用于快速判断从某位置开始是否存在有效表格
     */
    fun isValidTableStart(lines: List<String>, startIndex: Int): Boolean {
        if (startIndex + 1 >= lines.size) return false

        val headerLine = lines[startIndex]
        val separatorLine = lines[startIndex + 1]

        // 表头必须是数据行
        if (!isTableDataRow(headerLine)) return false

        // 第二行必须是分隔行
        if (!isTableSeparator(separatorLine)) return false

        // 验证列数一致性
        val headerCells = parseTableRow(headerLine)
        val separatorCells = parseTableRow(separatorLine)

        return headerCells.size == separatorCells.size && headerCells.size >= 2
    }

    /**
     * 提取连续的表格行
     *
     * 改进逻辑：
     * 1. 先验证表格起始（表头 + 分隔行）
     * 2. 收集后续的数据行（列数与表头一致）
     * 3. 遇到不符合格式的行时停止
     */
    fun extractTableLines(lines: List<String>, startIndex: Int): Pair<List<String>, Int> {
        // 首先验证是否为有效的表格起始
        if (!isValidTableStart(lines, startIndex)) {
            return Pair(emptyList(), startIndex)
        }

        val tableLines = mutableListOf<String>()
        val headerCellCount = parseTableRow(lines[startIndex]).size

        // 添加表头行
        tableLines.add(lines[startIndex])

        // 添加分隔行
        tableLines.add(lines[startIndex + 1])

        var currentIndex = startIndex + 2

        // 收集数据行
        while (currentIndex < lines.size) {
            val line = lines[currentIndex]

            // 检查是否为有效的数据行
            if (!isTableDataRow(line)) break

            // 检查列数是否与表头一致（允许少于或等于表头列数）
            val cellCount = parseTableRow(line).size
            if (cellCount > headerCellCount) break

            tableLines.add(line)
            currentIndex++
        }

        return Pair(tableLines, currentIndex)
    }

    /**
     * 解析表格行，提取单元格内容
     */
    fun parseTableRow(line: String): List<String> {
        val normalized = normalizeTableChars(line.replace("\uFEFF", "")).trim()
        return parseCells(normalized)
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