package com.example.everytalk.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
}

/**
 * 解析 Markdown 文本，分离代码块、数学公式和普通文本
 */
fun parseMarkdownParts(markdown: String): List<MarkdownPart> {
    val parts = mutableListOf<MarkdownPart>()
    
    // 定义各种块的正则表达式
    val codeBlockRegex = "```([a-zA-Z0-9+#-]*)?\\s*([\\s\\S]*?)\\s*```".toRegex()
    val mathBlockRegex = "\\$\\$([\\s\\S]*?)\\$\\$".toRegex()
    val inlineMathRegex = "\\$([^\\$\\n]+)\\$".toRegex()
    
    // 收集所有匹配项并按位置排序
    val allMatches = mutableListOf<Triple<IntRange, String, MatchResult>>()
    
    codeBlockRegex.findAll(markdown).forEach { match ->
        allMatches.add(Triple(match.range, "code", match))
    }
    
    mathBlockRegex.findAll(markdown).forEach { match ->
        allMatches.add(Triple(match.range, "mathBlock", match))
    }
    
    // 排序所有匹配项
    allMatches.sortBy { it.first.first }
    
    var lastIndex = 0
    allMatches.forEach { (range, type, match) ->
        // 添加匹配项前的文本
        if (range.first > lastIndex) {
            val textContent = markdown.substring(lastIndex, range.first)
            if (textContent.isNotBlank()) {
                // 在文本中查找内联数学公式
                val textParts = parseInlineMath(textContent)
                parts.addAll(textParts)
            }
        }
        
        // 添加匹配的块
        when (type) {
            "code" -> {
                val language = match.groupValues[1]
                val codeContent = match.groupValues[2]
                parts.add(MarkdownPart.CodeBlock(codeContent, language))
            }
            "mathBlock" -> {
                val mathContent = match.groupValues[1].trim()
                parts.add(MarkdownPart.MathBlock(mathContent, true))
            }
        }
        
        lastIndex = range.last + 1
    }
    
    // 添加最后剩余的文本
    if (lastIndex < markdown.length) {
        val textContent = markdown.substring(lastIndex)
        if (textContent.isNotBlank()) {
            val textParts = parseInlineMath(textContent)
            parts.addAll(textParts)
        }
    }
    
    // 如果没有找到任何块，解析整个文本中的内联数学公式
    if (parts.isEmpty()) {
        val textParts = parseInlineMath(markdown)
        parts.addAll(textParts)
    }
    
    return parts
}



/**
 * 解析文本中的内联数学公式
 */
