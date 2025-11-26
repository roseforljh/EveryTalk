package com.android.everytalk.ui.screens.ImageGeneration

import com.android.everytalk.R
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import android.net.Uri
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
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import java.io.FileOutputStream
import android.widget.Toast
import com.android.everytalk.data.DataClass.Message
import okhttp3.OkHttpClient
import okhttp3.Request
import android.graphics.BitmapFactory
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.ui.screens.MainScreen.chat.ChatListItem
import com.android.everytalk.ui.screens.MainScreen.chat.ChatScrollStateManager
import com.android.everytalk.ui.screens.BubbleMain.Main.AttachmentsContent
import com.android.everytalk.ui.screens.BubbleMain.Main.MessageContextMenu
import com.android.everytalk.ui.screens.BubbleMain.Main.ImageContextMenu
import com.android.everytalk.ui.theme.ChatDimensions
import com.android.everytalk.ui.theme.chatColors
import com.android.everytalk.ui.components.EnhancedMarkdownText
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ImageGenerationLoadingView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Start
        ) {
            val style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.ExtraBold,
            )
            Text("正在生成图像", style = style)

            val animY = remember { List(3) { Animatable(0f) } }
            val coroutineScope = rememberCoroutineScope()
            val density = LocalDensity.current

            LaunchedEffect(Unit) {
                animY.forEach { it.snapTo(0f) } // 初始化
                try {
                    repeat(Int.MAX_VALUE) {
                        animY.forEachIndexed { index, anim ->
                            launch {
                                kotlinx.coroutines.delay((index * 150L) % 450)
                                anim.animateTo(
                                    targetValue = with(density) { (-6).dp.toPx() },
                                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                                )
                                anim.animateTo(
                                    targetValue = 0f,
                                    animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing)
                                )
                                if (index == animY.lastIndex) kotlinx.coroutines.delay(600)
                            }
                        }
                        kotlinx.coroutines.delay(1200)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    coroutineScope.launch { animY.forEach { launch { it.snapTo(0f) } } }
                }
            }

            animY.forEach {
                Text(
                    text = ".",
                    style = style,
                    modifier = Modifier.offset(y = with(density) { it.value.toDp() })
                )
            }
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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val availableHeight = maxHeight
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
                                                    is com.android.everytalk.models.SelectedMediaItem.ImageFromUri -> {
                                                        // 🔥 修复：如果是 data URI，使用字符串而不是 Uri 对象
                                                        imagePreviewModel = if (att.uri.scheme == "data") {
                                                            att.uri.toString()
                                                        } else {
                                                            att.uri
                                                        }
                                                        isImagePreviewVisible = true
                                                    }
                                                    is com.android.everytalk.models.SelectedMediaItem.ImageFromBitmap -> {
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
                                        // 用户气泡：右对齐 + 自适应宽度（右上角直角，其他圆角）
                                        var bubbleGlobalPosition by remember { mutableStateOf(Offset.Zero) }
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
                                                            // 图像模式下用户气泡补充震动 + 全局坐标
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
                                                modifier = Modifier.padding(
                                                    horizontal = ChatDimensions.BUBBLE_INNER_PADDING_HORIZONTAL,
                                                    vertical = ChatDimensions.BUBBLE_INNER_PADDING_VERTICAL
                                                )
                                            ) {
                                                Text(
                                                    text = item.text,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    textAlign = androidx.compose.ui.text.style.TextAlign.Start
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        is ChatListItem.AiMessage -> {
                            val message = viewModel.getMessageById(item.messageId)
                            android.util.Log.d("ImageGenMessagesList", "🖼️ [UI] Rendering AI message: id=${message?.id?.take(8)}, hasImageUrls=${message?.imageUrls?.isNotEmpty()}, imageUrlsCount=${message?.imageUrls?.size}")
                            if (message != null) {
                                val isLastItem = index == chatItems.lastIndex
                                val shouldApplyMinHeight = isLastItem && chatItems.size > 2
                                
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
                                    scrollStateManager = scrollStateManager,
                                    viewModel = viewModel,
                                    modifier = if (shouldApplyMinHeight) {
                                        Modifier.heightIn(min = availableHeight * 0.85f)
                                    } else {
                                        Modifier
                                    }
                                )
                            }
                        }
                        is ChatListItem.LoadingIndicator -> {
                            val isLastItem = index == chatItems.lastIndex
                            val shouldApplyMinHeight = isLastItem && chatItems.size > 2
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp)
                                    .then(
                                        if (shouldApplyMinHeight) {
                                            Modifier.heightIn(min = availableHeight * 0.85f)
                                        } else {
                                            Modifier
                                        }
                                    ),
                                contentAlignment = if (shouldApplyMinHeight) Alignment.TopStart else Alignment.CenterStart
                            ) {
                                Row(
                                    verticalAlignment = Alignment.Bottom,
                                    horizontalArrangement = Arrangement.Start
                                ) {
                                    val style = MaterialTheme.typography.bodyLarge.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                    )
                                    Text("正在生成图像", style = style)
                                    val animY = remember { List(3) { Animatable(0f) } }
                                    val coroutineScope = rememberCoroutineScope()
                                    val density = LocalDensity.current
                                    LaunchedEffect(Unit) {
                                        animY.forEach { it.snapTo(0f) }
                                        try {
                                            repeat(Int.MAX_VALUE) {
                                                animY.forEachIndexed { index, anim ->
                                                    launch {
                                                        kotlinx.coroutines.delay((index * 150L) % 450)
                                                        anim.animateTo(
                                                            targetValue = with(density) { (-6).dp.toPx() },
                                                            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                                                        )
                                                        anim.animateTo(
                                                            targetValue = 0f,
                                                            animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing)
                                                        )
                                                        if (index == animY.lastIndex) kotlinx.coroutines.delay(600)
                                                    }
                                                }
                                                kotlinx.coroutines.delay(1200)
                                            }
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            coroutineScope.launch { animY.forEach { launch { it.snapTo(0f) } } }
                                        }
                                    }
                                    animY.forEach {
                                        Text(
                                            text = ".",
                                            style = style,
                                            modifier = Modifier.offset(y = with(density) { it.value.toDp() })
                                        )
                                    }
                                }
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

        // 全屏黑底图片预览（图1风格）+ 手势缩放 + 保存/分享
        if (isImagePreviewVisible && imagePreviewModel != null) {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            // 当前选中的图像生成配置（用于附加鉴权/来源头）
            val selectedImageConfig by viewModel.selectedImageGenApiConfig.collectAsState()
            val authToken = remember(selectedImageConfig) { selectedImageConfig?.key?.takeIf { it.isNotBlank() } }
            val refererHeader = remember(selectedImageConfig) { selectedImageConfig?.address?.takeIf { it.isNotBlank() } }

            // 手势缩放/平移状态
            var scale by remember { mutableStateOf(1f) }
            var offsetX by remember { mutableStateOf(0f) }
            var offsetY by remember { mutableStateOf(0f) }

            // 简易画笔编辑器状态
            var isBrushing by remember { mutableStateOf(false) }
            var brushBaseBitmap by remember { mutableStateOf<Bitmap?>(null) }

            fun resetTransform() {
                scale = 1f; offsetX = 0f; offsetY = 0f
            }

            // 解析当前 model 为 Bitmap（切到IO线程；尽量兜底各种来源）
            // 优先通过 Coil 获取（含缓存或网络），失败再回退手写网络
            suspend fun loadBitmapWithCache(
                model: Any,
                context: Context
            ): Bitmap? {
                // Coil 建议在主线程初始化 Request，但 execute 可在挂起函数中调用
                // 为确保安全，我们切换到 Main 调度器构建 Request，再执行
                return withContext(Dispatchers.Main) {
                    try {
                        val imageLoader = context.imageLoader
                        val request = ImageRequest.Builder(context)
                            .data(model)
                            .allowHardware(false) // 禁用硬件位图以便后续写入
                            // 必须指定 size，否则某些情况下 Coil 无法确定尺寸会导致加载失败
                            .size(coil3.size.Size.ORIGINAL)
                            .build()
                        
                        // execute 是挂起函数，会处理线程切换
                        val result = imageLoader.execute(request)
                        
                        if (result is SuccessResult) {
                            // 修复：coil3.Image 需要转为 Drawable 才能获取宽高和转换为 Bitmap
                            // Coil 3.x 的 result.image 是 coil3.Image 类型，需要用 asDrawable(Resources) 转为 Android Drawable
                            val image = result.image
                            val drawable = image?.asDrawable(context.resources)
                            
                            if (drawable != null) {
                                val w = drawable.intrinsicWidth.coerceAtLeast(1)
                                val h = drawable.intrinsicHeight.coerceAtLeast(1)
                                // 使用 AndroidX 的 toBitmap，避免手动离屏绘制易错
                                val bmp = drawable.toBitmap(w, h, Bitmap.Config.ARGB_8888)
                                android.util.Log.d("ImagePreview", "Bitmap obtained via Coil cache/network.")
                                return@withContext bmp
                            }
                        } else {
                            android.util.Log.w("ImagePreview", "Coil execute returned non-success result.")
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("ImagePreview", "Coil load failed, will fallback. Error: ${e.message}")
                    }
                    null
                }
            }


            // 统一的 HTTP 下载（附加鉴权/来源头，含重定向）
            suspend fun httpGetBitmap(urlStr: String): Bitmap? = withContext(Dispatchers.IO) {
                try {
                    val client = OkHttpClient.Builder()
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .build()
                    val builder = Request.Builder()
                        .url(urlStr)
                        .header("User-Agent", "EveryTalk/1.0 (Android)")
                        .header("Accept", "image/*")
                    authToken?.let { builder.header("Authorization", "Bearer $it") }
                    refererHeader?.let { builder.header("Referer", it) }
                    client.newCall(builder.build()).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            android.util.Log.w("ImagePreview", "HTTP code=${resp.code} for $urlStr")
                            return@use null
                        }
                        val bytes = resp.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            return@use BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                        null
                    }
                } catch (e: Exception) {
                    android.util.Log.w("ImagePreview", "HTTP load failed: ${e.message}")
                    null
                }
            }

            // 解析当前 model 为 Bitmap（切到IO线程；尽量兜底各种来源）
            suspend fun loadBitmapFromModel(model: Any): Bitmap? = withContext(Dispatchers.IO) {
                try {
                    android.util.Log.d("ImagePreview", "loadBitmapFromModel type=${model::class.java.name} value=$model")
                    when (model) {
                        is Bitmap -> return@withContext model
                        is Uri -> {
                            val scheme = model.scheme?.lowercase()
                            return@withContext when (scheme) {
                                "http", "https" -> {
                                    // 精简：与“长按-下载”一致，直接使用 OkHttp 获取
                                    httpGetBitmap(model.toString())
                                }
                                "content" -> {
                                    try {
                                        context.contentResolver.openInputStream(model)?.use { input ->
                                            BitmapFactory.decodeStream(input)
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w("ImagePreview", "Content read failed: ${e.message}")
                                        null
                                    }
                                }
                                "file" -> BitmapFactory.decodeFile(model.path)
                                else -> {
                                    val byFile = BitmapFactory.decodeFile(model.path)
                                    if (byFile != null) byFile else try {
                                        context.contentResolver.openInputStream(model)?.use { input ->
                                            BitmapFactory.decodeStream(input)
                                        }
                                    } catch (_: Exception) { null }
                                }
                            }
                        }
                        is String -> {
                            val s = model
                            // 修复：支持所有 data: 开头的 URI（包括 application/octet-stream）
                            if (s.startsWith("data:", ignoreCase = true)) {
                                val isBase64 = s.contains(";base64,", ignoreCase = true)
                                if (isBase64) {
                                    val base64 = s.substringAfter(";base64,", "")
                                    if (base64.isNotBlank()) {
                                        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                                        return@withContext BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    }
                                }
                            }
                            
                            val uri = try { Uri.parse(s) } catch (_: Exception) { null }
                            val scheme = uri?.scheme?.lowercase()
                            return@withContext when (scheme) {
                                "http", "https" -> {
                                    // 精简：与“长按-下载”一致，直接使用 OkHttp 获取
                                    httpGetBitmap(s)
                                }
                                "content" -> {
                                    try {
                                        context.contentResolver.openInputStream(uri!!)?.use {
                                            BitmapFactory.decodeStream(it)
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w("ImagePreview", "Content read failed: ${e.message}")
                                        null
                                    }
                                }
                                "file" -> BitmapFactory.decodeFile(uri?.path)
                                null -> BitmapFactory.decodeFile(s)
                                else -> {
                                    val bmp = BitmapFactory.decodeFile(s)
                                    if (bmp == null) {
                                        android.util.Log.w("ImagePreview", "Decode by file path failed for: $s (scheme=$scheme)")
                                    }
                                    bmp
                                }
                            }
                        }
                        else -> {
                            val s = model.toString()
                            val uri = try { Uri.parse(s) } catch (_: Exception) { null }
                            val scheme = uri?.scheme?.lowercase()
                            return@withContext when (scheme) {
                                "http", "https" -> {
                                    httpGetBitmap(s) ?: run {
                                        try {
                                            java.net.URL(s).openStream().use { input ->
                                                BitmapFactory.decodeStream(input)
                                            }
                                        } catch (_: Exception) { null }
                                    }
                                }
                                "content" -> {
                                    try {
                                        context.contentResolver.openInputStream(uri!!)?.use {
                                            BitmapFactory.decodeStream(it)
                                        }
                                    } catch (_: Exception) { null }
                                }
                                "file" -> BitmapFactory.decodeFile(uri?.path)
                                else -> BitmapFactory.decodeFile(s)
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ImagePreview", "loadBitmapFromModel error: ${e.message}", e)
                    null
                }?.also {
                    android.util.Log.d("ImagePreview", "Bitmap loaded")
                }
            }

            // 无损原始字节与 MIME 提取：基于 data:image、file、content、http(s)
            // 增加降级策略：如果直接获取失败（如链接失效），尝试从 Coil 缓存/显存获取 Bitmap 并转为 PNG
            suspend fun loadBytesAndMime(model: Any): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
                // 1. 尝试原始获取方式（保留原始格式）
                val primaryResult = try {
                    when (model) {
                        is String -> {
                            val s = model
                            // 修复：支持 application/octet-stream 等非标准 MIME 的 data URI
                            if (s.startsWith("data:", ignoreCase = true)) {
                                val mimePart = s.substringAfter("data:", "").substringBefore(";", "")
                                val isBase64 = s.contains(";base64,", ignoreCase = true)
                                
                                if (isBase64) {
                                    val base64 = s.substringAfter(";base64,", "")
                                    if (base64.isNotBlank()) {
                                        val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                                        // 如果 MIME 是 octet-stream，尝试修正为 image/png
                                        val finalMime = if (mimePart.contains("image", true)) mimePart else "image/png"
                                        android.util.Log.d("ImagePreview", "Decoded data URI with MIME: $finalMime")
                                        return@withContext bytes to finalMime
                                    }
                                }
                                // 如果不是 base64 或者是空，暂时不支持，走后续流程
                            }
                            
                            // 旧逻辑：仅检查 data:image
                            if (s.startsWith("data:image", ignoreCase = true)) {
                                val mime = s.substringAfter("data:", "").substringBefore(";base64", "")
                                val base64 = s.substringAfter(";base64,", "")
                                if (base64.isNotBlank()) {
                                    val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                                    return@withContext bytes to (mime.ifBlank { "image/png" })
                                }
                                return@withContext null
                            }
                            val uri = runCatching { Uri.parse(s) }.getOrNull()
                            val scheme = uri?.scheme?.lowercase()
                            return@withContext when (scheme) {
                                "http", "https" -> {
                                    val client = OkHttpClient.Builder()
                                        .followRedirects(true)
                                        .followSslRedirects(true)
                                        .build()
                                    val builder = Request.Builder().url(s)
                                        .header("User-Agent", "EveryTalk/1.0 (Android)")
                                        .header("Accept", "image/*")
                                    authToken?.let { builder.header("Authorization", "Bearer $it") }
                                    refererHeader?.let { builder.header("Referer", it) }
                                    client.newCall(builder.build()).execute().use { resp ->
                                        if (!resp.isSuccessful) return@use null
                                        val bytes = resp.body?.bytes() ?: return@use null
                                        val mime = resp.header("Content-Type") ?: "image/png"
                                        bytes to mime
                                    }
                                }
                                "content" -> {
                                    val mime = context.contentResolver.getType(uri!!)
                                        ?: "image/png"
                                    context.contentResolver.openInputStream(uri)?.use { input ->
                                        input.readBytes() to mime
                                    }
                                }
                                "file" -> {
                                    val path = uri?.path ?: return@withContext null
                                    val file = File(path)
                                    if (!file.exists()) return@withContext null
                                    val mime = when (file.extension.lowercase()) {
                                        "png" -> "image/png"
                                        "jpg", "jpeg" -> "image/jpeg"
                                        "webp" -> "image/webp"
                                        else -> "application/octet-stream"
                                    }
                                    file.readBytes() to mime
                                }
                                null -> {
                                    val file = File(s)
                                    if (!file.exists()) return@withContext null
                                    val mime = when (file.extension.lowercase()) {
                                        "png" -> "image/png"
                                        "jpg", "jpeg" -> "image/jpeg"
                                        "webp" -> "image/webp"
                                        else -> "application/octet-stream"
                                    }
                                    file.readBytes() to mime
                                }
                                else -> null
                            }
                        }
                        is Uri -> {
                            val scheme = model.scheme?.lowercase()
                            return@withContext when (scheme) {
                                "http", "https" -> {
                                    val client = OkHttpClient.Builder()
                                        .followRedirects(true)
                                        .followSslRedirects(true)
                                        .build()
                                    val builder = Request.Builder().url(model.toString())
                                        .header("User-Agent", "EveryTalk/1.0 (Android)")
                                        .header("Accept", "image/*")
                                    authToken?.let { builder.header("Authorization", "Bearer $it") }
                                    refererHeader?.let { builder.header("Referer", it) }
                                    client.newCall(builder.build()).execute().use { resp ->
                                        if (!resp.isSuccessful) return@use null
                                        val bytes = resp.body?.bytes() ?: return@use null
                                        val mime = resp.header("Content-Type") ?: "image/png"
                                        bytes to mime
                                    }
                                }
                                "content" -> {
                                    val mime = context.contentResolver.getType(model)
                                        ?: "image/png"
                                    context.contentResolver.openInputStream(model)?.use { input ->
                                        input.readBytes() to mime
                                    }
                                }
                                "file" -> {
                                    val path = model.path ?: return@withContext null
                                    val file = File(path)
                                    if (!file.exists()) return@withContext null
                                    val mime = when (file.extension.lowercase()) {
                                        "png" -> "image/png"
                                        "jpg", "jpeg" -> "image/jpeg"
                                        "webp" -> "image/webp"
                                        else -> "application/octet-stream"
                                    }
                                    file.readBytes() to mime
                                }
                                else -> null
                            }
                        }
                        is Bitmap -> {
                            // 内存位图（如编辑结果）选择 PNG 无损导出
                            val baos = java.io.ByteArrayOutputStream()
                            model.compress(Bitmap.CompressFormat.PNG, 100, baos)
                            baos.toByteArray() to "image/png"
                        }
                        else -> null
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ImagePreview", "loadBytesAndMime primary failed: ${e.message}", e)
                    null
                }

                if (primaryResult != null) return@withContext primaryResult

                // 2. 降级策略：从 Coil 缓存/渲染结果获取 Bitmap (所见即所得)
                android.util.Log.w("ImagePreview", "Primary load failed, falling back to Coil cache/render...")
                try {
                    val fallbackBitmap = loadBitmapWithCache(model, context)
                    if (fallbackBitmap != null) {
                        val baos = java.io.ByteArrayOutputStream()
                        fallbackBitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                        android.util.Log.i("ImagePreview", "Fallback success: recovered image from view/cache.")
                        return@withContext baos.toByteArray() to "image/png"
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ImagePreview", "Fallback failed: ${e.message}", e)
                }
                
                null
            }

            // 保存到相册（无损写入原始字节）
            fun saveToAlbum() {
                scope.launch {
                    try {
                        val pair = loadBytesAndMime(imagePreviewModel!!)
                        if (pair == null) {
                            android.util.Log.e("ImagePreview", "saveToAlbum failed: loadBytesAndMime returned null for model: $imagePreviewModel")
                            Toast.makeText(context, "无法加载图片", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        val (bytes, mime) = pair
                        val ext = when (mime.lowercase()) {
                            "image/png" -> "png"
                            "image/jpeg", "image/jpg" -> "jpg"
                            "image/webp" -> "webp"
                            else -> "img"
                        }
                        val resolver = context.contentResolver
                        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        else
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, "EveryTalk_${System.currentTimeMillis()}.$ext")
                            put(MediaStore.Images.Media.MIME_TYPE, mime)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                put(MediaStore.Images.Media.IS_PENDING, 1)
                            }
                        }
                        val uri = resolver.insert(collection, values)
                        if (uri == null) {
                            Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        resolver.openOutputStream(uri)?.use { os ->
                            os.write(bytes)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            values.clear()
                            values.put(MediaStore.Images.Media.IS_PENDING, 0)
                            resolver.update(uri, values, null, null)
                        }
                        Toast.makeText(context, "已保存到相册（无损）", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // 将当前模型转为可编辑/可分享的本地缓存文件Uri（FileProvider）- 无损写原始字节
            suspend fun ensureCacheFileUri(): Uri? {
                val pair = loadBytesAndMime(imagePreviewModel!!)
                if (pair == null) {
                    Toast.makeText(context, "无法加载图片", Toast.LENGTH_SHORT).show()
                    return null
                }
                val (bytes, mime) = pair
                val ext = when (mime.lowercase()) {
                    "image/png" -> "png"
                    "image/jpeg", "image/jpg" -> "jpg"
                    "image/webp" -> "webp"
                    else -> "img"
                }
                val cacheDir = File(context.cacheDir, "preview_cache").apply { mkdirs() }
                val file = File(cacheDir, "img_${System.currentTimeMillis()}.$ext")
                FileOutputStream(file).use { fos -> fos.write(bytes) }
                return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            }

            // 打开内置画笔编辑器
            fun openBrushEditor() {
                scope.launch {
                    val bmp = loadBitmapFromModel(imagePreviewModel!!)
                    if (bmp == null) {
                        Toast.makeText(context, "无法加载图片", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    brushBaseBitmap = bmp
                    isBrushing = true
                }
            }

            // 系统分享（无损写原始字节）
            fun shareImage() {
                scope.launch {
                    try {
                        val pair = loadBytesAndMime(imagePreviewModel!!)
                        if (pair == null) {
                            Toast.makeText(context, "无法加载图片", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        val (bytes, mime) = pair
                        val ext = when (mime.lowercase()) {
                            "image/png" -> "png"
                            "image/jpeg", "image/jpg" -> "jpg"
                            "image/webp" -> "webp"
                            else -> "img"
                        }
                        val cacheDir = File(context.cacheDir, "share_images").apply { mkdirs() }
                        val file = File(cacheDir, "share_${System.currentTimeMillis()}.$ext")
                        FileOutputStream(file).use { fos -> fos.write(bytes) }
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = mime
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            // 提高兼容性：通过 ClipData 传递并显式授权
                            clipData = android.content.ClipData.newUri(context.contentResolver, "image", uri)
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, "分享图片"))
                    } catch (e: Exception) {
                        Toast.makeText(context, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // 选择：把当前预览图片加入“已选择媒体”，供后续发送复用
            fun selectCurrentImage() {
                scope.launch {
                    try {
                        val uri = ensureCacheFileUri() ?: return@launch
                        viewModel.addMediaItem(
                            com.android.everytalk.models.SelectedMediaItem.ImageFromUri(
                                uri = uri,
                                id = UUID.randomUUID().toString(),
                                filePath = null
                            )
                        )
                        Toast.makeText(context, "已加入选择", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "选择失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // 编辑：改为“现在的选择功能”：加入已选择媒体并关闭预览返回
            fun editCurrentImage() {
                scope.launch {
                    val uri = ensureCacheFileUri()
                    if (uri == null) {
                        Toast.makeText(context, "无法加载图片", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    viewModel.addMediaItem(
                        com.android.everytalk.models.SelectedMediaItem.ImageFromUri(
                            uri = uri,
                            id = UUID.randomUUID().toString(),
                            filePath = null
                        )
                    )
                    Toast.makeText(context, "已加入选择", Toast.LENGTH_SHORT).show()
                    // 关闭预览对话框，返回图像模式页面
                    isImagePreviewVisible = false
                }
            }

            Dialog(
                onDismissRequest = { isImagePreviewVisible = false },
                properties = DialogProperties(
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                    usePlatformDefaultWidth = false
                )
            ) {
                Surface(
                    color = Color.Black,
                    contentColor = Color.White,
                    tonalElevation = 0.dp,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // 顶部工具栏：左关右更多
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .align(Alignment.TopCenter),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { isImagePreviewVisible = false }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "关闭预览",
                                    tint = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.width(48.dp))
                        }

                        // 居中展示图片，手势缩放与双击缩放
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = 36.dp) // 给顶部/底部留出空间
                                .pointerInput(imagePreviewModel) {
                                    detectTransformGestures { _, _, zoom, _ ->
                                        // 允许缩放，但不允许平移
                                        scale = (scale * zoom).coerceIn(1f, 6f)
                                        offsetX = 0f
                                        offsetY = 0f
                                    }
                                }
                                .pointerInput(imagePreviewModel) {
                                    detectTapGestures(
                                        onDoubleTap = {
                                            scale = if (scale > 1.5f) 1f else 2f
                                            if (scale == 1f) { offsetX = 0f; offsetY = 0f }
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = imagePreviewModel,
                                contentDescription = "预览图片",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        // 仅缩放，始终居中，不允许平移
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = 0f
                                        translationY = 0f
                                    },
                                contentScale = ContentScale.FillWidth
                            )
                        }

                        // 底部操作栏（编辑 / 选择 / 保存 / 共享）- 图标+小号加粗文字，增大间隔，定制按压动画
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 4.dp, start = 24.dp, end = 24.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BottomActionButton(
                                icon = Icons.Outlined.Edit,
                                label = "编辑",
                                onClick = { editCurrentImage() }
                            )
                            BottomActionButton(
                                icon = Icons.Outlined.Brush,
                                label = "选择",
                                onClick = { openBrushEditor() }
                            )
                            BottomActionButton(
                                icon = Icons.Outlined.Download,
                                label = "保存",
                                onClick = { saveToAlbum() }
                            )
                            BottomActionButton(
                                icon = Icons.Outlined.Share,
                                label = "共享",
                                onClick = { shareImage() }
                            )
                        }
                    }
                }
            }

            // 内置画笔编辑器覆盖层（使用全屏 Dialog 以保证位于预览之上）
            if (isBrushing && brushBaseBitmap != null) {
                Dialog(
                    onDismissRequest = { isBrushing = false },
                    properties = DialogProperties(
                        dismissOnBackPress = true,
                        dismissOnClickOutside = false,
                        usePlatformDefaultWidth = false
                    )
                ) {
                    BrushEditorOverlay(
                        baseBitmap = brushBaseBitmap!!,
                        onCancel = { isBrushing = false },
                        onDone = { edited ->
                            // 将编辑后的图片加入“已选择媒体”，并返回（关闭画笔和预览）
                            scope.launch {
                                try {
                                    val cacheDir = File(context.cacheDir, "preview_cache").apply { mkdirs() }
                                    val file = File(cacheDir, "edited_${System.currentTimeMillis()}.jpg")
                                    FileOutputStream(file).use { fos ->
                                        edited.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                                    }
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                                    viewModel.addMediaItem(
                                        com.android.everytalk.models.SelectedMediaItem.ImageFromUri(
                                            uri = uri,
                                            id = UUID.randomUUID().toString(),
                                            filePath = null
                                        )
                                    )
                                    Toast.makeText(context, "编辑完成，已加入选择", Toast.LENGTH_SHORT).show()
                                    isBrushing = false
                                    isImagePreviewVisible = false
                                } catch (e: Exception) {
                                    Toast.makeText(context, "保存编辑结果失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

// 简易画笔编辑器覆盖层：支持在图片上涂抹，完成后返回合成后的Bitmap
@Composable
private fun BrushEditorOverlay(
    baseBitmap: Bitmap,
    onCancel: () -> Unit,
    onDone: (Bitmap) -> Unit
) {
    // 数据与状态
    val imageBitmap = remember(baseBitmap) { baseBitmap.copy(Bitmap.Config.ARGB_8888, true).asImageBitmap() }
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val undoneStrokes = remember { mutableStateListOf<List<Offset>>() }
    val currentStroke = remember { mutableStateListOf<Offset>() } // 使用可观察列表，支持实时绘制
    val strokeWidthPx = 24f // 固定画笔粗细（不提供调节控件）
    val strokeColor = Color(0xFF82A8FF) // 接近示例中的淡蓝色

    Surface(
        color = Color.Black.copy(alpha = 0.95f),
        contentColor = Color.White,
        modifier = Modifier.fillMaxSize()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 计算显示区域尺寸，保持等比
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(top = 40.dp, bottom = 64.dp)
            ) {
                val maxW = constraints.maxWidth.toFloat()
                val maxH = constraints.maxHeight.toFloat()
                val bmpW = imageBitmap.width.toFloat()
                val bmpH = imageBitmap.height.toFloat()
                val scale = minOf(maxW / bmpW, maxH / bmpH).coerceAtMost(1f)
                val drawW = bmpW * scale
                val drawH = bmpH * scale

                val leftPad = (maxW - drawW) / 2f
                val topPad = (maxH - drawH) / 2f

                Box(
                    modifier = Modifier
                        .width(with(LocalDensity.current) { drawW.toDp() })
                        .height(with(LocalDensity.current) { drawH.toDp() })
                        .align(Alignment.Center)
                ) {
                    // 底图：使用 Image 进行渲染，避免依赖 drawImage 扩展
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.FillBounds
                    )

                    // 叠加一层 Canvas 仅绘制笔画，并处理绘制手势
                    Canvas(
                        modifier = Modifier
                            .matchParentSize()
                            .pointerInput(Unit) {
                                // 兼容性更好的连续绘制：基于 awaitPointerEvent 循环
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: continue
                                        val p = change.position
                                        if (change.pressed) {
                                            if (p.x in 0f..drawW && p.y in 0f..drawH) {
                                                currentStroke.add(p) // 实时追加点，触发重绘
                                            }
                                        } else {
                                            if (currentStroke.size > 1) {
                                                strokes.add(currentStroke.toList())
                                            }
                                            currentStroke.clear() // 清空以便下一笔实时绘制
                                        }
                                    }
                                }
                            }
                    ) {
                        // 仅绘制笔画到覆盖层
                        // 已完成笔画：使用贝塞尔平滑路径，避免“断点”观感
                        strokes.forEach { pts ->
                            if (pts.size > 1) {
                                val path = Path().apply {
                                    moveTo(pts.first().x, pts.first().y)
                                    for (i in 1 until pts.size) {
                                        val prev = pts[i - 1]
                                        val cur = pts[i]
                                        // 使用二次贝塞尔，控制点为上一个点，终点为中点，获得平滑曲线
                                        val mid = Offset(
                                            (prev.x + cur.x) / 2f,
                                            (prev.y + cur.y) / 2f
                                        )
                                        quadraticBezierTo(prev.x, prev.y, mid.x, mid.y)
                                    }
                                    // 收尾：最后一段到最终点
                                    val last = pts.last()
                                    lineTo(last.x, last.y)
                                }
                                drawPath(
                                    path = path,
                                    color = strokeColor,
                                    style = Stroke(
                                        width = strokeWidthPx,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round
                                    )
                                )
                            }
                        }
                        // 正在进行中的笔画：同样用路径实时绘制
                        if (currentStroke.size > 1) {
                            val pts = currentStroke
                            val path = Path().apply {
                                moveTo(pts.first().x, pts.first().y)
                                for (i in 1 until pts.size) {
                                    val prev = pts[i - 1]
                                    val cur = pts[i]
                                    val mid = Offset(
                                        (prev.x + cur.x) / 2f,
                                        (prev.y + cur.y) / 2f
                                    )
                                    quadraticBezierTo(prev.x, prev.y, mid.x, mid.y)
                                }
                                val last = pts.last()
                                lineTo(last.x, last.y)
                            }
                            drawPath(
                                path = path,
                                color = strokeColor,
                                style = Stroke(
                                    width = strokeWidthPx,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }
                }

                // 顶部标题、左侧竖向笔刷调节、底部工具条（取消/撤销/重做/下一步）
                Box(modifier = Modifier.fillMaxSize()) {

                    // 顶部标题
                    Text(
                        text = "选择要编辑的区域",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 20.dp, top = 16.dp)
                    )

                    // 左侧不显示笔刷调节控件（按要求固定画笔大小与颜色）

                    // 底部工具条：取消 | 撤销/重做 | 下一步（等分布局，避免单个按钮占满）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 20.dp, vertical = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 左：取消（占1/3）
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            Surface(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                contentColor = Color.White,
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier
                                    .height(44.dp)
                                    .widthIn(min = 84.dp)
                                    .clickable { onCancel() }
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("取消", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }

                        // 中：撤销 / 重做（占1/3）
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(28.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        if (strokes.isNotEmpty()) {
                                            val last = strokes.removeAt(strokes.lastIndex)
                                            undoneStrokes.add(last)
                                        }
                                    },
                                    enabled = strokes.isNotEmpty()
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Undo,
                                        contentDescription = "撤销",
                                        tint = if (strokes.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.4f)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (undoneStrokes.isNotEmpty()) {
                                            val last = undoneStrokes.removeAt(undoneStrokes.lastIndex)
                                            strokes.add(last)
                                        }
                                    },
                                    enabled = undoneStrokes.isNotEmpty()
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Redo,
                                        contentDescription = "重做",
                                        tint = if (undoneStrokes.isNotEmpty()) Color.White else Color.White.copy(alpha = 0.4f)
                                    )
                                }
                            }
                        }

                        // 右：下一步（占1/3）
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier
                                    .height(44.dp)
                                    .widthIn(min = 112.dp)
                                    .clickable {
                                        // 合成到原图尺寸
                                        val out = Bitmap.createBitmap(baseBitmap.width, baseBitmap.height, Bitmap.Config.ARGB_8888)
                                        val c = Canvas(out)
                                        c.drawBitmap(baseBitmap, 0f, 0f, null)
                                        val paint = android.graphics.Paint().apply {
                                            color = android.graphics.Color.RED
                                            isAntiAlias = true
                                            strokeWidth = strokeWidthPx / scale
                                            style = android.graphics.Paint.Style.STROKE
                                            strokeCap = android.graphics.Paint.Cap.ROUND
                                            strokeJoin = android.graphics.Paint.Join.ROUND
                                        }
                                        val sx = bmpW / drawW
                                        val sy = bmpH / drawH
                                        val allStrokes = (strokes + if (currentStroke.isNotEmpty()) listOf(currentStroke) else emptyList())
                                        allStrokes.forEach { pts ->
                                            if (pts.size > 1) {
                                                for (i in 0 until pts.size - 1) {
                                                    val x1 = pts[i].x * sx
                                                    val y1 = pts[i].y * sy
                                                    val x2 = pts[i + 1].x * sx
                                                    val y2 = pts[i + 1].y * sy
                                                    c.drawLine(x1, y1, x2, y2, paint)
                                                }
                                            }
                                        }
                                        onDone(out)
                                    }
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("下一步", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 底部操作按钮，使用与“历史项点击”一致的Ripple特效
 * - 固定图标容器尺寸，防止布局抬高
 * - 单击时显示有界Ripple（白色半透明）
 */
@Composable
private fun BottomActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    // 单一圆形容器，图标+文字都放在同一个圆里，点击仅在圆形范围产生涟漪
    Box(
        modifier = Modifier
            .size(68.dp) // 同一大圆，容纳图标+文字
            .clip(androidx.compose.foundation.shape.CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
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
    scrollStateManager: ChatScrollStateManager,
    viewModel: AppViewModel
) {
    val shape = androidx.compose.ui.graphics.RectangleShape
    val aiReplyMessageDescription = stringResource(id = R.string.ai_reply_message)

    Row(
        modifier = modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        var itemGlobalPosition by remember { mutableStateOf(Offset.Zero) }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    itemGlobalPosition = coords.localToRoot(Offset.Zero)
                }
                .pointerInput(message.id) {
                    detectTapGestures(
                        onLongPress = { localOffset ->
                            // 将本地偏移转换为全局，统一与附件一致的定位体验
                            onLongPress(message, itemGlobalPosition + localOffset)
                        }
                    )
                }
                .semantics {
                    contentDescription = aiReplyMessageDescription
                },
            shape = shape,
            color = Color.Transparent,
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
                        EnhancedMarkdownText(
                            message = message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            isStreaming = isStreaming,
                            messageOutputType = message.outputType,
                            viewModel = viewModel,  // 🎯 传递viewModel以获取实时流式文本
                            onImageClick = { url -> onOpenPreview(url) } // 🎯 让Markdown内图片单击可直接预览放大
                        )
                    }
                    android.util.Log.d("AiMessageItem", "🖼️ [RENDER] messageId=${message.id.take(8)}, imageUrls=${message.imageUrls?.size}, text='${text.take(20)}...'")
                    
                    if (message.imageUrls != null && message.imageUrls.isNotEmpty()) {
                        android.util.Log.d("AiMessageItem", "🖼️ [RENDER IMAGE] Showing ${message.imageUrls.size} images")
                        // Add a little space between text and image
                        if (text.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        AttachmentsContent(
                            attachments = message.imageUrls.map { urlStr ->
                                val safeUri = try {
                                    when {
                                        urlStr.startsWith("data:image", ignoreCase = true) -> Uri.parse(urlStr)
                                        urlStr.startsWith("file://", ignoreCase = true) -> Uri.parse(urlStr)
                                        urlStr.startsWith("/", ignoreCase = true) -> Uri.fromFile(File(urlStr))
                                        else -> Uri.parse(urlStr)
                                    }
                                } catch (_: Exception) {
                                    // 回退：尽量解析为 file 路径
                                    if (urlStr.startsWith("/")) Uri.fromFile(File(urlStr)) else Uri.parse(urlStr)
                                }
                                SelectedMediaItem.ImageFromUri(safeUri, UUID.randomUUID().toString())
                            },
                            onAttachmentClick = { _ ->
                                // 单击直接走"长按-查看图片"的同一路径（使用消息里的 URL）
                                val firstUrl = message.imageUrls.firstOrNull()
                                if (!firstUrl.isNullOrBlank()) {
                                    onOpenPreview(firstUrl)
                                }
                            },
                            maxWidth = maxWidth,
                            message = message,
                            onEditRequest = {},
                            onRegenerateRequest = {},
                            onLongPress = { msg, offset -> onLongPress(msg, offset) },
                            onImageLoaded = onImageLoaded,
                            bubbleColor = MaterialTheme.chatColors.aiBubble,
                            scrollStateManager = scrollStateManager,
                            isAiGenerated = true,  // 标识为AI生成的图片
                            onImageClick = { url -> onOpenPreview(url) }  // 🎯 连接到完整预览功能
                        )
                    }
                }
            }
        }
    }
}