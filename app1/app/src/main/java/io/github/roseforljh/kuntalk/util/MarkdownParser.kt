package io.github.roseforljh.kuntalk.util

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
    data class Header(val level: Int, val text: String) : MarkdownBlock()
    data class Text(val text: String) : MarkdownBlock()
    data class CodeBlock(val rawText: String, val language: String?) : MarkdownBlock()
    data class ListItem(val text: String) : MarkdownBlock()
    data class Image(val altText: String, val url: String) : MarkdownBlock()
    data class Table(val header: List<String>, val rows: List<List<String>>) : MarkdownBlock()
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
            line.startsWith("```") -> {
                val lang = line.substring(3).trim()
                val codeBlockLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].startsWith("```")) {
                    codeBlockLines.add(lines[i])
                    i++
                }
                val rawText = codeBlockLines.joinToString("\n")

                blocks.add(MarkdownBlock.CodeBlock(rawText, lang.ifEmpty { null }))

                if (i < lines.size) {
                    i++
                }
            }
            line.trim().startsWith("#") -> {
                val trimmedLine = line.trim()
                val level = trimmedLine.takeWhile { it == '#' }.length
                val text = trimmedLine.removePrefix("#".repeat(level)).trim()
                blocks.add(MarkdownBlock.Header(level, text))
                i++
            }
            line.trim().matches(Regex("^[*\\-]\\s+.*")) -> {
                val trimmedLine = line.trim()
                val contentStartIndex = trimmedLine.indexOfFirst { it.isWhitespace() } + 1
                val listContent = mutableListOf(trimmedLine.substring(contentStartIndex))
                i++
                while (i < lines.size && lines[i].isNotBlank() && !lines[i].trim().matches(Regex("^[*\\-]\\s+.*")) && !lines[i].trim().startsWith("#") && !lines[i].startsWith("```")) {
                    listContent.add(lines[i])
                    i++
                }
                blocks.add(MarkdownBlock.ListItem(listContent.joinToString("\n")))
            }
            line.trim().startsWith("|") && i + 1 < lines.size && isSeparatorLine(lines[i + 1]) -> {
                val headerLine = line.trim()
                val headerContent = headerLine.removePrefix("|").removeSuffix("|")
                val headerCells = headerContent.split("|").map { it.trim() }
                i++ // Consume header line
                i++ // Consume separator line

                val tableRows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    val rowLine = lines[i].trim()
                    val rowContent = rowLine.removePrefix("|").removeSuffix("|")
                    val rowCells = rowContent.split("|").map { it.trim() }
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
                        currentLine.trim().matches(Regex("^[*\\-]\\s+.*")) ||
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
                        blocks.add(MarkdownBlock.Text(text))
                    }
                }
            }
        }
    }
    return blocks
}

private fun isSeparatorLine(line: String): Boolean {
    val trimmed = line.trim()
    // A separator line must contain a pipe and a dash.
    if (!trimmed.contains('|') || !trimmed.contains('-')) return false

    // Strip optional leading and trailing pipes for splitting.
    val content = trimmed.removePrefix("|").removeSuffix("|")

    val separatorParts = content.split('|')
    if (separatorParts.isEmpty()) return false

    // Each part must be like ---, :---, ---:, or :---:.
    // Be lenient: require at least 2 dashes. Allow different dash characters.
    val dashRegex = Regex("^\\s*:?[\\-–—]{2,}:?\\s*$")

    return separatorParts.all { it.matches(dashRegex) }
}

