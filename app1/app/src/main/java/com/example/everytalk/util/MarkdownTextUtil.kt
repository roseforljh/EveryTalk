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

private fun unescapeMarkdownText(text: String): String {
    return text.replace(Regex("\\\\(.)"), "$1")
}

fun parseInlineMarkdownToAnnotatedString(line: String): AnnotatedString {
    val processedLine = line
        .replace(Regex("<br\\s*/?>"), "\n")
        .replace(Regex("\\s*\\|\\s*"), " ")

    return buildAnnotatedString {
        val regexes = mapOf(
            "link" to Regex("(?<!\\\\)\\[([^\\]]+?)\\]\\((https?://\\S+?)\\)"),
            "bold_italic" to Regex("(?<!\\\\)\\*\\*\\*([\\s\\S]+?)\\*\\*\\*"),
            "bold" to Regex("(?<!\\\\)\\*\\*([\\s\\S]+?)\\*\\*"),
            "italic" to Regex("(?<!\\\\)\\*([^*][\\s\\S]*?)\\*"),
            "code" to Regex("(?<!\\\\)`([^`]+?)`"),
            "math" to Regex("(?<!\\\\)\\$\\$([\\s\\S]*?)\\$\\$|(?<!\\\\)\\$([^\\$]*?)\\$"),
            "url" to Regex("\\b(https?://\\S+)")
        )

        var currentIndex = 0
        while (currentIndex < processedLine.length) {
            val firstMatch = regexes.flatMap { (type, regex) ->
                regex.findAll(processedLine, startIndex = currentIndex).map { match -> type to match }
            }.minByOrNull { (_, match) -> match.range.first }

            if (firstMatch == null) {
                append(unescapeMarkdownText(processedLine.substring(currentIndex)))
                break
            }

            val (type, match) = firstMatch

            if (match.range.first > currentIndex) {
                append(unescapeMarkdownText(processedLine.substring(currentIndex, match.range.first)))
            }

            when (type) {
                "link" -> {
                    val content = match.groupValues[1]
                    val url = match.groupValues[2]
                    pushStringAnnotation("URL", url)
                    withStyle(SpanStyle(color = Color(0xFF3498DB))) {
                        append(parseInlineMarkdownToAnnotatedString(content))
                    }
                    pop()
                }
                "bold_italic" -> {
                    val content = match.groupValues[1]
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                        append(parseInlineMarkdownToAnnotatedString(content))
                    }
                }
                "bold" -> {
                    val content = match.groupValues[1]
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(parseInlineMarkdownToAnnotatedString(content))
                    }
                }
                "italic" -> {
                    val content = match.groupValues[1]
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(parseInlineMarkdownToAnnotatedString(content))
                    }
                }
                "code" -> {
                    val content = match.groupValues[1]
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFFF0F0F0), fontSize = 13.sp)) {
                        append(unescapeMarkdownText(content))
                    }
                }
                "math" -> {
                    val mathContent = if (match.groupValues[1].isNotEmpty()) match.groupValues[1] else match.groupValues[2]
                    withStyle(SpanStyle(fontFamily = FontFamily.Default, color = Color.Black)) {
                        append(LatexToUnicode.convert(unescapeMarkdownText(mathContent)))
                    }
                }
                "url" -> {
                    val url = match.value
                    pushStringAnnotation("URL", url)
                    withStyle(SpanStyle(color = Color(0xFF3498DB))) {
                        append(url)
                    }
                    pop()
                }
            }
            currentIndex = match.range.last + 1
        }
    }
}