package com.android.everytalk.ui.screens.ImageGeneration
import com.android.everytalk.statecontroller.*

import android.annotation.SuppressLint
import com.android.everytalk.R
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.zIndex
import android.net.Uri
import android.graphics.drawable.Drawable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.asDrawable
import android.content.Context
import java.util.UUID
import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Base64
import android.widget.Toast
import com.android.everytalk.data.DataClass.Message
import android.graphics.BitmapFactory
import com.android.everytalk.data.network.SafeHttpDownloader
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.statecontroller.freezeWhileStreamingPaused
import com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem
import com.android.everytalk.ui.screens.MainScreen.chat.text.state.ChatScrollStateManager
import com.android.everytalk.ui.screens.BubbleMain.Main.AttachmentsContent
import com.android.everytalk.ui.screens.BubbleMain.Main.MessageContextMenu
import com.android.everytalk.ui.screens.BubbleMain.Main.ImageContextMenu
import com.android.everytalk.ui.screens.BubbleMain.Main.UserOrErrorMessageContent
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.HistoryLoadingBubblePlaceholderItem
import com.android.everytalk.ui.components.ChatMarkdownTextStyle
import com.android.everytalk.ui.components.streaming.UnifiedMarkdownRenderer
import com.android.everytalk.ui.components.streaming.buildStreamingRenderState
import com.android.everytalk.ui.theme.ChatDimensions
import com.android.everytalk.ui.theme.chatColors
import com.android.everytalk.ui.components.scrollFadeEdge
import com.android.everytalk.ui.topanchor.BottomScrollReason
import com.android.everytalk.ui.topanchor.RunTopAnchorReserveEngine
import com.android.everytalk.ui.topanchor.TopAnchorConfig
import com.android.everytalk.ui.topanchor.TopAnchorReserveEngineState
import com.android.everytalk.ui.topanchor.appendTopAnchorReserve
import com.android.everytalk.ui.topanchor.mapChatItemsToTopAnchorItems
import com.android.everytalk.ui.topanchor.resolveActiveTopAnchorTurn
import com.android.everytalk.ui.topanchor.resolveTopAnchorResponseTargetId
import com.android.everytalk.ui.topanchor.shouldAllowBottomScroll
import com.android.everytalk.util.storage.CappedByteArrayOutputStream
import com.android.everytalk.util.storage.readAtMost
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import coil3.size.Size
import kotlin.math.min

