package com.android.everytalk.ui.components.table

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * 轻量级内联 Markdown 解析器
 *
 * 参考 Open WebUI 的 MarkdownInlineTokens 设计：
 * - 只处理内联格式（加粗、斜体、代码、删除线、链接）
 * - 不处理块级元素（代码块、表格、列表等）
 * - 使用纯 Compose AnnotatedString，无 AndroidView 开销
 * - 性能优先：单次正则扫描 + 缓存
 */
object InlineMarkdownParser {

    // 内联 Markdown 模式（按优先级排序）
    private val INLINE_PATTERNS = listOf(
        // 加粗+斜体 ***text*** 或 ___text___
        InlinePattern(
            regex = Regex("""\*\*\*(.+?)\*\*\*|___(.+?)___"""),
            style = SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic),
            groupIndex = listOf(1, 2)
        ),
        // 加粗 **text** 或 __text__
        InlinePattern(
            regex = Regex("""\*\*(.+?)\*\*|__(.+?)__"""),
            style = SpanStyle(fontWeight = FontWeight.Bold),
            groupIndex = listOf(1, 2)
        ),
        // 斜体 *text* 或 _text_（注意：不能匹配单词内的下划线）
        InlinePattern(
            regex = Regex("""\*([^*]+)\*|(?<!\w)_([^_]+)_(?!\w)"""),
            style = SpanStyle(fontStyle = FontStyle.Italic),
            groupIndex = listOf(1, 2)
        ),
        // 删除线 ~~text~~
        InlinePattern(
            regex = Regex("""~~(.+?)~~"""),
            style = SpanStyle(textDecoration = TextDecoration.LineThrough),
            groupIndex = listOf(1)
        ),
        // 行内代码 `code`
        InlinePattern(
            regex = Regex("""`([^`]+)`"""),
            style = SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = Color(0x20808080)
            ),
            groupIndex = listOf(1)
        )
    )

    private data class InlinePattern(
        val regex: Regex,
        val style: SpanStyle,
        val groupIndex: List<Int>
    )

    private data class MatchInfo(
        val start: Int,
        val end: Int,
        val content: String,
        val style: SpanStyle
    )

    /**
     * 解析内联 Markdown 并返回 AnnotatedString
     *
     * @param text 原始文本
     * @param baseColor 基础文本颜色
     * @param codeBackground 代码背景色
     * @return 带格式的 AnnotatedString
     */
    fun parse(
        text: String,
        baseColor: Color = Color.Unspecified,
        codeBackground: Color = Color(0x20808080)
    ): AnnotatedString {
        if (text.isEmpty()) return AnnotatedString("")

        // 收集所有匹配项
        val allMatches = mutableListOf<MatchInfo>()

        for (pattern in INLINE_PATTERNS) {
            val effectiveStyle = if (pattern.style.fontFamily == FontFamily.Monospace) {
                pattern.style.copy(background = codeBackground)
            } else {
                pattern.style
            }

            pattern.regex.findAll(text).forEach { matchResult ->
                // 找到第一个非空的捕获组
                val content = pattern.groupIndex
                    .mapNotNull { idx ->
                        val group = matchResult.groups
                        if (idx < group.size) group[idx]?.value else null
                    }
                    .firstOrNull() ?: return@forEach

                allMatches.add(
                    MatchInfo(
                        start = matchResult.range.first,
                        end = matchResult.range.last + 1,
                        content = content,
                        style = effectiveStyle
                    )
                )
            }
        }

        // 如果没有任何匹配，直接返回原文本
        if (allMatches.isEmpty()) {
            return AnnotatedString(text)
        }

        // 按位置排序并过滤重叠的匹配（保留先匹配的）
        val sortedMatches = allMatches.sortedBy { it.start }
        val nonOverlappingMatches = mutableListOf<MatchInfo>()
        var lastEnd = 0

        for (match in sortedMatches) {
            if (match.start >= lastEnd) {
                nonOverlappingMatches.add(match)
                lastEnd = match.end
            }
        }

        // 构建 AnnotatedString
        return buildAnnotatedString {
            var currentIndex = 0

            for (match in nonOverlappingMatches) {
                // 添加匹配前的普通文本
                if (currentIndex < match.start) {
                    append(text.substring(currentIndex, match.start))
                }

                // 添加带样式的匹配内容
                pushStyle(match.style)
                append(match.content)
                pop()

                currentIndex = match.end
            }

            // 添加剩余的普通文本
            if (currentIndex < text.length) {
                append(text.substring(currentIndex))
            }
        }
    }

    /**
     * 检查文本是否包含内联 Markdown 语法
     * 用于快速判断是否需要解析
     */
    fun containsInlineMarkdown(text: String): Boolean {
        return text.contains('*') ||
                text.contains('_') ||
                text.contains('`') ||
                text.contains('~')
    }

    /**
     * 检查文本是否包含数学公式
     * 支持 $...$ 和 $$...$$ 格式
     */
    fun containsMath(text: String): Boolean {
        if (!text.contains('$')) return false
        // 检测未转义的 $ 符号
        // 简单检测：至少有两个 $（可能是一对 $...$ 或 $$...$$）
        var dollarCount = 0
        var i = 0
        while (i < text.length) {
            if (text[i] == '$' && (i == 0 || text[i - 1] != '\\')) {
                dollarCount++
            }
            i++
        }
        return dollarCount >= 2
    }
}
