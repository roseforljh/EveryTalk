package com.android.everytalk.ui.components.markdown

import com.android.everytalk.ui.components.streaming.DETAILS_FENCE_LANGUAGE
import com.android.everytalk.ui.components.streaming.DetailsRequest
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal data class MarkdownExtensionPreprocessResult(
    val markdown: String,
    val details: Map<String, DetailsRequest>,
)

private data class HtmlExtractionResult(
    val markdown: String,
    val details: Map<String, DetailsRequest>,
)

private data class FootnoteDefinition(
    val markdown: String,
)

private data class FootnoteExtractionResult(
    val markdown: String,
    val definitions: Map<String, FootnoteDefinition>,
)

private data class SourceLine(
    val startOffset: Int,
    val text: String,
    val ending: String,
)

private data class SourceRange(
    val start: Int,
    val endExclusive: Int,
)

private data class FenceMarker(
    val marker: Char,
    val length: Int,
    val containers: List<MarkdownContainerToken>,
)

private enum class MarkdownContainerType {
    BlockQuote,
    List,
}

private data class MarkdownContainerToken(
    val type: MarkdownContainerType,
    val contentIndent: Int = 0,
)

private data class MarkdownContainerLine(
    val contentStart: Int?,
    val containers: List<MarkdownContainerToken>,
)

private data class MarkdownIndent(
    val end: Int,
    val columns: Int,
)

private data class FenceOpeningLine(
    val marker: FenceMarker?,
    val listPaths: List<List<MarkdownContainerToken>>?,
    val indentedCode: Boolean,
)

private class MarkdownFenceTracker {
    private var activeFence: FenceMarker? = null
    private var activeListPaths = emptyList<List<MarkdownContainerToken>>()

    fun isFenceLine(line: String): Boolean {
        val fence = activeFence
        if (fence != null) {
            if (isFenceClosingLine(line, fence)) {
                activeFence = null
                return true
            }
            if (line.isBlank() || matchesFenceContainerPath(line, fence.containers)) return true
            activeFence = null
        }

        val opening = parseFenceOpeningLine(line, activeListPaths)
        opening.listPaths?.let { activeListPaths = it }
        if (opening.indentedCode) return true
        val marker = opening.marker ?: return false
        activeFence = marker
        return true
    }
}

private data class HtmlTag(
    val start: Int,
    val endExclusive: Int,
    val name: String,
    val closing: Boolean,
    val selfClosing: Boolean,
    val attributes: String,
)

private data class HtmlElement(
    val opening: HtmlTag,
    val closing: HtmlTag,
)

private data class DetailsContent(
    val summary: String,
    val markdown: String,
)

private data class FootnoteReference(
    val number: Int,
    val occurrence: Int,
)

private class FootnoteContext(
    val definitions: Map<String, FootnoteDefinition>,
) {
    val orderedIds = mutableListOf<String>()
    private val numberById = linkedMapOf<String, Int>()
    private val occurrencesById = mutableMapOf<String, Int>()

    fun referenceFor(id: String): FootnoteReference? {
        if (id !in definitions) return null
        val number = numberById.getOrPut(id) {
            orderedIds.add(id)
            orderedIds.size
        }
        val occurrence = occurrencesById.getOrDefault(id, 0) + 1
        occurrencesById[id] = occurrence
        return FootnoteReference(number = number, occurrence = occurrence)
    }
}

private val emojiShortcodes = mapOf(
    "smile" to "😄",
    "+1" to "👍",
    "warning" to "⚠️",
    "rocket" to "🚀",
    "white_check_mark" to "✅",
)

private val emailAutolinkRegex = Regex(
    """^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$"""
)
private val footnoteDefinitionRegex = Regex("""^\s{0,3}\[\^([^\]\s]+)]:[ \t]*(.*)$""")
private val referenceDefinitionRegex = Regex("""^\s{0,3}\[[^]\r\n]+]:[ \t]*\S.*$""")
internal const val FOOTNOTE_DEFINITION_SCHEME = "everytalk-footnote-definition:"
internal const val FOOTNOTE_REFERENCE_SCHEME = "everytalk-footnote-reference:"
private const val MAX_DETAILS_NESTING = 16

