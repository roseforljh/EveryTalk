package com.android.everytalk.ui.components

import com.android.everytalk.ui.components.table.TableUtils

/**
 * 基于 Regex 的 Markdown 文本解析器。
 * 将纯文本、代码块、表格等不同内容拆分开，以便 UI 层使用最合适的组件进行渲染。
 * 
 * 优化说明：
 * 1. 统一解析路径：流式与完成态使用相同的 Regex 逻辑，确保结构(Part数量与边界)完全一致。
 * 2. 消除跳动：由于解析产物结构稳定，流式结束时的 UI 切换不再触发重组或高度坍塌。
 */
object ContentParser {
    private val standaloneBlockMathRegex = Regex("^\\s*\\$\\$[\\s\\S]+\\$\\$\\s*$")
    private val standaloneInlineDollarMathRegex = Regex("^\\s*\\$(?!\\$)[^\\n$]+\\$(?!\\$)\\s*$")
    private val standaloneBracketMathRegex = Regex("^\\s*\\\\\\[[\\s\\S]+\\\\\\]\\s*$")
    private val standaloneParenMathRegex = Regex("^\\s*\\\\\\([\\s\\S]+\\\\\\)\\s*$")
    private val multilineStandaloneMathRegex = Regex(
        "^[ \\t]*(\\$\\$[\\s\\S]+?\\$\\$|\\\\\\[[\\s\\S]+?\\\\\\])[ \\t]*(?=\\n|$)"
    )
    private val trailingMathRegex = Regex(
        "^(.*?)(\\$\\$(?!\\$)[^\\n]+?\\$\\$|\\$(?!\\$)[^\\n$]+?\\$(?!\\$)|\\\\\\[[^\\n]+\\\\\\]|\\\\\\([^\\n]+\\\\\\))\\s*$"
    )
    private val fencedBlockLanguageRegex = Regex("^[A-Za-z0-9_+\\-#.]+$")
    private val openingFenceRegex = Regex("^([`~]{3,})([^`~]*)$")
    private val listMarkerRegex = Regex("^\\s{0,3}(?:[-*+]\\s+|\\d+[.)]\\s+)")
    private val inlineFenceRegex = Regex("(?m)([^\\s`~])([ \\t]*)(`{3,}|~{3,})([a-zA-Z0-9_+\\-#.]*)[ \\t]*$")

    /**
     * 解析完整内容。
     * 无论是否处于流式状态，均采用统一的 Regex 逻辑以保证产物结构稳定性。
     */
    fun parseCompleteContent(text: String, isStreaming: Boolean = false): List<ContentPart> {
        if (text.isEmpty()) return emptyList()
        return parseContent(normalizeInlineFences(text))
    }

    /**
     * 修复行内围栏标记：将粘在正文后面的 ``` 拆到新行。
     * 例如 "文本：```python" → "文本：\n```python"
     */
    private fun normalizeInlineFences(text: String): String {
        if (!text.contains("```") && !text.contains("~~~")) return text
        return inlineFenceRegex.replace(text) { mr ->
            "${mr.groupValues[1]}\n${mr.groupValues[3]}${mr.groupValues[4]}"
        }
    }

    /**
     * 核心 Regex 解析逻辑（原 parseStreamingFriendlyContent）。
     * 识别围栏代码块、表格、数学公式和普通文本。
     */
    private fun parseContent(text: String): List<ContentPart> {
        val parts = mutableListOf<ContentPart>()
        val lines = text.replace("\r\n", "\n").replace("\r", "\n").split('\n')
        val textBuffer = StringBuilder()
        var textStartOffset = 0
        var offset = 0
        var index = 0

        fun flushText() {
            if (textBuffer.isNotEmpty()) {
                parts.add(ContentPart.Text(textBuffer.toString(), textStartOffset))
                textBuffer.setLength(0)
            }
        }

        while (index < lines.size) {
            val line = lines[index]
            val nextOffset = offset + line.length + 1

            val openingFence = parseOpeningFence(line)
            if (openingFence != null && isValidFenceStart(line)) {
                flushText()
                val startOffset = offset
                val language = openingFence.language.ifBlank { null }
                val rawFence = line
                val codeBuilder = StringBuilder()
                index++
                offset = nextOffset
                var closed = false
                while (index < lines.size) {
                    val currentLine = lines[index]
                    val currentNextOffset = offset + currentLine.length + 1
                    if (isFenceClosingLine(currentLine, rawFence, openingFence.marker)) {
                        closed = true
                        index++
                        offset = currentNextOffset
                        break
                    }
                    if (codeBuilder.isNotEmpty()) codeBuilder.append('\n')
                    codeBuilder.append(currentLine)
                    index++
                    offset = currentNextOffset
                }
                
                // 统一产出 ContentPart.Code。
                // 无论是否闭合，都作为代码块处理。UI 层会根据流式状态决定渲染样式。
                parts.add(ContentPart.Code(language, codeBuilder.toString(), startOffset))
                continue
            }

            if (TableUtils.isValidTableStart(lines, index)) {
                flushText()
                val startOffset = offset
                val (tableLines, nextIndex) = TableUtils.extractTableLines(lines, index)
                if (tableLines.isNotEmpty()) {
                    parts.add(ContentPart.Table(tableLines, startOffset))
                    while (index < nextIndex) {
                        offset += lines[index].length + 1
                        index++
                    }
                    continue
                }
            }

            if (textBuffer.isEmpty()) {
                textStartOffset = offset
            }
            if (textBuffer.isNotEmpty()) textBuffer.append('\n')
            textBuffer.append(line)
            index++
            offset = nextOffset
        }

        flushText()
        return splitMathBlocks(mergeAdjacentTextParts(parts))
    }

