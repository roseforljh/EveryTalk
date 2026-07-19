package com.android.everytalk.ui.components.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.html.entities.Entities

private val htmlEntityRegex = Regex(
    "&(?:[A-Za-z][A-Za-z0-9]{1,31}|#[0-9]{1,8}|#[xX][0-9A-Fa-f]{1,8});"
)
private val openingSpanRegex = Regex(
    pattern = """^<span(?:\s+style\s*=\s*(?:\"([^\"]*)\"|'([^']*)'))?\s*>$""",
    option = RegexOption.IGNORE_CASE,
)
private val closingSpanRegex = Regex("""^</span\s*>$""", RegexOption.IGNORE_CASE)
private val anySpanTagRegex = Regex("""^</?span(?:\s|/?>)""", RegexOption.IGNORE_CASE)

private sealed interface SafeSpanTag {
    data class Open(val style: SpanStyle) : SafeSpanTag
    data object Close : SafeSpanTag
    data object Invalid : SafeSpanTag
}

/**
 * 实体必须在 Markdown AST 生成后解码，避免 &ast; 等实体重新触发强调语法。
 */
internal fun decodeMarkdownHtmlEntities(text: String): String {
    if ('&' !in text) return text

    return htmlEntityRegex.replace(text) { match ->
        val raw = match.value
        val codePoint = when {
            raw.startsWith("&#x", ignoreCase = true) ->
                raw.substring(3, raw.length - 1).toIntOrNull(16)

            raw.startsWith("&#") ->
                raw.substring(2, raw.length - 1).toIntOrNull()

            else -> Entities.map[raw]
        }
        codePoint?.toUnicodeScalarOrReplacement() ?: raw
    }
}

private fun Int.toUnicodeScalarOrReplacement(): String {
    val isUnicodeScalar = Character.isValidCodePoint(this) && this !in 0xD800..0xDFFF
    return if (isUnicodeScalar) {
        String(Character.toChars(this))
    } else {
        "\uFFFD"
    }
}

/**
 * 仅补齐 MikePenz 当前遗漏的实体与安全 span；其余 Markdown 节点继续走库原生逻辑。
 */
internal fun AnnotatedString.Builder.annotateMarkdownHtmlCompatibility(
    content: String,
    child: ASTNode,
): Boolean {
    if (
        child.type == MarkdownTokenTypes.TEXT &&
        !child.hasAncestorOfType(MarkdownElementTypes.CODE_SPAN)
    ) {
        append(decodeMarkdownHtmlEntities(child.getUnescapedTextInNode(content)))
        return true
    }

    if (child.type != MarkdownTokenTypes.HTML_TAG) return false
    return when (val action = child.resolveSafeSpanAction(content)) {
        is SafeSpanTag.Open -> {
            pushStyle(action.style)
            true
        }

        SafeSpanTag.Close -> {
            pop()
            true
        }

        SafeSpanTag.Invalid,
        null,
        -> false
    }
}

private fun ASTNode.hasAncestorOfType(type: org.intellij.markdown.IElementType): Boolean {
    var current = parent
    while (current != null) {
        if (current.type == type) return true
        current = current.parent
    }
    return false
}

private fun ASTNode.resolveSafeSpanAction(content: String): SafeSpanTag? {
    val siblings = parent?.children ?: return null
    val index = siblings.indexOf(this).takeIf { it >= 0 } ?: return null
    return when (val tag = parseSafeSpanTag(getTextInNode(content).toString())) {
        is SafeSpanTag.Open -> tag.takeIf {
            siblings.findMatchingSafeSpanClose(index, content) != null
        }

        SafeSpanTag.Close -> tag.takeIf {
            siblings.findMatchingSafeSpanOpen(index, content) != null
        }

        SafeSpanTag.Invalid -> null
        null -> null
    }
}

private fun List<ASTNode>.findMatchingSafeSpanClose(
    openIndex: Int,
    content: String,
): Int? {
    var depth = 0
    for (index in openIndex + 1 until size) {
        when (parseSafeSpanTag(this[index].getTextInNode(content).toString())) {
            is SafeSpanTag.Open -> depth++
            SafeSpanTag.Close -> {
                if (depth == 0) return index
                depth--
            }

            SafeSpanTag.Invalid -> return null
            null -> Unit
        }
    }
    return null
}

