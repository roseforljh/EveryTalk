package com.android.everytalk.data.computer

import java.text.Normalizer

/** 一次精准文本替换。所有 oldText 都在修改前的原文件中匹配。 */
internal data class ComputerTextEdit(
    val oldText: String,
    val newText: String,
)

/** edit 工具的纯文本处理结果，文件读写由 ComputerFileTransfer 负责。 */
internal data class ComputerTextEditResult(
    val content: String,
    val replacements: Int,
)

/**
 * 实现与 pi edit 相同的核心替换规则。
 *
 * 每段旧文本必须唯一，多段修改不能重叠。匹配时兼容换行符、BOM、行尾空白和常见 Unicode 标点，
 * 写回时保留原文件 BOM、换行风格以及未触碰行的原始内容。
 */
internal object ComputerTextEditor {
    fun apply(rawContent: String, edits: List<ComputerTextEdit>, path: String): ComputerTextEditResult {
        require(edits.isNotEmpty()) { "edits 至少需要一项替换" }

        val hasBom = rawContent.startsWith('\uFEFF')
        val content = if (hasBom) rawContent.substring(1) else rawContent
        val lineEnding = detectLineEnding(content)
        val normalizedContent = normalizeToLf(content)
        val normalizedEdits = edits.map { edit ->
            ComputerTextEdit(normalizeToLf(edit.oldText), normalizeToLf(edit.newText))
        }
        normalizedEdits.forEachIndexed { index, edit ->
            require(edit.oldText.isNotEmpty()) { "edits[$index].oldText 不能为空：$path" }
        }

        val useFuzzyMatch = normalizedEdits.any { edit ->
            normalizedContent.indexOf(edit.oldText) < 0 &&
                normalizeForFuzzyMatch(normalizedContent).indexOf(normalizeForFuzzyMatch(edit.oldText)) >= 0
        }
        val replacementBase = if (useFuzzyMatch) normalizeForFuzzyMatch(normalizedContent) else normalizedContent
        val matches = normalizedEdits.mapIndexed { index, edit ->
            val exactIndex = replacementBase.indexOf(edit.oldText)
            val matchedText = if (exactIndex >= 0) edit.oldText else normalizeForFuzzyMatch(edit.oldText)
            val matchIndex = if (exactIndex >= 0) exactIndex else replacementBase.indexOf(matchedText)
            require(matchIndex >= 0) {
                "无法在 $path 中找到 edits[$index].oldText，空白和换行必须与文件内容一致"
            }
            val occurrences = countOccurrences(normalizeForFuzzyMatch(replacementBase), normalizeForFuzzyMatch(edit.oldText))
            require(occurrences == 1) {
                "edits[$index].oldText 在 $path 中出现 $occurrences 次，必须增加上下文使其唯一"
            }
            MatchedEdit(index, matchIndex, matchedText.length, edit.newText)
        }.sortedBy(MatchedEdit::matchIndex)

        matches.zipWithNext().forEach { (previous, current) ->
            require(previous.matchIndex + previous.matchLength <= current.matchIndex) {
                "edits[${previous.editIndex}] 与 edits[${current.editIndex}] 在 $path 中重叠"
            }
        }

        val newContent = if (useFuzzyMatch) {
            applyPreservingUnchangedLines(normalizedContent, replacementBase, matches)
        } else {
            applyReplacements(replacementBase, matches)
        }
        require(newContent != normalizedContent) { "替换后 $path 的内容没有变化" }

        val restored = if (lineEnding == "\r\n") newContent.replace("\n", "\r\n") else newContent
        return ComputerTextEditResult(
            content = (if (hasBom) "\uFEFF" else "") + restored,
            replacements = edits.size,
        )
    }

    private fun detectLineEnding(content: String): String {
        val firstLf = content.indexOf('\n')
        val firstCrLf = content.indexOf("\r\n")
        return if (firstCrLf >= 0 && firstCrLf < firstLf) "\r\n" else "\n"
    }

