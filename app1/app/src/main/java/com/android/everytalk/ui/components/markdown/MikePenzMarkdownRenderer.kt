package com.android.everytalk.ui.components.markdown

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
private const val MARKDOWN_IMAGE_MIN_SIDE_DP = 1f
private const val MARKDOWN_IMAGE_LOADING_SIDE_DP = 48f
private const val MARKDOWN_IMAGE_LOADING_INDICATOR_SIDE_DP = 22f
private const val MARKDOWN_IMAGE_ONLY_TEXT_SIZE_SP = 1f
private const val MARKDOWN_IMAGE_ERROR_WIDTH_DP = 160f
private const val MARKDOWN_IMAGE_ERROR_HEIGHT_DP = 32f
private const val MARKDOWN_TABLE_EDGE_SHADOW_WIDTH_DP = 20f
private const val MARKDOWN_LINK_LOGO_SCHEME = "everytalk-link-logo:"
private const val MARKDOWN_LINK_LOGO_SIZE_EM = 0.9f
private const val MARKDOWN_LINK_LOGO_SIZE_DP = 16f
private const val MARKDOWN_LINK_LOGO_GAP_DP = 4f
private val MARKDOWN_LINK_LIGHT_COLOR = Color(0xFF3D7DB5)
private val MARKDOWN_LINK_DARK_COLOR = Color(0xFF8FC9FF)
private val MARKDOWN_IMAGE_ERROR_INTRINSIC_SIZE = Size(-1f, -1f)

internal data class MarkdownLinkLogoRequest(
    val host: String,
    val faviconUrl: String,
)

internal data class MarkdownLinkLogoIndex(
    val requests: List<MarkdownLinkLogoRequest>,
    val definitions: Map<String, String>,
)

private class AppendOnlyMarkdownBridge {
    var appendedContent: String = ""
    var nextRebaseGeneration: Int = 0
}

internal class StreamingMarkdownRebaseRequest(
    val generation: Int,
    val content: String,
    val beforePrepare: suspend () -> Unit = {},
    val result: CompletableDeferred<StreamingMarkdownState> = CompletableDeferred(),
)

internal data class StreamingMarkdownBundle(
    val state: StreamingMarkdownState,
    val preparedMessage: PreparedMessage,
)

internal fun appendOnlyMarkdownDelta(
    appendedContent: String,
    nextContent: String,
): String? = if (nextContent.startsWith(appendedContent)) {
    nextContent.substring(appendedContent.length)
} else {
    null
}

internal fun shouldRebaseStreamingMarkdownDelta(delta: String): Boolean {
    if (delta.isBlank()) return false
    if (delta.contains("\n\n")) return true

    return delta.lineSequence().any { line ->
        val trimmed = line.trimStart()
        trimmed.startsWith("```") ||
            trimmed.startsWith("~~~")
    }
}

@Composable
internal fun PrepareStreamingMarkdownRebase(request: StreamingMarkdownRebaseRequest?) {
    if (request == null) return
    key(request.generation) {
        val state = rememberStreamingMarkdownState()
        LaunchedEffect(state, request) {
            try {
                request.beforePrepare()
                if (request.content.isNotEmpty()) state.append(request.content)
                request.result.complete(state)
            } catch (error: CancellationException) {
                request.result.cancel(error)
                throw error
            } catch (error: Throwable) {
                request.result.completeExceptionally(error)
            }
        }
    }
}

@Composable
internal fun rememberStreamingMarkdownBundle(
    preparedMessage: PreparedMessage,
    streamEpoch: Int,
    enabled: Boolean,
    isStreaming: Boolean = true,
    beforeRebase: suspend (generation: Int) -> Unit = {},
): StreamingMarkdownBundle? {
    val activeBundle = remember(streamEpoch) {
        mutableStateOf<StreamingMarkdownBundle?>(null)
    }
    val pendingRebaseRequest = remember(streamEpoch) {
        mutableStateOf<StreamingMarkdownRebaseRequest?>(null)
    }
    val finalizedEpoch = remember(streamEpoch) { mutableIntStateOf(-1) }
    val latestPreparedMessage = remember(streamEpoch) {
        MutableStateFlow(preparedMessage)
    }
    val appendBridge = remember(streamEpoch) { AppendOnlyMarkdownBridge() }
    SideEffect {
        latestPreparedMessage.value = preparedMessage
    }

    LaunchedEffect(streamEpoch, enabled, isStreaming) {
        if (!enabled) return@LaunchedEffect
        val shouldFinalizeAfterFirstUpdate = !isStreaming
        latestPreparedMessage.collect { nextPreparedMessage ->
            val nextContent = nextPreparedMessage.markdown
            val currentBundle = activeBundle.value
            val delta = appendOnlyMarkdownDelta(
                appendedContent = appendBridge.appendedContent,
                nextContent = nextContent,
            )
            val shouldFinalize = shouldFinalizeAfterFirstUpdate && finalizedEpoch.intValue != streamEpoch
            val shouldRebase = currentBundle == null ||
                delta == null ||
                shouldFinalize ||
                shouldRebaseStreamingMarkdownDelta(delta.orEmpty())
            if (shouldRebase) {
                val generation = ++appendBridge.nextRebaseGeneration
                val request = StreamingMarkdownRebaseRequest(
                    generation = generation,
                    content = nextContent,
                    beforePrepare = { beforeRebase(generation) },
                )
                pendingRebaseRequest.value = request
                val rebasedState = try {
                    request.result.await()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    null
                } finally {
                    if (pendingRebaseRequest.value === request) {
                        pendingRebaseRequest.value = null
                    }
                }
                if (rebasedState != null) {
                    appendBridge.appendedContent = nextContent
                    activeBundle.value = StreamingMarkdownBundle(
                        state = rebasedState,
                        preparedMessage = nextPreparedMessage,
                    )
                    if (shouldFinalize) finalizedEpoch.intValue = streamEpoch
                }
            } else {
                val bundle = requireNotNull(currentBundle)
                val appendedDelta = requireNotNull(delta)
                if (appendedDelta.isNotEmpty()) bundle.state.append(appendedDelta)
                appendBridge.appendedContent = nextContent
                activeBundle.value = bundle.copy(preparedMessage = nextPreparedMessage)
            }
        }
    }

    if (enabled) PrepareStreamingMarkdownRebase(pendingRebaseRequest.value)
    return if (enabled) activeBundle.value else null
}

