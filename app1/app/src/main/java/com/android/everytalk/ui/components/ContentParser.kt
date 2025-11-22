package com.android.everytalk.ui.components

import android.util.Log
import com.android.everytalk.config.PerformanceConfig
import com.android.everytalk.ui.components.math.MathParser
import com.android.everytalk.ui.components.table.TableUtils

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
 * 代码块提取结果
 */
private sealed class CodeBlockResult {
    /**
     * 成功提取完整代码块
     */
    data class Success(val code: ContentPart.Code, val nextIndex: Int) : CodeBlockResult()
    
    /**
     * 代码块未闭合（流式模式下的临时状态）
     */
    data class Unclosed(val code: ContentPart.Code, val nextIndex: Int) : CodeBlockResult()
    
    /**
     * 无效的代码块格式（应作为普通文本处理）
     */
    object Invalid : CodeBlockResult()
}

/**
 * 内容解析器
 * 
 * 核心功能：
 * - 统一解析Markdown、数学公式、代码块
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
            
            Log.d(TAG, "parseCompleteContent: total lines=${lines.size}, isStreaming=$isStreaming")
            
            while (currentIndex < lines.size) {
                val line = lines[currentIndex]
                
                // 检查是否为代码块开始
                if (line.trimStart().startsWith("```")) {
                    Log.d(TAG, "Found code block start at line $currentIndex: '${line.take(20)}'")
                    when (val result = extractCodeBlockNew(lines, currentIndex, isStreaming)) {
                        is CodeBlockResult.Success -> {
                            Log.d(TAG, "CodeBlock Success: lang=${result.code.language}, lines=${result.code.content.lines().size}, nextIndex=${result.nextIndex}")
                            parts.add(result.code)
                            currentIndex = result.nextIndex
                            continue
                        }
                        is CodeBlockResult.Unclosed -> {
                            Log.d(TAG, "CodeBlock Unclosed: lang=${result.code.language}, lines=${result.code.content.lines().size}")
                            parts.add(result.code)
                            currentIndex = result.nextIndex
                            continue
                        }
                        is CodeBlockResult.Invalid -> {
                            Log.d(TAG, "CodeBlock Invalid at line $currentIndex, adding as Text")
                            parts.add(ContentPart.Text(line))
                            currentIndex++
                            continue
                        }
                    }
                }

                // 检查是否为表格开始
                if (TableUtils.isTableLine(line)) {
                    val (tableLines, nextIndex) = TableUtils.extractTableLines(lines, currentIndex)
                    if (tableLines.isNotEmpty()) {
                        parts.add(ContentPart.Table(tableLines))
                        currentIndex = nextIndex
                        continue
                    }
                }
                
                // 收集普通文本行
                val textLines = mutableListOf<String>()
                while (currentIndex < lines.size) {
                    val currentLine = lines[currentIndex]
                    
                    // 遇到代码块起始，立即中断文本收集
                    if (currentLine.trimStart().startsWith("```")) {
                        break
                    }

                    // 遇到可能的表格行，检查是否为有效表格起始
                    if (TableUtils.isTableLine(currentLine)) {
                        val nextLine = lines.getOrNull(currentIndex + 1)
                        if (nextLine != null && TableUtils.isTableSeparator(nextLine)) {
                            break
                        }
                    }

                    textLines.add(currentLine)
                    currentIndex++
                }
                
                if (textLines.isNotEmpty()) {
                    parts.add(ContentPart.Text(textLines.joinToString("\n")))
                }
            }
            
            Log.d(TAG, "Parsed ${parts.size} content parts: ${parts.mapIndexed { idx, it ->
                when(it) {
                    is ContentPart.Text -> "[$idx]Text(len=${it.content.length}, preview='${it.content.take(50).replace("\n", "\\n")}')"
                    is ContentPart.Code -> "[$idx]Code(lang=${it.language}, codeLines=${it.content.lines().size})"
                    is ContentPart.Table -> "[$idx]Table(${it.lines.size} rows)"
                }
            }}")
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
     * 提取代码块（新版本，返回明确的结果类型）
     */
    private fun extractCodeBlockNew(
        lines: List<String>,
        startIndex: Int,
        isStreaming: Boolean = false
    ): CodeBlockResult {
        // 边界检查
        if (startIndex < 0 || startIndex >= lines.size) {
            return CodeBlockResult.Invalid
        }
        
        val startLine = try {
            lines[startIndex].trimStart()
        } catch (_: Throwable) {
            return CodeBlockResult.Invalid
        }
        
        // 验证代码块起始标记
        if (startLine.length < 3 || !startLine.startsWith("```")) {
            return CodeBlockResult.Invalid
        }

        val language = startLine.removePrefix("```").trim().ifBlank { null }
        val codeLines = mutableListOf<String>()
        var currentIndex = startIndex + 1

        // 收集代码行，寻找结束标记
        while (currentIndex < lines.size) {
            val currentLine = try {
                lines[currentIndex]
            } catch (_: Throwable) {
                // 异常情况：根据模式返回不同结果
                return if (isStreaming && codeLines.isNotEmpty()) {
                    CodeBlockResult.Unclosed(
                        ContentPart.Code(codeLines.joinToString("\n"), language),
                        currentIndex
                    )
                } else {
                    CodeBlockResult.Invalid
                }
            }
            
            val trimmedLine = currentLine.trimStart()
            
            // 找到结束标记
            if (trimmedLine.length >= 3 && trimmedLine.startsWith("```")) {
                return CodeBlockResult.Success(
                    ContentPart.Code(codeLines.joinToString("\n"), language),
                    currentIndex + 1
                )
            }

            // 收集代码内容
            codeLines.add(currentLine)
            currentIndex++
        }

        // 未找到结束标记
        // 无论流式还是非流式，都将其作为代码块处理（避免Markdown渲染器再次渲染）
        return CodeBlockResult.Success(
            ContentPart.Code(codeLines.joinToString("\n"), language),
            currentIndex
        )
    }
    
    /**
     * 提取代码块（旧版本，保持向后兼容）
     * @deprecated 使用 extractCodeBlockNew 替代
     */
    @Deprecated("Use extractCodeBlockNew instead", ReplaceWith("extractCodeBlockNew(lines, startIndex, isStreaming)"))
    private fun extractCodeBlock(
        lines: List<String>,
        startIndex: Int,
        isStreaming: Boolean = false
    ): Pair<ContentPart.Code?, Int> {
        return when (val result = extractCodeBlockNew(lines, startIndex, isStreaming)) {
            is CodeBlockResult.Success -> result.code to result.nextIndex
            is CodeBlockResult.Unclosed -> result.code to result.nextIndex
            is CodeBlockResult.Invalid -> null to (startIndex + 1).coerceAtMost(lines.size)
        }
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
