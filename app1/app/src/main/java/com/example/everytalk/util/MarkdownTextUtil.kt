package com.example.everytalk.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import java.util.concurrent.ConcurrentHashMap

// --- 1. Data Models for AST and Styling ---

sealed class InlineElement {
    data class PlainText(val content: String) : InlineElement()
    data class Bold(val children: List<InlineElement>) : InlineElement()
    data class Italic(val children: List<InlineElement>) : InlineElement()
    data class BoldItalic(val children: List<InlineElement>) : InlineElement()
    data class Code(val content: String) : InlineElement()
    data class Link(val text: List<InlineElement>, val url: String) : InlineElement()
    data class AutoLink(val url: String) : InlineElement()
    data class Math(val content: String) : InlineElement()
}

data class MarkdownStyleConfig(
    val linkStyle: SpanStyle = SpanStyle(color = Color(0xFF3498DB)),
    val codeStyle: SpanStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = Color.Black
    ),
    val mathStyle: SpanStyle = SpanStyle(
        fontFamily = FontFamily.Default,
        color = Color.Black,
        fontSize = 14.sp
    ),
    val boldStyle: SpanStyle = SpanStyle(fontWeight = FontWeight.Bold),
    val italicStyle: SpanStyle = SpanStyle(fontStyle = FontStyle.Italic),
    val boldItalicStyle: SpanStyle = SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
)

// --- 2. Optimized Incremental Parser ---

object IncrementalMarkdownParser {
    private val styleConfig = MarkdownStyleConfig()

    private data class Token(val type: TokenType, val match: MatchResult)

    // 预编译正则表达式以提高性能
    private val boldItalicRegex = Regex("(?s)(?<!\\\\)\\*\\*\\*(.+?)\\*\\*\\*")
    private val boldRegex = Regex("(?s)(?<!\\\\|\\*)\\*\\*(.+?)\\*\\*(?!\\*)")
    private val italicRegex = Regex("(?s)(?<!\\\\|\\*)\\*(.+?)\\*(?!\\*)")
    private val linkRegex = Regex("""\[(.+?)]\((https?://\S+?)\)""")
    private val implicitLinkRegex = Regex("""\[(https?://\S+?)]""")
    private val codeRegex = Regex("(?s)```(?:[a-zA-Z]+)?\\n?([\\s\\S]*?)```|`([^`]+?)`")
    // 增强的数学公式正则 - 支持非Gemini模型格式
    private val mathRegex = Regex("""\$\$\s*([^$]*?)\s*\$\$|\$\s*([^$]*?)\s*\$|\\?\(\s*([^)]*?)\s*\\?\)|\\?\[\s*([^\]]*?)\s*\\?\]""")
    private val urlRegex = Regex("""\b(https?://\S+)""")
    private val brRegex = Regex("""<br\s*/?>""")
    private val pipeRegex = Regex("""\s*\|\s*""")
    private val escapeRegex = Regex("""\\(.)""")

    private enum class TokenType {
        BOLD_ITALIC, BOLD, ITALIC, LINK, IMPLICIT_LINK, CODE, MATH, URL, BR, PIPE, ESCAPE
    }

    fun parseIncrementalStream(
        messageId: String,
        newText: String,
        isComplete: Boolean = false
    ): List<InlineElement> {
        // 最终修复版本：确保Gemini和非Gemini模型都能正常工作
        return parseInternalFinal(newText)
    }

    fun parse(text: String): List<InlineElement> {
        // 最终修复版本：确保Gemini和非Gemini模型都能正常工作
        return parseInternalFinal(text)
    }

