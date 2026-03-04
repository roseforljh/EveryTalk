package com.android.everytalk.ui.components.markdown

/**
 * 数学分隔符规范化：
 * - 行内 $$...$$ -> $...$
 * - [single dollar]... / [double dollar]...（行内成对）-> $...$
 * - 成对转义分隔符 \$...\$ -> $...$
 *
 * 注意：
 * - 不处理块级 $$\n...\n$$
 * - 跳过代码围栏与行内代码，避免误改代码内容
 */
internal object MathDelimiterNormalizer {

    private val inlineDoubleDollarPattern = Regex("(?<!\\$)\\$\\$([^\\n$][^\\n]*?)\\$\\$(?!\\$)")
    private val inlineDoublePlaceholderPattern = Regex("\\[double dollar]([^\\[]+?)\\[double dollar]")
    private val inlineSinglePlaceholderPattern = Regex("\\[single dollar]([^\\[]+?)\\[single dollar]")
    private val singlePlaceholderPattern = Regex("\\[single dollar]")
    private val escapedInlineMathPattern = Regex("\\\\\\$([^$\\n]+?)\\\\\\$")

    fun normalize(input: String): String {
        if (input.isEmpty()) return input

        val lines = input.split("\n")
        val output = StringBuilder(input.length)
        var inFence = false
        var fenceMarker = ""

        lines.forEachIndexed { index, rawLine ->
            val line = rawLine
            val trimmed = line.trimStart()

            if (!inFence && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) {
                inFence = true
                fenceMarker = if (trimmed.startsWith("~~~")) "~~~" else "```"
                output.append(line)
            } else if (inFence && trimmed.startsWith(fenceMarker)) {
                inFence = false
                fenceMarker = ""
                output.append(line)
            } else if (inFence) {
                output.append(line)
            } else {
                output.append(normalizeOutsideInlineCode(line))
            }

            if (index != lines.lastIndex) output.append('\n')
        }

        return output.toString()
    }

    private fun normalizeOutsideInlineCode(line: String): String {
        if (line.isEmpty() || !line.contains('$') && !line.contains("[single dollar]") && !line.contains("[double dollar]")) {
            return line
        }

        val result = StringBuilder(line.length)
        var inInlineCode = false
        var segmentStart = 0
        var i = 0

        while (i < line.length) {
            val ch = line[i]
            val isBacktick = ch == '`' && (i == 0 || line[i - 1] != '\\')
            if (isBacktick) {
                val segment = line.substring(segmentStart, i)
                if (!inInlineCode) {
                    result.append(normalizeSegment(segment))
                } else {
                    result.append(segment)
                }

                result.append(ch)
                inInlineCode = !inInlineCode
                segmentStart = i + 1
            }
            i++
        }

        val tail = line.substring(segmentStart)
        if (!inInlineCode) {
            result.append(normalizeSegment(tail))
        } else {
            result.append(tail)
        }

        return result.toString()
    }

    private fun normalizeSegment(text: String): String {
        if (text.isEmpty()) return text

        var s = text

        // 行内占位符先归一
        s = s.replace(inlineDoublePlaceholderPattern) { mr ->
            val inner = mr.groupValues[1].trim()
            if (inner.isEmpty()) "" else "\$${inner}\$"
        }
        s = s.replace(inlineSinglePlaceholderPattern) { mr ->
            val inner = mr.groupValues[1].trim()
            if (inner.isEmpty()) "" else "\$${inner}\$"
        }
        s = s.replace(singlePlaceholderPattern, "$")

        // 成对转义分隔符
        s = s.replace(escapedInlineMathPattern) { mr ->
            "\$" + mr.groupValues[1].trim() + "\$"
        }

        // 行内 $$...$$ -> $...$
        s = s.replace(inlineDoubleDollarPattern) { mr ->
            "\$" + mr.groupValues[1].trim() + "\$"
        }

        return s
    }
}
