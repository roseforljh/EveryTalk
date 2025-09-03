package com.example.everytalk.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.delay

/**
 * 增强版 Markdown 渲染器，支持轻渲染模式和渐变淡入效果
 * - 流式阶段：完全跳过 KaTeX/表格/HTML/CodePreview 的初始化，使用轻量级文本渲染
 * - 结束后：一次性渲染重组件，进一步降抖
 * - 渐变效果：每块文本段输出时淡入，淡入时间刚好为下个块输出的时间
 */
@Composable
fun EnhancedMarkdownText(
    markdown: String,
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

    // 轻渲染模式：流式阶段只解析文本，结束后才解析重组件
    val parts = remember(markdown, inTableContext, isStreaming) {
        if (isStreaming) {
            // 流式阶段：只返回纯文本，跳过重组件解析
            splitTextIntoBlocks(markdown)
        } else {
            // 非流式或流式结束：完整解析
            parseMarkdownParts(markdown, inTableContext)
        }
    }

    Column(modifier = modifier.wrapContentWidth()) {
        parts.forEachIndexed { index, part ->
            // 渐变淡入效果
            FadeInTextBlock(
                index = index,
                isStreaming = isStreaming,
                fadeInDuration = 300L // 淡入时间
            ) {
                when (part) {
                    is MarkdownPart.Text -> {
                        if (isStreaming) {
                            // 流式阶段：使用轻量级文本渲染
                            LightweightTextRenderer(
                                text = part.content,
                                style = style,
                                textColor = textColor
                            )
                        } else {
                            // 非流式：完整渲染
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
                        // 流式阶段不渲染重组件
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
            if (index < parts.lastIndex) Spacer(Modifier.height(6.dp))
        }
    }
}

/**
 * 渐变淡入文本块组件
 */
@Composable
private fun FadeInTextBlock(
    index: Int,
    isStreaming: Boolean,
    fadeInDuration: Long,
    content: @Composable () -> Unit
) {
    val alpha = remember { Animatable(if (isStreaming) 0f else 1f) }
    
    LaunchedEffect(index, isStreaming) {
        if (isStreaming) {
            // 根据索引延迟淡入，模拟逐块输出
            delay(index * fadeInDuration)
            alpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = fadeInDuration.toInt())
            )
        } else {
            // 非流式直接显示
            alpha.snapTo(1f)
        }
    }
    
    Column(
        modifier = Modifier.alpha(alpha.value)
    ) {
        content()
    }
}

/**
 * 轻量级文本渲染器 - 流式阶段使用
 */
@Composable
private fun LightweightTextRenderer(
    text: String,
    style: TextStyle,
    textColor: Color
) {
    // 流式阶段使用最简单的文本渲染，避免复杂解析
    Text(
        text = text,
        style = style.copy(color = textColor),
        modifier = Modifier.wrapContentWidth()
    )
}

/**
 * 将文本分割为块，用于流式渲染的渐变效果
 */
private fun splitTextIntoBlocks(text: String): List<MarkdownPart.Text> {
    if (text.isBlank()) return listOf(MarkdownPart.Text(""))
    
    // 按段落分割，每个段落作为一个渐变块
    val paragraphs = text.split("\n\n").filter { it.isNotBlank() }
    return if (paragraphs.isEmpty()) {
        listOf(MarkdownPart.Text(text))
    } else {
        paragraphs.map { MarkdownPart.Text(it.trim()) }
    }
}

@Composable
fun StableMarkdownText(
    markdown: String,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    MarkdownText(
        markdown = markdown,
        style = style,
        modifier = modifier
    )
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
                InlineCodeChip(
                    code = seg.text,
                    baseStyle = style.copy(color = textColor)
                )
            } else {
                MarkdownText(
                    markdown = seg.text,
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
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(6.dp)
            )
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
    // 若以未闭合的反引号结束，则回退为普通文本，避免半截被当作代码
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

// 数据结构
sealed class MarkdownPart {
    data class Text(val content: String) : MarkdownPart()
    data class CodeBlock(val content: String, val language: String = "") : MarkdownPart()
    data class MathBlock(val latex: String, val isDisplay: Boolean = true) : MarkdownPart()
    data class InlineMath(val latex: String) : MarkdownPart()
    data class HtmlContent(val html: String) : MarkdownPart()
    data class Table(val tableData: TableData) : MarkdownPart()
}

// 主解析：按顺序切分 代码块 -> 表格 -> 其它文本
private fun parseMarkdownParts(markdown: String, inTableContext: Boolean = false): List<MarkdownPart> {
    if (markdown.isBlank()) return listOf(MarkdownPart.Text(""))

    // 1) 先切分代码块，确保内部内容不被后续规则误伤
    val codeRegex = "```\\s*([a-zA-Z0-9+#-]*)`?\\s*\\n?([\\s\\S]*?)\\n?```".toRegex()
    val result = mutableListOf<MarkdownPart>()

    var lastIndex = 0
    val matches = codeRegex.findAll(markdown).toList()
    if (matches.isEmpty()) {
        // 无代码块，直接处理表格/文本
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
        result += MarkdownPart.CodeBlock(code, language)
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
            // flush buffer
            if (buffer.isNotEmpty()) {
                parts += MarkdownPart.Text(buffer.toString().trimEnd('\n'))
                buffer.clear()
            }
            // collect table block
            val tableLines = mutableListOf<String>()
            tableLines += line
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
                // 回退为纯文本
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
    // 至少两列
    val cells = t.trim('|').split("|")
    return cells.size >= 2
}

private fun isAlignmentRow(line: String): Boolean {
    val t = line.trim()
    // e.g. | :--- | ---: | :---: |
    val cellRegex = ":?-{3,}:?".toRegex()
    if (!t.contains("|")) return false
    val cells = t.trim('|').split("|").map { it.trim() }
    if (cells.size < 2) return false
    return cells.all { it.matches(cellRegex) }
}

private fun containsMath(text: String): Boolean {
    // 1) 显式分隔符优先
    if (text.contains("$$")) return true
    if (text.contains("\\(") && text.contains("\\)")) return true
    if (text.contains("\\[") && text.contains("\\]")) return true

    // 成对的单个 $（排除 $$ 与转义 \$）
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

    // 2) 常见 LaTeX 命令/环境（在不含分隔符时兜底检测）
    val commonCommands = listOf(
        "frac", "sqrt", "sum", "int", "lim", "prod", "binom",
        "left", "right", "overline", "underline", "hat", "bar", "vec",
        "mathbb", "mathrm", "mathbf", "operatorname", "text",
        // 常见函数与希腊字母，避免过多误判：仅当以反斜杠开头
        "sin", "cos", "tan", "log", "ln",
        "alpha", "beta", "gamma", "delta", "epsilon", "theta",
        "lambda", "mu", "pi", "sigma", "phi", "omega"
    )
    val commandRegex = Regex("\\\\(" + commonCommands.joinToString("|") + ")\\b")
    if (commandRegex.containsMatchIn(text)) return true

    // \\begin{...} / \\end{...}
    val envRegex = Regex("\\\\(begin|end)\\s*\\{[a-zA-Z*]+\\}")
    if (envRegex.containsMatchIn(text)) return true

    // 若同时存在反斜杠与花括号，也大概率是 LaTeX 片段
    if (text.contains('\\') && text.contains('{') && text.contains('}')) return true

    return false
}