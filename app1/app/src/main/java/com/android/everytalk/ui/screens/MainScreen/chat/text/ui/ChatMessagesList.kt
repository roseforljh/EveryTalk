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

internal fun retainedStreamingHeightPx(
    naturalHeightPx: Int,
    cachedHeightPx: Int,
    isStreaming: Boolean,
): Int = if (isStreaming) maxOf(naturalHeightPx, cachedHeightPx) else naturalHeightPx

internal suspend fun shareMessageText(
    context: Context,
    text: String,
    onFailure: () -> Unit,
) {
    val safeText = withContext(Dispatchers.Default) {
        prepareTextForExternalTransfer(text)
    }
    withContext(Dispatchers.Main.immediate) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, safeText)
            }
            context.startActivity(Intent.createChooser(shareIntent, "分享"))
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: RuntimeException) {
            onFailure()
        }
    }
}

internal fun Modifier.retainGrowingHeightWhileStreaming(
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

internal fun shouldUsePreparedStaticAiRender(
    shouldPreferStreamingContent: Boolean,
    hasPreparedMessage: Boolean,
    itemText: String,
    effectiveContent: String,
): Boolean = !shouldPreferStreamingContent &&
    hasPreparedMessage &&
    itemText == effectiveContent

internal fun shouldAddConversationGapAfter(item: ChatListItem): Boolean = when (item) {
    is ChatListItem.AiMessageSources -> false
    is ChatListItem.AiMarkdownNode -> item.isLastNode
    else -> true
}

internal fun resolveStaticMarkdownTargetListIndex(
    currentListIndex: Int,
    item: ChatListItem.AiMarkdownNode,
    uri: String,
): Int? {
    val targetBlockIndex = item.targetBlockIndexByUri[uri] ?: return null
    return currentListIndex - item.blockIndex + targetBlockIndex
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
@SuppressLint("StateFlowValueCalledInComposition")
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
    // 防止 AnimatedItems 等状态在重组时被重复触发

    var isContextMenuVisible by remember { mutableStateOf(false) }
    var contextMenuMessage by remember { mutableStateOf<Message?>(null) }
    var contextMenuPressOffset by remember { mutableStateOf(Offset.Zero) }
    // 防重复触发：在极短时间内只允许一次预览弹出
    var lastImagePreviewAt by remember { mutableLongStateOf(0L) }
    val listCoroutineScope = rememberCoroutineScope()
    val footnoteNavigationByMessage = remember(scrollSessionKey) {
        mutableMapOf<String, FootnoteNavigationState>()
    }

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
    val density = LocalDensity.current
    val windowHeightDp = with(density) { LocalWindowInfo.current.containerSize.height.toDp().value }
    val pinnedUserBubbleMaxHeightPx = with(density) {
        resolveUserBubbleMaxHeightDp(
            screenHeightDp = windowHeightDp,
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
    val engineResponseTargetId = remember(topAnchorItems, engineTurn?.anchorMessageId) {
        engineTurn?.let { turn ->
            resolveTopAnchorResponseTargetId(topAnchorItems, turn.anchorMessageId)
        }
    }
    val engineAnchorInfo = remember(chatItems, engineTurn) {
        val turn = engineTurn ?: return@remember null
        chatItems.mapIndexedNotNull { index, item ->
            if (item.stableId == turn.anchorMessageId) index to item.stableId else null
        }.firstOrNull()
    }

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
        topAnchorEngine.activateTurn(turn)
        viewModel.consumeLastSentUserMessageId(turn.anchorMessageId)
    }

    LaunchedEffect(engineTurn?.anchorMessageId, engineResponseTargetId) {
        val turn = engineTurn ?: return@LaunchedEffect
        val targetId = engineResponseTargetId ?: return@LaunchedEffect
        topAnchorEngine.attachResponseTarget(turn, targetId)
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
                        reserveInsideTrailingItem = true,
                    ),
                    enabled = topAnchorEngine.runtime.hasRuntime,
                    hasResponseTarget = engineResponseTargetId != null,
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
                verticalArrangement = Arrangement.Top
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
                    is ChatListItem.AiMessageSources -> "AiMessageSources"
                    is ChatListItem.AiMarkdownNode -> "AiMarkdownBlock"
                    is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage,
                    is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageStreaming,
                    is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCode,
                    is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCodeStreaming -> "AiMessage"
                    else -> item::class.java.simpleName
                }
            }
        ) { index, item ->
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        bottom = if (
                            index < chatItems.lastIndex && shouldAddConversationGapAfter(item)
                        ) {
                            12.dp
                        } else {
                            0.dp
                        }
                    )
                    .appendTopAnchorReserve(
                        if (index == chatItems.lastIndex) topAnchorEngine.reservePx else 0
                    ),
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

                        is ChatListItem.AiMessageSources -> {
                            StaticAiMessageSourcesItem(
                                item = item,
                                maxWidth = bubbleMaxWidth,
                                viewModel = viewModel,
                            )
                        }

                        is ChatListItem.AiMarkdownNode -> {
                            val footnoteNavigation = footnoteNavigationByMessage.getOrPut(item.messageId) {
                                FootnoteNavigationState()
                            }
                            SideEffect {
                                footnoteNavigation.setFallbackNavigator { uri ->
                                    val targetListIndex = resolveStaticMarkdownTargetListIndex(
                                        currentListIndex = index,
                                        item = item,
                                        uri = uri,
                                    )
                                        ?: return@setFallbackNavigator false
                                    listCoroutineScope.launch {
                                        listState.animateScrollToItem(targetListIndex)
                                    }
                                    true
                                }
                            }
                            StaticAiMarkdownNodeItem(
                                item = item,
                                maxWidth = bubbleMaxWidth,
                                viewModel = viewModel,
                                footnoteNavigationState = footnoteNavigation,
                                onImageClick = { url ->
                                    val now = SystemClock.elapsedRealtime()
                                    if (now - lastImagePreviewAt > 500) {
                                        lastImagePreviewAt = now
                                        onImageClick(url)
                                    }
                                },
                            )
                        }

                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.UserMessage -> {
                            // 使用 Row + Arrangement.End，确保用户消息稳定靠右显示
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
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
                                        staticDisplayText = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage) {
                                            item.displayText
                                        } else {
                                            null
                                        },
                                        staticPageSources = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage) {
                                            item.pageSources
                                        } else {
                                            emptyList()
                                        },
                                        staticPreparedMessage = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage) {
                                            item.preparedMessage
                                        } else {
                                            null
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
                                        blocks = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCode) {
                                            item.blocks
                                        } else {
                                            emptyList()
                                        },
                                        staticDisplayText = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCode) {
                                            item.displayText
                                        } else {
                                            null
                                        },
                                        staticPageSources = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCode) {
                                            item.pageSources
                                        } else {
                                            emptyList()
                                        },
                                        staticPreparedMessage = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCode) {
                                            item.preparedMessage
                                        } else {
                                            null
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

                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageFooter -> {
                            AiMessageFooterItem(
                                message = item.message,
                                viewModel = viewModel,
                                scrollStateManager = scrollStateManager,
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

