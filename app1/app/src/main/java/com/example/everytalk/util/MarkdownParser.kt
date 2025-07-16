package com.example.everytalk.util

// --- 1. Enhanced Data Model (AST) ---
sealed class MarkdownBlock {
    data class Header(val level: Int, val text: String) : MarkdownBlock()
    data class CodeBlock(val language: String?, val rawText: String) : MarkdownBlock()
    data class Image(val altText: String, val url: String) : MarkdownBlock()
    data class Table(val header: List<String>, val rows: List<List<String>>) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Blockquote(val blocks: List<MarkdownBlock>) : MarkdownBlock()
    data class UnorderedList(val items: List<String>) : MarkdownBlock()
    data class OrderedList(val items: List<String>) : MarkdownBlock()
    object HorizontalRule : MarkdownBlock()
    data class MathBlock(val formula: String) : MarkdownBlock()
}

// --- 2. Parser Infrastructure ---
internal class ParseContext(val lines: List<String>, var currentIndex: Int) {
    fun currentLine(): String? = lines.getOrNull(currentIndex)
    fun hasMoreLines(): Boolean = currentIndex < lines.size
}

internal interface BlockParser {
    fun canParse(context: ParseContext): Boolean
    fun parse(context: ParseContext): MarkdownBlock
}

// --- 3. Concrete Parser Implementations ---

private object RegexConstants {
    val HEADER_REGEX = Regex("^#{1,6}\\s+.*")
    val UNORDERED_LIST_REGEX = Regex("^[\\-*•]\\s+.*")
    val ORDERED_LIST_REGEX = Regex("^\\d+\\.\\s+.*")
    val IMAGE_REGEX = Regex("^!\\[(.*?)\\]\\((.*?)\\)")
    val CODE_BLOCK_START_REGEX = Regex("^```.*")
    val TABLE_ROW_REGEX = Regex("^\\|.*\\|$")
    val HORIZONTAL_RULE_REGEX = Regex("^(?:---|\\*\\*\\*|___)$")
    val MATH_BLOCK_REGEX = Regex("^\\$\\$.*\\$\\$$")
}

internal class HeaderParser : BlockParser {
    override fun canParse(context: ParseContext): Boolean {
        val line = context.currentLine()?.trim()
        return line != null && RegexConstants.HEADER_REGEX.matches(line)
    }

    override fun parse(context: ParseContext): MarkdownBlock {
        val line = context.currentLine()!!
        val level = line.takeWhile { it == '#' }.length
        val text = line.removePrefix("#".repeat(level)).trim()
        context.currentIndex++
        return MarkdownBlock.Header(level, text)
    }
}

internal class CodeBlockParser : BlockParser {
    override fun canParse(context: ParseContext): Boolean {
        return context.currentLine()?.trim()?.let { RegexConstants.CODE_BLOCK_START_REGEX.matches(it) } == true
    }

    override fun parse(context: ParseContext): MarkdownBlock {
        val firstLine = context.currentLine()!!
        val language = firstLine.substring(3).trim().ifEmpty { null }
        context.currentIndex++

        val codeLines = mutableListOf<String>()
        while (context.hasMoreLines()) {

            val currentLine = context.currentLine()!!
            if (currentLine.trim() == "```") {
                context.currentIndex++ // Consume the closing ```
                break
            }
            codeLines.add(currentLine)
            context.currentIndex++
        }
        return MarkdownBlock.CodeBlock(language, codeLines.joinToString("\n"))
    }
}

internal class UnorderedListParser : BlockParser {
    override fun canParse(context: ParseContext): Boolean {
        return context.currentLine()?.trim()?.let { RegexConstants.UNORDERED_LIST_REGEX.matches(it) } == true
    }

