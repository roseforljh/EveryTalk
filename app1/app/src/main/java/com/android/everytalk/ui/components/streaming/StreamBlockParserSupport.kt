package com.android.everytalk.ui.components.streaming

import com.android.everytalk.ui.components.markdown.preprocessMarkdownExtensions
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

internal val fencedBlockLanguageRegex = Regex("^[A-Za-z0-9_+\\-#.]+$")
internal val openingFenceRegex = Regex("^([`~]{3,})([^`~]*)$")
internal val latexCommandRegex = Regex("""\\[A-Za-z]+""")
internal val proseWordRegex = Regex("""[A-Za-z]{2,}""")
internal val listItemRegex = Regex("""^([ ]{0,3})([*+-]|\d{1,9}[.)])([ \t]+|$)""")
internal val rawHtmlCodeElementNames = setOf("script", "pre", "style", "textarea")
internal val renderableMarkdownFenceLanguages = setOf("markdown", "md")

internal data class DelimiterMatch(
    val start: Int,
    val token: String,
    val type: StreamBlockType,
    val stateWhenClosed: MathBlockState,
    val stateWhenPending: MathBlockState,
)

internal fun findNextInlineMathStart(
    content: String,
    startIndex: Int,
    mathProtectionMask: BooleanArray,
): Int {
    var index = content.indexOf('$', startIndex)
    while (index >= 0) {
        val escaped = index > 0 && content[index - 1] == '\\'
        val isDoubleDollar = index + 1 < content.length && content[index + 1] == '$'
        if (
            !escaped &&
            !isDoubleDollar &&
            !isMathDelimiterProtected(content, index, mathProtectionMask) &&
            !isCurrencyDollar(content, index, mathProtectionMask)
        ) {
            return index
        }
        index = content.indexOf('$', index + 1)
    }
    return -1
}

internal fun isCurrencyDollar(
    content: String,
    index: Int,
    mathProtectionMask: BooleanArray,
): Boolean {
    if (index + 1 >= content.length || !content[index + 1].isDigit()) return false

    val closingIndex = findNextUnescapedSingleDollarOnLine(content, index + 1, mathProtectionMask)
    if (closingIndex == -1) return true
    if (closingIndex + 1 < content.length && content[closingIndex + 1].isDigit()) return true

    val body = content.substring(index + 1, closingIndex)
    return !isDigitStartedMathBody(body)
}

internal fun isDigitStartedMathBody(body: String): Boolean {
    val trimmed = body.trim()
    if (latexCommandRegex.containsMatchIn(trimmed)) return true
    val normalized = latexCommandRegex.replace(trimmed, "")
    if (normalized.isEmpty()) return false
    val hasCjk = normalized.any { it.code in 0x4E00..0x9FFF }
    val hasProseWord = proseWordRegex.containsMatchIn(normalized)
    return !hasCjk && !hasProseWord
}

internal fun findNextUnescapedSingleDollarOnLine(
    content: String,
    startIndex: Int,
    mathProtectionMask: BooleanArray,
): Int {
    var index = startIndex
    while (index < content.length) {
        val ch = content[index]
        if (ch == '\n' || ch == '\r') return -1
        if (ch == '`') {
            index++
            while (index < content.length && content[index] != '`') {
                if (content[index] == '\n' || content[index] == '\r') return -1
                index++
            }
            if (index < content.length) index++
            continue
        }

        val escaped = index > 0 && content[index - 1] == '\\'
        if (ch == '$' && !escaped) {
            if (isMathDelimiterProtected(content, index, mathProtectionMask)) {
                index++
                continue
            }
            if (index + 1 < content.length && content[index + 1] == '$') {
                index += 2
                continue
            }
            return index
        }
        index++
    }
    return -1
}