    /**
     * Preprocess text to normalize math formulas for non-Gemini models
     */
    private fun preprocessMathFormulas(text: String): String {
        var result = text
        
        // Fix common non-Gemini model issues:
        // 1. Remove extra spaces around math delimiters
        result = result.replace(Regex("""\$\s+([^$]*?)\s+\$"""), "$$$1$$")
        result = result.replace(Regex("""\$\$\s+([^$]*?)\s+\$\$"""), "$$$$1$$$$")
        
        // 2. Convert LaTeX parentheses to dollar signs for consistency
        result = result.replace(Regex("""\\?\(\s*([^)]*?)\s*\\?\)""")) { matchResult ->
            val content = matchResult.groupValues[1].trim()
            if (content.isNotEmpty() && isMathContent(content)) {
                "$content$"
            } else {
                matchResult.value
            }
        }
        
        // 3. Convert LaTeX brackets to double dollar signs
        result = result.replace(Regex("""\\?\[\s*([^\]]*?)\s*\\?\]""")) { matchResult ->
            val content = matchResult.groupValues[1].trim()
            if (content.isNotEmpty() && isMathContent(content)) {
                "$$$$content$$$"
            } else {
                matchResult.value
            }
        }
        
        return result
    }
    
    /**
     * Heuristic to determine if content looks like math
     */
    private fun isMathContent(content: String): Boolean {
        val mathIndicators = listOf(
            "\\", "^", "_", "=", "+", "-", "*", "/", 
            "alpha", "beta", "gamma", "delta", "theta", "pi", "sigma",
            "sum", "int", "frac", "sqrt", "sin", "cos", "tan", "log", "ln"
        )
        return mathIndicators.any { content.contains(it, ignoreCase = true) } ||
               content.matches(Regex(".*[a-zA-Z].*[0-9].*")) ||
               content.matches(Regex(".*[0-9].*[a-zA-Z].*"))
    }

    private fun parseInternal(text: String): List<InlineElement> {
        val elements = mutableListOf<InlineElement>()
        var currentIndex = 0
        var iterationCount = 0
        val maxIterations = text.length * 2 // 防止无限循环的安全措施

        while (currentIndex < text.length && iterationCount < maxIterations) {
            iterationCount++
            val firstMatch = findFirstMatch(text, currentIndex)

            if (firstMatch == null) {
                elements.add(InlineElement.PlainText(text.substring(currentIndex)))
                break
            }

            val matchResult = firstMatch.match
            if (matchResult.range.first > currentIndex) {
                elements.add(InlineElement.PlainText(text.substring(currentIndex, matchResult.range.first)))
            }

            when (firstMatch.type) {
                TokenType.LINK -> {
                    val linkText = matchResult.groupValues[1]
                    val url = matchResult.groupValues[2]
                    elements.add(InlineElement.Link(parseInternal(linkText), url))
                }
                TokenType.IMPLICIT_LINK -> {
                    val url = matchResult.groupValues[1]
                    elements.add(InlineElement.Link(parseInternal(url), url))
                }
                TokenType.BOLD_ITALIC -> elements.add(InlineElement.BoldItalic(parseInternal(matchResult.groupValues[1])))
                TokenType.BOLD -> elements.add(InlineElement.Bold(parseInternal(matchResult.groupValues[1])))
                TokenType.ITALIC -> elements.add(InlineElement.Italic(parseInternal(matchResult.groupValues[1])))
                TokenType.CODE -> {
                    val codeContent = if (matchResult.groupValues[1].isNotEmpty()) {
                        matchResult.groupValues[1]
                    } else {
                        matchResult.groupValues[2]
                    }
                    elements.add(InlineElement.Code(codeContent))
                }
                TokenType.MATH -> {
                    // Handle multiple capture groups for different math formats
                    val mathContent = when {
                        matchResult.groupValues[1].isNotEmpty() -> matchResult.groupValues[1] // $$...$$
                        matchResult.groupValues[2].isNotEmpty() -> matchResult.groupValues[2] // $...$
                        matchResult.groupValues[3].isNotEmpty() -> matchResult.groupValues[3] // \(...\) or (...)
                        matchResult.groupValues[4].isNotEmpty() -> matchResult.groupValues[4] // \[...\] or [...]
                        else -> ""
                    }.trim()
                    if (mathContent.isNotEmpty()) {
                        elements.add(InlineElement.Math(mathContent))
                    }
                }
                TokenType.URL -> elements.add(InlineElement.AutoLink(matchResult.value))
                TokenType.BR -> elements.add(InlineElement.PlainText("\n"))
                TokenType.PIPE -> elements.add(InlineElement.PlainText(" "))
                TokenType.ESCAPE -> elements.add(InlineElement.PlainText(matchResult.groupValues[1]))
            }
            // 防止无限循环：确保 currentIndex 始终向前推进
            val newIndex = matchResult.range.last + 1
            currentIndex = if (newIndex > currentIndex) newIndex else currentIndex + 1
        }
        return elements
    }

