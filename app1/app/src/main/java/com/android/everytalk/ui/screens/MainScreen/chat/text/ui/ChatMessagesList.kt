@file:OptIn(ExperimentalFoundationApi::class)
package com.android.everytalk.ui.screens.MainScreen.chat.text.ui
import com.android.everytalk.R

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.layout.onGloballyPositioned
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

@Composable
fun ChatMessagesList(
    chatItems: List<com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem>,
    viewModel: AppViewModel,
    listState: LazyListState,
    scrollStateManager: com.android.everytalk.ui.screens.MainScreen.chat.text.state.ChatScrollStateManager,
    bubbleMaxWidth: Dp,
    onShowAiMessageOptions: (Message) -> Unit,
    onImageLoaded: () -> Unit,
    onImageClick: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    // 永久化：移除animatedItems，不再需要追踪动画状态

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

    // 滚动锚点逻辑：当用户手动离开底部时，记录当前位置；当列表数据变化时，尝试恢复该位置
    // 避免因底部内容高度变化（如Finish时）导致视图整体上移
    val isAtBottom by scrollStateManager.isAtBottom
    LaunchedEffect(listState.isScrollInProgress, isAtBottom) {
        if (listState.isScrollInProgress && !isAtBottom) {
            scrollStateManager.onUserScrollSnapshot(listState)
        }
    }

    // 构造内容签名：仅结合列表长度和最后一条AI消息的ID
    // 修复：不再包含文本长度，避免流式输出过程中或结束时因长度变化触发强制锚点恢复导致跳动
    val lastAiItem = chatItems.lastOrNull {
        it is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage ||
        it is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCode ||
        it is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageStreaming ||
        it is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCodeStreaming
    }
    val contentSignature = remember(chatItems.size, lastAiItem) {
        "${chatItems.size}_${lastAiItem?.stableId}"
    }

    LaunchedEffect(contentSignature, isAtBottom) {
        if (!isAtBottom) {
            scrollStateManager.restoreAnchorIfNeeded(listState)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val availableHeight = maxHeight
        LazyColumn(
        state = listState,
        reverseLayout = false,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollStateManager.nestedScrollConnection),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 10.dp  // 增加底部padding以确保内容完全显示在输入框上方
        ),
        // 提升滚动稳定性：在可视区域外保留一定数量的项，降低回收/重组频率
        // 需要 @OptIn(ExperimentalFoundationApi::class)
        
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
            // 根据消息类型决定Box是否占满宽度
            val isUserMessage = item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.UserMessage ||
                (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.ErrorMessage &&
                 viewModel.getMessageById((item as com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.ErrorMessage).messageId)?.sender == com.android.everytalk.data.DataClass.Sender.User)
            
            // 父容器统一控制左右对齐，避免子树重组/图片尺寸回调致对齐失效
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

                    // 用户消息直接渲染，不需要Column包装
                    when (item) {
                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.UserMessage -> {
                            // 使用 Row + Arrangement.End 强制右贴齐，避免任何重组或父对齐变化造成漂移
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
                                                onRegenerateRequest = {
                                                    viewModel.regenerateAiResponse(it, isImageGeneration = false)
                                                    scrollStateManager.jumpToBottom()
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
                                            // 复用文本气泡渲染
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
                            // 放宽显示条件：一旦有推理文本且正文未开始，即显示思考框；
                            // 完成后由 reasoning_finish 控制收起
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
                            val isLastItem = index == chatItems.lastIndex
                            val shouldApplyMinHeight = isLastItem && chatItems.size >= 2

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (shouldApplyMinHeight) {
                                            Modifier.heightIn(min = availableHeight * 0.85f)
                                        } else {
                                            Modifier
                                        }
                                    ),
                                contentAlignment = if (shouldApplyMinHeight) Alignment.TopStart else Alignment.CenterStart
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
                                val isLastItem = index == chatItems.lastIndex
                                // Apply min height even for the first conversation (size >= 2) to ensure user message scrolls to top.
                                val shouldApplyMinHeight = isLastItem && chatItems.size >= 2

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
                                        },
                                        modifier = if (shouldApplyMinHeight) {
                                            Modifier.heightIn(min = availableHeight * 0.85f)
                                        } else {
                                            Modifier
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
                                val isLastItem = index == chatItems.lastIndex
                                val shouldApplyMinHeight = isLastItem && chatItems.size >= 2

                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    AiMessageItem(
                                        message = message,
                                        // 不再任何包裹：按原文渲染，避免把普通文本误判为代码
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
                                        },
                                        modifier = if (shouldApplyMinHeight) {
                                            Modifier.heightIn(min = availableHeight * 0.85f)
                                        } else {
                                            Modifier
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
                            val isLastItem = index == chatItems.lastIndex
                            val shouldApplyMinHeight = isLastItem && chatItems.size >= 2
                            Row(
                                modifier = Modifier
                                    .padding(
                                        start = ChatDimensions.HORIZONTAL_PADDING,
                                        top = ChatDimensions.VERTICAL_PADDING,
                                        bottom = ChatDimensions.VERTICAL_PADDING
                                    )
                                    .then(
                                        if (shouldApplyMinHeight) {
                                            Modifier.heightIn(min = availableHeight * 0.85f)
                                        } else {
                                            Modifier
                                        }
                                    ),
                                verticalAlignment = if (shouldApplyMinHeight) Alignment.Top else Alignment.Bottom,
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
            item(key = "chat_screen_footer_spacer_in_list") {
                Spacer(modifier = Modifier.height(1.dp))
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
                onRegenerate = {
                    // Remove resetScrollState to avoid conflicting scroll animations
                    viewModel.regenerateAiResponse(it, isImageGeneration = false)
                    isContextMenuVisible = false
                    coroutineScope.launch {
                        // Wait briefly for the list to update (AI message replaced by loading)
                        // This ensures we scroll based on the new layout
                        var attempts = 0
                        var targetIndex = -1
                        
                        // Increase timeout to 5 seconds to be safe against slow UI updates/DB operations
                        while (attempts < 100) {
                            val currentItems = viewModel.chatListItems.value
                            targetIndex = currentItems.indexOfFirst { item ->
                                (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.UserMessage && item.messageId == it.id) ||
                                (item is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.ErrorMessage && item.messageId == it.id)
                            }
                            
                            // We need to ensure that the Loading Indicator (or new AI message) has appeared AFTER the user message.
                            // If targetIndex is the last item, it means the AI message/loading hasn't been added yet (or old one removed).
                            // We wait until there is an item AFTER the user message, which ensures the list has grown/updated
                            // and the 'shouldApplyMinHeight' logic in the Loading item will trigger, allowing scroll to top.
                            if (targetIndex != -1 && targetIndex < currentItems.lastIndex) {
                                // Also verify that the LazyListState has actually updated to include these new items.
                                // The footer spacer adds 1 to the total count.
                                val expectedTotalItems = currentItems.size + 1
                                if (listState.layoutInfo.totalItemsCount >= expectedTotalItems) {
                                    break
                                }
                            }
                            delay(50)
                            attempts++
                        }
                        
                        // Give layout a moment to settle (measure the new height of the Loading item)
                        delay(100)
                        
                        // Always scroll to bottom for regenerate action, as requested by user
                        scrollStateManager.jumpToBottom()
                    }
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
    showMenuButton: Boolean = true,
    onImageClick: ((String) -> Unit)? = null
) {
    val shape = RectangleShape
    val aiReplyMessageDescription = stringResource(id = R.string.ai_reply_message)
    
    // 代码预览状态
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
        modifier = modifier
            .wrapContentWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            modifier = Modifier
                .wrapContentWidth()
                .widthIn(max = maxWidth) // AI气泡最大宽度设置为100%
                // 移除 pointerInput(detectTapGestures)，因为它会拦截子 View 的点击事件
                // 长按事件现由 MarkdownRenderer 内部的 setOnLongClickListener 处理
                .semantics {
                    contentDescription = aiReplyMessageDescription
                },
            shape = shape,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 0.dp
        ) {
            // 所有消息都使用正常内边距
            val contentType = ContentType.SIMPLE
            val needsZeroPadding = false
            
            Box(
                modifier = Modifier
                    .padding(
                        horizontal = if (needsZeroPadding) 0.dp else ChatDimensions.BUBBLE_INNER_PADDING_HORIZONTAL,
                        vertical = if (needsZeroPadding) 0.dp else ChatDimensions.BUBBLE_INNER_PADDING_VERTICAL
                    )
            ) {
                // 确保 Markdown 始终被解析渲染：
                // 始终使用 EnhancedMarkdownText 以保持组件树结构稳定，防止流式结束时的组件切换导致跳动。
                // EnhancedMarkdownText 内部会处理流式与非流式的状态，并利用 ContentCoordinator 统一调度。
                // 即使传入的 text 与 message.text 不同（例如代码块分离场景），EnhancedMarkdownText 也能正确处理。
                // 注意：如果 text 是完全自定义的内容且不希望走增强渲染，才需要考虑回退，但目前 ChatListItem 逻辑中
                // text 主要是 message.text 的快照或流式片段，走 EnhancedMarkdownText 是安全的。
                
                // 关键修复：移除 if (text != message.text) 分支，统一入口。
                // 但 EnhancedMarkdownText 默认使用 message.text，如果我们需要渲染传入的 `text` 参数
                // (例如在流式过程中 text 可能是累积的片段)，我们需要让 EnhancedMarkdownText 支持传入 overrideText。
                // 查看 EnhancedMarkdownText 源码，它目前主要依赖 message.text 或 streamingStateFlow。
                
                // 鉴于 EnhancedMarkdownText 目前没有 overrideText 参数，且 ChatListItem 中的 text
                // 在流式（AiMessageStreaming）和非流式（AiMessage）时其实都对应 message 的内容。
                // 唯一例外是 AiMessageCode 可能只有代码部分？不，ChatListItem.AiMessageCode 的 text 也是完整内容或片段。
                
                // 让我们再次确认：ChatListItem.AiMessage(text=...) 中的 text 是否总是等于 message.text？
                // 在 MessageItemsController 中：
                // ChatListItem.AiMessage(message.id, message.text, ...) -> 相等
                // ChatListItem.AiMessageCode(..., message.text, ...) -> 相等
                // ChatListItem.AiMessageStreaming 甚至不带 text，直接用 messageId 去取流。
                
                // 只有一种情况：ChatListItem 缓存了旧的 text，而 message.text 更新了。
                // 但 EnhancedMarkdownText 内部会监听流，或者使用传入的 message (它是引用)。
                // 如果 message 对象变了（StateFlow 发出的新列表），EnhancedMarkdownText 会重组。
                
                // 因此，直接使用 EnhancedMarkdownText 是安全的，也是解决跳动的关键。
                EnhancedMarkdownText(
                    message = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    isStreaming = isStreaming,
                    messageOutputType = messageOutputType,
                    onLongPress = onLongPress,
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
