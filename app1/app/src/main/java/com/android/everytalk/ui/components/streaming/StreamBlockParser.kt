package com.android.everytalk.ui.components.streaming

import java.util.Locale

object StreamBlockParser {

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
            val codeStart = content.indexOf("```", cursor)
            val mathBlockStart = content.indexOf("$$", cursor)
            val inlineStart = content.indexOf('$', cursor)

            val candidates = mutableListOf<Int>()
            if (codeStart >= 0) candidates.add(codeStart)
            if (mathBlockStart >= 0) candidates.add(mathBlockStart)
            if (inlineStart >= 0) candidates.add(inlineStart)
            val nextSpecial = candidates.minOrNull()

            if (nextSpecial == null) {
                appendPlain(cursor, content.length)
                break
            }

            if (nextSpecial > cursor) {
                appendPlain(cursor, nextSpecial)
                cursor = nextSpecial
            }

            if (content.startsWith("```", cursor)) {
                val close = content.indexOf("```", cursor + 3)
                if (close >= 0) {
                    val end = close + 3
                    blocks.add(
                        StreamBlock.CodeBlock(
                            stableId = nextId(StreamBlockType.CODE_BLOCK),
                            text = content.substring(cursor, end),
                            start = cursor,
                            endExclusive = end
                        )
                    )
                    cursor = end
                } else {
                    blocks.add(
                        StreamBlock.CodeBlock(
                            stableId = nextId(StreamBlockType.CODE_BLOCK),
                            text = content.substring(cursor),
                            start = cursor,
                            endExclusive = content.length
                        )
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
                            state = MathBlockState.COMPLETE
                        )
                    )
                    cursor = end
                } else {
                    hasPendingMath = true
                    blocks.add(
                        StreamBlock.MathBlock(
                            stableId = nextId(StreamBlockType.MATH_BLOCK),
                            text = content.substring(cursor),
                            start = cursor,
                            endExclusive = content.length,
                            state = MathBlockState.PENDING
                        )
                    )
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
                                state = MathBlockState.COMPLETE
                            )
                        )
                    } else {
                        blocks.add(
                            StreamBlock.MathInline(
                                stableId = nextId(StreamBlockType.MATH_INLINE),
                                text = inlineToken,
                                start = cursor,
                                endExclusive = end,
                                state = MathBlockState.COMPLETE
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
                            state = MathBlockState.PENDING
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
}
