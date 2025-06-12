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
    data class Image(val altText: String, val url: String) : MarkdownBlock()
    data class Table(val header: List<AnnotatedString>, val rows: List<List<AnnotatedString>>) : MarkdownBlock()
}

fun parseMarkdownToBlocks(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = markdown.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.trim().startsWith("![") && line.trim().endsWith(")") -> {
                val altTextRegex = Regex("!\\[(.*?)\\]")
                val urlRegex = Regex("\\((.*?)\\)")
                val altText = altTextRegex.find(line)?.groupValues?.get(1) ?: ""
                val url = urlRegex.find(line)?.groupValues?.get(1) ?: ""
                if (url.isNotEmpty()) {
                    blocks.add(MarkdownBlock.Image(altText, url))
                }
                i++
            }
            line.trim().startsWith("$$") || line.trim().startsWith("\\[") -> {
                val isDollars = line.trim().startsWith("$$")
                val openDelim = if (isDollars) "$$" else "\\["
                val closeDelim = if (isDollars) "$$" else "\\]"

                val mathBlockLines = mutableListOf<String>()
                val startingLine = line.trim().substring(openDelim.length)

                if (startingLine.endsWith(closeDelim)) {
                    mathBlockLines.add(startingLine.removeSuffix(closeDelim).trim())
                    i++
                } else {
                    mathBlockLines.add(startingLine)
                    i++
                    while (i < lines.size && !lines[i].trim().endsWith(closeDelim)) {
                        mathBlockLines.add(lines[i])
                        i++
                    }
                    if (i < lines.size) {
                        mathBlockLines.add(lines[i].trim().removeSuffix(closeDelim))
                        i++
                    }
                }
                blocks.add(MarkdownBlock.MathBlock(mathBlockLines.joinToString("\n").trim()))
            }
            line.startsWith("```") -> {
                val lang = line.substring(3).trim()
                val codeBlockLines = mutableListOf<String>()
                i++
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
                val isLikelyMath = lang.isEmpty() && latexCommands.any { rawText.contains(it) }

                if (isLikelyMath) {
                    blocks.add(MarkdownBlock.MathBlock(rawText))
                } else {
                    blocks.add(MarkdownBlock.CodeBlock(rawText, lang.ifEmpty { null }))
                }

                if (i < lines.size) {
                    i++
                }
            }
            line.trim().startsWith("#") -> {
                val trimmedLine = line.trim()
                val level = trimmedLine.takeWhile { it == '#' }.length
                val text = trimmedLine.removePrefix("#".repeat(level)).trim()
                blocks.add(MarkdownBlock.Header(level, parseInlineMarkdown(text)))
                i++
            }
            line.trim().startsWith("* ") || line.trim().startsWith("- ") -> {
                val trimmedLine = line.trim()
                val listContent = mutableListOf(trimmedLine.substring(2))
                i++
                while (i < lines.size && lines[i].isNotBlank() && !lines[i].trim().startsWith("* ") && !lines[i].trim().startsWith("- ") && !lines[i].trim().startsWith("#") && !lines[i].startsWith("```")) {
                    listContent.add(lines[i])
                    i++
                }
                blocks.add(MarkdownBlock.ListItem(parseInlineMarkdown(listContent.joinToString("\n"))))
            }
            line.trim().startsWith("|") && i + 1 < lines.size && isSeparatorLine(lines[i + 1]) -> {
                val headerLine = line.trim()
                val headerContent = headerLine.substring(1).let { if (it.endsWith("|")) it.substring(0, it.length - 1) else it }
                val headerCells = headerContent.split("|").map {
                    parseInlineMarkdown(it.trim())
                }
                i++ // Consume header line
                i++ // Consume separator line

                val tableRows = mutableListOf<List<AnnotatedString>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    val rowLine = lines[i].trim()
                    val rowContent = rowLine.substring(1).let { if (it.endsWith("|")) it.substring(0, it.length - 1) else it }
                    val rowCells = rowContent.split("|").map {
                        parseInlineMarkdown(it.trim())
                    }
                    tableRows.add(rowCells)
                    i++
                }
                blocks.add(MarkdownBlock.Table(headerCells, tableRows))
            }
            else -> {
                val textLines = mutableListOf<String>()
                while (i < lines.size) {
                    val currentLine = lines[i]
                    val isTableStart = currentLine.trim().startsWith("|") && i + 1 < lines.size && isSeparatorLine(lines[i + 1])

                    if (currentLine.startsWith("```") ||
                        currentLine.startsWith("#") ||
                        currentLine.trim().startsWith("* ") ||
                        currentLine.trim().startsWith("- ") ||
                        currentLine.trim().startsWith("$$") || currentLine.trim().startsWith("\\[") ||
                        isTableStart
                    ) {
                        break
                    }
                    textLines.add(currentLine)
                    i++
                }
                if (textLines.isNotEmpty()) {
                    val text = textLines.joinToString("\n")
                    if (text.isNotBlank()) {
                        val latexCommands = listOf(
                            "\\text", "\\frac", "\\sum", "\\beta", "\\alpha", "\\gamma", "\\delta",
                            "\\epsilon", "\\sigma", "\\rho", "\\theta", "\\omega", "\\mu", "\\nu",
                            "\\sin", "\\cos", "\\tan", "\\log", "\\ln", "\\sqrt", "\\in", "\\infty",
                            "\\left", "\\right", "\\times", "\\geq", "\\leq", "\\neq", "\\approx",
                            "\\cdot", "\\pm", "\\mp", "\\forall", "\\exists", "\\nabla", "\\partial",
                            "\\begin", "\\end"
                        )
                        if (latexCommands.any { text.contains(it) }) {
                            blocks.add(MarkdownBlock.MathBlock(text))
                        } else {
                            blocks.add(MarkdownBlock.Text(parseInlineMarkdown(text)))
                        }
                    }
                }
            }
        }
    }
    return blocks
}

