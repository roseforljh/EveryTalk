package com.android.everytalk.ui.components.markdown

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.Density
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.components.content.CodeBlockCard
import com.android.everytalk.ui.components.math.MathBlock
import com.android.everytalk.ui.components.math.MathInline
import com.android.everytalk.ui.components.streaming.MathBlockState
import com.android.everytalk.ui.components.streaming.StreamBlock
import com.android.everytalk.ui.components.streaming.StreamBlockParser
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownInlineImage
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.PlaceholderConfig
import java.nio.charset.StandardCharsets
import java.util.Base64

private const val INLINE_MATH_SCHEME = "everytalk-math-inline:"
private const val MATH_FENCE_LANGUAGE = "everytalk-internal-math-v1"

@Composable
fun MikePenzMarkdownRenderer(
    markdown: String,
    contentKey: String,
    modifier: Modifier = Modifier,
    sender: Sender = Sender.AI,
    isStreaming: Boolean = false,
    onCodePreviewRequested: ((String, String) -> Unit)? = null,
    onCodeCopied: (() -> Unit)? = null,
) {
    val renderedMarkdown = remember(markdown, contentKey) {
        prepareMarkdownForMikePenz(markdown, contentKey)
    }
    val typography = markdownTypography(
        h1 = MaterialTheme.typography.headlineSmall,
        h2 = MaterialTheme.typography.titleLarge,
        h3 = MaterialTheme.typography.titleMedium,
        h4 = MaterialTheme.typography.titleSmall,
        h5 = MaterialTheme.typography.bodyLarge,
        h6 = MaterialTheme.typography.bodyMedium,
    )
    val components = markdownComponents(
        codeFence = { model ->
            MarkdownCodeFence(model.content, model.node, model.typography.code) { code, language, _ ->
                val latex = if (language == MATH_FENCE_LANGUAGE) decodeMathPayload(code.trim()) else null
                if (latex != null) {
                    MathBlock(
                        latex = latex,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    CodeBlockCard(
                        language = language,
                        code = code,
                        isStreaming = isStreaming,
                        onCopy = onCodeCopied,
                        onPreviewRequested = onCodePreviewRequested?.let { callback ->
                            { callback(language.orEmpty(), code) }
                        },
                    )
                }
            }
        },
        codeBlock = { model ->
            MarkdownCodeBlock(model.content, model.node, model.typography.code) { code, language, _ ->
                CodeBlockCard(
                    language = language,
                    code = code,
                    isStreaming = isStreaming,
                    onCopy = onCodeCopied,
                    onPreviewRequested = onCodePreviewRequested?.let { callback ->
                        { callback(language.orEmpty(), code) }
                    },
                )
            }
        },
        inlineImage = { model ->
            val latex = decodeInlineMathLink(model.content)
            if (latex != null) {
                MathInline(
                    latex = latex,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                MarkdownInlineImage(model.content, model.node)
            }
        },
    )
    val markdownContent: @Composable () -> Unit = {
        Markdown(
            content = renderedMarkdown,
            colors = markdownColor(),
            typography = typography,
            modifier = modifier.fillMaxWidth(),
            imageTransformer = EveryTalkMarkdownImageTransformer,
            components = components,
        )
    }

    if (sender == Sender.AI) {
        SelectionContainer { markdownContent() }
    } else {
        markdownContent()
    }
}

internal fun prepareMarkdownForMikePenz(markdown: String, contentKey: String): String {
    val normalizedFences = normalizeNestedMarkdownCodeFences(markdown)
    return transformMathForMikePenz(
        markdown = unwrapMarkdownDocumentFences(normalizedFences),
        contentKey = contentKey,
    )
}

private data class MarkdownFenceLine(
    val indent: String,
    val marker: String,
    val info: String,
)

private val markdownFenceLinePattern = Regex("""^(\s{0,3})(`{3,}|~{3,})([^\r\n]*)\r?$""")

/**
 * 修复 Markdown 示例代码块内嵌套同长度围栏时的边界泄漏。
 * 外层仍是标准 fenced code block，只把外层围栏扩展到比内部围栏更长。
 */
internal fun normalizeNestedMarkdownCodeFences(markdown: String): String {
    if (markdown.isEmpty()) return markdown
    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n').toMutableList()
    var index = 0

    while (index < lines.size) {
        val opening = parseMarkdownFenceLine(lines[index])
        if (opening == null || opening.info.lowercase() !in setOf("markdown", "md")) {
            index++
            continue
        }

        val markerChar = opening.marker.first()
        var nestedDepth = 0
        var maxNestedMarkerLength = 0
        var closingIndex = -1
        var scanIndex = index + 1

        while (scanIndex < lines.size) {
            val candidate = parseMarkdownFenceLine(lines[scanIndex])
            if (candidate == null || candidate.marker.first() != markerChar) {
                scanIndex++
                continue
            }

            if (candidate.info.isNotEmpty()) {
                nestedDepth++
                maxNestedMarkerLength = maxOf(maxNestedMarkerLength, candidate.marker.length)
            } else if (nestedDepth > 0) {
                nestedDepth--
            } else if (candidate.marker.length >= opening.marker.length) {
                closingIndex = scanIndex
                break
            }
            scanIndex++
        }

        if (closingIndex < 0) {
            index++
            continue
        }

        if (maxNestedMarkerLength >= opening.marker.length) {
            val outerMarker = markerChar.toString().repeat(maxNestedMarkerLength + 1)
            val closing = requireNotNull(parseMarkdownFenceLine(lines[closingIndex]))
            lines[index] = opening.indent + outerMarker + opening.info
            lines[closingIndex] = closing.indent + outerMarker
        }
        index = closingIndex + 1
    }

    return lines.joinToString("\n")
}

private fun parseMarkdownFenceLine(line: String): MarkdownFenceLine? {
    val match = markdownFenceLinePattern.matchEntire(line) ?: return null
    return MarkdownFenceLine(
        indent = match.groupValues[1],
        marker = match.groupValues[2],
        info = match.groupValues[3].trim(),
    )
}

/**
 * AI 常用 markdown fenced block 包裹完整文档。这里仅解包语言为 markdown 或 md 的外层，
 * 内部其他语言代码围栏继续交给 Markdown 引擎和 CodeBlockCard。
 */
internal fun unwrapMarkdownDocumentFences(markdown: String): String {
    if (markdown.isEmpty()) return markdown
    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val output = mutableListOf<String>()
    var index = 0

    while (index < lines.size) {
        val opening = parseMarkdownFenceLine(lines[index])
        if (opening == null || opening.info.lowercase() !in setOf("markdown", "md")) {
            output += lines[index++]
            continue
        }

        val markerChar = opening.marker.first()
        val closingIndex = ((index + 1) until lines.size).firstOrNull { candidateIndex ->
            val candidate = parseMarkdownFenceLine(lines[candidateIndex])
            candidate != null &&
                candidate.info.isEmpty() &&
                candidate.marker.first() == markerChar &&
                candidate.marker.length >= opening.marker.length
        }
        if (closingIndex == null) {
            output += lines[index++]
            continue
        }

        output += lines.subList(index + 1, closingIndex)
        index = closingIndex + 1
    }

    return output.joinToString("\n")
}

private val inProgressTaskMarkerPattern =
    Regex("""(?m)^([ \t]*[-+*][ \t]+)\[/]([ \t]+)""")

private fun normalizeInProgressTaskMarkers(markdown: String): String {
    return inProgressTaskMarkerPattern.replace(markdown, "\$1[ ]\$2")
}

private fun transformMathForMikePenz(markdown: String, contentKey: String): String {
    if (markdown.isEmpty()) return markdown
    val parsed = StreamBlockParser.parse(markdown, contentKey)
    return buildString(markdown.length) {
        parsed.blocks.forEach { block ->
            when (block) {
                is StreamBlock.PlainText -> append(normalizeInProgressTaskMarkers(block.text))
                is StreamBlock.CodeBlock -> append(block.text)

                is StreamBlock.MathInline -> {
                    if (block.state == MathBlockState.RENDERED) {
                        append("![math](")
                        append(INLINE_MATH_SCHEME)
                        append(encodeMathPayload(extractPureMath(block.text)))
                        append(')')
                    } else {
                        append(block.text)
                    }
                }

                is StreamBlock.MathBlock -> {
                    if (block.state == MathBlockState.RENDERED) {
                        if (isNotEmpty() && !endsWith("\n\n")) append("\n\n")
                        append("```")
                        append(MATH_FENCE_LANGUAGE)
                        append('\n')
                        append(encodeMathPayload(extractPureMath(block.text)))
                        append("\n```")
                        if (block.endExclusive < markdown.length) append("\n\n")
                    } else {
                        append(block.text)
                    }
                }
            }
        }
    }
}

internal fun encodeMathPayload(latex: String): String {
    return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(latex.toByteArray(StandardCharsets.UTF_8))
}

internal fun decodeMathPayload(payload: String): String? {
    return runCatching {
        String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8)
    }.getOrNull()
}

internal fun decodeInlineMathLink(link: String): String? {
    if (!link.startsWith(INLINE_MATH_SCHEME)) return null
    return decodeMathPayload(link.removePrefix(INLINE_MATH_SCHEME))
}

private fun extractPureMath(token: String): String {
    val trimmed = token.trim()
    return when {
        trimmed.startsWith("$$") && trimmed.endsWith("$$") && trimmed.length >= 4 -> trimmed.substring(2, trimmed.length - 2)
        trimmed.startsWith("\\[") && trimmed.endsWith("\\]") && trimmed.length >= 4 -> trimmed.substring(2, trimmed.length - 2)
        trimmed.startsWith("\\(") && trimmed.endsWith("\\)") && trimmed.length >= 4 -> trimmed.substring(2, trimmed.length - 2)
        trimmed.startsWith('$') && trimmed.endsWith('$') && trimmed.length >= 2 -> trimmed.substring(1, trimmed.length - 1)
        else -> trimmed
    }.trim()
}

private object EveryTalkMarkdownImageTransformer : ImageTransformer {
    @Composable
    override fun transform(link: String): ImageData {
        if (decodeInlineMathLink(link) != null) {
            return ImageData(
                painter = ColorPainter(Color.Transparent),
                modifier = Modifier.fillMaxSize(),
                contentDescription = "数学公式",
            )
        }
        return Coil3ImageTransformerImpl.transform(link)
    }

    override fun placeholderConfig(
        link: String,
        density: Density,
        containerSize: Size,
        imageWidth: com.mikepenz.markdown.model.ImageWidth,
        imageSize: Size,
        imageSizeChanged: ((link: String, Size) -> Unit)?,
    ): PlaceholderConfig {
        val latex = decodeInlineMathLink(link)
        if (latex == null) {
            return super.placeholderConfig(
                link,
                density,
                containerSize,
                imageWidth,
                imageSize,
                imageSizeChanged,
            )
        }
        // ponytail: 行内公式只做一次轻量尺寸估算，避免为测量再启动一个 KaTeX WebView。
        val complex = latex.contains("\\frac") || latex.contains("\\sum") || latex.contains("\\int") || latex.contains("\\sqrt")
        val width = (latex.length * 8f + 16f).coerceIn(28f, 280f)
        val height = if (complex) 40f else 28f
        return PlaceholderConfig(
            size = Size(width, height),
            verticalAlign = PlaceholderVerticalAlign.Center,
        )
    }
}
