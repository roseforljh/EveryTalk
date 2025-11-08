package com.android.everytalk.ui.components.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import kotlin.math.min

/**
 * 轻量级内联 Markdown 渲染（仅支持粗体/斜体/行内代码），O(n) 线性扫描。
 * 设计目标：在流式长文本/含表格代码等场景避免重型解析导致的卡顿，同时让常用样式即时生效。
 *
 * 支持语法（不处理嵌套/跨段/围栏）：
 *  - **bold**
 *  - *italic* 或 _italic_
 *  - `code`
 *
 * 风险与保护：
 *  - 分段预算（最大标注段数），防止恶意构造触发大量 span
 *  - 最大跨度保护：单段超长时强制闭合
 *  - 遇到不成对的起始标记，按普通文本输出
 */
object LightweightInlineMarkdown {

    // 可调参数（必要时可由 BuildConfig 注入）
    private const val INLINE_SEGMENT_BUDGET_DEFAULT = 200
    private const val INLINE_MAX_SPAN_CHARS_DEFAULT = 8_192

    data class Options(
        val maxSegments: Int = INLINE_SEGMENT_BUDGET_DEFAULT,
        val maxSpanChars: Int = INLINE_MAX_SPAN_CHARS_DEFAULT,
        val extended: Boolean = true // 启用行级标题/列表/链接的最小化样式与链接注解
    )

    private enum class State { TEXT, BOLD, ITALIC, CODE }

    /**
     * 将 markdown 文本转换为 AnnotatedString（仅内联样式）。
     *
     * @param markdown 源文本
     * @param baseStyleColor 文本颜色
     * @param isDark 是否深色主题（用于行内代码颜色微调，如需）
     * @param opts 预算与跨度配置
     */
    fun renderInlineAnnotated(
        markdown: String,
        baseStyleColor: Color,
        isDark: Boolean,
        opts: Options = Options()
    ): AnnotatedString {
        if (markdown.isEmpty()) return AnnotatedString("")
        // 预规范化：兼容 AI 输出中的不可见字符/全角符号/CRLF
        val source: String = markdown
            .replace("\r\n", "\n")
            .replace('\u00A0', ' ')          // NBSP -> 空格
            .replace('\u2007', ' ')          // FIGURE SPACE -> 空格
            .replace('\u202F', ' ')          // NNBSP -> 空格
            .replace("\u2060", "")           // WORD JOINER
            .replace("\uFEFF", "")           // ZERO WIDTH NO-BREAK SPACE (BOM)
            .replace("\u200B", "")           // ZWSP
            .replace("\u200C", "")           // ZWNJ
            .replace("\u200D", "")           // ZWJ
            // 各类“星号变体”归一到 ASCII *
            .replace('\uFF0A', '*')          // 全角＊ -> *
            .replace('\u2217', '*')          // ∗ -> *
            .replace('\u204E', '*')          // ⁎ -> *
            .replace('\u2731', '*')          // ✱ -> *
            .replace('\u066D', '*')          // ٭ -> *
            // 常见中文标点的半/全角差异归一，避免被当作分隔导致匹配失败
            .replace('\u3000', ' ')          // 全角空格 -> 半角

        // 状态机

        val maxSegments = opts.maxSegments.coerceAtLeast(1)
        val maxSpan = opts.maxSpanChars.coerceAtLeast(256)

        var segments = 0
        var state = State.TEXT

        // 记录当前段落的起始位置（在输出缓冲中的位置）
        var currentSpanStart = -1
        var i = 0
        val n = source.length

        // 样式定义
        val boldStyle = SpanStyle(
            fontWeight = FontWeight.Bold,
            color = baseStyleColor
        )
        val italicStyle = SpanStyle(
            fontStyle = FontStyle.Italic,
            color = baseStyleColor
        )
        val codeStyle = SpanStyle(
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF008ACF),
            background = if (isDark) Color(0xFF000000) else Color(0xFFFFFFFF)
        )

        // 输出缓冲
        val out = StringBuilder(n + (n / 16)) // 预估容量，降低扩容
        // 已应用的样式区间（start, end, styleId）
        data class Range(val start: Int, val end: Int, val style: SpanStyle)
        val spans = ArrayList<Range>(min(maxSegments, 256))

