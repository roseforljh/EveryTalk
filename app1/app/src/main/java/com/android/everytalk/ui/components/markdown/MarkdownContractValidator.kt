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

        val fenceRecovered = recoverMalformedFenceBoundaries(markdown)
        val normalizedLineBreaks = fenceRecovered.replace("\r\n", "\n")
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

        return if (changed) output.joinToString("\n") else fenceRecovered
    }

    /**
     * 模型偶尔把起始围栏粘在引导正文末尾，后续独立围栏会被 GFM 反向识别成未闭合起点。
     * 仅在简单语言标记能与后续孤立关闭围栏无歧义配对时拆行，遇到其他合法围栏立即放弃恢复。
     */
    fun recoverMalformedFenceBoundaries(markdown: String): String {
        if (markdown.isEmpty() || (!markdown.contains("```") && !markdown.contains("~~~"))) {
            return markdown
        }

        val lines = splitSourceLines(markdown)
        val repairOffsets = IntArray(lines.size) { -1 }
        val fences = MarkdownFenceTracker()
        var index = 0

        while (index < lines.size) {
            val line = lines[index].text
            if (fences.isFenceLine(line)) {
                index++
                continue
            }

            val opening = findEmbeddedFenceOpening(line)
            if (opening == null) {
                index++
                continue
            }

            val closingIndex = findUnambiguousFenceClose(lines, index + 1, opening)
            if (closingIndex < 0) {
                index++
                continue
            }

            repairOffsets[index] = opening.offset
            index = closingIndex + 1
        }

        if (repairOffsets.all { it < 0 }) return markdown

        return buildString(markdown.length + lines.size) {
            lines.forEachIndexed { lineIndex, line ->
                val repairOffset = repairOffsets[lineIndex]
                if (repairOffset >= 0) {
                    append(line.text, 0, repairOffset)
                    append(line.ending)
                    append(line.text, repairOffset, line.text.length)
                } else {
                    append(line.text)
                }
                append(line.ending)
            }
        }
    }

    private data class EmbeddedFenceOpening(
        val offset: Int,
        val marker: Char,
        val length: Int,
    )

    private fun findEmbeddedFenceOpening(line: String): EmbeddedFenceOpening? {
        val containerLine = parseMarkdownContainerLine(line, emptyList()) ?: return null
        if (containerLine.contentStart == null || containerLine.containers.isNotEmpty()) return null

        var cursor = 0
        while (cursor < line.length) {
            val marker = line[cursor]
            if (marker != '`' && marker != '~') {
                cursor++
                continue
            }

            val length = countRun(line, cursor, marker)
            val prefix = line.substring(0, cursor)
            val info = line.substring(cursor + length).trim()
            if (
                length >= 3 &&
                prefix.isNotBlank() &&
                !isEscaped(line, cursor) &&
                isSimpleFenceInfo(info, prefix)
            ) {
                return EmbeddedFenceOpening(offset = cursor, marker = marker, length = length)
            }
            cursor += length.coerceAtLeast(1)
        }
        return null
    }

    private fun isSimpleFenceInfo(info: String, prefix: String): Boolean {
        if (info.isEmpty()) {
            val prefixEnd = prefix.trimEnd().lastOrNull()
            return prefixEnd == ':' || prefixEnd == '：'
        }
        return info.all { char ->
            char in 'a'..'z' ||
                char in 'A'..'Z' ||
                char in '0'..'9' ||
                char == '_' ||
                char == '+' ||
                char == '-' ||
                char == '#' ||
                char == '.'
        }
    }

    private fun findUnambiguousFenceClose(
        lines: List<SourceLine>,
        startIndex: Int,
        opening: EmbeddedFenceOpening,
    ): Int {
        val marker = FenceMarker(
            marker = opening.marker,
            length = opening.length,
            containers = emptyList(),
        )
        for (index in startIndex until lines.size) {
            val line = lines[index].text
            if (isFenceClosingLine(line, marker)) return index
            if (parseFenceOpeningLine(line, emptyList()).marker != null) return -1
        }
        return -1
    }

    private fun isEscaped(line: String, offset: Int): Boolean {
        var backslashes = 0
        var cursor = offset - 1
        while (cursor >= 0 && line[cursor] == '\\') {
            backslashes++
            cursor--
        }
        return backslashes % 2 == 1
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
