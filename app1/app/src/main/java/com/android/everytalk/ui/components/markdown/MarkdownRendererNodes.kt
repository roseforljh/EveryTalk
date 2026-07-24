package com.android.everytalk.ui.components.markdown
import com.android.everytalk.statecontroller.*

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.components.ChatMarkdownTextStyle
import com.android.everytalk.ui.components.EveryTalkLoadingIndicator
import com.android.everytalk.ui.components.content.CodeBlockCard
import com.android.everytalk.ui.components.math.MathBlock
import com.android.everytalk.ui.components.math.MathFormulaErrorKind
import com.android.everytalk.ui.components.math.MathFormulaRenderState
import com.android.everytalk.ui.components.math.MathInline
import com.android.everytalk.ui.components.math.MathJaxSvgRenderer
import com.android.everytalk.ui.components.math.formulaRenderIdentities
import com.android.everytalk.ui.components.math.rememberMathFormulaRenderStates
import com.android.everytalk.ui.components.math.mathWidthBucketPx
import com.android.everytalk.ui.components.math.requireHeightPx
import com.android.everytalk.ui.components.math.requireWidthPx
import com.android.everytalk.ui.components.math.toMathJaxCssColor
import com.android.everytalk.ui.components.streaming.BLOCK_FORMULA_FENCE_LANGUAGE
import com.android.everytalk.ui.components.streaming.DETAILS_FENCE_LANGUAGE
import com.android.everytalk.ui.components.streaming.DetailsRequest
import com.android.everytalk.ui.components.streaming.FormulaDisplayMode
import com.android.everytalk.ui.components.streaming.FormulaRequest
import com.android.everytalk.ui.components.streaming.INLINE_FORMULA_SCHEME
import com.android.everytalk.ui.components.streaming.PreparedMarkdownDocument
import com.android.everytalk.ui.components.streaming.PreparedMessage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Size as CoilSize
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownAnnotator
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownDivider
import com.mikepenz.markdown.compose.elements.MarkdownHeader
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.compose.elements.LocalTableRowIndex
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.ImageWidth
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.PlaceholderConfig
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.StreamingMarkdownState
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownInlineContent
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.parseMarkdown
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import com.android.everytalk.util.web.linkFaviconInitial
import com.android.everytalk.util.web.linkFaviconUrl
import com.android.everytalk.util.web.linkHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.koin.compose.koinInject

@Composable
fun MikePenzMarkdownNodeRenderer(
    preparedMessage: PreparedMessage,
    preparedMarkdownDocument: PreparedMarkdownDocument,
    node: ASTNode,
    modifier: Modifier = Modifier,
    sender: Sender = Sender.AI,
    onCodePreviewRequested: ((String, String) -> Unit)? = null,
    onCodeCopied: (() -> Unit)? = null,
    onImageClick: ((String) -> Unit)? = null,
    footnoteNavigationState: FootnoteNavigationState? = null,
) {
    MikePenzMarkdownNodesRenderer(
        preparedMessage = preparedMessage,
        preparedMarkdownDocument = preparedMarkdownDocument,
        nodes = listOf(node),
        modifier = modifier,
        sender = sender,
        onCodePreviewRequested = onCodePreviewRequested,
        onCodeCopied = onCodeCopied,
        onImageClick = onImageClick,
        footnoteNavigationState = footnoteNavigationState,
    )
}

@Composable
fun MikePenzMarkdownNodesRenderer(
    preparedMessage: PreparedMessage,
    preparedMarkdownDocument: PreparedMarkdownDocument,
    nodes: List<ASTNode>,
    modifier: Modifier = Modifier,
    sender: Sender = Sender.AI,
    onCodePreviewRequested: ((String, String) -> Unit)? = null,
    onCodeCopied: (() -> Unit)? = null,
    onImageClick: ((String) -> Unit)? = null,
    footnoteNavigationState: FootnoteNavigationState? = null,
) {
    MikePenzMarkdownRenderer(
        preparedMessage = preparedMessage,
        modifier = modifier,
        sender = sender,
        isStreaming = false,
        onCodePreviewRequested = onCodePreviewRequested,
        onCodeCopied = onCodeCopied,
        onImageClick = onImageClick,
        preparedMarkdownDocument = preparedMarkdownDocument,
        markdownNodes = nodes,
        footnoteNavigationState = footnoteNavigationState,
    )
}

