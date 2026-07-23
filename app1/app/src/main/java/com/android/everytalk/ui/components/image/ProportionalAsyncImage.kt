package com.android.everytalk.ui.components

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import android.graphics.Bitmap
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.android.everytalk.util.AppLogger
import com.android.everytalk.util.image.ImageScaleCalculator
import com.android.everytalk.util.image.ImageScaleConfig
import java.util.LinkedHashMap

private const val IMAGE_SIZE_CACHE_MAX_ENTRIES = 64
private const val MAX_IMAGE_SIZE_CACHE_KEY_CHARS = 4_096

internal fun proportionalImageCacheKey(model: Any?): String? {
    val value = model?.toString() ?: return null
    return if (value.startsWith("data:", ignoreCase = true) || value.length > MAX_IMAGE_SIZE_CACHE_KEY_CHARS) {
        // 组合阶段避免扫描或持有大字符串；同一模型实例仍可稳定命中缓存。
        "opaque:${value.length}:${System.identityHashCode(value)}"
    } else {
        value
    }
}

internal class ImageSizeLruCache(
    private val maxEntries: Int = IMAGE_SIZE_CACHE_MAX_ENTRIES,
) {
    init {
        require(maxEntries > 0) { "缓存容量必须大于 0" }
    }

    private val entries = object : LinkedHashMap<String, Pair<Int, Int>>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Int, Int>>?): Boolean {
            return size > maxEntries
        }
    }

    @Synchronized
    fun get(key: String?): Pair<Int, Int>? = key?.let(entries::get)

    @Synchronized
    fun put(key: String?, width: Int, height: Int) {
        if (key == null || width <= 0 || height <= 0) return
        entries[key] = width to height
    }

    @Synchronized
    internal fun size(): Int = entries.size
}

/**
 * 轻量级全局尺寸缓存：避免滑出视口后重建导致的布局跳动。
 * data URI 只保留长度与哈希，避免缓存永久持有完整图片正文。
 */
private object ImageSizeCache {
    private val cache = ImageSizeLruCache()
    fun get(key: String?): Pair<Int, Int>? = cache.get(key)
    fun put(key: String?, width: Int, height: Int) = cache.put(key, width, height)
}

/**
 * 等比例异步图片组件
 * 支持AI生成图片的比例保持和智能缩放
 */
@Composable
fun ProportionalAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    maxWidth: Dp,
    isAiGenerated: Boolean = false,
    preserveAspectRatio: Boolean = true,
    onSuccess: ((AsyncImagePainter.State.Success) -> Unit)? = null,
    onError: ((AsyncImagePainter.State.Error) -> Unit)? = null,
    onImageSizeCalculated: ((width: Int, height: Int) -> Unit)? = null,
    contentScale: ContentScale = ContentScale.Fit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val logger = remember { AppLogger.forComponent("ProportionalAsyncImage") }

    val cacheKey = remember(model) { proportionalImageCacheKey(model) }
    val initialSize: Pair<Int, Int>? = remember(model, cacheKey, preserveAspectRatio) {
        if (!preserveAspectRatio) {
            null
        } else {
            ImageSizeCache.get(cacheKey) ?: (model as? Bitmap)?.let { bitmap ->
                if (!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
                    bitmap.width to bitmap.height
                } else {
                    null
                }
            }
        }
    }

    var imageSize by remember(model, cacheKey, preserveAspectRatio) { mutableStateOf(initialSize) }
    val calculatedModifier = if (preserveAspectRatio && imageSize != null) {
        val (cw, ch) = requireNotNull(imageSize)
        val aspect = cw.toFloat() / ch.toFloat()
        val targetWidthDp = with(density) { cw.toDp() }
        modifier
            .width(minOf(targetWidthDp, maxWidth))
            .aspectRatio(aspect)
    } else {
        modifier
    }

    val imageRequest = remember(context, model) {
        ImageRequest.Builder(context)
            .data(model)
            .crossfade(true)
            .build()
    }

    AsyncImage(
        model = imageRequest,
        contentDescription = contentDescription,
        onSuccess = { state ->
            try {
                val painter = state.painter
                val originalWidth = painter.intrinsicSize.width.toInt()
                val originalHeight = painter.intrinsicSize.height.toInt()

                if (originalWidth > 0 && originalHeight > 0 && preserveAspectRatio) {
                    val wasUnknown = imageSize == null
                    imageSize = originalWidth to originalHeight
                    ImageSizeCache.put(cacheKey, originalWidth, originalHeight)

                    val maxWidthPx = with(density) { maxWidth.toPx().toInt() }
                    val (targetWidth, targetHeight) = ImageScaleCalculator.calculateProportionalScale(
                        originalWidth,
                        originalHeight,
                        ImageScaleConfig(
                            maxDimension = maxWidthPx,
                            preserveAspectRatio = true,
                            allowUpscale = false
                        )
                    )

                    if (wasUnknown) {
                        logger.debug("Image scaled from ${originalWidth}x${originalHeight} to ${targetWidth}x${targetHeight} (AI: $isAiGenerated)")
                        onImageSizeCalculated?.invoke(targetWidth, targetHeight)
                    }
                }

                onSuccess?.invoke(state)
            } catch (e: Exception) {
                logger.error("Error calculating proportional image size", e)
                onSuccess?.invoke(state)
            }
        },
        onError = { state ->
            logger.error("Failed to load image: ${state.result.throwable.message}")
            onError?.invoke(state)
        },
        modifier = if (preserveAspectRatio) calculatedModifier else modifier.widthIn(max = maxWidth),
        contentScale = contentScale
    )
}

/**
 * 简化版本的等比例图片组件，保持向后兼容
 */
@Composable
fun ProportionalAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    maxWidth: Dp,
    onSuccess: ((AsyncImagePainter.State.Success) -> Unit)? = null
) {
    ProportionalAsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier,
        maxWidth = maxWidth,
        isAiGenerated = false,
        onSuccess = onSuccess
    )
}
