package com.android.everytalk.ui.components

import android.util.Log
import com.android.everytalk.config.PerformanceConfig
import com.android.everytalk.ui.components.math.MathParser

/**
 * 内容类型枚举
 */
sealed class ContentPart {
    /**
     * 普通文本（支持Markdown格式）
     */
    data class Text(val content: String) : ContentPart()
    
    /**
     * 代码块
     */
    data class Code(val content: String, val language: String?) : ContentPart()
    
    /**
     * 表格
     */
    data class Table(val lines: List<String>) : ContentPart()
}

/**
 * 内容解析器
 * 
 * 核心功能：
 * - 统一解析Markdown、数学公式、代码块、表格
 * - 流式解析支持（批量缓冲）
 * - 安全断句点检测（避免不完整结构）
 */
object ContentParser {
    private const val TAG = "ContentParser"
    
    // 使用PerformanceConfig中的配置
    private const val PARSE_BUFFER_SIZE = PerformanceConfig.PARSE_BUFFER_SIZE
    
    // 代码块正则：```language\ncode```
    private val CODE_BLOCK_REGEX = Regex(
        """```(\w*)\n(.*?)```""", 
        RegexOption.DOT_MATCHES_ALL
    )
    
    /**
     * 完整内容解析（用于流式结束或完整消息）
     * 
     * @param text 原始文本
     * @return 解析后的内容块列表
     */
    fun parseCompleteContent(text: String, isStreaming: Boolean = false): List<ContentPart> {
        if (text.isBlank()) return listOf(ContentPart.Text(text))
        
        try {
            val parts = mutableListOf<ContentPart>()
            val lines = text.lines()
            var currentIndex = 0
            
            while (currentIndex < lines.size) {
                val line = lines[currentIndex]
                
                // 检查是否为代码块开始
                if (line.trimStart().startsWith("```")) {
                    val (codeBlock, nextIndex) = extractCodeBlock(lines, currentIndex, isStreaming)
                    if (codeBlock != null) {
                        parts.add(codeBlock)
                        currentIndex = nextIndex
                        continue
                    }
                }
                
                // 检查是否为表格开始
                if (isTableLine(line)) {
                    val (table, nextIndex) = extractTable(lines, currentIndex)
                    if (table != null) {
                        parts.add(table)
                        currentIndex = nextIndex
                        continue
                    }
                }
                
                // 收集普通文本行
                val textLines = mutableListOf<String>()
                while (currentIndex < lines.size && 
                       !lines[currentIndex].trimStart().startsWith("```") &&
                       !isTableLine(lines[currentIndex])) {
                    textLines.add(lines[currentIndex])
                    currentIndex++
                }
                
                if (textLines.isNotEmpty()) {
                    parts.add(ContentPart.Text(textLines.joinToString("\n")))
                }
            }
            
            Log.d(TAG, "Parsed ${parts.size} content parts from text")
            return parts.ifEmpty { listOf(ContentPart.Text(text)) }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing content", e)
            return listOf(ContentPart.Text(text))
        }
    }

    /**
     * 仅解析代码块（流式渲染快速路径）
     * - 跳过表格等复杂结构，降低主线程解析开销
     * - 非代码部分整体作为文本返回
     */
    fun parseCodeBlocksOnly(text: String): List<ContentPart> {
        if (text.isBlank()) return listOf(ContentPart.Text(text))
        return try {
            val parts = mutableListOf<ContentPart>()
            val lines = text.lines()
            var currentIndex = 0

            while (currentIndex < lines.size) {
                val line = lines[currentIndex]

                // 仅识别并提取 ``` 代码块
                if (line.trimStart().startsWith("```")) {
                    val (codeBlock, nextIndex) = extractCodeBlock(lines, currentIndex)
                    if (codeBlock != null) {
                        parts.add(codeBlock)
                        currentIndex = nextIndex
                        continue
                    }
                }

                // 聚合为普通文本，直到遇到下一个代码块
                val textLines = mutableListOf<String>()
                while (currentIndex < lines.size && !lines[currentIndex].trimStart().startsWith("```")) {
                    textLines.add(lines[currentIndex])
                    currentIndex++
                }
                if (textLines.isNotEmpty()) {
                    parts.add(ContentPart.Text(textLines.joinToString("\n")))
                }
            }

            if (parts.isEmpty()) listOf(ContentPart.Text(text)) else parts
        } catch (e: Exception) {
            Log.e(TAG, "Error in parseCodeBlocksOnly", e)
            listOf(ContentPart.Text(text))
        }
    }
    
