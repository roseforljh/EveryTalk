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
import androidx.compose.runtime.DisposableEffect
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
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.ui.theme.chatColors
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.delay
import com.example.everytalk.util.messageprocessor.parseMarkdownParts
import kotlinx.serialization.Serializable
import kotlinx.serialization.Contextual
import java.util.UUID

@Composable
fun EnhancedMarkdownText(
    message: Message,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    isStreaming: Boolean = false,
    messageOutputType: String = "",
    inTableContext: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    inSelectionDialog: Boolean = false
) {
    val startTime = remember { System.currentTimeMillis() }
    val systemDark = isSystemInDarkTheme()
    val textColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> if (systemDark) Color(0xFFFFFFFF) else Color(0xFF000000)
    }

    // 🎯 关键修复：强制UI更新，解决流式传输时显示不完整的问题
    val partsSignature = remember(message.parts) { message.parts.joinToString("|") { it.id } }
    
    // 🎯 修复：使用remember而不是rememberSaveable，确保流式更新时能立即触发重组
    val effectiveText = remember(message.id, message.text, isStreaming, partsSignature) {
        // 🎯 修复：优先使用 parts 重建文本，确保UI实时更新
        if (message.parts.isNotEmpty()) {
            try {
                // 🎯 关键修复：使用换行分隔符连接parts，保持Markdown格式
                message.parts.joinToString("\n") { part ->
                    when (part) {
                        is MarkdownPart.Text -> part.content
                        is MarkdownPart.CodeBlock -> "```" + part.language + "\n" + part.content + "\n```"
                        is MarkdownPart.MathBlock -> if (part.isDisplay) "$$" + part.latex + "$$" else "$" + part.latex + "$"
                        is MarkdownPart.Table -> buildTableMarkdown(part.tableData)
                        // 忽略其他类型的 part，因为它们不直接贡献文本内容
                        else -> ""
                    }
                }.trim()
            } catch (e: Exception) {
                // 如果重建失败，回退到使用原始文本
                message.text
            }
        } else {
            // 如果 parts 为空，直接使用原始文本
            message.text
        }
    }
    
    // 🎯 新增：强制UI重组的LaunchedEffect
    LaunchedEffect(message.text, isStreaming) {
        if (isStreaming) {
            // 在流式传输时，确保UI能够及时更新
            android.util.Log.v("EnhancedMarkdownText", "强制重组更新：消息${message.id}，文本长度=${message.text.length}")
        }
    }

    // 渲染监控（基于 effectiveText）
    LaunchedEffect(effectiveText) {
        val (isValid, issues) = RenderingMonitor.validateMarkdownOutput(effectiveText)
        if (!isValid) {
            RenderingMonitor.logRenderingIssue(
                messageId = message.id,
                issue = "Markdown格式问题: ${issues.joinToString(", ")}",
                content = effectiveText
            )
        }
    }
    
    DisposableEffect(message.id) {
        onDispose {
            RenderingMonitor.trackRenderingPerformance(message.id, startTime)
        }
    }

    if (inSelectionDialog) {
        // 在选择对话框中，始终使用原生 Text 以保证可选
        Text(
            text = effectiveText,
            style = style,
            color = color,
            modifier = modifier
        )
    } else {
        key(message.id) {
            // 🎯 修复：使用remember确保流式更新时能立即触发重组
            val normalizedForSimple = remember(message.id, effectiveText) {
                normalizeHeadingsForSimplePath(effectiveText)
            }
            val contentType = remember(message.id, effectiveText) { 
                detectContentType(effectiveText) 
            }
            
            LaunchedEffect(contentType) {
                val reason = when (contentType) {
                    ContentType.MATH_HEAVY -> "检测到数学公式内容"
                    ContentType.SIMPLE -> "简单文本内容"
                }
                RenderingMonitor.logContentTypeDecision(message.id, contentType, reason)
            }
            
            when (contentType) {
                ContentType.MATH_HEAVY -> {
                    // 🎯 使用新的Compose数学渲染器，不依赖WebView
                    ComposeMathRenderer(
                        text = effectiveText,
                        style = style,
                        color = textColor,
                        modifier = modifier
                    )
                }
                ContentType.SIMPLE -> {
                    // 🎯 普通内容使用MarkdownText渲染，也会处理其中的数学公式
                    ComposeMathRenderer(
                        text = normalizeBasicMarkdown(normalizedForSimple),
                        style = style,
                        color = textColor,
                        modifier = modifier
                    )
                }
            }
        }
    }
}

