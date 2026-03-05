package com.android.everytalk.ui.components.markdown

import android.util.TypedValue
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 可换行的块级 LaTeX 公式渲染器。
 *
 * 策略（与 Gemini 一致）：
 * 1. 先用 BreakableLatexDrawable 在运算符处自动换行
 * 2. 换行后如果公式仍超出屏幕宽度（如大矩阵），启用水平滑动
 *
 * 仅用于 ContentPart.Math（块级公式），行内公式仍由 MarkdownRenderer 处理。
 */
@Composable
fun BreakableLatexRenderer(
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
    // JLatexMath 的 setSize() 设置渲染像素大小（点 ≈ 像素）。
    // 必须将 sp 转换为 px，否则在高 DPI 设备上公式会非常小。
    val textSizeForJLatex = with(density) { textSizeSp.sp.toPx() }

    // 从 $$ 或 \[ 分隔符中提取纯 LaTeX 内容，并预处理防止数字被拆行
    val pureMath = remember(latex) {
        val raw = extractPureMathContent(latex)
        preventNumberBreaking(raw)
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val maxWidthPx = with(density) { maxWidth.toPx() }
        // 使用全部可用宽度（ImageView 自身有 4dp padding）
        val effectiveMaxWidth = maxWidthPx

        val cacheKey = remember(pureMath, isDark, textSizeSp, effectiveMaxWidth) {
            "breakable_${pureMath.hashCode()}_${isDark}_${textSizeSp.toInt()}_${effectiveMaxWidth.toInt()}"
        }

        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                // 参照 Gemini 的 qsx.smali：HorizontalScrollView 包裹 ImageView
                // 当公式换行后仍超宽时（如矩阵），可水平滑动
                val imageView = ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_START
                    adjustViewBounds = true
                    val paddingPx = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, 4f,
                        ctx.resources.displayMetrics
                    ).toInt()
                    setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
                }

                HorizontalScrollView(ctx).apply {
                    // 隐藏滚动条，视觉更简洁
                    isHorizontalScrollBarEnabled = false
                    // 允许内容超出容器宽度
                    isFillViewport = false
                    addView(imageView, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ))
                    // 将 imageView 存在 tag 中以便 update 时使用
                    tag = ScrollViewTag("", imageView)
                }
            },
            update = { scrollView ->
                val tagData = scrollView.tag as? ScrollViewTag ?: return@AndroidView
                val imageView = tagData.imageView

                if (tagData.cacheKey == cacheKey) return@AndroidView
                tagData.cacheKey = cacheKey

                try {
                    val drawable = BreakableLatexDrawable.create(
                        latex = pureMath,
                        textSize = textSizeForJLatex,
                        color = colorArgb,
                        maxWidthPx = effectiveMaxWidth,
                        align = BreakableLatexDrawable.ALIGN_LEFT,
                        interlineSpacing = 0.5f
                    )
                    imageView.setImageDrawable(drawable)

                    // 重置滚动位置
                    scrollView.scrollTo(0, 0)
                } catch (e: Exception) {
                    android.util.Log.e("BreakableLatexRenderer", "Failed to render: ${e.message}", e)
                    imageView.setImageDrawable(null)
                }
            }
        )
    }
}

/**
 * 从 LaTeX 块中提取纯数学内容（去掉 $$/$$ 或 \[/\] 分隔符）
 */
private fun extractPureMathContent(latex: String): String {
    val trimmed = latex.trim()
    return when {
        trimmed.startsWith("$$") && trimmed.endsWith("$$") ->
            trimmed.removePrefix("$$").removeSuffix("$$").trim()
        trimmed.startsWith("\\[") && trimmed.endsWith("\\]") ->
            trimmed.removePrefix("\\[").removeSuffix("\\]").trim()
        else -> trimmed
    }
}

/**
 * 预处理 LaTeX 字符串，防止多位数字被 BreakFormula 拆行。
 *
 * JLatexMath 的换行算法将每个数字字符视为独立原子，可能在中间断行
 * （如 576 → 57\n6）。将 2 位以上的数字包裹在 \mbox{} 中，
 * 使其成为不可拆分的水平盒子。数字在数学和文本模式下都是竖直体，
 * 视觉效果完全一致。
 */
private fun preventNumberBreaking(latex: String): String {
    // 匹配 2+ 位连续数字（可含小数点），但排除已在 \mbox{} 或 \text{} 内的
    return latex.replace(Regex("(?<!\\\\mbox\\{)(?<!\\\\text\\{)(\\d{2,}(?:\\.\\d+)?)")) {
        "\\mbox{${it.value}}"
    }
}

/**
 * HorizontalScrollView tag 数据持有类
 */
private class ScrollViewTag(
    var cacheKey: String,
    val imageView: ImageView
)
