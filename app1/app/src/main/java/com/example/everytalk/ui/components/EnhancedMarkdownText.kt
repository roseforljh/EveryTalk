package com.example.everytalk.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jeziellago.compose.markdowntext.MarkdownText

/**
 * 表示 Markdown 内容的一个部分
 */
sealed class MarkdownPart {
    data class Text(val content: String) : MarkdownPart()
    data class CodeBlock(val content: String, val language: String = "") : MarkdownPart()
    data class MathBlock(val latex: String, val isDisplay: Boolean = true) : MarkdownPart()
    data class InlineMath(val latex: String) : MarkdownPart()
    data class HtmlContent(val html: String) : MarkdownPart()
    data class Table(val tableData: TableData) : MarkdownPart()
}

/**
 * 解析 Markdown 文本，分离代码块、数学公式和普通文本
 * 增强版：改进匹配算法，避免重叠和冲突
 */
fun parseMarkdownParts(markdown: String): List<MarkdownPart> {
    val parts = mutableListOf<MarkdownPart>()
    
    // 首先检测并处理表格
    if (detectMarkdownTable(markdown)) {
        return parseMarkdownWithTables(markdown)
    }
    
    // 定义各种块的正则表达式 - 修复版：使用贪婪匹配确保代码块完整匹配
    val codeBlockRegex = "```([a-zA-Z0-9+#-]*)?\\s*\\n?([\\s\\S]*?)(?=\\n```|$)".toRegex()
    val mathBlockRegex = "\\$\\$([\\s\\S]*?)\\$\\$".toRegex()
    val inlineMathRegex = "(?<!\\$)\\$([^\\$\\n]+?)\\$(?!\\$)".toRegex()
    
    // 收集所有匹配项并按位置排序，避免重叠
    val allMatches = mutableListOf<Triple<IntRange, String, MatchResult>>()
    
    // 首先匹配代码块（优先级最高）
    codeBlockRegex.findAll(markdown).forEach { match ->
        allMatches.add(Triple(match.range, "code", match))
    }
    
    // 然后匹配数学块，但要避免与代码块重叠
    mathBlockRegex.findAll(markdown).forEach { match ->
        val hasOverlap = allMatches.any { (range, _, _) ->
            match.range.first < range.last && match.range.last > range.first
        }
        if (!hasOverlap) {
            allMatches.add(Triple(match.range, "mathBlock", match))
        }
    }
    
    // 排序所有匹配项
    allMatches.sortBy { it.first.first }
    
    var lastIndex = 0
    allMatches.forEach { (range, type, match) ->
        // 添加匹配项前的文本
        if (range.first > lastIndex) {
            val textContent = markdown.substring(lastIndex, range.first)
            if (textContent.isNotBlank()) {
                // 在文本中查找内联数学公式，但要避免与已匹配的块重叠
                val textParts = parseInlineMathSafely(textContent, lastIndex, allMatches)
                parts.addAll(textParts)
            }
        }
        
        // 添加匹配的块
        when (type) {
            "code" -> {
                val language = match.groupValues[1].trim()
                val codeContent = match.groupValues[2].trim()
                // 确保代码内容不为空且不包含明显的非代码内容
                if (codeContent.isNotEmpty() && !codeContent.contains("```")) {
                    parts.add(MarkdownPart.CodeBlock(codeContent, language.ifEmpty { "text" }))
                }
            }
            "mathBlock" -> {
                val mathContent = match.groupValues[1].trim()
                if (mathContent.isNotEmpty()) {
                    parts.add(MarkdownPart.MathBlock(mathContent, true))
                }
            }
        }
        
        lastIndex = range.last + 1
    }
    
    // 添加最后剩余的文本
    if (lastIndex < markdown.length) {
        val textContent = markdown.substring(lastIndex)
        if (textContent.isNotBlank()) {
            val textParts = parseInlineMathSafely(textContent, lastIndex, allMatches)
            parts.addAll(textParts)
        }
    }
    
    // 如果没有找到任何块，解析整个文本中的内联数学公式
    if (parts.isEmpty()) {
        val textParts = parseInlineMath(markdown)
        parts.addAll(textParts)
    }
    
    // 后处理：合并相邻的文本部分
    return mergeAdjacentTextParts(parts)
}



/**
 * 解析包含表格的Markdown内容
 */
