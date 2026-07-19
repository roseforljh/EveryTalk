package com.android.everytalk.ui.components.markdown

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.components.content.CodeBlockCard
import com.android.everytalk.ui.components.math.MathBlock
import com.android.everytalk.ui.components.math.MathFormulaErrorKind
import com.android.everytalk.ui.components.math.MathFormulaRenderState
import com.android.everytalk.ui.components.math.MathInline
import com.android.everytalk.ui.components.math.MathJaxSvgRenderer
import com.android.everytalk.ui.components.math.rememberMathFormulaRenderStates
import com.android.everytalk.ui.components.math.mathWidthBucketPx
import com.android.everytalk.ui.components.math.requireDepthPx
import com.android.everytalk.ui.components.math.requireHeightPx
import com.android.everytalk.ui.components.math.requireWidthPx
import com.android.everytalk.ui.components.math.toMathJaxCssColor
import com.android.everytalk.ui.components.streaming.BLOCK_FORMULA_FENCE_LANGUAGE
import com.android.everytalk.ui.components.streaming.FormulaDisplayMode
import com.android.everytalk.ui.components.streaming.FormulaRequest
import com.android.everytalk.ui.components.streaming.INLINE_FORMULA_SCHEME
import com.android.everytalk.ui.components.streaming.PreparedMessage
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownInlineContent
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.koin.compose.koinInject

private val formulaIdPattern = Regex("^[0-9a-f]{64}$")