/**
 * 只补齐 MikePenz 当前缺失的扩展语法，标准 Markdown 原文保持不变。
 */
internal fun preprocessMarkdownExtensions(
    markdown: String,
    contentVersion: Long,
): MarkdownExtensionPreprocessResult {
    if (markdown.isEmpty()) {
        return MarkdownExtensionPreprocessResult(markdown = markdown, details = emptyMap())
    }

    val sanitized = extractSafeHtmlBlocks(
        markdown = markdown,
        contentVersion = contentVersion,
        detailsNesting = 0,
        extractDetails = false,
    ).markdown
    val footnotes = extractFootnoteDefinitions(sanitized)
    val context = FootnoteContext(footnotes.definitions)
    val body = transformInlineExtensions(footnotes.markdown, context)
    val normalized = appendFootnoteDefinitions(body, context)
    val html = extractSafeHtmlBlocks(
        markdown = normalized,
        contentVersion = contentVersion,
        detailsNesting = 0,
        extractDetails = true,
    )
    return MarkdownExtensionPreprocessResult(
        markdown = html.markdown,
        details = html.details,
    )
}

private fun extractSafeHtmlBlocks(
    markdown: String,
    contentVersion: Long,
    detailsNesting: Int,
    extractDetails: Boolean,
): HtmlExtractionResult {
    val output = StringBuilder(markdown.length)
    val details = linkedMapOf<String, DetailsRequest>()
    var cursor = 0
    val fences = MarkdownFenceTracker()

    while (cursor < markdown.length) {
        if (isLineStart(markdown, cursor)) {
            val lineEnd = lineEndExclusive(markdown, cursor)
            val line = markdown.substring(cursor, lineContentEnd(markdown, cursor, lineEnd))
            if (fences.isFenceLine(line)) {
                output.append(markdown, cursor, lineEnd)
                cursor = lineEnd
                continue
            }
        }

        if (markdown[cursor] == '`') {
            val runLength = countRun(markdown, cursor, '`')
            val close = findInlineCodeClose(markdown, cursor + runLength, runLength)
            if (close != null) {
                output.append(markdown, cursor, close)
                cursor = close
                continue
            }
        }

        findMarkdownLinkDestinationEnd(markdown, cursor)?.let { endExclusive ->
            output.append(markdown, cursor, endExclusive)
            cursor = endExclusive
            continue
        }

        if (markdown[cursor] == '\\' && cursor + 1 < markdown.length) {
            output.append(markdown, cursor, cursor + 2)
            cursor += 2
            continue
        }

        val tag = parseHtmlTagAt(markdown, cursor)
        if (tag != null) {
            val protectedEndExclusive = findProtectedRawHtmlEnd(markdown, tag)
            when {
                protectedEndExclusive != null -> {
                    output.append(markdown, tag.start, protectedEndExclusive)
                    cursor = protectedEndExclusive
                    continue
                }

                tag.name.equals("script", ignoreCase = true) && !tag.closing -> {
                    val closing = if (tag.selfClosing) null else findFirstClosingTag(markdown, tag.endExclusive, "script")
                    cursor = closing?.endExclusive ?: tag.endExclusive
                    if (closing == null && !tag.selfClosing) cursor = markdown.length
                    continue
                }

                extractDetails &&
                    detailsNesting < MAX_DETAILS_NESTING &&
                    isSafeDetailsOpening(tag) -> {
                    val element = findMatchingHtmlElement(markdown, tag, "details")
                    val content = element?.let { extractDetailsContent(markdown, it) }
                    if (element != null && content != null) {
                        val summary = extractSafeHtmlBlocks(
                            markdown = content.summary,
                            contentVersion = contentVersion,
                            detailsNesting = detailsNesting + 1,
                            extractDetails = true,
                        )
                        val body = extractSafeHtmlBlocks(
                            markdown = content.markdown,
                            contentVersion = contentVersion,
                            detailsNesting = detailsNesting + 1,
                            extractDetails = true,
                        )
                        details.putAll(summary.details)
                        details.putAll(body.details)
                        val id = createDetailsId(summary.markdown, body.markdown)
                        details[id] = DetailsRequest(
                            id = id,
                            summary = summary.markdown,
                            markdown = body.markdown,
                            contentVersion = contentVersion,
                        )
                        appendDetailsFence(
                            output = output,
                            source = markdown,
                            sourceStart = tag.start,
                            sourceEndExclusive = element.closing.endExclusive,
                            id = id,
                        )
                        cursor = element.closing.endExclusive
                        continue
                    }
                }
            }

            output.append(markdown, tag.start, tag.endExclusive)
            cursor = tag.endExclusive
            continue
        }

        output.append(markdown[cursor])
        cursor++
    }

    return HtmlExtractionResult(output.toString(), details)
}

