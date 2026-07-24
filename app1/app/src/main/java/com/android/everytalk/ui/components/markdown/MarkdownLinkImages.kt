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

internal fun markdownLinkLogoIndex(content: String): MarkdownLinkLogoIndex {
    val state = runCatching {
        parseMarkdown(content, lookupLinks = true)
    }.getOrNull() as? State.Success ?: return MarkdownLinkLogoIndex(
        requests = emptyList(),
        definitions = emptyMap(),
    )

    return markdownLinkLogoIndex(content, state)
}

internal fun markdownLinkLogoIndex(
    content: String,
    state: State.Success,
): MarkdownLinkLogoIndex {
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

internal fun normalizeMarkdownLinkLabel(raw: String): String = raw
    .trim()
    .removePrefix("[")
    .removeSuffix("]")
    .trim()
    .lowercase()
    .replace(Regex("\\s+"), " ")

internal fun cleanMarkdownLinkDestination(raw: String): String = raw
    .trim()
    .removePrefix("<")
    .removeSuffix(">")
    .trim()

internal fun ASTNode.collectMarkdownLinkDefinitions(
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

internal fun ASTNode.collectMarkdownLinkLogoDestinations(
    content: String,
    definitions: Map<String, String>,
    onDestination: (String) -> Unit,
) {
    markdownLinkLogoDestination(content, definitions)?.let(onDestination)
    children.forEach { child ->
        child.collectMarkdownLinkLogoDestinations(content, definitions, onDestination)
    }
}

internal fun ASTNode.markdownLinkLogoDestination(
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

internal fun ASTNode.hasMarkdownLinkLogoExcludedAncestor(): Boolean {
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

internal fun preparedMessageLinkLogoSource(message: PreparedMessage): String = buildString {
    append(message.markdown)
    message.details.values.forEach { details ->
        append('\n')
        append(details.summary)
    }
}

internal fun resolveMarkdownLinkLogoIndex(
    isStreaming: Boolean,
    preparedMessage: PreparedMessage,
    preparedMarkdownDocument: PreparedMarkdownDocument?,
    calculate: (String) -> MarkdownLinkLogoIndex = ::markdownLinkLogoIndex,
): MarkdownLinkLogoIndex {
    if (isStreaming) {
        return MarkdownLinkLogoIndex(
            requests = emptyList(),
            definitions = emptyMap(),
        )
    }
    return preparedMarkdownDocument?.linkLogoIndex
        ?: calculate(preparedMessageLinkLogoSource(preparedMessage))
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
        modifier = modifier.wrapContentWidth(align = Alignment.Start),
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

internal fun markdownLinkLogoInlineContent(
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
internal fun MarkdownLinkLogo(
    request: MarkdownLinkLogoRequest,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val iconSizePx = with(LocalDensity.current) {
        MARKDOWN_LINK_LOGO_SIZE_DP.dp.roundToPx().coerceAtLeast(1)
    }
    val imageRequest = remember(context, request.faviconUrl, iconSizePx) {
        ImageRequest.Builder(context)
            .data(request.faviconUrl)
            .size(CoilSize(iconSizePx, iconSizePx))
            .build()
    }
    val painter = rememberAsyncImagePainter(model = imageRequest)
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
internal fun MarkdownInlineImageWithFailure(
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
internal fun EveryTalkMarkdownTable(
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