internal fun unwrapRenderableMarkdownFences(content: String): String {
    if (content.isEmpty() || (!content.contains("```") && !content.contains("~~~"))) {
        return content
    }

    val output = StringBuilder(content.length)
    var outerMarker: String? = null
    var nestedCodeMarker: String? = null
    var ordinaryCodeMarker: String? = null
    var changed = false
    var lineStart = 0

    while (lineStart < content.length) {
        val newline = content.indexOf('\n', lineStart)
        val lineEnd = if (newline >= 0) newline else content.length
        val segmentEnd = if (newline >= 0) newline + 1 else content.length
        val segment = content.substring(lineStart, segmentEnd)
        val line = content.substring(lineStart, lineEnd).removeSuffix("\r")
        val trimmedLine = line.trim()
        val hasRenderableFenceIndent = hasRenderableMarkdownFenceIndent(line)
        val fenceMatch = openingFenceRegex.matchEntire(trimmedLine)
        val marker = fenceMatch?.groupValues?.get(1)
        val language = fenceMatch
            ?.groupValues
            ?.get(2)
            ?.trim()
            ?.lowercase(Locale.ROOT)
        val activeOuterMarker = outerMarker
        val activeNestedMarker = nestedCodeMarker
        val activeOrdinaryMarker = ordinaryCodeMarker

        when {
            activeOuterMarker == null && activeOrdinaryMarker != null -> {
                output.append(segment)
                if (isStandaloneFenceClose(trimmedLine, activeOrdinaryMarker)) {
                    ordinaryCodeMarker = null
                }
            }

            activeOuterMarker == null &&
                marker != null &&
                hasRenderableFenceIndent &&
                language in renderableMarkdownFenceLanguages -> {
                outerMarker = marker
                changed = true
            }

            activeOuterMarker == null -> {
                output.append(segment)
                if (marker != null) {
                    ordinaryCodeMarker = marker
                }
            }

            activeNestedMarker != null -> {
                output.append(segment)
                if (isStandaloneFenceClose(trimmedLine, activeNestedMarker)) {
                    nestedCodeMarker = null
                }
            }

            hasRenderableFenceIndent && isStandaloneFenceClose(trimmedLine, activeOuterMarker) -> {
                outerMarker = null
                changed = true
            }

            else -> {
                output.append(segment)
                if (marker != null && !language.isNullOrEmpty()) {
                    nestedCodeMarker = marker
                }
            }
        }

        lineStart = segmentEnd
    }

    return if (changed) output.toString() else content
}

internal fun hasRenderableMarkdownFenceIndent(line: String): Boolean {
    var spaces = 0
    while (spaces < line.length && spaces < 4 && line[spaces] == ' ') {
        spaces++
    }
    return spaces <= 3 &&
        spaces < line.length &&
        (line[spaces] == '`' || line[spaces] == '~')
}

internal fun isStandaloneFenceClose(line: String, marker: String): Boolean {
    if (line.isEmpty() || line.first() != marker.first()) return false
    val markerLength = countFenceMarkerLength(line, marker.first())
    return markerLength >= marker.length && line.substring(markerLength).isBlank()
}


internal enum class FenceContainerKind {
    BLOCK_QUOTE,
    LIST_ITEM,
}

internal data class FenceContainerSegment(
    val kind: FenceContainerKind,
    val contentIndentColumns: Int = 0,
)

internal data class FenceContainerPrefix(
    val segments: List<FenceContainerSegment>,
    val fenceIndentColumns: Int,
)

internal data class FenceStart(
    val start: Int,
    val marker: String,
    val language: String,
    val headerEnd: Int,
    val indent: Int,
    val containerPath: List<FenceContainerSegment>,
)

internal fun findNextFenceStart(content: String, startIndex: Int): Int {
    var searchIndex = startIndex
    while (searchIndex < content.length) {
        val backtick = content.indexOf("```", searchIndex).takeIf { it >= 0 }
        val tilde = content.indexOf("~~~", searchIndex).takeIf { it >= 0 }
        val candidate = listOfNotNull(backtick, tilde).minOrNull() ?: return -1
        if (parseFenceStart(content, candidate) != null) return candidate
        searchIndex = candidate + 1
    }
    return -1
}