    private data class OpeningFence(
        val marker: String,
        val language: String
    )

    private fun parseOpeningFence(line: String): OpeningFence? {
        val trimmed = line.trimStart()
        val match = openingFenceRegex.matchEntire(trimmed) ?: return null
        return OpeningFence(
            marker = match.groupValues[1],
            language = match.groupValues[2].trim()
        )
    }

    private fun isValidFenceStart(currentLine: String): Boolean {
        if (parseOpeningFence(currentLine) == null) return false
        if (currentLine.takeWhile { it == ' ' || it == '\t' }.contains('\t')) return false
        return true
    }

    private fun isFenceClosingLine(line: String, openingFence: String, marker: String): Boolean {
        val currentIndent = line.indexOfFirst { it != ' ' }.let { if (it < 0) line.length else it }
        val openingIndent = openingFence.indexOfFirst { it != ' ' }.let { if (it < 0) openingFence.length else it }
        if (openingIndent <= 3 && currentIndent > 3) return false
        if (openingIndent > 3 && currentIndent != openingIndent) return false

        val trimmed = line.trimStart()
        val markerChar = marker.first()
        var markerLength = 0
        while (markerLength < trimmed.length && trimmed[markerLength] == markerChar) {
            markerLength++
        }
        if (markerLength < marker.length) return false
        return trimmed.substring(markerLength).isBlank()
    }

    /**
     * 合并相邻的文本块
     */
    private fun mergeAdjacentTextParts(parts: List<ContentPart>): List<ContentPart> {
        if (parts.isEmpty()) return parts

        val merged = mutableListOf<ContentPart>()
        var currentTextBuilder: StringBuilder? = null
        var currentTextStartOffset = 0

        for (part in parts) {
            when (part) {
                is ContentPart.Text -> {
                    if (currentTextBuilder == null) {
                        currentTextBuilder = StringBuilder(part.content)
                        currentTextStartOffset = part.startOffset
                    } else {
                        currentTextBuilder.append(part.content)
                    }
                }
                else -> {
                    currentTextBuilder?.let {
                        val text = it.toString()
                        if (text.isNotEmpty()) {
                            merged.add(ContentPart.Text(text, currentTextStartOffset))
                        }
                    }
                    currentTextBuilder = null
                    merged.add(part)
                }
            }
        }

        currentTextBuilder?.let {
            val text = it.toString()
            if (text.isNotEmpty()) {
                merged.add(ContentPart.Text(text, currentTextStartOffset))
            }
        }

        return merged
    }

    /**
     * 公开的 splitMathBlocks 接口，供增量解析路径使用。
     */
    fun splitMathBlocksPublic(parts: List<ContentPart>): List<ContentPart> = splitMathBlocks(parts)

    private fun splitMathBlocks(parts: List<ContentPart>): List<ContentPart> {
        if (parts.isEmpty()) return parts

        val result = mutableListOf<ContentPart>()

        parts.forEach { part ->
            if (part is ContentPart.Text) {
                result.addAll(splitTextPartMathLines(part))
            } else {
                result.add(part)
            }
        }

        return result
    }

