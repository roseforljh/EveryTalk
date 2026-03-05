package com.android.everytalk.ui.components.markdown

/**
 * 数学分隔符规范化：
 * - [single dollar]... / [double dollar]...（行内成对）-> $$...$$
 * - 成对转义分隔符 \$...\$ -> $$...$$
 *
 * 注意：
 * - 不处理块级 $$\n...\n$$
 * - 跳过代码围栏与行内代码
 */
internal object MathDelimiterNormalizer {

    private const val DOUBLE_PLACEHOLDER = "[double dollar]"
    private const val SINGLE_PLACEHOLDER = "[single dollar]"

    fun normalize(input: String): String {
        if (input.isEmpty()) return input

        val lines = input.split("\n")
        val output = StringBuilder(input.length)
        var inFence = false
        var fenceMarker = ""

        lines.forEachIndexed { index, line ->
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
        if (line.isEmpty() || (!line.contains('$') && !line.contains(SINGLE_PLACEHOLDER) && !line.contains(DOUBLE_PLACEHOLDER))) {
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

        val out = StringBuilder(text.length)
        var i = 0

        while (i < text.length) {
            if (text.startsWith(DOUBLE_PLACEHOLDER, i)) {
                val close = text.indexOf(DOUBLE_PLACEHOLDER, i + DOUBLE_PLACEHOLDER.length)
                if (close >= 0) {
                    val inner = text.substring(i + DOUBLE_PLACEHOLDER.length, close).trim()
                    if (inner.isNotEmpty()) out.append("$$").append(inner).append("$$")
                    i = close + DOUBLE_PLACEHOLDER.length
                    continue
                }
            }

            if (text.startsWith(SINGLE_PLACEHOLDER, i)) {
                val close = text.indexOf(SINGLE_PLACEHOLDER, i + SINGLE_PLACEHOLDER.length)
                if (close >= 0) {
                    val inner = text.substring(i + SINGLE_PLACEHOLDER.length, close).trim()
                    if (inner.isNotEmpty()) out.append("$$").append(inner).append("$$")
                    i = close + SINGLE_PLACEHOLDER.length
                    continue
                }
                // 兼容旧行为：孤立 [single dollar] 也替换为 $
                out.append('$')
                i += SINGLE_PLACEHOLDER.length
                continue
            }

            // 成对转义分隔符：\$...\$ -> $$...$$
            if (i + 1 < text.length && text[i] == '\\' && text[i + 1] == '$') {
                val close = text.indexOf("\\$", i + 2)
                if (close >= 0) {
                    val inner = text.substring(i + 2, close).trim()
                    out.append("$$").append(inner).append("$$")
                    i = close + 2
                    continue
                }
            }

            out.append(text[i])
            i++
        }

        return out.toString()
    }
}