internal fun findNextInlineCodeStart(content: String, startIndex: Int): Int {
    var candidate = content.indexOf('`', startIndex)
    while (candidate >= 0) {
        if (findInlineCodeEnd(content, candidate) != null) return candidate
        candidate = content.indexOf('`', candidate + 1)
    }
    return -1
}

internal fun findNextMathTokenStart(
    content: String,
    token: String,
    startIndex: Int,
    mathProtectionMask: BooleanArray,
): Int {
    var candidate = content.indexOf(token, startIndex)
    while (candidate >= 0) {
        if (!isMathDelimiterProtected(content, candidate, mathProtectionMask)) return candidate
        candidate = content.indexOf(token, candidate + 1)
    }
    return -1
}

internal fun findNextEscapedBlockMathStart(
    content: String,
    startIndex: Int,
    mathProtectionMask: BooleanArray,
): Int {
    var candidate = content.indexOf("\\[", startIndex)
    while (candidate >= 0) {
        if (!isMathDelimiterProtected(content, candidate, mathProtectionMask)) {
            val close = findNextMathTokenStart(content, "\\]", candidate + 2, mathProtectionMask)
            val bodyEnd = if (close >= 0) close else content.length
            if (isLikelyEscapedBlockMathBody(content.substring(candidate + 2, bodyEnd))) {
                return candidate
            }
        }
        candidate = content.indexOf("\\[", candidate + 2)
    }
    return -1
}

// Markdown 转义中括号与 TeX 块公式共用 \[...\]，仅接受含明确数学特征的内容。
internal fun isLikelyEscapedBlockMathBody(body: String): Boolean {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return false
    if (latexCommandRegex.containsMatchIn(trimmed)) return true
    if (trimmed.any { it.code in 0x4E00..0x9FFF }) return false
    if (trimmed.any { it in "=+*^<>≤≥≠±×÷∑∫√{}&" }) return true
    if (proseWordRegex.containsMatchIn(trimmed)) return false
    if (trimmed.any { it in "-/_" }) return true
    return trimmed.any { it.isLetterOrDigit() }
}

internal fun isMathDelimiterProtected(
    content: String,
    index: Int,
    mathProtectionMask: BooleanArray,
): Boolean =
    index in mathProtectionMask.indices &&
        (mathProtectionMask[index] || isInsideMarkdownLinkDestination(content, index))

internal fun buildMathProtectionMask(content: String): BooleanArray {
    val mask = BooleanArray(content.length)
    markIndentedCodeBlocks(content, mask)
    markHtmlTagsAndAutolinks(content, mask)
    markBareUrls(content, mask)
    return mask
}

/**
 * CommonMark 的缩进代码相对当前容器内容基线至少缩进四列，并且不能直接中断段落。
 * 空白行允许连接同一代码块；列表内容基线与引用前缀在这里统一扣除。
 */
internal fun markIndentedCodeBlocks(content: String, mask: BooleanArray) {
    var lineStart = 0
    var previousLineBlank = true
    var inIndentedCode = false
    var activeListContentIndent: Int? = null
    var activeListQuoteDepth = -1

    while (lineStart < content.length) {
        val newline = content.indexOf('\n', lineStart)
        val lineEnd = if (newline >= 0) newline else content.length
        val segmentEnd = if (newline >= 0) newline + 1 else content.length
        val contentEnd = if (lineEnd > lineStart && content[lineEnd - 1] == '\r') lineEnd - 1 else lineEnd
        val line = content.substring(lineStart, contentEnd)
        val container = parseBlockQuoteContainer(line)
        val body = line.substring(container.contentStart)
        val isBlank = body.isBlank()
        val indentColumns = countLeadingIndentColumns(body)
        val listItem = listItemRegex.find(body)

        if (listItem != null) {
            activeListContentIndent = listContentIndent(listItem)
            activeListQuoteDepth = container.quoteDepth
        } else if (
            !isBlank &&
            activeListContentIndent != null &&
            (container.quoteDepth != activeListQuoteDepth || indentColumns < activeListContentIndent)
        ) {
            activeListContentIndent = null
            activeListQuoteDepth = -1
        }

        val containerContentIndent = activeListContentIndent
            ?.takeIf { container.quoteDepth == activeListQuoteDepth }
            ?: 0
        val isIndentedCodeLine =
            !isBlank && listItem == null && indentColumns >= containerContentIndent + 4

        when {
            isBlank -> Unit
            isIndentedCodeLine && (inIndentedCode || previousLineBlank) -> {
                mask.fill(true, lineStart, segmentEnd)
                inIndentedCode = true
            }
            else -> inIndentedCode = false
        }

        previousLineBlank = isBlank
        lineStart = segmentEnd
    }
}