private fun extractFootnoteDefinitions(markdown: String): FootnoteExtractionResult {
    val lines = splitSourceLines(markdown)
    if (lines.isEmpty()) return FootnoteExtractionResult(markdown, emptyMap())

    val removed = BooleanArray(lines.size)
    val definitions = linkedMapOf<String, FootnoteDefinition>()
    val protectedRawHtmlRanges = findProtectedRawHtmlRanges(markdown)
    val fences = MarkdownFenceTracker()
    var index = 0

    while (index < lines.size) {
        if (protectedRawHtmlRanges.containsOffset(lines[index].startOffset)) {
            index++
            continue
        }
        val line = lines[index].text
        if (fences.isFenceLine(line)) {
            index++
            continue
        }

        val definition = footnoteDefinitionRegex.matchEntire(line)
        if (definition == null) {
            index++
            continue
        }

        val id = definition.groupValues[1]
        val bodyLines = mutableListOf(definition.groupValues[2])
        removed[index] = true
        var continuation = index + 1
        val pendingBlankLines = mutableListOf<Int>()
        while (continuation < lines.size) {
            if (protectedRawHtmlRanges.containsOffset(lines[continuation].startOffset)) break
            val continuationText = lines[continuation].text
            if (continuationText.isBlank()) {
                pendingBlankLines.add(continuation)
                continuation++
                continue
            }

            val stripped = stripFootnoteContinuationIndent(continuationText) ?: break
            pendingBlankLines.forEach { blankIndex ->
                removed[blankIndex] = true
                bodyLines.add("")
            }
            pendingBlankLines.clear()
            removed[continuation] = true
            bodyLines.add(stripped)
            continuation++
        }

        definitions.putIfAbsent(
            id,
            FootnoteDefinition(markdown = bodyLines.joinToString("\n").trimEnd()),
        )
        index = continuation - pendingBlankLines.size
    }

    val output = buildString(markdown.length) {
        lines.forEachIndexed { lineIndex, line ->
            if (!removed[lineIndex]) {
                append(line.text)
                append(line.ending)
            }
        }
    }
    return FootnoteExtractionResult(output, definitions)
}

private fun findProtectedRawHtmlRanges(markdown: String): List<SourceRange> {
    val ranges = mutableListOf<SourceRange>()
    val fences = MarkdownFenceTracker()
    var cursor = 0

    while (cursor < markdown.length) {
        if (isLineStart(markdown, cursor)) {
            val lineEnd = lineEndExclusive(markdown, cursor)
            val line = markdown.substring(cursor, lineContentEnd(markdown, cursor, lineEnd))
            if (fences.isFenceLine(line)) {
                cursor = lineEnd
                continue
            }
        }

        if (markdown[cursor] == '`') {
            val runLength = countRun(markdown, cursor, '`')
            val close = findInlineCodeClose(markdown, cursor + runLength, runLength)
            if (close != null) {
                cursor = close
                continue
            }
        }

        findMarkdownLinkDestinationEnd(markdown, cursor)?.let { endExclusive ->
            cursor = endExclusive
            continue
        }

        if (markdown[cursor] == '\\' && cursor + 1 < markdown.length) {
            cursor += 2
            continue
        }

        val tag = parseHtmlTagAt(markdown, cursor)
        if (tag != null) {
            val endExclusive = findProtectedRawHtmlEnd(markdown, tag)
            if (endExclusive != null) {
                ranges.add(SourceRange(start = tag.start, endExclusive = endExclusive))
                cursor = endExclusive
            } else {
                cursor = tag.endExclusive
            }
            continue
        }

        cursor++
    }
    return ranges
}

