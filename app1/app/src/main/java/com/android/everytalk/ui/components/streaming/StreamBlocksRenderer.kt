package com.android.everytalk.ui.components.streaming

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.components.markdown.MikePenzMarkdownRenderer
import com.android.everytalk.ui.components.table.InlineMarkdownParser

internal sealed interface InlineRenderPart {
    data class Text(val text: String) : InlineRenderPart
    data class Math(val block: StreamBlock.MathInline) : InlineRenderPart
    data class Image(val alt: String, val url: String) : InlineRenderPart
}

internal data class InlinePartsTextModel(
    val annotatedText: AnnotatedString,
    val mathPlaceholders: List<InlineMathPlaceholder>,
    val imagePlaceholders: List<InlineImagePlaceholder>,
)

internal data class InlineMathPlaceholder(
    val id: String,
    val latex: String,
    val width: TextUnit,
    val height: TextUnit,
)

internal data class InlineImagePlaceholder(
    val id: String,
    val alt: String,
    val url: String,
    val width: TextUnit,
    val height: TextUnit,
)

internal enum class StreamMathRenderPath {
    RawText,
    KaTeX,
}

internal fun resolveStreamMathRenderPath(block: StreamBlock.MathBlock): StreamMathRenderPath {
    return when (block.state) {
        MathBlockState.RENDERED -> StreamMathRenderPath.KaTeX
        MathBlockState.RAW,
        MathBlockState.PARSING,
        MathBlockState.FAILED -> StreamMathRenderPath.RawText
    }
}

@Composable
fun UnifiedMarkdownRenderer(
    markdown: String,
    contentKey: String,
    modifier: Modifier = Modifier,
    sender: Sender = Sender.AI,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    isStreaming: Boolean = false,
    onImageClick: ((String) -> Unit)? = null,
    onCodePreviewRequested: ((String, String) -> Unit)? = null,
    onCodeCopied: (() -> Unit)? = null,
) {
    MikePenzMarkdownRenderer(
        markdown = markdown,
        contentKey = contentKey,
        modifier = modifier,
        sender = sender,
        style = style,
        color = color,
        isStreaming = isStreaming,
        onImageClick = onImageClick,
        onCodePreviewRequested = onCodePreviewRequested,
        onCodeCopied = onCodeCopied,
    )
}

internal fun buildInlinePartsTextModel(
    parts: List<InlineRenderPart>,
    baseColor: Color,
    codeBackground: Color,
    codeColor: Color,
    codeFontSize: TextUnit,
): InlinePartsTextModel {
    val builder = AnnotatedString.Builder()
    val mathPlaceholders = mutableListOf<InlineMathPlaceholder>()
    val imagePlaceholders = mutableListOf<InlineImagePlaceholder>()
    var mathIndex = 0
    var imageIndex = 0

    parts.forEach { part ->
        when (part) {
            is InlineRenderPart.Text -> {
                if (part.text.isNotEmpty()) {
                    builder.append(
                        InlineMarkdownParser.parse(
                            text = part.text,
                            baseColor = baseColor,
                            codeBackground = codeBackground,
                            codeColor = codeColor,
                            codeFontSize = codeFontSize,
                        )
                    )
                }
            }

            is InlineRenderPart.Math -> {
                if (part.block.state == MathBlockState.RAW) {
                    builder.append(part.block.text)
                } else {
                    val id = "math-${mathIndex++}"
                    val latex = stripInlineMathDelimiters(part.block.text)
                    val placeholderSize = inlineMathPlaceholderSize(latex, codeFontSize)
                    builder.appendInlineContent(id = id, alternateText = "�")
                    mathPlaceholders += InlineMathPlaceholder(
                        id = id,
                        latex = latex,
                        width = placeholderSize.width,
                        height = placeholderSize.height,
                    )
                }
            }

            is InlineRenderPart.Image -> {
                val id = "image-${imageIndex++}"
                val placeholderSize = inlineImagePlaceholderSize(codeFontSize)
                builder.appendInlineContent(id = id, alternateText = "�")
                imagePlaceholders += InlineImagePlaceholder(
                    id = id,
                    alt = part.alt,
                    url = part.url,
                    width = placeholderSize.width,
                    height = placeholderSize.height,
                )
            }
        }
    }

    return InlinePartsTextModel(
        annotatedText = builder.toAnnotatedString(),
        mathPlaceholders = mathPlaceholders,
        imagePlaceholders = imagePlaceholders,
    )
}

private data class InlinePlaceholderSize(
    val width: TextUnit,
    val height: TextUnit,
)

private fun inlineMathPlaceholderSize(latex: String, fontSize: TextUnit): InlinePlaceholderSize {
    val baseSize = if (fontSize == TextUnit.Unspecified) 16.sp else fontSize
    val widthFactor = (latex.length.coerceIn(2, 32) * 0.54f).coerceAtLeast(2.2f)
    return InlinePlaceholderSize(
        width = baseSize * widthFactor,
        height = baseSize * 1.55f,
    )
}

private fun inlineImagePlaceholderSize(fontSize: TextUnit): InlinePlaceholderSize {
    val baseSize = if (fontSize == TextUnit.Unspecified) 16.sp else fontSize
    return InlinePlaceholderSize(
        width = baseSize * 2.25f,
        height = baseSize * 1.55f,
    )
}

private fun stripInlineMathDelimiters(text: String): String {
    return when {
        text.startsWith("\\(") && text.endsWith("\\)") && text.length >= 4 ->
            text.substring(2, text.length - 2)
        text.startsWith("$") && text.endsWith("$") && !text.startsWith("$$") && text.length >= 2 ->
            text.substring(1, text.length - 1)
        else -> text
    }
}

internal data class FencedCodeBlockContent(
    val language: String?,
    val code: String,
)

private val fencedCodeOpeningLinePattern = Regex("""^\s*([`~]{3,})([^\n`~]*)$""")

internal fun extractFencedCodeBlockContent(text: String): FencedCodeBlockContent {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    val lines = normalized.split('\n')
    val opening = lines.firstOrNull()?.let { fencedCodeOpeningLinePattern.matchEntire(it) }
    if (opening != null) {
        val marker = opening.groupValues[1]
        val language = opening.groupValues[2].trim().ifBlank { null }
        val bodyLines = lines.drop(1).toMutableList()
        if (bodyLines.isNotEmpty() && isFenceClosingLineForMarker(bodyLines.last(), marker)) {
            bodyLines.removeAt(bodyLines.lastIndex)
        }
        return FencedCodeBlockContent(
            language = language,
            code = bodyLines.joinToString("\n"),
        )
    }
    return FencedCodeBlockContent(language = null, code = normalized)
}

private fun isFenceClosingLineForMarker(line: String, marker: String): Boolean {
    val trimmed = line.trimStart()
    val markerChar = marker.first()
    var markerLength = 0
    while (markerLength < trimmed.length && trimmed[markerLength] == markerChar) {
        markerLength++
    }
    return markerLength >= marker.length && trimmed.substring(markerLength).isBlank()
}