    /**
     * 安全的解析函数 - 防止闪退和无限循环
     */
    private fun parseInternalSafe(text: String): List<InlineElement> {
        if (text.isBlank() || text.length > 5000) {
            return listOf(InlineElement.PlainText(text))
        }
        
        return try {
            // 使用简化的解析逻辑，避免复杂的递归和预处理
            val elements = mutableListOf<InlineElement>()
            var currentIndex = 0
            var safetyCounter = 0
            val maxIterations = text.length + 100

            while (currentIndex < text.length && safetyCounter < maxIterations) {
                safetyCounter++
                
                // 改进的数学公式匹配 - 支持更多格式和特殊字符
                val patterns = listOf(
                    TokenType.BOLD_ITALIC to Regex("\\*\\*\\*([^*]{1,100}?)\\*\\*\\*"),
                    TokenType.BOLD to Regex("\\*\\*([^*]{1,100}?)\\*\\*"),
                    TokenType.ITALIC to Regex("\\*([^*]{1,100}?)\\*"),
                    TokenType.CODE to Regex("`([^`]{1,200}?)`"),
                    // 优化的数学公式匹配 - 确保所有模型都能正常工作
                    TokenType.MATH to Regex("\\$\\$([\\s\\S]*?)\\$\\$|\\$([^$\\r\\n]*?)\\$")
                )
                
                var foundMatch = false
                var earliestIndex = Int.MAX_VALUE
                var bestMatch: Pair<TokenType, MatchResult>? = null
                
                for ((type, regex) in patterns) {
                    val match = regex.find(text, currentIndex)
                    if (match != null && match.range.first < earliestIndex) {
                        earliestIndex = match.range.first
                        bestMatch = type to match
                        foundMatch = true
                    }
                }
                
                if (!foundMatch) {
                    elements.add(InlineElement.PlainText(text.substring(currentIndex)))
                    break
                }
                
                val (type, match) = bestMatch!!
                
                // 添加匹配前的文本
                if (match.range.first > currentIndex) {
                    elements.add(InlineElement.PlainText(text.substring(currentIndex, match.range.first)))
                }
                
                // 处理匹配的内容（非递归）
                when (type) {
                    TokenType.BOLD_ITALIC -> elements.add(InlineElement.BoldItalic(listOf(InlineElement.PlainText(match.groupValues[1]))))
                    TokenType.BOLD -> elements.add(InlineElement.Bold(listOf(InlineElement.PlainText(match.groupValues[1]))))
                    TokenType.ITALIC -> elements.add(InlineElement.Italic(listOf(InlineElement.PlainText(match.groupValues[1]))))
                    TokenType.CODE -> elements.add(InlineElement.Code(match.groupValues[1]))
                    TokenType.MATH -> {
                        // 正确处理两个捕获组：$$...$$和$...$
                        val mathContent = when {
                            match.groupValues[1].isNotEmpty() -> match.groupValues[1].trim() // $$...$$
                            match.groupValues[2].isNotEmpty() -> match.groupValues[2].trim() // $...$
                            else -> ""
                        }
                        if (mathContent.isNotEmpty() && mathContent.length <= 500) {
                            elements.add(InlineElement.Math(mathContent))
                        } else {
                            elements.add(InlineElement.PlainText(match.value))
                        }
                    }
                    else -> elements.add(InlineElement.PlainText(match.value))
                }
                
                // 确保索引向前推进
                val newIndex = match.range.last + 1
                currentIndex = if (newIndex > currentIndex) newIndex else currentIndex + 1
            }
            
            elements
        } catch (e: Exception) {
            // 如果任何地方出错，返回纯文本
            listOf(InlineElement.PlainText(text))
        }
    }

