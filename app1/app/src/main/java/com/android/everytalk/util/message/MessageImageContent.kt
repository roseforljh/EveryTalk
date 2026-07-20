package com.android.everytalk.util.message

/**
 * 生成供剪贴板、文本分享和文本导出使用的安全文本。
 * 图片节点只属于界面内容，不进入系统文本通道。
 */
fun prepareTextForExternalTransfer(text: String): String {
    if (text.isEmpty()) return text
    val bounded = buildBoundedExternalText(text, MAX_EXTERNAL_TRANSFER_BYTES)
    val safeText = bounded.text.trim().ifEmpty { IMAGE_CONTENT_OMITTED }
    if (!bounded.truncated) return safeText

    val contentLimit = MAX_EXTERNAL_TRANSFER_BYTES - utf8Length(EXTERNAL_TEXT_TRUNCATED)
    return truncateUtf8ToBytes(safeText, contentLimit) + EXTERNAL_TEXT_TRUNCATED
}

fun prepareTextForExport(text: String): String {
    if (text.isEmpty()) return text
    return collapseBlankLines(removeUnsafeDataImages(removeMarkdownImages(text)))
        .trim()
        .ifEmpty { IMAGE_CONTENT_OMITTED }
}

private fun removeMarkdownImages(text: String): String = buildString(minOf(text.length, MAX_EXTERNAL_TRANSFER_BYTES)) {
    var index = 0
    while (index < text.length) {
        val imageMatch = markdownImageMatch(text, index)
        if (imageMatch != null) {
            index = imageMatch.nodeEnd
        } else {
            append(text[index])
            index++
        }
    }
}

internal data class MarkdownImageReference(
    val source: String,
    val sourceStart: Int,
    val sourceEnd: Int,
)

internal fun findMarkdownImageReferences(text: String): List<MarkdownImageReference> {
    val references = mutableListOf<MarkdownImageReference>()
    var index = 0
    while (index < text.length) {
        val match = markdownImageMatch(text, index)
        if (match == null) {
            index++
        } else {
            references += MarkdownImageReference(
                source = text.substring(match.sourceStart, match.sourceEnd),
                sourceStart = match.sourceStart,
                sourceEnd = match.sourceEnd,
            )
            index = match.nodeEnd
        }
    }
    return references
}

internal fun replaceMarkdownImageSources(
    text: String,
    replacements: Map<String, String>,
): String {
    if (replacements.isEmpty()) return text
    val references = findMarkdownImageReferences(text)
        .filter { it.source in replacements }
    if (references.isEmpty()) return text

    return buildString(text.length) {
        var index = 0
        references.forEach { reference ->
            append(text, index, reference.sourceStart)
            append(replacements.getValue(reference.source))
            index = reference.sourceEnd
        }
        append(text, index, text.length)
    }
}

private data class MarkdownImageMatch(
    val nodeEnd: Int,
    val sourceStart: Int,
    val sourceEnd: Int,
)

private fun markdownImageMatch(text: String, start: Int): MarkdownImageMatch? {
    if (start + 1 >= text.length || text[start] != '!' || text[start + 1] != '[') return null

    var index = start + 2
    var escaped = false
    while (index < text.length) {
        val character = text[index]
        if (escaped) {
            escaped = false
        } else if (character == '\\') {
            escaped = true
        } else if (character == ']' && index + 1 < text.length && text[index + 1] == '(') {
            index += 2
            val sourceStart = index
            var depth = 1
            escaped = false
            while (index < text.length) {
                val destinationCharacter = text[index]
                when {
                    escaped -> escaped = false
                    destinationCharacter == '\\' -> escaped = true
                    destinationCharacter == '(' -> depth++
                    destinationCharacter == ')' -> {
                        depth--
                        if (depth == 0) {
                            return MarkdownImageMatch(
                                nodeEnd = index + 1,
                                sourceStart = sourceStart,
                                sourceEnd = index,
                            )
                        }
                    }
                }
                index++
            }
            return null
        }
        index++
    }
    return null
}

private fun collapseBlankLines(text: String): String = buildString(text.length) {
    var consecutiveNewlines = 0
    text.forEach { character ->
        if (character == '\n') {
            consecutiveNewlines++
            if (consecutiveNewlines <= 2) append(character)
        } else {
            consecutiveNewlines = 0
            append(character)
        }
    }
}