    private fun normalizeToLf(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    /** pi 的宽容匹配只处理表现等价的字符，不忽略缩进或正文空格。 */
    private fun normalizeForFuzzyMatch(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val trimmedLines = buildString(normalized.length) {
            var lineStart = 0
            while (lineStart <= normalized.length) {
                val newline = normalized.indexOf('\n', lineStart)
                val lineEnd = if (newline >= 0) newline else normalized.length
                var trimmedEnd = lineEnd
                while (trimmedEnd > lineStart && normalized[trimmedEnd - 1].isTrailingWhitespace()) trimmedEnd--
                append(normalized, lineStart, trimmedEnd)
                if (newline < 0) break
                append('\n')
                lineStart = newline + 1
            }
        }
        return trimmedLines.map { character ->
            when (character) {
                '\u2018', '\u2019', '\u201A', '\u201B' -> '\''
                '\u201C', '\u201D', '\u201E', '\u201F' -> '"'
                '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2212' -> '-'
                '\u00A0', in '\u2002'..'\u200A', '\u202F', '\u205F', '\u3000' -> ' '
                else -> character
            }
        }.joinToString("")
    }

    private fun Char.isTrailingWhitespace(): Boolean = isWhitespace() || Character.isSpaceChar(this)

    private fun countOccurrences(content: String, text: String): Int {
        var count = 0
        var start = 0
        while (start <= content.length - text.length) {
            val match = content.indexOf(text, start)
            if (match < 0) break
            count++
            start = match + text.length
        }
        return count
    }

    private fun applyReplacements(content: String, matches: List<MatchedEdit>, offset: Int = 0): String {
        val result = StringBuilder(content)
        matches.asReversed().forEach { match ->
            val start = match.matchIndex - offset
            result.replace(start, start + match.matchLength, match.newText)
        }
        return result.toString()
    }

    /** 宽容匹配只重写实际命中的行，避免顺带规范化文件的其他部分。 */
    private fun applyPreservingUnchangedLines(
        originalContent: String,
        baseContent: String,
        matches: List<MatchedEdit>,
    ): String {
        val originalLines = splitLinesWithEndings(originalContent)
        val baseLines = lineSpans(baseContent)
        check(originalLines.size == baseLines.size) { "宽容匹配前后的行数不一致" }

        val groups = mutableListOf<ReplacementGroup>()
        matches.forEach { match ->
            val range = replacementLineRange(baseLines, match)
            val current = groups.lastOrNull()
            if (current != null && range.first < current.endLine) {
                current.endLine = maxOf(current.endLine, range.last + 1)
                current.matches += match
            } else {
                groups += ReplacementGroup(range.first, range.last + 1, mutableListOf(match))
            }
        }

        return buildString(originalContent.length) {
            var originalLineIndex = 0
            groups.forEach { group ->
                append(originalLines.subList(originalLineIndex, group.startLine).joinToString(""))
                val startOffset = baseLines[group.startLine].first
                val endOffset = baseLines[group.endLine - 1].last + 1
                append(applyReplacements(baseContent.substring(startOffset, endOffset), group.matches, startOffset))
                originalLineIndex = group.endLine
            }
            append(originalLines.subList(originalLineIndex, originalLines.size).joinToString(""))
        }
    }

    private fun splitLinesWithEndings(content: String): List<String> {
        if (content.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var start = 0
        while (start < content.length) {
            val newline = content.indexOf('\n', start)
            if (newline < 0) {
                lines += content.substring(start)
                break
            }
            lines += content.substring(start, newline + 1)
            start = newline + 1
        }
        return lines
    }

    private fun lineSpans(content: String): List<IntRange> {
        var offset = 0
        return splitLinesWithEndings(content).map { line ->
            val span = offset until offset + line.length
            offset += line.length
            span
        }
    }

    private fun replacementLineRange(lines: List<IntRange>, match: MatchedEdit): IntRange {
        val startLine = lines.indexOfFirst { match.matchIndex in it }
        check(startLine >= 0) { "替换范围超出文件内容" }
        val matchEnd = match.matchIndex + match.matchLength
        var endLine = startLine
        while (endLine < lines.lastIndex && lines[endLine].last + 1 < matchEnd) endLine++
        check(lines[endLine].last + 1 >= matchEnd) { "替换范围超出文件内容" }
        return startLine..endLine
    }

    private data class MatchedEdit(
        val editIndex: Int,
        val matchIndex: Int,
        val matchLength: Int,
        val newText: String,
    )

    private data class ReplacementGroup(
        val startLine: Int,
        var endLine: Int,
        val matches: MutableList<MatchedEdit>,
    )
}
