package com.android.everytalk.ui.components

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.parser.MarkdownParser

/**
 * 使用 intellij-markdown AST 解析 Markdown 文本。
 * 将纯文本、代码块、表格等不同内容拆分开，以便 UI 层使用最合适的组件进行渲染。
 */
object ContentParser {
    private val flavour = GFMFlavourDescriptor()
    private val parser = MarkdownParser(flavour)
    private val standaloneBlockMathRegex = Regex("^\\s*\\$\\$[\\s\\S]+\\$\\$\\s*$")
    private val standaloneInlineDollarMathRegex = Regex("^\\s*\\$(?!\\$)[^\\n$]+\\$(?!\\$)\\s*$")
    private val standaloneBracketMathRegex = Regex("^\\s*\\\\\\[[\\s\\S]+\\\\\\]\\s*$")
    private val standaloneParenMathRegex = Regex("^\\s*\\\\\\([\\s\\S]+\\\\\\)\\s*$")
    private val trailingMathRegex = Regex(
        "^(.*?)(\\$\\$(?!\\$)[^\\n]+?\\$\\$|\\$(?!\\$)[^\\n$]+?\\$(?!\\$)|\\\\\\[[^\\n]+\\\\\\]|\\\\\\([^\\n]+\\\\\\))\\s*$"
    )

    fun parseCompleteContent(text: String, isStreaming: Boolean = false): List<ContentPart> {
        if (text.isEmpty()) return emptyList()
        val astTree = parser.buildMarkdownTreeFromString(text)
        return extractParts(astTree, text)
    }

    private fun extractParts(root: ASTNode, text: String): List<ContentPart> {
        val parts = mutableListOf<ContentPart>()
        val textBuffer = StringBuilder()

        var textBufferStartOffset = -1
        
        fun flushTextBuffer() {
            if (textBuffer.isNotEmpty()) {
                parts.add(ContentPart.Text(textBuffer.toString(), if (textBufferStartOffset >= 0) textBufferStartOffset else 0))
                textBuffer.clear()
                textBufferStartOffset = -1
            }
        }

        fun processNode(node: ASTNode) {
            when (node.type) {
                MarkdownElementTypes.CODE_FENCE -> {
                    flushTextBuffer()
                    val (language, code) = parseCodeFence(node, text)
                    parts.add(ContentPart.Code(language, code, node.startOffset))
                }
                MarkdownElementTypes.CODE_BLOCK -> {
                    flushTextBuffer()
                    val code = node.getTextInNode(text).toString()
                        .lines()
                        .joinToString("\n") { it.removePrefix("    ").removePrefix("\t") }
                    parts.add(ContentPart.Code(null, code, node.startOffset))
                }
                GFMElementTypes.TABLE -> {
                    flushTextBuffer()
                    val tableText = node.getTextInNode(text).toString()
                    val tableLines = tableText.lines().filter { it.isNotBlank() }
                    parts.add(ContentPart.Table(tableLines, node.startOffset))
                }
                else -> {
                    if (node.children.isEmpty()) {
                        if (isTextNode(node.type)) {
                            if (textBuffer.isEmpty()) {
                                textBufferStartOffset = node.startOffset
                            }
                            textBuffer.append(node.getTextInNode(text))
                        }
                    } else {
                        if (shouldProcessAsBlock(node.type)) {
                            if (textBuffer.isEmpty()) {
                                textBufferStartOffset = node.startOffset
                            }
                            val blockText = node.getTextInNode(text).toString()
                            textBuffer.append(blockText)
                        } else {
                            node.children.forEach { processNode(it) }
                        }
                    }
                }
            }
        }

        root.children.forEach { child ->
            processNode(child)
        }

        flushTextBuffer()

        val merged = mergeAdjacentTextParts(parts)
        return splitMathBlocks(merged)
    }