fun parseMarkdownWithTables(markdown: String): List<MarkdownPart> {
    val parts = mutableListOf<MarkdownPart>()
    val lines = markdown.split("\n")
    val result = mutableListOf<String>()
    var i = 0
    
    while (i < lines.size) {
        val line = lines[i].trim()
        
        // 检查是否是表格行
        if (line.contains("|") && i + 1 < lines.size) {
            val nextLine = lines[i + 1].trim()
            val separatorPattern = "^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$".toRegex()
            
            // 如果下一行是分隔符，开始处理表格
            if (separatorPattern.matches(nextLine)) {
                // 先添加之前累积的文本
                if (result.isNotEmpty()) {
                    val textContent = result.joinToString("\n")
                    if (textContent.isNotBlank()) {
                        parts.add(MarkdownPart.Text(textContent))
                    }
                    result.clear()
                }
                
                // 收集表格内容
                val tableLines = mutableListOf<String>()
                tableLines.add(line) // 表头
                tableLines.add(nextLine) // 分隔符
                i += 2
                
                // 收集表格数据行
                while (i < lines.size && lines[i].trim().contains("|")) {
                    tableLines.add(lines[i].trim())
                    i++
                }
                
                // 解析表格数据并添加为Table类型
                val tableMarkdown = tableLines.joinToString("\n")
                val tableData = parseMarkdownTable(tableMarkdown)
                if (tableData != null) {
                    parts.add(MarkdownPart.Table(tableData))
                } else {
                    // 如果解析失败，回退到文本
                    parts.add(MarkdownPart.Text(tableMarkdown))
                }
                
                i-- // 因为while循环会再次递增
            } else {
                result.add(line)
            }
        } else {
            result.add(line)
        }
        i++
    }
    
    // 添加最后剩余的文本
    if (result.isNotEmpty()) {
        val textContent = result.joinToString("\n")
        if (textContent.isNotBlank()) {
            parts.add(MarkdownPart.Text(textContent))
        }
    }
    
    return parts
}

/**
 * 解析文本中的内联数学公式
 */
fun parseInlineMath(text: String): List<MarkdownPart> {
    val parts = mutableListOf<MarkdownPart>()
    val inlineMathRegex = "(?<!\\$)\\$([^\\$\\n]+?)\\$(?!\\$)".toRegex()
    
    var lastIndex = 0
    inlineMathRegex.findAll(text).forEach { match ->
        // 添加数学公式前的文本
        if (match.range.first > lastIndex) {
            val textContent = text.substring(lastIndex, match.range.first)
            if (textContent.isNotBlank()) {
                parts.add(MarkdownPart.Text(textContent))
            }
        }
        
        // 添加内联数学公式
        val mathContent = match.groupValues[1].trim()
        if (mathContent.isNotEmpty()) {
            parts.add(MarkdownPart.InlineMath(mathContent))
        }
        
        lastIndex = match.range.last + 1
    }
    
    // 添加最后剩余的文本
    if (lastIndex < text.length) {
        val textContent = text.substring(lastIndex)
        if (textContent.isNotBlank()) {
            parts.add(MarkdownPart.Text(textContent))
        }
    }
    
    // 如果没有找到内联数学公式，返回原文本
    if (parts.isEmpty()) {
        parts.add(MarkdownPart.Text(text))
    }
    
    return parts
}

/**
 * 安全地解析内联数学公式，避免与已匹配的块重叠
 */
fun parseInlineMathSafely(
    text: String, 
    textStartIndex: Int, 
    existingMatches: List<Triple<IntRange, String, MatchResult>>
): List<MarkdownPart> {
    val parts = mutableListOf<MarkdownPart>()
    val inlineMathRegex = "(?<!\\$)\\$([^\\$\\n]+?)\\$(?!\\$)".toRegex()
    
    var lastIndex = 0
    inlineMathRegex.findAll(text).forEach { match ->
        val absoluteRange = IntRange(
            textStartIndex + match.range.first,
            textStartIndex + match.range.last
        )
        
        // 检查是否与现有匹配项重叠
        val hasOverlap = existingMatches.any { (range, _, _) ->
            absoluteRange.first < range.last && absoluteRange.last > range.first
        }
        
        if (!hasOverlap) {
            // 添加数学公式前的文本
            if (match.range.first > lastIndex) {
                val textContent = text.substring(lastIndex, match.range.first)
                if (textContent.isNotBlank()) {
                    parts.add(MarkdownPart.Text(textContent))
                }
            }
            
            // 添加数学公式
            val mathContent = match.groupValues[1].trim()
            if (mathContent.isNotEmpty()) {
                parts.add(MarkdownPart.InlineMath(mathContent))
            }
            
            lastIndex = match.range.last + 1
        }
    }
    
    // 添加最后剩余的文本
    if (lastIndex < text.length) {
        val textContent = text.substring(lastIndex)
        if (textContent.isNotBlank()) {
            parts.add(MarkdownPart.Text(textContent))
        }
    }
    
    // 如果没有找到内联数学公式，返回整个文本
    if (parts.isEmpty()) {
        parts.add(MarkdownPart.Text(text))
    }
    
    return parts
}

