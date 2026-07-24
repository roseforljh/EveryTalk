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

internal const val MAX_PREVIEW_BITMAP_DIMENSION = 2048
internal const val MAX_IMAGE_RAW_BYTES = 50L * 1024L * 1024L
internal const val IMAGE_DOWNLOAD_TIMEOUT_MS = 30_000
internal const val MAX_IMAGE_BASE64_DECODED_BYTES = 32L * 1024L * 1024L
internal const val MAX_IMAGE_BASE64_WHITESPACE_CHARS = 2L * 1024L * 1024L
internal const val MAX_IMAGE_BASE64_ENCODED_CHARS =
    ((MAX_IMAGE_BASE64_DECODED_BYTES + 2L) / 3L) * 4L + MAX_IMAGE_BASE64_WHITESPACE_CHARS
internal const val MAX_TEMP_IMAGE_FILES = 16
internal const val TEMP_IMAGE_MAX_AGE_MS = 24L * 60L * 60L * 1000L

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

internal fun calculateBitmapSampleSize(width: Int, height: Int): Int {
    var sampleSize = 1
    while (width / sampleSize > MAX_PREVIEW_BITMAP_DIMENSION || height / sampleSize > MAX_PREVIEW_BITMAP_DIMENSION) {
        sampleSize *= 2
    }
    return sampleSize
}

internal fun decodePreviewByteArray(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateBitmapSampleSize(bounds.outWidth, bounds.outHeight)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

internal fun decodePreviewFile(path: String?): Bitmap? {
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

internal fun decodePreviewStream(openStream: () -> java.io.InputStream?): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateBitmapSampleSize(bounds.outWidth, bounds.outHeight)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return openStream()?.use { BitmapFactory.decodeStream(it, null, options) }
}

internal fun Bitmap.copyScaledToPreviewLimit(): Bitmap? {
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

internal fun renderDrawableToOwnedBitmap(drawable: Drawable, width: Int, height: Int): Bitmap {
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

internal fun mimeFromImagePath(path: String): String {
    return when (path.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }
}

internal fun File.readImageBytesAtMost(): ByteArray = readAtMost(MAX_IMAGE_RAW_BYTES)

internal fun createTemporaryImageFile(
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