    /**
     * 提取代码块
     */
    private fun extractCodeBlock(
        lines: List<String>,
        startIndex: Int,
        isStreaming: Boolean = false
    ): Pair<ContentPart.Code?, Int> {
        // 起始边界与空安全校验，避免 String.charAt/startsWith 在异常输入上崩溃
        if (startIndex < 0 || startIndex >= lines.size) {
            return null to (startIndex + 1).coerceAtMost(lines.size)
        }
        val startTrimmed = try {
            lines[startIndex].trimStart()
        } catch (_: Throwable) {
            return null to (startIndex + 1).coerceAtMost(lines.size)
        }
        if (startTrimmed.length < 3 || !startTrimmed.startsWith("```")) {
            return null to (startIndex + 1).coerceAtMost(lines.size)
        }

        val language = startTrimmed.removePrefix("```").trim().ifBlank { null }
        val codeLines = mutableListOf<String>()
        var currentIndex = startIndex + 1

        while (currentIndex < lines.size) {
            val endTrimmed = try {
                lines[currentIndex].trimStart()
            } catch (_: Throwable) {
                // 出现无法读取当前行的异常：保守处理为“未闭合”
                return if (isStreaming) {
                    ContentPart.Code(codeLines.joinToString("\n"), language) to currentIndex
                } else {
                    null to (startIndex + 1).coerceAtMost(lines.size)
                }
            }

            if (endTrimmed.length >= 3 && endTrimmed.startsWith("```")) {
                // 找到结束标记
                return ContentPart.Code(codeLines.joinToString("\n"), language) to (currentIndex + 1).coerceAtMost(lines.size)
            }

            // 收集代码行（对异常输入做保护）
            val safeLine = try { lines[currentIndex] } catch (_: Throwable) { "" }
            codeLines.add(safeLine)
            currentIndex++
        }

        // 未找到结束标记：流式模式下将剩余部分视为代码块，非流式下回退为文本
        return if (isStreaming) {
            ContentPart.Code(codeLines.joinToString("\n"), language) to currentIndex
        } else {
            null to (startIndex + 1).coerceAtMost(lines.size)
        }
    }
    
    /**
     * 提取表格
     */
    private fun extractTable(lines: List<String>, startIndex: Int): Pair<ContentPart.Table?, Int> {
        val tableLines = mutableListOf<String>()
        var currentIndex = startIndex
        
        // 收集连续的表格行
        while (currentIndex < lines.size && isTableLine(lines[currentIndex])) {
            tableLines.add(lines[currentIndex])
            currentIndex++
        }
        
        // 验证表格格式：至少需要表头、分隔行
        if (tableLines.size >= 2 && isTableSeparator(tableLines[1])) {
            return ContentPart.Table(tableLines) to currentIndex
        }
        
        // 不是有效的表格
        return null to startIndex + 1
    }
    
    // 缓存编译后的正则（避免重复编译）
    private val tableSeparatorRegex = Regex("^\\s*\\|?\\s*[-:]+\\s*(\\|\\s*[-:]+\\s*)+\\|?\\s*$")
    
    /**
     * 检查是否为表格行（优化版：减少正则匹配）
     */
    private fun isTableLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        
        // 快速检查：必须包含 |
        if (!trimmed.contains('|')) return false
        
        // 表格行必须包含至少两个 | 符号
        val pipeCount = trimmed.count { it == '|' }
        if (pipeCount < 2) return false
        
        // 🎯 优化：先用简单规则判断，减少正则匹配
        // 分隔行特征：主要包含 - : | 和空格
        val isLikelySeparator = trimmed.count { it == '-' || it == ':' } >= pipeCount
        if (isLikelySeparator) {
            // 精确验证分隔行
            return tableSeparatorRegex.matches(trimmed)
        }
        
        // 数据行：包含 | 但不全是特殊字符
        val isDataRow = trimmed.any { it != '|' && it != '-' && it != ':' && !it.isWhitespace() }
        
