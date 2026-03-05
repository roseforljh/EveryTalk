package com.android.everytalk.ui.components

internal object MathStreamingPolicy {

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

    fun shouldForceMathBoundaryRefresh(previous: String, current: String): Boolean {
        if (current.length <= previous.length) return true
        if (!current.startsWith(previous)) return true

        val delta = current.substring(previous.length)
        if (delta.isEmpty()) return false

        return delta.contains("$$") ||
            delta.contains("\\]") ||
            delta.any {
                it == '\n' ||
                    it == '.' || it == '!' || it == '?' ||
                    it == '\u3002' || it == '\uFF01' || it == '\uFF1F'
            }
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