internal data class MarkdownTableEdgeVisibility(
    val showLeft: Boolean,
    val showRight: Boolean,
)

internal fun markdownTableEdgeVisibility(
    scrollValue: Int,
    maxValue: Int,
): MarkdownTableEdgeVisibility {
    val scrollable = maxValue > 0
    return MarkdownTableEdgeVisibility(
        showLeft = scrollable && scrollValue > 0,
        showRight = scrollable && scrollValue < maxValue,
    )
}

internal fun markdownTableEdgeFadeColor(surfaceColor: Color): Color =
    if (surfaceColor.luminance() < 0.5f) Color.Black else Color.White

internal fun shouldFillMarkdownWidth(sender: Sender): Boolean = sender != Sender.User

private fun Modifier.markdownWidth(sender: Sender): Modifier = if (shouldFillMarkdownWidth(sender)) {
    fillMaxWidth()
} else {
    wrapContentWidth()
}

internal fun markdownLinkLogoIndex(content: String): MarkdownLinkLogoIndex {
    val state = runCatching {
        parseMarkdown(content, lookupLinks = true)
    }.getOrNull() as? State.Success ?: return MarkdownLinkLogoIndex(
        requests = emptyList(),
        definitions = emptyMap(),
    )

    val definitions = linkedMapOf<String, String>()
    state.node.collectMarkdownLinkDefinitions(content, definitions)

    val requestsByHost = linkedMapOf<String, MarkdownLinkLogoRequest>()
    state.node.collectMarkdownLinkLogoDestinations(content, definitions) { destination ->
        val host = linkHost(destination)
        if (host.isNotBlank()) {
            requestsByHost.putIfAbsent(
                host,
                MarkdownLinkLogoRequest(
                    host = host,
                    faviconUrl = linkFaviconUrl(destination),
                ),
            )
        }
    }
    return MarkdownLinkLogoIndex(
        requests = requestsByHost.values.toList(),
        definitions = definitions,
    )
}

internal fun markdownLinkLogoRequests(content: String): List<MarkdownLinkLogoRequest> =
    markdownLinkLogoIndex(content).requests

internal fun markdownLinkLogoKey(host: String): String = MARKDOWN_LINK_LOGO_SCHEME + host

private fun normalizeMarkdownLinkLabel(raw: String): String = raw
    .trim()
    .removePrefix("[")
    .removeSuffix("]")
    .trim()
    .lowercase()
    .replace(Regex("\\s+"), " ")

private fun cleanMarkdownLinkDestination(raw: String): String = raw
    .trim()
    .removePrefix("<")
    .removeSuffix(">")
    .trim()

private fun ASTNode.collectMarkdownLinkDefinitions(
    content: String,
    definitions: MutableMap<String, String>,
) {
    if (type == MarkdownElementTypes.LINK_DEFINITION) {
        val label = findDescendant(MarkdownElementTypes.LINK_LABEL)?.safeTextInNode(content)
        val destination = findDescendant(MarkdownElementTypes.LINK_DESTINATION)
            ?.safeTextInNode(content)
        if (label != null && destination != null) {
            definitions.putIfAbsent(
                normalizeMarkdownLinkLabel(label),
                cleanMarkdownLinkDestination(destination),
            )
        }
    }
    children.forEach { child ->
        child.collectMarkdownLinkDefinitions(content, definitions)
    }
}

