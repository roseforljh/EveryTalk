package com.android.everytalk.ui.components.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
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
import com.android.everytalk.ui.components.streaming.DETAILS_FENCE_LANGUAGE
import com.android.everytalk.ui.components.streaming.DetailsRequest
import com.android.everytalk.ui.components.streaming.FormulaDisplayMode
import com.android.everytalk.ui.components.streaming.FormulaRequest
import com.android.everytalk.ui.components.streaming.INLINE_FORMULA_SCHEME
import com.android.everytalk.ui.components.streaming.PreparedMessage
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHeader
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownInlineContent
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.parseMarkdown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.koin.compose.koinInject

private val contentAddressPattern = Regex("^[0-9a-f]{64}$")
private val footnoteDefinitionUriPattern =
    Regex("""^everytalk-footnote-definition:(\d+):(\d+)$""")
private val footnoteReferenceUriPattern =
    Regex("""^everytalk-footnote-reference:(\d+)$""")
private val footnoteReferenceTargetUriPattern =
    Regex("""^everytalk-footnote-reference:(\d+):(\d+)$""")
private val LocalFootnoteNavigation = compositionLocalOf<FootnoteNavigationState?> { null }
private const val FOOTNOTE_TARGET_PRIORITY_FALLBACK = 0
private const val FOOTNOTE_TARGET_PRIORITY_EXACT = 1

private data class RegisteredFootnoteRequester(
    val requester: BringIntoViewRequester,
    val priority: Int,
)

internal class FootnoteNavigationState {
    private val requestersByUri =
        linkedMapOf<String, LinkedHashSet<RegisteredFootnoteRequester>>()
    private val lastReferenceUriByNumber = mutableMapOf<Int, String>()

    fun register(
        uri: String,
        requester: BringIntoViewRequester,
        priority: Int = FOOTNOTE_TARGET_PRIORITY_EXACT,
    ) {
        requestersByUri.getOrPut(uri, ::linkedSetOf).add(
            RegisteredFootnoteRequester(requester = requester, priority = priority)
        )
    }

    fun unregister(uri: String, requester: BringIntoViewRequester) {
        requestersByUri[uri]?.let { registrations ->
            registrations.removeAll { registration -> registration.requester === requester }
            if (registrations.isEmpty()) requestersByUri.remove(uri)
        }
    }

    fun requesterFor(uri: String): BringIntoViewRequester? {
        val definitionLink = footnoteDefinitionUriPattern.matchEntire(uri)
        if (definitionLink != null) {
            val number = definitionLink.groupValues[1].toIntOrNull() ?: return null
            val occurrence = definitionLink.groupValues[2].toIntOrNull() ?: return null
            lastReferenceUriByNumber[number] = footnoteReferenceUri(number, occurrence)
            return bestRequester(footnoteDefinitionUri(number))
        }

        val referenceLink = footnoteReferenceUriPattern.matchEntire(uri)
        if (referenceLink != null) {
            val number = referenceLink.groupValues[1].toIntOrNull() ?: return null
            val preferredUri = lastReferenceUriByNumber[number]
            val preferred = preferredUri?.let(::bestRequester)
            if (preferred != null) return preferred
            val prefix = "$FOOTNOTE_REFERENCE_SCHEME$number:"
            return requestersByUri.entries.asSequence()
                .filter { (targetUri) -> targetUri.startsWith(prefix) }
                .flatMap { (_, registrations) -> registrations.asSequence() }
                .maxByOrNull(RegisteredFootnoteRequester::priority)
                ?.requester
        }

        return bestRequester(uri)
    }

    private fun bestRequester(uri: String): BringIntoViewRequester? =
        requestersByUri[uri]?.let { registrations ->
            val highestPriority = registrations.maxOfOrNull(
                RegisteredFootnoteRequester::priority
            ) ?: return@let null
            registrations.lastOrNull { registration ->
                registration.priority == highestPriority
            }?.requester
        }
}

private class FootnoteUriHandler(
    private val navigation: FootnoteNavigationState,
    private val coroutineScope: CoroutineScope,
    private val fallback: UriHandler,
) : UriHandler {
    override fun openUri(uri: String) {
        val requester = navigation.requesterFor(uri)
        when {
            requester != null -> coroutineScope.launch { requester.bringIntoView() }
            uri.startsWith(FOOTNOTE_DEFINITION_SCHEME) ||
                uri.startsWith(FOOTNOTE_REFERENCE_SCHEME) -> Unit

            else -> fallback.openUri(uri)
        }
    }
}

