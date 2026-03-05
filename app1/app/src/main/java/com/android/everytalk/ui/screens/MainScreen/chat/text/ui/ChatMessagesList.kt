@file:OptIn(ExperimentalFoundationApi::class)
package com.android.everytalk.ui.screens.MainScreen.chat.text.ui
import com.android.everytalk.R

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
    bubbleMaxWidth: Dp,
    onShowAiMessageOptions: (Message) -> Unit,
    onImageLoaded: () -> Unit,
    onImageClick: (String) -> Unit,
    additionalBottomPadding: Dp = 0.dp
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    // 濮橀晲绠欓崠鏍电窗缁夊娅巃nimatedItems閿涘奔绗夐崘宥夋付鐟曚浇鎷烽煪顏勫З閻㈣崵濮搁幀?

    var isContextMenuVisible by remember { mutableStateOf(false) }
    var contextMenuMessage by remember { mutableStateOf<Message?>(null) }
    var contextMenuPressOffset by remember { mutableStateOf(Offset.Zero) }
    // 闂冩煡鍣告径宥埿曢崣鎴窗閸︺劍鐎惌顓熸闂傛潙鍞撮崣顏勫帒鐠侀晲绔村▎锟狀暕鐟欏牆鑴婇崙?
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
    
    val conversationId by viewModel.currentConversationId.collectAsState()
    
    val dynamicBottomPadding by androidx.compose.animation.core.animateDpAsState(
        targetValue = dynamicBottomPaddingTarget,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 500,
            easing = androidx.compose.animation.core.CubicBezierEasing(0.25f, 0.1f, 0.25f, 1.0f)
        ),
        label = "dynamicBottomPadding"
    )
    
    LaunchedEffect(conversationId) {
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
    
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
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
            // 閺嶈宓佸☉鍫熶紖缁鐎烽崘鍐茬暰Box閺勵垰鎯侀崡鐘冲姬鐎硅棄瀹?
            val isUserMessage = item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.UserMessage ||
                (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.ErrorMessage &&
                 viewModel.getMessageById((item as com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.ErrorMessage).messageId)?.sender == com.android.everytalk.data.DataClass.Sender.User)
            
            // 閻栬泛顔愰崳銊х埠娑撯偓閹貉冨煑瀹革箑褰哥€靛綊缍堥敍宀勪缉閸忓秴鐡欓弽鎴﹀櫢缂?閸ュ墽澧栫亸鍝勵嚟閸ョ偠鐨熼懛鏉戭嚠姒绘劕銇戦弫?
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

                    // 閻劍鍩涘☉鍫熶紖閻╁瓨甯村〒鍙夌厠閿涘奔绗夐棁鈧憰涓唎lumn閸栧懓顥?
                    when (item) {
                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.UserMessage -> {
                            // 娴ｈ法鏁?Row + Arrangement.End 瀵搫鍩楅崣瀹犲垱姒绘劧绱濋柆鍨帳娴犺缍嶉柌宥囩矋閹存牜鍩楃€靛綊缍堥崣妯哄闁姵鍨氬鍌溞?
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
                                            // 婢跺秶鏁ら弬鍥ㄦ拱濮樻梹鍦哄〒鍙夌厠
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

                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageReasoning -> {
                            val reasoningCompleteMap = viewModel.textReasoningCompleteMap
                            val isReasoningStreaming = remember(
                                item.message.reasoning,
                                reasoningCompleteMap[item.message.id],
                                item.message.contentStarted
                            ) {
                                (item.message.reasoning?.isNotBlank() == true) &&
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
                                    displayedReasoningText = item.message.reasoning ?: "",
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
                            val message = viewModel.getMessageById(messageId)
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
                            val message = viewModel.getMessageById(messageId)
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
                                Text(
                                    text = stringResource(id = R.string.connecting_to_model),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.width(ChatDimensions.LOADING_SPACER_WIDTH))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(ChatDimensions.LOADING_INDICATOR_SIZE),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    strokeWidth = ChatDimensions.LOADING_INDICATOR_STROKE_WIDTH
                                )
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
                                Spacer(Modifier.width(ChatDimensions.LOADING_SPACER_WIDTH))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(ChatDimensions.LOADING_INDICATOR_SIZE),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    strokeWidth = ChatDimensions.LOADING_INDICATOR_STROKE_WIDTH
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
                        // 閺傚洦婀板Ο鈥崇础閻劍鍩涘鏃€鍦洪敍姘崇箻娑撯偓濮濄儰绗呯粔璁充簰鐠愮绻庨幍瀣瘹
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
    SIMPLE         // 閺咁噣鈧艾鍞寸€圭櫢绱濇担璺ㄦ暏濮濓絽鐖堕崘鍛扮珶鐠?
}

fun detectContentTypeForPadding(text: String): ContentType {
    // 閹碘偓閺堝鍞寸€瑰綊鍏樻担璺ㄦ暏濮濓絽鐖堕崘鍛扮珶鐠?
    return ContentType.SIMPLE
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
                val mergedSegments = remember(blockPayload?.blocksHash, messageOutputType) {
                    val sourceBlocks = blockPayload?.blocks.orEmpty()
                    if (sourceBlocks.isEmpty() || messageOutputType == "code") {
                        emptyList()
                    } else {
                        val result = mutableListOf<String>()
                        val inlineBuffer = StringBuilder()

                        fun flushInlineBuffer() {
                            if (inlineBuffer.isNotEmpty()) {
                                result.add(inlineBuffer.toString())
                                inlineBuffer.setLength(0)
                            }
                        }

                        sourceBlocks.forEach { block ->
                            when (block.type) {
                                com.android.everytalk.ui.components.streaming.StreamBlockType.CODE_BLOCK,
                                com.android.everytalk.ui.components.streaming.StreamBlockType.MATH_BLOCK -> {
                                    flushInlineBuffer()
                                    result.add(block.text)
                                }
                                else -> inlineBuffer.append(block.text)
                            }
                        }

                        flushInlineBuffer()
                        result.filter { it.isNotEmpty() }
                    }
                }

                // 流式阶段保持单组件订阅，避免实时输出丢失。
                if (!isStreaming && mergedSegments.size > 1) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        mergedSegments.forEachIndexed { index, segmentText ->
                            key("${message.id}:segment:$index", blockPayload?.blocksHash) {
                                EnhancedMarkdownText(
                                    message = message,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    isStreaming = false,
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
                                    contentOverride = segmentText,
                                    contentKeyOverride = "${message.id}:segment:$index",
                                    disableStreamingSubscription = true
                                )
                            }
                        }
                    }
                } else {
                    EnhancedMarkdownText(
                        message = message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        isStreaming = isStreaming,
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
                        viewModel = viewModel
                    )
                }
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