/**
 * 合并相邻的文本部分，优化渲染性能
 */
fun mergeAdjacentTextParts(parts: List<MarkdownPart>): List<MarkdownPart> {
    if (parts.isEmpty()) return parts
    
    val mergedParts = mutableListOf<MarkdownPart>()
    var currentTextContent = StringBuilder()
    
    parts.forEach { part ->
        when (part) {
            is MarkdownPart.Text -> {
                currentTextContent.append(part.content)
            }
            else -> {
                // 如果有累积的文本内容，先添加它
                if (currentTextContent.isNotEmpty()) {
                    mergedParts.add(MarkdownPart.Text(currentTextContent.toString()))
                    currentTextContent.clear()
                }
                // 添加非文本部分
                mergedParts.add(part)
            }
        }
    }
    
    // 添加最后剩余的文本内容
    if (currentTextContent.isNotEmpty()) {
        mergedParts.add(MarkdownPart.Text(currentTextContent.toString()))
    }
    
    return mergedParts
}

/**
 * 智能检测数学表达式并自动添加LaTeX格式
 */
fun smartDetectMathExpressions(text: String): List<MarkdownPart> {
    val parts = mutableListOf<MarkdownPart>()
    
    // 数学表达式模式：包含指数、分数、根号、积分、求和等
    val mathPatterns = listOf(
        // 指数表达式：e^x, x^2, a^{2}
        "([a-zA-Z]\\^\\{?[^\\s}]+\\}?)".toRegex(),
        // 分数表达式：a/b 在数学上下文中
        "([a-zA-Z0-9]+/[a-zA-Z0-9]+)".toRegex(),
        // 根号表达式：sqrt(...)
        "(\\u221A\\([^)]+\\))".toRegex(),
        // 积分符号：integral
        "(\\u222B[^\\s]*)".toRegex(),
        // 求和符号：sigma
        "(\\u03A3[^\\s]*)".toRegex(),
        // 希腊字母：alpha, beta, gamma, delta, pi等
        "([\\u03B1\\u03B2\\u03B3\\u03B4\\u03B5\\u03B6\\u03B7\\u03B8\\u03B9\\u03BA\\u03BB\\u03BC\\u03BD\\u03BE\\u03BF\\u03C0\\u03C1\\u03C3\\u03C4\\u03C5\\u03C6\\u03C7\\u03C8\\u03C9])".toRegex(),
        // 数学运算符：plus-minus, times, divide, less-equal, greater-equal, not-equal, approximately
        "([\\u00B1\\u00D7\\u00F7\\u2264\\u2265\\u2260\\u2248\\u221E])".toRegex(),
        // 复杂表达式：包含多个数学符号的表达式
        "([a-zA-Z0-9\\u03B1\\u03B2\\u03B3\\u03B4\\u03B5\\u03B6\\u03B7\\u03B8\\u03B9\\u03BA\\u03BB\\u03BC\\u03BD\\u03BE\\u03BF\\u03C0\\u03C1\\u03C3\\u03C4\\u03C5\\u03C6\\u03C7\\u03C8\\u03C9\\u00B1\\u00D7\\u00F7\\u2264\\u2265\\u2260\\u2248\\u221E^{}()\\[\\]/\\s]+(?:=|\\u2192|\\u2190|\\u2194)[a-zA-Z0-9\\u03B1\\u03B2\\u03B3\\u03B4\\u03B5\\u03B6\\u03B7\\u03B8\\u03B9\\u03BA\\u03BB\\u03BC\\u03BD\\u03BE\\u03BF\\u03C0\\u03C1\\u03C3\\u03C4\\u03C5\\u03C6\\u03C7\\u03C8\\u03C9\\u00B1\\u00D7\\u00F7\\u2264\\u2265\\u2260\\u2248\\u221E^{}()\\[\\]/\\s]+)".toRegex()
    )
    
    var processedText = text
    var hasMatches = false
    
    // 检查是否包含数学表达式
    for (pattern in mathPatterns) {
        if (pattern.containsMatchIn(processedText)) {
            hasMatches = true
            break
        }
    }
    
    if (hasMatches) {
        // 如果检测到数学表达式，尝试智能转换
        val convertedText = autoConvertToLatex(processedText)
        if (convertedText != processedText) {
            // 重新解析转换后的文本
            return parseInlineMath(convertedText)
        }
    }
    
    // 如果没有检测到数学表达式，返回原文本
    parts.add(MarkdownPart.Text(text))
    return parts
}