        fun openSpan(styleState: State, outputPos: Int) {
            if (segments >= maxSegments) return
            if (currentSpanStart != -1) return
            currentSpanStart = outputPos
            state = styleState
        }

        fun closeSpan(style: SpanStyle, outputPos: Int) {
            if (segments >= maxSegments) {
                // 超预算，直接忽略样式闭合，回到 TEXT
                currentSpanStart = -1
                state = State.TEXT
                return
            }
            val start = currentSpanStart
            if (start >= 0 && outputPos > start) {
                spans.add(Range(start, outputPos, style))
                segments++
            }
            currentSpanStart = -1
            state = State.TEXT
        }

        fun forceCloseIfTooLong(outputPos: Int) {
            if (currentSpanStart >= 0 && outputPos - currentSpanStart >= maxSpan) {
                when (state) {
                    State.BOLD -> closeSpan(boldStyle, outputPos)
                    State.ITALIC -> closeSpan(italicStyle, outputPos)
                    State.CODE -> closeSpan(codeStyle, outputPos)
                    else -> {
                        currentSpanStart = -1
                        state = State.TEXT
                    }
                }
            }
        }

        while (i < n) {
            val ch = source[i]

            // 反斜杠转义：\* \` 等，输出下一个字面字符
            if (ch == '\\' && i + 1 < n) {
                out.append(source[i + 1])
                i += 2
                forceCloseIfTooLong(out.length)
                continue
            }

            // 代码优先处理：`code`
            if (ch == '`') {
                when (state) {
                    State.TEXT -> {
                        if (segments < maxSegments) {
                            openSpan(State.CODE, out.length)
                            // 不把 ` 写入输出
                        } else {
                            // 超预算：按字面输出，避免吞字符
                            out.append('`')
                        }
                    }
                    State.CODE -> {
                        closeSpan(codeStyle, out.length)
                    }
                    State.BOLD, State.ITALIC -> {
                        // 在粗体/斜体中遇到 ` ：按普通字符输出，避免复杂嵌套
                        out.append(ch)
                    }
                }
                i += 1
                continue
            }

            // 粗体：**...**
            if (ch == '*' && i + 1 < n && source[i + 1] == '*') {
                when (state) {
                    State.TEXT -> {
                        if (segments < maxSegments) {
                            openSpan(State.BOLD, out.length)
                        } else {
                            // 超预算：按字面输出，避免吞掉 **
                            out.append('*'); out.append('*')
                        }
                    }
                    State.BOLD -> {
                        closeSpan(boldStyle, out.length)
                    }
                    State.ITALIC, State.CODE -> {
                        // 不支持嵌套，按字面输出两个 *
                        out.append('*'); out.append('*')
                    }
                }
                i += 2
                continue
            }

            // 斜体：*...* 或 _..._
            if (ch == '*' || ch == '_') {
                // 上下文判定：避免把列表项的 '*' 当作斜体起始
                // 1) 行首或换行后，且后面是空白（如 "* "）-> 视为列表标记，按字面输出
                // 2) 紧邻空白（"* 文本" 或 "文本 *"）也视为字面
                val prevSrc = if (i == 0) '\n' else source[i - 1]
                val nextSrc = if (i + 1 < n) source[i + 1] else '\u0000'
                val isAtLineStart = prevSrc == '\n' || prevSrc == '\r'
                val nextIsSpace = nextSrc.isWhitespace()
                val prevIsSpace = prevSrc.isWhitespace()

                val treatAsLiteral = (isAtLineStart && nextIsSpace) || nextIsSpace || prevIsSpace

                if (treatAsLiteral) {
                    // 列表或空白相邻场景，输出字面字符
                    out.append(ch)
                    i += 1
                    continue
                }

                when (state) {
                    State.TEXT -> {
                        if (segments < maxSegments) {
                            openSpan(State.ITALIC, out.length)
                        } else {
                            // 超预算：按字面输出
                            out.append(ch)
                        }
                    }
                    State.ITALIC -> {
                        closeSpan(italicStyle, out.length)
                    }
                    State.BOLD, State.CODE -> {
                        out.append(ch)
                    }
                }
                i += 1
                continue
            }

            // 换行符：强制重置所有内联状态
            if (ch == '\n') {
                if (state != State.TEXT) {
                    // 将未闭合的标记写回
                    val openMarker = when (state) {
                        State.BOLD -> "**"
                        State.ITALIC -> "*" // 简化处理，用一个*代替_
                        State.CODE -> "`"
                        else -> ""
                    }
                    out.insert(currentSpanStart, openMarker)
                    // 重置状态
                    currentSpanStart = -1
                    state = State.TEXT
                }
                out.append(ch)
                i += 1
                continue
            }

            // 其他字符落地
            out.append(ch)
            i += 1
            forceCloseIfTooLong(out.length)
        }