@Composable
fun MikePenzMarkdownRenderer(
    preparedMessage: PreparedMessage,
    modifier: Modifier = Modifier,
    sender: Sender = Sender.AI,
    isStreaming: Boolean = false,
    onCodePreviewRequested: ((String, String) -> Unit)? = null,
    onCodeCopied: (() -> Unit)? = null,
    enableSelectionContainer: Boolean = true,
) {
    val mathRenderer = koinInject<MathJaxSvgRenderer>()
    val density = LocalDensity.current
    val formulaColor = MaterialTheme.colorScheme.onSurface.toMathJaxCssColor()
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
    )
    val typography = markdownTypography(
        h1 = bodyStyle.copy(
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        h2 = bodyStyle.copy(
            fontSize = 22.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        h3 = bodyStyle.copy(
            fontSize = 20.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        h4 = bodyStyle.copy(
            fontSize = 18.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        h5 = bodyStyle.copy(
            fontSize = 17.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Medium,
        ),
        h6 = bodyStyle.copy(
            fontWeight = FontWeight.Medium,
        ),
        text = bodyStyle,
        quote = bodyStyle,
        paragraph = bodyStyle,
        ordered = bodyStyle,
        bullet = bodyStyle,
        list = bodyStyle,
        table = bodyStyle,
        inlineCode = bodyStyle.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        textLink = TextLinkStyles(
            style = SpanStyle(
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                textDecoration = TextDecoration.Underline,
            )
        ),
    )
    val padding = markdownPadding(
        block = 3.dp,
        list = 4.dp,
        listItemTop = 3.dp,
        listItemBottom = 3.dp,
        listIndent = 22.dp,
    )
    val inheritedFootnoteNavigation = LocalFootnoteNavigation.current
    val footnoteNavigation = inheritedFootnoteNavigation ?: remember { FootnoteNavigationState() }
    val fallbackUriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()
    val footnoteUriHandler = if (inheritedFootnoteNavigation == null) {
        remember(footnoteNavigation, coroutineScope, fallbackUriHandler) {
            FootnoteUriHandler(
                navigation = footnoteNavigation,
                coroutineScope = coroutineScope,
                fallback = fallbackUriHandler,
            )
        }
    } else {
        fallbackUriHandler
    }
    val formulaFontSize = typography.text.fontSize.takeIf { it.isSpecified }
        ?: MaterialTheme.typography.bodyLarge.fontSize
    val formulaFontSizePx = with(density) { formulaFontSize.toPx() }
    val validFormulas = remember(
        preparedMessage.markdown,
        preparedMessage.formulas,
        preparedMessage.contentVersion,
    ) {
        resolveVisibleFormulaRequests(preparedMessage)
    }

    val markdownContent: @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalFootnoteNavigation provides footnoteNavigation,
            LocalUriHandler provides footnoteUriHandler,
        ) {
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
                createPreparedMessageMarkdownAnnotator(preparedMessage)
            }
            val components = markdownComponents(
                paragraph = { model ->
                    FootnoteTarget(
                        targetUris = footnoteTargets(
                            content = model.content,
                            node = model.node,
                        ),
                        navigation = footnoteNavigation,
                    ) { targetModifier ->
                        MarkdownParagraph(
                            content = model.content,
                            node = model.node,
                            modifier = targetModifier,
                            style = model.typography.paragraph,
                        )
                    }
                },
                heading1 = { model ->
                    FootnoteMarkdownHeader(
                        model = model,
                        navigation = footnoteNavigation,
                        style = model.typography.h1,
                    )
                },
                heading2 = { model ->
                    FootnoteMarkdownHeader(
                        model = model,
                        navigation = footnoteNavigation,
                        style = model.typography.h2,
                    )
                },
                heading3 = { model ->
                    FootnoteMarkdownHeader(
                        model = model,
                        navigation = footnoteNavigation,
                        style = model.typography.h3,
                    )
                },
                heading4 = { model ->
                    FootnoteMarkdownHeader(
                        model = model,
                        navigation = footnoteNavigation,
                        style = model.typography.h4,
                    )
                },
                heading5 = { model ->
                    FootnoteMarkdownHeader(
                        model = model,
                        navigation = footnoteNavigation,
                        style = model.typography.h5,
                    )
                },
                heading6 = { model ->
                    FootnoteMarkdownHeader(
                        model = model,
                        navigation = footnoteNavigation,
                        style = model.typography.h6,
                    )
                },
                setextHeading1 = { model ->
                    FootnoteMarkdownHeader(
                        model = model,
                        navigation = footnoteNavigation,
                        style = model.typography.h1,
                        contentChildType = MarkdownTokenTypes.SETEXT_CONTENT,
                    )
                },
                setextHeading2 = { model ->
                    FootnoteMarkdownHeader(
                        model = model,
                        navigation = footnoteNavigation,
                        style = model.typography.h2,
                        contentChildType = MarkdownTokenTypes.SETEXT_CONTENT,
                    )
                },
                table = { model ->
                    MarkdownTable(
                        content = model.content,
                        node = model.node,
                        style = model.typography.table,
                        headerBlock = { content, header, tableWidth, style ->
                            FootnoteTarget(
                                targetUris = footnoteTargets(
                                    content = content,
                                    node = header,
                                ),
                                navigation = footnoteNavigation,
                            ) { targetModifier ->
                                Box(modifier = targetModifier) {
                                    MarkdownTableHeader(
                                        content = content,
                                        header = header,
                                        tableWidth = tableWidth,
                                        style = style,
                                    )
                                }
                            }
                        },
                        rowBlock = { content, row, tableWidth, style ->
                            FootnoteTarget(
                                targetUris = footnoteTargets(
                                    content = content,
                                    node = row,
                                ),
                                navigation = footnoteNavigation,
                            ) { targetModifier ->
                                Box(modifier = targetModifier) {
                                    MarkdownTableRow(
                                        content = content,
                                        header = row,
                                        tableWidth = tableWidth,
                                        style = style,
                                    )
                                }
                            }
                        },
                    )
                },
                codeFence = { model ->
                    MarkdownCodeFence(model.content, model.node, model.typography.code) { code, language, _ ->
                        val formula = resolveBlockFormula(language, code, preparedMessage)
                        val details = resolveDetailsRequest(language, code, preparedMessage)
                        when {
                            formula != null -> {
                                MathBlock(
                                    formula = formula,
                                    state = formulaStates[formula.id]
                                        ?: MathFormulaRenderState.Error(
                                            MathFormulaErrorKind.ENGINE
                                        ),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            details != null -> {
                                val nestedMessage = remember(
                                    details,
                                    preparedMessage.formulas,
                                    preparedMessage.details,
                                ) {
                                    preparedMessage.copy(markdown = details.markdown)
                                }
                                val summaryStyle = bodyStyle.copy(
                                    fontWeight = FontWeight.Medium,
                                )
                                val summaryAnnotatorSettings = annotatorSettings(
                                    annotator = annotator,
                                )
                                val summary = remember(
                                    details.summary,
                                    summaryStyle,
                                    summaryAnnotatorSettings,
                                ) {
                                    details.summary.buildMarkdownAnnotatedString(
                                        style = summaryStyle,
                                        annotatorSettings = summaryAnnotatorSettings,
                                    )
                                }
                                val summaryFootnoteTargets = remember(details.summary) {
                                    footnoteTargets(details.summary)
                                }
                                val bodyFootnoteFallbackTargets = remember(
                                    details,
                                    preparedMessage.details,
                                ) {
                                    detailsSubtreeFootnoteReferenceTargets(
                                        root = details,
                                        detailsById = preparedMessage.details,
                                    )
                                }
                                FootnoteTarget(
                                    targetUris = summaryFootnoteTargets,
                                    fallbackTargetUris = bodyFootnoteFallbackTargets,
                                    navigation = footnoteNavigation,
                                ) { targetModifier ->
                                    MarkdownDetailsBlock(
                                        request = details,
                                        summary = summary,
                                        summaryInlineContent = inlineContentMap,
                                        modifier = targetModifier,
                                    ) {
                                        MikePenzMarkdownRenderer(
                                            preparedMessage = nestedMessage,
                                            modifier = Modifier.fillMaxWidth(),
                                            sender = sender,
                                            isStreaming = isStreaming,
                                            onCodePreviewRequested = onCodePreviewRequested,
                                            onCodeCopied = onCodeCopied,
                                            enableSelectionContainer = false,
                                        )
                                    }
                                }
                            }

                            else -> {
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
                colors = markdownColor(
                    inlineCodeBackground = Color.Transparent,
                ),
                typography = typography,
                padding = padding,
                modifier = Modifier.fillMaxWidth(),
                imageTransformer = Coil3ImageTransformerImpl,
                annotator = annotator,
                inlineContent = markdownInlineContent(inlineContentMap),
                components = components,
            )
            }
        }
    }

    if (sender == Sender.AI && enableSelectionContainer) {
        SelectionContainer { markdownContent() }
    } else {
        markdownContent()
    }
}

@Composable
private fun FootnoteTarget(
    targetUris: Set<String>,
    fallbackTargetUris: Set<String> = emptySet(),
    navigation: FootnoteNavigationState,
    content: @Composable (Modifier) -> Unit,
) {
    if (targetUris.isEmpty() && fallbackTargetUris.isEmpty()) {
        content(Modifier)
        return
    }

    val requester = remember(targetUris, fallbackTargetUris) { BringIntoViewRequester() }
    DisposableEffect(navigation, requester, targetUris, fallbackTargetUris) {
        targetUris.forEach { uri ->
            navigation.register(
                uri = uri,
                requester = requester,
                priority = FOOTNOTE_TARGET_PRIORITY_EXACT,
            )
        }
        fallbackTargetUris.forEach { uri ->
            navigation.register(
                uri = uri,
                requester = requester,
                priority = FOOTNOTE_TARGET_PRIORITY_FALLBACK,
            )
        }
        onDispose {
            targetUris.forEach { uri -> navigation.unregister(uri, requester) }
            fallbackTargetUris.forEach { uri -> navigation.unregister(uri, requester) }
        }
    }
    content(Modifier.bringIntoViewRequester(requester))
}

@Composable
private fun FootnoteMarkdownHeader(
    model: MarkdownComponentModel,
    navigation: FootnoteNavigationState,
    style: TextStyle,
    contentChildType: IElementType = MarkdownTokenTypes.ATX_CONTENT,
) {
    FootnoteTarget(
        targetUris = footnoteTargets(content = model.content, node = model.node),
        navigation = navigation,
    ) { targetModifier ->
        Box(modifier = targetModifier) {
            MarkdownHeader(
                content = model.content,
                node = model.node,
                style = style,
                contentChildType = contentChildType,
            )
        }
    }
}

internal fun footnoteTargets(markdown: String): Set<String> =
    when (val state = parseMarkdown(markdown, lookupLinks = false)) {
        is State.Success -> footnoteTargets(content = state.content, node = state.node)
        else -> emptySet()
    }

internal fun footnoteTargets(content: String, node: ASTNode): Set<String> = buildSet {
    node.collectInlineLinks(content) { rawLink, destination ->
        val definitionLink = footnoteDefinitionUriPattern.matchEntire(destination)
        if (definitionLink != null) {
            val number = definitionLink.groupValues[1].toIntOrNull()
            val occurrence = definitionLink.groupValues[2].toIntOrNull()
            if (
                number != null &&
                occurrence != null &&
                rawLink == "[${footnoteNumberLabel(number)}]($destination)"
            ) {
                add(footnoteReferenceUri(number, occurrence))
            }
            return@collectInlineLinks
        }

        val referenceLink = footnoteReferenceUriPattern.matchEntire(destination)
        val number = referenceLink?.groupValues?.get(1)?.toIntOrNull()
        if (
            number != null &&
            rawLink == "[${footnoteNumberLabel(number)}]($destination)"
        ) {
            add(footnoteDefinitionUri(number))
        }
    }
}

internal fun footnoteReferenceTargets(markdown: String): Set<String> =
    footnoteTargets(markdown).filterTo(linkedSetOf()) { target ->
        footnoteReferenceTargetUriPattern.matches(target)
    }

internal fun footnoteDefinitionTargets(markdown: String): Set<String> =
    footnoteTargets(markdown).filterTo(linkedSetOf()) { target ->
        target.startsWith(FOOTNOTE_DEFINITION_SCHEME)
    }

internal fun detailsSubtreeFootnoteReferenceTargets(
    root: DetailsRequest,
    detailsById: Map<String, DetailsRequest>,
): Set<String> {
    val targets = linkedSetOf<String>()
    val visited = mutableSetOf<String>()

    fun collect(details: DetailsRequest) {
        if (!visited.add(details.id)) return
        targets.addAll(footnoteReferenceTargets(details.summary))
        targets.addAll(footnoteReferenceTargets(details.markdown))

        detailsById.values.forEach { child ->
            if (
                child.contentVersion == details.contentVersion &&
                (details.summary.containsDetailsFence(child.id) ||
                    details.markdown.containsDetailsFence(child.id))
            ) {
                collect(child)
            }
        }
    }

    collect(root)
    return targets
}

private fun String.containsDetailsFence(id: String): Boolean =
    contains("```$DETAILS_FENCE_LANGUAGE\n$id\n```")

private fun ASTNode.collectInlineLinks(
    content: String,
    onLink: (rawLink: String, destination: String) -> Unit,
) {
    if (type == MarkdownElementTypes.LINK_DESTINATION) {
        val link = parent?.takeIf { parentNode ->
            parentNode.type == MarkdownElementTypes.INLINE_LINK
        }
        if (link != null) {
            onLink(
                link.getTextInNode(content).toString(),
                getTextInNode(content).toString(),
            )
        }
    }
    children.forEach { child -> child.collectInlineLinks(content, onLink) }
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

internal fun createPreparedMessageMarkdownAnnotator(
    preparedMessage: PreparedMessage,
): MarkdownAnnotator = markdownAnnotator { content, child ->
    val link = child.inlineImageDestination(content)
    val formula = link?.let { resolveInlineFormula(it, preparedMessage) }
    if (link != null && formula != null) {
        appendInlineContent(link, inlineFormulaAlternateText(formula))
        true
    } else {
        annotateMarkdownHtmlCompatibility(content, child)
    }
}

internal fun resolveInlineFormula(
    link: String,
    preparedMessage: PreparedMessage,
): FormulaRequest? {
    if (!link.startsWith(INLINE_FORMULA_SCHEME)) return null
    val id = link.removePrefix(INLINE_FORMULA_SCHEME)
    if (!contentAddressPattern.matches(id)) return null
    return preparedMessage.formulas[id]?.takeIf { formula ->
        formula.id == id &&
            formula.displayMode == FormulaDisplayMode.INLINE &&
            formula.contentVersion == preparedMessage.contentVersion
    }
}

internal fun resolveVisibleFormulaRequests(
    preparedMessage: PreparedMessage,
): Map<String, FormulaRequest> {
    val visibleSource = buildString {
        append(preparedMessage.markdown)
        preparedMessage.details.values.forEach { details ->
            if (preparedMessage.markdown.contains(details.id)) {
                append('\n')
                append(details.summary)
            }
        }
    }
    return preparedMessage.formulas.filter { (id, formula) ->
        id == formula.id &&
            contentAddressPattern.matches(id) &&
            formula.contentVersion == preparedMessage.contentVersion &&
            visibleSource.contains(id)
    }
}

internal fun resolveBlockFormula(
    language: String?,
    code: String,
    preparedMessage: PreparedMessage,
): FormulaRequest? {
    if (language != BLOCK_FORMULA_FENCE_LANGUAGE) return null
    val id = code.trim()
    if (!contentAddressPattern.matches(id)) return null
    return preparedMessage.formulas[id]?.takeIf { formula ->
        formula.id == id &&
            formula.displayMode == FormulaDisplayMode.BLOCK &&
            formula.contentVersion == preparedMessage.contentVersion
    }
}

internal fun resolveDetailsRequest(
    language: String?,
    code: String,
    preparedMessage: PreparedMessage,
): DetailsRequest? {
    if (language != DETAILS_FENCE_LANGUAGE) return null
    val id = code.trim()
    if (!contentAddressPattern.matches(id)) return null
    return preparedMessage.details[id]?.takeIf { details ->
        details.id == id && details.contentVersion == preparedMessage.contentVersion
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
