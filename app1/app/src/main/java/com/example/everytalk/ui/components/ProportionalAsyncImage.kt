package com.example.everytalk.ui.components

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.example.everytalk.config.ImageCompressionPreferences
import com.example.everytalk.util.AppLogger
import com.example.everytalk.util.ImageScaleCalculator
import com.example.everytalk.util.ImageScaleConfig
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
    
    // 获取用户配置
    val compressionPrefs = remember { ImageCompressionPreferences(context) }
    val config = remember(isAiGenerated, compressionPrefs.compressionMode) {
        if (isAiGenerated) {
            // AI生成的图片使用专门的显示配置
            compressionPrefs.getAiImageDisplayConfig()
        } else {
            // 用户上传的图片使用聊天模式配置
            compressionPrefs.getChatModeConfig()
        }
    }
    
    // 动态计算的显示修饰符
    var calculatedModifier by remember { mutableStateOf(modifier) }
    var hasCalculatedSize by remember { mutableStateOf(false) }

    // 读取全局尺寸缓存（首帧即占位，防止版式跳动）
    val cacheKey = remember(model) { model?.toString() }
    val cachedSize = remember(cacheKey) { ImageSizeCache.get(cacheKey) }
    if (preserveAspectRatio && cachedSize != null && !hasCalculatedSize) {
        val (cw, ch) = cachedSize
        if (cw > 0 && ch > 0) {
            val aspect = cw.toFloat() / ch.toFloat()
            val targetWidthDp = with(density) { cw.toDp() }
            calculatedModifier = modifier
                .width(minOf(targetWidthDp, maxWidth))
                .aspectRatio(aspect)
            hasCalculatedSize = true
        }
    }

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
                        config.copy(maxDimension = maxWidthPx)
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