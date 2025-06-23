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

// --- 1. Data Models for AST and Styling ---

/**
 * Represents a node in the inline Markdown abstract syntax tree (AST).
 */
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

/**
 * Configuration for styling rendered Markdown.
 * Allows for easy themeing and customization.
 */
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

// --- 2. Parser and Renderer ---

/**
 * A robust parser for inline Markdown elements that separates parsing from rendering.
 * It first tokenizes the input string and then builds an AST.
 */
class InlineMarkdownParser(private val styleConfig: MarkdownStyleConfig = MarkdownStyleConfig()) {

    private data class Token(val type: TokenType, val match: MatchResult)

    private enum class TokenType(val regex: Regex) {
        // Order is crucial for correct parsing. More specific/longer rules come first.
        BOLD_ITALIC(Regex("(?<!\\\\)\\*\\*\\*(.+?)\\*\\*\\*")),
        BOLD(Regex("(?<![\\\\*])\\*\\*(.+?)\\*\\*(?![*])")),
        ITALIC(Regex("(?<![\\\\*])\\*(?!\\*)(.+?)(?<!\\*)\\*(?![*])")),
        LINK(Regex("(?<!\\\\)\\[(.+?)\\]\\((https?://\\S+?)\\)")),
        IMPLICIT_LINK(Regex("(?<!\\\\)\\[(https?://\\S+?)\\]")),
        CODE(Regex("(?<!\\\\)`([^`]+?)`")),
        MATH(Regex("(?<!\\\\)\\$\\$([^\\$]+?)\\$\\$|(?<!\\\\)\\$([^\\$]+?)\\$")),
        URL(Regex("\\b(https?://\\S+)")),
        BR(Regex("<br\\s*/?>")),
        PIPE(Regex("\\s*\\|\\s*")),
        ESCAPE(Regex("\\\\(.)"))
    }

    /**
     * Parses a raw string into a list of InlineElement nodes (AST).
     */
    fun parse(text: String): List<InlineElement> {
        return parseInternal(text)
    }

    /**
     * Renders a list of InlineElement nodes (AST) into a styled AnnotatedString.
     */
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
                            // Graceful fallback if LatexToUnicode fails
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

    /**
     * Internal recursive parsing function.
     * Note: While this is recursive, Markdown inline nesting is typically shallow.
     * For extremely deep nesting, a stack-based iterative approach would be more robust against StackOverflowError.
     */
    private fun parseInternal(text: String): List<InlineElement> {
        val elements = mutableListOf<InlineElement>()
        var currentIndex = 0

        while (currentIndex < text.length) {
            val firstMatch = TokenType.values()
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
                TokenType.CODE -> elements.add(InlineElement.Code(matchResult.groupValues[1]))
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
}

/**
 * Main function to parse and render a line of Markdown.
 * This is the public API that should be used.
 *
 * @param line The raw Markdown string.
 * @param styleConfig Optional custom styling configuration.
 * @return An AnnotatedString ready for display in Compose.
 */
fun parseInlineMarkdownToAnnotatedString(
    line: String,
    styleConfig: MarkdownStyleConfig = MarkdownStyleConfig()
): AnnotatedString {
    val parser = InlineMarkdownParser(styleConfig)
    val elements = parser.parse(line)
    return parser.render(elements)
}