package com.android.everytalk.ui.components.streaming

import java.util.Locale

object StreamBlockParser {
    private val fencedBlockLanguageRegex = Regex("^[A-Za-z0-9_+\\-#.]+$")
    private val openingFenceRegex = Regex("^([`~]{3,})([^`~]*)$")

    private data class DelimiterMatch(
        val start: Int,
        val token: String,
        val type: StreamBlockType,
        val stateWhenClosed: MathBlockState,
        val stateWhenPending: MathBlockState,
    )

    private fun shouldPromoteInlineMathToBlock(mathBody: String): Boolean {
        val body = mathBody.trim()
        if (body.isEmpty()) return false

        val hasComplexToken = body.contains("\\frac") ||
            body.contains("\\sum") ||
            body.contains("\\int") ||
            body.contains("\\prod") ||
            body.contains("\\lim") ||
            body.contains("\\begin") ||
            body.contains("\\left") ||
            body.contains("\\right") ||
            body.contains("\\matrix") ||
            body.contains("\\cases")

        return body.length >= 36 || hasComplexToken
    }

    data class ParseResult(
        val blocks: List<StreamBlock>,
        val hasPendingMath: Boolean,
        val blocksHash: String
    )

    fun parse(content: String, messageId: String): ParseResult {
        if (content.isEmpty()) {
            return ParseResult(emptyList(), hasPendingMath = false, blocksHash = "empty")
        }

        val blocks = mutableListOf<StreamBlock>()
        var cursor = 0
        var blockIndex = 0
        var hasPendingMath = false

        fun nextId(type: StreamBlockType): String {
            val id = "$messageId:${type.name.lowercase(Locale.ROOT)}:$blockIndex"
            blockIndex++
            return id
        }

        fun appendPlain(start: Int, endExclusive: Int) {
            if (endExclusive <= start) return
            blocks.add(
                StreamBlock.PlainText(
                    stableId = nextId(StreamBlockType.PLAIN_TEXT),
                    text = content.substring(start, endExclusive),
                    start = start,
                    endExclusive = endExclusive
                )
            )
        }

        while (cursor < content.length) {
            val codeStart = findNextFenceStart(content, cursor)
            val mathBlockStart = content.indexOf("$$", cursor)
            val inlineStart = content.indexOf('$', cursor)
            val escapedInlineStart = content.indexOf("\\(", cursor)
            val escapedBlockStart = content.indexOf("\\[", cursor)

            val candidates = mutableListOf<Int>()
            if (codeStart >= 0) candidates.add(codeStart)
            if (mathBlockStart >= 0) candidates.add(mathBlockStart)
            if (inlineStart >= 0) candidates.add(inlineStart)
            if (escapedInlineStart >= 0) candidates.add(escapedInlineStart)
            if (escapedBlockStart >= 0) candidates.add(escapedBlockStart)
            val nextSpecial = candidates.minOrNull()

            if (nextSpecial == null) {
                appendPlain(cursor, content.length)
                break
            }

            if (nextSpecial > cursor) {
                appendPlain(cursor, nextSpecial)
                cursor = nextSpecial
            }

            val fence = parseFenceStart(content, cursor)
            if (fence != null) {
                val fenceHeader = fence.language
                val close = findFenceClose(content, fence)
                if (close != null) {
                    val end = close.endExclusive
                    val fencedText = content.substring(cursor, end)
                    val shouldTreatAsCode = fenceHeader.isEmpty() || fencedBlockLanguageRegex.matches(fenceHeader)
                    blocks.add(
                        if (shouldTreatAsCode) {
                            StreamBlock.CodeBlock(
                                stableId = nextId(StreamBlockType.CODE_BLOCK),
                                text = fencedText,
                                start = cursor,
                                endExclusive = end
                            )
                        } else {
                            StreamBlock.PlainText(
                                stableId = nextId(StreamBlockType.PLAIN_TEXT),
                                text = fencedText,
                                start = cursor,
                                endExclusive = end
                            )
                        }
                    )
                    cursor = end
                } else {
                    val fencedText = content.substring(cursor)
                    val shouldTreatAsCode = fenceHeader.isEmpty() || fencedBlockLanguageRegex.matches(fenceHeader)
                    blocks.add(
                        if (shouldTreatAsCode) {
                            StreamBlock.CodeBlock(
                                stableId = nextId(StreamBlockType.CODE_BLOCK),
                                text = fencedText,
                                start = cursor,
                                endExclusive = content.length
                            )
                        } else {
                            StreamBlock.PlainText(
                                stableId = nextId(StreamBlockType.PLAIN_TEXT),
                                text = fencedText,
                                start = cursor,
                                endExclusive = content.length
                            )
                        }
                    )
                    cursor = content.length
                }
                continue
            }

            if (content.startsWith("$$", cursor)) {
                val close = content.indexOf("$$", cursor + 2)
                if (close >= 0) {
                    val end = close + 2
                    blocks.add(
                        StreamBlock.MathBlock(
                            stableId = nextId(StreamBlockType.MATH_BLOCK),
                            text = content.substring(cursor, end),
                            start = cursor,
                            endExclusive = end,
                            state = MathBlockState.RENDERED,
                        )
                    )
                    cursor = end
                } else {
                    blocks.add(
                        StreamBlock.MathBlock(
                            stableId = nextId(StreamBlockType.MATH_BLOCK),
                            text = content.substring(cursor),
                            start = cursor,
                            endExclusive = content.length,
                            state = MathBlockState.RAW,
                        )
                    )
                    hasPendingMath = true
                    cursor = content.length
                }
                continue
            }

            val escapedDelimiter = when {
                content.startsWith("\\[", cursor) -> DelimiterMatch(
                    start = cursor,
                    token = "\\[",
                    type = StreamBlockType.MATH_BLOCK,
                    stateWhenClosed = MathBlockState.RENDERED,
                    stateWhenPending = MathBlockState.RAW,
                )
                content.startsWith("\\(", cursor) -> DelimiterMatch(
                    start = cursor,
                    token = "\\(",
                    type = StreamBlockType.MATH_INLINE,
                    stateWhenClosed = MathBlockState.RENDERED,
                    stateWhenPending = MathBlockState.RAW,
                )
                else -> null
            }

            if (escapedDelimiter != null) {
                val closingToken = if (escapedDelimiter.token == "\\[") "\\]" else "\\)"
                val close = content.indexOf(closingToken, cursor + escapedDelimiter.token.length)
                if (close >= 0) {
                    val end = close + closingToken.length
                    val tokenText = content.substring(cursor, end)
                    val block = when (escapedDelimiter.type) {
                        StreamBlockType.MATH_BLOCK -> StreamBlock.MathBlock(
                            stableId = nextId(StreamBlockType.MATH_BLOCK),
                            text = tokenText,
                            start = cursor,
                            endExclusive = end,
                            state = escapedDelimiter.stateWhenClosed,
                        )
                        else -> StreamBlock.MathInline(
                            stableId = nextId(StreamBlockType.MATH_INLINE),
                            text = tokenText,
                            start = cursor,
                            endExclusive = end,
                            state = escapedDelimiter.stateWhenClosed,
                        )
                    }
                    blocks.add(block)
                    cursor = end
                } else {
                    hasPendingMath = true
                    val block = when (escapedDelimiter.type) {
                        StreamBlockType.MATH_BLOCK -> StreamBlock.MathBlock(
                            stableId = nextId(StreamBlockType.MATH_BLOCK),
                            text = content.substring(cursor),
                            start = cursor,
                            endExclusive = content.length,
                            state = escapedDelimiter.stateWhenPending,
                        )
                        else -> StreamBlock.MathInline(
                            stableId = nextId(StreamBlockType.MATH_INLINE),
                            text = content.substring(cursor),
                            start = cursor,
                            endExclusive = content.length,
                            state = escapedDelimiter.stateWhenPending,
                        )
                    }
                    blocks.add(block)
                    cursor = content.length
                }
                continue
            }

            if (content[cursor] == '$') {
                val close = content.indexOf('$', cursor + 1)
                if (close >= 0) {
                    val end = close + 1
                    val inlineToken = content.substring(cursor, end)
                    val mathBody = inlineToken.removePrefix("$").removeSuffix("$")
                    if (shouldPromoteInlineMathToBlock(mathBody)) {
                        val blockToken = "$$${mathBody}$$"
                        blocks.add(
                            StreamBlock.MathBlock(
                                stableId = nextId(StreamBlockType.MATH_BLOCK),
                                text = blockToken,
                                start = cursor,
                                endExclusive = end,
                                state = MathBlockState.RENDERED
                            )
                        )
                    } else {
                        blocks.add(
                            StreamBlock.MathInline(
                                stableId = nextId(StreamBlockType.MATH_INLINE),
                                text = inlineToken,
                                start = cursor,
                                endExclusive = end,
                                state = MathBlockState.RENDERED
                            )
                        )
                    }
                    cursor = end
                } else {
                    hasPendingMath = true
                    blocks.add(
                        StreamBlock.MathInline(
                            stableId = nextId(StreamBlockType.MATH_INLINE),
                            text = content.substring(cursor),
                            start = cursor,
                            endExclusive = content.length,
                            state = MathBlockState.RAW
                        )
                    )
                    cursor = content.length
                }
                continue
            }
        }

        val hashSource = buildString {
            blocks.forEach {
                append(it.type.name)
                append('|')
                append(it.text.hashCode())
                append('|')
                append(it.start)
                append('|')
                append(it.endExclusive)
                append(';')
            }
            append("pending=").append(hasPendingMath)
        }
        return ParseResult(
            blocks = blocks,
            hasPendingMath = hasPendingMath,
            blocksHash = hashSource.hashCode().toString()
        )
    }