/**
 * 自动将常见的数学表达式转换为LaTeX格式
 * 增强版：支持更多数学符号和复杂表达式
 */
fun autoConvertToLatex(text: String): String {
    var result = text
    
    // 转换复杂指数：x^(a+b) -> x^{a+b}
    result = result.replace("\\^\\(([^)]+)\\)".toRegex(), "^{$1}")
    
    // 转换简单指数：x^2 -> x^2 (保持不变，但确保格式正确)
    result = result.replace("\\^([a-zA-Z0-9])".toRegex(), "^$1")
    
    // 转换分数：a/b -> \frac{a}{b} (改进版，支持更复杂的表达式)
    result = result.replace("([a-zA-Z0-9()]+)/([a-zA-Z0-9()]+)".toRegex(), "\\frac{$1}{$2}")
    
    // 转换根号：sqrt(x) -> \sqrt{x}
    result = result.replace("sqrt\\(([^)]+)\\)".toRegex(), "\\sqrt{$1}")
    result = result.replace("√\\(([^)]+)\\)".toRegex(), "\\sqrt{$1}")
    result = result.replace("√([a-zA-Z0-9]+)".toRegex(), "\\sqrt{$1}")
    
    // 转换积分符号
    result = result.replace("∫", "\\int")
    result = result.replace("∮", "\\oint")
    
    // 转换求和符号
    result = result.replace("∑", "\\sum")
    result = result.replace("∏", "\\prod")
    
    // 转换希腊字母
    result = result.replace("α", "\\alpha")
    result = result.replace("β", "\\beta")
    result = result.replace("γ", "\\gamma")
    result = result.replace("δ", "\\delta")
    result = result.replace("ε", "\\epsilon")
    result = result.replace("θ", "\\theta")
    result = result.replace("λ", "\\lambda")
    result = result.replace("μ", "\\mu")
    result = result.replace("π", "\\pi")
    result = result.replace("σ", "\\sigma")
    result = result.replace("φ", "\\phi")
    result = result.replace("ω", "\\omega")
    
    // 转换数学运算符
    result = result.replace("±", "\\pm")
    result = result.replace("∓", "\\mp")
    result = result.replace("×", "\\times")
    result = result.replace("÷", "\\div")
    result = result.replace("≠", "\\neq")
    result = result.replace("≤", "\\leq")
    result = result.replace("≥", "\\geq")
    result = result.replace("≈", "\\approx")
    result = result.replace("∞", "\\infty")
    result = result.replace("∂", "\\partial")
    result = result.replace("∇", "\\nabla")
    
    // 转换集合符号
    result = result.replace("∈", "\\in")
    result = result.replace("∉", "\\notin")
    result = result.replace("⊂", "\\subset")
    result = result.replace("⊃", "\\supset")
    result = result.replace("∪", "\\cup")
    result = result.replace("∩", "\\cap")
    result = result.replace("∅", "\\emptyset")
    
    // 转换箭头符号
    result = result.replace("→", "\\rightarrow")
    result = result.replace("←", "\\leftarrow")
    result = result.replace("↔", "\\leftrightarrow")
    result = result.replace("⇒", "\\Rightarrow")
    result = result.replace("⇐", "\\Leftarrow")
    result = result.replace("⇔", "\\Leftrightarrow")
    
    // 转换上下标：处理更复杂的情况
    result = result.replace("_\\{([^}]+)\\}".toRegex(), "_{$1}")
    result = result.replace("_([a-zA-Z0-9])".toRegex(), "_$1")
    
    return result
}

