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


data class MarkdownLinkLogoRequest(
    val host: String,
    val faviconUrl: String,
)

data class MarkdownLinkLogoIndex(
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

internal fun Modifier.markdownWidth(sender: Sender): Modifier = if (shouldFillMarkdownWidth(sender)) {
    fillMaxWidth()
} else {
    wrapContentWidth()
}