    override fun parse(context: ParseContext): MarkdownBlock {
        val items = mutableListOf<String>()
        // This loop consumes all consecutive items of an unordered list
        while (context.hasMoreLines() && canParse(context)) {
            val itemContent = mutableListOf<String>()

            // First line of the item
            val firstLine = context.currentLine()!!.trim()
            val cleanedLine = firstLine.replaceFirst(Regex("^([\\-*•]\\s+)+"), "")
            itemContent.add(cleanedLine.trim())
            context.currentIndex++

            // Subsequent lines of the same item (continuation)
            // Subsequent lines of the same item (continuation)
            while (context.hasMoreLines()) {
                val currentLine = context.currentLine()
                if (currentLine.isNullOrBlank()) {
                    // Stop at the first blank line. This properly separates list items.
                    break
                }

                val trimmedLine = currentLine.trim()
                val isNewListItem = RegexConstants.UNORDERED_LIST_REGEX.matches(trimmedLine) ||
                        RegexConstants.ORDERED_LIST_REGEX.matches(trimmedLine)
                if (isNewListItem) break

                val isOtherBlock = MarkdownParser.blockParsers.any {
                    it !is ParagraphParser && it !is UnorderedListParser && it !is OrderedListParser && it.canParse(context)
                }
                if (isOtherBlock) break

                itemContent.add(trimmedLine)
                context.currentIndex++
            }
            items.add(itemContent.joinToString("\n").trim())
        }
        return MarkdownBlock.UnorderedList(items.filter { it.isNotBlank() })
    }
}

internal class OrderedListParser : BlockParser {
    override fun canParse(context: ParseContext): Boolean {
        val line = context.currentLine()?.trim()
        if (line == null || !RegexConstants.ORDERED_LIST_REGEX.matches(line)) {
            return false
        }
        // Heuristic: if a line looks like a list item but ends with a colon,
        // treat it as a simple paragraph to avoid misinterpreting headers as list items.
        if (line.endsWith(':')) {
            return false
        }
        return true
    }

    override fun parse(context: ParseContext): MarkdownBlock {
        val items = mutableListOf<String>()
        while (context.hasMoreLines() && canParse(context)) {
            // Check if the current line is a header or other block type before treating as a list item
            val isOtherBlock = MarkdownParser.blockParsers.any {
                it !is OrderedListParser && it !is ParagraphParser && it.canParse(context)
            }
            if (isOtherBlock) {
                break
            }

            val itemContent = mutableListOf<String>()

            // First line of the item
            val firstLine = context.currentLine()!!.trim()
            itemContent.add(firstLine.replaceFirst(Regex("^\\d+\\.\\s+"), "").trim())
            context.currentIndex++

            // Subsequent lines of the same item (continuation)
            while (context.hasMoreLines()) {
                val currentLine = context.currentLine()
                if (currentLine.isNullOrBlank()) {
                    break
                }
                val trimmedLine = currentLine.trim()
                val isNewListItem = RegexConstants.UNORDERED_LIST_REGEX.matches(trimmedLine) ||
                        RegexConstants.ORDERED_LIST_REGEX.matches(trimmedLine)
                if (isNewListItem) break

                val isAnotherBlock = MarkdownParser.blockParsers.any {
                    it !is ParagraphParser && it !is UnorderedListParser && it !is OrderedListParser && it.canParse(context)
                }
                if (isAnotherBlock) break

                itemContent.add(trimmedLine)
                context.currentIndex++
            }
            items.add(itemContent.joinToString("\n").trim())
        }
        return MarkdownBlock.OrderedList(items.filter { it.isNotBlank() })
    }
}

internal class ImageParser : BlockParser {
    override fun canParse(context: ParseContext): Boolean {
        return context.currentLine()?.trim()?.let { RegexConstants.IMAGE_REGEX.matches(it) } == true
    }

    override fun parse(context: ParseContext): MarkdownBlock {
        val line = context.currentLine()!!.trim()
        val match = RegexConstants.IMAGE_REGEX.find(line)!!
        val altText = match.groupValues[1]
        val url = match.groupValues[2]
        context.currentIndex++
        return MarkdownBlock.Image(altText, url)
    }
}

internal class HorizontalRuleParser : BlockParser {
    override fun canParse(context: ParseContext): Boolean {
        return context.currentLine()?.trim()?.let { RegexConstants.HORIZONTAL_RULE_REGEX.matches(it) } == true
    }

    override fun parse(context: ParseContext): MarkdownBlock {
        context.currentIndex++
        return MarkdownBlock.HorizontalRule
    }
}

internal class BlockquoteParser : BlockParser {
    override fun canParse(context: ParseContext): Boolean {
        return context.currentLine()?.trim()?.startsWith(">") == true
    }

    override fun parse(context: ParseContext): MarkdownBlock {
        val quoteLines = mutableListOf<String>()
        while (context.hasMoreLines() && (context.currentLine()?.trim()?.startsWith(">") == true)) {
            val line = context.currentLine()!!.trim()
            quoteLines.add(line.removePrefix(">").trim())
            context.currentIndex++
        }
        val nestedBlocks = MarkdownParser.parse(quoteLines.joinToString("\n"))
        return MarkdownBlock.Blockquote(nestedBlocks)
    }
}

