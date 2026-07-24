package com.android.everytalk.ui.components.markdown
import com.android.everytalk.statecontroller.*

import com.android.everytalk.ui.components.streaming.DETAILS_FENCE_LANGUAGE
import com.android.everytalk.ui.components.streaming.DetailsRequest
import java.nio.charset.StandardCharsets
import java.security.MessageDigest


internal fun extractDetailsContent(
    source: String,
    details: HtmlElement,
): DetailsContent? {
    var cursor = details.opening.endExclusive
    while (cursor < details.closing.start && source[cursor].isWhitespace()) cursor++
    val summaryOpening = parseHtmlTagAt(source, cursor) ?: return null
    if (
        summaryOpening.closing ||
        summaryOpening.selfClosing ||
        !summaryOpening.name.equals("summary", ignoreCase = true) ||
        summaryOpening.attributes.isNotBlank()
    ) {
        return null
    }

    val summaryElement = findMatchingHtmlElement(source, summaryOpening, "summary") ?: return null
    if (summaryElement.closing.endExclusive > details.closing.start) return null
    val summary = source.substring(summaryOpening.endExclusive, summaryElement.closing.start).trim()
    if (summary.isEmpty()) return null
    val body = source.substring(summaryElement.closing.endExclusive, details.closing.start)
        .trim('\r', '\n')
    return DetailsContent(summary = summary, markdown = body)
}

/**
 * raw HTML 的 pre/code 内容不再解释 Markdown 语法，只按同名 HTML 标签嵌套配对。
 */
internal fun findRawHtmlElement(
    source: String,
    opening: HtmlTag,
): HtmlElement? {
    var cursor = opening.endExclusive
    var depth = 1
    while (cursor < source.length) {
        val tag = parseHtmlTagAt(source, cursor)
        if (tag == null) {
            cursor++
            continue
        }

        if (tag.name.equals(opening.name, ignoreCase = true)) {
            if (tag.closing) {
                depth--
                if (depth == 0) return HtmlElement(opening, tag)
            } else if (!tag.selfClosing) {
                depth++
            }
        }
        cursor = tag.endExclusive
    }
    return null
}

internal fun findMatchingHtmlElement(
    source: String,
    opening: HtmlTag,
    elementName: String,
): HtmlElement? {
    var cursor = opening.endExclusive
    var depth = 1
    val fences = MarkdownFenceTracker()

    while (cursor < source.length) {
        if (isLineStart(source, cursor)) {
            val lineEnd = lineEndExclusive(source, cursor)
            val line = source.substring(cursor, lineContentEnd(source, cursor, lineEnd))
            if (fences.isFenceLine(line)) {
                cursor = lineEnd
                continue
            }
        }

        if (source[cursor] == '`') {
            val runLength = countRun(source, cursor, '`')
            val close = findInlineCodeClose(source, cursor + runLength, runLength)
            if (close != null) {
                cursor = close
                continue
            }
        }

        findMarkdownLinkDestinationEnd(source, cursor)?.let {
            cursor = it
            continue
        }

        if (source[cursor] == '\\' && cursor + 1 < source.length) {
            cursor += 2
            continue
        }

        val tag = parseHtmlTagAt(source, cursor)
        if (tag != null) {
            if (tag.name.equals(elementName, ignoreCase = true)) {
                if (tag.closing) {
                    depth--
                    if (depth == 0) return HtmlElement(opening, tag)
                } else if (!tag.selfClosing) {
                    depth++
                }
            }
            cursor = tag.endExclusive
            continue
        }
        cursor++
    }
    return null
}

internal fun findFirstClosingTag(
    source: String,
    startIndex: Int,
    name: String,
): HtmlTag? {
    var cursor = source.indexOf("</", startIndex, ignoreCase = true)
    while (cursor >= 0) {
        val tag = parseHtmlTagAt(source, cursor)
        if (tag != null && tag.closing && tag.name.equals(name, ignoreCase = true)) return tag
        cursor = source.indexOf("</", cursor + 2, ignoreCase = true)
    }
    return null
}