    private fun splitTextPartMathLines(part: ContentPart.Text): List<ContentPart> {
        val text = part.content
        if (text.isEmpty()) return listOf(part)

        val out = mutableListOf<ContentPart>()
        val textBuffer = StringBuilder()
        var textBufferStart = 0

        fun flushTextBuffer() {
            if (textBuffer.isNotEmpty()) {
                out.add(ContentPart.Text(textBuffer.toString(), part.startOffset + textBufferStart))
                textBuffer.clear()
            }
        }

        var index = 0
        while (index < text.length) {
            val multilineMathMatch = multilineStandaloneMathRegex.find(text.substring(index))
                ?.takeIf { match ->
                    match.range.first == 0 &&
                        match.value.contains('\n') &&
                        isStandaloneMath(match.groupValues[1].trim())
                }
            if (multilineMathMatch != null) {
                flushTextBuffer()
                val mathGroup = multilineMathMatch.groups[1]!!
                val mathStart = index + mathGroup.range.first
                val mathEndExclusive = index + mathGroup.range.last + 1
                out.add(ContentPart.Math(text.substring(mathStart, mathEndExclusive), part.startOffset + mathStart))
                index += multilineMathMatch.range.last + 1
                if (index < text.length && text[index] == '\n') {
                    out.add(ContentPart.Text("\n", part.startOffset + index))
                    index++
                }
                continue
            }

            val lineStart = index
            var lineEnd = index
            while (lineEnd < text.length && text[lineEnd] != '\n') {
                lineEnd++
            }
            val hasLineBreak = lineEnd < text.length && text[lineEnd] == '\n'
            val contentEndExclusive = if (hasLineBreak) lineEnd else text.length
            val fullLineEndExclusive = if (hasLineBreak) lineEnd + 1 else contentEndExclusive

            val lineContent = text.substring(lineStart, contentEndExclusive)
            val trimmed = lineContent.trim()
            val isStandaloneMathLine = trimmed.isNotEmpty() && isStandaloneMath(trimmed)

            if (isStandaloneMathLine) {
                flushTextBuffer()
                out.add(ContentPart.Math(trimmed, part.startOffset + lineStart + lineContent.indexOf(trimmed)))
                if (hasLineBreak) {
                    out.add(ContentPart.Text("\n", part.startOffset + lineEnd))
                }
            } else {
                val trailingMathMatch = trailingMathRegex.matchEntire(lineContent)
                if (trailingMathMatch != null) {
                    val prefix = trailingMathMatch.groupValues[1]
                    val mathToken = trailingMathMatch.groupValues[2]
                    if (shouldPromoteTrailingMath(prefix, mathToken)) {
                        flushTextBuffer()
                        if (prefix.isNotEmpty()) {
                            out.add(ContentPart.Text(prefix, part.startOffset + lineStart))
                        }
                        out.add(ContentPart.Math(mathToken, part.startOffset + lineStart + lineContent.indexOf(mathToken)))
                        if (hasLineBreak) {
                            out.add(ContentPart.Text("\n", part.startOffset + lineEnd))
                        }
                        index = fullLineEndExclusive
                        continue
                    }
                }

                if (textBuffer.isEmpty()) {
                    textBufferStart = lineStart
                }
                textBuffer.append(text.substring(lineStart, fullLineEndExclusive))
            }

            index = fullLineEndExclusive
        }

        flushTextBuffer()
        return if (out.isEmpty()) listOf(part) else out
    }

    private fun shouldPromoteTrailingMath(prefix: String, mathToken: String): Boolean {
        val trimmedPrefix = prefix.trim()
        if (trimmedPrefix.isEmpty()) return false

        // 避免破坏列表语义
        val listMarkerOnly = trimmedPrefix == "-" ||
            trimmedPrefix == "*" ||
            trimmedPrefix == "+" ||
            Regex("^\\d+[.)]$").matches(trimmedPrefix)
        if (listMarkerOnly) return false

        val mathBody = mathToken
            .removePrefix("$$")
            .removeSuffix("$$")
            .removePrefix("$")
            .removeSuffix("$")
            .removePrefix("\\[")
            .removeSuffix("\\]")
            .removePrefix("\\(")
            .removeSuffix("\\)")
            .trim()

        val isLong = mathBody.length >= 24
        val hasComplexToken = mathBody.contains("\\frac") ||
            mathBody.contains("\\sum") ||
            mathBody.contains("\\int") ||
            mathBody.contains("\\prod") ||
            mathBody.contains("\\lim") ||
            mathBody.contains("\\begin")

        return isLong || hasComplexToken
    }

    private fun isStandaloneMath(trimmedLine: String): Boolean {
        return standaloneBlockMathRegex.matches(trimmedLine) ||
            standaloneInlineDollarMathRegex.matches(trimmedLine) ||
            standaloneBracketMathRegex.matches(trimmedLine) ||
            standaloneParenMathRegex.matches(trimmedLine)
    }
}

/**
 * UI 层使用的密封类，与 Data 层区分
 */
@androidx.compose.runtime.Immutable
sealed class ContentPart {
    abstract val startOffset: Int
    
    data class Text(val content: String, override val startOffset: Int = 0) : ContentPart()
    data class Code(val language: String?, val content: String, override val startOffset: Int = 0) : ContentPart()
    data class Table(val lines: List<String>, override val startOffset: Int = 0) : ContentPart()
    data class Math(val content: String, override val startOffset: Int = 0) : ContentPart()

    fun contentHash(): Int = when (this) {
        is Text -> content.hashCode()
        is Code -> (language.hashCode() * 31) + content.hashCode()
        is Table -> lines.hashCode()
        is Math -> content.hashCode()
    }

    fun contentPreview(): String = when (this) {
        is Text -> content
        is Code -> content
        is Table -> lines.joinToString("\n")
        is Math -> content
    }
}
