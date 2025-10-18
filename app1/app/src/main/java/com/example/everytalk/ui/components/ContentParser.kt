package com.example.everytalk.ui.components

import android.util.Log
import com.example.everytalk.config.PerformanceConfig

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
    fun parseCompleteContent(text: String): List<ContentPart> {
        if (text.isBlank()) return listOf(ContentPart.Text(text))
        
        try {
            val parts = mutableListOf<ContentPart>()
            val lines = text.lines()
            var currentIndex = 0
            
            while (currentIndex < lines.size) {
                val line = lines[currentIndex]
                
                // 检查是否为代码块开始
                if (line.trimStart().startsWith("```")) {
                    val (codeBlock, nextIndex) = extractCodeBlock(lines, currentIndex)
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
     * 提取代码块
     */
    private fun extractCodeBlock(lines: List<String>, startIndex: Int): Pair<ContentPart.Code?, Int> {
        val startLine = lines[startIndex].trimStart()
        if (!startLine.startsWith("```")) return null to startIndex + 1
        
        val language = startLine.removePrefix("```").trim()
        val codeLines = mutableListOf<String>()
        var currentIndex = startIndex + 1
        
        while (currentIndex < lines.size) {
            val line = lines[currentIndex]
            if (line.trimStart().startsWith("```")) {
                // 找到结束标记
                return ContentPart.Code(codeLines.joinToString("\n"), language.takeIf { it.isNotBlank() }) to currentIndex + 1
            }
            codeLines.add(line)
            currentIndex++
        }
        
        // 未找到结束标记，返回null
        return null to startIndex + 1
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
    
    /**
     * 检查是否为表格行
     */
    private fun isTableLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        
        // 表格行必须包含至少两个 | 符号
        val pipeCount = trimmed.count { it == '|' }
        if (pipeCount < 2) return false
        
        // 检查是否为分隔行
        val isSeparator = trimmed.matches(Regex("^\\s*\\|?\\s*[-:]+\\s*(\\|\\s*[-:]+\\s*)+\\|?\\s*$"))
        
        // 检查是否为数据行
        val isDataRow = trimmed.contains("|") && !trimmed.all { it == '|' || it == '-' || it == ':' || it.isWhitespace() }
        
        return isSeparator || isDataRow
    }
    
    /**
     * 检查是否为表格分隔行
     */
    private fun isTableSeparator(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.matches(Regex("^\\s*\\|?\\s*[-:]+\\s*(\\|\\s*[-:]+\\s*)+\\|?\\s*$"))
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
        // 1. 如果未完成且缓冲区未满，不解析
        if (!isComplete && currentBuffer.length < PARSE_BUFFER_SIZE) {
            return emptyList<ContentPart>() to currentBuffer
        }
        
        // 2. 如果已完成，解析全部
        if (isComplete) {
            return parseCompleteContent(currentBuffer) to ""
        }
        
        // 3. 查找安全断句点
        val safeEndIndex = findSafeParsePoint(currentBuffer)
        
        if (safeEndIndex <= 0) {
            // 没有找到安全断句点，继续缓冲
            return emptyList<ContentPart>() to currentBuffer
        }
        
        // 4. 解析安全部分
        val toParse = currentBuffer.substring(0, safeEndIndex)
        val retained = currentBuffer.substring(safeEndIndex)
        
        val parts = parseCompleteContent(toParse)
        
        Log.d(TAG, "Streaming parse: ${parts.size} parts, retained: ${retained.length} chars")
        return parts to retained
    }
    
    /**
     * 查找安全解析点（避免截断未完成的结构）
     */
    private fun findSafeParsePoint(text: String): Int {
        // 优先级：
        // 1. 代码块结束标记```
        // 2. 数学块结束标记$$
        // 3. 句号/换行符
        // 4. 至少一半内容
        
        val codeEnd = text.lastIndexOf("```")
        val mathEnd = text.lastIndexOf("$$")
        val period = text.lastIndexOf('。')
        val newline = text.lastIndexOf('\n')
        val halfPoint = text.length / 2
        
        val candidates = listOf(
            codeEnd, 
            mathEnd, 
            period, 
            newline, 
            halfPoint
        ).filter { it > 0 }
        
        return if (candidates.isNotEmpty()) candidates.maxOrNull()!! else 0
    }
    
    /**
     * 解析纯文本（简化版，移除数学公式支持）
     */
    private fun parseTextWithMath(text: String): List<ContentPart> {
        if (text.isBlank()) return emptyList()
        return listOf(ContentPart.Text(text))
    }
    
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