private fun List<SourceRange>.containsOffset(offset: Int): Boolean =
    any { range -> offset >= range.start && offset < range.endExclusive }

private fun transformInlineExtensions(
    markdown: String,
    footnotes: FootnoteContext,
    allowFootnoteReferences: Boolean = true,
): String {
    val output = StringBuilder(markdown.length)
    var cursor = 0
    val fences = MarkdownFenceTracker()

    while (cursor < markdown.length) {
        if (isLineStart(markdown, cursor)) {
            val lineEnd = lineEndExclusive(markdown, cursor)
            val lineContentEnd = lineContentEnd(markdown, cursor, lineEnd)
            val line = markdown.substring(cursor, lineContentEnd)
            if (fences.isFenceLine(line)) {
                output.append(markdown, cursor, lineEnd)
                cursor = lineEnd
                continue
            }
            if (referenceDefinitionRegex.matches(line)) {
                output.append(markdown, cursor, lineEnd)
                cursor = lineEnd
                continue
            }
        }

        if (markdown[cursor] == '`') {
            val runLength = countRun(markdown, cursor, '`')
            val close = findInlineCodeClose(markdown, cursor + runLength, runLength)
            if (close != null) {
                output.append(markdown, cursor, close)
                cursor = close
                continue
            }
        }

        findMarkdownLinkDestinationEnd(markdown, cursor)?.let { endExclusive ->
            output.append(markdown, cursor, endExclusive)
            cursor = endExclusive
            continue
        }

        findPlainUrlEnd(markdown, cursor)?.let { endExclusive ->
            output.append(markdown, cursor, endExclusive)
            cursor = endExclusive
            continue
        }

        if (markdown[cursor] == '\\' && cursor + 1 < markdown.length) {
            output.append(markdown, cursor, cursor + 2)
            cursor += 2
            continue
        }

        if (markdown[cursor] == '<') {
            val opening = parseHtmlTagAt(markdown, cursor)
            val protectedEndExclusive = opening?.let { findProtectedRawHtmlEnd(markdown, it) }
            if (protectedEndExclusive != null) {
                output.append(markdown, cursor, protectedEndExclusive)
                cursor = protectedEndExclusive
                continue
            }

            val tagEnd = findHtmlLikeEnd(markdown, cursor)
            if (tagEnd != null) {
                val inner = markdown.substring(cursor + 1, tagEnd - 1)
                if (emailAutolinkRegex.matches(inner)) {
                    output.append('[').append(inner).append("](mailto:").append(inner).append(')')
                } else {
                    output.append(markdown, cursor, tagEnd)
                }
                cursor = tagEnd
                continue
            }
        }

        if (markdown.startsWith("[^", cursor) && (cursor == 0 || markdown[cursor - 1] != '!')) {
            val close = markdown.indexOf(']', cursor + 2)
            if (close > cursor + 2) {
                val id = markdown.substring(cursor + 2, close)
                val reference = if (allowFootnoteReferences) footnotes.referenceFor(id) else null
                if (reference != null) {
                    output.append('[')
                        .append(footnoteNumberLabel(reference.number))
                        .append("](")
                        .append(
                            footnoteDefinitionUri(
                                number = reference.number,
                                occurrence = reference.occurrence,
                            )
                        )
                        .append(')')
                    cursor = close + 1
                    continue
                }
            }
        }

        if (markdown[cursor] == '[') {
            val close = findMarkdownBracketClose(markdown, cursor)
            if (close != null) {
                output.append('[')
                output.append(
                    transformInlineExtensions(
                        markdown = markdown.substring(cursor + 1, close),
                        footnotes = footnotes,
                        allowFootnoteReferences = false,
                    )
                )
                output.append(']')
                val destinationEnd = findMarkdownLinkDestinationEnd(markdown, close)
                if (destinationEnd != null) {
                    output.append(markdown, close + 1, destinationEnd)
                    cursor = destinationEnd
                } else {
                    cursor = close + 1
                }
                continue
            }
        }

        if (markdown[cursor] == ':') {
            val close = markdown.indexOf(':', cursor + 1)
            if (close in (cursor + 2)..(cursor + 64)) {
                val shortcode = markdown.substring(cursor + 1, close)
                val emoji = emojiShortcodes[shortcode]
                if (emoji != null) {
                    output.append(emoji)
                    cursor = close + 1
                    continue
                }
            }
        }

        output.append(markdown[cursor])
        cursor++
    }

    return output.toString()
}