        // 未闭合的样式按普通文本处理（不追加样式 span）
        currentSpanStart = -1
        state = State.TEXT

        // 构建 AnnotatedString
        val plain = out.toString()

        // 构建 AnnotatedString.Builder 并附加初始内联样式
        val builder = AnnotatedString.Builder()
        builder.append(plain)
        for (r in spans) {
            val s = r.start.coerceIn(0, builder.length)
            val e = r.end.coerceIn(s, builder.length)
            if (e > s) builder.addStyle(r.style, s, e)
        }

        // 扩展：行级标题/列表/链接（预算受限，匹配失败则按文本退化）
        if (opts.extended && maxSegments > 0) {
            var used = spans.size
            val budget = maxSegments

            fun canUse(extra: Int = 1): Boolean = used + extra <= budget

            // 标题：^#{1,3}\s+(.+)$
            if (canUse()) {
                val headingRe = Regex("(?m)^(#{1,3})\\s+(.+)$")
                for (m in headingRe.findAll(plain)) {
                    val g = m.groups[2] ?: continue
                    if (!canUse()) break
                    val s = g.range.first.coerceIn(0, builder.length)
                    val e = (g.range.last + 1).coerceIn(s, builder.length)
                    if (e > s) {
                        builder.addStyle(
                            SpanStyle(fontWeight = FontWeight.SemiBold, color = baseStyleColor),
                            s, e
                        )
                        used++
                        if (!canUse()) break
                    }
                }
            }

            // 无序列表：^(\s*[-*+])\s+(.+)$
            if (canUse()) {
                val ulRe = Regex("(?m)^[ \\t]*([\\-\\*\\+])\\s+(.+)$")
                for (m in ulRe.findAll(plain)) {
                    val g = m.groups[2] ?: continue
                    if (!canUse()) break
                    val s = g.range.first.coerceIn(0, builder.length)
                    val e = (g.range.last + 1).coerceIn(s, builder.length)
                    if (e > s) {
                        builder.addStyle(
                            SpanStyle(color = baseStyleColor),
                            s, e
                        )
                        used++
                        if (!canUse()) break
                    }
                }
            }

            // 有序列表：^(\s*[0-9]{1,3}\.)\s+(.+)$
            if (canUse()) {
                val olRe = Regex("(?m)^[ \\t]*([0-9]{1,3}\\.)\\s+(.+)$")
                for (m in olRe.findAll(plain)) {
                    val g = m.groups[2] ?: continue
                    if (!canUse()) break
                    val s = g.range.first.coerceIn(0, builder.length)
                    val e = (g.range.last + 1).coerceIn(s, builder.length)
                    if (e > s) {
                        builder.addStyle(
                            SpanStyle(color = baseStyleColor),
                            s, e
                        )
                        used++
                        if (!canUse()) break
                    }
                }
            }

            // 链接：[text](url) —— 仅高亮 text 并添加 URL 注解（不改动原文）
            if (canUse()) {
                val linkRe = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")
                for (m in linkRe.findAll(plain)) {
                    val tg = m.groups[1] ?: continue
                    val ug = m.groups[2] ?: continue
                    if (!canUse(2)) break
                    val ts = tg.range.first.coerceIn(0, builder.length)
                    val te = (tg.range.last + 1).coerceIn(ts, builder.length)
                    val url = plain.substring(ug.range)
                    if (te > ts) {
                        builder.addStyle(
                            SpanStyle(
                                color = Color(0xFF2986CC),
                                textDecoration = TextDecoration.Underline
                            ),
                            ts, te
                        )
                        builder.addStringAnnotation(
                            tag = "URL",
                            annotation = url,
                            start = ts,
                            end = te
                        )
                        used += 2
                        if (!canUse()) break
                    }
                }
            }
        }

        return builder.toAnnotatedString()
    }
}