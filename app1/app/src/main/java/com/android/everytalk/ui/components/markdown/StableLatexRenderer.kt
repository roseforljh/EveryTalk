package com.android.everytalk.ui.components.markdown

import android.util.TypedValue
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import ru.noties.jlatexmath.awt.Color as JColor

/**
 * 稳定块级公式渲染器：
 * - 不做自动换行
 * - 使用原生 JLatexMath 直接渲染完整公式
 * - 超宽时交给 HorizontalScrollView 横向滚动
 *
 * 适用于矩阵、偏导、求和、积分等结构型公式，
 * 避免 Markdown/自定义换行路径导致的不完整渲染。
 */
@Composable
fun StableLatexRenderer(
    latex: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    contentKey: String = ""
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val isDark = isSystemInDarkTheme()

    val finalColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> MaterialTheme.colorScheme.onSurface
    }
    val colorArgb = finalColor.toArgb()

    val baseSp = if (style.fontSize.value > 0f) style.fontSize.value else 16f
    val textSizeSp = baseSp * 1.05f
    val textSizePx = with(density) { textSizeSp.sp.toPx() }
    val pureMath = remember(latex) {
        val trimmed = latex.trim()
        when {
            trimmed.startsWith("$$") && trimmed.endsWith("$$") ->
                trimmed.removePrefix("$$").removeSuffix("$$").trim()
            trimmed.startsWith("\\[") && trimmed.endsWith("\\]") ->
                trimmed.removePrefix("\\[").removeSuffix("\\]").trim()
            else -> trimmed
        }
    }

    val cacheKey = remember(pureMath, isDark, textSizeSp, colorArgb, contentKey) {
        "stable_${contentKey}_${pureMath.hashCode()}_${isDark}_${textSizeSp.toInt()}_$colorArgb"
    }

    val drawable = remember(cacheKey) {
        stableDrawableCache.get(cacheKey) ?: try {
            val icon = TeXFormula(pureMath)
                .TeXIconBuilder()
                .setStyle(TeXConstants.STYLE_DISPLAY)
                .setSize(textSizePx)
                .setFGColor(JColor(colorArgb))
                .build()
            StableLatexDrawable(icon).also { stableDrawableCache.put(cacheKey, it) }
        } catch (e: Exception) {
            android.util.Log.e("StableLatexRenderer", "Failed to render: ${e.message}", e)
            null
        }
    }

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            val imageView = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_START
                adjustViewBounds = true
                val paddingPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    4f,
                    ctx.resources.displayMetrics
                ).toInt()
                setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            }

            NestedStableHorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = false
                addView(
                    imageView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                tag = imageView
            }
        },
        update = { scrollView ->
            val imageView = scrollView.tag as? ImageView ?: return@AndroidView
            imageView.setImageDrawable(drawable)
            scrollView.scrollTo(0, 0)
        }
    )
}

private val stableDrawableCache = android.util.LruCache<String, StableLatexDrawable>(50)

private class NestedStableHorizontalScrollView(
    context: android.content.Context
) : HorizontalScrollView(context) {
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (canScrollHorizontally(1) || canScrollHorizontally(-1)) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onTouchEvent(ev)
    }
}
