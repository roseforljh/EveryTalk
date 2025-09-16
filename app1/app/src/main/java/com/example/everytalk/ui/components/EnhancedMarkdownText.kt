package com.example.everytalk.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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

// 🎯 新的合并内容数据类
private sealed class ConsolidatedContent {
    data class FlowContent(val parts: List<MarkdownPart>) : ConsolidatedContent()
    data class BlockContent(val part: MarkdownPart) : ConsolidatedContent()
}

// 🎯 智能检测是否应该合并所有内容进行统一渲染
private fun shouldMergeAllContent(parts: List<MarkdownPart>, originalText: String): Boolean {
    // 🎯 重要修复：如果包含MathBlock，绝对不要合并，让数学公式正确渲染
    val hasMathBlocks = parts.any { it is MarkdownPart.MathBlock }
    if (hasMathBlocks) {
        android.util.Log.d("shouldMergeAllContent", "🎯 Found MathBlocks, will NOT merge to preserve math rendering")
        return false
    }
    
    // 条件1：如果原始文本很短（小于200字符），倾向于合并
    if (originalText.length < 200) {
        // 条件1a：没有复杂的块级内容
        val hasComplexBlocks = parts.any { part ->
            when (part) {
                is MarkdownPart.CodeBlock -> true
                is MarkdownPart.MathBlock -> part.displayMode // 只有显示模式的数学公式算复杂
                else -> false
            }
        }
        
        if (!hasComplexBlocks) {
            return true
        }
        
        // 条件1b：parts数量过多相对于内容长度（可能被过度分割）
        if (parts.size > originalText.length / 20) {
            return true
        }
        
        // 条件1c：大多数parts都很短（可能是错误分割的结果）
        val shortParts = parts.count { part ->
            when (part) {
                is MarkdownPart.Text -> part.content.trim().length < 10
                is MarkdownPart.MathBlock -> !part.displayMode && part.latex.length < 20
                else -> false
            }
        }
        
        if (shortParts > parts.size * 0.7) { // 超过70%的parts都很短
            return true
        }
    }
    
    // 条件2：检测明显的错误分割模式
    // 如果有很多单字符或超短的文本part，可能是分割错误
    val singleCharParts = parts.count { part ->
        part is MarkdownPart.Text && part.content.trim().length <= 2
    }
    
    if (singleCharParts > 2 && singleCharParts > parts.size * 0.4) {
        return true
    }
    
    // 条件3：如果原始文本包含明显的连续内容但被分割了
    // 检测类似 "- *文字 e^x · **" 这样的模式
    val isListLikePattern = originalText.trim().let { text ->
        (text.startsWith("-") || text.startsWith("*") || text.startsWith("·")) &&
        text.length < 100 &&
        parts.size > 3
    }
    
    if (isListLikePattern) {
        return true
    }
    
    return false
}

// 🎯 激进的内容合并函数
private fun consolidateInlineContent(parts: List<MarkdownPart>): List<ConsolidatedContent> {
    val result = mutableListOf<ConsolidatedContent>()
    var currentInlineGroup = mutableListOf<MarkdownPart>()
    
    fun flushInlineGroup() {
        if (currentInlineGroup.isNotEmpty()) {
            result.add(ConsolidatedContent.FlowContent(currentInlineGroup.toList()))
            currentInlineGroup.clear()
        }
    }
    
    parts.forEach { part ->
        when (part) {
            is MarkdownPart.Text -> {
                currentInlineGroup.add(part)
            }
            is MarkdownPart.MathBlock -> {
                if (!part.displayMode) {
                    // 行内数学公式加入当前组
                    currentInlineGroup.add(part)
                } else {
                    // 块级数学公式：先输出当前组，然后独立处理
                    flushInlineGroup()
                    result.add(ConsolidatedContent.BlockContent(part))
                }
            }
            is MarkdownPart.CodeBlock -> {
                // 代码块：先输出当前组，然后独立处理
                flushInlineGroup()
                result.add(ConsolidatedContent.BlockContent(part))
            }
            is MarkdownPart.HtmlContent -> {
                // HTML内容：先输出当前组，然后独立处理
                flushInlineGroup()
                result.add(ConsolidatedContent.BlockContent(part))
            }
        }
    }
    
    flushInlineGroup()
    return result
}