internal class TableParser : BlockParser {
    private val separatorRegex = Regex("^\\s*:?[\\-–—]{2,}:?\\s*$")

    private fun isSeparatorLine(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.contains('|') || !trimmed.contains('-')) return false
        val content = trimmed.removePrefix("|").removeSuffix("|")
        return content.split('|').all { it.matches(separatorRegex) }
    }

    override fun canParse(context: ParseContext): Boolean {
        val currentLine = context.currentLine()?.trim() ?: return false
        val nextLine = context.lines.getOrNull(context.currentIndex + 1)?.trim() ?: return false
        return RegexConstants.TABLE_ROW_REGEX.matches(currentLine) && isSeparatorLine(nextLine)
    }

    override fun parse(context: ParseContext): MarkdownBlock {
        val headerLine = context.currentLine()!!.trim().removePrefix("|").removeSuffix("|")
        val header = headerLine.split("|").map { it.trim() }
        context.currentIndex += 2 // Skip header and separator

        val rows = mutableListOf<List<String>>()
        while (context.hasMoreLines()) {
            val line = context.currentLine()?.trim()
            if (line == null || !RegexConstants.TABLE_ROW_REGEX.matches(line)) {
                break
            }
            val rowContent = line.removePrefix("|").removeSuffix("|")
            val row = rowContent.split("|").map { it.trim() }
            if (row.size == header.size) {
                rows.add(row)
            }
            context.currentIndex++
        }
        return MarkdownBlock.Table(header, rows)
    }
}

internal class ParagraphParser : BlockParser {
    override fun canParse(context: ParseContext): Boolean {
        return context.currentLine()?.isNotBlank() == true
    }

    override fun parse(context: ParseContext): MarkdownBlock {
        val textLines = mutableListOf<String>()
        while (context.hasMoreLines()) {
            val line = context.currentLine()
            if (line.isNullOrBlank() || (line.isNotBlank() && isAnotherBlock(context))) {
                break
            }
            textLines.add(line)
            context.currentIndex++
        }
        return MarkdownBlock.Paragraph(textLines.joinToString("\n").trim())
    }

    private fun isAnotherBlock(context: ParseContext): Boolean {
        return MarkdownParser.blockParsers.any { it !is ParagraphParser && it.canParse(context) }
    }
}

internal class MathBlockParser : BlockParser {
    override fun canParse(context: ParseContext): Boolean {
        return context.currentLine()?.trim()?.let { RegexConstants.MATH_BLOCK_REGEX.matches(it) } == true
    }

    override fun parse(context: ParseContext): MarkdownBlock {
        val line = context.currentLine()!!.trim()
        val formula = line.removeSurrounding("$$").trim()
        context.currentIndex++
        return MarkdownBlock.MathBlock(formula)
    }
}

// --- 4. Main Parser Object ---
object MarkdownParser {
    internal val blockParsers: List<BlockParser> = listOf(
        HorizontalRuleParser(),
        CodeBlockParser(),
        HeaderParser(),
        BlockquoteParser(),
        ImageParser(),
        TableParser(),
        UnorderedListParser(),
        OrderedListParser(),
        MathBlockParser(),
        ParagraphParser() // Fallback
    )

    fun parse(markdown: String): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        // Normalize line endings and then split. This handles \r\n, \n, and \r.
        val lines = markdown.replace(Regex("\\r\\n?"), "\n").split('\n')
        val context = ParseContext(lines, 0)

        while (context.hasMoreLines()) {
            // Skip leading blank lines
            while (context.hasMoreLines() && context.currentLine().isNullOrBlank()) {
                context.currentIndex++
            }
            if (!context.hasMoreLines()) break

            val parser = blockParsers.firstOrNull { it.canParse(context) }
            if (parser != null) {
                val initialIndex = context.currentIndex
                val block = parser.parse(context)

                // Add block if it's not an empty paragraph
                if (block !is MarkdownBlock.Paragraph || block.text.isNotBlank()) {
                    blocks.add(block)
                }

                // Safety break to prevent infinite loops if a parser fails to advance
                if (context.currentIndex == initialIndex) {
                    context.currentIndex++
                }
            } else {
                // Should not happen with ParagraphParser as a fallback, but as a safety measure
                context.currentIndex++
            }
        }
        return blocks
    }
}

fun parseMarkdownToBlocks(markdown: String): List<MarkdownBlock> {
    return MarkdownParser.parse(markdown)
}
