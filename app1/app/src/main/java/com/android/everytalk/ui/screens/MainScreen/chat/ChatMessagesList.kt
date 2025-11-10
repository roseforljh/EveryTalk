@file:OptIn(ExperimentalFoundationApi::class)
package com.android.everytalk.ui.screens.MainScreen.chat
import com.android.everytalk.R

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
import com.android.everytalk.ui.components.markdown.MarkdownRenderer
import kotlinx.coroutines.launch

@Composable
fun ChatMessagesList(
    chatItems: List<ChatListItem>,
    viewModel: AppViewModel,
    listState: LazyListState,
    scrollStateManager: ChatScrollStateManager,
    bubbleMaxWidth: Dp,
    onShowAiMessageOptions: (Message) -> Unit,
    onImageLoaded: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    // 🎯 永久化：移除animatedItems，不再需要追踪动画状态

    var isContextMenuVisible by remember { mutableStateOf(false) }
    var contextMenuMessage by remember { mutableStateOf<Message?>(null) }
    var contextMenuPressOffset by remember { mutableStateOf(Offset.Zero) }

    val isApiCalling by viewModel.isTextApiCalling.collectAsState()
    val currentStreamingId by viewModel.currentTextStreamingAiMessageId.collectAsState()
    val density = LocalDensity.current
    
    // 🎯 Performance monitoring: Track recomposition count for ChatMessagesList
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

    // 取消因思考框(AiMessageReasoning)导致的外层自动滚动，避免联动到外层列表
    // LaunchedEffect(chatItems) { ... } 已移除

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollStateManager.nestedScrollConnection),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 10.dp  // 增加底部padding以确保内容完全显示在输入框上方
        ),
        // ⚡ 提升滚动稳定性：在可视区域外保留一定数量的项，降低回收/重组频率
        // 需要 @OptIn(ExperimentalFoundationApi::class)
        
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(
            items = chatItems,
            key = { _, item -> item.stableId },
            contentType = { _, item -> item::class.java.simpleName }
        ) { index, item ->
            // 根据消息类型决定Box是否占满宽度
            val isUserMessage = item is ChatListItem.UserMessage ||
                (item is ChatListItem.ErrorMessage &&
                 viewModel.getMessageById((item as ChatListItem.ErrorMessage).messageId)?.sender == com.android.everytalk.data.DataClass.Sender.User)
            
            Box(
                modifier = if (isUserMessage) {
                    Modifier.fillMaxWidth() // 用户消息需要fillMaxWidth以便右对齐
                } else {
                    Modifier.fillMaxWidth() // AI消息也需要fillMaxWidth以便左对齐
                }
            ) {
                val alignment = when (item) {
                    is ChatListItem.UserMessage -> Alignment.CenterEnd
                    is ChatListItem.ErrorMessage -> {
                        val message = viewModel.getMessageById(item.messageId)
                        if (message?.sender == com.android.everytalk.data.DataClass.Sender.User) {
                            Alignment.CenterEnd
                        } else {
                            Alignment.CenterStart
                        }
                    }
                    else -> Alignment.CenterStart
                }

                    // 用户消息直接渲染，不需要Column包装
                    when (item) {
                        is ChatListItem.UserMessage -> {
                            Column(
                                modifier = Modifier
                                    .align(alignment)
                                    .wrapContentWidth(),
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
                                            isAiGenerated = false
                                        )
                                    }
                                    if (item.text.isNotBlank()) {
                                        // 用户气泡：右对齐 + 自适应宽度 + 垂直滚动（无展开按钮）
                                        val SCROLLABLE_MAX_HEIGHT = 220.dp
                                        var bubbleGlobalPosition by remember(message.id) { mutableStateOf(Offset.Zero) }
                                        val innerScroll = rememberScrollState()

                                        Surface(
                                            modifier = Modifier
                                                .wrapContentWidth()
                                                .widthIn(max = bubbleMaxWidth * ChatDimensions.USER_BUBBLE_WIDTH_RATIO)
                                                .onGloballyPositioned {
                                                    bubbleGlobalPosition = it.localToRoot(Offset.Zero)
                                                }
                                                .pointerInput(message.id) {
                                                    detectTapGestures(
                                                        onLongPress = { localOffset ->
                                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                            contextMenuMessage = message
                                                            contextMenuPressOffset = bubbleGlobalPosition + localOffset
                                                            isContextMenuVisible = true
                                                        }
                                                    )
                                                },
                                            shape = RoundedCornerShape(
                                                topStart = ChatDimensions.CORNER_RADIUS_LARGE,
                                                topEnd = 0.dp,
                                                bottomStart = ChatDimensions.CORNER_RADIUS_LARGE,
                                                bottomEnd = ChatDimensions.CORNER_RADIUS_LARGE
                                            ),
                                            color = MaterialTheme.chatColors.userBubble,
                                            contentColor = MaterialTheme.colorScheme.onSurface,
                                            shadowElevation = 0.dp
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .padding(
                                                        horizontal = ChatDimensions.BUBBLE_INNER_PADDING_HORIZONTAL,
                                                        vertical = ChatDimensions.BUBBLE_INNER_PADDING_VERTICAL
                                                    )
                                            ) {
                                                // 限高 + 内部垂直滚动，并拦截惯性/滚动以防外层被带动
                                                val innerScrollBlocker = remember {
                                                    object : NestedScrollConnection {
                                                        override fun onPreScroll(
                                                            available: Offset,
                                                            source: NestedScrollSource
                                                        ): Offset {
                                                            // 消耗用户手势产生的垂直滚动，阻止向外层传递
                                                            return if (source == NestedScrollSource.UserInput) {
                                                                Offset(x = 0f, y = available.y)
                                                            } else Offset.Zero
                                                        }
                                                        override fun onPostScroll(
                                                            consumed: Offset,
                                                            available: Offset,
                                                            source: NestedScrollSource
                                                        ): Offset {
                                                            // 吞掉剩余的滚动量，避免外层获得“余量”
                                                            return Offset(x = 0f, y = available.y)
                                                        }
                                                        override suspend fun onPreFling(available: Velocity): Velocity {
                                                            // 吞掉即将开始的惯性，防止交给父级
                                                            return available
                                                        }
                                                        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                                                            // 吞掉剩余惯性，不让父级承接
                                                            return available
                                                        }
                                                    }
                                                }
                                                Box(
                                                    modifier = Modifier
                                                        .heightIn(max = SCROLLABLE_MAX_HEIGHT)
                                                        .verticalScroll(innerScroll)
                                                        .nestedScroll(innerScrollBlocker)
                                                ) {
                                                    // 将用户消息也按 Markdown 渲染，确保 "* **注册新账号**" 等语法被正确解析
                                                    MarkdownRenderer(
                                                        markdown = item.text,
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        modifier = Modifier.wrapContentWidth(),
                                                        isStreaming = false
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        is ChatListItem.AiMessageReasoning -> {
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

                        is ChatListItem.AiMessage -> {
                            val message = viewModel.getMessageById(item.messageId)
                            if (message != null) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    AiMessageItem(
                                        message = message,
                                        text = item.text,
                                        maxWidth = bubbleMaxWidth,
                                        hasReasoning = item.hasReasoning,
                                        onLongPress = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onShowAiMessageOptions(message)
                                        },
                                        isStreaming = currentStreamingId == message.id,
                                        messageOutputType = message.outputType,
                                        viewModel = viewModel,
                                        showMenuButton = false
                                    )
                                }
                            }
                        }

                        is ChatListItem.AiMessageCode -> {
                            val message = viewModel.getMessageById(item.messageId)
                            if (message != null) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    AiMessageItem(
                                        message = message,
                                        // 不再任何包裹：按原文渲染，避免把普通文本误判为代码
                                        text = item.text,
                                        maxWidth = bubbleMaxWidth,
                                        hasReasoning = item.hasReasoning,
                                        onLongPress = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onShowAiMessageOptions(message)
                                        },
                                        isStreaming = currentStreamingId == message.id,
                                        messageOutputType = message.outputType,
                                        viewModel = viewModel,
                                        showMenuButton = false
                                    )
                                }
                            }
                        }

                        is ChatListItem.AiMessageFooter -> {
                            AiMessageFooterItem(
                                message = item.message,
                                viewModel = viewModel,
                            )
                        }

                        is ChatListItem.AiMessageStreaming -> {
                            val message = viewModel.getMessageById(item.messageId)
                            if (message != null) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    AiMessageItem(
                                        message = message,
                                        text = message.text,
                                        maxWidth = bubbleMaxWidth,
                                        hasReasoning = item.hasReasoning,
                                        onLongPress = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onShowAiMessageOptions(message)
                                        },
                                        isStreaming = true,
                                        messageOutputType = message.outputType,
                                        viewModel = viewModel,
                                        showMenuButton = false
                                    )
                                }
                            }
                        }

                        is ChatListItem.AiMessageCodeStreaming -> {
                            val message = viewModel.getMessageById(item.messageId)
                            if (message != null) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    AiMessageItem(
                                        message = message,
                                        text = message.text,
                                        maxWidth = bubbleMaxWidth,
                                        hasReasoning = item.hasReasoning,
                                        onLongPress = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onShowAiMessageOptions(message)
                                        },
                                        isStreaming = true,
                                        messageOutputType = message.outputType,
                                        viewModel = viewModel,
                                        showMenuButton = false
                                    )
                                }
                            }
                        }

                        is ChatListItem.ErrorMessage -> {
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

                        is ChatListItem.LoadingIndicator -> {
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
                                    color = MaterialTheme.chatColors.loadingIndicator,
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
                    scrollStateManager.resetScrollState()
                    viewModel.regenerateAiResponse(it, isImageGeneration = false)
                    isContextMenuVisible = false
                    coroutineScope.launch {
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
    showMenuButton: Boolean = true
) {
    val shape = RectangleShape
    val aiReplyMessageDescription = stringResource(id = R.string.ai_reply_message)
    
    Row(
        modifier = modifier
            .wrapContentWidth()
            .pointerInput(message.id) {
                detectTapGestures(
                    onLongPress = { onLongPress() }
                )
            },
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            modifier = Modifier
                .wrapContentWidth()
                .widthIn(max = maxWidth) // 🎯 AI气泡最大宽度设置为100%
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
                // 若传入的 text 与 message.text 不同（例如经过前置加工），也用 MarkdownRenderer 正确解析。
                if (text != message.text) {
                    MarkdownRenderer(
                        markdown = text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                        isStreaming = false
                    )
                } else {
                    EnhancedMarkdownText(
                        message = message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        isStreaming = isStreaming,
                        messageOutputType = messageOutputType,
                        onLongPress = onLongPress,
                        viewModel = viewModel  // 🎯 传递viewModel以获取实时流式文本
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
