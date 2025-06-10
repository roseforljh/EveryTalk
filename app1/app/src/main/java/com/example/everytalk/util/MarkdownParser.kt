package com.example.everytalk.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

sealed class MarkdownBlock {
    data class Header(val level: Int, val text: AnnotatedString) : MarkdownBlock()
    data class Text(val text: AnnotatedString) : MarkdownBlock()
    data class CodeBlock(val rawText: String, val language: String?) : MarkdownBlock()
    data class ListItem(val text: AnnotatedString) : MarkdownBlock()
    data class MathBlock(val text: String) : MarkdownBlock()
}

fun parseMarkdownToBlocks(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = markdown.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.trim().startsWith("$$") || line.trim().startsWith("\\[") -> {
                val isDollars = line.trim().startsWith("$$")
                val openDelim = if (isDollars) "$$" else "\\["
                val closeDelim = if (isDollars) "$$" else "\\]"

                val mathBlockLines = mutableListOf<String>()
                val startingLine = line.trim().substring(openDelim.length)

                if (startingLine.endsWith(closeDelim)) {
                    // Single line block
                    mathBlockLines.add(startingLine.removeSuffix(closeDelim).trim())
                    i++
                } else {
                    mathBlockLines.add(startingLine)
                    i++ // consume opening line
                    while (i < lines.size && !lines[i].trim().endsWith(closeDelim)) {
                        mathBlockLines.add(lines[i])
                        i++
                    }
                    if (i < lines.size) {
                        mathBlockLines.add(lines[i].trim().removeSuffix(closeDelim))
                        i++ // consume closing line
                    }
                }
                blocks.add(MarkdownBlock.MathBlock(mathBlockLines.joinToString("\n").trim()))
            }
            line.startsWith("```") -> {
                val lang = line.substring(3).trim()
                val codeBlockLines = mutableListOf<String>()
                i++ // consume opening ```
                while (i < lines.size && !lines[i].startsWith("```")) {
                    codeBlockLines.add(lines[i])
                    i++
                }
                val rawText = codeBlockLines.joinToString("\n")

                val latexCommands = listOf(
                    "\\text", "\\frac", "\\sum", "\\beta", "\\alpha", "\\gamma", "\\delta",
                    "\\epsilon", "\\sigma", "\\rho", "\\theta", "\\omega", "\\mu", "\\nu",
                    "\\sin", "\\cos", "\\tan", "\\log", "\\ln", "\\sqrt", "\\in", "\\infty",
                    "\\left", "\\right", "\\times", "\\geq", "\\leq", "\\neq", "\\approx",
                    "\\cdot", "\\pm", "\\mp", "\\forall", "\\exists", "\\nabla", "\\partial"
                )
                // A block is likely math if it has no language, and contains latex commands.
                val isLikelyMath = lang.isEmpty() && latexCommands.any { rawText.contains(it) }

                if (isLikelyMath) {
                    blocks.add(MarkdownBlock.MathBlock(rawText))
                } else {
                    blocks.add(MarkdownBlock.CodeBlock(rawText, lang.ifEmpty { null }))
                }

                if (i < lines.size) {
                    i++ // consume closing ```
                }
            }
            line.startsWith("#") -> {
                val level = line.takeWhile { it == '#' }.length
                blocks.add(MarkdownBlock.Header(level, parseInlineMarkdown(line.removePrefix("#".repeat(level)).trim())))
                i++
            }
            line.startsWith("* ") || line.startsWith("- ") -> {
                val listContent = mutableListOf(line.substring(2))
                i++
                while (i < lines.size && lines[i].isNotBlank() && !lines[i].startsWith("* ") && !lines[i].startsWith("- ") && !lines[i].startsWith("#") && !lines[i].startsWith("```")) {
                    listContent.add(lines[i])
                    i++
                }
                blocks.add(MarkdownBlock.ListItem(parseInlineMarkdown(listContent.joinToString("\n"))))
            }
            else -> {
                val textLines = mutableListOf<String>()
                while (i < lines.size) {
                    val currentLine = lines[i]
                    if (currentLine.startsWith("```") ||
                        currentLine.startsWith("#") ||
                        currentLine.startsWith("* ") ||
                        currentLine.startsWith("- ") ||
                        currentLine.trim().startsWith("$$") || currentLine.trim().startsWith("\\[")
                    ) {
                        break // Start of a new block type
                    }
                    textLines.add(currentLine)
                    i++
                }
                if (textLines.isNotEmpty()) {
                    val text = textLines.joinToString("\n")
                    if (text.isNotBlank()) {
                        blocks.add(MarkdownBlock.Text(parseInlineMarkdown(text)))
                    }
                }
            }
        }
    }
    return blocks
}

private fun parseInlineMarkdown(line: String): AnnotatedString {
    return buildAnnotatedString {
        val boldItalicRegex = Regex("\\*\\*\\*([\\s\\S]+?)\\*\\*\\*")
        val boldRegex = Regex("\\*\\*([\\s\\S]+?)\\*\\*")
        val italicRegex = Regex("\\*([\\s\\S]+?)\\*")
        val codeRegex = Regex("`([^`]+?)`") // Non-greedy match
        val inlineMathRegex = Regex("\\\\\\((.*?)\\\\\\)|\\$(.*?)\\$") // For \(...\) or $...$

        var lastIndex = 0
        val allMatches = (
            boldItalicRegex.findAll(line) +
            boldRegex.findAll(line) +
            italicRegex.findAll(line) +
            codeRegex.findAll(line) +
            inlineMathRegex.findAll(line)
        ).sortedWith(compareBy<MatchResult> { it.range.first }.thenByDescending { it.range.last - it.range.first })

        allMatches.forEach { match ->
            if (match.range.first < lastIndex) {
                return@forEach
            }

            if (match.range.first > lastIndex) {
                append(line.substring(lastIndex, match.range.first))
            }

            val isMath = match.value.startsWith("\\(") || match.value.startsWith("$")
            val content = if (isMath && match.groupValues[1].isEmpty()) {
                match.groupValues[2]
            } else {
                match.groupValues[1]
            }
            val isCode = match.value.startsWith("`")

            val style = when {
                match.value.startsWith("***") -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                match.value.startsWith("**") -> SpanStyle(fontWeight = FontWeight.Bold)
                match.value.startsWith("*") -> SpanStyle(fontStyle = FontStyle.Italic)
                isCode -> SpanStyle(fontFamily = FontFamily.Monospace, background = Color.White, fontSize = 13.sp, color = Color.Gray)
                isMath -> SpanStyle(fontFamily = FontFamily.Default) // Style for math
                else -> SpanStyle()
            }
            withStyle(style) {
                if (isMath) {
                    append(LatexToUnicode.convert(content))
                } else {
                    append(content)
                }
            }
            lastIndex = match.range.last + 1
        }
        if (lastIndex < line.length) {
            append(line.substring(lastIndex))
        }
    }
}