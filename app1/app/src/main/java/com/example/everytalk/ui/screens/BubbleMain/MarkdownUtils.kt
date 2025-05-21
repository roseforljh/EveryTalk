package com.example.everytalk.util.markdown

// 定义分段类型 (保持不变)
sealed class TextSegment {
    data class Normal(val text: String) : TextSegment()
    data class CodeBlock(val language: String?, val code: String) : TextSegment()
    data class MathFormula(val latex: String, val isBlock: Boolean) : TextSegment()
}

fun parseMarkdownSegments(markdownInput: String): List<TextSegment> {
    if (markdownInput.isBlank()) return emptyList()
    // 1. 预处理代码块(与你原逻辑一致)
    var fixedMd = markdownInput
        .replace(Regex("(```)([^\n])"), "$1\n$2")
        .replace(Regex("([^\n])(```)"), "$1\n$2")
        .replace(Regex("[ \\t]*```[ \\t]*"), "\n```\n")
        .replace(Regex("(```)[ \t]*([^\n ])([^`\n]*)"), "$1\n$2$3")
        .replace(Regex("(?m)^[ \\t]+\n"), "\n")
    val segments = mutableListOf<TextSegment>()
    var cursor = 0
    val len = fixedMd.length
    val codeBlockRegex = Regex("```([\\w.+-]*)[ \\t]*\\n([\\s\\S]*?)\\n[ \\t]*```")
    while (cursor < len) {
        val codeMatch = codeBlockRegex.find(fixedMd, cursor)
        if (codeMatch != null && codeMatch.range.first >= cursor) {
            val before = fixedMd.substring(cursor, codeMatch.range.first)
            segments.addAll(splitMathAndNormalUnclosedSafe(before))
            val lang = codeMatch.groups[1]?.value?.trim()?.takeIf { it.isNotEmpty() }
            val code = codeMatch.groups[2]?.value ?: ""
            segments.add(TextSegment.CodeBlock(lang, code))
            cursor = codeMatch.range.last + 1
        } else {
            val remain = fixedMd.substring(cursor)
            segments.addAll(splitMathAndNormalUnclosedSafe(remain))
            break
        }
    }
    return segments
}

private fun splitMathAndNormalUnclosedSafe(text: String): List<TextSegment> {
    val segments = mutableListOf<TextSegment>()
    var i = 0
    val len = text.length
    while (i < len) {
        val idxBlock = text.indexOf("$$", i)
        val idxInline = text.indexOf('$', i)
        val (nextType, nextIdx) = when {
            idxBlock != -1 && (idxBlock == i) -> true to idxBlock        // $$优先且不把单$误判
            idxBlock != -1 && (idxBlock < idxInline || idxInline == -1) -> true to idxBlock
            idxInline != -1 -> false to idxInline
            else -> null to -1
        }
        if (nextIdx == -1) {
            val remain = text.substring(i)
            if (remain.isNotEmpty()) segments.add(TextSegment.Normal(remain))
            break
        }
        // 分割前的普通文本
        if (nextIdx > i) {
            val before = text.substring(i, nextIdx)
            if (before.isNotEmpty()) segments.add(TextSegment.Normal(before))
        }
        if (nextType == true) {
            // $$ math block
            val start = nextIdx + 2
            val end = text.indexOf("$$", start)
            if (end != -1) {
                val math = text.substring(start, end)
                // MODIFICATION: Treat $$ as inline for rendering purposes by setting isBlock = false
                segments.add(TextSegment.MathFormula(math.trim(), isBlock = false))
                i = end + 2
            } else {
                val math = text.substring(start)
                // MODIFICATION: Treat $$ as inline for rendering purposes by setting isBlock = false
                segments.add(TextSegment.MathFormula(math.trim(), isBlock = false))
                break
            }
        } else if (nextType == false) {
            // 行内math"$"，需要不是 $$...
            if (text.getOrNull(nextIdx + 1) == '$') {
                // This is '$$', let the block logic above handle it.
                // Move cursor to the start of '$$' so it's picked up as a block in the next iteration.
                i = nextIdx
                continue
            }
            val start = nextIdx + 1
            val end = text.indexOf('$', start)
            if (end != -1) {
                val math = text.substring(start, end)
                segments.add(TextSegment.MathFormula(math.trim(), isBlock = false))
                i = end + 1
            } else {
                val math = text.substring(start)
                segments.add(TextSegment.MathFormula(math.trim(), isBlock = false))
                break
            }
        }
    }
    return segments
}