internal data class MarkdownContainer(
    val contentStart: Int,
    val quoteDepth: Int,
)

internal fun parseBlockQuoteContainer(line: String): MarkdownContainer {
    var cursor = 0
    var quoteDepth = 0
    while (cursor < line.length) {
        cursor = consumeBlockQuoteMarker(line, cursor) ?: break
        quoteDepth++
    }
    return MarkdownContainer(contentStart = cursor, quoteDepth = quoteDepth)
}

internal fun consumeBlockQuoteMarker(text: String, start: Int): Int? {
    var marker = start
    var spaces = 0
    while (marker < text.length && spaces < 3 && text[marker] == ' ') {
        marker++
        spaces++
    }
    if (marker >= text.length || text[marker] != '>') return null

    var contentStart = marker + 1
    if (contentStart < text.length && (text[contentStart] == ' ' || text[contentStart] == '\t')) {
        contentStart++
    }
    return contentStart
}

internal fun listContentIndent(match: MatchResult): Int {
    val leadingSpaces = match.groupValues[1].length
    val markerWidth = match.groupValues[2].length
    val markerEndColumn = leadingSpaces + markerWidth
    val padding = match.groupValues[3]
    if (padding.isEmpty()) return markerEndColumn + 1

    val paddedColumn = advanceIndentColumns(padding, markerEndColumn)
    return markerEndColumn + (paddedColumn - markerEndColumn).coerceIn(1, 4)
}

internal fun countLeadingIndentColumns(line: String): Int {
    var columns = 0
    for (char in line) {
        when (char) {
            ' ' -> columns++
            '\t' -> columns += 4 - (columns % 4)
            else -> return columns
        }
    }
    return columns
}

internal fun advanceIndentColumns(text: String, initialColumn: Int): Int {
    var columns = initialColumn
    for (char in text) {
        when (char) {
            ' ' -> columns++
            '\t' -> columns += 4 - (columns % 4)
            else -> return columns
        }
    }
    return columns
}

internal fun markHtmlTagsAndAutolinks(content: String, mask: BooleanArray) {
    var searchIndex = 0
    while (searchIndex < content.length) {
        val start = content.indexOf('<', searchIndex)
        if (start < 0) return
        if (!isHtmlTagOrAutolinkStart(content, start)) {
            searchIndex = start + 1
            continue
        }

        val tagEndExclusive = findHtmlTagEnd(content, start)
        val endExclusive = findRawHtmlCodeElementEnd(
            content = content,
            start = start,
            openingEndExclusive = tagEndExclusive,
        ) ?: tagEndExclusive
        mask.fill(true, start, endExclusive)
        searchIndex = endExclusive
    }
}