internal fun parseHtmlTagAt(source: String, start: Int): HtmlTag? {
    if (start !in source.indices || source[start] != '<') return null
    if (source.startsWith("<!--", start)) {
        val close = source.indexOf("-->", start + 4)
        return HtmlTag(
            start = start,
            endExclusive = if (close >= 0) close + 3 else source.length,
            name = "!--",
            closing = false,
            selfClosing = false,
            attributes = "",
        )
    }

    var cursor = start + 1
    var closing = false
    if (cursor < source.length && source[cursor] == '/') {
        closing = true
        cursor++
    }
    val nameStart = cursor
    while (cursor < source.length && (source[cursor].isLetterOrDigit() || source[cursor] == '-')) cursor++
    if (cursor == nameStart) return null
    val name = source.substring(nameStart, cursor)
    if (
        cursor < source.length &&
        !source[cursor].isWhitespace() &&
        source[cursor] != '/' &&
        source[cursor] != '>'
    ) {
        return null
    }
    val attributesStart = cursor
    var quote: Char? = null
    while (cursor < source.length) {
        val char = source[cursor]
        when {
            quote != null && char == quote -> quote = null
            quote == null && (char == '\'' || char == '"') -> quote = char
            quote == null && char == '>' -> {
                val rawAttributes = source.substring(attributesStart, cursor).trim()
                val selfClosing = rawAttributes.endsWith('/')
                val attributes = if (selfClosing) rawAttributes.dropLast(1).trimEnd() else rawAttributes
                return HtmlTag(
                    start = start,
                    endExclusive = cursor + 1,
                    name = name,
                    closing = closing,
                    selfClosing = selfClosing,
                    attributes = attributes,
                )
            }
        }
        cursor++
    }
    return null
}

internal fun isSafeDetailsOpening(tag: HtmlTag): Boolean {
    return !tag.closing &&
        !tag.selfClosing &&
        tag.name.equals("details", ignoreCase = true) &&
        tag.attributes.isBlank()
}

internal fun isProtectedHtmlCodeOpening(tag: HtmlTag): Boolean =
    !tag.closing &&
        !tag.selfClosing &&
        (tag.name.equals("pre", ignoreCase = true) ||
            tag.name.equals("code", ignoreCase = true))

internal fun isProtectedHtmlRawTextOpening(tag: HtmlTag): Boolean =
    !tag.closing &&
        !tag.selfClosing &&
        (tag.name.equals("style", ignoreCase = true) ||
            tag.name.equals("textarea", ignoreCase = true) ||
            tag.name.equals("title", ignoreCase = true))

internal fun findProtectedRawHtmlEnd(
    source: String,
    opening: HtmlTag,
): Int? = when {
    opening.name == "!--" -> opening.endExclusive
    isProtectedHtmlCodeOpening(opening) ->
        findRawHtmlElement(source, opening)?.closing?.endExclusive ?: source.length
    isProtectedHtmlRawTextOpening(opening) ->
        findFirstClosingTag(source, opening.endExclusive, opening.name)?.endExclusive ?: source.length
    else -> null
}

internal fun appendDetailsFence(
    output: StringBuilder,
    source: String,
    sourceStart: Int,
    sourceEndExclusive: Int,
    id: String,
) {
    if (sourceStart > 0 && source[sourceStart - 1] != '\n' && source[sourceStart - 1] != '\r') {
        output.append("\n\n")
    }
    output.append("```").append(DETAILS_FENCE_LANGUAGE).append('\n')
    output.append(id).append("\n```")
    if (
        sourceEndExclusive < source.length &&
        source[sourceEndExclusive] != '\n' &&
        source[sourceEndExclusive] != '\r'
    ) {
        output.append("\n\n")
    }
}

