package com.android.everytalk.ui.components.streaming

import com.android.everytalk.ui.components.markdown.preprocessMarkdownExtensions
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

object StreamBlockParser {
    data class ParseResult(
        val blocks: List<StreamBlock>,
        val hasPendingMath: Boolean,
        val blocksHash: String
    )

    fun parse(content: String, messageId: String): ParseResult {
        if (content.isEmpty()) {
            return ParseResult(emptyList(), hasPendingMath = false, blocksHash = "empty")
        }

        val mathProtectionMask = buildMathProtectionMask(content)
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
            val inlineCodeStart = findNextInlineCodeStart(content, cursor)
            val mathBlockStart = findNextMathTokenStart(content, "$$", cursor, mathProtectionMask)
            val inlineStart = findNextInlineMathStart(content, cursor, mathProtectionMask)
            val escapedInlineStart = findNextMathTokenStart(content, "\\(", cursor, mathProtectionMask)
            val escapedBlockStart = findNextEscapedBlockMathStart(content, cursor, mathProtectionMask)

            val candidates = mutableListOf<Int>()
            if (codeStart >= 0) candidates.add(codeStart)
            if (inlineCodeStart >= 0) candidates.add(inlineCodeStart)
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
                val end = findFenceEnd(content, fence)
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
                continue
            }

            val inlineCodeEnd = findInlineCodeEnd(content, cursor)
            if (inlineCodeEnd != null) {
                appendPlain(cursor, inlineCodeEnd)
                cursor = inlineCodeEnd
                continue
            }

            if (content.startsWith("$$", cursor)) {
                val close = findNextMathTokenStart(content, "$$", cursor + 2, mathProtectionMask)
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
                val close = findNextMathTokenStart(
                    content = content,
                    token = closingToken,
                    startIndex = cursor + escapedDelimiter.token.length,
                    mathProtectionMask = mathProtectionMask,
                )
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
                val close = findNextUnescapedSingleDollarOnLine(content, cursor + 1, mathProtectionMask)
                if (close >= 0) {
                    val end = close + 1
                    val inlineToken = content.substring(cursor, end)
                    blocks.add(
                        StreamBlock.MathInline(
                            stableId = nextId(StreamBlockType.MATH_INLINE),
                            text = inlineToken,
                            start = cursor,
                            endExclusive = end,
                            state = MathBlockState.RENDERED
                        )
                    )
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

    fun prepareMessage(
        content: String,
        messageId: String,
        contentVersion: Long,
        includePendingMathRaw: Boolean = true,
    ): PreparedMessage {
        val parsed = parse(content, messageId)
        return prepareMessage(
            content = content,
            blocks = parsed.blocks,
            hasPendingFormula = parsed.hasPendingMath,
            contentVersion = contentVersion,
            includePendingMathRaw = includePendingMathRaw,
        )
    }

    internal fun prepareMessage(
        content: String,
        blocks: List<StreamBlock>,
        hasPendingFormula: Boolean,
        contentVersion: Long,
        includePendingMathRaw: Boolean = true,
    ): PreparedMessage {
        val renderableContent = unwrapRenderableMarkdownFences(content)
        val renderBlocks: List<StreamBlock>
        val renderHasPendingFormula: Boolean
        if (renderableContent == content) {
            renderBlocks = blocks
            renderHasPendingFormula = hasPendingFormula
        } else {
            val reparsed = parse(renderableContent, "renderable-markdown")
            renderBlocks = reparsed.blocks
            renderHasPendingFormula = reparsed.hasPendingMath
        }
        val formulas = linkedMapOf<String, FormulaRequest>()

        fun registerFormula(token: String, displayMode: FormulaDisplayMode): FormulaRequest {
            val latex = extractFormulaBody(token)
            val id = createFormulaId(latex, displayMode)
            return FormulaRequest(
                id = id,
                latex = latex,
                displayMode = displayMode,
                contentVersion = contentVersion,
            ).also { formulas[id] = it }
        }

        val markdown = buildString(renderableContent.length) {
            renderBlocks.forEach { block ->
                when (block) {
                    is StreamBlock.PlainText,
                    is StreamBlock.CodeBlock,
                    -> append(block.text)

                    is StreamBlock.MathInline -> {
                        if (block.state == MathBlockState.RENDERED) {
                            val formula = registerFormula(block.text, FormulaDisplayMode.INLINE)
                            append("![math](")
                            append(INLINE_FORMULA_SCHEME)
                            append(formula.id)
                            append(')')
                        } else if (includePendingMathRaw) {
                            append(block.text)
                        }
                    }

                    is StreamBlock.MathBlock -> {
                        if (block.state == MathBlockState.RENDERED) {
                            val formula = registerFormula(block.text, FormulaDisplayMode.BLOCK)
                            if (isNotEmpty() && !endsWith("\n\n")) append("\n\n")
                            append("```")
                            append(BLOCK_FORMULA_FENCE_LANGUAGE)
                            append('\n')
                            append(formula.id)
                            append("\n```")
                            if (block.endExclusive < renderableContent.length) append("\n\n")
                        } else if (includePendingMathRaw) {
                            append(block.text)
                        }
                    }
                }
            }
        }
        val extensionResult = preprocessMarkdownExtensions(
            markdown = markdown,
            contentVersion = contentVersion,
        )
        val referencedFormulas = formulas.filterKeys { formulaId ->
            extensionResult.markdown.contains(formulaId) ||
                extensionResult.details.values.any { details ->
                    details.summary.contains(formulaId) || details.markdown.contains(formulaId)
                }
        }
        return PreparedMessage(
            markdown = extensionResult.markdown,
            formulas = referencedFormulas,
            hasPendingFormula = renderHasPendingFormula,
            contentVersion = contentVersion,
            details = extensionResult.details,
        )
    }

    /**
     * 模型经常用 markdown 或 md 围栏包裹本应直接显示的完整正文。
     * 这里只移除这两种外层围栏，围栏内的真实语言代码块仍交给 CodeBlockCard。
     */
}
