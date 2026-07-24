@file:OptIn(ExperimentalFoundationApi::class)
package com.android.everytalk.ui.screens.MainScreen.chat.text.ui
import com.android.everytalk.statecontroller.*
import android.annotation.SuppressLint
import com.android.everytalk.R
import androidx.compose.ui.res.painterResource

import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.statecontroller.freezeWhileStreamingPaused
import com.android.everytalk.ui.screens.BubbleMain.Main.AttachmentsContent
import com.android.everytalk.ui.screens.BubbleMain.Main.ReasoningToggleAndContent
import com.android.everytalk.ui.screens.BubbleMain.Main.UserOrErrorMessageContent
import com.android.everytalk.ui.screens.BubbleMain.Main.resolveUserBubbleMaxHeightDp
import com.android.everytalk.ui.screens.BubbleMain.Main.MessageContextMenu
import com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem
import com.android.everytalk.ui.screens.MainScreen.chat.core.PlaceholderRole
import com.android.everytalk.ui.screens.MainScreen.chat.models.sortModelConfigs
import com.android.everytalk.ui.screens.MainScreen.chat.text.state.ChatScrollStateManager
import com.android.everytalk.ui.theme.ChatDimensions
import com.android.everytalk.ui.theme.chatColors

import com.android.everytalk.ui.components.ChatMarkdownTextStyle
import com.android.everytalk.ui.components.FullScreenCodeViewerDialog
import com.android.everytalk.ui.components.WebMarkdownSourcesExtractor
import com.android.everytalk.ui.components.everyTalkLoadingElapsedText
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.components.dialog.appDialogSubtextColor
import com.android.everytalk.ui.components.scrollFadeEdge
import com.android.everytalk.ui.components.markdown.FootnoteNavigationState
import com.android.everytalk.ui.components.streaming.PreparedMessage
import com.android.everytalk.ui.components.streaming.StreamBlock
import com.android.everytalk.ui.components.streaming.MathBlockState
import com.android.everytalk.ui.components.streaming.StreamBlockParser
import com.android.everytalk.ui.components.streaming.UnifiedMarkdownRenderer
import com.android.everytalk.ui.components.streaming.UnifiedMarkdownNodesRenderer
import com.android.everytalk.ui.components.streaming.buildStreamingRenderState
import com.android.everytalk.ui.components.streaming.contentVersionForRendering
import com.android.everytalk.ui.topanchor.RunTopAnchorReserveEngine
import com.android.everytalk.ui.topanchor.TopAnchorConfig
import com.android.everytalk.ui.topanchor.TopAnchorReserveEngineState
import com.android.everytalk.ui.topanchor.appendTopAnchorReserve
import com.android.everytalk.ui.topanchor.mapChatItemsToTopAnchorItems
import com.android.everytalk.ui.topanchor.resolveActiveTopAnchorTurn
import com.android.everytalk.ui.topanchor.resolveTopAnchorResponseTargetId
import com.android.everytalk.util.message.prepareTextForExternalTransfer
import com.android.everytalk.util.web.linkFaviconInitial
import com.android.everytalk.util.web.linkFaviconUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import coil3.compose.AsyncImage

internal fun resolveLoadingStageDisplayText(text: String?): String {
    return text?.takeIf { it.isNotBlank() }.orEmpty()
}

internal data class LoadingStageDisplayParts(
    val label: String,
    val elapsed: String?
)

internal fun splitLoadingStageDisplayText(text: String): LoadingStageDisplayParts {
    val normalized = text.trim()
    val match = Regex("^(.*)\\s+·\\s+(\\d+s)$").matchEntire(normalized)
    return if (match != null) {
        LoadingStageDisplayParts(
            label = match.groupValues[1],
            elapsed = match.groupValues[2]
        )
    } else {
        LoadingStageDisplayParts(
            label = normalized,
            elapsed = null
        )
    }
}

internal fun loadingStageElapsedText(elapsedMs: Long): String =
    everyTalkLoadingElapsedText(elapsedMs)

internal fun loadingStageViewportHeightDp(): Float = 34f

internal fun loadingStageMaskHeightDp(): Float = 10f

internal fun loadingStageBreathingDotSizeDp(): Float = 6f

internal fun loadingStageDotColor(isLightTheme: Boolean): Color {
    return if (isLightTheme) Color.Black else Color.White
}

internal fun loadingStageBackgroundColor(): Color = Color.Transparent

@Composable
internal fun LoadingStageIndicator(
    text: String,
    modifier: Modifier = Modifier,
) {
    val displayParts = remember(text) { splitLoadingStageDisplayText(text) }
    val stageStartedAt = remember(text) { SystemClock.elapsedRealtime() }
    var liveElapsedMs by remember(text) { mutableLongStateOf(0L) }
    if (displayParts.elapsed == null && displayParts.label.isNotBlank()) {
        LaunchedEffect(text) {
            while (true) {
                liveElapsedMs = SystemClock.elapsedRealtime() - stageStartedAt
                delay(1000L)
            }
        }
    }
    val elapsed = displayParts.elapsed
        ?: displayParts.label.takeIf { it.isNotBlank() }?.let {
            loadingStageElapsedText(liveElapsedMs)
        }
    val viewportHeight = loadingStageViewportHeightDp().dp
    val maskHeight = loadingStageMaskHeightDp().dp
    val breathingDotSize = loadingStageBreathingDotSizeDp().dp
    val dotColor = loadingStageDotColor(isLightTheme = !isSystemInDarkTheme())
    val textStyle = MaterialTheme.typography.bodySmall
    val textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f)
    val infiniteTransition = rememberInfiniteTransition(label = "loadingStage")
    val lineAlpha by infiniteTransition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.52f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "loadingStageLineAlpha",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(viewportHeight)
            .background(loadingStageBackgroundColor()),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(start = 6.dp)
                .size(breathingDotSize)
                .background(
                    color = dotColor.copy(alpha = lineAlpha),
                    shape = CircleShape
                )
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(viewportHeight)
                    .clipToBounds()
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth()
                        .padding(vertical = maskHeight)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = displayParts.label,
                            style = textStyle,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (elapsed == null) {
                                Modifier.fillMaxWidth()
                            } else {
                                Modifier.weight(1f, fill = false)
                            },
                        )
                        elapsed?.let { elapsed ->
                            Text(
                                text = " · ",
                                style = textStyle,
                                color = textColor,
                                maxLines = 1,
                            )
                            AnimatedContent(
                                targetState = elapsed,
                                transitionSpec = {
                                    (slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(animationSpec = tween(260)) + scaleIn(initialScale = 0.985f, animationSpec = tween(260)))
                                        .togetherWith(
                                            slideOutVertically(targetOffsetY = { -it / 2 }) + fadeOut(animationSpec = tween(220)) + scaleOut(targetScale = 0.985f, animationSpec = tween(220))
                                        )
                                },
                                label = "LoadingStageTimeAnimation"
                            ) { elapsedText ->
                                Text(
                                    text = elapsedText,
                                    style = textStyle,
                                    color = textColor,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

