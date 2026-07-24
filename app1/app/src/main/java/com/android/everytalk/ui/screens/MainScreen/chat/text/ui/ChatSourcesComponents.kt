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

private fun pageSourceFaviconUrl(href: String): String {
    return linkFaviconUrl(href)
}

private fun pageSourceInitial(source: WebSearchResult): String {
    return linkFaviconInitial(source.href, fallback = source.title)
}

@Composable
internal fun PageSourcesButton(
    pageSources: List<WebSearchResult>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDarkTheme = isSystemInDarkTheme()
    val buttonColor = if (isDarkTheme) {
        Color(0xFF2B2B2D)
    } else {
        Color(0xFFF1F1EF)
    }
    Surface(
        onClick = onClick,
        modifier = modifier.wrapContentWidth(align = Alignment.Start),
        shape = RoundedCornerShape(24.dp),
        color = buttonColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PageSourceIconStack(
                pageSources = pageSources
            )
            Text(
                text = "${pageSources.size} 页面",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun PageSourceIconStack(
    pageSources: List<WebSearchResult>,
) {
    val icons = pageSources.take(3)
    if (icons.isEmpty()) return

    val iconSize = 24.dp
    val overlapOffset = 14.dp
    val stackWidth = (24 + 14 * (icons.size - 1)).dp

    Box(
        modifier = Modifier
            .width(stackWidth)
            .height(iconSize)
    ) {
        icons.forEachIndexed { index, source ->
            Box(
                modifier = Modifier
                    .offset(x = overlapOffset * index)
                    .size(iconSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = pageSourceInitial(source),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val faviconUrl = pageSourceFaviconUrl(source.href)
                if (faviconUrl.isNotBlank()) {
                    AsyncImage(
                        model = faviconUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(iconSize)
                            .clip(CircleShape)
                    )
                }
            }
        }
    }
}

@Composable
internal fun StaticAiMessageSourcesItem(
    item: ChatListItem.AiMessageSources,
    maxWidth: Dp,
    viewModel: AppViewModel,
) {
    val aiReplyMessageDescription = stringResource(id = R.string.ai_reply_message)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .semantics { contentDescription = aiReplyMessageDescription },
            shape = RectangleShape,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 0.dp,
        ) {
            PageSourcesButton(
                pageSources = item.pageSources,
                onClick = { viewModel.showSourcesDialog(item.pageSources) },
                modifier = Modifier.padding(
                    start = ChatMarkdownTextStyle.ASSISTANT_CONTENT_START_PADDING_DP.dp,
                    top = ChatMarkdownTextStyle.ASSISTANT_CONTENT_TOP_PADDING_DP.dp,
                    bottom = 6.dp,
                ),
            )
        }
    }
}

@Composable
internal fun StaticAiMarkdownNodeItem(
    item: ChatListItem.AiMarkdownNode,
    maxWidth: Dp,
    viewModel: AppViewModel,
    footnoteNavigationState: FootnoteNavigationState,
    onImageClick: ((String) -> Unit)?,
) {
    val aiReplyMessageDescription = stringResource(id = R.string.ai_reply_message)
    var previewCode by remember { mutableStateOf<String?>(null) }
    var previewLanguage by remember { mutableStateOf("text") }

    previewCode?.let { code ->
        FullScreenCodeViewerDialog(
            code = code,
            language = previewLanguage,
            onDismiss = {
                previewCode = null
                previewLanguage = "text"
            },
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .semantics { contentDescription = aiReplyMessageDescription },
            shape = RectangleShape,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 0.dp,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = ChatMarkdownTextStyle.ASSISTANT_CONTENT_START_PADDING_DP.dp,
                        top = if (item.isFirstNode) {
                            ChatMarkdownTextStyle.ASSISTANT_CONTENT_TOP_PADDING_DP.dp
                        } else {
                            0.dp
                        },
                        end = ChatMarkdownTextStyle.ASSISTANT_CONTENT_END_PADDING_DP.dp,
                        bottom = if (item.isLastNode) {
                            ChatMarkdownTextStyle.ASSISTANT_CONTENT_BOTTOM_PADDING_DP.dp
                        } else {
                            0.dp
                        },
                    )
            ) {
                UnifiedMarkdownNodesRenderer(
                    preparedMessage = item.preparedMessage,
                    preparedMarkdownDocument = item.preparedMarkdownDocument,
                    nodes = item.nodes,
                    sender = item.message.sender,
                    onCodePreviewRequested = { language, code ->
                        previewLanguage = language
                        previewCode = code
                    },
                    onCodeCopied = {
                        viewModel.showSnackbar("已复制代码")
                    },
                    onImageClick = onImageClick,
                    footnoteNavigationState = footnoteNavigationState,
                )
            }
        }
    }
}