private fun isSeparatorLine(line: String): Boolean {
    val trimmed = line.trim()
    if (!trimmed.startsWith('|')) return false

    // A GFM separator line must contain a pipe and dashes.
    if (trimmed.length < 3 || !trimmed.contains('-')) return false

    // Strip optional leading and trailing pipes for splitting
    var content = trimmed
    if (content.startsWith('|')) content = content.substring(1)
    if (content.endsWith('|')) content = content.substring(0, content.length - 1)

    val separatorParts = content.split('|')
    if (separatorParts.isEmpty()) return false

    // Each part must be like ---, :---, ---:, or :---:
    // Let's be lenient and require at least two dashes.
    // The regex checks for optional leading/trailing whitespace, optional colons, and at least 2 dash-like characters.
    val dashRegex = Regex("^\\s*:?[\\-–—]{2,}:?\\s*$")

    return separatorParts.all { it.matches(dashRegex) }
}

private fun parseInlineMarkdown(line: String): AnnotatedString {
    return buildAnnotatedString {
        // Base case for recursion
        if (line.isEmpty()) return@buildAnnotatedString

        val regexes = mapOf(
            "link" to Regex("\\[([^\\]]+?)\\]\\((https?://\\S+?)\\)"),
            "fraction" to Regex("\\\\frac\\{([^}]+?)\\}\\{([^}]+)\\}"),
            "bold_italic" to Regex("\\*\\*\\*([\\s\\S]+?)\\*\\*\\*"),
            "bold" to Regex("\\*\\*([\\s\\S]+?)\\*\\*"),
            "italic" to Regex("\\*([\\s\\S]+?)\\*"),
            "code" to Regex("`([^`]+?)`"),
            "math" to Regex("\\\\\\((.*?)\\\\\\)|\\$(.*?)\\$"),
            "url" to Regex("\\b(https?://\\S+)")
        )

        val firstMatch = regexes.flatMap { (type, regex) ->
            regex.findAll(line).map { match -> type to match }
        }.sortedWith(
            compareBy<Pair<String, MatchResult>> { (_, match) -> match.range.first }
                .thenByDescending { (_, match) -> match.range.last - match.range.first }
        ).firstOrNull()

        if (firstMatch == null) {
            append(line)
            return@buildAnnotatedString
        }

        val (type, match) = firstMatch

        // Append text before the match
        if (match.range.first > 0) {
            append(line.substring(0, match.range.first))
        }

        // Get content and apply style
        val (content, style, annotation) = when (type) {
            "link" -> Triple(match.groupValues[1], SpanStyle(color = Color(0xFF3498DB)), "URL" to match.groupValues[2])
            "url" -> Triple(match.value, SpanStyle(color = Color(0xFF3498DB)), "URL" to match.value)
            "fraction" -> {
                val numerator = match.groupValues[1]
                val denominator = match.groupValues[2]
                Triple(" ", SpanStyle(), "FRACTION" to "$numerator/$denominator")
            }
            "bold_italic" -> Triple(match.groupValues[1], SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic), null)
            "bold" -> Triple(match.groupValues[1], SpanStyle(fontWeight = FontWeight.Bold), null)
            "italic" -> Triple(match.groupValues[1], SpanStyle(fontStyle = FontStyle.Italic), null)
            "code" -> Triple(match.groupValues[1], SpanStyle(fontFamily = FontFamily.Monospace, background = Color.White, fontSize = 13.sp, color = Color.Gray), null)
            "math" -> Triple(if (match.groupValues[1].isNotEmpty()) match.groupValues[1] else match.groupValues[2], SpanStyle(fontFamily = FontFamily.Default), null)
            else -> Triple("", SpanStyle(), null)
        }

        if (annotation != null) {
            pushStringAnnotation(annotation.first, annotation.second)
        }
        withStyle(style) {
            if (type == "url" || type == "code" || type == "math" || type == "fraction") {
                append(if (type == "math") LatexToUnicode.convert(content) else content)
            } else {
                append(parseInlineMarkdown(content)) // Recursive call
            }
        }
        if (annotation != null) {
            pop()
        }

        // Recursively parse the rest of the line
        val restOfLine = line.substring(match.range.last + 1)
        append(parseInlineMarkdown(restOfLine))
    }
}