// 🎯 自定义行内内容渲染器
@Composable
private fun InlineContentRenderer(
    parts: List<MarkdownPart>,
    textColor: Color,
    style: TextStyle
) {
    // 🎯 新策略：使用FlowRow但消除间距，让内容紧密相连
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalArrangement = Arrangement.Center
    ) {
        parts.forEach { part ->
            when (part) {
                is MarkdownPart.Text -> {
                    if (part.content.isNotBlank()) {
                        // 处理文本中可能包含的行内数学公式
                        val processedText = part.content
                        if (processedText.contains("$")) {
                            // 如果文本包含$符号，可能有行内数学公式，使用自定义处理
                            RenderTextWithInlineMath(
                                text = processedText,
                                textColor = textColor,
                                style = style
                            )
                        } else {
                            // 纯文本，使用智能渲染器处理可能的内联代码和Markdown格式
                            SmartTextRenderer(
                                text = processedText,
                                textColor = textColor,
                                style = style,
                                modifier = Modifier.wrapContentWidth()
                            )
                        }
                    }
                }
                is MarkdownPart.MathBlock -> {
                    if (!part.displayMode) {
                        // 行内数学公式，紧密连接
                        LatexMath(
                            latex = part.latex,
                            inline = true,
                            color = textColor,
                            style = style,
                            modifier = Modifier.wrapContentWidth(),
                            messageId = "inline_render"
                        )
                    }
                }
                else -> {
                    // 其他类型暂时忽略
                }
            }
        }
    }
}

// 🎯 智能文本渲染器：自动检测内联代码和数学公式
@Composable
private fun SmartTextRenderer(
    text: String,
    textColor: Color,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    val hasInlineCode = text.contains('`') && !text.startsWith("```")
    val hasMath = text.contains('$')
    
    when {
        hasInlineCode && hasMath -> {
            // 同时包含内联代码和数学公式，使用复合渲染
            RenderTextWithInlineCodeAndMath(text, textColor, style, modifier)
        }
        hasInlineCode -> {
            // 只有内联代码，使用自定义渲染器
            RenderTextWithInlineCode(text, style, textColor)
        }
        hasMath -> {
            // 只有数学公式，使用数学渲染器
            RenderTextWithInlineMath(text, textColor, style)
        }
        else -> {
            // 纯Markdown文本，使用原始渲染器
            MarkdownText(
                markdown = normalizeBasicMarkdown(text),
                style = style.copy(color = textColor),
                modifier = modifier
            )
        }
    }
}

// 🎯 处理同时包含内联代码和数学公式的文本
@Composable
private fun RenderTextWithInlineCodeAndMath(
    text: String,
    textColor: Color,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    // 先按代码块分割，然后在每个片段中处理数学公式
    val codeSegments = splitInlineCodeSegments(text)
    
    FlowRow(
        modifier = modifier.wrapContentWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalArrangement = Arrangement.Center
    ) {
        codeSegments.forEach { segment ->
            if (segment.isCode) {
                // 内联代码片段，使用自定义样式
                InlineCodeChip(segment.text, style.copy(color = textColor))
            } else {
                // 非代码片段，检查是否有数学公式
                if (segment.text.contains('$')) {
                    RenderTextWithInlineMath(segment.text, textColor, style)
                } else {
                    MarkdownText(
                        markdown = normalizeBasicMarkdown(segment.text),
                        style = style.copy(color = textColor),
                        modifier = Modifier.wrapContentWidth()
                    )
                }
            }
        }
    }
}