    /**
     * 增量解析：对 tailContent（从 globalOffset 开始的子串）执行解析，
     * 生成的 block 的 start/endExclusive 使用全局偏移量。
     * blockIndexStart 用于生成不与已提交 blocks 冲突的 stableId。
     */
    fun parseWithOffset(
        tailContent: String,
        messageId: String,
        globalOffset: Int,
        blockIndexStart: Int,
    ): ParseResult {
        if (tailContent.isEmpty()) {
            return ParseResult(emptyList(), hasPendingMath = false, blocksHash = "empty")
        }

        val fullResult = parse(tailContent, messageId)

        val offsetBlocks = fullResult.blocks.mapIndexed { index, block ->
            val newId = "$messageId:${block.type.name.lowercase(java.util.Locale.ROOT)}:${blockIndexStart + index}"
            when (block) {
                is StreamBlock.PlainText -> block.copy(
                    stableId = newId,
                    start = block.start + globalOffset,
                    endExclusive = block.endExclusive + globalOffset,
                )
                is StreamBlock.CodeBlock -> block.copy(
                    stableId = newId,
                    start = block.start + globalOffset,
                    endExclusive = block.endExclusive + globalOffset,
                )
                is StreamBlock.MathInline -> block.copy(
                    stableId = newId,
                    start = block.start + globalOffset,
                    endExclusive = block.endExclusive + globalOffset,
                )
                is StreamBlock.MathBlock -> block.copy(
                    stableId = newId,
                    start = block.start + globalOffset,
                    endExclusive = block.endExclusive + globalOffset,
                )
            }
        }

        return ParseResult(
            blocks = offsetBlocks,
            hasPendingMath = fullResult.hasPendingMath,
            blocksHash = fullResult.blocksHash,
        )
    }

