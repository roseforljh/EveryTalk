package com.android.everytalk.ui.components.markdown

/**
 * 渲染前只修复可确定识别的 Markdown 结构边界。
 *
 * 模型把正文和表头粘在同一行时，GFM 解析器会把整行当作普通段落。这里不改写表格内容，
 * 只在下一行确实是表格分隔行时，把表头从正文中拆出来，并避开代码围栏。
 */
internal object MarkdownContractValidator {

    fun normalize(markdown: String): String {
        if (markdown.isBlank() || '|' !in markdown) return markdown

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
            val firstPipe = findFirstTablePipe(line) ?: -1
            val prefix = if (firstPipe > 0) line.substring(0, firstPipe) else ""
            val tablePart = if (firstPipe >= 0) line.substring(firstPipe).trimStart() else ""
            val canSplit = !protectedLine &&
                prefix.isNotBlank() &&
                isPotentialTableRow(tablePart) &&
                nextLine != null &&
                isTableSeparatorRow(nextLine)

            if (canSplit) {
                output += prefix.trimEnd()
                output += ""
                output += tablePart
                changed = true
            } else {
                output += line
            }
            index++
        }

        return if (changed) output.joinToString("\n") else markdown
    }

    private fun isPotentialTableRow(line: String): Boolean {
        if (!line.startsWith('|') || countUnescapedPipes(line) < 2) return false
        return line.trimEnd().endsWith('|')
    }

    private fun isTableSeparatorRow(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.startsWith('|') || !trimmed.endsWith('|')) return false
        val cells = trimmed.removePrefix("|").removeSuffix("|").split('|')
        return cells.isNotEmpty() && cells.all { cell ->
            cell.trim().matches(Regex("^:?-{3,}:?$"))
        }
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
