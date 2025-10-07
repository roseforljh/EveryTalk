@file:OptIn(ExperimentalFoundationApi::class)
package com.example.everytalk.ui.screens.MainScreen.chat

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.everytalk.R
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.statecontroller.AppViewModel
import com.example.everytalk.ui.screens.BubbleMain.Main.AttachmentsContent
import com.example.everytalk.ui.screens.BubbleMain.Main.ReasoningToggleAndContent
import com.example.everytalk.ui.screens.BubbleMain.Main.UserOrErrorMessageContent
import com.example.everytalk.ui.screens.BubbleMain.Main.MessageContextMenu
import com.example.everytalk.ui.theme.ChatDimensions
import com.example.everytalk.ui.theme.chatColors

import com.example.everytalk.ui.components.EnhancedMarkdownText
import com.example.everytalk.ui.components.CodePreview
import com.example.everytalk.ui.components.normalizeMarkdownGlyphs
import com.example.everytalk.util.messageprocessor.parseMarkdownParts
import com.example.everytalk.ui.components.normalizeBasicMarkdown
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope

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
   val density = LocalDensity.current

    // 取消因思考框(AiMessageReasoning)导致的外层自动滚动，避免联动到外层列表
    // LaunchedEffect(chatItems) { ... } 已移除

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollStateManager.nestedScrollConnection),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(
            items = chatItems,
            key = { _, item -> item.stableId },
            contentType = { _, item -> item::class.java.simpleName }
        ) { index, item ->
            key(item.stableId) {
                // 根据消息类型决定Box是否占满宽度
                val isUserMessage = item is ChatListItem.UserMessage || 
                    (item is ChatListItem.ErrorMessage && 
                     viewModel.getMessageById((item as ChatListItem.ErrorMessage).messageId)?.sender == com.example.everytalk.data.DataClass.Sender.User)
                
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
                            if (message?.sender == com.example.everytalk.data.DataClass.Sender.User) {
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

                        is ChatListItem.AiMessageReasoning -> {
                            val reasoningCompleteMap = viewModel.textReasoningCompleteMap
                            val isReasoningStreaming = remember(isApiCalling, item.message.reasoning, reasoningCompleteMap[item.message.id]) {
                                isApiCalling && item.message.reasoning != null && reasoningCompleteMap[item.message.id] != true
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
                                AiMessageItem(
                                    message = message,
                                    text = item.text,
                                    maxWidth = bubbleMaxWidth,
                                    hasReasoning = item.hasReasoning,
                                    onLongPress = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onShowAiMessageOptions(message)
                                    },
                                    isStreaming = viewModel.currentTextStreamingAiMessageId.collectAsState().value == message.id,
                                    messageOutputType = message.outputType
                                )
                            }
                        }

                        is ChatListItem.AiMessageMath -> {
                            val message = viewModel.getMessageById(item.messageId)
                            if (message != null) {
                                AiMessageItem(
                                    message = message,
                                    text = item.text,
                                    maxWidth = bubbleMaxWidth,
                                    hasReasoning = item.hasReasoning,
                                    onLongPress = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onShowAiMessageOptions(message)
                                    },
                                    isStreaming = viewModel.currentTextStreamingAiMessageId.collectAsState().value == message.id,
                                    messageOutputType = message.outputType
                                )
                            }
                        }
                        is ChatListItem.AiMessageCode -> {
                            val message = viewModel.getMessageById(item.messageId)
                            if (message != null) {
                                AiMessageItem(
                                    message = message,
                                    text = "```${(message.outputType)}\n${item.text}\n```",
                                    maxWidth = bubbleMaxWidth,
                                    hasReasoning = item.hasReasoning,
                                    onLongPress = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onShowAiMessageOptions(message)
                                    },
                                    isStreaming = viewModel.currentTextStreamingAiMessageId.collectAsState().value == message.id,
                                    messageOutputType = message.outputType
                                )
                            }
                        }

                        is ChatListItem.AiMessageFooter -> {
                            AiMessageFooterItem(
                                message = item.message,
                                viewModel = viewModel,
                            )
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
                    }
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
                if (message.sender == com.example.everytalk.data.DataClass.Sender.User) {
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

private fun hasMathContent(text: String): Boolean {
    // 不再检测数学内容
    return false
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
    messageOutputType: String
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
            color = MaterialTheme.chatColors.aiBubble,
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
                EnhancedMarkdownText(
                    message = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    isStreaming = isStreaming,
                    messageOutputType = messageOutputType,
                    onLongPress = onLongPress
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