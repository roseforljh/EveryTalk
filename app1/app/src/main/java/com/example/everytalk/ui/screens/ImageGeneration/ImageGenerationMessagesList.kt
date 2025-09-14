package com.example.everytalk.ui.screens.ImageGeneration

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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.net.Uri
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.example.everytalk.R
import java.util.UUID
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.models.SelectedMediaItem
import com.example.everytalk.statecontroller.AppViewModel
import com.example.everytalk.ui.screens.MainScreen.chat.ChatListItem
import com.example.everytalk.ui.screens.MainScreen.chat.ChatScrollStateManager
import com.example.everytalk.ui.screens.BubbleMain.Main.AttachmentsContent
import com.example.everytalk.ui.screens.BubbleMain.Main.UserOrErrorMessageContent
import com.example.everytalk.ui.screens.BubbleMain.Main.MessageContextMenu
import com.example.everytalk.ui.screens.BubbleMain.Main.ImageContextMenu
import com.example.everytalk.ui.screens.BubbleMain.Main.ThreeDotsWaveAnimation
import com.example.everytalk.ui.theme.ChatDimensions
import com.example.everytalk.ui.theme.chatColors
import com.example.everytalk.ui.components.EnhancedMarkdownText
import com.example.everytalk.ui.components.normalizeMarkdownGlyphs
import com.example.everytalk.ui.components.parseMarkdownParts
import kotlinx.coroutines.launch

