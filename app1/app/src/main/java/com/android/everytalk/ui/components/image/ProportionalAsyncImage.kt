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
import android.net.Uri
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.android.everytalk.util.AppLogger
import com.android.everytalk.util.image.ImageScaleCalculator
import com.android.everytalk.util.image.ImageScaleConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * 轻量级全局尺寸缓存：避免滑出视口后重建导致的布局跳动
 * key 使用图片模型 toString()（对 URL/data:URI 均有效）
 */
private object ImageSizeCache {
    private val map = ConcurrentHashMap<String, Pair<Int, Int>>() // width to height
    fun get(key: String?): Pair<Int, Int>? = key?.let { map[it] }
    fun put(key: String?, w: Int, h: Int) {
        if (key == null || w <= 0 || h <= 0) return
        map[key] = w to h
    }
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
    
    val cacheKey = remember(model) { model?.toString() }
    val initialSize: Pair<Int, Int>? = remember(cacheKey) {
        if (!preserveAspectRatio) {
            null
        } else {
            ImageSizeCache.get(cacheKey) ?: run {
                when (model) {
                    is Bitmap -> {
                        val w = model.width
                        val h = model.height
                        if (w > 0 && h > 0) {
                            ImageSizeCache.put(cacheKey, w, h)
                            w to h
                        } else {
                            null
                        }
                    }
                    is Uri -> {
                        val scheme = model.scheme?.lowercase()
                        if (scheme == "content" || scheme == "file" || scheme.isNullOrEmpty()) {
                            try {
                                val options = android.graphics.BitmapFactory.Options().apply {
                                    inJustDecodeBounds = true
                                }
                                if (scheme == "content") {
                                    context.contentResolver.openInputStream(model)?.use {
                                        android.graphics.BitmapFactory.decodeStream(it, null, options)
                                    }
                                } else {
                                    val path = model.path ?: model.toString()
                                    android.graphics.BitmapFactory.decodeFile(path, options)
                                }
                                val w = options.outWidth
                                val h = options.outHeight
                                if (w > 0 && h > 0) {
                                    ImageSizeCache.put(cacheKey, w, h)
                                    w to h
                                } else {
                                    null
                                }
                            } catch (_: Exception) {
                                null
                            }
                        } else {
                            null
                        }
                    }
                    is String -> {
                        val s = model
                        if (s.startsWith("data:", ignoreCase = true)) {
                            val commaIndex = s.indexOf(',')
                            if (commaIndex != -1) {
                                val dataPart = s.substring(commaIndex + 1)
                                val isBase64 = s.substring(0, commaIndex).contains(";base64", ignoreCase = true)
                                if (isBase64 && dataPart.length < 2_000_000) {
                                    try {
                                        val bytes = android.util.Base64.decode(dataPart, android.util.Base64.DEFAULT)
                                        val options = android.graphics.BitmapFactory.Options().apply {
                                            inJustDecodeBounds = true
                                        }
                                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                                        val w = options.outWidth
                                        val h = options.outHeight
                                        if (w > 0 && h > 0) {
                                            ImageSizeCache.put(cacheKey, w, h)
                                            w to h
                                        } else {
                                            null
                                        }
                                    } catch (_: Exception) {
                                        null
                                    }
                                } else {
                                    null
                                }
                            } else {
                                null
                            }
                        } else if (s.startsWith("file://", ignoreCase = true) || s.startsWith("/")) {
                            try {
                                val path = if (s.startsWith("file://", ignoreCase = true)) {
                                    android.net.Uri.parse(s).path ?: s.removePrefix("file://")
                                } else {
                                    s
                                }
                                val options = android.graphics.BitmapFactory.Options().apply {
                                    inJustDecodeBounds = true
                                }
                                android.graphics.BitmapFactory.decodeFile(path, options)
                                val w = options.outWidth
                                val h = options.outHeight
                                if (w > 0 && h > 0) {
                                    ImageSizeCache.put(cacheKey, w, h)
                                    w to h
                                } else {
                                    null
                                }
                            } catch (_: Exception) {
                                null
                            }
                        } else {
                            null
                        }
                    }
                    else -> null
                }
            }
        }
    }

    val initialModifier = if (preserveAspectRatio && initialSize != null) {
        val (cw, ch) = initialSize
        val aspect = cw.toFloat() / ch.toFloat()
        val targetWidthDp = with(density) { cw.toDp() }
        modifier
            .width(minOf(targetWidthDp, maxWidth))
            .aspectRatio(aspect)
    } else {
        modifier
    }

    var calculatedModifier by remember { mutableStateOf(initialModifier) }
    var hasCalculatedSize by remember { mutableStateOf(initialSize != null) }

    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(model)
            .crossfade(true) // 短暂淡入，避免突兀
            .build(),
        contentDescription = contentDescription,
        onSuccess = { state ->
            try {
                // 修复：正确获取drawable信息
                val painter = state.painter
                val originalWidth = painter.intrinsicSize.width.toInt()
                val originalHeight = painter.intrinsicSize.height.toInt()
                
                if (originalWidth > 0 && originalHeight > 0 && preserveAspectRatio && !hasCalculatedSize) {
                    // 转换为像素进行计算
                    val maxWidthPx = with(density) { maxWidth.toPx().toInt() }
                    
                    // 计算等比缩放尺寸
                    val (targetWidth, targetHeight) = ImageScaleCalculator.calculateProportionalScale(
                        originalWidth,
                        originalHeight,
                        ImageScaleConfig(
                            maxDimension = maxWidthPx,
                            preserveAspectRatio = true,
                            allowUpscale = false
                        )
                    )
                    
                    // 转换回Dp
                    val targetWidthDp = with(density) { targetWidth.toDp() }
                    val aspectRatio = originalWidth.toFloat() / originalHeight.toFloat()
                    
                    // 更新显示修饰符
                    calculatedModifier = modifier
                        .width(minOf(targetWidthDp, maxWidth))
                        .aspectRatio(aspectRatio)
                    
                    hasCalculatedSize = true
                    // 写入全局尺寸缓存（下次直接占位）
                    ImageSizeCache.put(cacheKey, originalWidth, originalHeight)
                    
                    logger.debug("Image scaled from ${originalWidth}x${originalHeight} to ${targetWidth}x${targetHeight} (AI: $isAiGenerated)")
                    onImageSizeCalculated?.invoke(targetWidth, targetHeight)
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
