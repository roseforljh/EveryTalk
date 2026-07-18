package com.android.everytalk.ui.components.table

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import com.android.everytalk.ui.components.streaming.StreamBlock
import com.android.everytalk.ui.components.streaming.StreamBlockParser

private val inlineHighlightBackgroundColor = Color(0x33FFD54F)
private val inlineAutolinkPattern = Regex("""<https?://[^>\s]+>""")
private val inlineEmailAutolinkPattern = Regex("""<[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}>""")
private val inlineFontOpenTagPattern = Regex("""<font\b([^>]*)>""", RegexOption.IGNORE_CASE)
private val inlineFontCloseTagPattern = Regex("""</font\s*>""", RegexOption.IGNORE_CASE)
private val inlineTtOpenTagPattern = Regex("""<tt\b[^>]*>""", RegexOption.IGNORE_CASE)
private val inlineTtCloseTagPattern = Regex("""</tt\s*>""", RegexOption.IGNORE_CASE)
private val inlineBigOpenTagPattern = Regex("""<big\b[^>]*>""", RegexOption.IGNORE_CASE)
private val inlineBigCloseTagPattern = Regex("""</big\s*>""", RegexOption.IGNORE_CASE)
private val inlineSmallOpenTagPattern = Regex("""<small\b[^>]*>""", RegexOption.IGNORE_CASE)
private val inlineSmallCloseTagPattern = Regex("""</small\s*>""", RegexOption.IGNORE_CASE)
private val inlineSpanOpenTagPattern = Regex("""<span\b([^>]*)>""", RegexOption.IGNORE_CASE)
private val inlineSpanCloseTagPattern = Regex("""</span\s*>""", RegexOption.IGNORE_CASE)
private val inlineAnnotationOpenTagPattern = Regex("""<annotation\b([^>]*)>""", RegexOption.IGNORE_CASE)
private val inlineAnnotationCloseTagPattern = Regex("""</annotation\s*>""", RegexOption.IGNORE_CASE)
private val inlineHtmlAttributePattern = Regex("""([A-Za-z_:][A-Za-z0-9_:.-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""")
private val inlineBareUrlPattern = Regex("""https?://[^\s<>()\[\]{}]+(?:\([^\s<>()\[\]{}]*\)[^\s<>()\[\]{}]*)*""")
private val inlineHtmlEntityPattern = Regex("""&(?:amp|lt|gt|quot|apos|nbsp|ndash|mdash|hellip|copy|reg|trade|bull|middot|ldquo|rdquo|lsquo|rsquo|minus|times|divide|plusmn|deg|le|ge|ne|rarr|larr|Alpha|Beta|Gamma|Delta|Epsilon|Zeta|Eta|Theta|Iota|Kappa|Lambda|Mu|Nu|Xi|Omicron|Pi|Rho|Sigma|Tau|Upsilon|Phi|Chi|Psi|Omega|alpha|beta|gamma|delta|epsilon|zeta|eta|theta|iota|kappa|lambda|mu|nu|xi|omicron|pi|rho|sigma|tau|upsilon|phi|chi|psi|omega|#\d+|#x[0-9A-Fa-f]+);""")

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
                text.contains('+') ||
                text.contains('=') ||
                text.contains('^') ||
                text.contains(',') ||
                text.contains('[') ||
                text.contains("<font", ignoreCase = true) ||
                text.contains("<tt", ignoreCase = true) ||
                text.contains("<big", ignoreCase = true) ||
                text.contains("<small", ignoreCase = true) ||
                text.contains("<span", ignoreCase = true) ||
                text.contains("<annotation", ignoreCase = true) ||
                inlineBareUrlPattern.containsMatchIn(text) ||
                inlineHtmlEntityPattern.containsMatchIn(text) ||
                inlineAutolinkPattern.containsMatchIn(text) ||
                inlineEmailAutolinkPattern.containsMatchIn(text)
    }

    /**
     * 检查文本是否包含数学公式
     * 支持 $...$、$$...$$、\(...\) 和 \[...\] 格式
     */
    fun containsMath(text: String): Boolean {
        if (!text.contains('$') && !text.contains("\\(") && !text.contains("\\[")) return false
        val blocks = StreamBlockParser.parse(text, "inline-math-check").blocks
        return blocks.any { it is StreamBlock.MathInline || it is StreamBlock.MathBlock }
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
            if (
                text[index] == '\\' &&
                index + 1 < text.length &&
                isMarkdownEscapablePunctuation(text[index + 1])
            ) {
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
            ?: parseStyled(text, start, "++", SpanStyle(textDecoration = TextDecoration.Underline), codeBackground, codeColor, codeFontSize, linkColor)
            ?: parseStyled(text, start, "==", SpanStyle(background = inlineHighlightBackgroundColor), codeBackground, codeColor, codeFontSize, linkColor)
            ?: parseStyled(text, start, "^^", SpanStyle(baselineShift = BaselineShift.Superscript), codeBackground, codeColor, codeFontSize, linkColor)
            ?: parseStyled(text, start, ",,", SpanStyle(baselineShift = BaselineShift.Subscript), codeBackground, codeColor, codeFontSize, linkColor)
            ?: parseFontTag(text, start, codeBackground, codeColor, codeFontSize, linkColor)
            ?: parseStyledSpanTag(text, start, codeBackground, codeColor, codeFontSize, linkColor)
            ?: parseAnnotationTag(text, start, codeBackground, codeColor, codeFontSize, linkColor)
            ?: parseSimpleHtmlSpanTag(text, start, inlineTtOpenTagPattern, inlineTtCloseTagPattern, SpanStyle(fontFamily = FontFamily.Monospace), codeBackground, codeColor, codeFontSize, linkColor)
            ?: parseSimpleHtmlSpanTag(text, start, inlineBigOpenTagPattern, inlineBigCloseTagPattern, SpanStyle(fontSize = 1.25.em), codeBackground, codeColor, codeFontSize, linkColor)
            ?: parseSimpleHtmlSpanTag(text, start, inlineSmallOpenTagPattern, inlineSmallCloseTagPattern, SpanStyle(fontSize = 0.8.em), codeBackground, codeColor, codeFontSize, linkColor)
            ?: parseAutolink(text, start, linkColor)
            ?: parseHtmlEntity(text, start)
            ?: parseLink(text, start, codeBackground, codeColor, codeFontSize, linkColor)
            ?: parseBareUrl(text, start, linkColor)
            ?: parseItalic(text, start, codeBackground, codeColor, codeFontSize, linkColor)
    }

    private fun AnnotatedString.Builder.parseCode(
        text: String,
        start: Int,
        codeColor: Color,
        codeFontSize: TextUnit,
    ): Int? {
        if (text[start] != '`') return null
        val markerLength = countBacktickRun(text, start)
        val marker = "`".repeat(markerLength)
        val end = findClosingMarker(text, start + markerLength, marker) ?: return null
        pushStyle(
            SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = codeColor,
                fontSize = codeFontSize,
            )
        )
        append(normalizeCodeSpanLiteral(text.substring(start + markerLength, end)))
        pop()
        return end + markerLength
    }

    private fun normalizeCodeSpanLiteral(raw: String): String {
        val normalizedLineEndings = raw
            .replace("\r\n", " ")
            .replace('\n', ' ')
            .replace('\r', ' ')
        return if (
            normalizedLineEndings.length >= 2 &&
            normalizedLineEndings.first() == ' ' &&
            normalizedLineEndings.last() == ' ' &&
            normalizedLineEndings.any { it != ' ' }
        ) {
            normalizedLineEndings.substring(1, normalizedLineEndings.length - 1)
        } else {
            normalizedLineEndings
        }
    }

    private fun countBacktickRun(text: String, start: Int): Int {
        var end = start
        while (end < text.length && text[end] == '`') {
            end++
        }
        return end - start
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

    private fun AnnotatedString.Builder.parseFontTag(
        text: String,
        start: Int,
        codeBackground: Color,
        codeColor: Color,
        codeFontSize: TextUnit,
        linkColor: Color,
    ): Int? {
        val openTag = inlineFontOpenTagPattern.find(text, start) ?: return null
        if (openTag.range.first != start) return null
        val closeTag = inlineFontCloseTagPattern.find(text, openTag.range.last + 1) ?: return null
        val content = text.substring(openTag.range.last + 1, closeTag.range.first)
        val attributes = parseInlineHtmlAttributes(openTag.groupValues[1])
        val color = parseInlineHtmlColor(attributes["color"])
        val fontFamily = parseInlineHtmlFontFamily(attributes["face"])
        val hasFontStyle = color != null || fontFamily != null

        if (hasFontStyle) {
            pushStyle(
                SpanStyle(
                    color = color ?: Color.Unspecified,
                    fontFamily = fontFamily,
                )
            )
        }
        appendInlineMarkdown(
            text = content,
            codeBackground = codeBackground,
            codeColor = codeColor,
            codeFontSize = codeFontSize,
            linkColor = linkColor,
        )
        if (hasFontStyle) {
            pop()
        }
        return closeTag.range.last + 1
    }

    private fun parseInlineHtmlAttributes(rawAttributes: String): Map<String, String> {
        if (rawAttributes.isBlank()) return emptyMap()
        return inlineHtmlAttributePattern.findAll(rawAttributes).associate { match ->
            val name = match.groupValues[1].lowercase()
            val value = match.groupValues[2]
                .ifEmpty { match.groupValues[3] }
                .ifEmpty { match.groupValues[4] }
            name to value
        }
    }

    private fun parseInlineHtmlColor(rawColor: String?): Color? {
        val color = rawColor
            ?.trim()
            ?.trimEnd(';')
            ?.substringBefore(' ')
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return parseInlineHexColor(color) ?: parseInlineNamedColor(color) ?: parseInlineAndroidNumericColor(color)
    }

    private fun parseInlineHexColor(rawColor: String): Color? {
        if (!rawColor.startsWith("#")) return null
        val hex = rawColor.drop(1)
        return when (hex.length) {
            3 -> {
                val red = hexDigitPair(hex[0]) ?: return null
                val green = hexDigitPair(hex[1]) ?: return null
                val blue = hexDigitPair(hex[2]) ?: return null
                Color(red / 255f, green / 255f, blue / 255f, 1f)
            }
            6 -> {
                val rgb = hex.toIntOrNull(16) ?: return null
                val red = (rgb shr 16) and 0xFF
                val green = (rgb shr 8) and 0xFF
                val blue = rgb and 0xFF
                Color(red / 255f, green / 255f, blue / 255f, 1f)
            }
            8 -> {
                val argb = hex.toLongOrNull(16) ?: return null
                val alpha = ((argb shr 24) and 0xFF).toInt()
                val red = ((argb shr 16) and 0xFF).toInt()
                val green = ((argb shr 8) and 0xFF).toInt()
                val blue = (argb and 0xFF).toInt()
                Color(red / 255f, green / 255f, blue / 255f, alpha / 255f)
            }
            else -> null
        }
    }

    private fun hexDigitPair(char: Char): Int? {
        val digit = char.digitToIntOrNull(16) ?: return null
        return digit * 16 + digit
    }

    private fun parseInlineNamedColor(rawColor: String): Color? {
        return when (rawColor.lowercase()) {
            "black" -> Color.Black
            "white" -> Color.White
            "red" -> Color.Red
            "green" -> Color(0xFF008000)
            "blue" -> Color.Blue
            "yellow" -> Color.Yellow
            "cyan", "aqua" -> Color.Cyan
            "magenta", "fuchsia" -> Color.Magenta
            "darkgray", "darkgrey" -> Color(0xFFA9A9A9)
            "gray", "grey" -> Color(0xFF808080)
            "lightgray", "lightgrey" -> Color(0xFFD3D3D3)
            "lime" -> Color(0xFF00FF00)
            "maroon" -> Color(0xFF800000)
            "navy" -> Color(0xFF000080)
            "olive" -> Color(0xFF808000)
            "purple" -> Color(0xFF800080)
            "silver" -> Color(0xFFC0C0C0)
            "teal" -> Color(0xFF008080)
            else -> null
        }
    }

    private fun parseInlineAndroidNumericColor(rawColor: String): Color? {
        val color = parseAndroidHtmlColorInt(rawColor)?.takeIf { it != -1 } ?: return null
        return Color(color or 0xFF000000.toInt())
    }

    private fun parseAndroidHtmlColorInt(rawColor: String): Int? {
        if (rawColor.isEmpty()) return null
        var sign = 1
        var index = 0
        var base = 10

        if (rawColor[index] == '-') {
            sign = -1
            index++
            if (index >= rawColor.length) return null
        }

        if (rawColor[index] == '0') {
            if (index == rawColor.lastIndex) return 0
            val next = rawColor[index + 1]
            if (next == 'x' || next == 'X') {
                index += 2
                base = 16
            } else {
                index++
                base = 8
            }
        } else if (rawColor[index] == '#') {
            index++
            base = 16
        }

        if (index >= rawColor.length) return null
        return rawColor.substring(index).toIntOrNull(base)?.let { it * sign }
    }

    private fun parseInlineHtmlFontFamily(rawFace: String?): FontFamily? {
        val face = rawFace
            ?.split(',')
            ?.firstOrNull()
            ?.trim()
            ?.trim('"', '\'')
            ?.lowercase()
            ?: return null
        return when (face) {
            "monospace" -> FontFamily.Monospace
            "serif" -> FontFamily.Serif
            "sans-serif", "sans" -> FontFamily.SansSerif
            "cursive" -> FontFamily.Cursive
            else -> null
        }
    }

    private fun AnnotatedString.Builder.parseSimpleHtmlSpanTag(
        text: String,
        start: Int,
        openPattern: Regex,
        closePattern: Regex,
        style: SpanStyle,
        codeBackground: Color,
        codeColor: Color,
        codeFontSize: TextUnit,
        linkColor: Color,
    ): Int? {
        val openTag = openPattern.find(text, start) ?: return null
        if (openTag.range.first != start) return null
        val closeTag = closePattern.find(text, openTag.range.last + 1) ?: return null
        pushStyle(style)
        appendInlineMarkdown(
            text = text.substring(openTag.range.last + 1, closeTag.range.first),
            codeBackground = codeBackground,
            codeColor = codeColor,
            codeFontSize = codeFontSize,
            linkColor = linkColor,
        )
        pop()
        return closeTag.range.last + 1
    }

    private fun AnnotatedString.Builder.parseStyledSpanTag(
        text: String,
        start: Int,
        codeBackground: Color,
        codeColor: Color,
        codeFontSize: TextUnit,
        linkColor: Color,
    ): Int? {
        val openTag = inlineSpanOpenTagPattern.find(text, start) ?: return null
        if (openTag.range.first != start) return null
        val closeTag = inlineSpanCloseTagPattern.find(text, openTag.range.last + 1) ?: return null
        val attributes = parseInlineHtmlAttributes(openTag.groupValues[1])
        val style = parseInlineCssStyle(attributes["style"]) ?: return null
        pushStyle(style)
        appendInlineMarkdown(
            text = text.substring(openTag.range.last + 1, closeTag.range.first),
            codeBackground = codeBackground,
            codeColor = codeColor,
            codeFontSize = codeFontSize,
            linkColor = linkColor,
        )
        pop()
        return closeTag.range.last + 1
    }

    private fun AnnotatedString.Builder.parseAnnotationTag(
        text: String,
        start: Int,
        codeBackground: Color,
        codeColor: Color,
        codeFontSize: TextUnit,
        linkColor: Color,
    ): Int? {
        val openTag = inlineAnnotationOpenTagPattern.find(text, start) ?: return null
        if (openTag.range.first != start) return null
        val closeTag = inlineAnnotationCloseTagPattern.find(text, openTag.range.last + 1) ?: return null
        val attributes = parseInlineHtmlAttributes(openTag.groupValues[1])
        attributes.forEach { (tag, annotation) ->
            pushStringAnnotation(tag = tag, annotation = annotation)
        }
        appendInlineMarkdown(
            text = text.substring(openTag.range.last + 1, closeTag.range.first),
            codeBackground = codeBackground,
            codeColor = codeColor,
            codeFontSize = codeFontSize,
            linkColor = linkColor,
        )
        repeat(attributes.size) {
            pop()
        }
        return closeTag.range.last + 1
    }

    private fun parseInlineCssStyle(rawStyle: String?): SpanStyle? {
        val declarations = parseInlineCssDeclarations(rawStyle ?: return null)
        val color = declarations.firstNotNullOfOrNull { (name, value) ->
            if (name == "color") parseInlineHtmlColor(value) else null
        }
        val background = declarations.firstNotNullOfOrNull { (name, value) ->
            if (name == "background" || name == "background-color") parseInlineHtmlColor(value) else null
        }
        val textDecoration = declarations.firstNotNullOfOrNull { (name, value) ->
            if (
                name == "text-decoration" &&
                isAndroidHtmlLineThroughTextDecoration(value)
            ) {
                TextDecoration.LineThrough
            } else {
                null
            }
        }
        if (color == null && background == null && textDecoration == null) return null
        return SpanStyle(
            color = color ?: Color.Unspecified,
            background = background ?: Color.Unspecified,
            textDecoration = textDecoration,
        )
    }

    private fun isAndroidHtmlLineThroughTextDecoration(rawValue: String): Boolean {
        return rawValue.trim().substringBefore(' ').equals("line-through", ignoreCase = true)
    }

    private fun parseInlineCssDeclarations(rawStyle: String): List<Pair<String, String>> {
        return rawStyle.split(';').mapNotNull { declaration ->
            val separator = declaration.indexOf(':')
            if (separator <= 0) {
                null
            } else {
                val name = declaration.substring(0, separator).trim().lowercase()
                val value = declaration.substring(separator + 1).trim()
                if (name.isEmpty() || value.isEmpty()) null else name to value
            }
        }
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
        val urlEnd = findMarkdownLinkDestinationEnd(text, labelEnd + 2) ?: return null
        val url = parseMarkdownLinkUrl(text.substring(labelEnd + 2, urlEnd)) ?: return null

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
        pop()
        pop()
        return urlEnd + 1
    }

    private fun findMarkdownLinkDestinationEnd(text: String, start: Int): Int? {
        var index = start
        while (index < text.length) {
            val found = text.indexOf(')', index)
            if (found < 0) return null
            if (!isEscaped(text, found) && parseMarkdownLinkUrl(text.substring(start, found)) != null) {
                return found
            }
            index = found + 1
        }
        return null
    }

    private fun parseMarkdownLinkUrl(rawDestination: String): String? {
        val destination = rawDestination.trim()
        if (destination.isEmpty()) return null
        if (!destination.any { it.isWhitespace() }) {
            val url = stripMarkdownDestinationAngleBrackets(destination)
            return if (hasBalancedMarkdownLinkUrlParentheses(url)) url else null
        }

        val firstWhitespace = destination.indexOfFirst { it.isWhitespace() }
        val url = stripMarkdownDestinationAngleBrackets(destination.substring(0, firstWhitespace))
        if (url.isEmpty() ||
            url.any { it.isWhitespace() } ||
            !hasBalancedMarkdownLinkUrlParentheses(url)
        ) {
            return null
        }

        val title = destination.substring(firstWhitespace).trimStart()
        val validTitle = title.length >= 2 && (
            (title.first() == '"' && title.last() == '"') ||
                (title.first() == '\'' && title.last() == '\'') ||
                (title.first() == '(' && title.last() == ')')
            )
        return if (validTitle) url else null
    }

    private fun hasBalancedMarkdownLinkUrlParentheses(url: String): Boolean {
        var depth = 0
        url.forEachIndexed { index, char ->
            if (isEscaped(url, index)) return@forEachIndexed
            when (char) {
                '(' -> depth++
                ')' -> {
                    if (depth == 0) return false
                    depth--
                }
            }
        }
        return depth == 0
    }

    private fun AnnotatedString.Builder.parseAutolink(
        text: String,
        start: Int,
        linkColor: Color,
    ): Int? {
        if (text[start] != '<') return null
        val end = text.indexOf('>', start + 1)
        if (end < 0) return null
        val raw = text.substring(start, end + 1)
        val isEmailAutolink = inlineEmailAutolinkPattern.matches(raw)
        if (!inlineAutolinkPattern.matches(raw) && !isEmailAutolink) return null

        val target = raw.substring(1, raw.length - 1)
        val annotation = if (isEmailAutolink) "mailto:$target" else target
        pushStringAnnotation(tag = "URL", annotation = annotation)
        pushStyle(
            SpanStyle(
                color = linkColor,
                textDecoration = TextDecoration.Underline,
            )
        )
        append(target)
        pop()
        pop()
        return end + 1
    }

    private fun AnnotatedString.Builder.parseBareUrl(
        text: String,
        start: Int,
        linkColor: Color,
    ): Int? {
        if (!text.startsWith("http://", start) && !text.startsWith("https://", start)) return null
        if (start > 0 && isBareUrlBlockedPrefix(text[start - 1])) return null
        val match = inlineBareUrlPattern.find(text, start) ?: return null
        if (match.range.first != start) return null

        val rawUrl = match.value
        val visibleUrl = trimBareUrlTrailingPunctuation(rawUrl)
        if (visibleUrl.isBlank()) return null

        pushStringAnnotation(tag = "URL", annotation = visibleUrl)
        pushStyle(
            SpanStyle(
                color = linkColor,
                textDecoration = TextDecoration.Underline,
            )
        )
        append(visibleUrl)
        pop()
        pop()

        return start + visibleUrl.length
    }

    private fun isBareUrlBlockedPrefix(char: Char): Boolean {
        return char == '<' || char == '(' || char == '['
    }

    private fun trimBareUrlTrailingPunctuation(url: String): String {
        return url.trimEnd(
            '.', ',', ';', ':', '!', '?',
            '。', '，', '；', '：', '！', '？'
        )
    }

    private fun AnnotatedString.Builder.parseHtmlEntity(
        text: String,
        start: Int,
    ): Int? {
        if (text[start] != '&') return null
        val end = text.indexOf(';', start + 1)
        if (end < 0) return null
        val raw = text.substring(start, end + 1)
        if (!inlineHtmlEntityPattern.matches(raw)) return null

        val decoded = decodeHtmlEntity(raw) ?: return null
        append(decoded)
        return end + 1
    }

    private fun decodeHtmlEntity(raw: String): String? {
        return when (raw) {
            "&amp;" -> "&"
            "&lt;" -> "<"
            "&gt;" -> ">"
            "&quot;" -> "\""
            "&apos;" -> "'"
            "&nbsp;" -> "\u00A0"
            "&ndash;" -> "\u2013"
            "&mdash;" -> "\u2014"
            "&hellip;" -> "\u2026"
            "&copy;" -> "\u00A9"
            "&reg;" -> "\u00AE"
            "&trade;" -> "\u2122"
            "&bull;" -> "\u2022"
            "&middot;" -> "\u00B7"
            "&ldquo;" -> "\u201C"
            "&rdquo;" -> "\u201D"
            "&lsquo;" -> "\u2018"
            "&rsquo;" -> "\u2019"
            "&minus;" -> "\u2212"
            "&times;" -> "\u00D7"
            "&divide;" -> "\u00F7"
            "&plusmn;" -> "\u00B1"
            "&deg;" -> "\u00B0"
            "&le;" -> "\u2264"
            "&ge;" -> "\u2265"
            "&ne;" -> "\u2260"
            "&rarr;" -> "\u2192"
            "&larr;" -> "\u2190"
            "&Alpha;" -> "\u0391"
            "&Beta;" -> "\u0392"
            "&Gamma;" -> "\u0393"
            "&Delta;" -> "\u0394"
            "&Epsilon;" -> "\u0395"
            "&Zeta;" -> "\u0396"
            "&Eta;" -> "\u0397"
            "&Theta;" -> "\u0398"
            "&Iota;" -> "\u0399"
            "&Kappa;" -> "\u039A"
            "&Lambda;" -> "\u039B"
            "&Mu;" -> "\u039C"
            "&Nu;" -> "\u039D"
            "&Xi;" -> "\u039E"
            "&Omicron;" -> "\u039F"
            "&Pi;" -> "\u03A0"
            "&Rho;" -> "\u03A1"
            "&Sigma;" -> "\u03A3"
            "&Tau;" -> "\u03A4"
            "&Upsilon;" -> "\u03A5"
            "&Phi;" -> "\u03A6"
            "&Chi;" -> "\u03A7"
            "&Psi;" -> "\u03A8"
            "&Omega;" -> "\u03A9"
            "&alpha;" -> "\u03B1"
            "&beta;" -> "\u03B2"
            "&gamma;" -> "\u03B3"
            "&delta;" -> "\u03B4"
            "&epsilon;" -> "\u03B5"
            "&zeta;" -> "\u03B6"
            "&eta;" -> "\u03B7"
            "&theta;" -> "\u03B8"
            "&iota;" -> "\u03B9"
            "&kappa;" -> "\u03BA"
            "&lambda;" -> "\u03BB"
            "&mu;" -> "\u03BC"
            "&nu;" -> "\u03BD"
            "&xi;" -> "\u03BE"
            "&omicron;" -> "\u03BF"
            "&pi;" -> "\u03C0"
            "&rho;" -> "\u03C1"
            "&sigma;" -> "\u03C3"
            "&tau;" -> "\u03C4"
            "&upsilon;" -> "\u03C5"
            "&phi;" -> "\u03C6"
            "&chi;" -> "\u03C7"
            "&psi;" -> "\u03C8"
            "&omega;" -> "\u03C9"
            else -> decodeNumericHtmlEntity(raw)
        }
    }

    private fun decodeNumericHtmlEntity(raw: String): String? {
        val codePoint = when {
            raw.startsWith("&#x", ignoreCase = true) ->
                raw.substring(3, raw.length - 1).toIntOrNull(16)
            raw.startsWith("&#") ->
                raw.substring(2, raw.length - 1).toIntOrNull(10)
            else -> null
        } ?: return null

        return runCatching {
            String(Character.toChars(codePoint))
        }.getOrNull()
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

    private fun isMarkdownEscapablePunctuation(char: Char): Boolean {
        val code = char.code
        return code in 0x21..0x2F ||
            code in 0x3A..0x40 ||
            code in 0x5B..0x60 ||
            code in 0x7B..0x7E
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
