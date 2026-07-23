package com.android.everytalk.ui.screens.ImageGeneration

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

private const val MAX_PREVIEW_BITMAP_DIMENSION = 2048
private const val MAX_IMAGE_RAW_BYTES = 50L * 1024L * 1024L
private const val IMAGE_DOWNLOAD_TIMEOUT_MS = 30_000
internal const val MAX_IMAGE_BASE64_DECODED_BYTES = 32L * 1024L * 1024L
private const val MAX_IMAGE_BASE64_WHITESPACE_CHARS = 2L * 1024L * 1024L
private const val MAX_IMAGE_BASE64_ENCODED_CHARS =
    ((MAX_IMAGE_BASE64_DECODED_BYTES + 2L) / 3L) * 4L + MAX_IMAGE_BASE64_WHITESPACE_CHARS
private const val MAX_TEMP_IMAGE_FILES = 16
private const val TEMP_IMAGE_MAX_AGE_MS = 24L * 60L * 60L * 1000L

internal fun estimateImageBase64DecodedBytes(
    encoded: CharSequence,
    startIndex: Int = 0,
): Long {
    if (startIndex !in 0..encoded.length) return Long.MAX_VALUE

    var meaningfulChars = 0L
    var trailingPadding = 0
    for (index in startIndex until encoded.length) {
        when (val char = encoded[index]) {
            ' ', '\t', '\r', '\n' -> Unit
            else -> {
                meaningfulChars++
                trailingPadding = if (char == '=') trailingPadding + 1 else 0
            }
        }
    }
    return ((meaningfulChars * 3L) / 4L - trailingPadding.coerceAtMost(2)).coerceAtLeast(0L)
}

internal fun isImageBase64WithinDecodedLimit(
    encoded: CharSequence,
    startIndex: Int = 0,
    maxDecodedBytes: Long = MAX_IMAGE_BASE64_DECODED_BYTES,
): Boolean {
    if (startIndex !in 0..encoded.length || maxDecodedBytes < 0L) return false
    if ((encoded.length - startIndex).toLong() > MAX_IMAGE_BASE64_ENCODED_CHARS) return false
    return estimateImageBase64DecodedBytes(encoded, startIndex) <= maxDecodedBytes
}

private class Base64PayloadInputStream(
    private val source: CharSequence,
    private val startIndex: Int,
) : InputStream() {
    private var index = startIndex

    override fun read(): Int {
        while (index < source.length) {
            val char = source[index++]
            if (char == ' ' || char == '\t' || char == '\r' || char == '\n') continue
            require(char.code <= 0x7F) { "Base64 输入必须为 ASCII" }
            return char.code
        }
        return -1
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        if (length == 0) return 0

        var written = 0
        while (written < length && index < source.length) {
            val char = source[index++]
            if (char == ' ' || char == '\t' || char == '\r' || char == '\n') continue
            require(char.code <= 0x7F) { "Base64 输入必须为 ASCII" }
            buffer[offset + written] = char.code.toByte()
            written++
        }
        return if (written == 0) -1 else written
    }
}

internal fun decodeImageBase64DataUri(dataUri: String): ByteArray? {
    val commaIndex = dataUri.indexOf(',')
    if (commaIndex < 0) return null
    val base64MarkerIndex = dataUri.indexOf(";base64", startIndex = 5, ignoreCase = true)
    if (base64MarkerIndex !in 5 until commaIndex) return null

    val payloadStart = commaIndex + 1
    if (payloadStart >= dataUri.length || !isImageBase64WithinDecodedLimit(dataUri, payloadStart)) return null
    val estimatedBytes = estimateImageBase64DecodedBytes(dataUri, payloadStart)
    if (estimatedBytes > Int.MAX_VALUE) return null
    val output = ByteArray(estimatedBytes.toInt())
    return try {
        Base64.getDecoder().wrap(Base64PayloadInputStream(dataUri, payloadStart)).use { decodedStream ->
            var offset = 0
            while (offset < output.size) {
                val read = decodedStream.read(output, offset, output.size - offset)
                if (read == -1) break
                offset += read
            }
            if (decodedStream.read() != -1) return null
            if (offset == output.size) output else output.copyOf(offset)
        }
    } catch (_: Exception) {
        return null
    }
}

