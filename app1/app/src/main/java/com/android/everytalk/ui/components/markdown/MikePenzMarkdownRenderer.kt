package com.android.everytalk.ui.components.markdown

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.components.ChatMarkdownTextStyle
import com.android.everytalk.ui.components.ProportionalAsyncImage
import com.android.everytalk.ui.components.content.CodeBlockCard
import com.android.everytalk.ui.components.math.MathBlock
import com.android.everytalk.ui.components.math.MathInline
import com.android.everytalk.ui.components.streaming.MathBlockState
import com.android.everytalk.ui.components.streaming.StreamBlock
import com.android.everytalk.ui.components.streaming.StreamBlockParser
import com.android.everytalk.ui.components.table.TableRenderer
import com.android.everytalk.ui.components.table.TableUtils
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownImage
import com.mikepenz.markdown.compose.elements.MarkdownInlineImage
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.PlaceholderConfig
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
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
    style: TextStyle,
    color: Color,
    isStreaming: Boolean = false,
    onImageClick: ((String) -> Unit)? = null,
    onCodePreviewRequested: ((String, String) -> Unit)? = null,
    onCodeCopied: (() -> Unit)? = null,
) {
    val renderedMarkdown = remember(markdown, contentKey, isStreaming) {
        prepareMarkdownForMikePenz(markdown, contentKey)
    }
    val resolvedColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> MaterialTheme.colorScheme.onSurface
    }
    val bodyStyle = style.copy(color = resolvedColor)
    val typography = markdownTypography(
        h1 = headingStyle(bodyStyle, resolvedColor, 1),
        h2 = headingStyle(bodyStyle, resolvedColor, 2),
        h3 = headingStyle(bodyStyle, resolvedColor, 3),
        h4 = headingStyle(bodyStyle, resolvedColor, 4),
        h5 = headingStyle(bodyStyle, resolvedColor, 5),
        h6 = headingStyle(bodyStyle, resolvedColor, 6),
        text = bodyStyle,
        paragraph = bodyStyle,
        ordered = bodyStyle,
        bullet = bodyStyle,
        list = bodyStyle,
        quote = bodyStyle.copy(fontStyle = FontStyle.Italic),
        code = bodyStyle.copy(fontFamily = FontFamily.Monospace),
        inlineCode = bodyStyle.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = if (bodyStyle.fontSize.isSpecified) {
                bodyStyle.fontSize * ChatMarkdownTextStyle.INLINE_CODE_RELATIVE_SIZE
            } else {
                bodyStyle.fontSize
            },
        ),
        textLink = TextLinkStyles(
            style = SpanStyle(
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
            )
        ),
        table = bodyStyle,
    )
    val colors = markdownColor(
        text = resolvedColor,
        codeBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        inlineCodeBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
        dividerColor = resolvedColor.copy(alpha = ChatMarkdownTextStyle.HORIZONTAL_RULE_COLOR_ALPHA),
        tableBackground = MaterialTheme.colorScheme.background,
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
        table = { model ->
            val lines = model.node.getTextInNode(model.content).toString().lines()
            TableRenderer(
                lines = lines,
                modifier = Modifier.fillMaxWidth(),
                isStreaming = isStreaming,
                headerStyle = bodyStyle.copy(fontWeight = FontWeight.Bold),
                cellStyle = bodyStyle,
                onImageClick = onImageClick,
            )
        },
        image = { model ->
            val link = model.node.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)
                ?.getTextInNode(model.content)
                ?.toString()
                ?.trim()
                ?.removeSurrounding("<", ">")
            if (link != null) {
                val alt = model.node.findChildOfType(MarkdownElementTypes.LINK_TEXT)
                    ?.getTextInNode(model.content)
                    ?.toString()
                    ?.removeSurrounding("[", "]")
                val clickModifier = if (onImageClick != null) {
                    Modifier.clickable { onImageClick(link) }
                } else {
                    Modifier
                }
                ProportionalAsyncImage(
                    model = link,
                    contentDescription = alt,
                    maxWidth = 320.dp,
                    modifier = Modifier.then(clickModifier),
                )
            } else {
                MarkdownImage(model.content, model.node)
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
            colors = colors,
            typography = typography,
            modifier = modifier.fillMaxWidth(),
            padding = markdownPadding(
                block = 4.dp,
                list = 2.dp,
                listItemTop = 2.dp,
                listItemBottom = 2.dp,
                listIndent = 16.dp,
            ),
            dimens = markdownDimens(
                dividerThickness = ChatMarkdownTextStyle.HORIZONTAL_RULE_THICKNESS_DP.dp,
                codeBackgroundCornerSize = 24.dp,
                blockQuoteThickness = ChatMarkdownTextStyle.BLOCK_QUOTE_BAR_WIDTH_DP.dp,
                tableCellPadding = 8.dp,
                tableCornerSize = 12.dp,
            ),
            imageTransformer = EveryTalkMarkdownImageTransformer,
            components = components,
            retainState = true,
            loading = { Box(it) },
            error = { Box(it) },
        )
    }

    if (sender == Sender.AI) {
        SelectionContainer { markdownContent() }
    } else {
        markdownContent()
    }
}

private fun headingStyle(base: TextStyle, color: Color, level: Int): TextStyle {
    val normalizedLevel = level.coerceIn(1, 6)
    return base.copy(
        color = color.copy(
            alpha = when (normalizedLevel) {
                2, 4 -> 0.7f
                5 -> 0.5f
                else -> 1f
            }
        ),
        fontSize = ChatMarkdownTextStyle.headingFontSizeSp(normalizedLevel).sp,
        lineHeight = ChatMarkdownTextStyle.headingLineHeightSp(normalizedLevel).sp,
        fontWeight = if (normalizedLevel <= 5) FontWeight.W700 else FontWeight.Normal,
        fontStyle = if (normalizedLevel == 3) FontStyle.Italic else null,
    )
}

internal fun prepareMarkdownForMikePenz(markdown: String, contentKey: String): String {
    if (markdown.isEmpty()) return markdown
    val lines = markdown.split('\n')
    val chunks = mutableListOf<String>()
    var index = 0
    var plainStart = 0
    var segmentIndex = 0

    while (index < lines.size) {
        if (!TableUtils.isValidTableStart(lines, index)) {
            index++
            continue
        }

        if (plainStart < index) {
            chunks += transformMathOutsideTables(
                markdown = lines.subList(plainStart, index).joinToString("\n"),
                contentKey = "$contentKey:segment:${segmentIndex++}",
            )
        }
        val (tableLines, nextIndex) = TableUtils.extractTableLines(lines, index)
        chunks += tableLines.joinToString("\n")
        index = nextIndex
        plainStart = nextIndex
    }

    if (plainStart < lines.size) {
        chunks += transformMathOutsideTables(
            markdown = lines.subList(plainStart, lines.size).joinToString("\n"),
            contentKey = "$contentKey:segment:${segmentIndex}",
        )
    }
    return chunks.joinToString("\n")
}

private fun transformMathOutsideTables(markdown: String, contentKey: String): String {
    if (markdown.isEmpty()) return markdown
    val parsed = StreamBlockParser.parse(markdown, contentKey)
    return buildString(markdown.length) {
        parsed.blocks.forEach { block ->
            when (block) {
                is StreamBlock.PlainText,
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