// 新增：表格Markdown构建，供 parts→文本 重建使用
private fun buildTableMarkdown(tableData: TableData): String {
    if (tableData.headers.isEmpty()) return ""
    val result = StringBuilder()
    result.append("| ${tableData.headers.joinToString(" | ")} |\n")
    result.append("| ${tableData.headers.joinToString(" | ") { "---" }} |\n")
    tableData.rows.forEach { row ->
        result.append("| ${row.joinToString(" | ")} |\n")
    }
    return result.toString()
}

/**
 * 将文本分割为块，用于流式渲染的渐变效果（按空行拆段）
 */
private fun splitTextIntoBlocks(text: String): List<MarkdownPart.Text> {
    if (text.isBlank()) return listOf(MarkdownPart.Text(id = "text_${UUID.randomUUID()}", content = ""))
    val paragraphs = text.split("\n\n").filter { it.isNotBlank() }
    return if (paragraphs.isEmpty()) {
        listOf(MarkdownPart.Text(id = "text_${UUID.randomUUID()}", content = text))
    } else {
        paragraphs.map { MarkdownPart.Text(id = "text_${UUID.randomUUID()}", content = it.trim()) }
    }
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
    // 在表格上下文中，解包反引号包裹的"扩展名"，并规范化全角星号，避免被当作代码突出显示
    val normalized = normalizeMarkdownGlyphs(unwrapFileExtensionsInBackticks(text))
    val segments = remember(normalized) { splitInlineCodeSegments(normalized) }
    FlowRow(modifier = Modifier.wrapContentWidth()) {
        segments.forEach { seg ->
            if (seg.isCode) {
                // 🎯 使用自定义的适配白天/黑天模式的内联代码样式
                Text(
                    text = seg.text,
                    style = style.copy(
                        color = textColor, 
                        fontWeight = FontWeight.Normal,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = style.fontSize * 0.9f // 稍微小一点的字体
                    ),
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.chatColors.codeBlockBackground,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp)
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
// Moved to MarkdownPart.kt to decouple from UI rendering logic and avoid compile-time cascading errors.
// @Serializable sealed class MarkdownPart { ... }

/**
 * 🎯 内容类型分类
 */
enum class ContentType {
    MATH_HEAVY,    // 数学公式密集，需要特殊处理
    SIMPLE         // 普通文本内容，使用MarkdownText渲染
}

/**
 * 🎯 数学公式检测 - 使用Compose渲染器，不依赖WebView
 */
private fun detectContentType(text: String): ContentType {
    if (text.isBlank()) return ContentType.SIMPLE

    // 检测数学公式内容，使用Compose渲染器处理
    if (hasMathContent(text)) return ContentType.MATH_HEAVY

    // 其他内容也使用ComposeMathRenderer，确保数学公式能被处理
    return ContentType.SIMPLE
}

/**
 * 检测数学公式内容
 */
private fun hasMathContent(text: String): Boolean {
    return text.contains("$$") || // LaTeX块级公式
            text.contains("$") && text.count { it == '$' } >= 2 || // LaTeX行内公式
            text.contains("\\begin{") || // LaTeX环境
            text.contains("\\frac") || // 分数
            text.contains("\\sum") || // 求和
            text.contains("\\int") || // 积分
            text.contains("\\sqrt") || // 根号
            text.contains("\\alpha") || // 希腊字母
            text.contains("\\beta") ||
            text.contains("\\gamma") ||
            text.contains("\\delta") ||
            text.contains("\\pi") ||
            text.contains("\\theta") ||
            text.contains("\\lambda") ||
            // 🎯 新增缺失的重要符号检测
            text.contains("\\infty") || // 无穷大
            text.contains("\\dots") || // 省略号
            text.contains("\\ldots") ||
            text.contains("\\cdots") ||
            text.contains("\\left") || // 括号
            text.contains("\\right") ||
            text.contains("\\cdot") || // 点乘
            text.contains("\\times") || // 乘法
            text.contains("\\sin") || // 三角函数
            text.contains("\\cos") ||
            text.contains("\\tan") ||
            text.contains("\\ln") || // 对数
            text.contains("\\log") ||
            text.contains("\\lim") || // 极限
            text.contains("\\omega") ||
            text.contains("\\sigma") ||
            text.contains("\\mu") ||
            text.contains("\\nu")
}


// Parsing logic is now in util.messageprocessor.MarkdownParser.kt


/**
 * 检测是否包含强调标记（加粗/斜体），用于决定是否走 HTML 渲染以保证效果一致
 */
private fun containsBoldOrItalic(text: String): Boolean {
    if (text.isEmpty()) return false
    // 加粗：**text** 或 ＊＊text＊＊ 或 __text__
    if (text.contains("**") || text.contains("＊＊")) return true
    if (text.contains("__") && Regex("""__[^_\n]+__""").containsMatchIn(text)) return true
    // 斜体：*text* / ＊text＊ / _text_
    if (Regex("""(^|[^*＊])[\*＊]([^*＊\n]+)[\*＊](?![*＊])""").containsMatchIn(text)) return true
    if (Regex("""(^|[^_])_([^_ \n]+)_($|[^_])""").containsMatchIn(text)) return true
    return false
}

/**
 * 仅在表格相关语境中使用：将 `.<ext>` 这种纯扩展名从反引号解包为普通文本，
 * 例如 `.rtf`、`.docx`、`.txt`、`.html` 等，避免被识别为代码。
 * 规则谨慎：仅匹配以点开头、后接 2-10 位字母数字的片段；不影响其他代码片段。
 */
private fun unwrapFileExtensionsInBackticks(text: String): String {
    val regex = Regex("`\\.(?:[a-zA-Z0-9]{2,10})`")
    if (!regex.containsMatchIn(text)) return text
    return text.replace(regex) { mr -> mr.value.removePrefix("`").removeSuffix("`") }
}

/**
 * SIMPLE 路径保底：修正中文环境常见的标题无空格、全角＃等问题
 */
private fun normalizeHeadingsForSimplePath(text: String): String {
    if (text.isBlank()) return text
    val lines = text.lines().map { line ->
        var l = line
        // 全角＃转半角#
        if (l.startsWith("＃")) {
            val count = l.takeWhile { it == '＃' }.length
            l = "#".repeat(count) + l.drop(count)
        }
        // 行首 # 后补空格
        l = l.replace(Regex("^(\\s*#{1,6})([^#\\s])")) { mr ->
            "${mr.groups[1]!!.value} ${mr.groups[2]!!.value}"
        }
        l
    }
    return lines.joinToString("\n")
}

object RenderingMonitor {
    private const val TAG = "MarkdownRendering"
    
    fun logRenderingIssue(messageId: String, issue: String, content: String) {
        android.util.Log.w(TAG, "消息$messageId 渲染问题: $issue")
        android.util.Log.v(TAG, "问题内容摘要: ${content.take(100)}...")
    }
    
    fun trackRenderingPerformance(messageId: String, startTime: Long) {
        val duration = System.currentTimeMillis() - startTime
        if (duration > 1000) { // 渲染超过1秒
            android.util.Log.w(TAG, "消息$messageId 渲染耗时: ${duration}ms")
        } else {
            android.util.Log.v(TAG, "消息$messageId 渲染完成: ${duration}ms")
        }
    }
    
    fun logContentTypeDecision(messageId: String, contentType: ContentType, reason: String) {
        android.util.Log.d(TAG, "消息$messageId 内容类型: $contentType, 原因: $reason")
    }
    
    fun validateMarkdownOutput(content: String): Pair<Boolean, List<String>> {
        val issues = mutableListOf<String>()

        // 统计围栏代码
        val fenceCount = Regex("```").findAll(content).count()
        if (fenceCount % 2 != 0) {
            issues.add("未闭合的代码块")
        }

        // 统计 $$ 块级数学
        val blockMathCount = Regex("\\$\\$").findAll(content).count()
        if (blockMathCount % 2 != 0) {
            issues.add("未闭合的数学公式")
        }

        // 表格分隔线检查
        val tableLines = content.lines().map { it.trim() }.filter { it.isNotEmpty() && it.contains("|") }
        if (tableLines.isNotEmpty()) {
            val separatorRegex = Regex("^\\|?\\s*:?[-]{3,}:?\\s*(\\|\\s*:?[-]{3,}:?\\s*)+\\|?$")
            val hasSeparator = tableLines.any { separatorRegex.containsMatchIn(it) }
            if (!hasSeparator) {
                issues.add("表格缺少分隔行")
            }
        }

        return issues.isEmpty() to issues
    }
}