private fun appendFootnoteDefinitions(
    markdown: String,
    context: FootnoteContext,
): String {
    if (context.orderedIds.isEmpty()) return markdown

    val output = StringBuilder(markdown.length + 128)
    output.append(markdown.trimEnd())
    if (output.isNotEmpty()) output.append("\n\n")

    var index = 0
    while (index < context.orderedIds.size) {
        val id = context.orderedIds[index]
        val definition = context.definitions.getValue(id)
        val body = transformInlineExtensions(definition.markdown, context)
        val bodyLines = body.split('\n')
        val number = index + 1
        output.append('[')
            .append(footnoteNumberLabel(number))
            .append("](")
            .append(footnoteReferenceUri(number))
            .append(')')
        bodyLines.firstOrNull().orEmpty().takeIf { it.isNotEmpty() }?.let { firstLine ->
            output.append(' ').append(firstLine)
        }
        bodyLines.drop(1).forEach { line ->
            output.append('\n').append(line)
        }
        index++
        if (index < context.orderedIds.size) output.append("\n\n")
    }
    return output.toString()
}

internal fun footnoteDefinitionUri(number: Int): String = FOOTNOTE_DEFINITION_SCHEME + number

internal fun footnoteDefinitionUri(number: Int, occurrence: Int): String =
    "$FOOTNOTE_DEFINITION_SCHEME$number:$occurrence"

internal fun footnoteReferenceUri(number: Int): String = FOOTNOTE_REFERENCE_SCHEME + number

internal fun footnoteReferenceUri(number: Int, occurrence: Int): String =
    "$FOOTNOTE_REFERENCE_SCHEME$number:$occurrence"

internal fun footnoteNumberLabel(number: Int): String = buildString {
    number.toString().forEach { digit ->
        append(
            when (digit) {
                '0' -> '⁰'
                '1' -> '¹'
                '2' -> '²'
                '3' -> '³'
                '4' -> '⁴'
                '5' -> '⁵'
                '6' -> '⁶'
                '7' -> '⁷'
                '8' -> '⁸'
                '9' -> '⁹'
                else -> digit
            }
        )
    }
}

