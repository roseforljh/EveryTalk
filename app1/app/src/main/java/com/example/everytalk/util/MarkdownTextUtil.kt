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

    private enum class TokenType(val regex: Regex) {
        BOLD_ITALIC(Regex("(?s)(?<!\\\\)\\*\\*\\*(.+?)\\*\\*\\*")),
        BOLD(Regex("(?s)(?<!\\\\|\\*)\\*\\*(.+?)\\*\\*(?!\\*)")),
        ITALIC(Regex("(?s)(?<!\\\\|\\*)\\*(.+?)\\*(?!\\*)")),
        LINK(Regex("""\[(.+?)]\((https?://\S+?)\)""")),
        IMPLICIT_LINK(Regex("""\[(https?://\S+?)]""")),
        CODE(Regex("(?s)```(?:[a-zA-Z]+)?\\n?([\\s\\S]*?)```|`([^`]+?)`")),
        MATH(Regex("""\$\$([^$]+?)\$\$|\$([^$]+?)\$""")),
        URL(Regex("""\b(https?://\S+)""")),
        BR(Regex("""<br\s*/?>""")),
        PIPE(Regex("""\s*\|\s*""")),
        ESCAPE(Regex("""\\(.)"""))
    }





    fun parseIncrementalStream(
        messageId: String,
        newText: String,
        isComplete: Boolean = false
    ): List<InlineElement> {
        // Removed caching mechanism to prevent state corruption on recomposition.
        // Always perform a full parse for correctness.
        return parseInternal(newText)
    }


    fun parse(text: String): List<InlineElement> {
        return parseInternal(text)
    }

    private fun parseInternal(text: String): List<InlineElement> {
        val elements = mutableListOf<InlineElement>()
        var currentIndex = 0

        while (currentIndex < text.length) {
            val firstMatch = TokenType.values()
                .asSequence()
                .mapNotNull { type -> type.regex.find(text, currentIndex)?.let { Token(type, it) } }
                .minByOrNull { it.match.range.first }

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
                    val mathContent = if (matchResult.groupValues[1].isNotEmpty()) matchResult.groupValues[1] else matchResult.groupValues[2]
                    elements.add(InlineElement.Math(mathContent))
                }
                TokenType.URL -> elements.add(InlineElement.AutoLink(matchResult.value))
                TokenType.BR -> elements.add(InlineElement.PlainText("\n"))
                TokenType.PIPE -> elements.add(InlineElement.PlainText(" "))
                TokenType.ESCAPE -> elements.add(InlineElement.PlainText(matchResult.groupValues[1]))
            }
            currentIndex = matchResult.range.last + 1
        }
        return elements
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