private fun ASTNode.collectMarkdownLinkLogoDestinations(
    content: String,
    definitions: Map<String, String>,
    onDestination: (String) -> Unit,
) {
    markdownLinkLogoDestination(content, definitions)?.let(onDestination)
    children.forEach { child ->
        child.collectMarkdownLinkLogoDestinations(content, definitions, onDestination)
    }
}

private fun ASTNode.markdownLinkLogoDestination(
    content: String,
    definitions: Map<String, String>,
): String? {
    if (hasMarkdownLinkLogoExcludedAncestor()) return null

    val destination = when (type) {
        MarkdownElementTypes.INLINE_LINK ->
            findDescendant(MarkdownElementTypes.LINK_DESTINATION)
                ?.safeTextInNode(content)
        MarkdownElementTypes.AUTOLINK,
        GFMTokenTypes.GFM_AUTOLINK ->
            findDescendant(MarkdownElementTypes.LINK_DESTINATION)
                ?.safeTextInNode(content)
                ?: safeTextInNode(content)
        MarkdownElementTypes.FULL_REFERENCE_LINK,
        MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
            val label = findDescendant(MarkdownElementTypes.LINK_LABEL)
                ?.safeTextInNode(content)
                ?: return null
            definitions[normalizeMarkdownLinkLabel(label)]
        }
        else -> null
    } ?: return null

    return cleanMarkdownLinkDestination(destination)
}

private fun ASTNode.hasMarkdownLinkLogoExcludedAncestor(): Boolean {
    var current = parent
    while (current != null) {
        if (
            current.type == MarkdownElementTypes.IMAGE ||
            current.type == MarkdownElementTypes.CODE_FENCE ||
            current.type == MarkdownElementTypes.CODE_BLOCK ||
            current.type == MarkdownElementTypes.CODE_SPAN
        ) {
            return true
        }
        current = current.parent
    }
    return false
}

private fun preparedMessageLinkLogoSource(message: PreparedMessage): String = buildString {
    append(message.markdown)
    message.details.values.forEach { details ->
        append('\n')
        append(details.summary)
    }
}

internal fun markdownSingleAutolinkLogoRequest(
    content: String,
    node: ASTNode,
    definitions: Map<String, String>,
    requests: List<MarkdownLinkLogoRequest>,
): MarkdownLinkLogoRequest? {
    if (node.type != MarkdownElementTypes.PARAGRAPH) return null

    val significantChildren = node.children.filterNot { child ->
        child.type == MarkdownTokenTypes.WHITE_SPACE || child.type == MarkdownTokenTypes.EOL
    }
    val autolink = significantChildren.singleOrNull { child ->
        child.type == MarkdownElementTypes.AUTOLINK ||
            child.type == GFMTokenTypes.GFM_AUTOLINK
    } ?: return null
    val paragraphText = node.safeTextInNode(content)?.trim() ?: return null
    val autolinkText = autolink.safeTextInNode(content)?.trim() ?: return null
    if (paragraphText != autolinkText) return null
    val host = autolink.markdownLinkLogoDestination(content, definitions)
        ?.let(::linkHost)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return requests.firstOrNull { it.host == host }
}

@Composable
internal fun MarkdownSingleAutolinkLogoParagraph(
    content: String,
    node: ASTNode,
    style: TextStyle,
    request: MarkdownLinkLogoRequest,
    logoFreeAnnotator: MarkdownAnnotator,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.wrapContentWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        MarkdownLinkLogo(
            request = request,
            modifier = Modifier.size(MARKDOWN_LINK_LOGO_SIZE_DP.dp),
        )
        Spacer(modifier = Modifier.size(MARKDOWN_LINK_LOGO_GAP_DP.dp))
        Box {
            CompositionLocalProvider(
                LocalMarkdownAnnotator provides logoFreeAnnotator,
            ) {
                MarkdownParagraph(
                    content = content,
                    node = node,
                    style = style,
                )
            }
        }
    }
}

private fun markdownLinkLogoInlineContent(
    requests: List<MarkdownLinkLogoRequest>,
): Map<String, InlineTextContent> = requests.associate { request ->
    markdownLinkLogoKey(request.host) to InlineTextContent(
        placeholder = Placeholder(
            width = MARKDOWN_LINK_LOGO_SIZE_EM.em,
            height = MARKDOWN_LINK_LOGO_SIZE_EM.em,
            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
        ),
    ) {
        MarkdownLinkLogo(request)
    }
}