internal fun createDetailsId(summary: String, markdown: String): String {
    val payload = "details\u0000$summary\u0000$markdown"
    return MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal fun splitSourceLines(source: String): List<SourceLine> {
    if (source.isEmpty()) return emptyList()
    val lines = mutableListOf<SourceLine>()
    var start = 0
    while (start < source.length) {
        var cursor = start
        while (cursor < source.length && source[cursor] != '\r' && source[cursor] != '\n') cursor++
        val ending = when {
            cursor >= source.length -> ""
            source[cursor] == '\r' && cursor + 1 < source.length && source[cursor + 1] == '\n' -> "\r\n"
            else -> source[cursor].toString()
        }
        lines.add(
            SourceLine(
                startOffset = start,
                text = source.substring(start, cursor),
                ending = ending,
            )
        )
        start = cursor + ending.length
    }
    return lines
}

internal fun stripFootnoteContinuationIndent(line: String): String? {
    if (line.startsWith('\t')) return line.substring(1)
    var spaces = 0
    while (spaces < line.length && spaces < 4 && line[spaces] == ' ') spaces++
    return if (spaces == 4) line.substring(4) else null
}

internal fun parseFenceOpeningLine(
    line: String,
    activeListPaths: List<List<MarkdownContainerToken>>,
): FenceOpeningLine {
    val content = line.trimEnd('\r')
    if (content.isBlank()) {
        return FenceOpeningLine(marker = null, listPaths = null, indentedCode = false)
    }

    val containerLine = activeListPaths
        .asSequence()
        .sortedByDescending { it.size }
        .mapNotNull { path -> parseMarkdownContainerLine(content, path) }
        .firstOrNull()
        ?: parseMarkdownContainerLine(content, emptyList())
        ?: return FenceOpeningLine(marker = null, listPaths = emptyList(), indentedCode = false)

    val listPaths = markdownListContainerPaths(containerLine.containers)
    val nextListPaths = if (
        containerLine.contentStart == content.length &&
        listPaths.isEmpty() &&
        activeListPaths.isNotEmpty()
    ) {
        null
    } else {
        listPaths
    }
    val contentStart = containerLine.contentStart
    if (contentStart == null) {
        return FenceOpeningLine(marker = null, listPaths = nextListPaths, indentedCode = true)
    }

    return FenceOpeningLine(
        marker = parseFenceMarkerAt(content, contentStart, containerLine.containers),
        listPaths = nextListPaths,
        indentedCode = false,
    )
}

internal fun parseMarkdownContainerLine(
    line: String,
    requiredContainers: List<MarkdownContainerToken>,
): MarkdownContainerLine? {
    var cursor = 0
    requiredContainers.forEach { container ->
        cursor = consumeMarkdownContainer(line, cursor, container) ?: return null
    }

    val containers = requiredContainers.toMutableList()
    while (true) {
        val containerStart = cursor
        val indent = markdownIndent(line, cursor)
        if (indent.columns > 3) {
            return MarkdownContainerLine(contentStart = null, containers = containers)
        }
        cursor = indent.end
        if (cursor >= line.length) {
            return MarkdownContainerLine(contentStart = cursor, containers = containers)
        }

        if (line[cursor] == '>') {
            containers.add(MarkdownContainerToken(MarkdownContainerType.BlockQuote))
            cursor++
            if (cursor < line.length && (line[cursor] == ' ' || line[cursor] == '\t')) cursor++
            continue
        }

        val listMarkerEnd = markdownListMarkerEnd(line, cursor)
        if (listMarkerEnd != null) {
            containers.add(
                MarkdownContainerToken(
                    type = MarkdownContainerType.List,
                    contentIndent = markdownColumns(line, containerStart, listMarkerEnd),
                )
            )
            cursor = listMarkerEnd
            continue
        }

        return MarkdownContainerLine(contentStart = cursor, containers = containers)
    }
}

internal fun consumeMarkdownContainer(
    line: String,
    start: Int,
    container: MarkdownContainerToken,
): Int? {
    return when (container.type) {
        MarkdownContainerType.BlockQuote -> {
            val indent = markdownIndent(line, start)
            if (indent.columns > 3 || indent.end >= line.length || line[indent.end] != '>') {
                null
            } else {
                var cursor = indent.end + 1
                if (cursor < line.length && (line[cursor] == ' ' || line[cursor] == '\t')) cursor++
                cursor
            }
        }

        MarkdownContainerType.List -> consumeMarkdownIndentExactly(
            line = line,
            start = start,
            columns = container.contentIndent,
        )
    }
}

internal fun parseFenceMarkerAt(
    line: String,
    cursor: Int,
    containers: List<MarkdownContainerToken>,
): FenceMarker? {
    if (cursor >= line.length || (line[cursor] != '`' && line[cursor] != '~')) return null
    val marker = line[cursor]
    val length = countRun(line, cursor, marker)
    if (length < 3) return null
    if (marker == '`' && line.substring(cursor + length).contains('`')) return null
    return FenceMarker(marker = marker, length = length, containers = containers.toList())
}

internal fun isFenceClosingLine(line: String, fence: FenceMarker): Boolean {
    val content = line.trimEnd('\r')
    val cursor = markdownContainerContentStart(content, fence.containers) ?: return false
    if (cursor >= content.length || content[cursor] != fence.marker) return false
    val length = countRun(content, cursor, fence.marker)
    return length >= fence.length && content.substring(cursor + length).isBlank()
}

internal fun matchesFenceContainerPath(
    line: String,
    containers: List<MarkdownContainerToken>,
): Boolean {
    val content = line.trimEnd('\r')
    var cursor = 0
    containers.forEach { container ->
        cursor = consumeMarkdownContainer(content, cursor, container) ?: return false
    }
    return true
}

/**
 * 根据围栏开启时记录的容器路径，返回关闭围栏允许出现的正文起点。
 */
internal fun markdownContainerContentStart(
    line: String,
    containers: List<MarkdownContainerToken>,
): Int? {
    var cursor = 0
    containers.forEach { container ->
        cursor = consumeMarkdownContainer(line, cursor, container) ?: return null
    }
    val indent = markdownIndent(line, cursor)
    return indent.end.takeIf { indent.columns <= 3 }
}

internal fun markdownListContainerPaths(
    containers: List<MarkdownContainerToken>,
): List<List<MarkdownContainerToken>> = buildList {
    containers.forEachIndexed { index, container ->
        if (container.type == MarkdownContainerType.List) {
            add(containers.subList(0, index + 1).toList())
        }
    }
}

internal fun markdownIndent(line: String, start: Int): MarkdownIndent {
    var cursor = start
    var columns = 0
    while (cursor < line.length && (line[cursor] == ' ' || line[cursor] == '\t')) {
        columns += if (line[cursor] == '\t') 4 - (columns % 4) else 1
        cursor++
    }
    return MarkdownIndent(end = cursor, columns = columns)
}

internal fun consumeMarkdownIndentExactly(
    line: String,
    start: Int,
    columns: Int,
): Int? {
    var cursor = start
    var consumed = 0
    while (consumed < columns && cursor < line.length && (line[cursor] == ' ' || line[cursor] == '\t')) {
        consumed += if (line[cursor] == '\t') 4 - (consumed % 4) else 1
        if (consumed > columns) return null
        cursor++
    }
    return cursor.takeIf { consumed == columns }
}

internal fun markdownColumns(line: String, start: Int, endExclusive: Int): Int {
    var cursor = start
    var columns = 0
    while (cursor < endExclusive) {
        columns += if (line[cursor] == '\t') 4 - (columns % 4) else 1
        cursor++
    }
    return columns
}

internal fun markdownListMarkerEnd(line: String, start: Int): Int? {
    if (start >= line.length) return null
    if (line[start] in "-*+") {
        val whitespace = start + 1
        return if (whitespace < line.length && (line[whitespace] == ' ' || line[whitespace] == '\t')) {
            whitespace + 1
        } else {
            null
        }
    }

    var cursor = start
    var digits = 0
    while (cursor < line.length && line[cursor].isDigit() && digits < 9) {
        cursor++
        digits++
    }
    if (digits == 0 || cursor >= line.length || line[cursor] !in ".)") return null
    val whitespace = cursor + 1
    return if (whitespace < line.length && (line[whitespace] == ' ' || line[whitespace] == '\t')) {
        whitespace + 1
    } else {
        null
    }
}

internal fun findInlineCodeClose(source: String, startIndex: Int, runLength: Int): Int? {
    var cursor = source.indexOf('`', startIndex)
    while (cursor >= 0) {
        val length = countRun(source, cursor, '`')
        if (length == runLength) return cursor + runLength
        cursor = source.indexOf('`', cursor + length)
    }
    return null
}

internal fun findMarkdownBracketClose(source: String, start: Int): Int? {
    if (start !in source.indices || source[start] != '[') return null
    var cursor = start + 1
    var depth = 0
    while (cursor < source.length) {
        when (source[cursor]) {
            '\\' -> cursor += 2
            '`' -> {
                val runLength = countRun(source, cursor, '`')
                cursor = findInlineCodeClose(source, cursor + runLength, runLength) ?: return null
            }

            '[' -> {
                depth++
                cursor++
            }

            ']' -> {
                if (depth == 0) return cursor
                depth--
                cursor++
            }

            else -> cursor++
        }
    }
    return null
}

internal fun findMarkdownLinkDestinationEnd(source: String, start: Int): Int? {
    if (start + 1 >= source.length || source[start] != ']' || source[start + 1] != '(') return null
    var cursor = start + 2
    var depth = 1
    while (cursor < source.length) {
        when (source[cursor]) {
            '\\' -> cursor += 2
            '(' -> {
                depth++
                cursor++
            }
            ')' -> {
                depth--
                cursor++
                if (depth == 0) return cursor
            }
            else -> cursor++
        }
    }
    return null
}

internal fun findPlainUrlEnd(source: String, start: Int): Int? {
    val prefixLength = when {
        source.startsWith("https://", start, ignoreCase = true) -> 8
        source.startsWith("http://", start, ignoreCase = true) -> 7
        else -> return null
    }
    var cursor = start + prefixLength
    while (cursor < source.length && !source[cursor].isWhitespace() && source[cursor] !in listOf('<', '>', '"', '\'')) {
        cursor++
    }
    return cursor
}

internal fun findHtmlLikeEnd(source: String, start: Int): Int? {
    if (source.startsWith("<!--", start)) {
        val close = source.indexOf("-->", start + 4)
        return if (close >= 0) close + 3 else null
    }
    var cursor = start + 1
    var quote: Char? = null
    while (cursor < source.length) {
        val char = source[cursor]
        when {
            quote != null && char == quote -> quote = null
            quote == null && (char == '\'' || char == '"') -> quote = char
            quote == null && char == '>' -> return cursor + 1
        }
        cursor++
    }
    return null
}

internal fun countRun(source: String, start: Int, marker: Char): Int {
    var cursor = start
    while (cursor < source.length && source[cursor] == marker) cursor++
    return cursor - start
}

internal fun isLineStart(source: String, index: Int): Boolean {
    return index == 0 || source[index - 1] == '\n' || source[index - 1] == '\r'
}

internal fun lineEndExclusive(source: String, start: Int): Int {
    var cursor = start
    while (cursor < source.length && source[cursor] != '\r' && source[cursor] != '\n') cursor++
    if (cursor < source.length && source[cursor] == '\r') cursor++
    if (cursor < source.length && source[cursor] == '\n') cursor++
    return cursor
}

internal fun lineContentEnd(source: String, start: Int, endExclusive: Int): Int {
    var cursor = endExclusive
    while (cursor > start && (source[cursor - 1] == '\r' || source[cursor - 1] == '\n')) cursor--
    return cursor
}
