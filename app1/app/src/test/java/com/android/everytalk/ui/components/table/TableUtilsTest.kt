package com.android.everytalk.ui.components.table

import org.junit.Assert.*
import org.junit.Test

/**
 * TableUtils 单元测试
 *
 * 验证表格检测、解析逻辑的正确性
 */
class TableUtilsTest {

    @Test
    fun `isTableSeparator should match standard separator`() {
        assertTrue(TableUtils.isTableSeparator("| --- | --- |"))
        assertTrue(TableUtils.isTableSeparator("| :--- | :--- | :--- |"))
        assertTrue(TableUtils.isTableSeparator("|:---|:---|:---|"))
        assertTrue(TableUtils.isTableSeparator("| :---: | ---: | :--- |"))
        assertTrue(TableUtils.isTableSeparator("  | --- | --- |  "))
    }

    @Test
    fun `isTableSeparator should reject invalid separators`() {
        assertFalse(TableUtils.isTableSeparator("| cell1 | cell2 |"))
        assertFalse(TableUtils.isTableSeparator("just text"))
        assertFalse(TableUtils.isTableSeparator("| - |")) // too short
        assertFalse(TableUtils.isTableSeparator(""))
    }

    @Test
    fun `isTableDataRow should match valid data rows`() {
        assertTrue(TableUtils.isTableDataRow("| cell1 | cell2 |"))
        assertTrue(TableUtils.isTableDataRow("| 特性 | Apache 2.0 | MIT |"))
        assertTrue(TableUtils.isTableDataRow("cell1 | cell2"))
        assertTrue(TableUtils.isTableDataRow("| a | b | c | d |"))
    }

    @Test
    fun `isTableDataRow should reject separators and invalid rows`() {
        assertFalse(TableUtils.isTableDataRow("| --- | --- |"))
        assertFalse(TableUtils.isTableDataRow("| :--- | :--- |"))
        assertFalse(TableUtils.isTableDataRow("just text"))
        assertFalse(TableUtils.isTableDataRow("| single |")) // only 1 cell
    }

    @Test
    fun `isValidTableStart should validate header plus separator`() {
        val validTable = listOf(
            "| Header1 | Header2 |",
            "| --- | --- |",
            "| Data1 | Data2 |"
        )
        assertTrue(TableUtils.isValidTableStart(validTable, 0))

        val validTableChinese = listOf(
            "| 特性 | Apache 2.0 | MIT |",
            "| :--- | :--- | :--- |",
            "| 许可类型 | 宽松开源许可 | 宽松开源许可 |"
        )
        assertTrue(TableUtils.isValidTableStart(validTableChinese, 0))
    }

    @Test
    fun `isValidTableStart should reject invalid tables`() {
        // No separator line
        val noSeparator = listOf(
            "| Header1 | Header2 |",
            "| Data1 | Data2 |"
        )
        assertFalse(TableUtils.isValidTableStart(noSeparator, 0))

        // Single line
        val singleLine = listOf("| Header1 | Header2 |")
        assertFalse(TableUtils.isValidTableStart(singleLine, 0))

        // Column count mismatch
        val columnMismatch = listOf(
            "| Header1 | Header2 | Header3 |",
            "| --- | --- |",
            "| Data1 | Data2 | Data3 |"
        )
        assertFalse(TableUtils.isValidTableStart(columnMismatch, 0))
    }

    @Test
    fun `extractTableLines should extract complete table`() {
        val lines = listOf(
            "Some text before",
            "| Header1 | Header2 |",
            "| --- | --- |",
            "| Data1 | Data2 |",
            "| Data3 | Data4 |",
            "Some text after"
        )

        val (tableLines, nextIndex) = TableUtils.extractTableLines(lines, 1)

        assertEquals(4, tableLines.size)
        assertEquals("| Header1 | Header2 |", tableLines[0])
        assertEquals("| --- | --- |", tableLines[1])
        assertEquals("| Data1 | Data2 |", tableLines[2])
        assertEquals("| Data3 | Data4 |", tableLines[3])
        assertEquals(5, nextIndex)
    }

    @Test
    fun `extractTableLines should return empty for invalid start`() {
        val lines = listOf(
            "| Header1 | Header2 |",
            "| Data1 | Data2 |" // Missing separator
        )

        val (tableLines, nextIndex) = TableUtils.extractTableLines(lines, 0)

        assertTrue(tableLines.isEmpty())
        assertEquals(0, nextIndex)
    }

    @Test
    fun `parseTableRow should extract cells correctly`() {
        val cells = TableUtils.parseTableRow("| cell1 | cell2 | cell3 |")
        assertEquals(3, cells.size)
        assertEquals("cell1", cells[0])
        assertEquals("cell2", cells[1])
        assertEquals("cell3", cells[2])
    }

    @Test
    fun `parseTableRow should handle Chinese content`() {
        val cells = TableUtils.parseTableRow("| 特性 | Apache 2.0 | MIT |")
        assertEquals(3, cells.size)
        assertEquals("特性", cells[0])
        assertEquals("Apache 2.0", cells[1])
        assertEquals("MIT", cells[2])
    }

    @Test
    fun `parseTableRow should normalize fullwidth characters`() {
        // 全角分隔符：｜ 特性 ｜ Apache 2.0 ｜
        // 规范化后：| 特性 | Apache 2.0 |
        // 分割后得到 2 个单元格
        val cells = TableUtils.parseTableRow("｜ 特性 ｜ Apache 2.0 ｜")
        assertEquals(2, cells.size)
        assertEquals("特性", cells[0])
        assertEquals("Apache 2.0", cells[1])
    }

    @Test
    fun `user example table should be detected correctly`() {
        // 用户原始问题中的表格
        val lines = listOf(
            "| 特性 | Apache 2.0 | MIT |",
            "| :--- | :--- | :--- |",
            "| 许可类型 | 宽松开源许可 | 宽松开源许可 |",
            "| 专利授权 | 明确授予使用者专利权 | 无明确条款 |",
            "| 商标使用 | 明确禁止使用项目商标 | 无明确条款 |"
        )

        // 验证表格起始检测
        assertTrue(TableUtils.isValidTableStart(lines, 0))

        // 验证分隔行检测
        assertTrue(TableUtils.isTableSeparator(lines[1]))

        // 验证完整表格提取
        val (tableLines, nextIndex) = TableUtils.extractTableLines(lines, 0)
        assertEquals(5, tableLines.size)
        assertEquals(5, nextIndex)
    }
}
