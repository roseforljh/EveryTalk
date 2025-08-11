package com.example.everytalk.ui.components.math

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 高性能数学公式视图组件
 * 完全替代WebView，提供极高的渲染性能和流畅的用户体验
 */
@Composable
fun HighPerformanceMathView(
    latex: String,
    modifier: Modifier = Modifier,
    textColor: Color = Color.Black,
    textSize: TextUnit = 16.sp,
    isDisplay: Boolean = false,
    backgroundColor: Color = Color.Transparent,
    useCache: Boolean = true
) {
    val density = LocalDensity.current
    val cache = remember { MathCache.getInstance() }
    
    // 生成缓存键
    val cacheKey = remember(latex, textSize, textColor, isDisplay) {
        if (useCache) {
            cache.generateCacheKey(latex, textSize.value, textColor, isDisplay)
        } else null
    }
    
    // 尝试从缓存获取
    val cachedEntry = remember(cacheKey) {
        cacheKey?.let { cache.getCachedMath(it) }?.also {
            cache.recordRequest(true)
        }
    }
    
    // 计算组件尺寸
    val (componentWidth, componentHeight) = remember(cachedEntry, latex, textSize, isDisplay) {
        if (cachedEntry != null) {
            Pair(cachedEntry.width, cachedEntry.height)
        } else {
            // 预估尺寸
            val textSizePx = with(density) { textSize.toPx() }
            val estimatedWidth = latex.length * textSizePx * 0.6f
            val estimatedHeight = if (isDisplay) textSizePx * 1.5f else textSizePx * 1.2f
            Pair(estimatedWidth, estimatedHeight)
        }
    }
    
    Canvas(
        modifier = modifier
            .then(
                if (isDisplay) {
                    Modifier
                        .fillMaxWidth()
                        .height(with(density) { componentHeight.toDp().coerceAtLeast(30.dp) })
                } else {
                    Modifier
                        .width(with(density) { componentWidth.toDp().coerceAtLeast(20.dp) })
                        .height(with(density) { componentHeight.toDp().coerceAtLeast(20.dp) })
                }
            )
    ) {
        // 绘制背景
        if (backgroundColor != Color.Transparent) {
            drawRect(backgroundColor)
        }
        
        if (cachedEntry != null) {
            // 使用缓存的位图
            drawCachedMath(cachedEntry, isDisplay)
        } else {
            // 实时渲染并缓存
            drawAndCacheMath(
                latex = latex,
                textSize = textSize,
                textColor = textColor,
                isDisplay = isDisplay,
                cacheKey = cacheKey,
                cache = cache,
                density = density
            )
        }
    }
}

/**
 * 绘制缓存的数学公式
 */
private fun DrawScope.drawCachedMath(
    cachedEntry: MathCache.CacheEntry,
    isDisplay: Boolean
) {
    val bitmap = cachedEntry.bitmap.asImageBitmap()
    
    val destRect = if (isDisplay) {
        // 居中显示
        val offsetX = (size.width - cachedEntry.width) / 2f
        androidx.compose.ui.geometry.Rect(
            offset = androidx.compose.ui.geometry.Offset(offsetX, 0f),
            size = androidx.compose.ui.geometry.Size(cachedEntry.width, cachedEntry.height)
        )
    } else {
        androidx.compose.ui.geometry.Rect(
            offset = androidx.compose.ui.geometry.Offset.Zero,
            size = androidx.compose.ui.geometry.Size(cachedEntry.width, cachedEntry.height)
        )
    }
    
    drawImage(
        image = bitmap,
        dstOffset = androidx.compose.ui.unit.IntOffset(
            destRect.left.toInt(),
            destRect.top.toInt()
        ),
        dstSize = androidx.compose.ui.unit.IntSize(
            destRect.width.toInt(),
            destRect.height.toInt()
        )
    )
}

/**
 * 实时渲染数学公式并缓存
 */
private fun DrawScope.drawAndCacheMath(
    latex: String,
    textSize: TextUnit,
    textColor: Color,
    isDisplay: Boolean,
    cacheKey: String?,
    cache: MathCache,
    density: androidx.compose.ui.unit.Density
) {
    drawIntoCanvas { canvas ->
        val nativeCanvas = canvas.nativeCanvas
        val textSizePx = with(density) { textSize.toPx() }
        val mathRenderer = MathRenderer()
        
        // 计算起始位置
        val startX = if (isDisplay) {
            0f // 让渲染器自己处理居中
        } else {
            0f
        }
        val startY = size.height / 2f + textSizePx / 3f
        
        try {
            val result = mathRenderer.renderMath(
                canvas = nativeCanvas,
                latex = latex,
                x = startX,
                y = startY,
                textSize = textSizePx,
                color = textColor,
                isDisplay = isDisplay
            )
            
            // 异步缓存结果
            cacheKey?.let {
                cache.recordRequest(false)
                // 在后台线程中缓存，避免阻塞UI
                GlobalScope.launch(Dispatchers.Default) {
                    cache.cacheMath(it, latex, textSizePx, textColor, isDisplay)
                }
            }
            
        } catch (e: Exception) {
            // 渲染失败时显示错误信息
            val paint = android.graphics.Paint().apply {
                this.textSize = textSizePx * 0.8f
                this.color = Color.Red.toArgb()
                isAntiAlias = true
            }
            nativeCanvas.drawText("Math Error", startX, startY, paint)
        }
    }
}

/**
 * 轻量级数学公式组件 - 用于简单表达式
 */
@Composable
fun LightweightMathView(
    latex: String,
    modifier: Modifier = Modifier,
    textColor: Color = Color.Black,
    textSize: TextUnit = 16.sp,
    isDisplay: Boolean = false
) {
    // 判断是否为简单表达式
    val isSimple = remember(latex) {
        !latex.contains("\\frac") && 
        !latex.contains("\\sqrt") && 
        !latex.contains("\\sum") && 
        !latex.contains("\\int") &&
        !latex.contains("^{") &&
        !latex.contains("_{") &&
        latex.length < 50
    }
    
    if (isSimple) {
        SimpleMathText(
            text = latex,
            modifier = modifier,
            color = textColor,
            fontSize = textSize
        )
    } else {
        HighPerformanceMathView(
            latex = latex,
            modifier = modifier,
            textColor = textColor,
            textSize = textSize,
            isDisplay = isDisplay
        )
    }
}

/**
 * 数学公式预览组件 - 用于编辑时的实时预览
 */
@Composable
fun MathPreview(
    latex: String,
    modifier: Modifier = Modifier,
    textColor: Color = Color.Black,
    textSize: TextUnit = 14.sp,
    showError: Boolean = true
) {
    if (latex.isBlank()) {
        androidx.compose.material3.Text(
            text = "数学公式预览",
            modifier = modifier,
            color = textColor.copy(alpha = 0.6f),
            fontSize = textSize
        )
    } else {
        LightweightMathView(
            latex = latex,
            modifier = modifier,
            textColor = textColor,
            textSize = textSize,
            isDisplay = false
        )
    }
}