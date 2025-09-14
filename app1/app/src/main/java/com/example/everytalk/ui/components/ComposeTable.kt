package com.example.everytalk.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// 为表格提供列对齐信息
data class TableData(
    val headers: List<String>,
    val rows: List<List<String>>,
    val aligns: List<TextAlign> = List(headers.size) { TextAlign.Left }
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
    val oddRowColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    
    var shouldRender by remember { mutableStateOf(delayMs == 0L) }
    
    LaunchedEffect(tableData, delayMs) {
        if (delayMs > 0L) {
            shouldRender = false
            delay(delayMs)
            shouldRender = true
        } else {
            shouldRender = true
        }
    }
    
    if (!shouldRender) {
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerBackgroundColor)
                .padding(8.dp)
        ) {
            tableData.headers.forEachIndexed { index, header ->
                val align = tableData.aligns.getOrNull(index) ?: TextAlign.Left
                val parts = remember(header) { parseMarkdownParts(normalizeMarkdownGlyphs(header), true) }
                EnhancedMarkdownText(
                    parts = parts,
                    rawMarkdown = header,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = align
                    ),
                    inTableContext = true
                )
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
        
        tableData.rows.forEachIndexed { rowIndex, row ->
            val backgroundColor = if (rowIndex % 2 == 0) evenRowColor else oddRowColor
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(backgroundColor)
                    .padding(8.dp)
            ) {
                row.forEachIndexed { cellIndex, cell ->
                    val align = tableData.aligns.getOrNull(cellIndex) ?: TextAlign.Left
                    val parts = remember(cell) { parseMarkdownParts(normalizeMarkdownGlyphs(cell), true) }
                    EnhancedMarkdownText(
                        parts = parts,
                        rawMarkdown = cell,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = align
                        ),
                        inTableContext = true
                    )
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

internal fun splitMarkdownTableRow(line: String): List<String> {
    // 兼容全角/半角竖线与常见表格字符
    val normalizedLine = line.replace('｜','|').replace('│','|').replace('┃','|')
    val trimmed = normalizedLine.trim()
    val hasLeading = trimmed.startsWith("|")
    val hasTrailing = trimmed.endsWith("|")

    val cells = mutableListOf<StringBuilder>()
    var current = StringBuilder()
    var escape = false
    var insideCode = false // naive inline code detection using backtick toggling

    fun flushCell() {
        cells.add(current)
        current = StringBuilder()
    }

    for (ch in trimmed) {
        when {
            escape -> {
                current.append(ch)
                escape = false
            }
            ch == '\\' -> {
                escape = true
            }
            ch == '`' -> {
                insideCode = !insideCode
                current.append(ch)
            }
            ch == '|' && !insideCode -> {
                flushCell()
            }
            else -> current.append(ch)
        }
    }
    cells.add(current)

    var result = cells.map { it.toString().replace("\\|", "|").trim() }.toMutableList()

    if (hasLeading && result.isNotEmpty() && result.first().isEmpty()) {
        result.removeFirst()
    }
    if (hasTrailing && result.isNotEmpty() && result.last().isEmpty()) {
        result.removeLast()
    }

    return result
}

private fun parseAlignmentRow(separatorLine: String, colCount: Int): List<TextAlign> {
    // 分隔符形如 | :--- | :---: | ---: |，并兼容全角冒号/破折号
    val tokens = splitMarkdownTableRow(
        separatorLine.replace('：', ':').replace('—','-').replace('－','-')
    )
    val aligns = tokens.map { token ->
        val t = token.replace('：', ':').trim()
        val left = t.startsWith(":")
        val right = t.endsWith(":")
        when {
            left && right -> TextAlign.Center
            right -> TextAlign.Right
            else -> TextAlign.Left
        }
    }.toMutableList()
    // 补齐或截断到列数
    return when {
        aligns.size < colCount -> aligns + List(colCount - aligns.size) { TextAlign.Left }
        aligns.size > colCount -> aligns.take(colCount)
        else -> aligns
    }
}

fun parseMarkdownTable(markdownTable: String): TableData? {
    // 全局标准化：兼容全角竖线、中文冒号、破折号等
    val normalizedTable = markdownTable
        .replace('｜','|')
        .replace('│','|')
        .replace('┃','|')
        .replace('—','-')
        .replace('－','-')
        .replace('：', ':')
    val lines = normalizedTable.trim().split("\n").filter { it.isNotBlank() }
    if (lines.size < 3) return null

    val headerCells = splitMarkdownTableRow(lines[0])
    if (headerCells.isEmpty()) return null

    val colCount = headerCells.size

    // 解析对齐行（第二行）
    val aligns = parseAlignmentRow(lines[1], colCount)

    val dataRows = mutableListOf<List<String>>()
    for (i in 2 until lines.size) {
        val rawCells = splitMarkdownTableRow(lines[i])
        if (rawCells.isEmpty()) continue
        val cells = when {
            rawCells.size < colCount -> rawCells + List(colCount - rawCells.size) { "" }
            rawCells.size > colCount -> rawCells.take(colCount)
            else -> rawCells
        }
        dataRows.add(cells)
    }

    return TableData(headerCells, dataRows, aligns)
}