        return isDataRow
    }
    
    /**
     * 检查是否为表格分隔行（优化版：复用缓存的正则）
     */
    private fun isTableSeparator(line: String): Boolean {
        val trimmed = line.trim()
        return tableSeparatorRegex.matches(trimmed)
    }
    
    /**
     * 流式内容解析（带缓冲区）
     * 
     * @param currentBuffer 当前缓冲区内容
     * @param isComplete 是否流式结束
     * @return Pair<解析的内容块列表, 保留的缓冲内容>
     */
    fun parseStreamingContent(
        currentBuffer: String,
        isComplete: Boolean
    ): Pair<List<ContentPart>, String> {
        // 1) 未完成且缓冲区未满，直接保留
        if (!isComplete && currentBuffer.length < PARSE_BUFFER_SIZE) {
            return emptyList<ContentPart>() to currentBuffer
        }

        // 2) 若仍未完成，且处于未闭合的数学/代码块中，则暂缓解析，避免半成品闪烁
        if (!isComplete && isInsideUnclosedCodeFence(currentBuffer)) {
            return emptyList<ContentPart>() to currentBuffer
        }
        // 数学未闭合（$ 或 $$ 奇偶/未闭合），同样暂缓，等待安全闭合点
        if (!isComplete && MathParser.isInsideUnclosedMath(currentBuffer)) {
            return emptyList<ContentPart>() to currentBuffer
        }

        // 3) 已完成则全量解析
        if (isComplete) {
            return parseCompleteContent(currentBuffer) to ""
        }

        // 4) 根据安全断点切分
        val safeEndIndex = findSafeParsePoint(currentBuffer)
        if (safeEndIndex <= 0) {
            return emptyList<ContentPart>() to currentBuffer
        }

        val toParse = currentBuffer.substring(0, safeEndIndex)
        val retained = currentBuffer.substring(safeEndIndex)

        val parts = parseCompleteContent(toParse)
        Log.d(TAG, "Streaming parse: ${parts.size} parts, retained: ${retained.length} chars")
        return parts to retained
    }
    
    /**
     * Find a safe parse point to avoid cutting incomplete structures.
     */
    private fun findSafeParsePoint(text: String): Int {
        // Priority order:
        // 1) end of the most recent closed code fence (three backticks)
        // 2) natural breaks: full-width period (U+3002) or newline
        // 3) fallback: midpoint to avoid very small slices
        // If no safe candidates exist, return 0 to keep buffering.
        //
        // When an unfinished structure exists, fall back to the latest closed boundary.
        // Compute the nearest safe position based on closed code fences
        val codeFenceSafe = lastSafeEndForFence(text, fence = "```")
        // 数学闭合切点（最近闭合的 $...$ 或 $$...$$）
        val mathSafe = MathParser.findSafeMathCut(text).takeIf { it > 0 }

        // Natural breakpoints
        val period = text.lastIndexOf('。')
        val newline = text.lastIndexOf('\n')

        // Fallback keeps at least half of the content before cutting
        val halfPoint = text.length / 2

        val candidates = listOfNotNull(
            codeFenceSafe,
            mathSafe,
            period.takeIf { it > 0 },
            newline.takeIf { it > 0 },
            halfPoint.takeIf { it > 0 }
        )

        return if (candidates.isNotEmpty()) candidates.maxOrNull()!! else 0
    }

    // ======= Helpers: unfinished structure detection and safe position lookups =======

    private fun isInsideUnclosedCodeFence(text: String): Boolean {
        // Count code fences composed of three backticks; odd occurrences indicate an unfinished block
        var idx = 0
        var count = 0
        while (true) {
            val pos = text.indexOf("```", idx)
            if (pos < 0) break
            count++
            idx = pos + 3
        }
        return (count % 2) == 1
    }

    private fun lastSafeEndForFence(text: String, fence: String): Int? {
        // 返回最近一个“完整对”的结束位置（即闭合围栏后的索引）
        val idxs = mutableListOf<Int>()
        var i = 0
        while (true) {
            val p = text.indexOf(fence, i)
            if (p < 0) break
            idxs.add(p)
            i = p + fence.length
        }
        if (idxs.size < 2) return null
        // 成对配对，取最后一对的闭合位置之后
        val pairCount = idxs.size / 2
        val closePos = idxs[pairCount * 2 - 1] + fence.length
        return closePos
    }

    
    /**
     * 解析纯文本（简化版，移除数学公式支持）
     */
    
    /**
     * 内部：内容片段
     */
    private data class ContentSegment(
        val type: SegmentType,
        val range: IntRange,
        val content: String,
        val metadata: String?
    )
    
    private enum class SegmentType {
        CODE
    }
    
    /**
     * 判断范围是否包含另一个范围的起始点
     */
    private fun IntRange.contains(index: Int): Boolean {
        return index in first..last
    }
}