@Composable
private fun MarkdownLinkLogo(
    request: MarkdownLinkLogoRequest,
    modifier: Modifier = Modifier,
) {
    val painter = rememberAsyncImagePainter(model = request.faviconUrl)
    val painterState = painter.state.collectAsState().value
    Box(
        modifier = Modifier
            .then(modifier)
            .fillMaxSize()
            .padding(1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics(mergeDescendants = true) {
                contentDescription = "链接 Logo：${request.host}"
            },
        contentAlignment = Alignment.Center,
    ) {
        when (painterState) {
            is AsyncImagePainter.State.Empty,
            is AsyncImagePainter.State.Loading -> {
                EveryTalkLoadingIndicator(
                    size = 10.dp,
                    strokeWidth = 1.dp,
                    contentDescription = "链接 Logo 加载中",
                )
            }
            is AsyncImagePainter.State.Error -> {
                Text(
                    text = linkFaviconInitial("https://${request.host}"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is AsyncImagePainter.State.Success -> {
                Image(
                    painter = painter,
                    contentDescription = "链接来源：${request.host}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

internal object EveryTalkMarkdownImageTransformer : ImageTransformer by Coil3ImageTransformerImpl {
    override fun placeholderConfig(
        link: String,
        density: Density,
        containerSize: Size,
        imageWidth: ImageWidth,
        imageSize: Size,
        imageSizeChanged: ((link: String, Size) -> Unit)?,
    ): PlaceholderConfig {
        if (imageSize == MARKDOWN_IMAGE_ERROR_INTRINSIC_SIZE) {
            val containerWidthDp = if (containerSize.isUnspecified) {
                MARKDOWN_IMAGE_ERROR_WIDTH_DP
            } else {
                with(density) { containerSize.width.toDp().value }
            }
            return PlaceholderConfig(
                size = Size(
                    width = minOf(MARKDOWN_IMAGE_ERROR_WIDTH_DP, containerWidthDp)
                        .coerceAtLeast(MARKDOWN_IMAGE_MIN_SIDE_DP),
                    height = MARKDOWN_IMAGE_ERROR_HEIGHT_DP,
                )
            )
        }
        if (imageSize.isUnspecified) {
            return PlaceholderConfig(
                size = Size(MARKDOWN_IMAGE_LOADING_SIDE_DP, MARKDOWN_IMAGE_LOADING_SIDE_DP)
            )
        }
        return Coil3ImageTransformerImpl.placeholderConfig(
            link = link,
            density = density,
            containerSize = containerSize,
            imageWidth = imageWidth,
            imageSize = imageSize,
            imageSizeChanged = imageSizeChanged,
        )
    }

    @Composable
    override fun intrinsicSize(painter: Painter): Size {
        val painterState = (painter as? AsyncImagePainter)?.state?.collectAsState()?.value
        return markdownImageIntrinsicSize(
            hasError = painterState is AsyncImagePainter.State.Error,
            intrinsicSize = Coil3ImageTransformerImpl.intrinsicSize(painter),
        )
    }
}

internal fun markdownParagraphStyle(
    content: String,
    node: ASTNode,
    baseStyle: TextStyle,
): TextStyle {
    val image = node.children.singleOrNull { child ->
        child.type == MarkdownElementTypes.IMAGE
    } ?: return baseStyle
    val paragraphText = node.safeTextInNode(content) ?: return baseStyle
    val imageText = image.safeTextInNode(content) ?: return baseStyle
    if (paragraphText.trim() != imageText.trim()) {
        return baseStyle
    }
    return baseStyle.copy(
        fontSize = MARKDOWN_IMAGE_ONLY_TEXT_SIZE_SP.sp,
        lineHeight = MARKDOWN_IMAGE_ONLY_TEXT_SIZE_SP.sp,
    )
}

internal fun markdownImageIntrinsicSize(
    hasError: Boolean,
    intrinsicSize: Size,
): Size = if (hasError) MARKDOWN_IMAGE_ERROR_INTRINSIC_SIZE else intrinsicSize

@Composable
internal fun MarkdownImageFailure(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Text(
                text = "图片加载失败",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun MarkdownImageLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        EveryTalkLoadingIndicator(
            size = MARKDOWN_IMAGE_LOADING_INDICATOR_SIDE_DP.dp,
            strokeWidth = 2.dp,
            contentDescription = "图片加载中",
        )
    }
}

@Composable
private fun MarkdownInlineImageWithFailure(
    model: MarkdownComponentModel,
    onImageClick: ((String) -> Unit)?,
) {
    val imageData = EveryTalkMarkdownImageTransformer.transform(model.content)
    val painterState = (imageData?.painter as? AsyncImagePainter)?.state?.collectAsState()?.value
    when {
        imageData == null || painterState is AsyncImagePainter.State.Error -> {
            MarkdownImageFailure(modifier = Modifier.fillMaxSize())
        }

        painterState is AsyncImagePainter.State.Empty || painterState is AsyncImagePainter.State.Loading -> {
            MarkdownImageLoading(modifier = Modifier.fillMaxSize())
        }

        else -> {
            Image(
                painter = imageData.painter,
                contentDescription = imageData.contentDescription,
                modifier = Modifier
                    .fillMaxSize()
                    .then(imageData.modifier)
                    .markdownImageClick(model.content, onImageClick),
                alignment = imageData.alignment,
                contentScale = imageData.contentScale,
                alpha = imageData.alpha,
                colorFilter = imageData.colorFilter,
            )
        }
    }
}

internal fun Modifier.markdownImageClick(
    source: String,
    onImageClick: ((String) -> Unit)?,
): Modifier = if (onImageClick == null) {
    this
} else {
    clickable(
        onClickLabel = "预览图片",
        onClick = { onImageClick(source) },
    )
}

@Composable
private fun EveryTalkMarkdownTable(
    content: String,
    node: ASTNode,
    style: TextStyle,
    headerBlock: @Composable (String, ASTNode, Dp, TextStyle) -> Unit,
    rowBlock: @Composable (String, ASTNode, Dp, TextStyle) -> Unit,
) {
    val tableMaxWidth = LocalMarkdownDimens.current.tableMaxWidth
    val tableCellWidth = LocalMarkdownDimens.current.tableCellWidth
    val columnsCount = remember(node) {
        node.findChildOfType(GFMElementTypes.HEADER)
            ?.children
            ?.count { it.type == GFMTokenTypes.CELL }
            ?: 0
    }
    val rowsCount = remember(node) {
        node.children.count { it.type == GFMElementTypes.ROW } + 1
    }
    val tableWidth = columnsCount * tableCellWidth

    BoxWithConstraints(
        modifier = Modifier
            .widthIn(max = tableMaxWidth)
            .semantics {
                collectionInfo = CollectionInfo(
                    rowCount = rowsCount,
                    columnCount = columnsCount,
                )
            },
    ) {
        val scrollable = tableWidth > maxWidth
        val scrollState = rememberScrollState()
        val edgeColor = markdownTableEdgeFadeColor(MaterialTheme.colorScheme.surface)
        val transparentEdgeColor = edgeColor.copy(alpha = 0f)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawWithContent {
                    drawContent()
                    if (!scrollable) return@drawWithContent

                    val visibility = markdownTableEdgeVisibility(
                        scrollValue = scrollState.value,
                        maxValue = scrollState.maxValue,
                    )
                    val edgeWidth = minOf(
                        MARKDOWN_TABLE_EDGE_SHADOW_WIDTH_DP.dp.toPx(),
                        size.width / 3f,
                    )
                    if (edgeWidth <= 0f) return@drawWithContent

                    if (visibility.showLeft) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(edgeColor, transparentEdgeColor),
                                startX = 0f,
                                endX = edgeWidth,
                            ),
                            topLeft = Offset.Zero,
                            size = Size(edgeWidth, size.height),
                        )
                    }
                    if (visibility.showRight) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(transparentEdgeColor, edgeColor),
                                startX = size.width - edgeWidth,
                                endX = size.width,
                            ),
                            topLeft = Offset(size.width - edgeWidth, 0f),
                            size = Size(edgeWidth, size.height),
                        )
                    }
                },
        ) {
            Column(
                modifier = if (scrollable) {
                    Modifier
                        .horizontalScroll(scrollState)
                        .requiredWidth(tableWidth)
                } else {
                    Modifier.fillMaxWidth()
                },
            ) {
                var rowIndex = 1
                node.children.forEach { child ->
                    when (child.type) {
                        GFMElementTypes.HEADER -> headerBlock(content, child, tableWidth, style)
                        GFMElementTypes.ROW -> {
                            CompositionLocalProvider(LocalTableRowIndex provides rowIndex) {
                                rowBlock(content, child, tableWidth, style)
                            }
                            rowIndex++
                        }
                        GFMTokenTypes.TABLE_SEPARATOR -> MarkdownDivider()
                    }
                }
            }
        }
    }
}

private data class RegisteredFootnoteRequester(
    val requester: BringIntoViewRequester,
    val priority: Int,
)

class FootnoteNavigationState {
    private val requestersByUri =
        linkedMapOf<String, LinkedHashSet<RegisteredFootnoteRequester>>()
    private val lastReferenceUriByNumber = mutableMapOf<Int, String>()
    private var fallbackNavigator: ((String) -> Boolean)? = null

    fun setFallbackNavigator(navigator: ((String) -> Boolean)?) {
        fallbackNavigator = navigator
    }

    fun navigateFallback(uri: String): Boolean = fallbackNavigator?.invoke(uri) == true

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
                uri.startsWith(FOOTNOTE_REFERENCE_SCHEME) -> navigation.navigateFallback(uri)

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
    onImageClick: ((String) -> Unit)? = null,
    enableSelectionContainer: Boolean = true,
    preparedMarkdownDocument: PreparedMarkdownDocument? = null,
    markdownNode: ASTNode? = null,
    markdownNodes: List<ASTNode>? = null,
    footnoteNavigationState: FootnoteNavigationState? = null,
) {
    val committedStreamEpoch = remember { mutableIntStateOf(0) }
    val wasStreaming = remember { mutableStateOf(false) }
    val startsNewStream = sender == Sender.AI && isStreaming && !wasStreaming.value
    val streamEpoch = committedStreamEpoch.intValue + if (startsNewStream) 1 else 0
    SideEffect {
        if (startsNewStream) committedStreamEpoch.intValue = streamEpoch
        wasStreaming.value = sender == Sender.AI && isStreaming
    }
    val usesStreamingMarkdownState = sender == Sender.AI && streamEpoch > 0
    val visibleStreamingBundle = rememberStreamingMarkdownBundle(
        preparedMessage = preparedMessage,
        streamEpoch = streamEpoch,
        enabled = usesStreamingMarkdownState,
        isStreaming = isStreaming,
    )
    val visiblePreparedMessage = visibleStreamingBundle?.preparedMessage ?: preparedMessage

    val mathRenderer = koinInject<MathJaxSvgRenderer>()
    val density = LocalDensity.current
    val formulaColor = MaterialTheme.colorScheme.onSurface.toMathJaxCssColor()
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
    )
    val markdownLinkColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        MARKDOWN_LINK_DARK_COLOR
    } else {
        MARKDOWN_LINK_LIGHT_COLOR
    }
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
                color = markdownLinkColor,
                fontWeight = FontWeight.Medium,
                textDecoration = TextDecoration.None,
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
    val footnoteNavigation = footnoteNavigationState
        ?: inheritedFootnoteNavigation
        ?: remember { FootnoteNavigationState() }
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
        visiblePreparedMessage.markdown,
        visiblePreparedMessage.formulas,
        visiblePreparedMessage.contentVersion,
    ) {
        resolveVisibleFormulaRequests(visiblePreparedMessage)
    }
    val renderIdentities = remember(validFormulas) { formulaRenderIdentities(validFormulas) }
    val renderFormulaMessage = remember(renderIdentities) {
        val renderVersion = validFormulas.values.firstOrNull()?.contentVersion
            ?: visiblePreparedMessage.contentVersion
        PreparedMessage(
            markdown = "",
            formulas = validFormulas,
            hasPendingFormula = false,
            contentVersion = renderVersion,
        )
    }
    val renderFormulas = renderFormulaMessage.formulas

    val markdownContent: @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalFootnoteNavigation provides footnoteNavigation,
            LocalUriHandler provides footnoteUriHandler,
        ) {
            BoxWithConstraints(modifier = modifier.markdownWidth(sender)) {
            val blockMaxWidthPx = constraints.maxWidth
                .takeIf { constraints.hasBoundedWidth }
                ?.toFloat()
                .let(::mathWidthBucketPx)
            val formulaStates = rememberMathFormulaRenderStates(
                renderer = mathRenderer,
                formulas = renderFormulas,
                fontSizePx = formulaFontSizePx,
                color = formulaColor,
                blockMaxWidthPx = blockMaxWidthPx,
            )
            val inlineContentMap = remember(
                renderFormulas,
                formulaStates,
                formulaFontSizePx,
            ) {
                renderFormulas.values
                    .filter { it.displayMode == FormulaDisplayMode.INLINE }
                    .associate { formula ->
                        val link = INLINE_FORMULA_SCHEME + formula.id
                        val state = formulaStates[formula.id] ?: MathFormulaRenderState.Loading
                        val metrics = inlineFormulaMetrics(state, formulaFontSizePx)
                        link to InlineTextContent(
                            placeholder = Placeholder(
                                width = metrics.widthEm.em,
                                height = metrics.heightEm.em,
                                placeholderVerticalAlign = metrics.verticalAlign,
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
            val linkLogoSource = remember(
                isStreaming,
                visiblePreparedMessage.markdown.takeUnless { isStreaming },
                visiblePreparedMessage.details.takeUnless { isStreaming },
            ) {
                // 流式期间不创建 favicon 请求，避免每个增量片段触发额外重组。
                if (isStreaming) "" else preparedMessageLinkLogoSource(visiblePreparedMessage)
            }
            val linkLogoIndex = remember(linkLogoSource) {
                markdownLinkLogoIndex(linkLogoSource)
            }
            val linkLogoRequests = linkLogoIndex.requests
            val linkLogoDefinitions = linkLogoIndex.definitions
            val linkLogoInlineContent = remember(linkLogoRequests) {
                markdownLinkLogoInlineContent(linkLogoRequests)
            }
            val markdownInlineContentMap = remember(
                inlineContentMap,
                linkLogoInlineContent,
            ) {
                inlineContentMap + linkLogoInlineContent
            }
            val annotator = remember(
                renderFormulaMessage,
                linkLogoRequests,
                linkLogoDefinitions,
            ) {
                createPreparedMessageMarkdownAnnotator(
                    preparedMessage = renderFormulaMessage,
                    linkLogoHosts = linkLogoRequests.mapTo(linkedSetOf()) { it.host },
                    linkLogoDefinitions = linkLogoDefinitions,
                )
            }
            val logoFreeAnnotator = remember(renderFormulaMessage) {
                createPreparedMessageMarkdownAnnotator(
                    preparedMessage = renderFormulaMessage,
                )
            }
            val currentPreparedMessage = rememberUpdatedState(visiblePreparedMessage)
            val currentFormulaMessage = rememberUpdatedState(renderFormulaMessage)
            val currentFormulaStates = rememberUpdatedState(formulaStates)
            val currentInlineContentMap = rememberUpdatedState(markdownInlineContentMap)
            val currentAnnotator = rememberUpdatedState(annotator)
            val currentLogoFreeAnnotator = rememberUpdatedState(logoFreeAnnotator)
            val currentLinkLogoRequests = rememberUpdatedState(linkLogoRequests)
            val currentLinkLogoDefinitions = rememberUpdatedState(linkLogoDefinitions)
    val currentBodyStyle = rememberUpdatedState(bodyStyle)
    val currentSender = rememberUpdatedState(sender)
    val currentIsStreaming = rememberUpdatedState(isStreaming)
    val currentSuppressInitialCodeBlockResizeAnimation = rememberUpdatedState(
        preparedMarkdownDocument != null && markdownNode != null && !isStreaming
    )
    val currentCodePreviewCallback = rememberUpdatedState(onCodePreviewRequested)
            val currentCodeCopiedCallback = rememberUpdatedState(onCodeCopied)
            val currentImageClickCallback = rememberUpdatedState(onImageClick)
            val components = remember(footnoteNavigation) {
                markdownComponents(
                inlineImage = { model ->
                    MarkdownInlineImageWithFailure(
                        model = model,
                        onImageClick = currentImageClickCallback.value,
                    )
                },
                paragraph = { model ->
                    FootnoteTarget(
                        targetUris = footnoteTargets(
                            content = model.content,
                            node = model.node,
                        ),
                        navigation = footnoteNavigation,
                    ) { targetModifier ->
                        val linkLogoRequest = markdownSingleAutolinkLogoRequest(
                            content = model.content,
                            node = model.node,
                            definitions = currentLinkLogoDefinitions.value,
                            requests = currentLinkLogoRequests.value,
                        )
                        if (linkLogoRequest == null) {
                            MarkdownParagraph(
                                content = model.content,
                                node = model.node,
                                modifier = targetModifier,
                                style = markdownParagraphStyle(
                                    content = model.content,
                                    node = model.node,
                                    baseStyle = model.typography.paragraph,
                                ),
                            )
                        } else {
                            MarkdownSingleAutolinkLogoParagraph(
                                content = model.content,
                                node = model.node,
                                style = markdownParagraphStyle(
                                    content = model.content,
                                    node = model.node,
                                    baseStyle = model.typography.paragraph,
                                ),
                                request = linkLogoRequest,
                                logoFreeAnnotator = currentLogoFreeAnnotator.value,
                                modifier = targetModifier,
                            )
                        }
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
                    EveryTalkMarkdownTable(
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
                                        maxLines = Int.MAX_VALUE,
                                        overflow = TextOverflow.Clip,
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
                                        maxLines = Int.MAX_VALUE,
                                        overflow = TextOverflow.Clip,
                                    )
                                }
                            }
                        },
                    )
                },
                codeFence = { model ->
                    MarkdownCodeFence(model.content, model.node, model.typography.code) { code, language, _ ->
                        when (language) {
                            BLOCK_FORMULA_FENCE_LANGUAGE -> {
                                val activeMessage = currentFormulaMessage.value
                                val formula = resolveBlockFormula(language, code, activeMessage)
                                if (formula == null) {
                                    CodeBlockCard(
                                        language = language,
                                        code = code,
                                        isStreaming = currentIsStreaming.value,
                                        suppressInitialAsyncResizeAnimation =
                                            currentSuppressInitialCodeBlockResizeAnimation.value,
                                        onCopy = currentCodeCopiedCallback.value,
                                    )
                                } else {
                                MathBlock(
                                    formula = formula,
                                    state = currentFormulaStates.value[formula.id]
                                        ?: MathFormulaRenderState.Error(
                                            MathFormulaErrorKind.ENGINE
                                        ),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                }
                            }

                            DETAILS_FENCE_LANGUAGE -> {
                                val activeMessage = currentPreparedMessage.value
                                val details = resolveDetailsRequest(language, code, activeMessage)
                                if (details == null) {
                                    CodeBlockCard(
                                        language = language,
                                        code = code,
                                        isStreaming = currentIsStreaming.value,
                                        suppressInitialAsyncResizeAnimation =
                                            currentSuppressInitialCodeBlockResizeAnimation.value,
                                        onCopy = currentCodeCopiedCallback.value,
                                    )
                                    return@MarkdownCodeFence
                                }
                                val nestedMessage = remember(
                                    details,
                                    activeMessage.formulas,
                                    activeMessage.details,
                                ) {
                                    activeMessage.copy(markdown = details.markdown)
                                }
                                val summaryStyle = currentBodyStyle.value.copy(
                                    fontWeight = FontWeight.Medium,
                                )
                                val summaryAnnotatorSettings = annotatorSettings(
                                    annotator = currentAnnotator.value,
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
                                    activeMessage.details,
                                ) {
                                    detailsSubtreeFootnoteReferenceTargets(
                                        root = details,
                                        detailsById = activeMessage.details,
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
                                        summaryInlineContent = currentInlineContentMap.value,
                                        modifier = targetModifier,
                                    ) {
                                        MikePenzMarkdownRenderer(
                                            preparedMessage = nestedMessage,
                                            modifier = Modifier,
                                            sender = currentSender.value,
                                            isStreaming = currentIsStreaming.value,
                                            onCodePreviewRequested = currentCodePreviewCallback.value,
                                            onCodeCopied = currentCodeCopiedCallback.value,
                                            enableSelectionContainer = false,
                                        )
                                    }
                                }
                            }

                            else -> {
                                CodeBlockCard(
                                    language = language,
                                    code = code,
                                    isStreaming = currentIsStreaming.value,
                                    suppressInitialAsyncResizeAnimation =
                                        currentSuppressInitialCodeBlockResizeAnimation.value,
                                    onCopy = currentCodeCopiedCallback.value,
                                    onPreviewRequested = currentCodePreviewCallback.value?.let { callback ->
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
                            isStreaming = currentIsStreaming.value,
                            suppressInitialAsyncResizeAnimation =
                                currentSuppressInitialCodeBlockResizeAnimation.value,
                            onCopy = currentCodeCopiedCallback.value,
                            onPreviewRequested = currentCodePreviewCallback.value?.let { callback ->
                                { callback(language.orEmpty(), code) }
                            },
                        )
                    }
                },
            )
            }

            val markdownColors = markdownColor(
                inlineCodeBackground = Color.Transparent,
                tableBackground = Color.Transparent,
            )
            val markdownInlineContent = markdownInlineContent(markdownInlineContentMap)
            val renderStaticMarkdown: @Composable () -> Unit = {
                val selectedMarkdownNodes = markdownNodes ?: markdownNode?.let(::listOf)
                if (preparedMarkdownDocument != null && selectedMarkdownNodes != null) {
                    Markdown(
                        state = preparedMarkdownDocument.state,
                        colors = markdownColors,
                        typography = typography,
                        padding = padding,
                        modifier = Modifier.markdownWidth(sender),
                        imageTransformer = EveryTalkMarkdownImageTransformer,
                        annotator = annotator,
                        inlineContent = markdownInlineContent,
                        components = components,
                        success = { state, nodeComponents, nodeModifier ->
                            MarkdownNodesSuccess(
                                state = state,
                                components = nodeComponents,
                                modifier = nodeModifier,
                                nodes = selectedMarkdownNodes,
                            )
                        },
                    )
                } else {
                    Markdown(
                        content = visiblePreparedMessage.markdown,
                        colors = markdownColors,
                        typography = typography,
                        padding = padding,
                        modifier = Modifier.markdownWidth(sender),
                        imageTransformer = EveryTalkMarkdownImageTransformer,
                        annotator = annotator,
                        inlineContent = markdownInlineContent,
                        components = components,
                        retainState = true,
                    )
                }
            }

            Column {
                key(streamEpoch) {
                    if (usesStreamingMarkdownState) {
                        val visibleStreamingMarkdownState = visibleStreamingBundle?.state
                        if (visibleStreamingMarkdownState == null) {
                            if (visiblePreparedMessage.markdown.isNotEmpty()) {
                                Box(modifier = Modifier.size(18.dp)) {
                                    EveryTalkLoadingIndicator(
                                        size = 14.dp,
                                        strokeWidth = 1.5.dp,
                                        contentDescription = "Markdown 渲染中",
                                    )
                                }
                            }
                        } else {
                            Markdown(
                                streamingMarkdownState = visibleStreamingMarkdownState,
                                colors = markdownColors,
                                typography = typography,
                                padding = padding,
                                modifier = Modifier.markdownWidth(sender),
                                imageTransformer = EveryTalkMarkdownImageTransformer,
                                annotator = annotator,
                                inlineContent = markdownInlineContent,
                                components = components,
                            )
                        }
                    } else {
                        renderStaticMarkdown()
                    }
                }
                if (sender == Sender.AI && isStreaming && preparedMessage.hasPendingFormula) {
                    Box(
                        modifier = Modifier
                            .padding(top = 3.dp)
                            .size(18.dp),
                    ) {
                        EveryTalkLoadingIndicator(
                            size = 14.dp,
                            strokeWidth = 1.5.dp,
                            contentDescription = "数学公式输入中",
                        )
                    }
                }
            }
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
private fun MarkdownNodesSuccess(
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

internal fun ASTNode.inlineImageDestination(content: String): String? {
    if (type != MarkdownElementTypes.IMAGE) return null
    return findDescendant(MarkdownElementTypes.LINK_DESTINATION)
        ?.safeTextInNode(content)
}

private fun ASTNode.findDescendant(type: org.intellij.markdown.IElementType): ASTNode? {
    children.forEach { child ->
        if (child.type == type) return child
        child.findDescendant(type)?.let { return it }
    }
    return null
}