    private fun parseCodeFence(node: ASTNode, text: String): Pair<String?, String> {
        var language: String? = null
        val codeBuilder = StringBuilder()
        var insideFence = false
        var skippedFirstEolAfterStart = false

        for (child in node.children) {
            when (child.type) {
                MarkdownTokenTypes.FENCE_LANG -> {
                    language = child.getTextInNode(text).toString().trim().ifBlank { null }
                }
                MarkdownTokenTypes.CODE_FENCE_START -> {
                    insideFence = true
                    skippedFirstEolAfterStart = false
                }
                MarkdownTokenTypes.CODE_FENCE_END -> {
                    insideFence = false
                }
                MarkdownTokenTypes.EOL -> {
                    if (insideFence) {
                        if (!skippedFirstEolAfterStart) {
                            skippedFirstEolAfterStart = true
                        } else if (codeBuilder.isNotEmpty() && codeBuilder.last() != '\n') {
                            codeBuilder.append('\n')
                        }
                    }
                }
                MarkdownTokenTypes.CODE_FENCE_CONTENT -> {
                    if (insideFence) {
                        codeBuilder.append(child.getTextInNode(text).toString())
                        skippedFirstEolAfterStart = true
                    }
                }
            }
        }

        val code = codeBuilder.toString()
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trimEnd()

        return Pair(language, code)
    }

    /**
     * 判断节点类型是否应作为文本处理
     */
    private fun isTextNode(type: IElementType): Boolean {
        return type == MarkdownTokenTypes.TEXT ||
                type == MarkdownTokenTypes.WHITE_SPACE ||
                type == MarkdownTokenTypes.EOL ||
                type == MarkdownTokenTypes.SINGLE_QUOTE ||
                type == MarkdownTokenTypes.DOUBLE_QUOTE ||
                type == MarkdownTokenTypes.LPAREN ||
                type == MarkdownTokenTypes.RPAREN ||
                type == MarkdownTokenTypes.LBRACKET ||
                type == MarkdownTokenTypes.RBRACKET ||
                type == MarkdownTokenTypes.LT ||
                type == MarkdownTokenTypes.GT ||
                type == MarkdownTokenTypes.COLON ||
                type == MarkdownTokenTypes.EXCLAMATION_MARK
    }

    /**
     * 判断节点是否应作为整体块处理（不递归）
     */
    private fun shouldProcessAsBlock(type: IElementType): Boolean {
        return type == MarkdownElementTypes.PARAGRAPH ||
                type == MarkdownElementTypes.SETEXT_1 ||
                type == MarkdownElementTypes.SETEXT_2 ||
                type == MarkdownElementTypes.ATX_1 ||
                type == MarkdownElementTypes.ATX_2 ||
                type == MarkdownElementTypes.ATX_3 ||
                type == MarkdownElementTypes.ATX_4 ||
                type == MarkdownElementTypes.ATX_5 ||
                type == MarkdownElementTypes.ATX_6 ||
                type == MarkdownElementTypes.BLOCK_QUOTE ||
                type == MarkdownElementTypes.ORDERED_LIST ||
                type == MarkdownElementTypes.UNORDERED_LIST ||
                type == MarkdownElementTypes.HTML_BLOCK
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
     * 当 TableAwareText 在流式期间只追加了文本到最后一个 Text 块时，
     * 需要重新检查是否有完整的 $$ 数学公式块可以拆分出来。
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
 *
 * 标记为 @Immutable 帮助 Compose 编译器优化重组：
 * - 当 ContentPart 实例未变化时，跳过使用它的 Composable 重组
 * - 参考 RikkaHub 的 referentialEqualityPolicy 策略
 */
@androidx.compose.runtime.Immutable
sealed class ContentPart {
    /**
     * 内容在原始文本中的起始位置，用于生成稳定的 Compose key
     * 流式模式下，同一位置的内容块 key 不变，避免 index 漂移导致重组
     */
    abstract val startOffset: Int
    
    data class Text(val content: String, override val startOffset: Int = 0) : ContentPart()
    data class Code(val language: String?, val content: String, override val startOffset: Int = 0) : ContentPart()
    data class Table(val lines: List<String>, override val startOffset: Int = 0) : ContentPart()
    data class Math(val content: String, override val startOffset: Int = 0) : ContentPart()

    /**
     * 获取内容的哈希值，用于 Compose key 优化
     * 相同内容的块会生成相同的哈希，避免不必要的重组
     */
    fun contentHash(): Int = when (this) {
        is Text -> content.hashCode()
        is Code -> (language.hashCode() * 31) + content.hashCode()
        is Table -> lines.hashCode()
        is Math -> content.hashCode()
    }

    /**
     * 获取内容的预览字符串，用于流式输出时的 key 生成
     * 返回内容的前缀，用于减少小增量变化导致的 key 频繁变化
     */
    fun contentPreview(): String = when (this) {
        is Text -> content
        is Code -> content
        is Table -> lines.joinToString("\n")
        is Math -> content
    }
}