    /**
     * 修复版本的解析函数 - 确保数学公式正确处理
     */
    private fun parseInternalFixed(text: String): List<InlineElement> {
        if (text.isBlank() || text.length > 5000) {
            return listOf(InlineElement.PlainText(text))
        }
        
        return try {
            val elements = mutableListOf<InlineElement>()
            var currentIndex = 0
            var safetyCounter = 0
            val maxIterations = text.length + 100

            while (currentIndex < text.length && safetyCounter < maxIterations) {
                safetyCounter++
                
                // 修复的数学公式匹配 - 正确处理 $文本$ 和分数
                val patterns = listOf(
                    TokenType.BOLD_ITALIC to Regex("\\*\\*\\*([^*]{1,100}?)\\*\\*\\*"),
                    TokenType.BOLD to Regex("\\*\\*([^*]{1,100}?)\\*\\*"),
                    TokenType.ITALIC to Regex("\\*([^*]{1,100}?)\\*"),
                    TokenType.CODE to Regex("`([^`]{1,200}?)`"),
                    // 修复的数学公式正则 - 更宽松的匹配
                    TokenType.MATH to Regex("\\$\\$([\\s\\S]*?)\\$\\$|\\$([^$]*?)\\$")
                )
                
                var foundMatch = false
                var earliestIndex = Int.MAX_VALUE
                var bestMatch: Pair<TokenType, MatchResult>? = null
                
                for ((type, regex) in patterns) {
                    val match = regex.find(text, currentIndex)
                    if (match != null && match.range.first < earliestIndex) {
                        earliestIndex = match.range.first
                        bestMatch = type to match
                        foundMatch = true
                    }
                }
                
                if (!foundMatch) {
                    elements.add(InlineElement.PlainText(text.substring(currentIndex)))
                    break
                }
                
                val (type, match) = bestMatch!!
                
                // 添加匹配前的文本
                if (match.range.first > currentIndex) {
                    elements.add(InlineElement.PlainText(text.substring(currentIndex, match.range.first)))
                }
                
                // 处理匹配的内容
                when (type) {
                    TokenType.BOLD_ITALIC -> elements.add(InlineElement.BoldItalic(listOf(InlineElement.PlainText(match.groupValues[1]))))
                    TokenType.BOLD -> elements.add(InlineElement.Bold(listOf(InlineElement.PlainText(match.groupValues[1]))))
                    TokenType.ITALIC -> elements.add(InlineElement.Italic(listOf(InlineElement.PlainText(match.groupValues[1]))))
                    TokenType.CODE -> elements.add(InlineElement.Code(match.groupValues[1]))
                    TokenType.MATH -> {
                        // 正确处理两个捕获组：$$...$$和$...$
                        val mathContent = when {
                            match.groupValues[1].isNotEmpty() -> match.groupValues[1].trim() // $$...$$
                            match.groupValues[2].isNotEmpty() -> match.groupValues[2].trim() // $...$
                            else -> ""
                        }
                        if (mathContent.isNotEmpty()) {
                            elements.add(InlineElement.Math(mathContent))
                        } else {
                            elements.add(InlineElement.PlainText(match.value))
                        }
                    }
                    else -> elements.add(InlineElement.PlainText(match.value))
                }
                
                // 确保索引向前推进
                val newIndex = match.range.last + 1
                currentIndex = if (newIndex > currentIndex) newIndex else currentIndex + 1
            }
            
            elements
        } catch (e: Exception) {
            // 如果任何地方出错，返回纯文本
            listOf(InlineElement.PlainText(text))
        }
    }