private fun extractDetailsContent(
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
private fun findRawHtmlElement(
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

private fun findMatchingHtmlElement(
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

private fun findFirstClosingTag(
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

private fun parseHtmlTagAt(source: String, start: Int): HtmlTag? {
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

private fun isSafeDetailsOpening(tag: HtmlTag): Boolean {
    return !tag.closing &&
        !tag.selfClosing &&
        tag.name.equals("details", ignoreCase = true) &&
        tag.attributes.isBlank()
}

private fun isProtectedHtmlCodeOpening(tag: HtmlTag): Boolean =
    !tag.closing &&
        !tag.selfClosing &&
        (tag.name.equals("pre", ignoreCase = true) ||
            tag.name.equals("code", ignoreCase = true))

private fun isProtectedHtmlRawTextOpening(tag: HtmlTag): Boolean =
    !tag.closing &&
        !tag.selfClosing &&
        (tag.name.equals("style", ignoreCase = true) ||
            tag.name.equals("textarea", ignoreCase = true) ||
            tag.name.equals("title", ignoreCase = true))

private fun findProtectedRawHtmlEnd(
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

private fun appendDetailsFence(
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

private fun createDetailsId(summary: String, markdown: String): String {
    val payload = "details\u0000$summary\u0000$markdown"
    return MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun splitSourceLines(source: String): List<SourceLine> {
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

private fun stripFootnoteContinuationIndent(line: String): String? {
    if (line.startsWith('\t')) return line.substring(1)
    var spaces = 0
    while (spaces < line.length && spaces < 4 && line[spaces] == ' ') spaces++
    return if (spaces == 4) line.substring(4) else null
}

private fun parseFenceOpeningLine(
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

private fun parseMarkdownContainerLine(
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

private fun consumeMarkdownContainer(
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

private fun parseFenceMarkerAt(
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

private fun isFenceClosingLine(line: String, fence: FenceMarker): Boolean {
    val content = line.trimEnd('\r')
    val cursor = markdownContainerContentStart(content, fence.containers) ?: return false
    if (cursor >= content.length || content[cursor] != fence.marker) return false
    val length = countRun(content, cursor, fence.marker)
    return length >= fence.length && content.substring(cursor + length).isBlank()
}

private fun matchesFenceContainerPath(
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
private fun markdownContainerContentStart(
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

private fun markdownListContainerPaths(
    containers: List<MarkdownContainerToken>,
): List<List<MarkdownContainerToken>> = buildList {
    containers.forEachIndexed { index, container ->
        if (container.type == MarkdownContainerType.List) {
            add(containers.subList(0, index + 1).toList())
        }
    }
}

private fun markdownIndent(line: String, start: Int): MarkdownIndent {
    var cursor = start
    var columns = 0
    while (cursor < line.length && (line[cursor] == ' ' || line[cursor] == '\t')) {
        columns += if (line[cursor] == '\t') 4 - (columns % 4) else 1
        cursor++
    }
    return MarkdownIndent(end = cursor, columns = columns)
}

private fun consumeMarkdownIndentExactly(
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

private fun markdownColumns(line: String, start: Int, endExclusive: Int): Int {
    var cursor = start
    var columns = 0
    while (cursor < endExclusive) {
        columns += if (line[cursor] == '\t') 4 - (columns % 4) else 1
        cursor++
    }
    return columns
}

private fun markdownListMarkerEnd(line: String, start: Int): Int? {
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

private fun findInlineCodeClose(source: String, startIndex: Int, runLength: Int): Int? {
    var cursor = source.indexOf('`', startIndex)
    while (cursor >= 0) {
        val length = countRun(source, cursor, '`')
        if (length == runLength) return cursor + runLength
        cursor = source.indexOf('`', cursor + length)
    }
    return null
}

private fun findMarkdownBracketClose(source: String, start: Int): Int? {
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

private fun findMarkdownLinkDestinationEnd(source: String, start: Int): Int? {
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

private fun findPlainUrlEnd(source: String, start: Int): Int? {
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

private fun findHtmlLikeEnd(source: String, start: Int): Int? {
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

private fun countRun(source: String, start: Int, marker: Char): Int {
    var cursor = start
    while (cursor < source.length && source[cursor] == marker) cursor++
    return cursor - start
}

private fun isLineStart(source: String, index: Int): Boolean {
    return index == 0 || source[index - 1] == '\n' || source[index - 1] == '\r'
}

private fun lineEndExclusive(source: String, start: Int): Int {
    var cursor = start
    while (cursor < source.length && source[cursor] != '\r' && source[cursor] != '\n') cursor++
    if (cursor < source.length && source[cursor] == '\r') cursor++
    if (cursor < source.length && source[cursor] == '\n') cursor++
    return cursor
}

private fun lineContentEnd(source: String, start: Int, endExclusive: Int): Int {
    var cursor = endExclusive
    while (cursor > start && (source[cursor - 1] == '\r' || source[cursor - 1] == '\n')) cursor--
    return cursor
}