    private data class FenceStart(
        val start: Int,
        val marker: String,
        val language: String,
        val headerEnd: Int,
        val indent: Int,
    )

    private data class FenceClose(
        val start: Int,
        val endExclusive: Int,
    )

    private fun findNextFenceStart(content: String, startIndex: Int): Int {
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

    private fun parseFenceStart(content: String, start: Int): FenceStart? {
        if (start < 0 || start >= content.length) return null
        val lineStart = content.lastIndexOf('\n', start).let { if (it < 0) 0 else it + 1 }
        val indentPrefix = content.substring(lineStart, start)
        if (indentPrefix.any { it != ' ' && it != '\t' }) return null

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
            indent = indentPrefix.length,
        )
    }

    private fun findFenceClose(content: String, fence: FenceStart): FenceClose? {
        var searchIndex = fence.headerEnd
        while (searchIndex < content.length) {
            val lineStart = if (searchIndex == fence.headerEnd) {
                content.indexOf('\n', searchIndex).let { if (it < 0) return null else it + 1 }
            } else {
                searchIndex
            }
            if (lineStart >= content.length) return null
            val lineEnd = content.indexOf('\n', lineStart).let { if (it < 0) content.length else it }
            val line = content.substring(lineStart, lineEnd)
            val closeOffset = findFenceCloseOffsetInLine(line, fence)
            if (closeOffset >= 0) {
                val markerLength = countFenceMarkerLength(line.substring(closeOffset), fence.marker.first())
                return FenceClose(
                    start = lineStart + closeOffset,
                    endExclusive = lineStart + closeOffset + markerLength,
                )
            }
            searchIndex = lineEnd + 1
        }
        return null
    }

    private fun findFenceCloseOffsetInLine(line: String, fence: FenceStart): Int {
        val indent = line.indexOfFirst { it != ' ' }.let { if (it < 0) line.length else it }
        if (fence.indent <= 3 && indent > 3) return -1
        if (fence.indent > 3 && indent != fence.indent) return -1

        val trimmed = line.trimStart()
        val markerChar = fence.marker.first()
        val markerLength = countFenceMarkerLength(trimmed, markerChar)
        if (markerLength < fence.marker.length) return -1
        if (trimmed.substring(markerLength).isNotBlank()) return -1
        return indent
    }

    private fun countFenceMarkerLength(text: String, markerChar: Char): Int {
        var markerLength = 0
        while (markerLength < text.length && text[markerLength] == markerChar) {
            markerLength++
        }
        return markerLength
    }
}
