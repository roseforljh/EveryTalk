package com.android.everytalk.ui.components.table

internal data class TableCellImageMarkdown(
    val alt: String,
    val url: String,
)

internal sealed interface TableCellMarkdownPart {
    data class Text(val text: String) : TableCellMarkdownPart
    data class Image(
        val alt: String,
        val url: String,
    ) : TableCellMarkdownPart
}

private val tableCellInlineImageMarkdownPattern =
    Regex("""!\[([^\]]*)]\((<[^>\s]+>|(?:[^\s()]|\([^)]*\))+)(?:\s+(?:"[^"]*"|'[^']*'|\([^)]*\)))?\)""")

private val tableCellImageMarkdownPattern =
    Regex("""^\s*!\[([^\]]*)]\((<[^>\s]+>|(?:[^\s()]|\([^)]*\))+)(?:\s+(?:"[^"]*"|'[^']*'|\([^)]*\)))?\)\s*$""")

internal fun parseTableCellImageMarkdown(content: String): TableCellImageMarkdown? {
    val match = tableCellImageMarkdownPattern.matchEntire(content) ?: return null
    return TableCellImageMarkdown(
        alt = match.groupValues[1],
        url = stripMarkdownDestinationAngleBrackets(match.groupValues[2]),
    )
}

internal fun parseTableCellMarkdownParts(content: String): List<TableCellMarkdownPart>? {
    if (!content.contains("![")) return null

    val matches = tableCellInlineImageMarkdownPattern.findAll(content).toList()
    if (matches.isEmpty()) return null

    val parts = mutableListOf<TableCellMarkdownPart>()
    var cursor = 0
    matches.forEach { match ->
        appendTableCellTextPart(parts, content.substring(cursor, match.range.first))
        parts.add(
            TableCellMarkdownPart.Image(
                alt = match.groupValues[1],
                url = stripMarkdownDestinationAngleBrackets(match.groupValues[2]),
            )
        )
        cursor = match.range.last + 1
    }
    appendTableCellTextPart(parts, content.substring(cursor))

    return parts.ifEmpty { null }
}

private fun appendTableCellTextPart(
    parts: MutableList<TableCellMarkdownPart>,
    rawText: String,
) {
    val text = rawText.trim()
    if (text.isNotEmpty()) {
        parts.add(TableCellMarkdownPart.Text(text))
    }
}

internal fun stripMarkdownDestinationAngleBrackets(destination: String): String {
    return if (destination.length >= 2 && destination.first() == '<' && destination.last() == '>') {
        destination.substring(1, destination.lastIndex)
    } else {
        destination
    }
}