@Composable
internal fun MarkdownNodesSuccess(
    state: State.Success,
    components: MarkdownComponents,
    modifier: Modifier,
    nodes: List<ASTNode>,
) {
    Column(modifier = modifier) {
        nodes.forEach { node ->
            MarkdownElement(node, components, state.content)
        }
    }
}

@Composable
internal fun FootnoteTarget(
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
internal fun FootnoteMarkdownHeader(
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

internal fun String.containsDetailsFence(id: String): Boolean =
    contains("```$DETAILS_FENCE_LANGUAGE\n$id\n```")

internal fun ASTNode.collectInlineLinks(
    content: String,
    onLink: (rawLink: String, destination: String) -> Unit,
) {
    if (type == MarkdownElementTypes.LINK_DESTINATION) {
        val link = parent?.takeIf { parentNode ->
            parentNode.type == MarkdownElementTypes.INLINE_LINK
        }
        if (link != null) {
            val rawLink = link.safeTextInNode(content)
            val destination = safeTextInNode(content)
            if (rawLink != null && destination != null) onLink(rawLink, destination)
        }
    }
    children.forEach { child -> child.collectInlineLinks(content, onLink) }
}

internal data class InlineFormulaMetrics(
    val widthEm: Float,
    val heightEm: Float,
    val verticalAlign: PlaceholderVerticalAlign,
)

internal fun inlineFormulaMetrics(
    state: MathFormulaRenderState,
    fontSizePx: Float,
): InlineFormulaMetrics = when (state) {
    MathFormulaRenderState.Loading -> InlineFormulaMetrics(
        widthEm = 1f,
        heightEm = 1f,
        verticalAlign = PlaceholderVerticalAlign.TextCenter,
    )

    is MathFormulaRenderState.Error -> InlineFormulaMetrics(
        widthEm = 3.5f,
        heightEm = 1.15f,
        verticalAlign = PlaceholderVerticalAlign.TextCenter,
    )

    is MathFormulaRenderState.Ready -> InlineFormulaMetrics(
        widthEm = (state.result.requireWidthPx() / fontSizePx).coerceAtLeast(0.01f),
        heightEm = (state.result.requireHeightPx() / fontSizePx).coerceAtLeast(0.01f),
        verticalAlign = PlaceholderVerticalAlign.TextCenter,
    )
}

internal fun inlineFormulaAlternateText(formula: FormulaRequest): String =
    "${'$'}${formula.latex}${'$'}"

internal fun createPreparedMessageMarkdownAnnotator(
    preparedMessage: PreparedMessage,
    linkLogoHosts: Set<String> = emptySet(),
    linkLogoDefinitions: Map<String, String> = emptyMap(),
): MarkdownAnnotator = markdownAnnotator { content, child ->
    val linkLogoDestination = child.markdownLinkLogoDestination(content, linkLogoDefinitions)
    val linkLogoHost = linkLogoDestination
        ?.let(::linkHost)
        ?.takeIf { it in linkLogoHosts }
    if (linkLogoHost != null) {
        appendInlineContent(
            markdownLinkLogoKey(linkLogoHost),
            linkLogoHost,
        )
    }

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
    visibleMarkdown: String = preparedMessage.markdown,
): Map<String, FormulaRequest> {
    val visibleSource = buildString {
        append(visibleMarkdown)
        preparedMessage.details.values.forEach { details ->
            if (visibleMarkdown.contains(details.id)) {
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

internal fun markdownNodesSourceOrNull(
    content: String,
    nodes: List<ASTNode>,
): String? = buildString {
    nodes.forEachIndexed { index, node ->
        val nodeSource = node.safeTextInNode(content) ?: return null
        if (index > 0) append('\n')
        append(nodeSource)
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

internal fun ASTNode.inlineImageDestination(content: String): String? {
    if (type != MarkdownElementTypes.IMAGE) return null
    return findDescendant(MarkdownElementTypes.LINK_DESTINATION)
        ?.safeTextInNode(content)
}

internal fun ASTNode.findDescendant(type: org.intellij.markdown.IElementType): ASTNode? {
    children.forEach { child ->
        if (child.type == type) return child
        child.findDescendant(type)?.let { return it }
    }
    return null
}
