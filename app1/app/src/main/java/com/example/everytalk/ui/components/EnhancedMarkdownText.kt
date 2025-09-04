package com.example.everytalk.ui.components

import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.delay

@Composable
fun EnhancedMarkdownText(
    markdown: String,
    messageId: String? = null,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    isStreaming: Boolean = false,
    messageOutputType: String = "",
    inTableContext: Boolean = false
) {
    val systemDark = isSystemInDarkTheme()
    val textColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> if (systemDark) Color(0xFFFFFFFF) else Color(0xFF000000)
    }

    // 流式阶段：只切纯文本块；结束后再解析表格/代码/数学等重组件
    val parts: List<MarkdownPart> = remember(markdown, inTableContext, isStreaming) {
        if (isStreaming) {
            splitTextIntoBlocks(markdown).map { it as MarkdownPart }
        } else {
            parseMarkdownParts(markdown, inTableContext)
        }
    }

    // 串行淡入
    val stableKeyBase = remember(markdown, messageId) { messageId ?: markdown.hashCode().toString() }
    var revealedCount by rememberSaveable(stableKeyBase, isStreaming) { mutableStateOf(if (isStreaming) 0 else parts.size) }
    LaunchedEffect(parts.size, isStreaming, stableKeyBase) {
        if (!isStreaming) {
            revealedCount = parts.size
        } else {
            while (revealedCount < parts.size) {
                delay(33L)
                revealedCount += 1
            }
        }
    }

    Column(modifier = modifier.wrapContentWidth()) {
        parts.forEachIndexed { index, part ->
            val contentHash = when (part) {
                is MarkdownPart.Text -> part.content.hashCode()
                is MarkdownPart.CodeBlock -> (part.language + "|" + part.content).hashCode()
                is MarkdownPart.MathBlock -> (part.latex + "|" + part.isDisplay).hashCode()
                is MarkdownPart.InlineMath -> part.latex.hashCode()
                is MarkdownPart.HtmlContent -> part.html.hashCode()
                is MarkdownPart.Table -> part.tableData.hashCode()
            }
            key("${stableKeyBase}_${index}_$contentHash") {
                androidx.compose.animation.AnimatedVisibility(
                    visible = index < revealedCount,
                    enter = androidx.compose.animation.fadeIn(animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing)),
                    exit = ExitTransition.None
                ) {
                    when (part) {
                        is MarkdownPart.Text -> {
                            if (isStreaming) {
                                // 流式阶段：普通文本实时 Markdown 渲染（保留标题等），重组件仍延后
                                MarkdownText(
                                    markdown = normalizeHeadingSpacing(part.content),
                                    style = style.copy(color = textColor),
                                    modifier = Modifier.wrapContentWidth()
                                )
                            } else {
                                val hasMath = containsMath(part.content)
                                if (hasMath) {
                                    RichMathTextView(
                                        textWithLatex = part.content,
                                        textColor = textColor,
                                        textSize = style.fontSize,
                                        modifier = Modifier.wrapContentWidth(),
                                        delayMs = 0L,
                                        backgroundColor = MaterialTheme.colorScheme.surface
                                    )
                                } else {
                                    RenderTextWithInlineCode(
                                        text = part.content,
                                        style = style,
                                        textColor = textColor
                                    )
                                }
                            }
                        }
                        is MarkdownPart.CodeBlock -> {
                            if (!isStreaming) {
                                CodePreview(
                                    code = part.content.trimEnd('\n'),
                                    language = part.language.ifBlank { null },
                                    modifier = Modifier.wrapContentWidth(),
                                )
                            }
                        }
                        is MarkdownPart.MathBlock -> {
                            if (!isStreaming) {
                                MathView(
                                    latex = part.latex,
                                    isDisplay = part.isDisplay,
                                    textColor = textColor,
                                    modifier = Modifier.wrapContentWidth(),
                                    textSize = style.fontSize,
                                    delayMs = 0L
                                )
                            }
                        }
                        is MarkdownPart.InlineMath -> {
                            if (!isStreaming) {
                                MathView(
                                    latex = part.latex,
                                    isDisplay = false,
                                    textColor = textColor,
                                    modifier = Modifier.wrapContentWidth(),
                                    textSize = style.fontSize,
                                    delayMs = 0L
                                )
                            }
                        }
                        is MarkdownPart.HtmlContent -> {
                            if (!isStreaming) {
                                HtmlView(
                                    htmlContent = part.html,
                                    modifier = Modifier.wrapContentWidth()
                                )
                            }
                        }
                        is MarkdownPart.Table -> {
                            if (!isStreaming) {
                                ComposeTable(
                                    tableData = part.tableData,
                                    modifier = Modifier.wrapContentWidth(),
                                    delayMs = 0L
                                )
                            }
                        }
                    }
                }
            }
            if (index < parts.lastIndex) Spacer(Modifier.height(6.dp))
        }
    }
}

