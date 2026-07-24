package com.android.everytalk.ui.components.markdown
import com.android.everytalk.statecontroller.*

import com.android.everytalk.ui.components.streaming.DETAILS_FENCE_LANGUAGE
import com.android.everytalk.ui.components.streaming.DetailsRequest
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal data class MarkdownExtensionPreprocessResult(
    val markdown: String,
    val details: Map<String, DetailsRequest>,
)

internal data class HtmlExtractionResult(
    val markdown: String,
    val details: Map<String, DetailsRequest>,
)

internal data class FootnoteDefinition(
    val markdown: String,
)

internal data class FootnoteExtractionResult(
    val markdown: String,
    val definitions: Map<String, FootnoteDefinition>,
)

internal data class SourceLine(
    val startOffset: Int,
    val text: String,
    val ending: String,
)

internal data class SourceRange(
    val start: Int,
    val endExclusive: Int,
)

internal data class FenceMarker(
    val marker: Char,
    val length: Int,
    val containers: List<MarkdownContainerToken>,
)

internal enum class MarkdownContainerType {
    BlockQuote,
    List,
}

internal data class MarkdownContainerToken(
    val type: MarkdownContainerType,
    val contentIndent: Int = 0,
)

internal data class MarkdownContainerLine(
    val contentStart: Int?,
    val containers: List<MarkdownContainerToken>,
)

internal data class MarkdownIndent(
    val end: Int,
    val columns: Int,
)

internal data class FenceOpeningLine(
    val marker: FenceMarker?,
    val listPaths: List<List<MarkdownContainerToken>>?,
    val indentedCode: Boolean,
)

internal class MarkdownFenceTracker {
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

internal data class HtmlTag(
    val start: Int,
    val endExclusive: Int,
    val name: String,
    val closing: Boolean,
    val selfClosing: Boolean,
    val attributes: String,
)

internal data class HtmlElement(
    val opening: HtmlTag,
    val closing: HtmlTag,
)

internal data class DetailsContent(
    val summary: String,
    val markdown: String,
)

internal data class FootnoteReference(
    val number: Int,
    val occurrence: Int,
)

internal class FootnoteContext(
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

internal val emojiShortcodes = mapOf(
    "smile" to "😄",
    "+1" to "👍",
    "warning" to "⚠️",
    "rocket" to "🚀",
    "white_check_mark" to "✅",
)

internal val emailAutolinkRegex = Regex(
    """^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$"""
)
internal val footnoteDefinitionRegex = Regex("""^\s{0,3}\[\^([^\]\s]+)]:[ \t]*(.*)$""")
internal val referenceDefinitionRegex = Regex("""^\s{0,3}\[[^]\r\n]+]:[ \t]*\S.*$""")
internal const val FOOTNOTE_DEFINITION_SCHEME = "everytalk-footnote-definition:"
internal const val FOOTNOTE_REFERENCE_SCHEME = "everytalk-footnote-reference:"
internal const val MAX_DETAILS_NESTING = 16

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

    val contractNormalized = MarkdownContractValidator.normalize(markdown)
    val sanitized = extractSafeHtmlBlocks(
        markdown = contractNormalized,
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

internal fun extractSafeHtmlBlocks(
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

internal fun extractFootnoteDefinitions(markdown: String): FootnoteExtractionResult {
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

internal fun findProtectedRawHtmlRanges(markdown: String): List<SourceRange> {
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

internal fun List<SourceRange>.containsOffset(offset: Int): Boolean =
    any { range -> offset >= range.start && offset < range.endExclusive }

internal fun transformInlineExtensions(
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

internal fun appendFootnoteDefinitions(
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
