package com.example.everytalk.ui.components.math

/**
 * MathParser
 *
 * 仅通过新文件提供数学解析与安全断点能力，不修改现有解析器。
 * 规则聚焦两类定界：
 * - 内联：$...$（不跨行，遇到换行视作未闭合）
 * - 块级：$$...$$（可跨行）
 *
 * 解析目标：
 * - splitToSpans: 将原文按 Text/Math 两类切分，忽略代码围栏 ``` 内的 $/$$
 * - isInsideUnclosedMath: 判断当前缓冲是否处于“未闭合数学”状态（奇数 $ 或未成对 $$）
 * - findSafeMathCut: 找到最近一个“已闭合数学边界”的安全切点（用于流式分块）
 */
object MathParser {

    sealed class Span {
        data class Text(val content: String) : Span()
        data class Math(val content: String, val inline: Boolean) : Span()
    }

    /**
     * 将整段文本拆分为 Text / Math(Inline/Block) 片段。
     * - 避开 ``` 围栏代码块
     * - 优先识别 $$...$$，其次识别 $...$（$...$ 默认不跨行）
     * - 转义 \$ 不计为定界
     */
    fun splitToSpans(input: String): List<Span> {
        if (input.isEmpty()) return listOf(Span.Text(""))
        val out = mutableListOf<Span>()
        val sb = StringBuilder()

        var i = 0
        var inFence = false
        fun flushText() {
            if (sb.isNotEmpty()) {
                out += Span.Text(sb.toString())
                sb.setLength(0)
            }
        }

        while (i < input.length) {
            // 代码围栏（仅识别 ```，不处理缩进变体）
            if (!inFence && i + 2 < input.length && input[i] == '`' && input[i + 1] == '`' && input[i + 2] == '`') {
                // 进入围栏
                sb.append("```")
                i += 3
                inFence = true
                continue
            } else if (inFence && i + 2 < input.length && input[i] == '`' && input[i + 1] == '`' && input[i + 2] == '`') {
                // 退出围栏
                sb.append("```")
                i += 3
                inFence = false
                continue
            }

            if (inFence) {
                sb.append(input[i])
                i++
                continue
            }

            // 尝试块级 $$...$$
            if (i + 1 < input.length && input[i] == '$' && input[i + 1] == '$' && !isEscaped(input, i)) {
                // 寻找下一个未转义的 $$ 作为结束
                val (found, endIdx) = findBlockEnd(input, i + 2)
                if (found) {
                    // 推出前面的文本
                    flushText()
                    // 仅截取纯 LaTeX 内容（不包含任何定界 $）
                    // findBlockEnd 返回的 endIdx 为“闭合 $$ 的第一个 $ 的前一个字符”的索引（即内容最后一个字符的索引，inclusive）
                    val content = input.substring(i + 2, endIdx + 1) // substring 右边界为 exclusive
                    out += Span.Math(content = content, inline = false)
                    // 关闭对位于 endIdx+1 和 endIdx+2（两个 $），跳过后续从 endIdx+3 继续
                    i = endIdx + 3
                    continue
                } else {
                    // 未闭合，视作普通文本
                    sb.append(input[i])
                    i++
                    continue
                }
            }

            // 尝试内联 $...$
            if (input[i] == '$' && !isEscaped(input, i)) {
                // 内联不跨行；若后续找不到匹配或遇到换行则当作普通字符
                val (found, endIdx) = findInlineEndSameLine(input, i + 1)
                if (found) {
                    flushText()
                    // 仅截取纯 LaTeX 内容（不包含任何定界 $）
                    // findInlineEndSameLine 返回的 endIdx 为内容最后一个字符的索引（inclusive）
                    val content = input.substring(i + 1, endIdx + 1) // substring 右边界为 exclusive
                    out += Span.Math(content = content, inline = true)
                    // 闭合 $ 在 endIdx+1，跳过它后从 endIdx+2 开始
                    i = endIdx + 2
                    continue
                } else {
                    sb.append(input[i])
                    i++
                    continue
                }
            }

            // 默认作为文本
            sb.append(input[i])
            i++
        }

        flushText()
        return out
    }