@Composable
fun MikePenzMarkdownRenderer(
    preparedMessage: PreparedMessage,
    modifier: Modifier = Modifier,
    sender: Sender = Sender.AI,
    isStreaming: Boolean = false,
    onCodePreviewRequested: ((String, String) -> Unit)? = null,
    onCodeCopied: (() -> Unit)? = null,
) {
    val mathRenderer = koinInject<MathJaxSvgRenderer>()
    val density = LocalDensity.current
    val formulaColor = MaterialTheme.colorScheme.onSurface.toMathJaxCssColor()
    val typography = markdownTypography(
        h1 = MaterialTheme.typography.headlineSmall,
        h2 = MaterialTheme.typography.titleLarge,
        h3 = MaterialTheme.typography.titleMedium,
        h4 = MaterialTheme.typography.titleSmall,
        h5 = MaterialTheme.typography.bodyLarge,
        h6 = MaterialTheme.typography.bodyMedium,
    )
    val formulaFontSize = typography.text.fontSize.takeIf { it.isSpecified }
        ?: MaterialTheme.typography.bodyLarge.fontSize
    val formulaFontSizePx = with(density) { formulaFontSize.toPx() }
    val validFormulas = remember(preparedMessage.formulas, preparedMessage.contentVersion) {
        preparedMessage.formulas.filter { (id, formula) ->
            id == formula.id &&
                formulaIdPattern.matches(id) &&
                formula.contentVersion == preparedMessage.contentVersion
        }
    }

    val markdownContent: @Composable () -> Unit = {
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            val blockMaxWidthPx = constraints.maxWidth
                .takeIf { constraints.hasBoundedWidth }
                ?.toFloat()
                .let(::mathWidthBucketPx)
            val formulaStates = rememberMathFormulaRenderStates(
                renderer = mathRenderer,
                formulas = validFormulas,
                fontSizePx = formulaFontSizePx,
                color = formulaColor,
                blockMaxWidthPx = blockMaxWidthPx,
            )
            val inlineContentMap = remember(
                validFormulas,
                formulaStates,
                formulaFontSizePx,
            ) {
                validFormulas.values
                    .filter { it.displayMode == FormulaDisplayMode.INLINE }
                    .associate { formula ->
                        val link = INLINE_FORMULA_SCHEME + formula.id
                        val state = formulaStates[formula.id] ?: MathFormulaRenderState.Loading
                        val metrics = inlineFormulaMetrics(state, formulaFontSizePx)
                        link to InlineTextContent(
                            placeholder = Placeholder(
                                width = metrics.widthEm.em,
                                height = metrics.heightAboveBaselineEm.em,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline,
                            ),
                        ) {
                            MathInline(
                                formula = formula,
                                state = state,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
            }
            val annotator = remember(preparedMessage.formulas, preparedMessage.contentVersion) {
                markdownAnnotator { content, child ->
                    val link = child.inlineImageDestination(content)
                    val formula = link?.let { resolveInlineFormula(it, preparedMessage) }
                    if (link != null && formula != null) {
                        appendInlineContent(link, inlineFormulaAlternateText(formula))
                        true
                    } else {
                        false
                    }
                }
            }
            val components = markdownComponents(
                codeFence = { model ->
                    MarkdownCodeFence(model.content, model.node, model.typography.code) { code, language, _ ->
                        val formula = resolveBlockFormula(language, code, preparedMessage)
                        if (formula != null) {
                            MathBlock(
                                formula = formula,
                                state = formulaStates[formula.id]
                                    ?: MathFormulaRenderState.Error(
                                        MathFormulaErrorKind.ENGINE
                                    ),
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
            )

            Markdown(
                content = preparedMessage.markdown,
                colors = markdownColor(),
                typography = typography,
                modifier = Modifier.fillMaxWidth(),
                imageTransformer = Coil3ImageTransformerImpl,
                annotator = annotator,
                inlineContent = markdownInlineContent(inlineContentMap),
                components = components,
            )
        }
    }

    if (sender == Sender.AI) {
        SelectionContainer { markdownContent() }
    } else {
        markdownContent()
    }
}

private data class InlineFormulaMetrics(
    val widthEm: Float,
    val heightAboveBaselineEm: Float,
)

private fun inlineFormulaMetrics(
    state: MathFormulaRenderState,
    fontSizePx: Float,
): InlineFormulaMetrics = when (state) {
    MathFormulaRenderState.Loading -> InlineFormulaMetrics(
        widthEm = 1f,
        heightAboveBaselineEm = 1f,
    )

    is MathFormulaRenderState.Error -> InlineFormulaMetrics(
        widthEm = 3.5f,
        heightAboveBaselineEm = 1.15f,
    )

    is MathFormulaRenderState.Ready -> InlineFormulaMetrics(
        widthEm = (state.result.requireWidthPx() / fontSizePx).coerceAtLeast(0.01f),
        heightAboveBaselineEm = (
            (state.result.requireHeightPx() - state.result.requireDepthPx()) / fontSizePx
        ).coerceAtLeast(0.01f),
    )
}

internal fun inlineFormulaAlternateText(formula: FormulaRequest): String =
    "${'$'}${formula.latex}${'$'}"

internal fun resolveInlineFormula(
    link: String,
    preparedMessage: PreparedMessage,
): FormulaRequest? {
    if (!link.startsWith(INLINE_FORMULA_SCHEME)) return null
    val id = link.removePrefix(INLINE_FORMULA_SCHEME)
    if (!formulaIdPattern.matches(id)) return null
    return preparedMessage.formulas[id]?.takeIf { formula ->
        formula.id == id &&
            formula.displayMode == FormulaDisplayMode.INLINE &&
            formula.contentVersion == preparedMessage.contentVersion
    }
}

internal fun resolveBlockFormula(
    language: String?,
    code: String,
    preparedMessage: PreparedMessage,
): FormulaRequest? {
    if (language != BLOCK_FORMULA_FENCE_LANGUAGE) return null
    val id = code.trim()
    if (!formulaIdPattern.matches(id)) return null
    return preparedMessage.formulas[id]?.takeIf { formula ->
        formula.id == id &&
            formula.displayMode == FormulaDisplayMode.BLOCK &&
            formula.contentVersion == preparedMessage.contentVersion
    }
}

private fun ASTNode.inlineImageDestination(content: String): String? {
    if (type != MarkdownElementTypes.IMAGE) return null
    return findDescendant(MarkdownElementTypes.LINK_DESTINATION)
        ?.getTextInNode(content)
        ?.toString()
}

private fun ASTNode.findDescendant(type: org.intellij.markdown.IElementType): ASTNode? {
    children.forEach { child ->
        if (child.type == type) return child
        child.findDescendant(type)?.let { return it }
    }
    return null
}