@OptIn(ExperimentalFoundationApi::class)
@Composable
@SuppressLint("StateFlowValueCalledInComposition")
fun ImageGenerationMessagesList(
    chatItems: List<ChatListItem>,
    viewModel: AppViewModel,
    listState: LazyListState,
    scrollStateManager: ChatScrollStateManager,
    bubbleMaxWidth: Dp,
    onShowAiMessageOptions: (Message) -> Unit,
    onImageLoaded: () -> Unit,
    additionalBottomPadding: Dp = 0.dp,
    scrollSessionKey: String = ""
) {
    val haptic = LocalHapticFeedback.current
    val animatedItems = remember(scrollSessionKey) { mutableStateMapOf<String, Boolean>() }
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
    var imagePreviewModels by remember { mutableStateOf<List<Any>>(emptyList()) }

    val allConversationImages: List<Any> = remember(chatItems) {
        chatItems.flatMap { item ->
            when (item) {
                is ChatListItem.UserMessage -> {
                    item.attachments.mapNotNull { att ->
                        when (att) {
                            is com.android.everytalk.models.SelectedMediaItem.ImageFromUri ->
                                att.filePath?.takeIf { it.isNotBlank() }
                                    ?: if (att.uri.scheme == "data") att.uri.toString() else att.uri
                            is com.android.everytalk.models.SelectedMediaItem.ImageFromBitmap ->
                                att.model
                            else -> null
                        }
                    }
                }
                is ChatListItem.AiMessage -> {
                    (item.message.imageUrls ?: emptyList()).map { it as Any }
                }
                else -> emptyList()
            }
        }
    }
    var currentImageIndex by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selectedImageConfig by viewModel.selectedImageGenApiConfig.collectAsState()
    val authToken = remember(selectedImageConfig) { selectedImageConfig?.key?.takeIf { it.isNotBlank() } }
    val refererHeader = remember(selectedImageConfig) { selectedImageConfig?.address?.takeIf { it.isNotBlank() } }
    val imageDownloadHeaders = remember(authToken, refererHeader) {
        buildMap {
            authToken?.let { put("Authorization", "Bearer $it") }
            refererHeader?.let { put("Referer", it) }
        }
    }

    suspend fun downloadImageBytes(url: String): Pair<ByteArray, String>? {
        val downloaded = SafeHttpDownloader.download(
            url = url,
            maxBytes = MAX_IMAGE_RAW_BYTES,
            timeoutMillis = IMAGE_DOWNLOAD_TIMEOUT_MS,
            accept = "image/*",
            headers = imageDownloadHeaders,
            trustedOrigin = refererHeader,
        )
        return downloaded.bytes.takeIf { it.isNotEmpty() }?.let { bytes ->
            bytes to downloaded.contentType.substringBefore(';').ifBlank { "image/png" }
        }
    }

    suspend fun cacheImageModelForEditing(model: Any): Uri? = withContext(Dispatchers.IO) {
        try {
            val bytesAndMime = when (model) {
                is String -> {
                    if (model.startsWith("data:", ignoreCase = true)) {
                        val mime = model.substringAfter("data:", "").substringBefore(";")
                            .takeIf { it.contains("image", ignoreCase = true) } ?: "image/png"
                        val bytes = decodeImageBase64DataUri(model) ?: return@withContext null
                        bytes to mime
                    } else {
                        val uri = runCatching { model.toUri() }.getOrNull()
                        when (uri?.scheme?.lowercase()) {
                            "http", "https" -> downloadImageBytes(model)
                            "content" -> context.contentResolver.openInputStream(uri)?.use { input ->
                                readAtMost(input, MAX_IMAGE_RAW_BYTES) to (context.contentResolver.getType(uri) ?: "image/png")
                            }
                            "file" -> uri.path?.let { path ->
                                File(path).takeIf { it.exists() }?.let { it.readImageBytesAtMost() to mimeFromImagePath(path) }
                            }
                            null -> File(model).takeIf { it.exists() }?.let { it.readImageBytesAtMost() to mimeFromImagePath(model) }
                            else -> null
                        }
                    }
                }
                is Uri -> when (model.scheme?.lowercase()) {
                    "content" -> context.contentResolver.openInputStream(model)?.use { input ->
                        readAtMost(input, MAX_IMAGE_RAW_BYTES) to (context.contentResolver.getType(model) ?: "image/png")
                    }
                    "file" -> model.path?.let { path ->
                        File(path).takeIf { it.exists() }?.let { it.readImageBytesAtMost() to mimeFromImagePath(path) }
                    }
                    "http", "https" -> downloadImageBytes(model.toString())
                    else -> null
                }
                else -> null
            } ?: return@withContext null

            val (bytes, mime) = bytesAndMime
            val ext = when (mime.lowercase().substringBefore(";")) {
                "image/jpeg", "image/jpg" -> "jpg"
                "image/webp" -> "webp"
                else -> "png"
            }
            val file = createTemporaryImageFile(context, "preview_cache", "img", ext)
            FileOutputStream(file).use { it.write(bytes) }
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ImagePreview", "cacheImageModelForEditing failed: ${e.message}", e)
            null
        }
    }

    fun openImagePreview(model: Any) {
        imagePreviewModel = model
        val normalizedModel = com.android.everytalk.ui.components.image.normalizeImageSourceForComparison(model.toString())
        val index = allConversationImages.indexOfFirst { img ->
            com.android.everytalk.ui.components.image.normalizeImageSourceForComparison(img.toString()) == normalizedModel
        }
        currentImageIndex = if (index >= 0) index else 0
        imagePreviewModels = if (allConversationImages.isNotEmpty()) allConversationImages else listOf(model)
        isImagePreviewVisible = true
    }

    fun dismissImagePreview() {
        isImagePreviewVisible = false
        imagePreviewModel = null
        imagePreviewModels = emptyList()
        currentImageIndex = 0
    }

    val pauseAwareApiCalling = remember(viewModel) {
        viewModel.isImageApiCalling.freezeWhileStreamingPaused(viewModel.isStreamingPaused)
    }
    val pauseAwareStreamingId = remember(viewModel) {
        viewModel.currentImageStreamingAiMessageId.freezeWhileStreamingPaused(viewModel.isStreamingPaused)
    }
    val isApiCalling by pauseAwareApiCalling.collectAsState(initial = viewModel.isImageApiCalling.value)
    val currentStreamingId by pauseAwareStreamingId.collectAsState(
        initial = viewModel.currentImageStreamingAiMessageId.value
    )

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topPadding = statusBarTop + 72.dp
    val topPaddingPx = with(density) { topPadding.toPx().toInt() }
    val topAnchorEngine = remember(scrollSessionKey) { TopAnchorReserveEngineState() }
    val lastSentImageUserMessageId by viewModel.lastSentImageUserMessageId.collectAsState()
    val topAnchorItems = remember(chatItems) {
        mapChatItemsToTopAnchorItems(
            items = chatItems,
            resolveErrorSender = { messageId -> viewModel.getMessageById(messageId)?.sender }
        )
    }
    val activeTurn = remember(
        topAnchorItems,
        lastSentImageUserMessageId,
        scrollSessionKey,
        chatItems.size,
    ) {
        resolveActiveTopAnchorTurn(
            items = topAnchorItems,
            sentUserMessageId = lastSentImageUserMessageId,
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
    val guardedOnImageLoaded = {
        if (shouldAllowBottomScroll(
                isUserAction = false,
                suppressesBottomScroll = topAnchorEngine.runtime.suppressesBottomScroll,
                isAtBottom = scrollStateManager.isAtBottom.value,
                reason = BottomScrollReason.ImageLoaded
            )
        ) {
            onImageLoaded()
        }
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
        viewModel.consumeLastSentImageUserMessageId(turn.anchorMessageId)
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
    BoxWithConstraints(
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
                    tallAnchorThresholdPx = with(density) { 240.dp.toPx().toInt() },
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

        if (chatItems.isEmpty()) {
            if (isApiCalling) {
                ImageGenerationLoadingView()
            } else {
                EmptyImageGenerationView()
            }
        } else {
            LazyColumn(
                state = listState,
                userScrollEnabled = topAnchorEngine.userScrollEnabled,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollStateManager.nestedScrollConnection),
                contentPadding = PaddingValues(
                    start = 6.dp,
                    end = 16.dp,
                    top = topPadding,
                    bottom = additionalBottomPadding + 25.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
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
                        .appendTopAnchorReserve(
                            if (index == chatItems.lastIndex) topAnchorEngine.reservePx else 0
                        )
                ) {
                    when (item) {
                        is ChatListItem.UserMessage -> {
                            val message = viewModel.getMessageById(item.messageId)
                            if (message != null) {
                                // 使用 Row + Arrangement.End 强制右贴齐，避免重组导致漂移
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Column(
                                        modifier = Modifier.wrapContentWidth(),
                                        horizontalAlignment = Alignment.End
                                    ) {
                                        if (!item.attachments.isNullOrEmpty()) {
                                            AttachmentsContent(
                                                attachments = item.attachments,
                                                onAttachmentClick = { att ->
                                                    when (att) {
                                                        is com.android.everytalk.models.SelectedMediaItem.ImageFromUri -> {
                                                            val model = if (att.uri.scheme == "data") {
                                                                att.uri.toString()
                                                            } else {
                                                                att.uri
                                                            }
                                                            openImagePreview(model)
                                                        }
                                                        is com.android.everytalk.models.SelectedMediaItem.ImageFromBitmap -> {
                                                            openImagePreview(att.model)
                                                        }
                                                        else -> { /* 其他类型暂不预览 */ }
                                                    }
                                                },
                                                maxWidth = bubbleMaxWidth * ChatDimensions.USER_BUBBLE_WIDTH_RATIO,
                                                message = message,
                                                onEditRequest = { viewModel.requestEditMessage(it) },
                                                onRegenerateRequest = {
                                                    scrollStateManager.lockAutoScroll()
                                                    viewModel.regenerateAiResponse(it, isImageGeneration = true, scrollToNewMessage = true)
                                                },
                                                onLongPress = { msg, offset ->
                                                    contextMenuMessage = msg
                                                    contextMenuPressOffset = offset
                                                    isContextMenuVisible = true
                                                },
                                                onImageLoaded = guardedOnImageLoaded,
                                                bubbleColor = MaterialTheme.chatColors.userBubble,
                                                scrollStateManager = scrollStateManager,
                                                onImageClick = { url ->
                                                    openImagePreview(url)
                                                }
                                            )
                                        }
                                        if (item.text.isNotBlank()) {
                                            // 复用文本气泡渲染，与文本模式一致
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

                        is ChatListItem.AiMessage -> {
                            val message = item.message
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
                                    openImagePreview(model)
                                },
                                isStreaming = currentStreamingId == message.id,
                                onImageLoaded = guardedOnImageLoaded,
                                scrollStateManager = scrollStateManager,
                                viewModel = viewModel,
                                modifier = Modifier
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
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        contextMenuMessage = msg
                                        contextMenuPressOffset = offset
                                        isContextMenuVisible = true
                                    },
                                    scrollStateManager = scrollStateManager
                                )
                            }
                        }
                        is ChatListItem.LoadingIndicator -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                ImageGenLoadingIndicator(text = item.text)
                            }
                        }
                        is ChatListItem.LoadingBubblePlaceholder -> {
                            HistoryLoadingBubblePlaceholderItem(
                                role = item.role,
                                widthFraction = item.widthFraction,
                                estimatedHeight = item.estimatedHeightDp.dp,
                            )
                        }
                        else -> {}
                    }
                }
            }
            }
        }

        // 其他代码保持不变
        contextMenuMessage?.let { message ->
            MessageContextMenu(
                isVisible = isContextMenuVisible,
                message = message,
                pressOffset = with(density) {
                    if (message.sender == com.android.everytalk.data.DataClass.Sender.User) {
                        // 图像模式用户气泡
                        Offset(contextMenuPressOffset.x, contextMenuPressOffset.y)
                    } else {
                        // 图像模式 AI 气泡）
                        Offset(contextMenuPressOffset.x, contextMenuPressOffset.y)
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
                    scrollStateManager.lockAutoScroll()
                    viewModel.regenerateAiResponse(it, isImageGeneration = true, scrollToNewMessage = true)
                    isContextMenuVisible = false
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
                        openImagePreview(firstUrl)
                    }
                    isImageMenuVisible = false
                },
                onDownload = { msg ->
                    viewModel.downloadImageFromMessage(msg)
                    isImageMenuVisible = false
                },
                onEdit = { msg ->
                    val firstUrl = msg.imageUrls?.firstOrNull()
                    if (!firstUrl.isNullOrBlank()) {
                        scope.launch {
                            val uri = cacheImageModelForEditing(firstUrl)
                            if (uri == null) {
                                viewModel.showSnackbar("加载失败")
                                return@launch
                            }
                            viewModel.addMediaItem(
                                SelectedMediaItem.ImageFromUri(
                                    uri = uri,
                                    id = UUID.randomUUID().toString(),
                                    filePath = null
                                )
                            )
                            viewModel.showSnackbar("已选择")
                        }
                    }
                    isImageMenuVisible = false
                }
            )
        }

        // 全屏黑底图片预览（图1风格）+ 手势缩放 + 保存/分享 + 左右滑动切换
        ImageGenerationImagePreview(
            isImagePreviewVisible = isImagePreviewVisible,
            imagePreviewModel = imagePreviewModel,
            imagePreviewModels = imagePreviewModels,
            currentImageIndex = currentImageIndex,
            context = context,
            viewModel = viewModel,
            scope = scope,
            imageDownloadHeaders = imageDownloadHeaders,
            refererHeader = refererHeader,
            onImagePreviewModelChanged = { imagePreviewModel = it },
            onImageIndexChanged = { currentImageIndex = it },
            onDismiss = ::dismissImagePreview,
        )
    }
}
