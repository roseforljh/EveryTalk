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


import kotlinx.coroutines.CoroutineScope

@Composable
internal fun ImageGenerationImagePreview(
    isImagePreviewVisible: Boolean,
    imagePreviewModel: Any?,
    imagePreviewModels: List<Any>,
    currentImageIndex: Int,
    context: Context,
    viewModel: AppViewModel,
    scope: CoroutineScope,
    imageDownloadHeaders: Map<String, String>,
    refererHeader: String?,
    onImagePreviewModelChanged: (Any?) -> Unit,
    onImageIndexChanged: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
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
        if (isImagePreviewVisible && imagePreviewModels.isNotEmpty()) {
            val controlBackgroundColor = Color.Gray.copy(alpha = 0.42f)
            val controlBorderColor = Color.White.copy(alpha = 0.75f)
            val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            // 当前选中的图像生成配置（用于附加鉴权/来源头）

            // HorizontalPager 状态
            val pagerState = rememberPagerState(
                initialPage = currentImageIndex.coerceIn(0, imagePreviewModels.lastIndex.coerceAtLeast(0)),
                pageCount = { imagePreviewModels.size }
            )

            // 当 pager 页面变化时，同步更新 imagePreviewModel
            LaunchedEffect(pagerState.currentPage) {
                if (pagerState.currentPage in imagePreviewModels.indices) {
                    onImagePreviewModelChanged(imagePreviewModels[pagerState.currentPage])
                    onImageIndexChanged(pagerState.currentPage)
                }
            }

            // 手势缩放/平移状态（每页独立）
            var scale by remember { mutableFloatStateOf(1f) }
            var offsetX by remember { mutableFloatStateOf(0f) }
            var offsetY by remember { mutableFloatStateOf(0f) }

            // 页面切换时重置缩放
            LaunchedEffect(pagerState.currentPage) {
                scale = 1f
                offsetX = 0f
                offsetY = 0f
            }

            // 简易画笔编辑器状态
            val brushLoadingScope = rememberCoroutineScope()
            var isBrushing by remember { mutableStateOf(false) }
            var isBrushLoading by remember { mutableStateOf(false) }
            var brushBaseBitmap by remember { mutableStateOf<Bitmap?>(null) }

            fun closeBrushEditor() {
                isBrushing = false
                val bitmap = brushBaseBitmap
                brushBaseBitmap = null
                if (bitmap != null && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }

            DisposableEffect(Unit) {
                onDispose {
                    brushBaseBitmap?.takeIf { !it.isRecycled }?.recycle()
                }
            }

            LaunchedEffect(imagePreviewModel) {
                if (isBrushing) closeBrushEditor()
            }

            fun resetTransform() {
                scale = 1f; offsetX = 0f; offsetY = 0f
            }

            // 解析当前 model 为 Bitmap（切到IO线程；尽量兜底各种来源）
            // 优先通过 Coil 获取（含缓存或网络），失败再回退手写网络
            suspend fun loadBitmapWithCache(
                model: Any,
                context: Context
            ): Bitmap? {
                return withContext(Dispatchers.IO) {
                    try {
                        val imageLoader = context.imageLoader
                        val request = ImageRequest.Builder(context)
                            .data(model)
                            .allowHardware(false)
                            .size(Size(MAX_PREVIEW_BITMAP_DIMENSION, MAX_PREVIEW_BITMAP_DIMENSION))
                            .build()
                        
                        // execute 是挂起函数，会处理线程切换
                        val result = imageLoader.execute(request)
                        
                        if (result is SuccessResult) {
                            val image = result.image
                            val drawable = image.asDrawable(context.resources)

                            val w = drawable.intrinsicWidth.coerceAtLeast(1)
                            val h = drawable.intrinsicHeight.coerceAtLeast(1)
                            val scale = min(
                                MAX_PREVIEW_BITMAP_DIMENSION.toFloat() / w.toFloat(),
                                MAX_PREVIEW_BITMAP_DIMENSION.toFloat() / h.toFloat()
                            ).coerceAtMost(1f)
                            val targetW = (w * scale).toInt().coerceAtLeast(1)
                            val targetH = (h * scale).toInt().coerceAtLeast(1)
                            val bmp = renderDrawableToOwnedBitmap(drawable, targetW, targetH)
                            android.util.Log.d("ImagePreview", "Bitmap obtained via Coil cache/network.")
                            return@withContext bmp
                        } else {
                            android.util.Log.w("ImagePreview", "Coil execute returned non-success result.")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("ImagePreview", "Coil load failed, will fallback. Error: ${e.message}")
                    }
                    null
                }
            }


            // 统一的 HTTP 下载（附加鉴权/来源头，含重定向）
            suspend fun httpGetBitmap(urlStr: String): Bitmap? = withContext(Dispatchers.IO) {
                try {
                    downloadImageBytes(urlStr)?.first?.let(::decodePreviewByteArray)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("ImagePreview", "HTTP load failed: ${e.message}")
                    null
                }
            }

            // 解析当前 model 为 Bitmap（切到IO线程；尽量兜底各种来源）
            suspend fun loadBitmapFromModel(model: Any): Bitmap? = withContext(Dispatchers.IO) {
                try {
                    android.util.Log.d(
                        "ImagePreview",
                        "loadBitmapFromModel type=${model::class.java.simpleName} chars=${model.toString().length}",
                    )
                    when (model) {
                        is Bitmap -> return@withContext model.copyScaledToPreviewLimit()
                        is Uri -> {
                            val scheme = model.scheme?.lowercase()
                            return@withContext when (scheme) {
                                "http", "https" -> {
                                    // 精简：与"长按-下载"一致，直接使用 OkHttp 获取
                                    httpGetBitmap(model.toString())
                                }
                                "content" -> {
                                    try {
                                        decodePreviewStream { context.contentResolver.openInputStream(model) }
                                    } catch (e: Exception) {
                                        android.util.Log.w("ImagePreview", "Content read failed: ${e.message}")
                                        null
                                    }
                                }
                                "file" -> decodePreviewFile(model.path)
                                else -> {
                                    val byFile = decodePreviewFile(model.path)
                                    if (byFile != null) byFile else try {
                                        decodePreviewStream { context.contentResolver.openInputStream(model) }
                                    } catch (_: Exception) { null }
                                }
                            }
                        }
                        is String -> {
                            val s = model
                            if (s.startsWith("data:", ignoreCase = true)) {
                                return@withContext decodeImageBase64DataUri(s)?.let(::decodePreviewByteArray)
                            }
                            
                            val uri = try { s.toUri() } catch (_: Exception) { null }
                            val scheme = uri?.scheme?.lowercase()
                            return@withContext when (scheme) {
                                "http", "https" -> {
                                    // 精简：与"长按-下载"一致，直接使用 OkHttp 获取
                                    httpGetBitmap(s)
                                }
                                "content" -> {
                                    try {
                                        decodePreviewStream { context.contentResolver.openInputStream(uri) }
                                    } catch (e: Exception) {
                                        android.util.Log.w("ImagePreview", "Content read failed: ${e.message}")
                                        null
                                    }
                                }
                                "file" -> decodePreviewFile(uri.path)
                                null -> decodePreviewFile(s)
                                else -> {
                                    val bmp = decodePreviewFile(s)
                                    if (bmp == null) {
                                        android.util.Log.w("ImagePreview", "Decode by file path failed for: $s (scheme=$scheme)")
                                    }
                                    bmp
                                }
                            }
                        }
                        else -> {
                            val s = model.toString()
                            val uri = try { s.toUri() } catch (_: Exception) { null }
                            val scheme = uri?.scheme?.lowercase()
                            return@withContext when (scheme) {
                                "http", "https" -> httpGetBitmap(s)
                                "content" -> {
                                    try {
                                        decodePreviewStream { context.contentResolver.openInputStream(uri) }
                                    } catch (e: Exception) { null }
                                }
                                "file" -> decodePreviewFile(uri.path)
                                else -> decodePreviewFile(s)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("ImagePreview", "loadBytesAndMime error: ${e.message}", e)
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
                                val bytes = decodeImageBase64DataUri(s)
                                if (bytes != null) {
                                    // 如果 MIME 是 octet-stream，尝试修正为 image/png
                                    val finalMime = if (mimePart.contains("image", true)) mimePart else "image/png"
                                    android.util.Log.d("ImagePreview", "Decoded data URI with MIME: $finalMime")
                                    return@withContext bytes to finalMime
                                }
                                return@withContext null
                            }
                            val uri = runCatching { s.toUri() }.getOrNull()
                            val scheme = uri?.scheme?.lowercase()
                            return@withContext when (scheme) {
                                "http", "https" -> downloadImageBytes(s)
                                "content" -> {
                                    val mime = context.contentResolver.getType(uri)
                                        ?: "image/png"
                                    context.contentResolver.openInputStream(uri)?.use { input ->
                                        readAtMost(input, MAX_IMAGE_RAW_BYTES) to mime
                                    }
                                }
                                "file" -> {
                                    val path = uri.path ?: return@withContext null
                                    val file = File(path)
                                    if (!file.exists()) return@withContext null
                                    val mime = when (file.extension.lowercase()) {
                                        "png" -> "image/png"
                                        "jpg", "jpeg" -> "image/jpeg"
                                        "webp" -> "image/webp"
                                        else -> "application/octet-stream"
                                    }
                                    file.readImageBytesAtMost() to mime
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
                                    file.readImageBytesAtMost() to mime
                                }
                                else -> null
                            }
                        }
                        is Uri -> {
                            val scheme = model.scheme?.lowercase()
                            return@withContext when (scheme) {
                                "http", "https" -> downloadImageBytes(model.toString())
                                "content" -> {
                                    val mime = context.contentResolver.getType(model)
                                        ?: "image/png"
                                    context.contentResolver.openInputStream(model)?.use { input ->
                                        readAtMost(input, MAX_IMAGE_RAW_BYTES) to mime
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
                                    file.readImageBytesAtMost() to mime
                                }
                                else -> null
                            }
                        }
                        is Bitmap -> {
                            // 内存位图（如编辑结果）选择 PNG 无损导出
                            val baos = CappedByteArrayOutputStream(MAX_IMAGE_RAW_BYTES)
                            check(model.compress(Bitmap.CompressFormat.PNG, 100, baos)) {
                                "图片编码失败"
                            }
                            baos.toByteArray() to "image/png"
                        }
                        else -> null
                    }
                } catch (e: CancellationException) {
                    throw e
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
                        try {
                            val bytes = CappedByteArrayOutputStream(MAX_IMAGE_RAW_BYTES).use { output ->
                                check(fallbackBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                    "图片编码失败"
                                }
                                output.toByteArray()
                            }
                            android.util.Log.i("ImagePreview", "Fallback success: recovered image from view/cache.")
                            return@withContext bytes to "image/png"
                        } finally {
                            if (!fallbackBitmap.isRecycled) fallbackBitmap.recycle()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
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
                            android.util.Log.e(
                                "ImagePreview",
                                "saveToAlbum failed: modelType=${imagePreviewModel?.javaClass?.simpleName} chars=${imagePreviewModel.toString().length}",
                            )
                            viewModel.showSnackbar("加载失败")
                            return@launch
                        }
                        val (bytes, mime) = pair
                        val ext = when (mime.lowercase()) {
                            "image/png" -> "png"
                            "image/jpeg", "image/jpg" -> "jpg"
                            "image/webp" -> "webp"
                            else -> "img"
                        }
                        withContext(Dispatchers.IO) {
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
                            var insertedUri: Uri? = null
                            try {
                                val uri = checkNotNull(resolver.insert(collection, values)) { "无法创建相册文件" }
                                insertedUri = uri
                                checkNotNull(resolver.openOutputStream(uri)) { "无法写入相册文件" }.use { output ->
                                    output.write(bytes)
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    values.clear()
                                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                                    resolver.update(uri, values, null, null)
                                }
                                insertedUri = null
                            } catch (error: Throwable) {
                                insertedUri?.let { uri -> runCatching { resolver.delete(uri, null, null) } }
                                throw error
                            }
                        }
                        viewModel.showSnackbar("已保存")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        viewModel.showSnackbar("保存失败")
                    }
                }
            }

            // 将当前模型转为可编辑/可分享的本地缓存文件Uri（FileProvider）- 无损写原始字节
            suspend fun ensureCacheFileUri(): Uri? {
                val pair = loadBytesAndMime(imagePreviewModel!!)
                if (pair == null) {
                    viewModel.showSnackbar("加载失败")
                    return null
                }
                val (bytes, mime) = pair
                val ext = when (mime.lowercase()) {
                    "image/png" -> "png"
                    "image/jpeg", "image/jpg" -> "jpg"
                    "image/webp" -> "webp"
                    else -> "img"
                }
                return withContext(Dispatchers.IO) {
                    val file = createTemporaryImageFile(context, "preview_cache", "img", ext)
                    FileOutputStream(file).use { output -> output.write(bytes) }
                    FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                }
            }

            // 打开内置画笔编辑器
            fun openBrushEditor() {
                if (isBrushing || isBrushLoading) return
                val requestedModel = imagePreviewModel ?: return
                isBrushLoading = true
                brushLoadingScope.launch(Dispatchers.IO) {
                    var bitmap: Bitmap? = null
                    try {
                        bitmap = loadBitmapFromModel(requestedModel)
                        withContext(Dispatchers.Main.immediate) {
                            val loadedBitmap = bitmap
                            if (loadedBitmap == null) {
                                viewModel.showSnackbar("加载失败")
                                return@withContext
                            }
                            if (!isImagePreviewVisible || imagePreviewModel != requestedModel) {
                                return@withContext
                            }
                            brushBaseBitmap?.takeIf { !it.isRecycled }?.recycle()
                            brushBaseBitmap = loadedBitmap
                            bitmap = null
                            isBrushing = true
                        }
                    } finally {
                        bitmap?.takeIf { !it.isRecycled }?.recycle()
                        withContext(Dispatchers.Main.immediate) {
                            isBrushLoading = false
                        }
                    }
                }
            }

            // 系统分享（无损写原始字节）
            fun shareImage() {
                scope.launch {
                    try {
                        val pair = loadBytesAndMime(imagePreviewModel!!)
                        if (pair == null) {
                            viewModel.showSnackbar("加载失败")
                            return@launch
                        }
                        val (bytes, mime) = pair
                        val ext = when (mime.lowercase()) {
                            "image/png" -> "png"
                            "image/jpeg", "image/jpg" -> "jpg"
                            "image/webp" -> "webp"
                            else -> "img"
                        }
                        val uri = withContext(Dispatchers.IO) {
                            val file = createTemporaryImageFile(context, "share_images", "share", ext)
                            FileOutputStream(file).use { output -> output.write(bytes) }
                            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                        }
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = mime
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            // 提高兼容性：通过 ClipData 传递并显式授权
                            clipData = android.content.ClipData.newUri(context.contentResolver, "image", uri)
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, "分享图片"))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        viewModel.showSnackbar("分享失败")
                    }
                }
            }

            // 选择：把当前预览图片加入"已选择媒体"，供后续发送复用
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
                        viewModel.showSnackbar("已选择")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        viewModel.showSnackbar("选择失败")
                    }
                }
            }

            // 编辑：改为"现在的选择功能"：加入已选择媒体并关闭预览返回
            fun editCurrentImage() {
                scope.launch {
                    val uri = ensureCacheFileUri()
                    if (uri == null) {
                        viewModel.showSnackbar("加载失败")
                        return@launch
                    }
                    viewModel.addMediaItem(
                        com.android.everytalk.models.SelectedMediaItem.ImageFromUri(
                            uri = uri,
                            id = UUID.randomUUID().toString(),
                            filePath = null
                        )
                    )
                    viewModel.showSnackbar("已选择")
                    // 关闭预览对话框，返回图像模式页面
                    onDismiss()
                }
            }

            Dialog(
                onDismissRequest = { onDismiss() },
                properties = DialogProperties(
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                    usePlatformDefaultWidth = false
                )
            ) {
                Surface(
                    color = Color.Transparent,
                    contentColor = Color.White,
                    tonalElevation = 0.dp,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // 顶部工具栏：居中页码 + 右上关闭
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .zIndex(2f)
                        ) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (imagePreviewModels.size > 1) {
                                    Spacer(modifier = Modifier.size(40.dp))
                                    Text(
                                        text = "${pagerState.currentPage + 1} / ${imagePreviewModels.size}",
                                        color = Color.White.copy(alpha = 0.8f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f),
                                        textAlign = TextAlign.Center
                                    )
                                } else {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                                IconButton(
                                    onClick = { onDismiss() },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(controlBackgroundColor, CircleShape)
                                        .border(1.dp, controlBorderColor, CircleShape)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_gpt_close_lg),
                                        contentDescription = "关闭预览",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        // 左右滑动切换图片 + 双指缩放
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            userScrollEnabled = imagePreviewModels.size > 1 && scale == 1f,
                            beyondViewportPageCount = 1
                        ) { page ->
                            val currentUrl = imagePreviewModels.getOrNull(page)
                            val animatedScale = remember(page) { Animatable(1f) }
                            var pageOffsetX by remember(page) { mutableFloatStateOf(0f) }
                            var pageOffsetY by remember(page) { mutableFloatStateOf(0f) }
                            val coroutineScope = rememberCoroutineScope()
                            val toggleZoom: () -> Unit = {
                                coroutineScope.launch {
                                    if (animatedScale.value > 1f) {
                                        animatedScale.animateTo(1f, tween(250))
                                        pageOffsetX = 0f
                                        pageOffsetY = 0f
                                    } else {
                                        animatedScale.animateTo(2f, tween(250))
                                    }
                                    scale = animatedScale.value
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(page) {
                                        val w = size.width.toFloat()
                                        val h = size.height.toFloat()
                                        awaitEachGesture {
                                            awaitFirstDown(requireUnconsumed = false)
                                            do {
                                                val event = awaitPointerEvent()
                                                val pointerCount = event.changes.size
                                                if (pointerCount >= 2) {
                                                    val zoomChange = event.calculateZoom()
                                                    val panChange = event.calculatePan()
                                                    val curScale = animatedScale.value
                                                    if (zoomChange != 1f || curScale > 1f) {
                                                        val newScale = (curScale * zoomChange).coerceIn(1f, 5f)
                                                        coroutineScope.launch { animatedScale.snapTo(newScale) }
                                                        if (newScale > 1f) {
                                                            val mx = (newScale - 1f) * w / 2f
                                                            val my = (newScale - 1f) * h / 2f
                                                            pageOffsetX = (pageOffsetX + panChange.x).coerceIn(-mx, mx)
                                                            pageOffsetY = (pageOffsetY + panChange.y).coerceIn(-my, my)
                                                        } else {
                                                            pageOffsetX = 0f
                                                            pageOffsetY = 0f
                                                        }
                                                        scale = newScale
                                                        event.changes.forEach { it.consume() }
                                                    }
                                                } else if (pointerCount == 1 && animatedScale.value > 1f) {
                                                    val panChange = event.calculatePan()
                                                    val s = animatedScale.value
                                                    val mx = (s - 1f) * w / 2f
                                                    val my = (s - 1f) * h / 2f
                                                    pageOffsetX = (pageOffsetX + panChange.x).coerceIn(-mx, mx)
                                                    pageOffsetY = (pageOffsetY + panChange.y).coerceIn(-my, my)
                                                    event.changes.forEach { it.consume() }
                                                }
                                            } while (event.changes.any { it.pressed })
                                        }
                                    }
                                    .combinedClickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() },
                                        role = Role.Button,
                                        onClickLabel = "切换图片缩放",
                                        onClick = toggleZoom,
                                        onDoubleClick = toggleZoom,
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = currentUrl,
                                    contentDescription = "预览图片 ${page + 1}/${imagePreviewModels.size}",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 12.dp, vertical = 72.dp)
                                        .graphicsLayer {
                                            scaleX = animatedScale.value
                                            scaleY = animatedScale.value
                                            translationX = pageOffsetX
                                            translationY = pageOffsetY
                                        },
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }


                        // 底部操作栏（编辑 / 选择 / 保存 / 分享）：纯图标圆按钮
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .padding(bottom = bottomInset + 12.dp, start = 24.dp, end = 24.dp)
                                .zIndex(2f),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BottomActionButton(
                                icon = painterResource(R.drawable.ic_gpt_edit),
                                contentDescription = "编辑图片",
                                onClick = { editCurrentImage() }
                            )
                            BottomActionButton(
                                icon = painterResource(R.drawable.ic_gpt_select_image_area),
                                contentDescription = "选择图片",
                                onClick = { openBrushEditor() }
                            )
                            BottomActionButton(
                                icon = painterResource(R.drawable.ic_gpt_download),
                                contentDescription = "保存图片",
                                onClick = { saveToAlbum() }
                            )
                            BottomActionButton(
                                icon = painterResource(R.drawable.ic_gpt_share),
                                contentDescription = "分享图片",
                                onClick = { shareImage() }
                            )
                        }
                    }
                }
            }

            // 内置画笔编辑器覆盖层（使用全屏 Dialog 以保证位于预览之上）
            if (isBrushing && brushBaseBitmap != null) {
                Dialog(
                    onDismissRequest = { closeBrushEditor() },
                    properties = DialogProperties(
                        dismissOnBackPress = true,
                        dismissOnClickOutside = false,
                        usePlatformDefaultWidth = false
                    )
                ) {
                    BrushEditorOverlay(
                        baseBitmap = brushBaseBitmap!!,
                        onCancel = { closeBrushEditor() },
                        onDone = { edited ->
                            // 将编辑后的图片加入"已选择媒体"，并返回（关闭画笔和预览）
                            scope.launch {
                                try {
                                    val (uri, path) = withContext(Dispatchers.IO) {
                                        val file = createTemporaryImageFile(context, "preview_cache", "edited", "jpg")
                                        FileOutputStream(file).use { fos ->
                                            check(edited.compress(Bitmap.CompressFormat.JPEG, 95, fos)) { "图片压缩失败" }
                                        }
                                        FileProvider.getUriForFile(context, "${context.packageName}.provider", file) to file.absolutePath
                                    }
                                    viewModel.addMediaItem(
                                        com.android.everytalk.models.SelectedMediaItem.ImageFromUri(
                                            uri = uri,
                                            id = UUID.randomUUID().toString(),
                                            filePath = path
                                        )
                                    )
                                    viewModel.showSnackbar("已编辑")
                                    closeBrushEditor()
                                    onDismiss()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    viewModel.showSnackbar("保存失败")
                                } finally {
                                    if (!edited.isRecycled) edited.recycle()
                                }
                            }
                        }
                    )
                }
            }
        }
}
@Composable
private fun BottomActionButton(
    icon: androidx.compose.ui.graphics.painter.Painter,
    contentDescription: String,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    // 单一圆形容器，点击仅在圆形范围产生涟漪
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Color.Gray.copy(alpha = 0.42f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.75f), CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(22.dp)
        )
    }
}