@Composable
fun ImageGenerationLoadingView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            ThreeDotsWaveAnimation(
                dotColor = MaterialTheme.colorScheme.primary,
                dotSize = 12.dp,
                spacing = 8.dp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "正在连接图像大模型...",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageGenerationMessagesList(
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
    val density = LocalDensity.current
 
    var isContextMenuVisible by remember { mutableStateOf(false) }
    var contextMenuMessage by remember { mutableStateOf<Message?>(null) }
    var contextMenuPressOffset by remember { mutableStateOf(Offset.Zero) }

    // 图片专用菜单状态
    var isImageMenuVisible by remember { mutableStateOf(false) }
    var imageMenuMessage by remember { mutableStateOf<Message?>(null) }
    var imageMenuPressOffset by remember { mutableStateOf(Offset.Zero) }

    // 图片预览对话框状态
    var isImagePreviewVisible by remember { mutableStateOf(false) }
    var imagePreviewModel by remember { mutableStateOf<Any?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        val isApiCalling by viewModel.isImageApiCalling.collectAsState()

        if (chatItems.isEmpty()) {
            if (isApiCalling) {
                ImageGenerationLoadingView()
            } else {
                EmptyImageGenerationView()
            }
        } else {
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
                val translationY = remember { Animatable(50f) }

                LaunchedEffect(item.stableId) {
                    if (animatedItems[item.stableId] != true) {
                        launch {
                            alpha.animateTo(1f, animationSpec = tween(durationMillis = 300))
                        }
                        launch {
                            translationY.animateTo(0f, animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing))
                        }
                        animatedItems[item.stableId] = true
                    } else {
                        alpha.snapTo(1f)
                        translationY.snapTo(0f)
                    }
                }

                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            this.alpha = alpha.value
                            this.translationY = translationY.value
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
                                            onAttachmentClick = { att ->
                                                when (att) {
                                                    is com.example.everytalk.models.SelectedMediaItem.ImageFromUri -> {
                                                        imagePreviewModel = att.uri
                                                        isImagePreviewVisible = true
                                                    }
                                                    is com.example.everytalk.models.SelectedMediaItem.ImageFromBitmap -> {
                                                        imagePreviewModel = att.bitmap
                                                        isImagePreviewVisible = true
                                                    }
                                                    else -> { /* 其他类型暂不预览 */ }
                                                }
                                            },
                                            maxWidth = bubbleMaxWidth * ChatDimensions.BUBBLE_WIDTH_RATIO,
                                            message = message,
                                            onEditRequest = { viewModel.requestEditMessage(it) },
                                            onRegenerateRequest = {
                                                viewModel.regenerateAiResponse(it, isImageGeneration = true)
                                                scrollStateManager.jumpToBottom()
                                            },
                                           onLongPress = { msg, offset ->
                                                contextMenuMessage = msg
                                                contextMenuPressOffset = offset
                                                isContextMenuVisible = true
                                            },
                                            onImageLoaded = onImageLoaded,
                                            bubbleColor = MaterialTheme.chatColors.userBubble,
                                            scrollStateManager = scrollStateManager
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

                        is ChatListItem.AiMessage -> {
                            val message = viewModel.getMessageById(item.messageId)
                            if (message != null) {
                                AiMessageItem(
                                    message = message,
                                    text = item.text,
                                    maxWidth = bubbleMaxWidth,
                                    onLongPress = { msg, pressOffset ->
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (msg.imageUrls?.isNotEmpty() == true) {
                                            imageMenuMessage = msg
                                            imageMenuPressOffset = pressOffset
                                            isImageMenuVisible = true
                                        } else {
                                            onShowAiMessageOptions(msg)
                                        }
                                    },
                                    onOpenPreview = { model ->
                                        imagePreviewModel = model
                                        isImagePreviewVisible = true
                                    },
                                    isStreaming = viewModel.currentImageStreamingAiMessageId.collectAsState().value == message.id,
                                    onImageLoaded = onImageLoaded,
                                    scrollStateManager = scrollStateManager
                                )
                            }
                        }
                        is ChatListItem.LoadingIndicator -> {
                            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                                ThreeDotsWaveAnimation()
                            }
                        }
                        else -> {}
                    }
                }
            }
                item(key = "chat_screen_footer_spacer_in_list") {
                    Spacer(modifier = Modifier.height(1.dp))
                }
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
                        Offset(contextMenuPressOffset.x, contextMenuPressOffset.y + 100.dp.toPx())
                    }
                },
                onDismiss = { isContextMenuVisible = false },
                onCopy = {
                    viewModel.copyToClipboard(it.text)
                    isContextMenuVisible = false
                },
                onEdit = {
                    viewModel.requestEditMessage(it, isImageGeneration = true)
                    isContextMenuVisible = false
                },
                onRegenerate = {
                    scrollStateManager.resetScrollState()
                    viewModel.regenerateAiResponse(it, isImageGeneration = true)
                    isContextMenuVisible = false
                    coroutineScope.launch {
                        scrollStateManager.jumpToBottom()
                    }
                }
            )
        }

        // 图片长按菜单：查看/下载（应用内预览 + 下载）
        imageMenuMessage?.let { message ->
            ImageContextMenu(
                isVisible = isImageMenuVisible,
                message = message,
                pressOffset = imageMenuPressOffset,
                onDismiss = { isImageMenuVisible = false },
                onView = { msg ->
                    val firstUrl = msg.imageUrls?.firstOrNull()
                    if (!firstUrl.isNullOrBlank()) {
                        imagePreviewModel = firstUrl // 可为 String 或 Uri，AsyncImage 都支持
                        isImagePreviewVisible = true
                    }
                    isImageMenuVisible = false
                },
                onDownload = { msg ->
                    viewModel.downloadImageFromMessage(msg)
                    isImageMenuVisible = false
                }
            )
        }

        // 内置图片预览对话框
        if (isImagePreviewVisible && imagePreviewModel != null) {
            Dialog(
                onDismissRequest = { isImagePreviewVisible = false },
                properties = DialogProperties(
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                    usePlatformDefaultWidth = false
                )
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = imagePreviewModel,
                            contentDescription = "预览图片",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                        )
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                        ) {
                            TextButton(onClick = { isImagePreviewVisible = false }) {
                                Text("关闭")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiMessageItem(
    message: Message,
    text: String,
    maxWidth: Dp,
    onLongPress: (Message, Offset) -> Unit,
    onOpenPreview: (Any) -> Unit,
    modifier: Modifier = Modifier,
    isStreaming: Boolean,
    onImageLoaded: () -> Unit,
    scrollStateManager: ChatScrollStateManager
) {
    val shape = RoundedCornerShape(
        topStart = ChatDimensions.CORNER_RADIUS_LARGE,
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
                    onLongPress = { localOffset ->
                        // 非图片区域长按，使用本地偏移；图片区域长按由 AttachmentsContent 传递全局坐标
                        onLongPress(message, localOffset)
                    }
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
                Column {
                    if (text.isNotBlank()) {
                        val parts = remember(text) { parseMarkdownParts(normalizeMarkdownGlyphs(text)) }
                        EnhancedMarkdownText(
                            parts = parts,
                            rawMarkdown = text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            isStreaming = isStreaming,
                            messageOutputType = message.outputType
                        )
                    }
                    if (message.imageUrls != null && message.imageUrls.isNotEmpty()) {
                        // Add a little space between text and image
                        if (text.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        AttachmentsContent(
                            attachments = message.imageUrls.map { SelectedMediaItem.ImageFromUri(Uri.parse(it), UUID.randomUUID().toString()) },
                            onAttachmentClick = { att ->
                                when (att) {
                                    is SelectedMediaItem.ImageFromUri -> onOpenPreview(att.uri)
                                    is SelectedMediaItem.ImageFromBitmap -> onOpenPreview(att.bitmap)
                                    else -> { /* ignore */ }
                                }
                            },
                            maxWidth = maxWidth,
                            message = message,
                            onEditRequest = {},
                            onRegenerateRequest = {},
                            onLongPress = { msg, offset -> onLongPress(msg, offset) },
                            onImageLoaded = onImageLoaded,
                            bubbleColor = MaterialTheme.chatColors.aiBubble,
                            scrollStateManager = scrollStateManager
                        )
                    }
                }
            }
        }
    }
}