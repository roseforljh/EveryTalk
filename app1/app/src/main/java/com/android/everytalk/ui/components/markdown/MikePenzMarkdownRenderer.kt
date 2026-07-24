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
        fontSize = ChatMarkdownTextStyle.BODY_FONT_SIZE_SP.sp,
        lineHeight = ChatMarkdownTextStyle.BODY_LINE_HEIGHT_SP.sp,
        fontWeight = FontWeight.Normal,
    )
    val markdownLinkColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        MARKDOWN_LINK_DARK_COLOR
    } else {
        MARKDOWN_LINK_LIGHT_COLOR
    }
    val typography = markdownTypography(
        h1 = bodyStyle.copy(
            fontSize = ChatMarkdownTextStyle.headingFontSizeSp(1).sp,
            lineHeight = ChatMarkdownTextStyle.headingLineHeightSp(1).sp,
            fontWeight = FontWeight.Medium,
        ),
        h2 = bodyStyle.copy(
            fontSize = ChatMarkdownTextStyle.headingFontSizeSp(2).sp,
            lineHeight = ChatMarkdownTextStyle.headingLineHeightSp(2).sp,
            fontWeight = FontWeight.Medium,
        ),
        h3 = bodyStyle.copy(
            fontSize = ChatMarkdownTextStyle.headingFontSizeSp(3).sp,
            lineHeight = ChatMarkdownTextStyle.headingLineHeightSp(3).sp,
            fontWeight = FontWeight.Medium,
        ),
        h4 = bodyStyle.copy(
            fontSize = ChatMarkdownTextStyle.headingFontSizeSp(4).sp,
            lineHeight = ChatMarkdownTextStyle.headingLineHeightSp(4).sp,
            fontWeight = FontWeight.Medium,
        ),
        h5 = bodyStyle.copy(
            fontSize = ChatMarkdownTextStyle.headingFontSizeSp(5).sp,
            lineHeight = ChatMarkdownTextStyle.headingLineHeightSp(5).sp,
            fontWeight = FontWeight.Medium,
        ),
        h6 = bodyStyle.copy(
            fontSize = ChatMarkdownTextStyle.headingFontSizeSp(6).sp,
            lineHeight = ChatMarkdownTextStyle.headingLineHeightSp(6).sp,
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
            fontSize = 13.sp,
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
        block = ChatMarkdownTextStyle.SPACING_PARAGRAPH_DP.dp,
        list = ChatMarkdownTextStyle.LIST_TOP_LEVEL_ITEM_SPACING_DP.dp,
        listItemTop = 8.dp,
        listItemBottom = 8.dp,
        listIndent = ChatMarkdownTextStyle.LIST_NESTED_INDENT_DP.dp,
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
    val selectedFormulaMarkdown = remember(
        preparedMarkdownDocument?.state?.content,
        markdownNode,
        markdownNodes,
    ) {
        val selectedNodes = markdownNodes ?: markdownNode?.let(::listOf)
        if (preparedMarkdownDocument == null || selectedNodes == null) {
            null
        } else {
            markdownNodesSourceOrNull(
                content = preparedMarkdownDocument.state.content,
                nodes = selectedNodes,
            )
        }
    }
    val validFormulas = remember(
        visiblePreparedMessage.markdown,
        visiblePreparedMessage.formulas,
        visiblePreparedMessage.details,
        visiblePreparedMessage.contentVersion,
        selectedFormulaMarkdown,
    ) {
        resolveVisibleFormulaRequests(
            preparedMessage = visiblePreparedMessage,
            visibleMarkdown = selectedFormulaMarkdown ?: visiblePreparedMessage.markdown,
        )
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
            val linkLogoIndex = remember(
                isStreaming,
                visiblePreparedMessage.markdown.takeUnless { isStreaming },
                visiblePreparedMessage.details.takeUnless { isStreaming },
                preparedMarkdownDocument?.linkLogoIndex,
            ) {
                resolveMarkdownLinkLogoIndex(
                    isStreaming = isStreaming,
                    preparedMessage = visiblePreparedMessage,
                    preparedMarkdownDocument = preparedMarkdownDocument,
                )
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
                horizontalRule = {
                    // 相邻空行由节点渲染器收口，分隔线自身提供完全对称的上下留白。
                    MarkdownDivider(
                        modifier = Modifier.padding(
                            top = LocalMarkdownHorizontalRuleTopPadding.current,
                            bottom = ChatMarkdownTextStyle.HORIZONTAL_RULE_VERTICAL_PADDING_DP.dp,
                        ),
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
                                        flavour = EveryTalkMarkdownFlavourDescriptor,
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
                    val selectedNodeStartIndex = markdownNodeStartIndex(
                        contextNodes = preparedMarkdownDocument.nodes,
                        selectedNodes = selectedMarkdownNodes,
                    )
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
                                contextNodes = preparedMarkdownDocument.nodes,
                                firstNodeIndex = selectedNodeStartIndex,
                            )
                        },
                    )
                } else {
                    Markdown(
                        content = visiblePreparedMessage.markdown,
                        colors = markdownColors,
                        typography = typography,
                        padding = padding,
                        flavour = EveryTalkMarkdownFlavourDescriptor,
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
                                nodes = state.node.children,
                            )
                        },
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
                                success = { snapshot, nodeComponents, nodeModifier ->
                                    MarkdownStreamingNodesSuccess(
                                        snapshot = snapshot,
                                        components = nodeComponents,
                                        modifier = nodeModifier,
                                        streamingContent = visibleStreamingMarkdownState.content.toString(),
                                    )
                                },
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
