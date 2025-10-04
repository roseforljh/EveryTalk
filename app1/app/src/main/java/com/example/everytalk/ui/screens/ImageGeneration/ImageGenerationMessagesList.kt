package com.example.everytalk.ui.screens.ImageGeneration

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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import com.example.everytalk.R
import java.util.UUID
import android.graphics.Bitmap
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import android.content.Intent
import android.widget.Toast
import com.example.everytalk.data.DataClass.Message
import okhttp3.OkHttpClient
import okhttp3.Request
import android.graphics.BitmapFactory
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
import com.example.everytalk.util.messageprocessor.parseMarkdownParts
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

        // 全屏黑底图片预览（图1风格）+ 手势缩放 + 保存/分享
        if (isImagePreviewVisible && imagePreviewModel != null) {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            // 手势缩放/平移状态
            var scale by remember { mutableStateOf(1f) }
            var offsetX by remember { mutableStateOf(0f) }
            var offsetY by remember { mutableStateOf(0f) }

            fun resetTransform() {
                scale = 1f; offsetX = 0f; offsetY = 0f
            }

            // 解析当前 model 为 Bitmap（切到IO线程；尽量兜底各种来源）
            suspend fun loadBitmapFromModel(model: Any): Bitmap? = withContext(Dispatchers.IO) {
                try {
                    android.util.Log.d("ImagePreview", "loadBitmapFromModel type=${model::class.java.name} value=$model")
                    when (model) {
                        is Bitmap -> return@withContext model
                        is Uri -> {
                            // 根因修复：当 Uri 实际是 http/https 时，不能用 ContentResolver 打开
                            // 需要与字符串分支一致，走网络下载逻辑；其余 scheme 再分别处理
                            val scheme = model.scheme?.lowercase()
                            return@withContext when (scheme) {
                                "http", "https" -> {
                                    // OkHttp（含重定向 + UA） -> URL.openStream 兜底
                                    val httpBitmap = try {
                                        val client = OkHttpClient.Builder()
                                            .followRedirects(true)
                                            .followSslRedirects(true)
                                            .build()
                                        val req = Request.Builder()
                                            .url(model.toString())
                                            .header("User-Agent", "EveryTalk/1.0 (Android)")
                                            .build()
                                        client.newCall(req).execute().use { resp ->
                                            if (!resp.isSuccessful) {
                                                android.util.Log.w("ImagePreview", "HTTP code=${resp.code}")
                                                null
                                            } else {
                                                val bytes = resp.body?.bytes()
                                                if (bytes != null && bytes.isNotEmpty()) {
                                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                                } else null
                                            }
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w("ImagePreview", "HTTP load failed (uri): ${e.message}")
                                        null
                                    }
                                    if (httpBitmap != null) httpBitmap else try {
                                        java.net.URL(model.toString()).openStream().use { input ->
                                            BitmapFactory.decodeStream(input)
                                        }
                                    } catch (e2: Exception) {
                                        android.util.Log.w("ImagePreview", "URL.openStream failed (uri): ${e2.message}")
                                        null
                                    }
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
                                "file" -> {
                                    BitmapFactory.decodeFile(model.path)
                                }
                                else -> {
                                    // 其他未知 scheme：先尝试当作本地路径，再尝试 ContentResolver
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
                            // data:image/*
                            if (s.startsWith("data:image", ignoreCase = true)) {
                                val base64 = s.substringAfter(",", "")
                                return@withContext if (base64.isNotBlank()) {
                                    val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                } else null
                            }
                            // 其它统一走 parse + 分支
                            val uri = try { Uri.parse(s) } catch (_: Exception) { null }
                            val scheme = uri?.scheme?.lowercase()
                            return@withContext when (scheme) {
                                "http", "https" -> {
                                    // 改进：OkHttp（含重定向 + UA） -> URL.openStream 兜底
                                    val httpBitmap = try {
                                        val client = OkHttpClient.Builder()
                                            .followRedirects(true)
                                            .followSslRedirects(true)
                                            .build()
                                        val req = Request.Builder()
                                            .url(s)
                                            .header("User-Agent", "EveryTalk/1.0 (Android)")
                                            .build()
                                        client.newCall(req).execute().use { resp ->
                                            if (!resp.isSuccessful) {
                                                android.util.Log.w("ImagePreview", "HTTP code=${resp.code}")
                                                null
                                            } else {
                                                val bytes = resp.body?.bytes()
                                                if (bytes != null && bytes.isNotEmpty()) {
                                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                                } else null
                                            }
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w("ImagePreview", "HTTP load failed: ${e.message}")
                                        null
                                    }
                                    if (httpBitmap != null) return@withContext httpBitmap

                                    try {
                                        java.net.URL(s).openStream().use { input ->
                                            BitmapFactory.decodeStream(input)
                                        }
                                    } catch (e2: Exception) {
                                        android.util.Log.w("ImagePreview", "URL.openStream failed: ${e2.message}")
                                        null
                                    }
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
                                null -> {
                                    // 无 scheme：可能是绝对路径
                                    BitmapFactory.decodeFile(s)
                                }
                                else -> {
                                    // 未知scheme再尝试当作路径；仍为空时记录日志
                                    val bmp = BitmapFactory.decodeFile(s)
                                    if (bmp == null) {
                                        android.util.Log.w("ImagePreview", "Decode by file path failed for: $s (scheme=$scheme)")
                                    }
                                    bmp
                                }
                            }
                        }
                        else -> {
                            // 兜底：尝试用 toString 再解一次
                            val s = model.toString()
                            val uri = try { Uri.parse(s) } catch (_: Exception) { null }
                            val scheme = uri?.scheme?.lowercase()
                            return@withContext when (scheme) {
                                "http", "https" -> {
                                    val httpBitmap = try {
                                        val client = OkHttpClient.Builder()
                                            .followRedirects(true)
                                            .followSslRedirects(true)
                                            .build()
                                        val req = Request.Builder()
                                            .url(s)
                                            .header("User-Agent", "EveryTalk/1.0 (Android)")
                                            .build()
                                        client.newCall(req).execute().use { resp ->
                                            if (!resp.isSuccessful) null
                                            else {
                                                val bytes = resp.body?.bytes()
                                                if (bytes != null && bytes.isNotEmpty()) {
                                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                                } else null
                                            }
                                        }
                                    } catch (_: Exception) { null }
                                    if (httpBitmap != null) return@withContext httpBitmap

                                    try {
                                        java.net.URL(s).openStream().use { input ->
                                            BitmapFactory.decodeStream(input)
                                        }
                                    } catch (_: Exception) { null }
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
                    // 仅记录已成功加载，保持返回类型为 Bitmap?（also 返回接收者本身）
                    android.util.Log.d("ImagePreview", "Bitmap loaded")
                }
            }

            // 保存到相册
            fun saveToAlbum() {
                scope.launch {
                    try {
                        val bmp = loadBitmapFromModel(imagePreviewModel!!)
                        if (bmp == null) {
                            Toast.makeText(context, "无法加载图片", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        val resolver = context.contentResolver
                        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        else
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, "EveryTalk_${System.currentTimeMillis()}.jpg")
                            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
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
                            bmp.compress(Bitmap.CompressFormat.JPEG, 95, os)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            values.clear()
                            values.put(MediaStore.Images.Media.IS_PENDING, 0)
                            resolver.update(uri, values, null, null)
                        }
                        Toast.makeText(context, "已保存到相册", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // 将当前模型转为可编辑/可分享的本地缓存文件Uri（FileProvider）
            suspend fun ensureCacheFileUri(): Uri? {
                val bmp = loadBitmapFromModel(imagePreviewModel!!)
                if (bmp == null) {
                    Toast.makeText(context, "无法加载图片", Toast.LENGTH_SHORT).show()
                    return null
                }
                val cacheDir = File(context.cacheDir, "preview_cache").apply { mkdirs() }
                val file = File(cacheDir, "img_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { fos ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                }
                return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            }

            // 系统分享
            fun shareImage() {
                scope.launch {
                    try {
                        val bmp = loadBitmapFromModel(imagePreviewModel!!)
                        if (bmp == null) {
                            Toast.makeText(context, "无法加载图片", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        val cacheDir = File(context.cacheDir, "share_images").apply { mkdirs() }
                        val file = File(cacheDir, "share_${System.currentTimeMillis()}.jpg")
                        FileOutputStream(file).use { fos ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                        }
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
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
                            com.example.everytalk.models.SelectedMediaItem.ImageFromUri(
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

            // 编辑：调用系统编辑器（如相册编辑/标注）
            fun editCurrentImage() {
                scope.launch {
                    try {
                        val uri = ensureCacheFileUri() ?: return@launch
                        val intent = Intent(Intent.ACTION_EDIT).apply {
                            setDataAndType(uri, "image/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            // 一些机型需要通过 ClipData 传递并授予权限
                            clipData = android.content.ClipData.newUri(context.contentResolver, "image", uri)
                            putExtra(Intent.EXTRA_STREAM, uri)
                        }
                        context.startActivity(Intent.createChooser(intent, "编辑图片"))
                    } catch (e: Exception) {
                        Toast.makeText(context, "未找到可用的图片编辑应用", Toast.LENGTH_SHORT).show()
                    }
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
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        scale = (scale * zoom).coerceIn(1f, 6f)
                                        // 缩放后允许一定平移
                                        offsetX += pan.x
                                        offsetY += pan.y
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
                                    .fillMaxHeight(0.88f)
                                    .graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = offsetX
                                        translationY = offsetY
                                    },
                                contentScale = ContentScale.Fit
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
                                onClick = { selectCurrentImage() }
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
                        EnhancedMarkdownText(
                            message = message,
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
                                    is SelectedMediaItem.ImageFromBitmap -> att.bitmap?.let { onOpenPreview(it) }
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
                            scrollStateManager = scrollStateManager,
                            isAiGenerated = true  // 标识为AI生成的图片
                        )
                    }
                }
            }
        }
    }
}