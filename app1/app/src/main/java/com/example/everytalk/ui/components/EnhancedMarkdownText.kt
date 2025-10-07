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
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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

    // 优先：强特征的"多行列表/编号段落"→ 合并整段渲染,避免被拆散后丢失列表上下文
    run {
        val lines = originalText.lines()
        // 统计项目符号或有序编号开头的行数（允许前导空格）
        val bulletRegex = Regex("^\\s*([*+\\-]|\\d+[.)])\\s+")
        val bulletLines = lines.count { bulletRegex.containsMatchIn(it) }
        // 若存在"编号标题行 + 若干缩进子项"的结构，也强制合并
        val hasHeadingNumber = lines.any { Regex("^\\s*\\d+[.)]\\s+").containsMatchIn(it) }
        if (bulletLines >= 2 || (hasHeadingNumber && bulletLines >= 1)) {
            return true
        }
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
            is MarkdownPart.Table -> {
                // 表格：先输出当前组，然后独立处理
                flushInlineGroup()
                result.add(ConsolidatedContent.BlockContent(part))
            }
            is MarkdownPart.MixedContent -> {
                // 混合内容：先输出当前组，然后独立处理
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
    // 🎯 修复：改用 Center 替代 Bottom，实现垂直居中
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalArrangement = Arrangement.Center,  // ✅ 垂直居中对齐
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
                // 将前一段文本拆成"可换行前缀 + 不可换行的结尾词"，
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
                val glueText = glue.trimEnd() // 去掉尾部空格，避免"为 "+ 公式之间出现断行/间隙
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
                        // 先处理“纯裸 LaTeX 单行”——例如：\boxed{275.5}
                        if (isPureBareLatexLine(processedText)) {
                            LatexMath(
                                latex = processedText.trim(),
                                inline = false,
                                color = textColor,
                                style = style,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                messageId = "pure_bare_latex_line"
                            )
                        } else if (processedText.contains("$")) {
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
    // 关键修复：在做能力分支前，先最小化将“裸 sqrt/frac 等”包裹为 $...$
    // 这样 hasMath 判定才能捕捉到后续渲染为 LaTeX，而不是被走到 Markdown 渲染路径
    val preWrapped = remember(text) {
        if (!text.contains('$')) wrapBareLatexForInline(text) else text
    }
    val hasInlineCode = preWrapped.contains('`') && !preWrapped.startsWith("```")
    val hasMath = preWrapped.contains('$')
    
    when {
        hasInlineCode && hasMath -> {
            // 同时包含内联代码和数学公式，使用复合渲染
            RenderTextWithInlineCodeAndMath(preWrapped, textColor, style, modifier)
        }
        hasInlineCode -> {
            // 只有内联代码，使用自定义渲染器
            RenderTextWithInlineCode(preWrapped, style, textColor)
        }
        hasMath -> {
            // 只有数学公式，使用数学渲染器
            RenderTextWithInlineMath(preWrapped, textColor, style)
        }
        else -> {
            // 0) 优先处理“块级数学 $$...$$”场景（严格成对），避免误切导致文本缺失
            run {
                val pairCount = Regex("\\$\\$").findAll(text).count()
                if (pairCount >= 2 && pairCount % 2 == 0) {
                    RenderTextWithBlockMath(
                        text = text,
                        textColor = textColor,
                        style = style
                    )
                    return
                }
            }
            // 0b) 裸 LaTeX 直达行内数学管线（如 \boxed{...} / \frac 等未加 $ 的情形）
            if (containsBareLatexToken(text)) {
                RenderTextWithInlineMath(
                    text = wrapBareLatexForInline(text),
                    textColor = textColor,
                    style = style
                )
                return
            }
            // 1) 围栏代码：严格匹配并“全部遍历”，确保每个代码块都走自定义 CodePreview
            val fencedRegex = Regex("(?s)```\\s*([a-zA-Z0-9_+\\-#.]*)\\s*\\n(.*?)\\n```")
            val matches = fencedRegex.findAll(text).toList()
            if (matches.isNotEmpty()) {
                var last = 0
                matches.forEachIndexed { idx, mr ->
                    val before = text.substring(last, mr.range.first)
                    if (before.isNotBlank()) {
                        SmartTextRenderer(
                            text = before,
                            textColor = textColor,
                            style = style,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    val lang = mr.groups[1]?.value?.trim().orEmpty()
                    val code = mr.groups[2]?.value ?: ""
                    CodePreview(
                        code = code,
                        language = if (lang.isBlank()) null else lang,
                        modifier = Modifier.fillMaxWidth()
                    )
                    last = mr.range.last + 1
                    if (idx != matches.lastIndex) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                if (last < text.length) {
                    val tail = text.substring(last)
                    if (tail.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        SmartTextRenderer(
                            text = tail,
                            textColor = textColor,
                            style = style,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                // 已处理全部代码块，直接返回
                return
            } else if (text.contains("```")) {
                // 宽松兜底：即使围栏不规范（缺少换行或末尾未闭合），也按围栏切片渲染
                // 这样不会退回库默认的 MarkdownText，从而保证启用自定义 CodePreview（带复制/预览）
                FallbackFencedRenderer(
                    raw = text,
                    textColor = textColor,
                    style = style
                )
                return
            }
 
            // 1b) 识别“缩进式代码块”（以4个空格或Tab起始的连续行），统一走 CodePreview 渲染
            run {
                val blocks = extractIndentedCodeBlocks(text)
                if (blocks != null && blocks.blocks.isNotEmpty()) {
                    var last = 0
                    blocks.blocks.forEachIndexed { idx, b ->
                        val before = text.substring(last, b.range.first)
                        if (before.isNotBlank()) {
                            SmartTextRenderer(
                                text = before,
                                textColor = textColor,
                                style = style,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        CodePreview(
                            code = b.code,
                            language = null,
                            modifier = Modifier.fillMaxWidth()
                        )
                        last = b.range.last + 1
                        if (idx != blocks.blocks.lastIndex) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                    if (last < text.length) {
                        val tail = text.substring(last)
                        if (tail.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            SmartTextRenderer(
                                text = tail,
                                textColor = textColor,
                                style = style,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    return
                }
            }
 
            // 2) 表格兜底检测：即使上游给到 Text，也分流到表格渲染
            if (detectMarkdownTable(text)) {
                // 原生表格兜底渲染
                SimpleTableRenderer(
                    content = text,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                // 3) 纯Markdown文本，使用原始渲染器
                MarkdownText(
                    markdown = normalizeBasicMarkdown(text),
                    style = style.copy(color = textColor, platformStyle = PlatformTextStyle(includeFontPadding = false)),
                    modifier = modifier
                )
            }
        }
    }
}

// 🎯 处理包含块级数学（$$...$$）与普通文本的混合内容
@Composable
private fun RenderTextWithBlockMath(
    text: String,
    textColor: Color,
    style: TextStyle
) {
    // 以成对 $$ 作为分段，奇数段为文本，偶数段为数学（与 split 结果一致）
    val parts = text.split("$$")
    Column(modifier = Modifier.fillMaxWidth()) {
        parts.forEachIndexed { idx, seg ->
            if (seg.isEmpty()) return@forEachIndexed
            if (idx % 2 == 1) {
                // 数学段（块级）
                LatexMath(
                    latex = seg.trim(),
                    inline = false,
                    color = textColor,
                    style = style,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    messageId = "block_segment"
                )
            } else {
                // 普通文本（不做数学改写）
                SmartTextRenderer(
                    text = seg,
                    textColor = textColor,
                    style = style,
                    modifier = Modifier.fillMaxWidth()
                )
            }
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
    // 统一渲染：去除内联代码反引号，再按行内数学拆分渲染，避免 FlowRow 造成的断行/间距混乱
    val noTicks = remember(text) { removeInlineCodeBackticks(text) }
    RenderTextWithInlineMath(
        text = noTicks,
        textColor = textColor,
        style = style
    )
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
    
    // 🎯 修复：改用 Center，实现垂直居中
    FlowRow(
        modifier = Modifier.wrapContentWidth(),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalArrangement = Arrangement.Center,  // ✅ 垂直居中
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
                        markdown = normalizeBasicMarkdownNoMath(prefix),
                        style = style.copy(color = textColor, platformStyle = PlatformTextStyle(includeFontPadding = false)),
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
                    SmartTextRenderer(
                        text = seg.content,
                        textColor = textColor,
                        style = style,
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
        SmartTextRenderer(
            text = text,
            textColor = textColor,
            style = style,
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
    
    val baseStyle = remember(style) { style.normalizeForChat() }
    val textColor = when {
        color != Color.Unspecified -> color
        baseStyle.color != Color.Unspecified -> baseStyle.color
        else -> if (systemDark) Color(0xFFFFFFFF) else Color(0xFF000000)
    }
    
    // 🔧 统一清洗：去行尾“\”与相邻重复段，避免重复/脏字符导致的格式混乱
    val cleanedText = remember(message.text) { sanitizeAiOutput(message.text) }
 
     DisposableEffect(message.id) {
        onDispose {
            RenderingMonitor.trackRenderingPerformance(message.id, startTime)
            // 清理渲染状态
            MathRenderingManager.clearMessageStates(message.id)
        }
    }

    // 🎯 检查消息是否包含数学公式，提交渲染任务（基于清洗后的文本）
    LaunchedEffect(message.id, cleanedText) {
        if (message.sender == com.example.everytalk.data.DataClass.Sender.AI &&
            MathRenderingManager.hasRenderableMath(cleanedText)) {
            val mathBlocks = ConversationLoadManager.extractMathBlocks(cleanedText, message.id)
            if (mathBlocks.isNotEmpty()) {
                MathRenderingManager.submitMessageMathTasks(message.id, mathBlocks)
            }
        }
    }

    Column(modifier = modifier) {
        // 若已结束流式（最终态），优先使用“整段文本级”的渲染策略，避免被碎片化 parts 干扰
        if (!isStreaming) {
            val t = cleanedText
            // 先渲染整段中的首张表格（若存在）
            if (detectMarkdownTable(t)) {
                val (before, tableBlock, after) = splitByFirstMarkdownTable(t)
                if (before.isNotBlank()) {
                    SmartTextRenderer(
                        text = before,
                        textColor = textColor,
                        style = baseStyle,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (tableBlock.isNotBlank()) {
                    SimpleTableRenderer(
                        content = tableBlock,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (after.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    SmartTextRenderer(
                        text = after,
                        textColor = textColor,
                        style = baseStyle,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                return@Column
            }

            // 再渲染整段中的围栏代码（严格：多段顺序渲染；失败则宽松兜底），强制使用 CodePreview（带复制/预览）
            run {
                val strict = Regex("(?s)```\\s*([a-zA-Z0-9_+\\-#.]*)\\s*\\n(.*?)\\n```")
                val matches = strict.findAll(t).toList()
                if (matches.isNotEmpty()) {
                    var last = 0
                    matches.forEachIndexed { idx, mr ->
                        val before = t.substring(last, mr.range.first)
                        if (before.isNotBlank()) {
                            SmartTextRenderer(
                                text = before,
                                textColor = textColor,
                                style = baseStyle,
                                modifier = Modifier
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        val lang = mr.groups[1]?.value?.trim().orEmpty()
                        val code = mr.groups[2]?.value ?: ""
                        CodePreview(
                            code = code,
                            language = if (lang.isBlank()) null else lang,
                            modifier = Modifier.fillMaxWidth()
                        )
                        last = mr.range.last + 1
                        if (idx != matches.lastIndex) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                    if (last < t.length) {
                        val tail = t.substring(last)
                        if (tail.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            SmartTextRenderer(
                                text = tail,
                                textColor = textColor,
                                style = baseStyle,
                                modifier = Modifier
                            )
                        }
                    }
                    return@Column
                } else if (t.contains("```")) {
                    // 宽松兜底：不规范围栏（缺换行/缺闭合）整体交给 FallbackFencedRenderer，保证依然走 CodePreview
                    FallbackFencedRenderer(
                        raw = t,
                        textColor = textColor,
                        style = baseStyle
                    )
                    return@Column
                }
            }

            // 无表格/围栏，走智能渲染（含行内代码/数学）
            SmartTextRenderer(
                text = t,
                textColor = textColor,
                style = baseStyle,
                modifier = Modifier
            )
            return@Column
        }

        // 🎯 调试日志：检查消息的解析状态
        android.util.Log.d("EnhancedMarkdownText", "=== Rendering Message ${message.id} ===")
        android.util.Log.d("EnhancedMarkdownText", "Message sender: ${message.sender}")
        android.util.Log.d("EnhancedMarkdownText", "Message text: ${message.text.take(100)}...")
        android.util.Log.d("EnhancedMarkdownText", "Message parts count: ${message.parts.size}")
        android.util.Log.d("EnhancedMarkdownText", "Message contentStarted: ${message.contentStarted}")
        
        // 🎯 保持原逻辑：非 AI 消息不做任何格式转换，完全不影响用户气泡自适应宽度
        if (message.sender != com.example.everytalk.data.DataClass.Sender.AI) {
            android.util.Log.d("EnhancedMarkdownText", "User/Non-AI message - displaying raw text without formatting")
            Text(
                text = message.text,
                style = style.copy(color = textColor, platformStyle = PlatformTextStyle(includeFontPadding = false)),
                modifier = Modifier.wrapContentWidth()
            )
            return@Column
        }
        
        // 🎯 简单Markdown快速路径：无$$块级数学、无围栏代码、无表格时，直接交给SmartTextRenderer统一渲染
        run {
            val t = cleanedText
            val hasBlockMath = t.contains("$$")
            val hasFenced = Regex("(?s)```").containsMatchIn(t)
            val hasTable = detectMarkdownTable(t)
            if (!hasBlockMath && !hasFenced && !hasTable) {
                SmartTextRenderer(
                    text = t,
                    textColor = textColor,
                    style = baseStyle,
                    modifier = Modifier.fillMaxWidth()
                )
                return@Column
            }
        }
        
        // 优先级更高：整条消息级别的表格检测与切分渲染（避免被分片打散而检测失败）
        if (detectMarkdownTable(cleanedText)) {
            val (before, tableBlock, after) = splitByFirstMarkdownTable(cleanedText)
            if (before.isNotBlank()) {
                SmartTextRenderer(
                    text = before,
                    textColor = textColor,
                    style = baseStyle,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (tableBlock.isNotBlank()) {
                SimpleTableRenderer(
                    content = tableBlock,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (after.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                SmartTextRenderer(
                    text = after,
                    textColor = textColor,
                    style = baseStyle,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            return@Column
        }

        // 🎯 致命问题根因修复：上游将整段代码围栏拆成多个 Text 分片，导致每个分片都看不到完整的 ```...```。
        // 在"消息级别"先对整条 message.text 扫描并渲染所有围栏代码块，直接走自定义 CodePreview，避免依赖 parts 粒度。
        run {
            val fencedRegex = Regex("(?s)```\\s*([a-zA-Z0-9_+\\-#.]*)[ \\t]*\\r?\\n?([\\s\\S]*?)\\r?\\n?```")
            val matches = fencedRegex.findAll(cleanedText).toList()
            if (matches.isNotEmpty()) {
                var last = 0
                matches.forEachIndexed { idx, mr ->
                    val before = cleanedText.substring(last, mr.range.first)
                    if (before.isNotBlank()) {
                        SmartTextRenderer(
                            text = before,
                            textColor = textColor,
                            style = baseStyle,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    val lang = mr.groups[1]?.value?.trim().orEmpty()
                    val code = mr.groups[2]?.value ?: ""
                    CodePreview(
                        code = code,
                        language = if (lang.isBlank()) null else lang,
                        modifier = Modifier.fillMaxWidth()
                    )
                    last = mr.range.last + 1
                    if (idx != matches.lastIndex) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                // 统一使用 cleanedText，避免与上面的切片来源不一致导致错位
                if (last < cleanedText.length) {
                    val tail = cleanedText.substring(last)
                    if (tail.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        SmartTextRenderer(
                            text = tail,
                            textColor = textColor,
                            style = baseStyle,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                return@Column
            }
        }
        
        if (message.parts.isEmpty()) {
            android.util.Log.w("EnhancedMarkdownText", "⚠️ AI Message parts is EMPTY, attempting to parse math formulas")
            // 🎯 临时修复：即使parts为空，也尝试解析数学公式（仅针对AI消息）
            if (cleanedText.contains("$") || cleanedText.contains("\\")) {
                android.util.Log.d("EnhancedMarkdownText", "Found potential math content, parsing...")
                
                val parsedParts = try {
                    parseMarkdownParts(cleanedText)
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
                                        style = baseStyle,
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
                            is MarkdownPart.Table -> {
                                // 表格原生渲染（禁止 WebView/HTML）
                                SimpleTableRenderer(
                                    content = part.content,
                                    modifier = Modifier.fillMaxWidth()
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
                        text = cleanedText,
                        textColor = textColor,
                        style = baseStyle,
                        modifier = Modifier
                    )
                }
            } else {
                // 没有数学内容，使用智能渲染器
                SmartTextRenderer(
                    text = cleanedText,
                    textColor = textColor,
                    style = baseStyle,
                    modifier = Modifier
                )
            }
        } else {
            // 检查 parts 的有效性
            val hasValidParts = message.parts.any { part ->
                when (part) {
                    is MarkdownPart.Text -> part.content.isNotBlank()
                    is MarkdownPart.CodeBlock -> part.content.isNotBlank()
                    is MarkdownPart.MathBlock -> part.latex.isNotBlank() || part.content.isNotBlank()
                    is MarkdownPart.HtmlContent -> part.html.isNotBlank()
                    is MarkdownPart.Table -> part.content.isNotBlank()
                    is MarkdownPart.MixedContent -> part.content.isNotBlank()
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
                    is MarkdownPart.Table -> android.util.Log.d("EnhancedMarkdownText", "  Table: '${part.content.take(30)}...'")
                    is MarkdownPart.MixedContent -> android.util.Log.d("EnhancedMarkdownText", "  MixedContent: '${part.content.take(30)}...'")
                }
            }
            
            if (!hasValidParts && message.text.isNotBlank()) {
                // 回退到原始文本渲染，使用智能渲染器
                RenderingMonitor.logRenderingIssue(message.id, "Parts无效，回退到原始文本", cleanedText)
                SmartTextRenderer(
                    text = cleanedText,
                    textColor = textColor,
                    style = style,
                    modifier = Modifier
                )
            } else {
                // 🎯 智能检测：如果内容很短且可能被错误分割，直接合并渲染
                val shouldMergeContent = shouldMergeAllContent(message.parts, cleanedText)
                android.util.Log.d("EnhancedMarkdownText", "Should merge content: $shouldMergeContent")
                
                if (shouldMergeContent) {
                    android.util.Log.d("EnhancedMarkdownText", "🔧 检测到内容被错误分割，合并渲染")
                    // 直接使用清洗后的文本进行完整渲染，使用智能渲染器
                    SmartTextRenderer(
                        text = cleanedText,
                        textColor = textColor,
                        style = baseStyle,
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
                                    is MarkdownPart.Table -> {
                                        android.util.Log.d("EnhancedMarkdownText", "🎯 Rendering Table block (native)")
                                        SimpleTableRenderer(
                                            content = part.content,
                                            modifier = Modifier.fillMaxWidth()
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

// 轻量表格检测（前端兜底用）：存在带竖线的多行，且第二行/任一行包含 --- 分隔
private fun detectMarkdownTable(content: String): Boolean {
    val lines = content.trim().lines().filter { it.isNotBlank() }
    if (lines.size < 2) return false
    // 允许全角/框线竖线
    fun normPipes(s: String) = s.replace("｜", "|").replace("│", "|").replace("┃", "|")
    val hasPipes = lines.count { normPipes(it).contains("|") } >= 2
    if (!hasPipes) return false
    // 放宽匹配：允许分隔行后紧跟首行数据（同一行）
    val separatorRegexLoose = Regex("^\\s*\\|?\\s*:?[-]{2,}:?\\s*(\\|\\s*:?[-]{2,}:?\\s*)+\\|?\\s*")
    return lines.any { line ->
        val t = normPipes(line).trim()
        separatorRegexLoose.containsMatchIn(t)
    }
}

// 从整段文本中提取第一张 Markdown 表格，返回 (表格前文本, 表格文本, 表格后文本)
private fun splitByFirstMarkdownTable(content: String): Triple<String, String, String> {
    val rawLines = content.lines()
    fun normPipes(s: String) = s.replace("｜", "|").replace("│", "|").replace("┃", "|")
    // 放宽：分隔模式无需锚定到行尾，便于从同行中切出尾部数据
    val separatorRegexLoose = Regex("^\\s*\\|?\\s*:?[-]{2,}:?\\s*(\\|\\s*:?[-]{2,}:?\\s*)+\\|?\\s*")

    // 找到第一条分隔行（宽松匹配）
    var sepIdx = -1
    var sepMatch: MatchResult? = null
    for (i in rawLines.indices) {
        val t = normPipes(rawLines[i]).trim()
        val mr = separatorRegexLoose.find(t)
        if (mr != null) {
            sepIdx = i
            sepMatch = mr
            break
        }
    }
    if (sepIdx <= 0 || sepMatch == null) return Triple(content, "", "")

    // 表头行：向上找第一条“含管道且非空”的行
    var headerIdx = sepIdx - 1
    while (headerIdx >= 0) {
        val ht = normPipes(rawLines[headerIdx])
        if (ht.isNotBlank() && ht.contains("|")) break
        headerIdx--
    }
    if (headerIdx < 0) return Triple(content, "", "")

    // 计算 start/end，并处理“分隔行后拼接了首行数据”的尾巴
    val start = headerIdx
    val lines = rawLines.toMutableList()

    // 取分隔片段与尾部数据
    val sepLineNorm = normPipes(lines[sepIdx])
    val matchedSep = sepMatch!!.value.trim()
    val tail = sepLineNorm.substring(sepMatch!!.range.last + 1).trim()

    // 用纯分隔行替换原 sepIdx 行
    lines[sepIdx] = matchedSep

    // 如果 tail 存在，作为第一条数据行插入到 sepIdx+1
    var end = sepIdx + 1
    if (tail.isNotEmpty()) {
        val firstData = if (tail.startsWith("|")) tail else "| $tail |"
        lines.add(end, firstData)
        end++ // 指向下一行
    }

    // 继续向下吞并数据行
    while (end < lines.size) {
        val dt = normPipes(lines[end])
        if (dt.isBlank()) {
            // 空行算作表格块的终止，与后文分隔
            break
        }
        if (!dt.contains("|")) break
        end++
    }

    val before = lines.take(start).joinToString("\n").trimEnd()
    val tableBlock = lines.subList(start, end).joinToString("\n").trim()
    val after = if (end < lines.size) lines.drop(end).joinToString("\n").trimStart() else ""
    return Triple(before, tableBlock, after)
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
    // 单体渲染：去掉反引号后交给 MarkdownText，保持段落级排版稳定且无白底
    val unified = remember(text) {
        normalizeBasicMarkdownNoMath(removeInlineCodeBackticks(unwrapFileExtensionsInBackticks(text)))
    }
    MarkdownText(
        markdown = unified,
        style = style.copy(color = textColor, platformStyle = PlatformTextStyle(includeFontPadding = false)),
        modifier = Modifier.fillMaxWidth()
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

// 识别“缩进式代码块”工具：连续两行及以上以4空格或Tab开头的文本
private data class IndentedBlock(val range: IntRange, val code: String)
private data class IndentedBlocks(val blocks: List<IndentedBlock>)

private fun extractIndentedCodeBlocks(text: String): IndentedBlocks? {
    if (text.isBlank()) return null
    val lines = text.split("\n")
    val blocks = mutableListOf<IndentedBlock>()
    var i = 0
    var offset = 0
    while (i < lines.size) {
        val line = lines[i]
        val isIndented = line.startsWith("    ") || line.startsWith("\t")
        if (!isIndented) {
            offset += line.length + if (i != lines.lastIndex) 1 else 0
            i++
            continue
        }
        val startOffset = offset
        val startLine = i
        val buf = StringBuilder()
        var count = 0
        while (i < lines.size) {
            val cur = lines[i]
            if (cur.startsWith("    ") || cur.startsWith("\t")) {
                val stripped = if (cur.startsWith("\t")) cur.removePrefix("\t") else cur.removePrefix("    ")
                buf.append(stripped)
                if (i != lines.lastIndex) buf.append("\n")
                offset += cur.length + if (i != lines.lastIndex) 1 else 0
                i++
                count++
            } else {
                break
            }
        }
        if (count >= 2) {
            // 仅将“至少两行”的连续缩进行视为代码块
            val endOffsetExclusive = offset
            blocks += IndentedBlock(IntRange(startOffset, endOffsetExclusive - 1), buf.toString().trimEnd('\n'))
        } else {
            // 单行缩进回退为普通文本
        }
    }
    return if (blocks.isEmpty()) null else IndentedBlocks(blocks)
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

/**
* 宽松的围栏代码渲染器：
* - 仅依据 “```” 分割奇偶段；偶数段视为围栏头（可能包含语言与同一行代码）
* - 允许缺少结尾 ```；最后一个代码段也会用 CodePreview 渲染
* - 尽最大努力提取语言：开头行的“```lang”作为语言，紧随其后的同一行残余拼到代码正文
*/
@Composable
private fun FallbackFencedRenderer(
   raw: String,
   textColor: Color,
   style: TextStyle
) {
   val parts = raw.split("```")
   // 没有足够的片段，直接按普通文本渲染
   if (parts.size <= 1) {
       SmartTextRenderer(
           text = raw,
           textColor = textColor,
           style = style,
           modifier = Modifier.fillMaxWidth()
       )
       return
   }

   Column(modifier = Modifier.fillMaxWidth()) {
       var i = 0
       while (i < parts.size) {
           val segment = parts[i]
           if (i % 2 == 0) {
               // 非代码段
               if (segment.isNotBlank()) {
                   SmartTextRenderer(
                       text = segment,
                       textColor = textColor,
                       style = style,
                       modifier = Modifier.fillMaxWidth()
                   )
               }
           } else {
               // 代码段（可能是不规范的开头/缺少闭合）
               val lines = segment.lines()
               val header = lines.firstOrNull() ?: ""
               val lang = header.trim().takeWhile { !it.isWhitespace() }
               val restFirst = header.removePrefix(lang).trimStart()
               val body = buildString {
                   if (restFirst.isNotEmpty()) appendLine(restFirst)
                   if (lines.size > 1) {
                       append(lines.drop(1).joinToString("\n"))
                   }
               }.trimEnd()

               Spacer(modifier = Modifier.height(8.dp))
               CodePreview(
                   code = body,
                   language = if (lang.isBlank()) null else lang,
                   modifier = Modifier.fillMaxWidth()
               )
               Spacer(modifier = Modifier.height(8.dp))
           }
           i++
       }
   }
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