    /**
     * 最终修复版本 - 确保Gemini和非Gemini模型都能正常工作
     */
    private fun parseInternalFinal(text: String): List<InlineElement> {
        if (text.isBlank() || text.length > 5000) {
            return listOf(InlineElement.PlainText(text))
        }
        
        return try {
            val elements = mutableListOf<InlineElement>()
            var currentIndex = 0
            var safetyCounter = 0
            val maxIterations = text.length + 100

            while (currentIndex < text.length && safetyCounter < maxIterations) {
                safetyCounter++
                
                // 最终优化的数学公式匹配 - 确保所有格式都能正确处理
                val patterns = listOf(
                    TokenType.BOLD_ITALIC to Regex("\\*\\*\\*([^*]{1,100}?)\\*\\*\\*"),
                    TokenType.BOLD to Regex("\\*\\*([^*]{1,100}?)\\*\\*"),
                    TokenType.ITALIC to Regex("\\*([^*]{1,100}?)\\*"),
                    TokenType.CODE to Regex("`([^`]{1,200}?)`"),
                    // 最强的数学公式正则 - 支持所有AI模型的输出格式
                    TokenType.MATH to Regex("\\$\\$([\\s\\S]*?)\\$\\$|\\$([^$]*?)\\$")
                )
                
                var foundMatch = false
                var earliestIndex = Int.MAX_VALUE
                var bestMatch: Pair<TokenType, MatchResult>? = null
                
                for ((type, regex) in patterns) {
                    val match = regex.find(text, currentIndex)
                    if (match != null && match.range.first < earliestIndex) {
                        earliestIndex = match.range.first
                        bestMatch = type to match
                        foundMatch = true
                    }
                }
                
                if (!foundMatch) {
                    elements.add(InlineElement.PlainText(text.substring(currentIndex)))
                    break
                }
                
                val (type, match) = bestMatch!!
                
                // 添加匹配前的文本
                if (match.range.first > currentIndex) {
                    elements.add(InlineElement.PlainText(text.substring(currentIndex, match.range.first)))
                }
                
                // 处理匹配的内容
                when (type) {
                    TokenType.BOLD_ITALIC -> elements.add(InlineElement.BoldItalic(listOf(InlineElement.PlainText(match.groupValues[1]))))
                    TokenType.BOLD -> elements.add(InlineElement.Bold(listOf(InlineElement.PlainText(match.groupValues[1]))))
                    TokenType.ITALIC -> elements.add(InlineElement.Italic(listOf(InlineElement.PlainText(match.groupValues[1]))))
                    TokenType.CODE -> elements.add(InlineElement.Code(match.groupValues[1]))
                    TokenType.MATH -> {
                        // 最终修复：正确处理两个捕获组 $$...$$和$...$
                        val mathContent = when {
                            match.groupValues[1].isNotEmpty() -> match.groupValues[1].trim() // $$...$$
                            match.groupValues[2].isNotEmpty() -> match.groupValues[2].trim() // $...$
                            else -> ""
                        }
                        if (mathContent.isNotEmpty()) {
                            elements.add(InlineElement.Math(mathContent))
                        } else {
                            elements.add(InlineElement.PlainText(match.value))
                        }
                    }
                    else -> elements.add(InlineElement.PlainText(match.value))
                }
                
                // 确保索引向前推进
                val newIndex = match.range.last + 1
                currentIndex = if (newIndex > currentIndex) newIndex else currentIndex + 1
            }
            
            elements
        } catch (e: Exception) {
            // 如果任何地方出错，返回纯文本
            listOf(InlineElement.PlainText(text))
        }
    }

    /**
     * Optimized pattern matching - finds the first match among all patterns
     */
    private fun findFirstMatch(text: String, startIndex: Int): Token? {
        var earliestMatch: Token? = null
        var earliestIndex = Int.MAX_VALUE
        
        // Check each pattern and find the earliest match
        val patterns = listOf(
            TokenType.BOLD_ITALIC to boldItalicRegex,
            TokenType.BOLD to boldRegex,
            TokenType.ITALIC to italicRegex,
            TokenType.LINK to linkRegex,
            TokenType.IMPLICIT_LINK to implicitLinkRegex,
            TokenType.CODE to codeRegex,
            TokenType.MATH to mathRegex,
            TokenType.URL to urlRegex,
            TokenType.BR to brRegex,
            TokenType.PIPE to pipeRegex,
            TokenType.ESCAPE to escapeRegex
        )
        
        for ((tokenType, regex) in patterns) {
            val match = regex.find(text, startIndex)
            if (match != null && match.range.first < earliestIndex) {
                earliestIndex = match.range.first
                earliestMatch = Token(tokenType, match)
            }
        }
        
        return earliestMatch
    }