/**
 * 将文本分割为块，用于流式渲染的渐变效果（按空行拆段）
 */
private fun splitTextIntoBlocks(text: String): List<MarkdownPart.Text> {
    if (text.isBlank()) return listOf(MarkdownPart.Text(""))
    val paragraphs = text.split("\n\n").filter { it.isNotBlank() }
    return if (paragraphs.isEmpty()) listOf(MarkdownPart.Text(text)) else paragraphs.map { MarkdownPart.Text(it.trim()) }
}

@Composable
fun StableMarkdownText(
    markdown: String,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    MarkdownText(markdown = markdown, style = style, modifier = modifier)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RenderTextWithInlineCode(
    text: String,
    style: TextStyle,
    textColor: Color
) {
    val segments = remember(text) { splitInlineCodeSegments(text) }
    FlowRow(modifier = Modifier.wrapContentWidth()) {
        segments.forEach { seg ->
            if (seg.isCode) {
                InlineCodeChip(code = seg.text, baseStyle = style.copy(color = textColor))
            } else {
                MarkdownText(
                    markdown = normalizeHeadingSpacing(seg.text),
                    style = style.copy(color = textColor),
                    modifier = Modifier.wrapContentWidth()
                )
            }
        }
    }
}

@Composable
private fun InlineCodeChip(
    code: String,
    baseStyle: TextStyle
) {
    Text(
        text = code,
        style = baseStyle.copy(fontWeight = FontWeight.Medium),
        modifier = Modifier
            .padding(horizontal = 2.dp, vertical = 1.dp)
            .background(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

private data class InlineSegment(val text: String, val isCode: Boolean)

private fun splitInlineCodeSegments(text: String): List<InlineSegment> {
    if (text.isEmpty()) return listOf(InlineSegment("", false))
    val res = mutableListOf<InlineSegment>()
    val sb = StringBuilder()
    var inCode = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '`') {
            val escaped = i > 0 && text[i - 1] == '\\'
            if (!escaped) {
                if (sb.isNotEmpty()) {
                    res += InlineSegment(sb.toString(), inCode)
                    sb.clear()
                }
                inCode = !inCode
            } else {
                sb.append('`')
            }
        } else {
            sb.append(c)
        }
        i++
    }
    if (sb.isNotEmpty()) res += InlineSegment(sb.toString(), inCode)
    // 若以未闭合的反引号结束，则回退为普通文本
    if (res.isNotEmpty() && res.last().isCode) {
        val merged = buildString {
            res.forEach { seg ->
                if (seg.isCode) append('`')
                append(seg.text)
            }
        }
        return listOf(InlineSegment(merged, false))
    }
    return res
}

/**
 * 标题容错：
 * 1) 行内出现的 ##... -> 强制换行到行首
 * 2) 行首 #{1..6} 后若未跟空格则补空格（###标题 -> ### 标题）
 */
private fun normalizeHeadingSpacing(md: String): String {
    if (md.isEmpty()) return md
    var text = md
    // 将“行内标题”移到新的一行（避免被当作普通文本）
    val newlineBefore = Regex("(?m)([^\\n])\\s*(#{1,6})(?=\\S)")
    text = text.replace(newlineBefore, "$1\n$2")
    // 标题后补空格（行首 #... 与后续字符之间补空格）
    val spaceAfter = Regex("(?m)^(#{1,6})([^#\\s])")
    text = text.replace(spaceAfter, "$1 $2")
    return text
}

// 数据结构
sealed class MarkdownPart {
    data class Text(val content: String) : MarkdownPart()
    data class CodeBlock(val content: String, val language: String = "") : MarkdownPart()
    data class MathBlock(val latex: String, val isDisplay: Boolean = true) : MarkdownPart()
    data class InlineMath(val latex: String) : MarkdownPart()
    data class HtmlContent(val html: String) : MarkdownPart()
    data class Table(val tableData: TableData) : MarkdownPart()
}

// 主解析：先切代码块，再在非代码区域提取表格
private fun parseMarkdownParts(markdown: String, inTableContext: Boolean = false): List<MarkdownPart> {
    if (markdown.isBlank()) return listOf(MarkdownPart.Text(""))

    val codeRegex = "```\\s*([a-zA-Z0-9+#-]*)`?\\s*\\n?([\\s\\S]*?)\\n?```".toRegex()
    val result = mutableListOf<MarkdownPart>()

    var lastIndex = 0
    val matches = codeRegex.findAll(markdown).toList()
    if (matches.isEmpty()) {
        result += extractTablesAsParts(markdown, inTableContext)
        return result
    }

    matches.forEach { m ->
        if (m.range.first > lastIndex) {
            val before = markdown.substring(lastIndex, m.range.first)
            result += extractTablesAsParts(before, inTableContext)
        }
        val language = m.groups[1]?.value.orEmpty()
        val code = m.groups[2]?.value.orEmpty()
        val langLower = language.lowercase()

        when {
            // 误包裹为 ```markdown/md：解围栏递归解析
            langLower == "markdown" || langLower == "md" -> {
                result += parseMarkdownParts(code, inTableContext)
            }
            // 明确要求 Markdown 预览：保留为代码块
            langLower == "mdpreview" || langLower == "markdown_preview" -> {
                result += MarkdownPart.CodeBlock(code, "markdown")
            }
            // 空语言或 text 且像表格：解围栏为表格渲染
            langLower.isBlank() || langLower == "text" -> {
                val linesForCheck = code.trim().split("\n")
                val looksLikeTable = linesForCheck.size >= 2 &&
                    looksLikeTableHeader(linesForCheck[0]) &&
                    isAlignmentRow(linesForCheck[1])
                if (looksLikeTable) {
                    result += extractTablesAsParts(code, inTableContext)
                } else {
                    result += MarkdownPart.CodeBlock(code, language)
                }
            }
            else -> {
                result += MarkdownPart.CodeBlock(code, language)
            }
        }
        lastIndex = m.range.last + 1
    }
    if (lastIndex < markdown.length) {
        result += extractTablesAsParts(markdown.substring(lastIndex), inTableContext)
    }
    return result
}

// 将文本中的表格提取为 Table，其余保持为 Text（不在表格单元格上下文时才做块级表格）
private fun extractTablesAsParts(text: String, inTableContext: Boolean): List<MarkdownPart> {
    if (text.isBlank()) return emptyList()
    if (inTableContext) return listOf(MarkdownPart.Text(text))

    val lines = text.split("\n")
    val parts = mutableListOf<MarkdownPart>()
    val buffer = StringBuilder()

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val next = if (i + 1 < lines.size) lines[i + 1] else null
        val maybeStart = looksLikeTableHeader(line) && next?.let { isAlignmentRow(it) } == true
        if (maybeStart) {
            if (buffer.isNotEmpty()) {
                parts += MarkdownPart.Text(buffer.toString().trimEnd('\n'))
                buffer.clear()
            }
            // 处理可能出现在表头行前的说明性前缀（如“……：”），避免被当成第一列
            var headerLine = line
            val firstPipeIdx = line.indexOf('|')
            if (firstPipeIdx > 0) {
                val prefix = line.substring(0, firstPipeIdx)
                val prefixTrim = prefix.trim()
                val prefixLooksLikeIntro =
                    prefixTrim.endsWith(":") || prefixTrim.endsWith("：") ||
                    prefixTrim.endsWith("。") || prefixTrim.endsWith("！") || prefixTrim.endsWith("？") ||
                    prefixTrim.length >= 12
                if (prefixLooksLikeIntro) {
                    if (prefixTrim.isNotEmpty()) {
                        parts += MarkdownPart.Text(prefixTrim)
                    }
                    headerLine = line.substring(firstPipeIdx)
                }
            }
            val tableLines = mutableListOf<String>()
            tableLines += headerLine
            tableLines += next!!
            i += 2
            while (i < lines.size) {
                val row = lines[i]
                if (row.trim().isEmpty()) break
                if (!row.contains("|")) break
                tableLines += row
                i += 1
            }
            val tableMd = tableLines.joinToString("\n")
            val tableData = parseMarkdownTable(tableMd)
            if (tableData != null) {
                parts += MarkdownPart.Table(tableData)
            } else {
                buffer.append(tableMd).append('\n')
            }
            continue
        } else {
            buffer.append(line).append('\n')
            i += 1
        }
    }
    if (buffer.isNotEmpty()) {
        parts += MarkdownPart.Text(buffer.toString().trimEnd('\n'))
    }
    return parts
}

private fun looksLikeTableHeader(line: String): Boolean {
    val t = line.trim()
    if (!t.contains("|")) return false
    val cells = t.trim('|').split("|")
    return cells.size >= 2
}

private fun isAlignmentRow(line: String): Boolean {
    val t = line.trim()
    val cellRegex = ":?-{3,}:?".toRegex()
    if (!t.contains("|")) return false
    val cells = t.trim('|').split("|").map { it.trim() }
    if (cells.size < 2) return false
    return cells.all { it.matches(cellRegex) }
}

private fun containsMath(text: String): Boolean {
    if (text.contains("$$")) return true
    if (text.contains("\\(") && text.contains("\\)")) return true
    if (text.contains("\\[") && text.contains("\\]")) return true

    run {
        var i = 0
        var open = false
        while (i < text.length) {
            val c = text[i]
            if (c == '$') {
                val escaped = i > 0 && text[i - 1] == '\\'
                val isDouble = i + 1 < text.length && text[i + 1] == '$'
                if (!escaped && !isDouble) {
                    open = !open
                    if (!open) return true
                }
            }
            i++
        }
    }

    val commonCommands = listOf(
        "frac", "sqrt", "sum", "int", "lim", "prod", "binom",
        "left", "right", "overline", "underline", "hat", "bar", "vec",
        "mathbb", "mathrm", "mathbf", "operatorname", "text",
        "sin", "cos", "tan", "log", "ln",
        "alpha", "beta", "gamma", "delta", "epsilon", "theta",
        "lambda", "mu", "pi", "sigma", "phi", "omega"
    )
    val commandRegex = Regex("""\\(${commonCommands.joinToString("|")})\b""")
    if (commandRegex.containsMatchIn(text)) return true

    val envRegex = Regex("""\\(begin|end)\s*\{[a-zA-Z*]+\}""")
    if (envRegex.containsMatchIn(text)) return true

    if (text.contains('\\') && text.contains('{') && text.contains('}')) return true

    return false
}