private data class BoundedExternalText(
    val text: String,
    val truncated: Boolean,
)

private fun buildBoundedExternalText(text: String, maxBytes: Int): BoundedExternalText {
    val output = StringBuilder(minOf(text.length, maxBytes.coerceAtLeast(16)))
    var outputBytes = 0
    var index = 0
    var consecutiveNewlines = 0
    while (index < text.length) {
        val imageMatch = markdownImageMatch(text, index)
        if (imageMatch != null) {
            index = imageMatch.nodeEnd
            continue
        }

        val dataImageEnd = unsafeDataImageEnd(text, index)
        if (dataImageEnd != null) {
            index = dataImageEnd
            continue
        }

        val codePoint = Character.codePointAt(text, index)
        val characterCount = Character.charCount(codePoint)
        if (codePoint == '\n'.code) {
            consecutiveNewlines++
            if (consecutiveNewlines > 2) {
                index += characterCount
                continue
            }
        } else {
            consecutiveNewlines = 0
        }

        val codePointBytes = utf8Length(codePoint)
        if (outputBytes + codePointBytes > maxBytes) {
            return BoundedExternalText(output.toString(), truncated = true)
        }
        output.appendCodePoint(codePoint)
        outputBytes += codePointBytes
        index += characterCount
    }
    return BoundedExternalText(output.toString(), truncated = false)
}

private fun removeUnsafeDataImages(text: String): String = buildString(minOf(text.length, MAX_EXTERNAL_TRANSFER_BYTES)) {
    var index = 0
    while (index < text.length) {
        val dataImageEnd = unsafeDataImageEnd(text, index)
        if (dataImageEnd == null) {
            append(text[index])
            index++
        } else {
            index = dataImageEnd
        }
    }
}

private fun unsafeDataImageEnd(text: String, start: Int): Int? {
    if (!text.regionMatches(start, DATA_IMAGE_PREFIX, 0, DATA_IMAGE_PREFIX.length, ignoreCase = true)) {
        return null
    }

    val payloadStart = text.indexOf(',', startIndex = start + DATA_IMAGE_PREFIX.length)
    if (payloadStart == -1) return text.length

    var index = payloadStart + 1
    var sawPadding = false
    while (index < text.length) {
        val character = text[index]
        when {
            isBase64AlphabetCharacter(character) -> {
                if (sawPadding) break
                index++
            }
            character == '=' -> {
                sawPadding = true
                index++
            }
            character == '-' || character == '_' || character == '+' || character == '/' -> {
                if (sawPadding) break
                index++
            }
            character == ' ' || character == '\t' || character == '\r' || character == '\n' -> {
                if (sawPadding) {
                    while (index < text.length && text[index].isWhitespace()) index++
                    break
                }
                index++
            }
            else -> break
        }
    }
    return index
}

private fun isBase64AlphabetCharacter(character: Char): Boolean =
    character in 'A'..'Z' ||
        character in 'a'..'z' ||
        character in '0'..'9'

private const val DATA_IMAGE_PREFIX = "data:image/"

internal const val MAX_EXTERNAL_TRANSFER_BYTES = 256 * 1024
private const val IMAGE_CONTENT_OMITTED = "[图片内容已省略]"
private const val EXTERNAL_TEXT_TRUNCATED = "\n\n[内容过长，已截断]"

private fun truncateUtf8ToBytes(text: String, maxBytes: Int): String {
    var index = 0
    var bytes = 0
    while (index < text.length) {
        val codePoint = Character.codePointAt(text, index)
        val codePointBytes = utf8Length(codePoint)
        if (bytes + codePointBytes > maxBytes) break
        bytes += codePointBytes
        index += Character.charCount(codePoint)
    }
    return text.substring(0, index)
}

private fun utf8Length(text: String): Int {
    var index = 0
    var bytes = 0
    while (index < text.length) {
        val codePoint = Character.codePointAt(text, index)
        bytes += utf8Length(codePoint)
        index += Character.charCount(codePoint)
    }
    return bytes
}

private fun utf8Length(codePoint: Int): Int = when {
    codePoint <= 0x7f -> 1
    codePoint <= 0x7ff -> 2
    codePoint <= 0xffff -> 3
    else -> 4
}