fun parseInlineMath(text: String): List<MarkdownPart> {
    val parts = mutableListOf<MarkdownPart>()
    val inlineMathRegex = "\\$([^\\$\\n]+)\\$".toRegex()
    
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
        parts.add(MarkdownPart.InlineMath(mathContent))
        
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
        // 根号表达式：√(...)
        "(√\\([^)]+\\))".toRegex(),
        // 积分符号：∫
        "(∫[^\\s]*)".toRegex(),
        // 求和符号：Σ
        "(Σ[^\\s]*)".toRegex(),
        // 希腊字母：α, β, γ, δ, π等
        "([αβγδεζηθικλμνξοπρστυφχψω])".toRegex(),
        // 数学运算符：±, ×, ÷, ≤, ≥, ≠, ≈
        "([±×÷≤≥≠≈∞])".toRegex(),
        // 复杂表达式：包含多个数学符号的表达式
        "([a-zA-Z0-9αβγδεζηθικλμνξοπρστυφχψω±×÷≤≥≠≈∞^{}()\\[\\]/\\s]+(?:=|→|←|↔)[a-zA-Z0-9αβγδεζηθικλμνξοπρστυφχψω±×÷≤≥≠≈∞^{}()\\[\\]/\\s]+)".toRegex()
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
 */
fun autoConvertToLatex(text: String): String {
    var result = text
    
    // 转换指数表达式
    result = result.replace("([a-zA-Z])\\^([0-9]+)".toRegex()) { match ->
        val base = match.groupValues[1]
        val exponent = match.groupValues[2]
        "\$${base}^{${exponent}}\$"
    }
    
    // 转换复杂指数表达式
    result = result.replace("([a-zA-Z])\\^\\{([^}]+)\\}".toRegex()) { match ->
        val base = match.groupValues[1]
        val exponent = match.groupValues[2]
        "\$${base}^{${exponent}}\$"
    }
    
    // 转换分数表达式（在数学上下文中）
    result = result.replace("([a-zA-Z0-9]+)/([a-zA-Z0-9]+)".toRegex()) { match ->
        val numerator = match.groupValues[1]
        val denominator = match.groupValues[2]
        "\$\\frac{${numerator}}{${denominator}}\$"
    }
    
    // 转换根号表达式
    result = result.replace("√\\(([^)]+)\\)".toRegex()) { match ->
        val content = match.groupValues[1]
        "\$\\sqrt{${content}}\$"
    }
    
    // 转换积分表达式
    result = result.replace("∫([^\\s]*)".toRegex()) { match ->
        val content = match.groupValues[1]
        "\$\\int ${content}\$"
    }
    
    // 转换求和表达式
    result = result.replace("Σ([^\\s]*)".toRegex()) { match ->
        val content = match.groupValues[1]
        "\$\\sum ${content}\$"
    }
    
    // 转换等式表达式
    result = result.replace("([a-zA-Z0-9αβγδεζηθικλμνξοπρστυφχψω±×÷≤≥≠≈∞^{}()\\[\\]/\\s]+)\\s*=\\s*([a-zA-Z0-9αβγδεζηθικλμνξοπρστυφχψω±×÷≤≥≠≈∞^{}()\\[\\]/\\s]+)".toRegex()) { match ->
        val left = match.groupValues[1].trim()
        val right = match.groupValues[2].trim()
        "\$${left} = ${right}\$"
    }
    
    return result
}

/**
 * 预处理 Markdown 文本，将内联代码块转换为带有自定义样式的 HTML span 标签
 */
fun preprocessMarkdownForCustomCodeStyle(markdown: String, isDarkTheme: Boolean): String {
    val backgroundColor = if (isDarkTheme) "#1a1a1a" else "#ffffff"
    val textColor = if (isDarkTheme) "#ffffff" else "#000000"
    
    // 使用正则表达式匹配内联代码块，避免匹配多行代码块
    // 负向前瞻和负向后瞻确保不是三个反引号的一部分
    val inlineCodeRegex = "(?<!`)`([^`\n]+)`(?!`)".toRegex()
    
    return markdown.replace(inlineCodeRegex) { matchResult ->
        val codeContent = matchResult.groupValues[1]
        "<span style=\"background-color:$backgroundColor;color:$textColor;font-weight:bold;padding:2px 4px;border-radius:3px;\">$codeContent</span>"
    }
}

/**
 * 增强的 Markdown 文本组件
 * 使用混合方案：普通文本用 compose-markdown 渲染，多行代码块用 CodePreview 组件渲染
 * 支持自定义内联代码块样式和多行代码块样式
 */
@Composable
fun EnhancedMarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodyLarge
) {
    val isDarkTheme = isSystemInDarkTheme()
    val parts = parseMarkdownParts(markdown)
    
    Column(modifier = modifier) {
        parts.forEachIndexed { index, part ->
            when (part) {
                is MarkdownPart.Text -> {
                    val processedMarkdown = preprocessMarkdownForCustomCodeStyle(part.content, isDarkTheme)
                    MarkdownText(
                        markdown = processedMarkdown,
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
                        textColor = color
                    )
                }
                is MarkdownPart.InlineMath -> {
                    MathView(
                        latex = part.latex,
                        isDisplay = false,
                        textColor = color
                    )
                }
            }
            
            // 添加间距，除了最后一个元素
            if (index < parts.size - 1) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
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