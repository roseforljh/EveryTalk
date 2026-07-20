@file:OptIn(ExperimentalFoundationApi::class)
package com.android.everytalk.ui.screens.MainScreen.chat.text.ui
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
import com.android.everytalk.ui.theme.ChatDimensions
import com.android.everytalk.ui.theme.chatColors

import com.android.everytalk.ui.components.ChatMarkdownTextStyle
import com.android.everytalk.ui.components.FullScreenCodeViewerDialog
import com.android.everytalk.ui.components.WebMarkdownSourcesExtractor
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.components.dialog.appDialogSubtextColor
import com.android.everytalk.ui.components.scrollFadeEdge
import com.android.everytalk.ui.components.streaming.StreamBlock
import com.android.everytalk.ui.components.streaming.MathBlockState
import com.android.everytalk.ui.components.streaming.StreamBlockParser
import com.android.everytalk.ui.components.streaming.UnifiedMarkdownRenderer
import com.android.everytalk.ui.components.streaming.buildStreamingRenderState
import com.android.everytalk.ui.components.streaming.contentVersionForRendering
import com.android.everytalk.ui.topanchor.RunTopAnchorReserveEngine
import com.android.everytalk.ui.topanchor.TopAnchorConfig
import com.android.everytalk.ui.topanchor.TopAnchorPhase
import com.android.everytalk.ui.topanchor.TopAnchorReserveEngineState
import com.android.everytalk.ui.topanchor.mapChatItemsToTopAnchorItems
import com.android.everytalk.ui.topanchor.resolveActiveTopAnchorTurn
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import coil3.compose.AsyncImage
import java.net.URI

internal fun retainedStreamingHeightPx(
    naturalHeightPx: Int,
    cachedHeightPx: Int,
    isStreaming: Boolean,
): Int = if (isStreaming) maxOf(naturalHeightPx, cachedHeightPx) else naturalHeightPx

private fun Modifier.retainGrowingHeightWhileStreaming(
    isStreaming: Boolean,
    heightCachePx: IntArray,
): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val retainedHeight = retainedStreamingHeightPx(
        naturalHeightPx = placeable.height,
        cachedHeightPx = heightCachePx[0],
        isStreaming = isStreaming,
    ).coerceIn(constraints.minHeight, constraints.maxHeight)
    heightCachePx[0] = if (isStreaming) retainedHeight else 0
    layout(placeable.width, retainedHeight) {
        placeable.placeRelative(0, 0)
    }
}

internal fun shouldBuildSourceStrippedRenderBlocks(
    messageOutputType: String,
    extractedSourceCount: Int,
    effectiveContent: String,
    displayContent: String,
): Boolean {
    return messageOutputType != "code" &&
        extractedSourceCount > 0 &&
        displayContent.isNotBlank() &&
        displayContent != effectiveContent
}

internal fun shouldBuildLocalRenderBlocks(
    messageOutputType: String,
    displayContent: String,
    hasUpstreamBlocks: Boolean,
    hasStreamingBlocks: Boolean,
    hasSourceStrippedBlocks: Boolean,
    hasExtractedSources: Boolean,
): Boolean {
    return displayContent.isNotBlank() &&
        !hasUpstreamBlocks &&
        !hasStreamingBlocks &&
        !hasSourceStrippedBlocks &&
        !hasExtractedSources
}

@Composable
private fun rememberHistoryLoadingShimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "historyLoadingShimmer")
    val shimmerOffset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "historyLoadingShimmerOffset",
    )
    val baseColor = if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    } else {
        Color(0xFFDFDFE2)
    }
    val highlightColor = if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    } else {
        baseColor.copy(alpha = 0.20f)
    }
    return Brush.linearGradient(
        colors = listOf(baseColor, highlightColor, baseColor),
        start = Offset(shimmerOffset - 3000f, 0f),
        end = Offset(shimmerOffset, 0f),
    )
}

@Composable
internal fun HistoryLoadingBubblePlaceholderItem(
    role: PlaceholderRole,
    widthFraction: Float,
    estimatedHeight: Dp,
) {
    val shimmerBrush = rememberHistoryLoadingShimmerBrush()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (role == PlaceholderRole.User) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(widthFraction.coerceIn(0.1f, 1f))
                .height(estimatedHeight)
                .clip(RoundedCornerShape(18.dp))
                .background(shimmerBrush),
        )
    }
}