internal fun findRawHtmlCodeElementEnd(
    content: String,
    start: Int,
    openingEndExclusive: Int,
): Int? {
    var nameStart = start + 1
    if (nameStart >= content.length || content[nameStart] == '/') return null
    while (nameStart < content.length && content[nameStart].isWhitespace()) nameStart++
    val nameEnd = content.indexOfFirstFrom(nameStart) { !it.isLetterOrDigit() && it != '-' }
    if (nameEnd <= nameStart) return null
    val name = content.substring(nameStart, nameEnd).lowercase(Locale.ROOT)
    if (name !in rawHtmlCodeElementNames) return null
    if (content.substring(start, openingEndExclusive).trimEnd().endsWith("/>")) return null

    var closingStart = content.indexOf("</$name", openingEndExclusive, ignoreCase = true)
    while (closingStart >= 0) {
        val boundary = closingStart + name.length + 2
        if (
            boundary >= content.length ||
            content[boundary].isWhitespace() ||
            content[boundary] == '>'
        ) {
            return findHtmlTagEnd(content, closingStart)
        }
        closingStart = content.indexOf("</$name", closingStart + 2, ignoreCase = true)
    }
    return content.length
}

internal inline fun String.indexOfFirstFrom(
    startIndex: Int,
    predicate: (Char) -> Boolean,
): Int {
    for (index in startIndex until length) {
        if (predicate(this[index])) return index
    }
    return length
}

internal fun isHtmlTagOrAutolinkStart(content: String, start: Int): Boolean {
    if (start + 1 >= content.length) return false
    if (
        content.regionMatches(start, "<http://", 0, 8, ignoreCase = true) ||
        content.regionMatches(start, "<https://", 0, 9, ignoreCase = true)
    ) {
        return true
    }
    val angleEnd = content.indexOf('>', start + 1)
    if (angleEnd >= 0) {
        val target = content.substring(start + 1, angleEnd)
        if (target.contains('@') && target.none { it.isWhitespace() }) return true
    }

    var nameStart = start + 1
    when (content[nameStart]) {
        '!', '?' -> return true
        '/' -> {
            nameStart++
            if (nameStart >= content.length) return false
        }
    }
    if (!content[nameStart].isLetter()) return false

    var cursor = nameStart + 1
    while (
        cursor < content.length &&
        (content[cursor].isLetterOrDigit() || content[cursor] == '-' || content[cursor] == ':' || content[cursor] == '_')
    ) {
        cursor++
    }
    if (cursor >= content.length) return true
    return content[cursor].isWhitespace() || content[cursor] == '/' || content[cursor] == '>'
}

internal fun findHtmlTagEnd(content: String, start: Int): Int {
    if (content.startsWith("<!--", start)) {
        val commentEnd = content.indexOf("-->", start + 4)
        return if (commentEnd >= 0) commentEnd + 3 else content.length
    }

    var quote: Char? = null
    var cursor = start + 1
    while (cursor < content.length) {
        val char = content[cursor]
        when {
            quote != null && char == quote && (cursor == start + 1 || content[cursor - 1] != '\\') -> quote = null
            quote == null && (char == '\'' || char == '"') -> quote = char
            quote == null && char == '>' -> return cursor + 1
        }
        cursor++
    }
    return content.length
}

internal fun markBareUrls(content: String, mask: BooleanArray) {
    var searchIndex = 0
    while (searchIndex < content.length) {
        val http = content.indexOf("http://", searchIndex, ignoreCase = true).takeIf { it >= 0 }
        val https = content.indexOf("https://", searchIndex, ignoreCase = true).takeIf { it >= 0 }
        val start = listOfNotNull(http, https).minOrNull() ?: return
        if (start > 0 && (content[start - 1].isLetterOrDigit() || content[start - 1] in "_.-")) {
            searchIndex = start + 1
            continue
        }

        var endExclusive = start
        while (endExclusive < content.length && !isBareUrlTerminator(content[endExclusive])) {
            endExclusive++
        }
        mask.fill(true, start, endExclusive)
        searchIndex = endExclusive.coerceAtLeast(start + 1)
    }
}

internal fun isBareUrlTerminator(char: Char): Boolean =
    char.isWhitespace() || char in "<>\"'`，。；！？、"

