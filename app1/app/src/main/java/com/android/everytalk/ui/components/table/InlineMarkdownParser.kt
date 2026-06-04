package com.android.everytalk.ui.components.table

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit

private const val INLINE_EXTERNAL_LINK_SUFFIX = " ↗"

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
        codeBackground: Color = Color.Transparent,
        codeColor: Color = Color.Unspecified,
        codeFontSize: TextUnit = TextUnit.Unspecified,
    ): AnnotatedString {
        if (text.isEmpty()) return AnnotatedString("")

        return buildAnnotatedString {
            appendInlineMarkdown(
                text = text,
                codeBackground = codeBackground,
                codeColor = codeColor,
                codeFontSize = codeFontSize,
                linkColor = resolveLinkColor(baseColor),
            )
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
                text.contains('~') ||
                text.contains('[')
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

    private fun resolveLinkColor(baseColor: Color): Color {
        return baseColor
    }

    private fun AnnotatedString.Builder.appendInlineMarkdown(
        text: String,
        codeBackground: Color,
        codeColor: Color,
        codeFontSize: TextUnit,
        linkColor: Color,
    ) {
        var index = 0
        while (index < text.length) {
            if (text[index] == '\\' && index + 1 < text.length) {
                append(text[index + 1])
                index += 2
                continue
            }

            val parsed = parseInlineToken(
                text = text,
                start = index,
                codeBackground = codeBackground,
                codeColor = codeColor,
                codeFontSize = codeFontSize,
                linkColor = linkColor,
            )
            if (parsed != null) {
                index = parsed
                continue
            }

            append(text[index])
            index++
        }
    }

    private fun AnnotatedString.Builder.parseInlineToken(
        text: String,
        start: Int,
        codeBackground: Color,
        codeColor: Color,
        codeFontSize: TextUnit,
        linkColor: Color,
    ): Int? {
        return parseCode(text, start, codeColor, codeFontSize)
            ?: parseStyled(text, start, "***", SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic), codeBackground, codeColor, codeFontSize, linkColor)
            ?: parseStyled(text, start, "___", SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic), codeBackground, codeColor, codeFontSize, linkColor)
            ?: parseStyled(text, start, "**", SpanStyle(fontWeight = FontWeight.Bold), codeBackground, codeColor, codeFontSize, linkColor)
            ?: parseStyled(text, start, "__", SpanStyle(fontWeight = FontWeight.Bold), codeBackground, codeColor, codeFontSize, linkColor)
            ?: parseStyled(text, start, "~~", SpanStyle(textDecoration = TextDecoration.LineThrough), codeBackground, codeColor, codeFontSize, linkColor)
            ?: parseLink(text, start, codeBackground, codeColor, codeFontSize, linkColor)
            ?: parseItalic(text, start, codeBackground, codeColor, codeFontSize, linkColor)
    }

    private fun AnnotatedString.Builder.parseCode(
        text: String,
        start: Int,
        codeColor: Color,
        codeFontSize: TextUnit,
    ): Int? {
        if (text[start] != '`') return null
        val end = findClosingMarker(text, start + 1, "`") ?: return null
        pushStyle(
            SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = codeColor,
                fontSize = codeFontSize,
            )
        )
        append(text.substring(start + 1, end))
        pop()
        return end + 1
    }

    private fun AnnotatedString.Builder.parseStyled(
        text: String,
        start: Int,
        marker: String,
        style: SpanStyle,
        codeBackground: Color,
        codeColor: Color,
        codeFontSize: TextUnit,
        linkColor: Color,
    ): Int? {
        if (!text.startsWith(marker, start)) return null
        val end = findClosingMarker(text, start + marker.length, marker) ?: return null
        pushStyle(style)
        appendInlineMarkdown(
            text = text.substring(start + marker.length, end),
            codeBackground = codeBackground,
            codeColor = codeColor,
            codeFontSize = codeFontSize,
            linkColor = linkColor,
        )
        pop()
        return end + marker.length
    }

    private fun AnnotatedString.Builder.parseItalic(
        text: String,
        start: Int,
        codeBackground: Color,
        codeColor: Color,
        codeFontSize: TextUnit,
        linkColor: Color,
    ): Int? {
        val marker = when (text[start]) {
            '*' -> "*"
            '_' -> "_"
            else -> return null
        }
        if (marker == "_" && !isUnderscoreOpeningDelimiter(text, start)) return null
        if (text.startsWith(marker + marker, start)) return null

        val end = findClosingMarker(text, start + 1, marker) ?: return null
        if (marker == "_" && !isUnderscoreClosingDelimiter(text, end)) return null

        pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
        appendInlineMarkdown(
            text = text.substring(start + 1, end),
            codeBackground = codeBackground,
            codeColor = codeColor,
            codeFontSize = codeFontSize,
            linkColor = linkColor,
        )
        pop()
        return end + 1
    }

    private fun AnnotatedString.Builder.parseLink(
        text: String,
        start: Int,
        codeBackground: Color,
        codeColor: Color,
        codeFontSize: TextUnit,
        linkColor: Color,
    ): Int? {
        if (text[start] != '[') return null
        val labelEnd = findClosingMarker(text, start + 1, "]") ?: return null
        if (labelEnd + 1 >= text.length || text[labelEnd + 1] != '(') return null
        val urlEnd = findClosingMarker(text, labelEnd + 2, ")") ?: return null
        val url = text.substring(labelEnd + 2, urlEnd).trim()
        if (url.isEmpty() || url.any { it.isWhitespace() }) return null

        pushStringAnnotation(tag = "URL", annotation = url)
        pushStyle(
            SpanStyle(
                color = linkColor,
                textDecoration = TextDecoration.Underline,
            )
        )
        appendInlineMarkdown(
            text = text.substring(start + 1, labelEnd),
            codeBackground = codeBackground,
            codeColor = codeColor,
            codeFontSize = codeFontSize,
            linkColor = linkColor,
        )
        append(INLINE_EXTERNAL_LINK_SUFFIX)
        pop()
        pop()
        return urlEnd + 1
    }

    private fun findClosingMarker(text: String, start: Int, marker: String): Int? {
        var index = start
        while (index <= text.length - marker.length) {
            val found = text.indexOf(marker, index)
            if (found < 0) return null
            if (!isEscaped(text, found)) return found
            index = found + marker.length
        }
        return null
    }

    private fun isEscaped(text: String, index: Int): Boolean {
        var slashCount = 0
        var cursor = index - 1
        while (cursor >= 0 && text[cursor] == '\\') {
            slashCount++
            cursor--
        }
        return slashCount % 2 == 1
    }

    private fun isUnderscoreOpeningDelimiter(text: String, index: Int): Boolean {
        val before = text.getOrNull(index - 1)
        val after = text.getOrNull(index + 1)
        return before?.isLetterOrDigit() != true &&
            after != null &&
            !after.isWhitespace() &&
            after != '_'
    }

    private fun isUnderscoreClosingDelimiter(text: String, index: Int): Boolean {
        val before = text.getOrNull(index - 1)
        val after = text.getOrNull(index + 1)
        return before != null &&
            !before.isWhitespace() &&
            before != '_' &&
            after?.isLetterOrDigit() != true
    }
}
