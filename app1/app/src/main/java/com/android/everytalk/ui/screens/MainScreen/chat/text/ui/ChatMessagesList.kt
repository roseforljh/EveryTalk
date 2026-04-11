@file:OptIn(ExperimentalFoundationApi::class)
package com.android.everytalk.ui.screens.MainScreen.chat.text.ui
import com.android.everytalk.R

import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.ui.screens.BubbleMain.Main.AttachmentsContent
import com.android.everytalk.ui.screens.BubbleMain.Main.ReasoningToggleAndContent
import com.android.everytalk.ui.screens.BubbleMain.Main.UserOrErrorMessageContent
import com.android.everytalk.ui.screens.BubbleMain.Main.MessageContextMenu
import com.android.everytalk.ui.theme.ChatDimensions
import com.android.everytalk.ui.theme.chatColors

import com.android.everytalk.ui.components.EnhancedMarkdownText
import com.android.everytalk.ui.components.StableMarkdownText
import com.android.everytalk.ui.components.markdown.MarkdownRenderer
import com.android.everytalk.ui.components.streaming.StreamBlocksRenderer
import com.android.everytalk.ui.components.WebPreviewDialog
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

@Composable
fun ChatMessagesList(
    chatItems: List<com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem>,
    viewModel: AppViewModel,
    listState: LazyListState,
    scrollStateManager: com.android.everytalk.ui.screens.MainScreen.chat.text.state.ChatScrollStateManager,
    scrollSessionKey: String,
    bubbleMaxWidth: Dp,
    onShowAiMessageOptions: (Message) -> Unit,
    onImageLoaded: () -> Unit,
    onImageClick: (String) -> Unit,
    additionalBottomPadding: Dp = 0.dp
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    // 防止 AnimatedItems 等状态在重组时被重复触发

    var isContextMenuVisible by remember { mutableStateOf(false) }
    var contextMenuMessage by remember { mutableStateOf<Message?>(null) }
    var contextMenuPressOffset by remember { mutableStateOf(Offset.Zero) }
    // 防重复触发：在极短时间内只允许一次预览弹出
    var lastImagePreviewAt by remember { mutableStateOf(0L) }

    val isApiCalling by viewModel.isTextApiCalling.collectAsState()
    val currentStreamingId by viewModel.currentTextStreamingAiMessageId.collectAsState()
    val density = LocalDensity.current
    
    // Performance monitoring: Track recomposition count for ChatMessagesList
    // This helps verify that the overall list recomposition is reduced
    // Requirements: 1.4, 3.4
    val listRecompositionCount = remember { mutableStateOf(0) }
    LaunchedEffect(chatItems.size, isApiCalling, currentStreamingId) {
        listRecompositionCount.value++
        if (listRecompositionCount.value % 5 == 0) {
            android.util.Log.d(
                "ChatMessagesList",
                "List recomposed ${listRecompositionCount.value} times (items: ${chatItems.size}, streaming: $isApiCalling)"
            )
        }
    }

    val isAtBottom by scrollStateManager.isAtBottom
    
    val userMessageIndices = remember(chatItems) {
        val indices = mutableListOf<Int>()
        chatItems.forEachIndexed { i, item ->
            if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.UserMessage) {
                indices.add(i)
            }
        }
        indices
    }
    
    val lastUserMessageInfo = remember(chatItems) {
        var index = -1
        var id: String? = null
        chatItems.forEachIndexed { i, item ->
            if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.UserMessage) {
                index = i
                id = item.stableId
            }
        }
        Pair(index, id)
    }
    
    var previousUserMessageId by remember { mutableStateOf<String?>(null) }
    var dynamicBottomPaddingTarget by remember { mutableStateOf(0.dp) }
    var firstBubbleScreenY by remember { mutableStateOf(-1) }

    val dynamicBottomPadding by androidx.compose.animation.core.animateDpAsState(
        targetValue = dynamicBottomPaddingTarget,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 500,
            easing = androidx.compose.animation.core.CubicBezierEasing(0.25f, 0.1f, 0.25f, 1.0f)
        ),
        label = "dynamicBottomPadding"
    )

    LaunchedEffect(scrollSessionKey) {
        dynamicBottomPaddingTarget = 0.dp
        firstBubbleScreenY = -1
        previousUserMessageId = null
        android.util.Log.d("GrokScroll", "Session changed, reset state")
    }
    
    LaunchedEffect(isApiCalling) {
        if (!isApiCalling && dynamicBottomPaddingTarget > 0.dp) {
            kotlinx.coroutines.delay(300)
            dynamicBottomPaddingTarget = 0.dp
            android.util.Log.d("GrokScroll", "Cleared padding")
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
            val topPadding = 85.dp
            val density = LocalDensity.current
            val topPaddingPx = with(density) { topPadding.toPx().toInt() }
            
            LaunchedEffect(lastUserMessageInfo.second) {
                val (lastUserIndex, lastUserMessageId) = lastUserMessageInfo
                android.util.Log.d("GrokScroll", "Triggered: index=$lastUserIndex, id=$lastUserMessageId, isApiCalling=$isApiCalling")
                
                if (lastUserMessageId == null || lastUserIndex < 0) return@LaunchedEffect
                if (lastUserMessageId == previousUserMessageId) return@LaunchedEffect
                
                if (!isApiCalling) {
                    previousUserMessageId = lastUserMessageId
                    android.util.Log.d("GrokScroll", "Skipping - not sending")
                    return@LaunchedEffect
                }
                
                kotlinx.coroutines.delay(50)
                
                val li = listState.layoutInfo
                val viewportStart = li.viewportStartOffset
                val firstUserIndex = userMessageIndices.firstOrNull() ?: -1
                
                if (userMessageIndices.size == 1) {
                    val firstItem = li.visibleItemsInfo.firstOrNull { it.index == firstUserIndex }
                    if (firstItem != null) {
                        firstBubbleScreenY = firstItem.offset - viewportStart
                        android.util.Log.d("GrokScroll", "First bubble Y: $firstBubbleScreenY")
                    }
                    previousUserMessageId = lastUserMessageId
                    return@LaunchedEffect
                }
                
                if (firstBubbleScreenY < 0) {
                    firstBubbleScreenY = topPaddingPx
                    android.util.Log.d("GrokScroll", "Using topPadding as target Y: $firstBubbleScreenY")
                }
                
                val targetScreenY = firstBubbleScreenY
                android.util.Log.d("GrokScroll", "Target Y: $targetScreenY")
                
                var currItem = li.visibleItemsInfo.firstOrNull { it.index == lastUserIndex }
                
                if (currItem == null) {
                    val viewportHeight = li.viewportEndOffset - li.viewportStartOffset
                    dynamicBottomPaddingTarget = with(density) { viewportHeight.toDp() }
                    android.util.Log.d("GrokScroll", "Adding padding to make item visible")
                    repeat(3) { kotlinx.coroutines.delay(16) }
                    
                    val animationDistance = with(density) { 200.dp.toPx().toInt() }
                    listState.scrollToItem(lastUserIndex, scrollOffset = -animationDistance)
                    kotlinx.coroutines.delay(50)
                    
                    val li2 = listState.layoutInfo
                    currItem = li2.visibleItemsInfo.firstOrNull { it.index == lastUserIndex }
                }
                
                if (currItem != null) {
                    val li3 = listState.layoutInfo
                    val currScreenY = currItem.offset - li3.viewportStartOffset
                    val scrollNeeded = currScreenY - targetScreenY
                    android.util.Log.d("GrokScroll", "currScreenY=$currScreenY, scrollNeeded=$scrollNeeded")
                    
                    if (scrollNeeded != 0) {
                        if (scrollNeeded > 0) {
                            val viewportHeight = li3.viewportEndOffset - li3.viewportStartOffset
                            val lastItem = li3.visibleItemsInfo.lastOrNull()
                            val contentBottom = if (lastItem != null) lastItem.offset + lastItem.size else 0
                            val canScroll = contentBottom - viewportHeight + li3.afterContentPadding
                            
                            if (scrollNeeded > canScroll) {
                                val extraPadding = scrollNeeded - canScroll + 50
                                dynamicBottomPaddingTarget = with(density) { extraPadding.toDp() }
                                android.util.Log.d("GrokScroll", "Extra padding: $extraPadding")
                                repeat(3) { kotlinx.coroutines.delay(16) }
                            }
                        }
                        
                        listState.animateScrollBy(
                            scrollNeeded.toFloat(),
                            androidx.compose.animation.core.tween(
                                durationMillis = 500,
                                easing = androidx.compose.animation.core.CubicBezierEasing(0.25f, 0.1f, 0.25f, 1.0f)
                            )
                        )
                        android.util.Log.d("GrokScroll", "Scrolled by $scrollNeeded")
                    }
                }
                
                previousUserMessageId = lastUserMessageId
            }
            
            LazyColumn(
                state = listState,
                reverseLayout = false,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollStateManager.nestedScrollConnection),
                contentPadding = PaddingValues(
                    start = 4.dp,
                    end = 4.dp,
                    top = topPadding,
                    bottom = additionalBottomPadding + 10.dp
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(
            items = chatItems,
            key = { _, item -> item.stableId },
            contentType = { _, item -> 
                when (item) {
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
                    when (item) {
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
                            val streamingReasoning by remember(item.message.id) {
                                viewModel.getStreamingReasoning(item.message.id)
                            }.collectAsState(initial = item.message.reasoning ?: "")
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
                                modifier = Modifier.fillMaxWidth(),
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
                                val hasReasoning = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage) item.hasReasoning else (item as com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageStreaming).hasReasoning
                                val isStreaming = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageStreaming) true else (currentStreamingId == message.id)
                                val blockPayload = item as? com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage
                                if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage && item.hasPendingMath) {
                                    android.util.Log.d(
                                        "ChatMessagesList",
                                        "Pending math block: msgId=${item.messageId.take(8)}, blocks=${item.blocks.size}, hash=${item.blocksHash}"
                                    )
                                }

                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    AiMessageItem(
                                        message = message,
                                        text = text,
                                        maxWidth = bubbleMaxWidth,
                                        hasReasoning = hasReasoning,
                                        onLongPress = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onShowAiMessageOptions(message)
                                        },
                                        isStreaming = isStreaming,
                                        messageOutputType = message.outputType,
                                        viewModel = viewModel,
                                        blockPayload = blockPayload,
                                        showMenuButton = false,
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
                                val hasReasoning = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCode) item.hasReasoning else (item as com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCodeStreaming).hasReasoning
                                val isStreaming = if (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCodeStreaming) true else (currentStreamingId == message.id)

                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    AiMessageItem(
                                        message = message,
                                        text = text,
                                        maxWidth = bubbleMaxWidth,
                                        hasReasoning = hasReasoning,
                                        onLongPress = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onShowAiMessageOptions(message)
                                        },
                                        isStreaming = isStreaming,
                                        messageOutputType = message.outputType,
                                        viewModel = viewModel,
                                        showMenuButton = false,
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
                                val defaultText = stringResource(id = R.string.connecting_to_model)
                                val displayText = resolveLoadingStageDisplayText(item.text, defaultText)
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
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        
                        else -> { /* no-op */ }
                    }
                }
            }
                
            if (dynamicBottomPadding > 0.dp) {
                item(key = "dynamic_padding_spacer") {
                    Spacer(modifier = Modifier.height(dynamicBottomPadding))
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

internal fun resolveLoadingStageDisplayText(text: String?, defaultText: String): String {
    return text?.takeIf { it.isNotBlank() } ?: defaultText
}

internal fun loadingStageViewportHeightDp(): Float = 34f

internal fun loadingStageMaskHeightDp(): Float = 10f

internal fun loadingStageBreathingDotSizeDp(): Float = 6f

@Composable
private fun LoadingStageIndicator(
    text: String,
    modifier: Modifier = Modifier,
) {
    val viewportHeight = loadingStageViewportHeightDp().dp
    val maskHeight = loadingStageMaskHeightDp().dp
    val breathingDotSize = loadingStageBreathingDotSizeDp().dp
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
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "loadingStageGlowAlpha",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(viewportHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha),
                            Color.Transparent,
                        ),
                        radius = 220f,
                        center = Offset(120f, viewportHeight.value * 1.6f),
                    )
                )
        )

        Box(
            modifier = Modifier
                .padding(start = 6.dp)
                .size(breathingDotSize)
                .background(
                    color = Color.White.copy(alpha = lineAlpha),
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
            ) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.background,
                                    MaterialTheme.colorScheme.background.copy(alpha = 0f),
                                )
                            )
                        )
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth()
                        .padding(vertical = maskHeight)
                ) {
                    AnimatedContent(
                        targetState = text,
                        transitionSpec = {
                            (slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(animationSpec = tween(260)) + scaleIn(initialScale = 0.985f, animationSpec = tween(260)))
                                .togetherWith(
                                    slideOutVertically(targetOffsetY = { -it / 2 }) + fadeOut(animationSpec = tween(220)) + scaleOut(targetScale = 0.985f, animationSpec = tween(220))
                                )
                        },
                        label = "LoadingStageTextAnimation"
                    ) { stageText ->
                        Text(
                            text = stageText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
                            maxLines = 1,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(maskHeight)
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.background,
                                    MaterialTheme.colorScheme.background.copy(alpha = 0f),
                                )
                            )
                        )
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(maskHeight)
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.background.copy(alpha = 0f),
                                    MaterialTheme.colorScheme.background,
                                )
                            )
                        )
                )
            }
        }
    }
}