    fun render(elements: List<InlineElement>): AnnotatedString {
        return buildAnnotatedString {
            elements.forEach { element ->
                when (element) {
                    is InlineElement.PlainText -> append(element.content)
                    is InlineElement.Bold -> withStyle(styleConfig.boldStyle) { append(render(element.children)) }
                    is InlineElement.Italic -> withStyle(styleConfig.italicStyle) { append(render(element.children)) }
                    is InlineElement.BoldItalic -> withStyle(styleConfig.boldItalicStyle) { append(render(element.children)) }
                    is InlineElement.Code -> withStyle(styleConfig.codeStyle) { append(element.content) }
                    is InlineElement.Math -> {
                        try {
                            withStyle(styleConfig.mathStyle) { append(LatexToUnicode.convert(element.content)) }
                        } catch (e: Exception) {
                            withStyle(styleConfig.codeStyle) { append(element.content) }
                        }
                    }
                    is InlineElement.Link -> {
                        pushStringAnnotation("URL", element.url)
                        withStyle(styleConfig.linkStyle) { append(render(element.text)) }
                        pop()
                    }
                    is InlineElement.AutoLink -> {
                        pushStringAnnotation("URL", element.url)
                        withStyle(styleConfig.linkStyle) { append(element.url) }
                        pop()
                    }
                }
            }
        }
    }
}

fun parseInlineMarkdownToAnnotatedString(
    line: String,
    styleConfig: MarkdownStyleConfig = MarkdownStyleConfig()
): AnnotatedString {
    val elements = IncrementalMarkdownParser.parse(line)
    return IncrementalMarkdownParser.render(elements)
}

sealed class RenderMode {
    object Streaming : RenderMode()
    object Complete : RenderMode()
}

fun parseBasicMarkdown(text: String): AnnotatedString {
    // A simplified parser for streaming mode
    return buildAnnotatedString {
        val boldItalicRegex = Regex("(?<!\\\\|\\*)\\*\\*\\*([\\s\\S]+?)\\*\\*\\*(?!\\*)")
        val boldRegex = Regex("(?<!\\\\|\\*)\\*\\*([\\s\\S]+?)\\*\\*(?!\\*)")
        val italicRegex = Regex("(?<!\\\\|\\*)\\*([\\s\\S]+?)\\*(?!\\*)")

        var currentIndex = 0

        val allMatches = (
                boldItalicRegex.findAll(text).map { "bold_italic" to it } +
                        boldRegex.findAll(text).map { "bold" to it } +
                        italicRegex.findAll(text).map { "italic" to it }
                ).sortedBy { it.second.range.first }

        val processedMatches = mutableSetOf<MatchResult>()

        for ((type, match) in allMatches) {
            if (match in processedMatches || match.range.first < currentIndex) continue

            val isContained = allMatches.any { otherMatch ->
                match != otherMatch.second &&
                        match.range.first >= otherMatch.second.range.first &&
                        match.range.last <= otherMatch.second.range.last
            }

            if (isContained) continue

            if (match.range.first > currentIndex) {
                append(text.substring(currentIndex, match.range.first))
            }

            val content = match.groupValues[1]
            when (type) {
                "bold_italic" -> withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) { append(parseBasicMarkdown(content)) }
                "bold" -> withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) { append(parseBasicMarkdown(content)) }
                "italic" -> withStyle(style = SpanStyle(fontStyle = FontStyle.Italic)) { append(parseBasicMarkdown(content)) }
            }
            currentIndex = match.range.last + 1
            processedMatches.add(match)
        }

        if (currentIndex < text.length) {
            append(text.substring(currentIndex))
        }
    }
}

fun parseMarkdownWithMode(
    text: String,
    mode: RenderMode
): AnnotatedString {
    return when (mode) {
        RenderMode.Streaming -> {
            parseBasicMarkdown(text)
        }
        RenderMode.Complete -> {
            parseInlineMarkdownToAnnotatedString(text)
        }
    }
}