internal fun isInsideMarkdownLinkDestination(content: String, index: Int): Boolean {
    if (index !in content.indices) return false
    val lineStart = content.lastIndexOf('\n', index).let { if (it < 0) 0 else it + 1 }
    var searchIndex = index
    while (searchIndex > lineStart) {
        val open = content.lastIndexOf('(', searchIndex - 1)
        if (open < lineStart) return false
        val isLinkDestination = open > lineStart &&
            content[open - 1] == ']'
        if (isLinkDestination) {
            val close = findMarkdownLinkDestinationEnd(content, open)
            return close == null || index < close
        }
        searchIndex = open
    }
    return false
}

internal fun findMarkdownLinkDestinationEnd(content: String, open: Int): Int? {
    var depth = 1
    var index = open + 1
    while (index < content.length) {
        when (content[index]) {
            '\n', '\r' -> return null
            '\\' -> index++
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return index
            }
        }
        index++
    }
    return null
}

internal fun findInlineCodeEnd(content: String, start: Int): Int? {
    if (start !in content.indices || content[start] != '`') return null
    val markerLength = countFenceMarkerLength(content.substring(start), '`')
    val marker = "`".repeat(markerLength)
    var searchIndex = start + markerLength
    while (searchIndex < content.length) {
        val close = content.indexOf(marker, searchIndex)
        if (close < 0) return null
        val precededByBacktick = close > 0 && content[close - 1] == '`'
        val followedByBacktick = close + markerLength < content.length && content[close + markerLength] == '`'
        if (!precededByBacktick && !followedByBacktick) return close + markerLength
        searchIndex = close + markerLength
    }
    return null
}

internal fun parseFenceStart(content: String, start: Int): FenceStart? {
    if (start < 0 || start >= content.length) return null
    val lineStart = content.lastIndexOf('\n', start).let { if (it < 0) 0 else it + 1 }
    val containerPrefix = parseFenceContainerPrefix(content.substring(lineStart, start)) ?: return null

    val headerEnd = content.indexOf('\n', start).let { if (it >= 0) it else content.length }
    val trimmedHeader = content.substring(start, headerEnd).trim()
    val match = openingFenceRegex.matchEntire(trimmedHeader) ?: return null
    val marker = match.groupValues[1]
    val language = match.groupValues[2].trim()
    return FenceStart(
        start = start,
        marker = marker,
        language = language,
        headerEnd = headerEnd,
        indent = containerPrefix.fenceIndentColumns,
        containerPath = containerPrefix.segments,
    )
}

internal fun parseFenceContainerPrefix(prefix: String): FenceContainerPrefix? {
    val segments = mutableListOf<FenceContainerSegment>()
    var cursor = 0
    while (cursor < prefix.length) {
        val quoteEnd = consumeBlockQuoteMarker(prefix, cursor)
        if (quoteEnd != null) {
            segments.add(FenceContainerSegment(FenceContainerKind.BLOCK_QUOTE))
            cursor = quoteEnd
            continue
        }

        val listItem = listItemRegex.find(prefix.substring(cursor))
        if (listItem != null) {
            segments.add(
                FenceContainerSegment(
                    kind = FenceContainerKind.LIST_ITEM,
                    contentIndentColumns = listContentIndent(listItem),
                )
            )
            cursor += listItem.value.length
            continue
        }
        break
    }

    val indent = prefix.substring(cursor)
    if (indent.any { it != ' ' && it != '\t' }) return null
    return FenceContainerPrefix(
        segments = segments,
        fenceIndentColumns = countLeadingIndentColumns(indent),
    )
}

