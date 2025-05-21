package com.example.everytalk.util.markdown

// 定义分段类型
sealed class TextSegment {
    data class Normal(val text: String) : TextSegment()
    data class CodeBlock(val language: String?, val code: String) : TextSegment()
    data class MathFormula(val latex: String, val isBlock: Boolean) : TextSegment()
}

// parseMarkdownSegments 函数内部使用的正则表达式
// 注意：这是根据你提供的 parseMarkdownSegments 函数体内的实现来定义的。
// 如果你希望使用之前讨论的 GFM_CLOSED_CODE_BLOCK_REGEX，请替换这里的定义并在函数中相应修改。
private val codeBlockRegex = Regex("```([\\w.+-]*)[ \\t]*\\n([\\s\\S]*?)\\n[ \\t]*```")

/**
 * 将输入的Markdown字符串分割成 TextSegment 对象列表。
 * 它能识别GFM风格的闭合代码块 (例如 ```python\ncode\n```) 以及KaTeX数学公式。
 */
fun parseMarkdownSegments(markdownInput: String): List<TextSegment> {
    if (markdownInput.isBlank()) return emptyList()


    var fixedMd = markdownInput
        .replace(Regex("(```)([^\n])"), "$1\n$2")      // ``` immediately followed by non-newline -> ```\n...
        .replace(Regex("([^\n])(```)"), "$1\n$2")      // Non-newline immediately followed by ``` -> ...\n```
        .replace(Regex("[ \\t]*```[ \\t]*"), "\n```\n") // ``` surrounded by spaces/tabs -> \n```\n (normalizes)
        .replace(Regex("(```)[ \t]*([^\n ])([^`\n]*)"), "$1\n$2$3") // ``` lang (no newline after lang) -> ```\nlang
        .replace(Regex("(?m)^[ \\t]+\n"), "\n")         // Lines with only spaces/tabs then newline -> newline (removes whitespace-only lines effectively)

    val segments = mutableListOf<TextSegment>()
    var cursor = 0
    val len = fixedMd.length

    while (cursor < len) {
        val codeMatch = codeBlockRegex.find(fixedMd, cursor)
        if (codeMatch != null && codeMatch.range.first >= cursor) {
            // 处理代码块之前的部分 (可能包含普通文本和数学公式)
            val beforeCodeBlock = fixedMd.substring(cursor, codeMatch.range.first)
            if (beforeCodeBlock.isNotEmpty()) {
                segments.addAll(splitMathAndNormalUnclosedSafe(beforeCodeBlock))
            }

            // 处理代码块
            val lang = codeMatch.groups[1]?.value?.trim()?.takeIf { it.isNotEmpty() }
            val code = codeMatch.groups[2]?.value ?: "" // 代码内容保持原样，不 trim
            segments.add(TextSegment.CodeBlock(lang, code))
            cursor = codeMatch.range.last + 1
        } else {
            // 没有更多代码块，处理剩余部分 (可能包含普通文本和数学公式)
            val remainingText = fixedMd.substring(cursor)
            if (remainingText.isNotEmpty()) {
                segments.addAll(splitMathAndNormalUnclosedSafe(remainingText))
            }
            break // 处理完毕
        }
    }
    return segments
}

/**
 * 辅助函数：将不包含代码块的文本分割为普通文本 (Normal) 和数学公式 (MathFormula) 片段。
 * 它能处理行内公式 ($...$) 和块级公式 ($$...$$)。
 * @param text 不包含 "```" 代码块的纯文本。
 * @return TextSegment 列表，只包含 Normal 和 MathFormula。
 */