/**
 * 预处理 Markdown 文本，将内联代码块和其他格式转换为带有自定义样式的 HTML span 标签
 * 支持粗体文本、斜体文本、内联代码块、标题和表格的处理
 */
fun preprocessMarkdownForCustomCodeStyle(markdown: String, isDarkTheme: Boolean): String {
    val backgroundColor = if (isDarkTheme) "#1a1a1a" else "#ffffff"
    val textColor = if (isDarkTheme) "#ffffff" else "#000000"
    val borderColor = if (isDarkTheme) "#444444" else "#cccccc"
    
    var result = markdown
    
    // 不在这里处理表格，而是在parseMarkdownParts中处理
    // result = convertMarkdownTableToHtml(result, isDarkTheme)
    
    // 处理编号列表：1. xxx -> <ol><li>xxx</li></ol>
    // 首先处理连续的编号列表项
    val numberedListRegex = "^(\\d+\\.)\\s+(.+)$".toRegex(RegexOption.MULTILINE)
    val numberedListMatches = numberedListRegex.findAll(result).toList()
    
    if (numberedListMatches.isNotEmpty()) {
        // 按行分组处理连续的编号列表
        val lines = result.split("\n").toMutableList()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (numberedListRegex.matches(line)) {
                // 找到编号列表的开始
                val listItems = mutableListOf<String>()
                var j = i
                
                // 收集连续的编号列表项
                while (j < lines.size && numberedListRegex.matches(lines[j])) {
                    val match = numberedListRegex.find(lines[j])!!
                    val content = match.groupValues[2]
                    listItems.add("<li style=\"margin:4px 0;word-wrap:break-word;overflow-wrap:break-word;white-space:normal;\">$content</li>")
                    j++
                }
                
                // 替换为HTML有序列表
                val listHtml = "<ol style=\"color:$textColor;margin:8px 0;padding-left:20px;word-wrap:break-word;overflow-wrap:break-word;white-space:normal;\">" + 
                              listItems.joinToString("") + 
                              "</ol>"
                
                // 替换原始行
                for (k in i until j) {
                    lines[k] = if (k == i) listHtml else ""
                }
                
                i = j
            } else {
                i++
            }
        }
        
        result = lines.filter { it.isNotEmpty() }.joinToString("\n")
    }
    
    // 处理标题：### -> <h3>, ## -> <h2>, # -> <h1>
    result = result.replace("^#{6}\\s+(.+)$".toRegex(RegexOption.MULTILINE)) { matchResult ->
        val titleContent = matchResult.groupValues[1]
        "<h6 style=\"color:$textColor;margin:8px 0;\">$titleContent</h6>"
    }
    result = result.replace("^#{5}\\s+(.+)$".toRegex(RegexOption.MULTILINE)) { matchResult ->
        val titleContent = matchResult.groupValues[1]
        "<h5 style=\"color:$textColor;margin:8px 0;\">$titleContent</h5>"
    }
    result = result.replace("^#{4}\\s+(.+)$".toRegex(RegexOption.MULTILINE)) { matchResult ->
        val titleContent = matchResult.groupValues[1]
        "<h4 style=\"color:$textColor;margin:8px 0;\">$titleContent</h4>"
    }
    result = result.replace("^#{3}\\s+(.+)$".toRegex(RegexOption.MULTILINE)) { matchResult ->
        val titleContent = matchResult.groupValues[1]
        "<h3 style=\"color:$textColor;margin:8px 0;\">$titleContent</h3>"
    }
    result = result.replace("^#{2}\\s+(.+)$".toRegex(RegexOption.MULTILINE)) { matchResult ->
        val titleContent = matchResult.groupValues[1]
        "<h2 style=\"color:$textColor;margin:10px 0;\">$titleContent</h2>"
    }
    result = result.replace("^#{1}\\s+(.+)$".toRegex(RegexOption.MULTILINE)) { matchResult ->
        val titleContent = matchResult.groupValues[1]
        "<h1 style=\"color:$textColor;margin:12px 0;\">$titleContent</h1>"
    }
    
    // 处理内联代码块：`code`
    val inlineCodeRegex = "(?<!`)`([^`\n]+)`(?!`)".toRegex()
    result = result.replace(inlineCodeRegex) { matchResult ->
        val codeContent = matchResult.groupValues[1]
        "<span style=\"background-color:$backgroundColor;color:$textColor;font-weight:bold;padding:2px 4px;border-radius:3px;\">$codeContent</span>"
    }
    
    // 处理粗体文本：**text**
    val boldRegex = "\\*\\*([^*\n]+?)\\*\\*".toRegex()
    result = result.replace(boldRegex) { matchResult ->
        val boldContent = matchResult.groupValues[1]
        "<b>$boldContent</b>"
    }
    
    // 处理斜体文本：*text*（但不匹配**text**）
    val italicRegex = "(?<!\\*)\\*([^*\n]+?)\\*(?!\\*)".toRegex()
    result = result.replace(italicRegex) { matchResult ->
        val italicContent = matchResult.groupValues[1]
        "<i>$italicContent</i>"
    }
    
    return result
}