private fun List<ASTNode>.findMatchingSafeSpanOpen(
    closeIndex: Int,
    content: String,
): Int? {
    var depth = 0
    for (index in closeIndex - 1 downTo 0) {
        when (parseSafeSpanTag(this[index].getTextInNode(content).toString())) {
            SafeSpanTag.Close -> depth++
            is SafeSpanTag.Open -> {
                if (depth == 0) return index
                depth--
            }

            SafeSpanTag.Invalid -> return null
            null -> Unit
        }
    }
    return null
}

private fun parseSafeSpanTag(raw: String): SafeSpanTag? {
    val tag = raw.trim()
    if (closingSpanRegex.matches(tag)) return SafeSpanTag.Close

    val open = openingSpanRegex.matchEntire(tag)
    if (open != null) {
        val css = open.groupValues[1].ifEmpty { open.groupValues[2] }
        val style = parseSafeSpanStyle(css) ?: return SafeSpanTag.Invalid
        return SafeSpanTag.Open(style)
    }

    return if (anySpanTagRegex.containsMatchIn(tag)) SafeSpanTag.Invalid else null
}

internal fun parseSafeSpanStyle(css: String): SpanStyle? {
    if (css.isBlank()) return SpanStyle()

    var color = Color.Unspecified
    var background = Color.Unspecified
    var fontWeight: FontWeight? = null
    var fontStyle: FontStyle? = null
    var textDecoration: TextDecoration? = null

    for (declaration in css.split(';')) {
        if (declaration.isBlank()) continue
        val separator = declaration.indexOf(':')
        if (separator <= 0 || separator == declaration.lastIndex) return null
        val property = declaration.substring(0, separator).trim().lowercase()
        val value = declaration.substring(separator + 1).trim().lowercase()

        when (property) {
            "color" -> color = parseSafeCssColor(value) ?: return null
            "background-color" -> background = parseSafeCssColor(value) ?: return null
            "font-weight" -> fontWeight = when (value) {
                "normal", "400" -> FontWeight.Normal
                "bold", "600", "700", "800", "900" -> FontWeight.Bold
                else -> return null
            }

            "font-style" -> fontStyle = when (value) {
                "normal" -> FontStyle.Normal
                "italic" -> FontStyle.Italic
                else -> return null
            }

            "text-decoration" -> textDecoration = when (value) {
                "none" -> TextDecoration.None
                "underline" -> TextDecoration.Underline
                "line-through" -> TextDecoration.LineThrough
                "underline line-through", "line-through underline" -> TextDecoration.combine(
                    listOf(TextDecoration.Underline, TextDecoration.LineThrough)
                )

                else -> return null
            }

            else -> return null
        }
    }

    return SpanStyle(
        color = color,
        background = background,
        fontWeight = fontWeight,
        fontStyle = fontStyle,
        textDecoration = textDecoration,
    )
}

private fun parseSafeCssColor(value: String): Color? {
    val named = when (value) {
        "black" -> Color.Black
        "white" -> Color.White
        "red" -> Color.Red
        "green" -> Color.Green
        "blue" -> Color.Blue
        "yellow" -> Color.Yellow
        "gray", "grey" -> Color.Gray
        "cyan" -> Color.Cyan
        "magenta" -> Color.Magenta
        "transparent" -> Color.Transparent
        else -> null
    }
    if (named != null) return named

    if (!value.startsWith('#')) return null
    val hex = value.substring(1)
    val rgb = when (hex.length) {
        3 -> hex.map { "$it$it" }.joinToString("")
        6 -> hex
        else -> return null
    }
    val packed = rgb.toLongOrNull(16) ?: return null
    return Color(
        red = ((packed shr 16) and 0xFF).toInt(),
        green = ((packed shr 8) and 0xFF).toInt(),
        blue = (packed and 0xFF).toInt(),
    )
}