private fun splitMathAndNormalUnclosedSafe(text: String): List<TextSegment> {
    val segments = mutableListOf<TextSegment>()
    var i = 0
    val len = text.length

    while (i < len) {
        val idxBlock = text.indexOf("$$", i)    // 查找块级公式 " $$ "
        val idxInline = text.indexOf('$', i)   // 查找行内公式 " $ "

        // 决定下一个数学公式分隔符是块级还是行内，并获取其索引
        val (isNextBlock, nextDelimiterIndex) = when {
            // 如果 $$ 存在且位于 $ 之前 (或 $ 不存在)，则优先处理 $$
            idxBlock != -1 && (idxBlock < idxInline || idxInline == -1) -> true to idxBlock
            // 否则，如果 $ 存在，则处理 $
            idxInline != -1 -> false to idxInline
            // 如果两者都不存在，则没有更多数学公式
            else -> null to -1
        }

        if (nextDelimiterIndex == -1) { // 没有更多数学公式分隔符
            val remainingNormalText = text.substring(i)
            if (remainingNormalText.isNotEmpty()) {
                segments.add(TextSegment.Normal(remainingNormalText))
            }
            break // 处理完毕
        }

        // 添加数学公式分隔符之前的普通文本
        if (nextDelimiterIndex > i) {
            val normalTextBeforeMath = text.substring(i, nextDelimiterIndex)
            if (normalTextBeforeMath.isNotEmpty()) {
                segments.add(TextSegment.Normal(normalTextBeforeMath))
            }
        }

        if (isNextBlock == true) { // 处理块级公式 $$...$$
            val mathContentStart = nextDelimiterIndex + 2 // 跳过 "$$"
            val mathContentEnd = text.indexOf("$$", mathContentStart)
            if (mathContentEnd != -1) { // 找到匹配的结束 "$$"
                val mathLatex = text.substring(mathContentStart, mathContentEnd).trim()
                // 【修改】按照之前的约定，即使是 $$...$$，也设 isBlock = false，让WebView的KaTeX CSS去控制其内联行为
                segments.add(TextSegment.MathFormula(mathLatex, isBlock = false))
                i = mathContentEnd + 2 // 更新索引到结束 "$$" 之后
            } else { // 未找到结束 "$$"，视为到文本末尾的块级公式
                val mathLatex = text.substring(mathContentStart).trim()
                segments.add(TextSegment.MathFormula(mathLatex, isBlock = false))
                break // 处理完毕
            }
        } else { // 处理行内公式 $...$ (isNextBlock == false)
            // 必须确保这不是一个块级公式的开始 (即 $$)
            if (text.getOrNull(nextDelimiterIndex + 1) == '$') {
                // 这是 "$$" 的开始，但被优先识别为行内 $。这通常不应该发生，因为上面的 when 块会优先选 $$。
                // 但作为安全措施，如果真的走到了这里，我们将它视为一个未处理的 "$$"，
                // 并将指针移回，让外层循环或下一次迭代的 idxBlock 去正确匹配它。
                // Log.w("MarkdownParse", "Warning: Encountered '$$' but treated as inline '$' start. Index: $nextDelimiterIndex. Text: ${text.substring(nextDelimiterIndex).take(10)}")
                // 这种情况下，我们最好只消耗当前的 $ 字符，并期望外部逻辑正确处理，或者将它当作普通文本。
                // 为了简单和避免死循环，我们将此 $ 视为普通文本的一部分，然后继续。
                // 或者，更安全的做法是，如果 isNextBlock 为 false，我们已经确定它不是 $$ 的开始。
                // 所以，如果到了这里，它必然是单个 $。
                i = nextDelimiterIndex + 1 // 指向 $ 后的字符
                val mathContentStart = i
                val mathContentEnd = text.indexOf('$', mathContentStart)
                if (mathContentEnd != -1) { // 找到匹配的结束 $
                    val mathLatex = text.substring(mathContentStart, mathContentEnd).trim()
                    segments.add(TextSegment.MathFormula(mathLatex, isBlock = false))
                    i = mathContentEnd + 1 // 更新索引到结束 $ 之后
                } else { // 未找到结束 $，视为到文本末尾的行内公式
                    val mathLatex = text.substring(mathContentStart).trim()
                    segments.add(TextSegment.MathFormula(mathLatex, isBlock = false))
                    break // 处理完毕
                }
            } else { // 是单个 $
                val mathContentStart = nextDelimiterIndex + 1
                val mathContentEnd = text.indexOf('$', mathContentStart)
                if (mathContentEnd != -1) { // 找到匹配的结束 $
                    val mathLatex = text.substring(mathContentStart, mathContentEnd).trim()
                    segments.add(TextSegment.MathFormula(mathLatex, isBlock = false))
                    i = mathContentEnd + 1 // 更新索引到结束 $ 之后
                } else { // 未找到结束 $，视为到文本末尾的行内公式
                    val mathLatex = text.substring(mathContentStart).trim()
                    segments.add(TextSegment.MathFormula(mathLatex, isBlock = false))
                    break // 处理完毕
                }
            }
        }
    }
    return segments
}