/**
 * 增强的 Markdown 文本组件
 * 使用混合方案：普通文本用 compose-markdown 渲染，多行代码块用 CodePreview 组件渲染
 * 支持自定义内联代码块样式和多行代码块样式
 * 优化表格渲染，避免闪白问题
 */
@Composable
fun EnhancedMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    delayMs: Long = 0L
) {
    val isDarkTheme = isSystemInDarkTheme()
    
    // 根据主题设置数学公式的正确颜色
    val mathTextColor = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        Color.Black // 浅色主题使用纯黑色
    } else {
        Color.White // 深色主题使用纯白色
    }
    
    // 使用记忆化来避免不必要的重新解析
    val parts = remember(markdown) {
        parseMarkdownParts(markdown)
    }
    
    // 使用记忆化来避免不必要的预处理
    val processedParts = remember(parts, isDarkTheme) {
        parts.map { part ->
            when (part) {
                is MarkdownPart.Text -> {
                    part.copy(content = preprocessMarkdownForCustomCodeStyle(part.content, isDarkTheme))
                }
                else -> part
            }
        }
    }
    
    Column(modifier = modifier) {
        processedParts.forEachIndexed { index, part ->
            key("${part.hashCode()}_$index") { // 使用key来优化重组
                when (part) {
                    is MarkdownPart.Text -> {
                        // 使用稳定的组合来避免重新渲染
                        StableMarkdownText(
                            markdown = part.content,
                            style = style.copy(color = color)
                        )
                    }
                    is MarkdownPart.CodeBlock -> {
                        CodePreview(
                            code = part.content,
                            language = part.language.ifEmpty { "text" }
                        )
                    }
                    is MarkdownPart.MathBlock -> {
                        MathView(
                            latex = part.latex,
                            isDisplay = part.isDisplay,
                            textColor = mathTextColor,
                            delayMs = delayMs
                        )
                    }
                    is MarkdownPart.InlineMath -> {
                        MathView(
                            latex = part.latex,
                            isDisplay = false,
                            textColor = mathTextColor,
                            delayMs = delayMs
                        )
                    }
                    is MarkdownPart.HtmlContent -> {
                        HtmlView(
                            htmlContent = part.html,
                            textColor = color
                        )
                    }
                    is MarkdownPart.Table -> {
                        ComposeTable(
                            tableData = part.tableData,
                            modifier = Modifier.fillMaxWidth(),
                            delayMs = delayMs
                        )
                    }
                }
            }
            
            // 添加间距，除了最后一个元素
            if (index < processedParts.size - 1) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/**
 * 稳定的MarkdownText组件，使用记忆化来避免不必要的重新渲染
 * 特别优化表格渲染性能
 */
@Composable
private fun StableMarkdownText(
    markdown: String,
    style: TextStyle
) {
    // 检测是否包含表格 - 改进的检测逻辑
    val hasTable = remember(markdown) {
        detectMarkdownTable(markdown)
    }
    
    // 使用记忆化的内容哈希来避免不必要的重新渲染
    val contentHash = remember(markdown) { markdown.hashCode() }
    
    // 对于表格内容，使用key来确保稳定的渲染
     if (hasTable) {
         key(contentHash) {
             MarkdownText(
                 markdown = markdown,
                 style = style
             )
         }
     } else {
        // 对于普通内容，直接渲染
        MarkdownText(
            markdown = markdown,
            style = style
        )
    }
}

/**
 * 将Markdown表格转换为HTML表格
 */
fun convertMarkdownTableToHtml(markdown: String, isDarkTheme: Boolean): String {
    if (!detectMarkdownTable(markdown)) {
        return markdown
    }
    
    val lines = markdown.split("\n").toMutableList()
    val result = mutableListOf<String>()
    var i = 0
    
    while (i < lines.size) {
        val line = lines[i].trim()
        
        // 检查是否是表格行
        if (line.contains("|") && i + 1 < lines.size) {
            val nextLine = lines[i + 1].trim()
            val separatorPattern = "^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$".toRegex()
            
            // 如果下一行是分隔符，开始处理表格
            if (separatorPattern.matches(nextLine)) {
                val tableHtml = buildString {
                    val tableStyle = if (isDarkTheme) {
                        "border-collapse: collapse; width: 100%; color: #ffffff; background-color: #1a1a1a;"
                    } else {
                        "border-collapse: collapse; width: 100%; color: #000000; background-color: #ffffff;"
                    }
                    val cellStyle = if (isDarkTheme) {
                        "border: 1px solid #444444; padding: 8px; text-align: left;"
                    } else {
                        "border: 1px solid #cccccc; padding: 8px; text-align: left;"
                    }
                    val headerStyle = if (isDarkTheme) {
                        "border: 1px solid #444444; padding: 8px; text-align: left; background-color: #333333; font-weight: bold;"
                    } else {
                        "border: 1px solid #cccccc; padding: 8px; text-align: left; background-color: #f5f5f5; font-weight: bold;"
                    }
                    
                    append("<table style=\"$tableStyle\">")
                    
                    // 处理表头
                    val headerCells = line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                    if (headerCells.isNotEmpty()) {
                        append("<thead><tr>")
                        headerCells.forEach { cell ->
                            append("<th style=\"$headerStyle\">$cell</th>")
                        }
                        append("</tr></thead>")
                    }
                    
                    // 跳过分隔符行
                    i += 2
                    
                    // 处理表格数据行
                    append("<tbody>")
                    while (i < lines.size && lines[i].trim().contains("|")) {
                        val dataLine = lines[i].trim()
                        val dataCells = dataLine.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                        if (dataCells.isNotEmpty()) {
                            append("<tr>")
                            dataCells.forEach { cell ->
                                append("<td style=\"$cellStyle\">$cell</td>")
                            }
                            append("</tr>")
                        }
                        i++
                    }
                    append("</tbody></table>")
                }
                
                result.add(tableHtml)
                i-- // 因为while循环会再次递增
            } else {
                result.add(line)
            }
        } else {
            result.add(line)
        }
        i++
    }
    
    return result.joinToString("\n")
}

/**
 * 检测Markdown文本中是否包含表格
 * 改进的检测逻辑，支持多种表格格式
 */
fun detectMarkdownTable(markdown: String): Boolean {
    val lines = markdown.split("\n")
    
    // 检查是否有表格分隔符行（如 |---|---|---| 或 | --- | --- | --- |）
    val separatorPattern = "^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$".toRegex()
    
    for (i in lines.indices) {
        val line = lines[i].trim()
        
        // 检查当前行是否是分隔符行
        if (separatorPattern.matches(line)) {
            // 检查分隔符行的前一行是否包含管道符（表头）
            if (i > 0) {
                val prevLine = lines[i - 1].trim()
                if (prevLine.contains("|") && prevLine.split("|").size >= 2) {
                    return true
                }
            }
        }
        
        // 检查是否有连续的包含管道符的行（至少2行）
        if (line.contains("|") && line.split("|").size >= 3) {
            // 检查下一行是否也包含管道符或分隔符
            if (i < lines.size - 1) {
                val nextLine = lines[i + 1].trim()
                if (nextLine.contains("|") || separatorPattern.matches(nextLine)) {
                    return true
                }
            }
        }
    }
    
    return false
}

/**
 * 兼容性包装器，保持与现有代码的兼容性
 * 可以逐步迁移到 EnhancedMarkdownText
 */
@Composable
fun ImprovedMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodyLarge
) {
    // 使用增强版本
    EnhancedMarkdownText(
        markdown = markdown,
        modifier = modifier,
        color = color,
        style = style
    )
}