@Composable
fun AiMessageItem(
    message: Message,
    text: String,
    maxWidth: Dp,
    hasReasoning: Boolean,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    isStreaming: Boolean,
    isFastScroll: Boolean = false,
    messageOutputType: String,
    viewModel: AppViewModel,
    blockPayload: com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage? = null,
    showMenuButton: Boolean = true,
    onImageClick: ((String) -> Unit)? = null
) {
    val shape = RectangleShape
    val aiReplyMessageDescription = stringResource(id = R.string.ai_reply_message)

    var previewCode by remember { mutableStateOf<String?>(null) }
    var previewLanguage by remember { mutableStateOf("text") }

    if (previewCode != null) {
        WebPreviewDialog(
            code = previewCode!!,
            language = previewLanguage,
            onDismiss = {
                previewCode = null
                previewLanguage = "text"
            }
        )
    }

    Row(
        modifier = modifier.wrapContentWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            modifier = Modifier
                .wrapContentWidth()
                .widthIn(max = maxWidth)
                .semantics { contentDescription = aiReplyMessageDescription },
            shape = shape,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 0.dp
        ) {
            Box(
                modifier = Modifier.padding(
                    horizontal = ChatDimensions.BUBBLE_INNER_PADDING_HORIZONTAL,
                    vertical = ChatDimensions.BUBBLE_INNER_PADDING_VERTICAL
                )
            ) {
                val streamingRenderState by remember(message.id, viewModel) {
                    viewModel.getStreamingRenderState(message.id)
                }.collectAsState()

                val shouldPreferStreamingContent =
                    isStreaming ||
                        streamingRenderState.isStreaming

                val effectiveContent = if (shouldPreferStreamingContent) {
                    streamingRenderState.content.ifBlank { message.text }
                } else {
                    message.text
                }

                val renderMessage = if (effectiveContent == message.text) {
                    message
                } else {
                    message.copy(text = effectiveContent)
                }

                // 流式阶段也始终对累计全文做结构化分块解析，
                // UI 依靠块级 stable key 复用前面已稳定的 block，
                // 而不是让单个 MarkdownRenderer 重排整段文本。
                EnhancedMarkdownText(
                    message = renderMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    isStreaming = shouldPreferStreamingContent,
                    messageOutputType = messageOutputType,
                    onLongPress = { _ -> onLongPress() },
                    onImageClick = onImageClick,
                    onCodePreviewRequested = { lang, code ->
                        previewLanguage = lang
                        previewCode = code
                    },
                    onCodeCopied = {
                        viewModel.showSnackbar("已复制代码")
                    },
                    viewModel = viewModel,
                    contentOverride = effectiveContent,
                    contentKeyOverride = message.id,
                    disableStreamingSubscription = true
                )
            }
        }
    }
}

@Composable
fun AiMessageFooterItem(
    message: Message,
    viewModel: AppViewModel,
) {
    if (!message.webSearchResults.isNullOrEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ChatDimensions.HORIZONTAL_PADDING),
            horizontalArrangement = Arrangement.Start
        ) {
            TextButton(
                onClick = {
                    viewModel.showSourcesDialog(message.webSearchResults)
                },
            ) {
                Text(stringResource(id = R.string.view_sources, message.webSearchResults.size))
            }
        }
    }
}