private fun calculateBitmapSampleSize(width: Int, height: Int): Int {
    var sampleSize = 1
    while (width / sampleSize > MAX_PREVIEW_BITMAP_DIMENSION || height / sampleSize > MAX_PREVIEW_BITMAP_DIMENSION) {
        sampleSize *= 2
    }
    return sampleSize
}

private fun decodePreviewByteArray(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateBitmapSampleSize(bounds.outWidth, bounds.outHeight)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private fun decodePreviewFile(path: String?): Bitmap? {
    if (path.isNullOrBlank()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateBitmapSampleSize(bounds.outWidth, bounds.outHeight)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeFile(path, options)
}

private fun decodePreviewStream(openStream: () -> java.io.InputStream?): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateBitmapSampleSize(bounds.outWidth, bounds.outHeight)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return openStream()?.use { BitmapFactory.decodeStream(it, null, options) }
}

private fun Bitmap.copyScaledToPreviewLimit(): Bitmap? {
    if (isRecycled) return null
    if (width <= MAX_PREVIEW_BITMAP_DIMENSION && height <= MAX_PREVIEW_BITMAP_DIMENSION) {
        return copy(Bitmap.Config.ARGB_8888, false)
    }
    val scale = min(
        MAX_PREVIEW_BITMAP_DIMENSION.toFloat() / width.toFloat(),
        MAX_PREVIEW_BITMAP_DIMENSION.toFloat() / height.toFloat()
    )
    val targetW = (width * scale).toInt().coerceAtLeast(1)
    val targetH = (height * scale).toInt().coerceAtLeast(1)
    return this.scale(targetW, targetH)
}

private fun renderDrawableToOwnedBitmap(drawable: Drawable, width: Int, height: Int): Bitmap {
    val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val previousBounds = android.graphics.Rect(drawable.bounds)
    return try {
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        bitmap
    } catch (error: Throwable) {
        bitmap.recycle()
        throw error
    } finally {
        drawable.setBounds(previousBounds)
    }
}

internal fun imageContextEditUsesPreview(): Boolean = false

private fun mimeFromImagePath(path: String): String {
    return when (path.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }
}

private fun File.readImageBytesAtMost(): ByteArray = readAtMost(MAX_IMAGE_RAW_BYTES)

private fun createTemporaryImageFile(
    context: Context,
    directoryName: String,
    prefix: String,
    extension: String,
): File {
    val directory = File(context.cacheDir, directoryName).apply { mkdirs() }
    val now = System.currentTimeMillis()
    directory.listFiles()?.filter(File::isFile).orEmpty()
        .filter { now - it.lastModified() > TEMP_IMAGE_MAX_AGE_MS }
        .forEach(File::delete)
    val remaining = directory.listFiles()?.filter(File::isFile).orEmpty()
        .sortedBy(File::lastModified)
    val excessCount = (remaining.size - MAX_TEMP_IMAGE_FILES + 1).coerceAtLeast(0)
    remaining.take(excessCount).forEach(File::delete)
    return File.createTempFile("${prefix}_", ".${extension.ifBlank { "img" }}", directory)
}

@Composable
fun ImageGenerationLoadingView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterStart
    ) {
        ImageGenLoadingIndicator(text = "等待首个响应")
    }
}

@Composable
private fun ImageGenLoadingIndicator(
    modifier: Modifier = Modifier,
    text: String? = null,
) {
    val displayText = com.android.everytalk.ui.screens.MainScreen.chat.text.ui.resolveLoadingStageDisplayText(
        text
    )
    com.android.everytalk.ui.screens.MainScreen.chat.text.ui.LoadingStageIndicator(
        text = displayText,
        modifier = modifier,
    )
}

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
                    imagePreviewModel = imagePreviewModels[pagerState.currentPage]
                    currentImageIndex = pagerState.currentPage
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
                    dismissImagePreview()
                }
            }

            Dialog(
                onDismissRequest = { dismissImagePreview() },
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
                                    onClick = { dismissImagePreview() },
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
                                    dismissImagePreview()
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
}

