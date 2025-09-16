package com.example.everytalk.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.ui.theme.chatColors
import dev.jeziellago.compose.markdowntext.MarkdownText
import com.example.everytalk.util.messageprocessor.parseMarkdownParts
import kotlinx.serialization.Serializable
import kotlinx.serialization.Contextual
import java.util.UUID
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.TextView
import ru.noties.jlatexmath.JLatexMathView

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

    DisposableEffect(message.id) {
        onDispose {
            RenderingMonitor.trackRenderingPerformance(message.id, startTime)
        }
    }

    Column(modifier = modifier) {
        if (message.parts.isEmpty()) {
            // 使用 MarkdownText 进行正常的 Markdown 渲染，但跳过数学公式处理
            MarkdownText(
                markdown = normalizeBasicMarkdown(message.text),
                style = style.copy(color = textColor),
                modifier = Modifier
            )
        } else {
            // 检查 parts 的有效性
            val hasValidParts = message.parts.any { part ->
                when (part) {
                    is MarkdownPart.Text -> part.content.isNotBlank()
                    is MarkdownPart.CodeBlock -> part.content.isNotBlank()
                    is MarkdownPart.MathBlock -> part.latex.isNotBlank()
                    else -> true
                }
            }
            
            if (!hasValidParts && message.text.isNotBlank()) {
                // 回退到原始文本渲染，但使用 MarkdownText
                RenderingMonitor.logRenderingIssue(message.id, "Parts无效，回退到原始文本", message.text)
                MarkdownText(
                    markdown = normalizeBasicMarkdown(message.text),
                    style = style.copy(color = textColor),
                    modifier = Modifier
                )
            } else {
                // 使用有效的 parts 进行渲染
                message.parts.forEach { part ->
                    when (part) {
                        is MarkdownPart.Text -> {
                            if (part.content.isNotBlank()) {
                                MarkdownText(
                                    markdown = normalizeBasicMarkdown(part.content),
                                    style = style.copy(color = textColor),
                                    modifier = Modifier
                                )
                            }
                        }
                        is MarkdownPart.CodeBlock -> {
                            CodePreview(
                                code = part.content,
                                language = part.language
                            )
                        }
                        is MarkdownPart.MathBlock -> {
                            LatexMath(
                                latex = part.latex,
                                inline = !part.displayMode,
                                color = textColor,
                                style = style,
                                modifier = if (part.displayMode) Modifier.fillMaxWidth().padding(vertical = 6.dp) else Modifier.wrapContentWidth()
                            )
                        }
                        else -> {
                            // Handle other types or do nothing
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LatexMath(
    latex: String,
    inline: Boolean,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    android.util.Log.d("LatexMath", "🎯 开始渲染LaTeX: '$latex' (inline=$inline)")
    
    // 优先使用离线MathJax渲染器，更稳定且无网络依赖
    AndroidView(
        factory = { context ->
            android.util.Log.d("LatexMath", "🎯 创建离线WebView LaTeX渲染器")
            try {
                val webView = android.webkit.WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = false
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                }
                
                val cleanLatex = latex.trim()
                val fontSize = style.fontSize.value * if (inline) 0.9f else 1.1f
                // 修复颜色格式：确保正确处理白天/黑夜模式的颜色
                val colorHex = String.format("#%06X", 0xFFFFFF and color.toArgb())
                val mathType = if (inline) "math-inline" else "math-display"
                
                android.util.Log.d("LatexMath", "🎨 颜色信息: color=${color}, colorHex=$colorHex, fontSize=${fontSize}px")
                
                // 加载离线HTML模板并替换内容
                val offlineHtml = try {
                    context.assets.open("mathjax_offline.html").bufferedReader().use { it.readText() }
                        .replace("MATH_CONTENT", cleanLatex)
                        .replace("MATH_TYPE", mathType)
                        .replace("FONT_SIZE", "${fontSize}px")
                        .replace("color: inherit;", "color: $colorHex;")
                } catch (e: Exception) {
                    android.util.Log.e("LatexMath", "❌ 读取离线HTML模板失败: ${e.message}")
                    // 如果读取失败，使用内置的简化版本
                    """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta charset="UTF-8">
                        <style>
                            body { 
                                margin: 0; 
                                padding: 4px; 
                                background: transparent; 
                                font-family: 'Times New Roman', serif;
                                font-size: ${fontSize}px;
                                color: $colorHex;
                            }
                            .math-inline { display: inline-block; vertical-align: middle; }
                            .math-display { display: block; text-align: center; margin: 8px 0; }
                            .superscript { vertical-align: super; font-size: 0.8em; }
                            .subscript { vertical-align: sub; font-size: 0.8em; }
                            .fraction { display: inline-block; vertical-align: middle; }
                            .numerator { display: block; text-align: center; border-bottom: 1px solid; }
                            .denominator { display: block; text-align: center; }
                        </style>
                    </head>
                    <body>
                        <div id="math-content" class="$mathType">$cleanLatex</div>
                        <script>
                            function renderBasicLatex(latex) {
                                let result = latex;
                                result = result.replace(/\^{([^}]*)}/g, '<span class="superscript">$1</span>');
                                result = result.replace(/\^([a-zA-Z0-9])/g, '<span class="superscript">$1</span>');
                                result = result.replace(/_{([^}]*)}/g, '<span class="subscript">$1</span>');
                                result = result.replace(/_([a-zA-Z0-9])/g, '<span class="subscript">$1</span>');
                                result = result.replace(/\\\\frac{([^}]*)}{([^}]*)}/g, 
                                    '<span class="fraction"><span class="numerator">$1</span><span class="denominator">$2</span></span>');
                                result = result.replace(/\\\\sqrt{([^}]*)}/g, '<span>√$1</span>');
                                result = result.replace(/\\\\pi/g, 'π');
                                result = result.replace(/\\\\theta/g, 'θ');
                                result = result.replace(/\\\\alpha/g, 'α');
                                result = result.replace(/\\\\beta/g, 'β');
                                result = result.replace(/\\\\gamma/g, 'γ');
                                result = result.replace(/\\\\delta/g, 'δ');
                                result = result.replace(/\\\\lambda/g, 'λ');
                                result = result.replace(/\\\\mu/g, 'μ');
                                result = result.replace(/\\\\sigma/g, 'σ');
                                result = result.replace(/\\\\phi/g, 'φ');
                                result = result.replace(/\\\\infty/g, '∞');
                                result = result.replace(/\\\\int/g, '∫');
                                result = result.replace(/\\\\sum/g, 'Σ');
                                return result;
                            }
                            document.addEventListener('DOMContentLoaded', function() {
                                const content = document.getElementById('math-content');
                                if (content) {
                                    const latex = content.textContent;
                                    content.innerHTML = renderBasicLatex(latex);
                                }
                            });
                        </script>
                    </body>
                    </html>
                    """.trimIndent()
                }
                
                webView.loadDataWithBaseURL("file:///android_asset/", offlineHtml, "text/html", "UTF-8", null)
                android.util.Log.d("LatexMath", "✅ 离线WebView LaTeX加载成功")
                webView
                
            } catch (t: Throwable) {
                android.util.Log.e("LatexMath", "❌ 离线WebView LaTeX创建失败: ${t.message}", t)
                
                // 第二种备用方案：尝试JLatexMathView
                try {
                    android.util.Log.d("LatexMath", "🎯 尝试JLatexMathView备用方案")
                    val latexView = JLatexMathView(context)
                    latexView.setLatex(latex.trim())
                    val scale = (style.fontSize.value / 16f) * if (inline) 0.95f else 1.1f
                    latexView.scaleX = scale
                    latexView.scaleY = scale
                    android.util.Log.d("LatexMath", "✅ JLatexMathView备用方案成功")
                    latexView
                } catch (t2: Throwable) {
                    android.util.Log.e("LatexMath", "❌ JLatexMathView也失败: ${t2.message}", t2)
                    
                    // 最终备用方案：显示带样式的文本
                    TextView(context).apply {
                        text = if (inline) "$latex" else "\n$latex\n"
                        setTextColor(android.graphics.Color.BLUE) // 用蓝色表示这是数学公式
                        textSize = style.fontSize.value * if (inline) 0.95f else 1.1f
                        typeface = android.graphics.Typeface.MONOSPACE
                        android.util.Log.d("LatexMath", "⚠️ 使用文本备用方案显示: $latex")
                    }
                }
            }
        },
        update = { view ->
            android.util.Log.d("LatexMath", "🎯 更新LaTeX渲染: '$latex'")
            when (view) {
                is android.webkit.WebView -> {
                    // WebView更新逻辑 - 使用离线HTML
                    val cleanLatex = latex.trim()
                    val fontSize = style.fontSize.value * if (inline) 0.9f else 1.1f
                    // 修复颜色格式：确保正确处理白天/黑夜模式的颜色
                    val colorHex = String.format("#%06X", 0xFFFFFF and color.toArgb())
                    val mathType = if (inline) "math-inline" else "math-display"
                    
                    android.util.Log.d("LatexMath", "🎨 更新颜色信息: color=${color}, colorHex=$colorHex, fontSize=${fontSize}px")
                    
                    try {
                        val context = view.context
                        val offlineHtml = context.assets.open("mathjax_offline.html").bufferedReader().use { it.readText() }
                            .replace("MATH_CONTENT", cleanLatex)
                            .replace("MATH_TYPE", mathType)
                            .replace("FONT_SIZE", "${fontSize}px")
                            .replace("color: inherit;", "color: $colorHex;")
                        
                        view.loadDataWithBaseURL("file:///android_asset/", offlineHtml, "text/html", "UTF-8", null)
                    } catch (e: Exception) {
                        android.util.Log.e("LatexMath", "❌ 更新离线HTML失败: ${e.message}")
                        // 使用JavaScript直接更新内容
                        view.evaluateJavascript("updateMath('$cleanLatex', $inline);", null)
                    }
                }
                is JLatexMathView -> {
                    try {
                        view.setLatex(latex.trim())
                        val scale = (style.fontSize.value / 16f) * if (inline) 0.95f else 1.1f
                        view.scaleX = scale
                        view.scaleY = scale
                    } catch (t: Throwable) {
                        android.util.Log.e("LatexMath", "❌ JLatexMathView更新失败: ${t.message}")
                    }
                }
                is TextView -> {
                    view.text = if (inline) latex else "\n$latex\n"
                    view.setTextColor(android.graphics.Color.BLUE)
                    view.textSize = style.fontSize.value * if (inline) 0.95f else 1.1f
                }
            }
        },
        modifier = modifier.then(
            if (inline) Modifier.wrapContentHeight()
            else Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )
    )
}

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
    val normalized = normalizeMarkdownGlyphs(unwrapFileExtensionsInBackticks(text))
    val segments = remember(normalized) { splitInlineCodeSegments(normalized) }
    FlowRow(modifier = Modifier.wrapContentWidth()) {
        segments.forEach { seg ->
            if (seg.isCode) {
                Text(
                    text = seg.text,
                    style = style.copy(
                        color = textColor, 
                        fontWeight = FontWeight.Normal,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = style.fontSize * 0.9f
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

private fun unwrapFileExtensionsInBackticks(text: String): String {
    val regex = Regex("`\\.(?:[a-zA-Z0-9]{2,10})`")
    if (!regex.containsMatchIn(text)) return text
    return text.replace(regex) { mr -> mr.value.removePrefix("`").removeSuffix("`") }
}

private fun normalizeHeadingsForSimplePath(text: String): String {
    if (text.isBlank()) return text
    val lines = text.lines().map { line ->
        var l = line
        if (l.startsWith("＃")) {
            val count = l.takeWhile { it == '＃' }.length
            l = "#".repeat(count) + l.drop(count)
        }
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
        if (duration > 1000) {
            android.util.Log.w(TAG, "消息$messageId 渲染耗时: ${duration}ms")
        } else {
            android.util.Log.v(TAG, "消息$messageId 渲染完成: ${duration}ms")
        }
    }
    
    fun validateMarkdownOutput(content: String): Pair<Boolean, List<String>> {
        val issues = mutableListOf<String>()
        val fenceCount = Regex("```").findAll(content).count()
        if (fenceCount % 2 != 0) {
            issues.add("未闭合的代码块")
        }
        // 移除数学公式检查，只保留表格检查
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