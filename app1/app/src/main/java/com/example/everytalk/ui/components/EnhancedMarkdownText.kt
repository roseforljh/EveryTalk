package com.example.everytalk.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.ui.theme.chatColors
import dev.jeziellago.compose.markdowntext.MarkdownText
import com.example.everytalk.util.messageprocessor.parseMarkdownParts
import java.util.UUID

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
    // 回滚到工作的FlowRow布局，但减少间距
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalArrangement = Arrangement.Center,
        maxItemsInEachRow = Int.MAX_VALUE
    ) {
        var i = 0
        fun String.endsWithoutSpace(): Boolean {
            val t = this.trimEnd()
            return t.isNotEmpty() && !t.last().isWhitespace()
        }
        while (i < parts.size) {
            val part = parts[i]
            val next = if (i + 1 < parts.size) parts[i + 1] else null
            // 检测 "文本(无尾空格) + 行内公式" 组合，作为一个不可换行单元渲染
            if (part is MarkdownPart.Text &&
                next is MarkdownPart.MathBlock &&
                !next.displayMode
            ) {
                // 将前一段文本拆成“可换行前缀 + 不可换行的结尾词”，
                // 用结尾词与数学公式粘连，避免公式单独跑到下一行
                val (prefix, glue) = splitForNoWrapTail(part.content)
                if (prefix.isNotBlank()) {
                    SmartTextRenderer(
                        text = prefix,
                        textColor = textColor,
                        style = style,
                        modifier = Modifier.wrapContentWidth()
                    )
                }
                val glueText = glue.trimEnd() // 去掉尾部空格，避免“为 ”+ 公式之间出现断行/间隙
                if (glueText.isNotBlank()) {
                    NoWrapTextAndMath(
                        text = glueText,
                        latex = next.latex,
                        textColor = textColor,
                        style = style
                    )
                    i += 2
                    continue
                }
                // 若无法有效拆分，则走默认流程
            }

            when (part) {
                is MarkdownPart.Text -> {
                    if (part.content.isNotBlank()) {
                        val processedText = part.content
                        if (processedText.contains("$")) {
                            RenderTextWithInlineMath(
                                text = processedText,
                                textColor = textColor,
                                style = style
                            )
                        } else {
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
                else -> { /* 忽略其他类型 */ }
            }
            i += 1
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
        horizontalArrangement = Arrangement.spacedBy(0.dp), // 紧密排列
        verticalArrangement = Arrangement.Center,
        maxItemsInEachRow = Int.MAX_VALUE // 避免不必要的换行
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
    
    // 回滚到FlowRow布局
    FlowRow(
        modifier = Modifier.wrapContentWidth(),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalArrangement = Arrangement.Center,
        maxItemsInEachRow = Int.MAX_VALUE
    ) {
        fun String.endsWithoutSpace(): Boolean {
            val t = this.trimEnd()
            return t.isNotEmpty() && !t.last().isWhitespace()
        }
        var i = 0
        while (i < segments.size) {
            val seg = segments[i]
            val next = if (i + 1 < segments.size) segments[i + 1] else null
            // 将 "文本(无尾空格) + 数学" 合并为不可换行单元
            if (!seg.isMath && next != null && next.isMath) {
                // 将分段文本的尾部词与接下来的数学段粘连，形成不可换行单元
                val (prefix, glue) = splitForNoWrapTail(seg.content)
                if (prefix.isNotBlank()) {
                    MarkdownText(
                        markdown = normalizeBasicMarkdown(prefix),
                        style = style.copy(color = textColor),
                        modifier = Modifier.wrapContentWidth()
                    )
                }
                val glueText = glue.trimEnd()
                if (glueText.isNotBlank()) {
                    NoWrapTextAndMath(
                        text = glueText,
                        latex = next.content,
                        textColor = textColor,
                        style = style
                    )
                    i += 2
                    continue
                }
                // 拆分失败则走默认流程
            }

            if (seg.isMath) {
                LatexMath(
                    latex = seg.content,
                    inline = true,
                    color = textColor,
                    style = style,
                    modifier = Modifier.wrapContentWidth(),
                    messageId = "math_segment"
                )
            } else {
                if (seg.content.contains('`')) {
                    RenderTextWithInlineCode(seg.content, style, textColor)
                } else {
                    MarkdownText(
                        markdown = normalizeBasicMarkdown(seg.content),
                        style = style.copy(color = textColor),
                        modifier = Modifier.wrapContentWidth()
                    )
                }
            }
            i += 1
        }
    }
}

// 将"文本+行内数学"渲染为不可换行单元，避免被拆行
@Composable
private fun NoWrapTextAndMath(
    text: String,
    latex: String,
    textColor: Color,
    style: TextStyle
) {
    Row(
        modifier = Modifier.wrapContentWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MarkdownText(
            markdown = normalizeBasicMarkdown(text),
            style = style.copy(color = textColor),
            modifier = Modifier.wrapContentWidth()
        )
        LatexMath(
            latex = latex,
            inline = true,
            color = textColor,
            style = style,
            modifier = Modifier.wrapContentWidth(),
            messageId = "nowrap_pair"
        )
    }
}

// 将文本分割为可换行前缀和不可换行尾部
private fun splitForNoWrapTail(text: String): Pair<String, String> {
    if (text.isBlank()) return Pair("", "")
    
    // 找到最后一个空格或标点符号的位置
    val words = text.split(Regex("\\s+"))
    if (words.size <= 1) {
        return Pair("", text) // 只有一个词，全部作为尾部
    }
    
    // 取最后一个词作为不可换行部分
    val prefix = words.dropLast(1).joinToString(" ")
    val tail = words.last()
    
    return Pair(prefix, tail)
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
    android.util.Log.d("LatexMath", "🎯 开始原生渲染LaTeX: '$latex' (inline=$inline, mathId=$mathId)")
    
    // 直接使用新的原生渲染器，无需复杂的状态管理
    NativeMathText(
        latex = latex,
        isInline = inline,
        modifier = modifier.then(
            if (inline) Modifier.wrapContentHeight().padding(vertical = 0.dp)
            else Modifier.fillMaxWidth().padding(vertical = 2.dp)
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
    // 回滚到FlowRow布局
    FlowRow(
        modifier = Modifier.wrapContentWidth(),
        horizontalArrangement = Arrangement.spacedBy(1.dp), // 极小间距
        verticalArrangement = Arrangement.Center,
        maxItemsInEachRow = Int.MAX_VALUE
    ) {
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