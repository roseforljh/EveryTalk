package com.android.everytalk.ui.components.markdown

/**
 * 渲染前只修复可确定识别的 Markdown 结构边界。
 *
 * 模型把正文与列表或表头粘在同一行时，GFM 解析器会把整行当作普通段落。独占一行的加粗
 * 小标题紧邻正文时，CommonMark 也会把两行合并为同一段。这里只补齐可确定的块边界，并避开代码围栏。
 */
internal object MarkdownContractValidator {

    fun normalize(markdown: String): String {
        if (markdown.isBlank()) return markdown

        val normalizedLineBreaks = markdown.replace("\r\n", "\n")
        val lines = normalizedLineBreaks.split('\n')
        val fenceTracker = MarkdownFenceTracker()
        val output = ArrayList<String>(lines.size + 2)
        var changed = false
        var index = 0

        while (index < lines.size) {
            val line = lines[index]
            val protectedLine = fenceTracker.isFenceLine(line)
            val nextLine = lines.getOrNull(index + 1)
            val listMarker = findEmbeddedUnorderedListMarker(line) ?: -1
            val firstPipe = findFirstTablePipe(line) ?: -1
            val prefix = if (firstPipe > 0) line.substring(0, firstPipe) else ""
            val tablePart = if (firstPipe >= 0) line.substring(firstPipe).trimStart() else ""
            val canSplitList = !protectedLine && listMarker > 0
            val canSplit = !protectedLine &&
                prefix.isNotBlank() &&
                isPotentialTableRow(tablePart) &&
                nextLine != null &&
                isTableSeparatorRow(nextLine)
            val canSeparateStrongHeading = !protectedLine &&
                isStandaloneStrongLine(line) &&
                nextLine?.isNotBlank() == true

            if (canSplitList) {
                output += line.substring(0, listMarker).trimEnd()
                output += ""
                output += line.substring(listMarker)
                changed = true
            } else if (canSplit) {
                output += prefix.trimEnd()
                output += ""
                output += tablePart
                changed = true
            } else if (canSeparateStrongHeading) {
                if (output.lastOrNull()?.isNotBlank() == true) output += ""
                output += line
                output += ""
                changed = true
            } else {
                output += line
            }
            index++
        }

        return if (changed) output.joinToString("\n") else markdown
    }

    private fun findEmbeddedUnorderedListMarker(line: String): Int? {
        var inInlineCode = false
        for (index in 1 until line.length - 2) {
            if (line[index] == '`') {
                inInlineCode = !inInlineCode
                continue
            }
            if (!inInlineCode &&
                (line[index - 1] == '：' || line[index - 1] == ':') &&
                line[index] == '-' &&
                line[index + 1].isWhitespace() &&
                line.substring(index + 2).isNotBlank()
            ) {
                return index
            }
        }
        return null
    }

    private fun isPotentialTableRow(line: String): Boolean {
        if (!line.startsWith('|') || countUnescapedPipes(line) < 2) return false
        return line.trimEnd().endsWith('|')
    }

    private fun isTableSeparatorRow(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.startsWith('|') || !trimmed.endsWith('|')) return false
        val cells = trimmed.removePrefix("|").removeSuffix("|").split('|')
        return cells.isNotEmpty() && cells.all(::isTableSeparatorCell)
    }

    private fun isTableSeparatorCell(cell: String): Boolean {
        val trimmed = cell.trim()
        var start = 0
        var endExclusive = trimmed.length
        if (trimmed.getOrNull(start) == ':') start++
        if (endExclusive > start && trimmed[endExclusive - 1] == ':') endExclusive--
        if (endExclusive - start < 3) return false
        return (start until endExclusive).all { trimmed[it] == '-' }
    }

    private fun isStandaloneStrongLine(line: String): Boolean {
        val trimmed = line.trim()
        val delimiter = when {
            trimmed.startsWith("**") && trimmed.endsWith("**") -> '*'
            trimmed.startsWith("__") && trimmed.endsWith("__") -> '_'
            else -> return false
        }
        if (trimmed.length <= 4 || trimmed[2] == delimiter || trimmed[trimmed.length - 3] == delimiter) {
            return false
        }
        val content = trimmed.substring(2, trimmed.length - 2)
        return content.isNotBlank() && !containsUnescapedDelimiter(content, delimiter)
    }

    private fun containsUnescapedDelimiter(content: String, delimiter: Char): Boolean {
        var index = 0
        while (index < content.length - 1) {
            if (content[index] == '\\') {
                index += 2
            } else if (content[index] == delimiter && content[index + 1] == delimiter) {
                return true
            } else {
                index++
            }
        }
        return false
    }

    private fun countUnescapedPipes(line: String): Int {
        var count = 0
        var escaped = false
        var inInlineCode = false
        for (char in line) {
            when {
                char == '`' && !escaped -> inInlineCode = !inInlineCode
                char == '|' && !escaped && !inInlineCode -> count++
                char == '\\' -> escaped = !escaped
                else -> escaped = false
            }
        }
        return count
    }

    private fun findFirstTablePipe(line: String): Int? {
        var escaped = false
        var inInlineCode = false
        line.forEachIndexed { index, char ->
            when {
                char == '`' && !escaped -> inInlineCode = !inInlineCode
                char == '|' && !escaped && !inInlineCode -> return index
                char == '\\' -> escaped = !escaped
                else -> escaped = false
            }
        }
        return null
    }
}