// 🎯 处理包含行内数学公式的文本
@Composable
private fun RenderTextWithInlineMath(
    text: String,
    textColor: Color,
    style: TextStyle
) {
    // 简单的$...$分割处理
    val segments = splitMathSegments(text)
    
    FlowRow(
        modifier = Modifier.wrapContentWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalArrangement = Arrangement.Center
    ) {
        segments.forEach { segment ->
            if (segment.isMath) {
                LatexMath(
                    latex = segment.content,
                    inline = true,
                    color = textColor,
                    style = style,
                    modifier = Modifier.wrapContentWidth(),
                    messageId = "math_segment"
                )
            } else {
                // 对于非数学的文本段，检查是否有内联代码
                if (segment.content.contains('`')) {
                    RenderTextWithInlineCode(segment.content, style, textColor)
                } else {
                    MarkdownText(
                        markdown = normalizeBasicMarkdown(segment.content),
                        style = style.copy(color = textColor),
                        modifier = Modifier.wrapContentWidth()
                    )
                }
            }
        }
    }
}

// 🎯 简单的数学公式分割器
private data class TextSegment(val content: String, val isMath: Boolean)

private fun splitMathSegments(text: String): List<TextSegment> {
    val segments = mutableListOf<TextSegment>()
    var currentPos = 0
    
    while (currentPos < text.length) {
        val mathStart = text.indexOf('$', currentPos)
        if (mathStart == -1) {
            // 没有更多数学公式，添加剩余文本
            if (currentPos < text.length) {
                segments.add(TextSegment(text.substring(currentPos), false))
            }
            break
        }
        
        // 添加数学公式前的文本
        if (mathStart > currentPos) {
            segments.add(TextSegment(text.substring(currentPos, mathStart), false))
        }
        
        // 查找数学公式结束
        val mathEnd = text.indexOf('$', mathStart + 1)
        if (mathEnd == -1) {
            // 没有找到结束$，当作普通文本
            segments.add(TextSegment(text.substring(mathStart), false))
            break
        }
        
        // 添加数学公式
        val mathContent = text.substring(mathStart + 1, mathEnd)
        if (mathContent.isNotBlank()) {
            segments.add(TextSegment(mathContent, true))
        }
        
        currentPos = mathEnd + 1
    }
    
    return segments
}

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
            // 清理渲染状态
            MathRenderingManager.clearMessageStates(message.id)
        }
    }

    // 🎯 检查消息是否包含数学公式，提交渲染任务
    LaunchedEffect(message.id, message.text) {
        if (message.sender == com.example.everytalk.data.DataClass.Sender.AI && 
            MathRenderingManager.hasRenderableMath(message.text)) {
            val mathBlocks = ConversationLoadManager.extractMathBlocks(message.text, message.id)
            if (mathBlocks.isNotEmpty()) {
                MathRenderingManager.submitMessageMathTasks(message.id, mathBlocks)
            }
        }
    }

    Column(modifier = modifier) {
        // 🎯 调试日志：检查消息的解析状态
        android.util.Log.d("EnhancedMarkdownText", "=== Rendering Message ${message.id} ===")
        android.util.Log.d("EnhancedMarkdownText", "Message sender: ${message.sender}")
        android.util.Log.d("EnhancedMarkdownText", "Message text: ${message.text.take(100)}...")
        android.util.Log.d("EnhancedMarkdownText", "Message parts count: ${message.parts.size}")
        android.util.Log.d("EnhancedMarkdownText", "Message contentStarted: ${message.contentStarted}")
        
        // 🎯 重要修复：只对AI消息进行数学公式解析，用户消息保持原始文本
        if (message.sender != com.example.everytalk.data.DataClass.Sender.AI) {
            // 用户消息：直接显示原始文本，不进行任何格式转换
            android.util.Log.d("EnhancedMarkdownText", "User message - displaying raw text without any formatting")
            Text(
                text = message.text,
                style = style.copy(color = textColor),
                modifier = Modifier
            )
            return@Column
        }
        
        if (message.parts.isEmpty()) {
            android.util.Log.w("EnhancedMarkdownText", "⚠️ AI Message parts is EMPTY, attempting to parse math formulas")
            // 🎯 临时修复：即使parts为空，也尝试解析数学公式（仅针对AI消息）
            if (message.text.contains("$") || message.text.contains("\\")) {
                android.util.Log.d("EnhancedMarkdownText", "Found potential math content, parsing...")
                
                val parsedParts = try {
                    parseMarkdownParts(message.text)
                } catch (e: Exception) {
                    android.util.Log.e("EnhancedMarkdownText", "Failed to parse math content: ${e.message}")
                    emptyList()
                }
                
                android.util.Log.d("EnhancedMarkdownText", "Parsed ${parsedParts.size} parts from empty-parts message")
                parsedParts.forEachIndexed { index, part ->
                    android.util.Log.d("EnhancedMarkdownText", "Part $index: ${part::class.simpleName} - ${part.toString().take(100)}...")
                }
                
                if (parsedParts.isNotEmpty()) {
                    // 使用解析后的parts进行渲染
                    parsedParts.forEach { part ->
                        when (part) {
                            is MarkdownPart.Text -> {
                                if (part.content.isNotBlank()) {
                                    SmartTextRenderer(
                                        text = part.content,
                                        textColor = textColor,
                                        style = style,
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
                                    modifier = if (part.displayMode) 
                                        Modifier.fillMaxWidth().padding(vertical = 4.dp) 
                                    else 
                                        Modifier.wrapContentWidth(),
                                    messageId = message.id
                                )
                            }
                            else -> {
                                // 其他类型
                            }
                        }
                    }
                } else {
                    // 解析失败，使用智能渲染器
                    SmartTextRenderer(
                        text = message.text,
                        textColor = textColor,
                        style = style,
                        modifier = Modifier
                    )
                }
            } else {
                // 没有数学内容，使用智能渲染器
                SmartTextRenderer(
                    text = message.text,
                    textColor = textColor,
                    style = style,
                    modifier = Modifier
                )
            }
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
            
            android.util.Log.d("EnhancedMarkdownText", "Has valid parts: $hasValidParts")
            message.parts.forEachIndexed { index, part ->
                android.util.Log.d("EnhancedMarkdownText", "Checking Part $index: ${part::class.simpleName}")
                when (part) {
                    is MarkdownPart.Text -> android.util.Log.d("EnhancedMarkdownText", "  Text: '${part.content.take(30)}...'")
                    is MarkdownPart.MathBlock -> android.util.Log.d("EnhancedMarkdownText", "  MathBlock: '${part.latex}' (displayMode=${part.displayMode})")
                    is MarkdownPart.CodeBlock -> android.util.Log.d("EnhancedMarkdownText", "  CodeBlock: '${part.content.take(30)}...'")
                    is MarkdownPart.HtmlContent -> android.util.Log.d("EnhancedMarkdownText", "  HtmlContent: '${part.html.take(30)}...'")
                }
            }
            
            if (!hasValidParts && message.text.isNotBlank()) {
                // 回退到原始文本渲染，使用智能渲染器
                RenderingMonitor.logRenderingIssue(message.id, "Parts无效，回退到原始文本", message.text)
                SmartTextRenderer(
                    text = message.text,
                    textColor = textColor,
                    style = style,
                    modifier = Modifier
                )
            } else {
                // 🎯 智能检测：如果内容很短且可能被错误分割，直接合并渲染
                val shouldMergeContent = shouldMergeAllContent(message.parts, message.text)
                android.util.Log.d("EnhancedMarkdownText", "Should merge content: $shouldMergeContent")
                
                if (shouldMergeContent) {
                    android.util.Log.d("EnhancedMarkdownText", "🔧 检测到内容被错误分割，合并渲染")
                    // 直接使用原始文本进行完整渲染，使用智能渲染器
                    SmartTextRenderer(
                        text = message.text,
                        textColor = textColor,
                        style = style,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    android.util.Log.d("EnhancedMarkdownText", "🎯 Using part-by-part rendering with ${message.parts.size} parts")
                    // 🎯 激进优化：将连续的文本和行内数学公式合并成一个流式布局
                    val consolidatedContent = consolidateInlineContent(message.parts)
                    android.util.Log.d("EnhancedMarkdownText", "Consolidated content count: ${consolidatedContent.size}")
                    
                    consolidatedContent.forEach { content ->
                        when (content) {
                            is ConsolidatedContent.FlowContent -> {
                                android.util.Log.d("EnhancedMarkdownText", "🎯 Rendering FlowContent with ${content.parts.size} parts")
                                // 使用自定义的行内渲染器，完全消除换行
                                InlineContentRenderer(
                                    parts = content.parts,
                                    textColor = textColor,
                                    style = style
                                )
                            }
                            is ConsolidatedContent.BlockContent -> {
                                val part = content.part
                                android.util.Log.d("EnhancedMarkdownText", "🎯 Rendering BlockContent: ${part::class.simpleName}")
                                when (part) {
                                    is MarkdownPart.CodeBlock -> {
                                        android.util.Log.d("EnhancedMarkdownText", "🎯 Rendering CodeBlock")
                                        CodePreview(
                                            code = part.content,
                                            language = part.language
                                        )
                                    }
                                    is MarkdownPart.MathBlock -> {
                                        android.util.Log.d("EnhancedMarkdownText", "🎯 Rendering MathBlock: '${part.latex}' (displayMode=${part.displayMode})")
                                        LatexMath(
                                            latex = part.latex,
                                            inline = false,
                                            color = textColor,
                                            style = style,
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            messageId = message.id
                                        )
                                    }
                                    else -> {
                                        android.util.Log.d("EnhancedMarkdownText", "🎯 Other block content type: ${part::class.simpleName}")
                                        // 处理其他块级内容
                                    }
                                }
                            }
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
    modifier: Modifier = Modifier,
    messageId: String = "",
    mathId: String = "${messageId}_${latex.hashCode()}"
) {
    android.util.Log.d("LatexMath", "🎯 开始渲染LaTeX: '$latex' (inline=$inline, mathId=$mathId)")
    
    // 获取渲染状态
    val renderState by MathRenderingManager.getRenderState(mathId)
    
    // 当状态为PENDING时，标记为开始渲染
    LaunchedEffect(mathId) {
        if (renderState == MathRenderingManager.RenderState.PENDING) {
            MathRenderingManager.markRenderingStarted(mathId)
        }
    }
    
    when (renderState) {
        MathRenderingManager.RenderState.PENDING -> {
            // 显示占位符
            Box(
                modifier = modifier.then(
                    if (inline) Modifier.size(50.dp, 20.dp) else Modifier.size(100.dp, 40.dp)
                ),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(if (inline) 12.dp else 16.dp),
                    strokeWidth = 1.dp,
                    color = color.copy(alpha = 0.6f)
                )
            }
        }
        
        MathRenderingManager.RenderState.RENDERING,
        MathRenderingManager.RenderState.COMPLETED -> {
            // 渲染WebView
            AndroidView(
                factory = { context ->
                    android.util.Log.d("LatexMath", "🎯 创建离线WebView LaTeX渲染器 for $mathId")
                    try {
                        val webView = android.webkit.WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = false
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = true
                            settings.allowContentAccess = true
                            
                            // 设置WebView回调，标记渲染完成
                            webViewClient = object : android.webkit.WebViewClient() {
                                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    android.util.Log.d("LatexMath", "✅ WebView页面加载完成: $mathId")
                                    MathRenderingManager.markRenderingCompleted(mathId)
                                }
                                
                                override fun onReceivedError(
                                    view: android.webkit.WebView?,
                                    errorCode: Int,
                                    description: String?,
                                    failingUrl: String?
                                ) {
                                    super.onReceivedError(view, errorCode, description, failingUrl)
                                    android.util.Log.e("LatexMath", "❌ WebView加载失败: $mathId, $description")
                                    MathRenderingManager.markRenderingFailed(mathId)
                                }
                            }
                        }
                        
                        val cleanLatex = latex.trim()
                        val fontSize = style.fontSize.value * if (inline) 0.9f else 1.1f
                        val colorHex = String.format("#%06X", 0xFFFFFF and color.toArgb())
                        val mathType = if (inline) "math-inline" else "math-display"
                        
                        android.util.Log.d("LatexMath", "🎨 颜色信息: color=${color}, colorHex=$colorHex, fontSize=${fontSize}px")
                        
                        val offlineHtml = try {
                            context.assets.open("mathjax_offline.html").bufferedReader().use { it.readText() }
                                .replace("MATH_CONTENT", cleanLatex)
                                .replace("MATH_TYPE", mathType)
                                .replace("FONT_SIZE", "${fontSize}px")
                                .replace("color: inherit;", "color: $colorHex;")
                        } catch (e: Exception) {
                            android.util.Log.e("LatexMath", "❌ 读取离线HTML模板失败: ${e.message}")
                            generateFallbackHtml(cleanLatex, mathType, fontSize, colorHex)
                        }
                        
                        webView.loadDataWithBaseURL("file:///android_asset/", offlineHtml, "text/html", "UTF-8", null)
                        android.util.Log.d("LatexMath", "✅ 离线WebView LaTeX加载成功: $mathId")
                        webView
                        
                    } catch (t: Throwable) {
                        android.util.Log.e("LatexMath", "❌ 离线WebView LaTeX创建失败: ${t.message}", t)
                        MathRenderingManager.markRenderingFailed(mathId)
                        
                        // 最终备用方案：显示带样式的文本
                        TextView(context).apply {
                            text = if (inline) latex else "\n$latex\n"
                            setTextColor(android.graphics.Color.BLUE)
                            textSize = style.fontSize.value * if (inline) 0.95f else 1.1f
                            typeface = android.graphics.Typeface.MONOSPACE
                            android.util.Log.d("LatexMath", "⚠️ 使用文本备用方案显示: $latex")
                        }
                    }
                },
                update = { view ->
                    // 更新逻辑保持不变，但添加状态标记
                    android.util.Log.d("LatexMath", "🎯 更新LaTeX渲染: '$latex' for $mathId")
                    when (view) {
                        is android.webkit.WebView -> {
                            val cleanLatex = latex.trim()
                            val fontSize = style.fontSize.value * if (inline) 0.9f else 1.1f
                            val colorHex = String.format("#%06X", 0xFFFFFF and color.toArgb())
                            val mathType = if (inline) "math-inline" else "math-display"
                            
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
                                view.evaluateJavascript("updateMath('$cleanLatex', $inline);", null)
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
                    if (inline) Modifier.wrapContentHeight().padding(vertical = 0.dp)
                    else Modifier.fillMaxWidth().padding(vertical = 2.dp)
                )
            )
        }
        
        MathRenderingManager.RenderState.FAILED -> {
            // 显示错误状态
            Text(
                text = if (inline) latex else "\n$latex\n",
                style = style.copy(
                    color = color,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = style.fontSize * if (inline) 0.95f else 1.1f
                ),
                modifier = modifier.then(
                    if (inline) Modifier.wrapContentHeight().padding(vertical = 0.dp)
                    else Modifier.fillMaxWidth().padding(vertical = 2.dp)
                )
            )
        }
    }
}

/**
 * 生成备用HTML内容
 */
private fun generateFallbackHtml(cleanLatex: String, mathType: String, fontSize: Float, colorHex: String): String {
    return """
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="UTF-8">
        <style>
            body { 
                margin: 0; 
                padding: 2px; 
                background: transparent; 
                font-family: 'Times New Roman', serif;
                font-size: ${fontSize}px;
                color: $colorHex;
                line-height: 1.0;
            }
            .math-inline { display: inline-block; vertical-align: middle; margin: 0; padding: 0; }
            .math-display { display: block; text-align: center; margin: 2px 0; padding: 0; }
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
                result = result.replace(/\\\\dots/g, '…');
                result = result.replace(/\\\\ldots/g, '…');
                result = result.replace(/\\\\cdots/g, '⋯');
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
                InlineCodeChip(
                    code = seg.text,
                    baseStyle = style.copy(color = textColor)
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
        style = baseStyle.copy(
            fontWeight = FontWeight.Normal,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontSize = baseStyle.fontSize * 0.9f
        ),
        modifier = Modifier
            .background(
                color = MaterialTheme.chatColors.codeBlockBackground,
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 4.dp, vertical = 2.dp)
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