    /**
     * 是否处于“未闭合数学”状态：
     * - 存在未闭合的 $$...（找到了起始 $$ 但没有匹配的终止 $$）
     * - 或者（排除 $$ 成对后）余下的单个 $ 为奇数个（存在未闭合 $）
     * - 不统计 ``` 围栏内的 $/$$
     */
    fun isInsideUnclosedMath(buffer: String): Boolean {
        var i = 0
        var inFence = false
        var doubleFenceOpen = false
        var singleDollarCount = 0

        while (i < buffer.length) {
            if (!inFence && i + 2 < buffer.length && buffer[i] == '`' && buffer[i + 1] == '`' && buffer[i + 2] == '`') {
                inFence = true; i += 3; continue
            } else if (inFence && i + 2 < buffer.length && buffer[i] == '`' && buffer[i + 1] == '`' && buffer[i + 2] == '`') {
                inFence = false; i += 3; continue
            }
            if (inFence) { i++; continue }

            if (i + 1 < buffer.length && buffer[i] == '$' && buffer[i + 1] == '$' && !isEscaped(buffer, i)) {
                doubleFenceOpen = !doubleFenceOpen
                i += 2
                continue
            }

            if (buffer[i] == '$' && !isEscaped(buffer, i)) {
                singleDollarCount++
            }
            i++
        }

        if (doubleFenceOpen) return true
        // 剔除 $$ 之后残留的 $ 奇偶性：若奇数，说明有未闭合 $（仅近似，足以流式安全判断）
        return (singleDollarCount % 2) == 1
    }

    /**
     * 查找最近的“安全切点”（闭合的数学定界之后）。
     * 优先顺序：
     * - 最近闭合的 $$...$$ 的结束位置之后
     * - 其次最近闭合的 $...$ 的结束位置之后（同一行）
     * - 若均无，则返回 0（表示继续缓冲）
     */
    fun findSafeMathCut(text: String): Int {
        var i = 0
        var inFence = false
        var lastSafe = -1

        fun update(pos: Int) {
            if (pos > lastSafe) lastSafe = pos
        }

        while (i < text.length) {
            if (!inFence && i + 2 < text.length && text[i] == '`' && text[i + 1] == '`' && text[i + 2] == '`') {
                inFence = true; i += 3; continue
            } else if (inFence && i + 2 < text.length && text[i] == '`' && text[i + 1] == '`' && text[i + 2] == '`') {
                inFence = false; i += 3; continue
            }
            if (inFence) { i++; continue }

            // $$...$$
            if (i + 1 < text.length && text[i] == '$' && text[i + 1] == '$' && !isEscaped(text, i)) {
                val (found, endIdx) = findBlockEnd(text, i + 2)
                if (!found) { i++ ; continue }
                // endIdx 为闭合 $$ 的第一个 $ 的前一位；闭合 $$ 覆盖 endIdx+1 和 endIdx+2
                // 安全切点设为闭合 $$ 之后
                update(endIdx + 3)
                i = endIdx + 3
                continue
            }

            // $...$（仅同一行）
            if (text[i] == '$' && !isEscaped(text, i)) {
                val (found, endIdx) = findInlineEndSameLine(text, i + 1)
                if (found) {
                    // endIdx 为内容最后一个字符；闭合 $ 在 endIdx+1
                    update(endIdx + 2)
                    i = endIdx + 2
                    continue
                }
            }
            i++
        }
        return if (lastSafe >= 0) lastSafe else 0
    }

    // ------------------- helpers -------------------

    private fun isEscaped(s: String, idx: Int): Boolean {
        // 偶数个反斜杠不过滤，奇数个认为转义
        var backslashes = 0
        var i = idx - 1
        while (i >= 0 && s[i] == '\\') { backslashes++; i-- }
        return (backslashes % 2) == 1
    }

    // 查找 $$...$$ 的结束下标（content 的最后一个字符位置，即右侧 $$ 的前一个字符）
    private fun findBlockEnd(s: String, from: Int): Pair<Boolean, Int> {
        var i = from
        while (i + 1 <= s.lastIndex) {
            if (s[i] == '$' && i + 1 < s.length && s[i + 1] == '$' && !isEscaped(s, i)) {
                return true to (i - 1)
            }
            i++
        }
        return false to -1
    }

    // 查找同一行内 $...$ 的结束（返回结束 $ 的前一个字符下标）
    private fun findInlineEndSameLine(s: String, from: Int): Pair<Boolean, Int> {
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (c == '\n' || c == '\r') return false to -1
            if (c == '$' && !isEscaped(s, i)) {
                // 空内容不认：$ $
                if (i == from) return false to -1
                return true to (i - 1)
            }
            i++
        }
        return false to -1
    }
}