internal fun findFenceEnd(content: String, fence: FenceStart): Int {
    var searchIndex = fence.headerEnd
    while (searchIndex < content.length) {
        val lineStart = if (searchIndex == fence.headerEnd) {
            content.indexOf('\n', searchIndex).let { if (it < 0) return content.length else it + 1 }
        } else {
            searchIndex
        }
        if (lineStart >= content.length) return content.length
        val lineEnd = content.indexOf('\n', lineStart).let { if (it < 0) content.length else it }
        val line = content.substring(lineStart, lineEnd)
        val containerContentStart = stripFenceContainerPath(line, fence.containerPath)
        if (containerContentStart == null) {
            if (line.isBlank()) {
                searchIndex = lineEnd + 1
                continue
            }
            return lineStart
        }

        val containerContent = line.substring(containerContentStart)
        val closeOffset = findFenceCloseOffsetInLine(containerContent, fence)
        if (closeOffset >= 0) {
            val absoluteCloseOffset = containerContentStart + closeOffset
            val markerLength = countFenceMarkerLength(
                line.substring(absoluteCloseOffset),
                fence.marker.first(),
            )
            return lineStart + absoluteCloseOffset + markerLength
        }
        if (
            containerContent.isNotBlank() &&
            fence.indent > 3 &&
            countLeadingIndentColumns(containerContent) < fence.indent
        ) {
            return lineStart
        }
        searchIndex = lineEnd + 1
    }
    return content.length
}

internal fun stripFenceContainerPath(
    line: String,
    containerPath: List<FenceContainerSegment>,
): Int? {
    var cursor = 0
    for (segment in containerPath) {
        cursor = when (segment.kind) {
            FenceContainerKind.BLOCK_QUOTE -> consumeBlockQuoteMarker(line, cursor) ?: return null
            FenceContainerKind.LIST_ITEM -> consumeIndentColumns(
                text = line,
                start = cursor,
                requiredColumns = segment.contentIndentColumns,
            ) ?: return null
        }
    }
    return cursor
}

internal fun consumeIndentColumns(
    text: String,
    start: Int,
    requiredColumns: Int,
): Int? {
    var cursor = start
    var columns = 0
    while (cursor < text.length && columns < requiredColumns) {
        columns = when (text[cursor]) {
            ' ' -> columns + 1
            '\t' -> columns + 4 - (columns % 4)
            else -> return null
        }
        cursor++
    }
    return cursor.takeIf { columns >= requiredColumns }
}

internal fun findFenceCloseOffsetInLine(line: String, fence: FenceStart): Int {
    val indentLength = line.indexOfFirst { it != ' ' && it != '\t' }
        .let { if (it < 0) line.length else it }
    val indentColumns = countLeadingIndentColumns(line.substring(0, indentLength))
    if (fence.indent <= 3 && indentColumns > 3) return -1
    if (fence.indent > 3 && indentColumns != fence.indent) return -1

    val trimmed = line.trimStart()
    val markerChar = fence.marker.first()
    val markerLength = countFenceMarkerLength(trimmed, markerChar)
    if (markerLength < fence.marker.length) return -1
    if (trimmed.substring(markerLength).isNotBlank()) return -1
    return indentLength
}

internal fun countFenceMarkerLength(text: String, markerChar: Char): Int {
    var markerLength = 0
    while (markerLength < text.length && text[markerLength] == markerChar) {
        markerLength++
    }
    return markerLength
}

internal fun createFormulaId(latex: String, displayMode: FormulaDisplayMode): String {
    val source = "${displayMode.name}\u0000$latex"
    return MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal fun extractFormulaBody(token: String): String {
    val normalized = token.replace("\r\n", "\n").replace('\r', '\n').trim()
    return when {
        normalized.startsWith("$$") && normalized.endsWith("$$") && normalized.length >= 4 ->
            normalized.substring(2, normalized.length - 2)
        normalized.startsWith("\\[") && normalized.endsWith("\\]") && normalized.length >= 4 ->
            normalized.substring(2, normalized.length - 2)
        normalized.startsWith("\\(") && normalized.endsWith("\\)") && normalized.length >= 4 ->
            normalized.substring(2, normalized.length - 2)
        normalized.startsWith('$') && normalized.endsWith('$') && normalized.length >= 2 ->
            normalized.substring(1, normalized.length - 1)
        else -> normalized
    }.trim()
}
