package com.android.everytalk.ui.components

internal object MathStreamingPolicy {

    data class AffectedRange(
        val start: Int,
        val endExclusive: Int
    )

    fun hasMathSyntax(text: String): Boolean {
        return text.contains('$') || text.contains("\\[") || text.contains("\\(")
    }

    fun hasUnclosedMathDelimiter(text: String): Boolean {
        var inInlineCode = false
        var inBlockMath = false
        var inInlineMath = false
        var blockMarker = ""

        var i = 0
        while (i < text.length) {
            val ch = text[i]

            if (ch == '`') {
                inInlineCode = !inInlineCode
                i++
                continue
            }
            if (inInlineCode) {
                i++
                continue
            }

            if (inBlockMath) {
                if (text.startsWith(blockMarker, i)) {
                    inBlockMath = false
                    i += blockMarker.length
                    continue
                }
                i++
                continue
            }

            if (text.startsWith("$$", i)) {
                inBlockMath = true
                blockMarker = "$$"
                i += 2
                continue
            }

            if (ch == '\\' && i + 1 < text.length && text[i + 1] == '[') {
                inBlockMath = true
                blockMarker = "\\]"
                i += 2
                continue
            }

            if (ch == '$') {
                val isCurrency = i + 1 < text.length && text[i + 1].isDigit()
                if (!isCurrency) {
                    inInlineMath = !inInlineMath
                }
                i++
                continue
            }

            i++
        }

        return inBlockMath || inInlineMath
    }

    fun findMathAffectedRange(previous: String, current: String): AffectedRange? {
        if (previous == current) return null
        if (current.isEmpty()) return null

        if (current.length < previous.length || !current.startsWith(previous)) {
            return AffectedRange(0, current.length)
        }

        val deltaStart = previous.length
        val deltaEnd = current.length
        if (deltaStart >= deltaEnd) return null

        val contextPadding = 96
        val start = (deltaStart - contextPadding).coerceAtLeast(0)
        val end = (deltaEnd + contextPadding).coerceAtMost(current.length)
        val window = current.substring(start, end)
        if (!hasMathSyntax(window)) return null

        return AffectedRange(start, end)
    }

    fun shouldForceMathBoundaryRefresh(previous: String, current: String): Boolean {
        return findMathAffectedRange(previous, current) != null
    }

    fun escapeUnclosedMathDelimiters(input: String): String {
        if (!input.contains('$') && !input.contains("\\[")) return input

        var inInlineCode = false
        var inMathBlock = false
        var mathBlockStart = -1
        var mathBlockMarker = ""
        var inInlineMath = false
        var inlineMathStart = -1

        var i = 0
        while (i < input.length) {
            val ch = input[i]

            if (ch == '`') {
                inInlineCode = !inInlineCode
                i++
                continue
            }
            if (inInlineCode) {
                i++
                continue
            }

            if (inMathBlock) {
                if (input.startsWith(mathBlockMarker, i)) {
                    inMathBlock = false
                    mathBlockStart = -1
                    i += mathBlockMarker.length
                    continue
                }
                i++
                continue
            }

            if (input.startsWith("$$", i)) {
                inMathBlock = true
                mathBlockMarker = "$$"
                mathBlockStart = i
                i += 2
                continue
            }

            if (ch == '\\' && i + 1 < input.length && input[i + 1] == '[') {
                inMathBlock = true
                mathBlockMarker = "\\]"
                mathBlockStart = i
                i += 2
                continue
            }

            if (ch == '$') {
                val isCurrency = i + 1 < input.length && input[i + 1].isDigit()
                if (!isCurrency) {
                    inInlineMath = !inInlineMath
                    if (inInlineMath) inlineMathStart = i else inlineMathStart = -1
                }
                i++
                continue
            }

            i++
        }

        if (!inMathBlock && !inInlineMath) return input

        data class Edit(val pos: Int, val origLen: Int, val replacement: String)
        val edits = mutableListOf<Edit>()

        if (inMathBlock && mathBlockStart >= 0) {
            if (input.startsWith("$$", mathBlockStart)) {
                edits.add(Edit(mathBlockStart, 2, "\\$\\$"))
            } else if (input.startsWith("\\[", mathBlockStart)) {
                edits.add(Edit(mathBlockStart, 2, "\\\\["))
            }
        }
        if (inInlineMath && inlineMathStart >= 0) {
            edits.add(Edit(inlineMathStart, 1, "\\$"))
        }

        val sb = StringBuilder(input)
        edits.sortByDescending { it.pos }
        for (edit in edits) {
            sb.replace(edit.pos, edit.pos + edit.origLen, edit.replacement)
        }
        return sb.toString()
    }

    /**
     * 数学渲染失败时的降级策略：转义所有数学分隔符，尽量保留 Markdown 的其余样式渲染。
     */
    fun escapeAllMathDelimiters(input: String): String {
        if (!hasMathSyntax(input)) return input

        val output = StringBuilder(input.length + 16)
        var inInlineCode = false
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            if (ch == '`') {
                inInlineCode = !inInlineCode
                output.append(ch)
                i++
                continue
            }
            if (inInlineCode) {
                output.append(ch)
                i++
                continue
            }

            if (input.startsWith("$$", i)) {
                output.append("\\$\\$")
                i += 2
                continue
            }

            if (ch == '$') {
                output.append("\\$")
                i++
                continue
            }

            if (ch == '\\' && i + 1 < input.length) {
                val next = input[i + 1]
                if (next == '[' || next == ']' || next == '(' || next == ')') {
                    output.append("\\\\")
                    output.append(next)
                    i += 2
                    continue
                }
            }

            output.append(ch)
            i++
        }
        return output.toString()
    }
}