@Composable
fun ChatMessagesList(
    chatItems: List<ChatListItem>,
    viewModel: AppViewModel,
    listState: LazyListState,
    scrollStateManager: com.android.everytalk.ui.screens.MainScreen.chat.text.state.ChatScrollStateManager,
    scrollSessionKey: String,
    conversationId: String = scrollSessionKey,
    bubbleMaxWidth: Dp,
    onShowAiMessageOptions: (Message) -> Unit,
    onImageLoaded: () -> Unit,
    onImageClick: (String) -> Unit,
    additionalBottomPadding: Dp = 0.dp
) {
    val reasoningHeightMap = remember { mutableMapOf<String, Int>() }
    // 防止 AnimatedItems 等状态在重组时被重复触发

    var isContextMenuVisible by remember { mutableStateOf(false) }
    var contextMenuMessage by remember { mutableStateOf<Message?>(null) }
    var contextMenuPressOffset by remember { mutableStateOf(Offset.Zero) }
    // 防重复触发：在极短时间内只允许一次预览弹出
    var lastImagePreviewAt by remember { mutableStateOf(0L) }

    val pauseAwareApiCalling = remember(viewModel) {
        viewModel.isTextApiCalling.freezeWhileStreamingPaused(viewModel.isStreamingPaused)
    }
    val pauseAwareStreamingId = remember(viewModel) {
        viewModel.currentTextStreamingAiMessageId.freezeWhileStreamingPaused(viewModel.isStreamingPaused)
    }
    val isApiCalling by pauseAwareApiCalling.collectAsState(initial = viewModel.isTextApiCalling.value)
    val currentStreamingId by pauseAwareStreamingId.collectAsState(
        initial = viewModel.currentTextStreamingAiMessageId.value
    )
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val pinnedUserBubbleMaxHeightPx = with(density) {
        resolveUserBubbleMaxHeightDp(
            screenHeightDp = configuration.screenHeightDp.toFloat(),
            isExpanded = false,
        ).dp.toPx().toInt()
    }
    
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topPadding = statusBarTop + 72.dp
    val topPaddingPx = with(density) { topPadding.toPx().toInt() }
    val topAnchorEngine = remember(scrollSessionKey) { TopAnchorReserveEngineState() }
    val lastSentUserMessageId by viewModel.lastSentUserMessageId.collectAsState()
    val topAnchorItems = remember(chatItems) {
        mapChatItemsToTopAnchorItems(
            items = chatItems,
            resolveErrorSender = { messageId -> viewModel.getMessageById(messageId)?.sender }
        )
    }
    val activeTurn = remember(
        topAnchorItems,
        lastSentUserMessageId,
        scrollSessionKey,
        chatItems.size,
    ) {
        resolveActiveTopAnchorTurn(
            items = topAnchorItems,
            sentUserMessageId = lastSentUserMessageId,
            sessionKey = scrollSessionKey,
            generation = chatItems.size.toLong(),
        )
    }
    val engineTurn = topAnchorEngine.runtime.currentTurn
    val engineAnchorInfo = remember(chatItems, engineTurn) {
        val turn = engineTurn ?: return@remember null
        chatItems.mapIndexedNotNull { index, item ->
            if (item.stableId == turn.anchorMessageId) index to item.stableId else null
        }.firstOrNull()
    }
    val engineReserveDp = with(density) { topAnchorEngine.reservePx.toDp() }

    LaunchedEffect(scrollSessionKey) {
        topAnchorEngine.clearRuntime()
        scrollStateManager.updateTopAnchorBottomScrollSuppression(false)
    }

    DisposableEffect(scrollStateManager, topAnchorEngine) {
        scrollStateManager.setTopAnchorRuntimeClearer(topAnchorEngine::clearRuntime)
        scrollStateManager.setTopAnchorUserScrollReleaser(topAnchorEngine::releaseForUserScroll)
        onDispose {
            scrollStateManager.setTopAnchorRuntimeClearer(null)
            scrollStateManager.setTopAnchorUserScrollReleaser(null)
        }
    }

    LaunchedEffect(topAnchorEngine.runtime.suppressesBottomScroll) {
        scrollStateManager.updateTopAnchorBottomScrollSuppression(
            topAnchorEngine.runtime.suppressesBottomScroll
        )
    }

    LaunchedEffect(activeTurn?.anchorMessageId, activeTurn?.targetItemId, activeTurn?.generation) {
        val turn = activeTurn ?: return@LaunchedEffect
        topAnchorEngine.updateRuntime(
            topAnchorEngine.runtime.copy(
                phase = TopAnchorPhase.InitialSnap,
                activeTurn = turn,
                retainedTurn = null
            )
        )
        viewModel.consumeLastSentUserMessageId(turn.anchorMessageId)
    }

    LaunchedEffect(engineTurn, engineAnchorInfo) {
        if (
            engineTurn != null &&
            engineAnchorInfo == null &&
            topAnchorEngine.runtime.currentTurn == engineTurn
        ) {
            topAnchorEngine.clearRuntime()
        }
    }

    val fadeBackgroundColor = MaterialTheme.colorScheme.background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .scrollFadeEdge(listState = listState, backgroundColor = fadeBackgroundColor)
    ) {
            engineAnchorInfo?.let { (anchorIndex, anchorKey) ->
                RunTopAnchorReserveEngine(
                    state = topAnchorEngine,
                    listState = listState,
                    anchorIndex = anchorIndex,
                    anchorKey = anchorKey,
                    targetAnchorY = topPaddingPx,
                    trailingRealItemIndex = chatItems.lastIndex,
                    isRunning = isApiCalling,
                    config = TopAnchorConfig(
                        tallAnchorThresholdPx = pinnedUserBubbleMaxHeightPx,
                        tallAnchorVisibleHeightPx = with(density) { 96.dp.toPx().toInt() },
                        topInsetPx = topPaddingPx,
                        stableWindowNanos = 50_000_000L,
                        keepReserveAfterRunEnd = true,
                    ),
                    enabled = topAnchorEngine.runtime.hasRuntime
                )
            }
            
            // 前端消息列表渲染。
            // LazyColumn 只渲染屏幕附近的消息，适合长对话；listState 负责记录滚动位置。
            LazyColumn(
                state = listState,
                reverseLayout = false,
                userScrollEnabled = topAnchorEngine.userScrollEnabled,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollStateManager.nestedScrollConnection),
                contentPadding = PaddingValues(
                    start = ChatDimensions.HORIZONTAL_PADDING,
                    end = ChatDimensions.HORIZONTAL_PADDING,
                    top = topPadding,
                    bottom = additionalBottomPadding + 12.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(
            items = chatItems,
            key = { _, item -> item.stableId },
            contentType = { _, item -> 
                when (item) {
                    // stableId 和 contentType 用来稳定复用消息气泡，流式回复时界面不会频繁重建。
                    // Merge all AI message types into a single contentType to prevent
                    // item recreation when switching between Streaming/Non-Streaming states.
                    // This allows the inner Composable to handle state transitions smoothly.
                    is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage,
                    is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageStreaming,
                    is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCode,
                    is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCodeStreaming -> "AiMessage"
                    else -> item::class.java.simpleName
                }
            }
        ) { index, item ->
            // 判断是否为用户消息，用于决定布局和对齐方式
            val isUserMessage = item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.UserMessage ||
                (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.ErrorMessage &&
                 viewModel.getMessageById((item as com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.ErrorMessage).messageId)?.sender == com.android.everytalk.data.DataClass.Sender.User)
            
                    // 根据消息类型决定对齐方式和气泡布局
            val itemAlignment = when (item) {
                is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.UserMessage -> Alignment.CenterEnd
                is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.ErrorMessage -> {
                    val message = viewModel.getMessageById(item.messageId)
                    if (message?.sender == com.android.everytalk.data.DataClass.Sender.User) {
                        Alignment.CenterEnd
                    } else {
                        Alignment.CenterStart
                    }
                }
                else -> Alignment.CenterStart
            }

            Box(
                modifier = if (isUserMessage) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.fillMaxWidth()
                },
                contentAlignment = itemAlignment
            ) {

                    // 根据消息类型渲染不同内容；这里不用 Column 包裹以避免额外布局抖动
                    // 前端消息列表渲染：这里按消息类型分发到不同气泡组件。
                    // 用户消息靠右，AI 和系统消息靠左，附件、思考过程、正文等内容在各自分支里渲染。
                    when (item) {
                        is ChatListItem.LoadingBubblePlaceholder -> {
                            HistoryLoadingBubblePlaceholderItem(
                                role = item.role,
                                widthFraction = item.widthFraction,
                                estimatedHeight = item.estimatedHeightDp.dp,
                            )
                        }

                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.UserMessage -> {
                            // 使用 Row + Arrangement.End，确保用户消息稳定靠右显示
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(
                                    modifier = Modifier.wrapContentWidth(),
                                    horizontalAlignment = Alignment.End
                                ) {
                                    val message = viewModel.getMessageById(item.messageId)
                                    if (message != null) {
                                        if (!item.attachments.isNullOrEmpty()) {
                                            AttachmentsContent(
                                                attachments = item.attachments,
                                                onAttachmentClick = { },
                                                maxWidth = bubbleMaxWidth * ChatDimensions.USER_BUBBLE_WIDTH_RATIO,
                                                message = message,
                                                onEditRequest = { viewModel.requestEditMessage(it) },
                                                onRegenerateRequest = { regeneratedMessage ->
                                                    scrollStateManager.lockAutoScroll()
                                                    viewModel.regenerateAiResponse(regeneratedMessage, isImageGeneration = false, scrollToNewMessage = true)
                                                },
                                                onLongPress = { msg, offset ->
                                                    contextMenuMessage = msg
                                                    contextMenuPressOffset = offset
                                                    isContextMenuVisible = true
                                                },
                                                scrollStateManager = scrollStateManager,
                                                onImageLoaded = onImageLoaded,
                                                bubbleColor = MaterialTheme.chatColors.userBubble,
                                                isAiGenerated = false,
                                                onImageClick = onImageClick
                                            )
                                        }
                                        if (item.text.isNotBlank()) {
                                            // 纯文本用户消息内容
                                            UserOrErrorMessageContent(
                                                message = message,
                                                displayedText = item.text,
                                                showLoadingDots = false,
                                                bubbleColor = MaterialTheme.chatColors.userBubble,
                                                contentColor = MaterialTheme.colorScheme.onSurface,
                                                isError = false,
                                                maxWidth = bubbleMaxWidth * ChatDimensions.USER_BUBBLE_WIDTH_RATIO,
                                                onLongPress = { msg, offset ->
                                                    contextMenuMessage = msg
                                                    contextMenuPressOffset = offset
                                                    isContextMenuVisible = true
                                                },
                                                scrollStateManager = scrollStateManager
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.SystemMessage -> {
                            val message = viewModel.getMessageById(item.messageId)
                            if (message != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Start,
                                    verticalAlignment = Alignment.Top
                                ) {
                                    UserOrErrorMessageContent(
                                        message = message,
                                        displayedText = item.text,
                                        showLoadingDots = false,
                                        bubbleColor = MaterialTheme.chatColors.aiBubble,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                        isError = false,
                                        maxWidth = bubbleMaxWidth,
                                        onLongPress = { msg, offset ->
                                            contextMenuMessage = msg
                                            contextMenuPressOffset = offset
                                            isContextMenuVisible = true
                                        },
                                        scrollStateManager = scrollStateManager
                                    )
                                }
                            }
                        }

                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageReasoning -> {
                            val reasoningCompleteMap = viewModel.textReasoningCompleteMap
                            val streamingReasoningSource = remember(item.message.id, viewModel) {
                                viewModel.getStreamingReasoning(item.message.id)
                            }
                            val pauseAwareReasoning = remember(streamingReasoningSource, viewModel) {
                                streamingReasoningSource.freezeWhileStreamingPaused(viewModel.isStreamingPaused)
                            }
                            val streamingReasoning by pauseAwareReasoning.collectAsState(
                                initial = streamingReasoningSource.value.ifBlank { item.message.reasoning ?: "" }
                            )
                            val displayedReasoningText =
                                if (currentStreamingId == item.message.id && streamingReasoning.isNotBlank()) {
                                    streamingReasoning
                                } else {
                                    item.message.reasoning ?: ""
                                }
                            val isReasoningStreaming = remember(
                                displayedReasoningText,
                                reasoningCompleteMap[item.message.id],
                                item.message.contentStarted
                            ) {
                                displayedReasoningText.isNotBlank() &&
                                (reasoningCompleteMap[item.message.id] != true) &&
                                !item.message.contentStarted
                            }
                            val isReasoningComplete = reasoningCompleteMap[item.message.id] ?: false

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                ReasoningToggleAndContent(
                                    modifier = Modifier.fillMaxWidth(),
                                    currentMessageId = item.message.id,
                                    displayedReasoningText = displayedReasoningText,
                                    isReasoningStreaming = isReasoningStreaming,
                                    isReasoningComplete = isReasoningComplete,
                                    messageIsError = item.message.isError,
                                    mainContentHasStarted = item.message.contentStarted,
                                    reasoningTextColor = MaterialTheme.chatColors.reasoningText,
                                    reasoningToggleDotColor = MaterialTheme.colorScheme.onSurface,
                                    onVisibilityChanged = { }
                                )
                            }
                        }

                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage, is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageStreaming -> {
                            val messageId = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage) item.messageId else (item as com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageStreaming).messageId
                            val message = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage) item.message else viewModel.getMessageById(messageId)
                            if (message != null) {
                                val text = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage) item.text else message.text
                                val isStreaming = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageStreaming) true else (currentStreamingId == message.id)

                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    AiMessageItem(
                                        message = message,
                                        text = text,
                                        blocks = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage) {
                                            item.blocks
                                        } else {
                                            emptyList()
                                        },
                                        maxWidth = bubbleMaxWidth,
                                        isStreaming = isStreaming,
                                        messageOutputType = message.outputType,
                                        viewModel = viewModel,
                                        onImageClick = { url ->
                                            val now = SystemClock.elapsedRealtime()
                                            if (now - lastImagePreviewAt > 500) {
                                                lastImagePreviewAt = now
                                                onImageClick(url)
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCode, is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCodeStreaming -> {
                            val messageId = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCode) item.messageId else (item as com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCodeStreaming).messageId
                            val message = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCode) item.message else viewModel.getMessageById(messageId)
                            if (message != null) {
                                val text = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCode) item.text else message.text
                                val isStreaming = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCodeStreaming) true else (currentStreamingId == message.id)

                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    AiMessageItem(
                                        message = message,
                                        text = text,
                                        maxWidth = bubbleMaxWidth,
                                        isStreaming = isStreaming,
                                        messageOutputType = message.outputType,
                                        viewModel = viewModel,
                                        onImageClick = { url ->
                                            val now = SystemClock.elapsedRealtime()
                                            if (now - lastImagePreviewAt > 500) {
                                                lastImagePreviewAt = now
                                                onImageClick(url)
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageFooter -> {
                            AiMessageFooterItem(
                                message = item.message,
                                viewModel = viewModel,
                            )
                        }


                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.ErrorMessage -> {
                            val message = viewModel.getMessageById(item.messageId)
                            if (message != null) {
                                UserOrErrorMessageContent(
                                    message = message,
                                    displayedText = item.text,
                                    showLoadingDots = false,
                                    bubbleColor = MaterialTheme.chatColors.aiBubble,
                                    contentColor = MaterialTheme.chatColors.errorContent,
                                    isError = true,
                                    maxWidth = bubbleMaxWidth,
                                    onLongPress = { msg, offset ->
                                        contextMenuMessage = msg
                                        contextMenuPressOffset = offset
                                        isContextMenuVisible = true
                                    },
                                    scrollStateManager = scrollStateManager
                                )
                            }
                        }

                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.LoadingIndicator -> {
                            Row(
                                modifier = Modifier
                                    .padding(
                                        start = ChatDimensions.HORIZONTAL_PADDING,
                                        top = ChatDimensions.VERTICAL_PADDING,
                                        bottom = ChatDimensions.VERTICAL_PADDING
                                    ),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.Start
                            ) {
                                val displayText = resolveLoadingStageDisplayText(item.text)
                                LoadingStageIndicator(text = displayText)
                            }
                        }
                        
                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.StatusIndicator -> {
                            Row(
                                modifier = Modifier
                                    .padding(
                                        start = ChatDimensions.HORIZONTAL_PADDING,
                                        top = ChatDimensions.VERTICAL_PADDING,
                                        bottom = ChatDimensions.VERTICAL_PADDING
                                    ),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.Start
                            ) {
                                Text(
                                    text = item.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
                
            if (engineReserveDp > 0.dp) {
                item(key = "dynamic_padding_spacer") {
                    Spacer(modifier = Modifier.height(engineReserveDp))
                }
            }
        }

        contextMenuMessage?.let { message ->
            MessageContextMenu(
                isVisible = isContextMenuVisible,
                message = message,
                pressOffset = with(density) {
                    if (message.sender == com.android.everytalk.data.DataClass.Sender.User) {
                        // 文本模式用户气泡：进一步下移以贴近手指
                        Offset(contextMenuPressOffset.x, contextMenuPressOffset.y)
                    } else {
                        Offset(contextMenuPressOffset.x, contextMenuPressOffset.y)
                    }
                },
                onDismiss = { isContextMenuVisible = false },
                onCopy = {
                    viewModel.copyToClipboard(it.text)
                    isContextMenuVisible = false
                },
                onEdit = {
                    viewModel.requestEditMessage(it)
                    isContextMenuVisible = false
                },
                onRegenerate = { regeneratedMessage ->
                    scrollStateManager.lockAutoScroll()
                    viewModel.regenerateAiResponse(regeneratedMessage, isImageGeneration = false, scrollToNewMessage = true)
                    isContextMenuVisible = false
                }
            )
        }
    }
}

enum class ContentType {
    SIMPLE         // 普通内容，使用正常内边距
}

fun detectContentTypeForPadding(text: String): ContentType {
    // 所有内容都使用正常内边距
    return ContentType.SIMPLE
}

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
                            modifier = if (displayParts.elapsed == null) {
                                Modifier.fillMaxWidth()
                            } else {
                                Modifier.weight(1f, fill = false)
                            },
                        )
                        displayParts.elapsed?.let { elapsed ->
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

private fun pageSourceHost(href: String): String {
    return runCatching {
        URI(href).host?.removePrefix("www.") ?: ""
    }.getOrDefault("")
}

private fun pageSourceFaviconUrl(href: String): String {
    val host = pageSourceHost(href)
    return if (host.isBlank()) {
        ""
    } else {
        "https://www.google.com/s2/favicons?domain=$host&sz=64"
    }
}

private fun pageSourceInitial(source: WebSearchResult): String {
    val host = pageSourceHost(source.href)
    val raw = host.ifBlank { source.title }.trim()
    return raw.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

@Composable
private fun PageSourcesButton(
    pageSources: List<WebSearchResult>,
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
) {
    val isDarkTheme = isSystemInDarkTheme()
    val buttonColor = if (isDarkTheme) {
        Color(0xFF2B2B2D)
    } else {
        Color(0xFFF1F1EF)
    }
    Surface(
        onClick = {
            viewModel.showSourcesDialog(pageSources)
        },
        modifier = modifier,
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
fun AiMessageItem(
    message: Message,
    text: String,
    blocks: List<StreamBlock> = emptyList(),
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    isStreaming: Boolean,
    messageOutputType: String,
    viewModel: AppViewModel,
    onImageClick: ((String) -> Unit)? = null
) {
    val shape = RectangleShape
    val aiReplyMessageDescription = stringResource(id = R.string.ai_reply_message)

    var previewCode by remember { mutableStateOf<String?>(null) }
    var previewLanguage by remember { mutableStateOf("text") }

    if (previewCode != null) {
        FullScreenCodeViewerDialog(
            code = previewCode!!,
            language = previewLanguage,
            onDismiss = {
                previewCode = null
                previewLanguage = "text"
            }
        )
    }

    val streamingHeightCachePx = remember(message.id) { intArrayOf(0) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth()
                .semantics { contentDescription = aiReplyMessageDescription },
            shape = shape,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 0.dp
        ) {
            val streamingRenderStateSource = remember(message.id, viewModel) {
                viewModel.getStreamingRenderState(message.id)
            }
            val pauseAwareRenderState = remember(streamingRenderStateSource, viewModel) {
                streamingRenderStateSource.freezeWhileStreamingPaused(viewModel.isStreamingPaused)
            }
            val streamingRenderState by pauseAwareRenderState.collectAsState(
                initial = streamingRenderStateSource.value
            )

            val shouldPreferStreamingContent =
                isStreaming ||
                    streamingRenderState.isStreaming

            val effectiveContent = if (shouldPreferStreamingContent) {
                streamingRenderState.content.ifBlank { message.text }
            } else {
                // 流式结束后，优先使用 message.text；但如果 message.text 为空或明显短于
                // streamingRenderState.content，说明存在同步竞态，使用流式内容兜底防止闪烁
                if (message.text.isBlank() && streamingRenderState.content.isNotBlank()) {
                    streamingRenderState.content
                } else if (message.text.length < streamingRenderState.content.length * 0.8 && streamingRenderState.content.isNotBlank()) {
                    streamingRenderState.content
                } else {
                    message.text
                }
            }

            val renderMessage = if (effectiveContent == message.text) {
                message
            } else {
                message.copy(text = effectiveContent)
            }
            val sourcesExtraction = remember(effectiveContent) {
                WebMarkdownSourcesExtractor.extract(effectiveContent)
            }
            val pageSources = message.webSearchResults?.takeIf { it.isNotEmpty() } ?: sourcesExtraction.sources
            val displayContent = sourcesExtraction.displayText
            val displayMessage = if (displayContent == renderMessage.text) {
                renderMessage
            } else {
                renderMessage.copy(text = displayContent)
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                if (pageSources.isNotEmpty()) {
                    PageSourcesButton(
                        pageSources = pageSources,
                        viewModel = viewModel,
                        modifier = Modifier.padding(
                            start = ChatMarkdownTextStyle.ASSISTANT_CONTENT_START_PADDING_DP.dp,
                            top = ChatMarkdownTextStyle.ASSISTANT_CONTENT_TOP_PADDING_DP.dp,
                            bottom = 6.dp
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .retainGrowingHeightWhileStreaming(
                            isStreaming = isStreaming,
                            heightCachePx = streamingHeightCachePx,
                        )
                        .fillMaxWidth()
                        .padding(
                            start = ChatMarkdownTextStyle.ASSISTANT_CONTENT_START_PADDING_DP.dp,
                            top = ChatMarkdownTextStyle.ASSISTANT_CONTENT_TOP_PADDING_DP.dp,
                            end = ChatMarkdownTextStyle.ASSISTANT_CONTENT_END_PADDING_DP.dp,
                            bottom = ChatMarkdownTextStyle.ASSISTANT_CONTENT_BOTTOM_PADDING_DP.dp
                        )
                ) {

                val useStreamingBlocks =
                    sourcesExtraction.sources.isEmpty() &&
                        streamingRenderState.content == effectiveContent &&
                        streamingRenderState.blocks.isNotEmpty()

                val sourceStrippedRenderState = remember(
                    message.id,
                    displayContent,
                    effectiveContent,
                    messageOutputType,
                    sourcesExtraction.sources.size,
                    shouldPreferStreamingContent,
                ) {
                    if (shouldBuildSourceStrippedRenderBlocks(
                            messageOutputType = messageOutputType,
                            extractedSourceCount = sourcesExtraction.sources.size,
                            effectiveContent = effectiveContent,
                            displayContent = displayContent,
                        )
                    ) {
                        buildStreamingRenderState(
                            messageId = "${message.id}:sources-stripped",
                            content = displayContent,
                            isStreaming = shouldPreferStreamingContent,
                            isComplete = !shouldPreferStreamingContent,
                        )
                    } else {
                        null
                    }
                }

                val renderBlocks = when {
                    useStreamingBlocks -> streamingRenderState.blocks
                    sourceStrippedRenderState != null -> sourceStrippedRenderState.blocks
                    sourcesExtraction.sources.isNotEmpty() -> emptyList()
                    blocks.isNotEmpty() && (text == effectiveContent || message.text == effectiveContent) -> blocks
                    else -> emptyList()
                }

                val localRenderState = remember(
                    message.id,
                    displayContent,
                    messageOutputType,
                    blocks.size,
                    streamingRenderState.blocks.size,
                    sourceStrippedRenderState?.blocks?.size ?: 0,
                    sourcesExtraction.sources.size,
                ) {
                    if (shouldBuildLocalRenderBlocks(
                            messageOutputType = messageOutputType,
                            displayContent = displayContent,
                            hasUpstreamBlocks = blocks.isNotEmpty(),
                            hasStreamingBlocks = useStreamingBlocks,
                            hasSourceStrippedBlocks = sourceStrippedRenderState != null,
                            hasExtractedSources = sourcesExtraction.sources.isNotEmpty(),
                        )
                    ) {
                        buildStreamingRenderState(
                            messageId = "${message.id}:local",
                            content = displayContent,
                            isStreaming = false,
                            isComplete = true,
                        )
                    } else {
                        null
                    }
                }

                val fallbackRenderState = remember(
                    message.id,
                    displayContent,
                    renderBlocks.size,
                    localRenderState?.blocks?.size ?: 0,
                ) {
                    if (displayContent.isNotBlank() && renderBlocks.isEmpty() && localRenderState == null) {
                        buildStreamingRenderState(
                            messageId = "${message.id}:fallback",
                            content = displayContent,
                            isStreaming = shouldPreferStreamingContent,
                            isComplete = !shouldPreferStreamingContent,
                        )
                    } else {
                        null
                    }
                }

                val selectedRenderState = when {
                    useStreamingBlocks -> streamingRenderState
                    sourceStrippedRenderState != null -> sourceStrippedRenderState
                    localRenderState != null -> localRenderState
                    fallbackRenderState != null -> fallbackRenderState
                    else -> null
                }

                val effectiveRenderBlocks = renderBlocks.ifEmpty {
                    selectedRenderState?.blocks ?: emptyList()
                }

                val preparedMessage = selectedRenderState?.preparedMessage ?: remember(
                    displayMessage.text,
                    effectiveRenderBlocks,
                ) {
                    val hasPendingFormula = effectiveRenderBlocks.any { block ->
                        when (block) {
                            is StreamBlock.MathInline -> block.state != MathBlockState.RENDERED
                            is StreamBlock.MathBlock -> block.state != MathBlockState.RENDERED
                            else -> false
                        }
                    }
                    StreamBlockParser.prepareMessage(
                        content = displayMessage.text,
                        blocks = effectiveRenderBlocks,
                        hasPendingFormula = hasPendingFormula,
                        contentVersion = contentVersionForRendering(displayMessage.text),
                    )
                }

                if (effectiveRenderBlocks.isNotEmpty()) {
                    UnifiedMarkdownRenderer(
                        preparedMessage = preparedMessage,
                        sender = displayMessage.sender,
                        isStreaming = shouldPreferStreamingContent,
                        onCodePreviewRequested = { lang, code ->
                            previewLanguage = lang
                            previewCode = code
                        },
                        onCodeCopied = {
                            viewModel.showSnackbar("已复制代码")
                        },
                    )
                }
                }
            }
        }
    }
}

@Composable
fun AiMessageFooterItem(
    message: Message,
    viewModel: AppViewModel,
    onShowOptions: (Message) -> Unit = {},
) {
    var showPopupMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ChatDimensions.HORIZONTAL_PADDING)
    ) {
        Row(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    val latestMessage = viewModel.getMessageById(message.id) ?: message
                    viewModel.copyToClipboard(latestMessage.text)
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_copy),
                    contentDescription = "复制",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            IconButton(
                onClick = {
                    val latestMessage = viewModel.getMessageById(message.id) ?: message
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, latestMessage.text)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "分享"))
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_share),
                    contentDescription = "分享",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Box {
                IconButton(
                    onClick = { showPopupMenu = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_dots_horizontal),
                        contentDescription = "更多",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                AiMessagePopupMenu(
                    expanded = showPopupMenu,
                    onDismiss = { showPopupMenu = false },
                    onRegenerate = {
                        val latestMessage = viewModel.getMessageById(message.id) ?: message
                        viewModel.regenerateAiResponse(latestMessage, scrollToNewMessage = true)
                    },
                    modelName = message.modelName,
                    availableModels = viewModel.apiConfigs.collectAsState().value,
                    selectedModelId = viewModel.selectedApiConfig.collectAsState().value?.id,
                    onChangeModelConfirm = { config ->
                        val latestMessage = viewModel.getMessageById(message.id) ?: message
                        viewModel.regenerateAiResponseWithConfig(latestMessage, config, scrollToNewMessage = true)
                    },
                    onExport = {
                        val latestMessage = viewModel.getMessageById(message.id) ?: message
                        viewModel.exportMessageText(latestMessage.text)
                    }
                )
            }
        }
    }
}

@Composable
private fun AiMessagePopupMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRegenerate: () -> Unit,
    modelName: String?,
    availableModels: List<ApiConfig>,
    selectedModelId: String?,
    onChangeModelConfirm: (ApiConfig) -> Unit,
    onExport: () -> Unit,
) {
    var showPopup by remember { mutableStateOf(false) }
    val scaleAnim = remember { Animatable(0.8f) }
    val alphaAnim = remember { Animatable(0f) }

    val emphasizedDecelerate = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
    val decelerateEasing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    LaunchedEffect(expanded) {
        if (expanded) {
            showPopup = true
            scaleAnim.snapTo(0.8f)
            alphaAnim.snapTo(0f)
            coroutineScope {
                launch { scaleAnim.animateTo(1f, tween(120, easing = emphasizedDecelerate)) }
                launch { alphaAnim.animateTo(1f, tween(30, easing = decelerateEasing)) }
            }
        } else if (showPopup) {
            coroutineScope {
                launch { alphaAnim.animateTo(0f, tween(75, easing = decelerateEasing)) }
                launch {
                    delay(74)
                    scaleAnim.snapTo(0.8f)
                }
            }
            showPopup = false
        }
    }

    var showModelPicker by remember { mutableStateOf(false) }
    var pendingConfirmModel by remember { mutableStateOf<ApiConfig?>(null) }

    if (!showPopup) return

    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0xFF212121) else Color(0xFFFFFFFF)
    val textColor = MaterialTheme.colorScheme.onSurface
    val iconTint = textColor
    val borderColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color(0xFF0D0D0D).copy(alpha = 0.05f)

    Popup(
        alignment = Alignment.BottomStart,
        onDismissRequest = {
            showModelPicker = false
            onDismiss()
        },
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            modifier = Modifier
                .wrapContentWidth()
                .widthIn(min = 200.dp)
                .graphicsLayer {
                    this.scaleX = scaleAnim.value
                    this.scaleY = scaleAnim.value
                    this.alpha = alphaAnim.value
                    this.transformOrigin = TransformOrigin(0f, 1f)
                }
                .shadow(8.dp, RoundedCornerShape(28.dp))
                .border(1.dp, borderColor, RoundedCornerShape(28.dp)),
            shape = RoundedCornerShape(28.dp),
            color = cardBg
        ) {
            if (showModelPicker) {
                ModelPickerPopupContent(
                    availableModels = availableModels,
                    selectedModelId = selectedModelId,
                    textColor = textColor,
                    iconTint = iconTint,
                    onModelSelected = { pendingConfirmModel = it }
                )
            } else {
                Column(modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .padding(vertical = 12.dp)
                ) {
                    PopupMenuItem(
                        painter = painterResource(R.drawable.ic_regenerate),
                        text = "重新回答",
                        textColor = textColor,
                        iconTint = iconTint,
                        onClick = { onRegenerate(); onDismiss() }
                    )
                    PopupMenuItem(
                        painter = painterResource(R.drawable.ic_robot_head),
                        text = modelName ?: "切换模型",
                        textColor = textColor,
                        iconTint = iconTint,
                        onClick = { showModelPicker = true }
                    )
                    PopupMenuItem(
                        painter = painterResource(R.drawable.ic_export),
                        text = "导出文本",
                        textColor = textColor,
                        iconTint = iconTint,
                        onClick = { onExport(); onDismiss() }
                    )
                }
            }
        }
    }

    pendingConfirmModel?.let { config ->
        ConfirmModelRegenerateDialog(
            modelName = config.name.takeIf { it.isNotBlank() } ?: config.model,
            onBack = { pendingConfirmModel = null },
            onConfirm = {
                pendingConfirmModel = null
                showModelPicker = false
                onChangeModelConfirm(config)
                onDismiss()
            }
        )
    }
}

@Composable
private fun ModelPickerPopupContent(
    availableModels: List<ApiConfig>,
    selectedModelId: String?,
    textColor: Color,
    iconTint: Color,
    onModelSelected: (ApiConfig) -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(min = 240.dp, max = 320.dp)
            .heightIn(max = 360.dp)
            .padding(vertical = 12.dp)
    ) {
        if (availableModels.isEmpty()) {
            Text(
                text = "当前无可用模型",
                color = textColor.copy(alpha = 0.7f),
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        } else {
            LazyColumn {
                items(availableModels, key = { it.id }) { config ->
                    val displayName = config.name.takeIf { it.isNotBlank() } ?: config.model
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { onModelSelected(config) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_robot_head),
                            contentDescription = null,
                            tint = if (config.id == selectedModelId) Color(0xFF66B5FF) else iconTint,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = displayName,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal,
                                color = textColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (config.model != displayName) {
                                Text(
                                    text = config.model,
                                    fontSize = 12.sp,
                                    color = textColor.copy(alpha = 0.65f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmModelRegenerateDialog(
    modelName: String,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    val cardBg = appDialogContainerColor()
    val textColor = appDialogContentColor()
    val subtextColor = appDialogSubtextColor()

    AlertDialog(
        onDismissRequest = onBack,
        modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
        shape = AppDialogShape,
        containerColor = cardBg,
        title = {
            Text(
                text = "切换模型重新回答",
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        text = {
            Text(
                text = "将使用“$modelName”重新回答这个问题。",
                color = subtextColor,
                fontSize = 14.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确定", color = Color(0xFF66B5FF), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onBack) {
                Text("返回", color = textColor)
            }
        }
    )
}

@Composable
private fun PopupMenuItem(
    painter: androidx.compose.ui.graphics.painter.Painter,
    text: String,
    textColor: Color,
    iconTint: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painter,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