// 简易画笔编辑器覆盖层：支持在图片上涂抹，完成后返回合成后的Bitmap
@Composable
private fun BrushEditorOverlay(
    baseBitmap: Bitmap,
    onCancel: () -> Unit,
    onDone: (Bitmap) -> Unit
) {
    // 数据与状态
    val coroutineScope = rememberCoroutineScope()
    val imageBitmap = remember(baseBitmap) { baseBitmap.asImageBitmap() }
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val undoneStrokes = remember { mutableStateListOf<List<Offset>>() }
    val currentStroke = remember { mutableStateListOf<Offset>() } // 使用可观察列表，支持实时绘制
    var isCompositing by remember { mutableStateOf(false) }
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
                    // 画底图
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
                            .pointerInput(drawW, drawH) {
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
                                                undoneStrokes.clear()
                                            }
                                            currentStroke.clear() // 清空以便下一笔实时绘制
                                        }
                                    }
                                }
                            }
                    ) {
                        // 仅绘制笔画到覆盖层
                        // 已完成笔画：使用贝塞尔平滑路径，避免"断点"观感
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
                                    quadraticTo(prev.x, prev.y, mid.x, mid.y)
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
                                    quadraticTo(prev.x, prev.y, mid.x, mid.y)
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
                                        painter = painterResource(R.drawable.ic_arrow_back),
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
                                        painter = painterResource(R.drawable.ic_arrow_end),
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
                                    .clickable(enabled = !isCompositing) {
                                        isCompositing = true
                                        val strokeSnapshot = buildList {
                                            addAll(strokes.map { it.toList() })
                                            if (currentStroke.isNotEmpty()) add(currentStroke.toList())
                                        }
                                        coroutineScope.launch {
                                            var pendingBitmap: Bitmap? = null
                                            try {
                                                pendingBitmap = withContext(Dispatchers.Default) {
                                                    val result = createBitmap(
                                                        baseBitmap.width,
                                                        baseBitmap.height,
                                                        Bitmap.Config.ARGB_8888,
                                                    )
                                                    val canvas = Canvas(result)
                                                    canvas.drawBitmap(baseBitmap, 0f, 0f, null)
                                                    val paint = android.graphics.Paint().apply {
                                                        color = strokeColor.toArgb()
                                                        isAntiAlias = true
                                                        strokeWidth = strokeWidthPx / scale
                                                        style = android.graphics.Paint.Style.STROKE
                                                        strokeCap = android.graphics.Paint.Cap.ROUND
                                                        strokeJoin = android.graphics.Paint.Join.ROUND
                                                    }
                                                    val sx = bmpW / drawW
                                                    val sy = bmpH / drawH
                                                    strokeSnapshot.forEach { points ->
                                                        for (index in 0 until points.lastIndex) {
                                                            canvas.drawLine(
                                                                points[index].x * sx,
                                                                points[index].y * sy,
                                                                points[index + 1].x * sx,
                                                                points[index + 1].y * sy,
                                                                paint,
                                                            )
                                                        }
                                                    }
                                                    result
                                                }
                                                onDone(requireNotNull(pendingBitmap))
                                                pendingBitmap = null
                                            } finally {
                                                pendingBitmap?.takeIf { !it.isRecycled }?.recycle()
                                                isCompositing = false
                                            }
                                        }
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
 * 底部操作按钮，使用与"历史项点击"一致的Ripple特效
 * - 固定图标容器尺寸，防止布局抬高
 * - 单击时显示有界Ripple（白色半透明）
 */
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

@Composable
@SuppressLint("StateFlowValueCalledInComposition")
private fun AiMessageItem(
    message: Message,
    text: String,
    maxWidth: Dp,
    onLongPress: (Message, Offset) -> Unit,
    onOpenPreview: (Any) -> Unit,
    isStreaming: Boolean,
    onImageLoaded: () -> Unit,
    scrollStateManager: ChatScrollStateManager,
    viewModel: AppViewModel,
    modifier: Modifier = Modifier
) {
    val shape = androidx.compose.ui.graphics.RectangleShape
    val aiReplyMessageDescription = stringResource(id = R.string.ai_reply_message)
    val streamingRenderStateSource = remember(message.id, viewModel) {
        viewModel.getStreamingRenderState(message.id)
    }
    val pauseAwareRenderState = remember(streamingRenderStateSource, viewModel) {
        streamingRenderStateSource.freezeWhileStreamingPaused(viewModel.isStreamingPaused)
    }
    val streamingRenderState by pauseAwareRenderState.collectAsState(
        initial = streamingRenderStateSource.value
    )
    val shouldPreferStreamingContent = isStreaming || streamingRenderState.isStreaming
    val effectiveText = if (shouldPreferStreamingContent) {
        streamingRenderState.content.ifBlank { text.ifBlank { message.text } }
    } else {
        val staticText = text.ifBlank { message.text }
        if (staticText.isBlank() && streamingRenderState.content.isNotBlank()) {
            streamingRenderState.content
        } else {
            staticText
        }
    }
    val renderMessage = if (effectiveText == message.text) {
        message
    } else {
        message.copy(text = effectiveText)
    }
    val useUpstreamRenderState = shouldPreferStreamingContent &&
        streamingRenderState.content == effectiveText &&
        streamingRenderState.blocks.isNotEmpty()
    val localRenderState = remember(
        message.id,
        effectiveText,
        shouldPreferStreamingContent,
        useUpstreamRenderState,
    ) {
        if (!useUpstreamRenderState && effectiveText.isNotBlank()) {
            buildStreamingRenderState(
                messageId = "${message.id}:image-generation",
                content = effectiveText,
                isStreaming = shouldPreferStreamingContent,
                isComplete = !shouldPreferStreamingContent,
            )
        } else {
            null
        }
    }
    val renderState = if (useUpstreamRenderState) {
        streamingRenderState
    } else {
        localRenderState
    }
    val currentMessage by rememberUpdatedState(message)
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    Row(
        modifier = modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        var itemGlobalPosition by remember(message.id) { mutableStateOf(Offset.Zero) }
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
                            currentOnLongPress(currentMessage, itemGlobalPosition + localOffset)
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
                        start = ChatMarkdownTextStyle.ASSISTANT_CONTENT_START_PADDING_DP.dp,
                        top = ChatMarkdownTextStyle.ASSISTANT_CONTENT_TOP_PADDING_DP.dp,
                        end = ChatMarkdownTextStyle.ASSISTANT_CONTENT_END_PADDING_DP.dp,
                        bottom = ChatMarkdownTextStyle.ASSISTANT_CONTENT_BOTTOM_PADDING_DP.dp
                    )
            ) {
                Column {
                    if (renderState != null && renderState.blocks.isNotEmpty()) {
                        UnifiedMarkdownRenderer(
                            preparedMessage = renderState.preparedMessage,
                            sender = renderMessage.sender,
                            isStreaming = shouldPreferStreamingContent,
                        )
                    }
                    if (message.imageUrls != null && message.imageUrls.isNotEmpty()) {
                        if (text.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        AttachmentsContent(
                            attachments = message.imageUrls.map { urlStr ->
                                val safeUri = try {
                                    when {
                                        urlStr.startsWith("data:image", ignoreCase = true) -> urlStr.toUri()
                                        urlStr.startsWith("file://", ignoreCase = true) -> urlStr.toUri()
                                        urlStr.startsWith("/", ignoreCase = true) -> Uri.fromFile(File(urlStr))
                                        else -> urlStr.toUri()
                                    }
                                } catch (_: Exception) {
                                    if (urlStr.startsWith("/")) Uri.fromFile(File(urlStr)) else urlStr.toUri()
                                }
                                SelectedMediaItem.ImageFromUri(safeUri, UUID.randomUUID().toString())
                            },
                            onAttachmentClick = { _ ->
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
                            isAiGenerated = true,
                            onImageClick = { url -> onOpenPreview(url) }
                        )
                    }
                }
            }
        }
    }
}
