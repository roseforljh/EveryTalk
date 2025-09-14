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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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

import com.example.everytalk.ui.components.MathView
import com.example.everytalk.ui.components.EnhancedMarkdownText
import com.example.everytalk.ui.components.CodePreview
import com.example.everytalk.ui.components.normalizeMarkdownGlyphs
import com.example.everytalk.ui.components.parseMarkdownParts
import com.example.everytalk.ui.components.normalizeBasicMarkdown
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

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
    val animatedItems = remember { mutableStateMapOf<String, Boolean>() }

    var isContextMenuVisible by remember { mutableStateOf(false) }
    var contextMenuMessage by remember { mutableStateOf<Message?>(null) }
    var contextMenuPressOffset by remember { mutableStateOf(Offset.Zero) }

    val isApiCalling by viewModel.isTextApiCalling.collectAsState()
   val density = LocalDensity.current

    LaunchedEffect(chatItems) {
        if (chatItems.lastOrNull() is ChatListItem.AiMessageReasoning) {
            scrollStateManager.jumpToBottom()
        }
    }

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
            val alpha = remember { Animatable(0f) }

            LaunchedEffect(item.stableId) {
                if (animatedItems[item.stableId] != true) {
                    launch {
                        alpha.animateTo(1f, animationSpec = tween(durationMillis = 180))
                    }
                    animatedItems[item.stableId] = true
                } else {
                    alpha.snapTo(1f)
                }
            }

            Box(
                modifier = Modifier
                    .graphicsLayer {
                        this.alpha = alpha.value
                    }
            ) {
                when (item) {
                    is ChatListItem.UserMessage -> {
                        val message = viewModel.getMessageById(item.messageId)
                        if (message != null) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.End
                            ) {
                                if (!item.attachments.isNullOrEmpty()) {
                                    AttachmentsContent(
                                        attachments = item.attachments,
                                        onAttachmentClick = { },
                                        maxWidth = bubbleMaxWidth * ChatDimensions.BUBBLE_WIDTH_RATIO,
                                        message = message,
                                        onEditRequest = { viewModel.requestEditMessage(it) },
                                        onRegenerateRequest = {
                                            viewModel.regenerateAiResponse(it, isImageGeneration = false)
                                            scrollStateManager.jumpToBottom()
                                        },
                                       onLongPress = { message, offset ->
                                            contextMenuMessage = message
                                            contextMenuPressOffset = offset
                                            isContextMenuVisible = true
                                        },
                                        scrollStateManager = scrollStateManager,
                                        onImageLoaded = onImageLoaded,
                                        bubbleColor = MaterialTheme.chatColors.userBubble
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
                                        maxWidth = bubbleMaxWidth * ChatDimensions.BUBBLE_WIDTH_RATIO,
                                        onLongPress = { message, offset ->
                                            contextMenuMessage = message
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
                                onLongPress = { message, offset ->
                                    contextMenuMessage = message
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

@Composable
private fun AiMessageItem(
    message: Message,
    text: String,
    maxWidth: Dp,
    hasReasoning: Boolean,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    isStreaming: Boolean,
    messageOutputType: String
) {
    val shape = RoundedCornerShape(
        topStart = if (!hasReasoning) ChatDimensions.CORNER_RADIUS_LARGE else ChatDimensions.CORNER_RADIUS_SMALL,
        topEnd = ChatDimensions.CORNER_RADIUS_LARGE,
        bottomStart = ChatDimensions.CORNER_RADIUS_LARGE,
        bottomEnd = ChatDimensions.CORNER_RADIUS_LARGE
    )
    val aiReplyMessageDescription = stringResource(id = R.string.ai_reply_message)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(message.id) {
                detectTapGestures(
                    onLongPress = { onLongPress() }
                )
            },
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = aiReplyMessageDescription
                },
            shape = shape,
            color = MaterialTheme.chatColors.aiBubble,
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
                var parts by remember { mutableStateOf<List<com.example.everytalk.ui.components.MarkdownPart>>(emptyList()) }

                LaunchedEffect(text, isStreaming) {
                    if (isStreaming) {
                        delay(100L) // 流式输出时节流，每100ms解析一次
                    }
                    parts = parseMarkdownParts(normalizeBasicMarkdown(text))
                }

                EnhancedMarkdownText(
                    parts = parts,
                    rawMarkdown = text,
                    messageId = message.id,
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
private fun AiMessageFooterItem(
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