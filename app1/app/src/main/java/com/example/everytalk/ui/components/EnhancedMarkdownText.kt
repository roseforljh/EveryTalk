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
    parts: List<MarkdownPart>,
    rawMarkdown: String,
    messageId: String? = null,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    isStreaming: Boolean = false,
    messageOutputType: String = "",
    inTableContext: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    inSelectionDialog: Boolean = false
) {
    val systemDark = isSystemInDarkTheme()
    val textColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> if (systemDark) Color(0xFFFFFFFF) else Color(0xFF000000)
    }

    // 串行淡入
    val stableKeyBase = remember(rawMarkdown, messageId) { messageId ?: rawMarkdown.hashCode().toString() }
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

    if (inSelectionDialog) {
        // 在选择对话框中，始终使用原生 Text 以保证可选
        Text(
            text = rawMarkdown,
            style = style,
            color = color,
            modifier = modifier
        )
    } else {
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
                // 流式时保持基于索引的稳定 key，避免内容变化触发视图重建导致闪白
                val itemKey = "${stableKeyBase}_${index}_${part::class.java.simpleName}"
                key(itemKey) {
                    val contentComposable: @Composable () -> Unit = {
                        when (part) {
                            is MarkdownPart.Text -> {
                                if (isStreaming) {
                                    val hasEmphasisNow = containsBoldOrItalic(part.content)
                                    if (hasEmphasisNow) {
                                        RichMathTextView(
                                            textWithLatex = part.content,
                                            textColor = textColor,
                                            textSize = style.fontSize,
                                            modifier = Modifier.wrapContentWidth(),
                                            delayMs = 0L,
                                            backgroundColor = MaterialTheme.colorScheme.surface,
                                            onLongPress = onLongPress
                                        )
                                    } else {
                                        MarkdownText(
                                            markdown = normalizeBasicMarkdown(part.content),
                                            style = style.copy(color = textColor),
                                            modifier = Modifier.wrapContentWidth()
                                        )
                                    }
                                } else {
                                    val hasMath = containsMath(part.content)
                                    val hasEmphasis = containsBoldOrItalic(part.content)
                                    if (hasMath || hasEmphasis) {
                                        RichMathTextView(
                                            textWithLatex = part.content,
                                            textColor = textColor,
                                            textSize = style.fontSize,
                                            modifier = Modifier.wrapContentWidth(),
                                            delayMs = 0L,
                                            backgroundColor = MaterialTheme.colorScheme.surface,
                                            onLongPress = onLongPress
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
                                CodePreview(
                                    code = part.content.trimEnd('\n'),
                                    language = part.language.ifBlank { null },
                                    modifier = Modifier.wrapContentWidth(),
                                )
                            }
                            is MarkdownPart.MathBlock -> {
                                MathView(
                                    latex = part.latex,
                                    isDisplay = part.isDisplay,
                                    textColor = textColor,
                                    modifier = Modifier.wrapContentWidth(),
                                    textSize = style.fontSize,
                                    delayMs = 0L,
                                    onLongPress = onLongPress
                                )
                            }
                            is MarkdownPart.InlineMath -> {
                                MathView(
                                    latex = part.latex,
                                    isDisplay = false,
                                    textColor = textColor,
                                    modifier = Modifier.wrapContentWidth(),
                                    textSize = style.fontSize,
                                    delayMs = 0L,
                                    onLongPress = onLongPress
                                )
                            }
                            is MarkdownPart.HtmlContent -> {
                                HtmlView(
                                    htmlContent = part.html,
                                    modifier = Modifier.wrapContentWidth()
                                )
                            }
                            is MarkdownPart.Table -> {
                                ComposeTable(
                                    tableData = part.tableData,
                                    modifier = Modifier.wrapContentWidth(),
                                    delayMs = 0L
                                )
                            }
                        }
                    }

                    if (isStreaming) {
                        contentComposable()
                    } else {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = index < revealedCount,
                            enter = androidx.compose.animation.fadeIn(animationSpec = tween(durationMillis = 180, easing = LinearOutSlowInEasing)),
                            exit = ExitTransition.None
                        ) {
                            contentComposable()
                        }
                    }
                }
                if (index < parts.lastIndex) Spacer(Modifier.height(6.dp))
            }
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
    // 在表格上下文中，解包反引号包裹的“扩展名”，并规范化全角星号，避免被当作代码突出显示
    val normalized = normalizeMarkdownGlyphs(unwrapFileExtensionsInBackticks(text))
    val segments = remember(normalized) { splitInlineCodeSegments(normalized) }
    FlowRow(modifier = Modifier.wrapContentWidth()) {
        segments.forEach { seg ->
            if (seg.isCode) {
                // 行内 code 与正文统一样式：无背景、不加粗、继承颜色
                Text(
                    text = seg.text,
                    style = style.copy(color = textColor, fontWeight = FontWeight.Normal)
                )
            } else {
                MarkdownText(
                    markdown = normalizeBasicMarkdown(seg.text),
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
    // 不再使用 Chip 风格，保持与正文一致（保留函数供兼容，实际不再被调用）
    Text(
        text = code,
        style = baseStyle.copy(fontWeight = FontWeight.Normal),
        modifier = Modifier
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
internal fun parseMarkdownParts(markdown: String, inTableContext: Boolean = false): List<MarkdownPart> {
    if (markdown.isBlank()) return listOf(MarkdownPart.Text(""))

    val codeRegex = "```\\s*([a-zA-Z0-9_+#\\-]*)`?\\s*\\r?\\n?([\\s\\S]*?)\\r?\\n?```".toRegex()
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
        val rawLine = lines[i]
        val next = if (i + 1 < lines.size) lines[i + 1] else null

        // 预处理：尝试剥离表头行前的说明性前缀或列表标记（如 “- ”、“* ”、“1. ”、“说明：” 等）
        var headerLine = rawLine
        var leadingIntroText: String? = null
        run {
            val firstPipeIdx = rawLine.indexOf('|')
            if (firstPipeIdx > 0) {
                val prefix = rawLine.substring(0, firstPipeIdx)
                val prefixTrim = prefix.trim()
                val isListMarker = prefixTrim.matches(Regex("[-*+]\\s+.*")) ||
                    prefixTrim.matches(Regex("\\d+[.)]\\s+.*"))
                val looksIntro = prefixTrim.endsWith(":") || prefixTrim.endsWith("：") ||
                    prefixTrim.endsWith("。") || prefixTrim.endsWith("！") || prefixTrim.endsWith("？") ||
                    prefixTrim.length >= 12 || isListMarker
                if (looksIntro) {
                    leadingIntroText = prefixTrim
                    headerLine = rawLine.substring(firstPipeIdx)
                }
            }
        }

        val hasAlignmentNext = next?.let { isAlignmentRow(it) } == true
        val headerLooksLike = looksLikeTableHeader(headerLine)

        // 头部列数
        val colCountHeader = splitMarkdownTableRow(headerLine).size

        // 情况A：标准表格（第二行是对齐分隔行）
        val isStandardTableStart = headerLooksLike && hasAlignmentNext

        // 情况B：宽松表格（缺失对齐分隔行，但下一行看起来就是数据行，且列数一致）
        val isImplicitTableStart = headerLooksLike && !hasAlignmentNext && next != null &&
            next.contains('|') && colCountHeader >= 2 &&
            colCountHeader == splitMarkdownTableRow(next).size

        // 情况C：对齐分隔行与首条数据行被误写在同一行，如：
        // "| :--- | :--- | :--- || cell1 | cell2 | cell3 |"
        val combinedPair = if (headerLooksLike && !hasAlignmentNext && next != null) {
            splitCombinedAlignmentAndFirstRow(next, colCountHeader)
        } else null
        val isCombinedAlignmentAndFirstRow = combinedPair != null

        if (isStandardTableStart || isImplicitTableStart || isCombinedAlignmentAndFirstRow) {
            // 先把缓冲的普通文本刷出
            if (buffer.isNotEmpty()) {
                parts += MarkdownPart.Text(buffer.toString().trimEnd('\n'))
                buffer.clear()
            }
            // 如有说明性前缀，单独作为文本输出（避免被当作第一列）
            if (!leadingIntroText.isNullOrBlank()) {
                parts += MarkdownPart.Text(leadingIntroText!!.trim())
            }

            val tableLines = mutableListOf<String>()
            tableLines += headerLine
            var j = i + 1

            when {
                isCombinedAlignmentAndFirstRow -> {
                    val (alignmentRow, firstDataRow) = combinedPair!!
                    tableLines += alignmentRow
                    tableLines += firstDataRow
                    j = i + 2
                }
                isImplicitTableStart -> {
                    // 自动补一行对齐分隔行
                    val alignmentRow = buildString {
                        append("| ")
                        append(List(colCountHeader) { "---" }.joinToString(" | "))
                        append(" |")
                    }
                    tableLines += alignmentRow
                    // 把 next 作为第一行数据
                    tableLines += next!!
                    j = i + 2
                }
                else -> {
                    // 标准表格：第二行已是分隔行
                    tableLines += next!!
                    j = i + 2
                }
            }

            // 收集后续数据行（直到空行或不再包含竖线）
            while (j < lines.size) {
                val row = lines[j]
                if (row.trim().isEmpty()) break
                if (!row.contains("|")) break
                tableLines += row
                j += 1
            }

            val tableMd = tableLines.joinToString("\n")
            val tableData = parseMarkdownTable(tableMd)
            if (tableData != null) {
                parts += MarkdownPart.Table(tableData)
                i = j
                continue
            } else {
                // 解析失败则退回为普通文本
                buffer.append(tableMd).append('\n')
                i = j
                continue
            }
        }

        // 非表格起始，累积到缓冲
        buffer.append(rawLine).append('\n')
        i += 1
    }
    if (buffer.isNotEmpty()) {
        parts += MarkdownPart.Text(buffer.toString().trimEnd('\n'))
    }
    return parts
}

private fun looksLikeTableHeader(line: String): Boolean {
    val t = line.replace('｜','|').replace('│','|').trim()
    if (!t.contains("|")) return false
    val cells = t.trim('|').split("|")
    return cells.size >= 2
}

private fun isAlignmentRow(line: String): Boolean {
    val t = line.replace('｜','|').replace('│','|').replace('—','-').replace('－','-').replace('：', ':').trim()
    if (!t.contains("|")) return false
    val cells = t.trim('|').split("|").map { it.trim() }
    if (cells.size < 2) return false
    val cellRegex = Regex("[:：]?[-—－]{3,}[:：]?")
    return cells.all { it.matches(cellRegex) }
}

/**
 * 处理把对齐行与首条数据行写在同一行的情况：
 * 形如："| :--- | :--- | :--- || cell1 | cell2 | cell3 |"
 * 返回 Pair(标准化的对齐行, 标准化的首条数据行)；否则返回 null
 */
private fun splitCombinedAlignmentAndFirstRow(line: String, expectedCols: Int): Pair<String, String>? {
    val normalized = line
        .replace('｜','|')
        .replace('│','|')
        .replace('：', ':')
        .replace('—','-')
        .replace('－','-')
        .trim()

    val cellPat = "[:：]?[-—－]{3,}[:：]?"
    val regexStr = "^\\|?\\s*((?:$cellPat\\s*\\|\\s*){${expectedCols - 1}}$cellPat)\\s*\\|\\|\\s*(.*)$"
    val regex = Regex(regexStr)
    val m = regex.find(normalized) ?: return null

    val alignPart = m.groupValues[1].trim()
    val rowPartRaw = m.groupValues[2].trim()

    // 规范化对齐行
    val alignLineWithBars = if (alignPart.startsWith("|")) alignPart else "| $alignPart |"
    val cells = splitMarkdownTableRow(alignLineWithBars)
    if (cells.size != expectedCols) return null
    val alignmentRow = "| " + cells.joinToString(" | ") + " |"

    // 规范化首条数据行
    val firstRow = if (rowPartRaw.startsWith("|")) rowPartRaw else "| $rowPartRaw |"

    return alignmentRow to firstRow
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

/**
 * 检测是否包含强调标记（加粗/斜体），用于决定是否走 HTML 渲染以保证效果一致
 */
private fun containsBoldOrItalic(text: String): Boolean {
    if (text.isEmpty()) return false
    // 加粗：**text** 或 ＊＊text＊＊ 或 __text__
    if (text.contains("**") || text.contains("＊＊")) return true
    if (text.contains("__") && Regex("""__[^_
]+__""").containsMatchIn(text)) return true
    // 斜体：*text* / ＊text＊ / _text_
    if (Regex("""(^|[^*＊])[\*＊]([^*＊
]+)[\*＊](?![*＊])""").containsMatchIn(text)) return true
    if (Regex("""(^|[^_])_([^_
]+)_(${'$'}|[^_])""").containsMatchIn(text)) return true
    return false
}

/**
 * 仅在表格相关语境中使用：将 `.<ext>` 这种纯扩展名从反引号解包为普通文本，
 * 例如 `\.rtf`、`\.docx`、`\.txt`、`\.html` 等，避免被识别为代码。
 * 规则谨慎：仅匹配以点开头、后接 2-10 位字母数字的片段；不影响其他代码片段。
 */
private fun unwrapFileExtensionsInBackticks(text: String): String {
    val regex = Regex("`\\.(?:[a-zA-Z0-9]{2,10})`")
    if (!regex.containsMatchIn(text)) return text
    return text.replace(regex) { mr -> mr.value.removePrefix("`").removeSuffix("`") }
}
