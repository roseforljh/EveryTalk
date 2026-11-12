package com.android.everytalk.ui.components
import com.android.everytalk.statecontroller.*

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

fun Modifier.floatingEdgeGradient(
    backgroundColor: Color,
    fromTop: Boolean,
): Modifier {
    val colorStops = if (fromTop) {
        arrayOf(
            0f to backgroundColor.copy(alpha = 0.94f),
            0.6f to backgroundColor.copy(alpha = 0.72f),
            1f to Color.Transparent,
        )
    } else {
        arrayOf(
            0f to Color.Transparent,
            0.4f to backgroundColor.copy(alpha = 0.72f),
            1f to backgroundColor.copy(alpha = 0.94f),
        )
    }
    return background(Brush.verticalGradient(colorStops = colorStops))
}

fun Modifier.scrollFadeEdge(
    listState: LazyListState,
    backgroundColor: Color
): Modifier = drawWithContent {
    val alphaEdgePx = 44.dp.toPx()
    val topOverlayPx = 132.dp.toPx().coerceAtMost(size.height)
    val bottomOverlayPx = 48.dp.toPx()

    val topScrollOffset = if (listState.firstVisibleItemIndex == 0) {
        listState.firstVisibleItemScrollOffset.toFloat()
    } else {
        alphaEdgePx
    }
    val visibleAlpha = ((topScrollOffset * 2f) / alphaEdgePx).coerceIn(0f, 1f)

    drawContent()

    if (visibleAlpha > 0f) {
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to backgroundColor.copy(alpha = 0.9f * visibleAlpha),
                    0.4f to backgroundColor.copy(alpha = 0.75f * visibleAlpha),
                    1.0f to backgroundColor.copy(alpha = 0.0f),
                ),
                startY = 0f,
                endY = topOverlayPx,
            ),
            topLeft = Offset.Zero,
            size = Size(size.width, topOverlayPx),
        )
    }

    if (size.height > 0f) {
        val bottomTop = (size.height - bottomOverlayPx).coerceAtLeast(0f)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    backgroundColor.copy(alpha = 0.0f),
                    backgroundColor,
                ),
                startY = bottomTop,
                endY = size.height,
            ),
            topLeft = Offset(0f, bottomTop),
            size = Size(size.width, size.height - bottomTop),
        )
    }
}

/**
 * 为普通纵向 ScrollState 提供上下滚动边缘渐隐遮罩。
 * 当顶部或底部仍有未滚完的内容时，按滚动距离平滑淡入渐隐层。
 */
fun Modifier.scrollFadeEdge(
    scrollState: ScrollState,
    backgroundColor: Color,
): Modifier = drawWithContent {
    val alphaEdgePx = 44.dp.toPx()
    val topOverlayPx = 56.dp.toPx().coerceAtMost(size.height)
    val bottomOverlayPx = 56.dp.toPx().coerceAtMost(size.height)

    val topAlpha = (scrollState.value.toFloat() / alphaEdgePx).coerceIn(0f, 1f)
    val remainingBottomPx = (scrollState.maxValue - scrollState.value).coerceAtLeast(0).toFloat()
    val bottomAlpha = (remainingBottomPx / alphaEdgePx).coerceIn(0f, 1f)

    drawContent()

    if (topAlpha > 0f && topOverlayPx > 0f) {
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to backgroundColor.copy(alpha = 0.94f * topAlpha),
                    0.45f to backgroundColor.copy(alpha = 0.72f * topAlpha),
                    1.0f to Color.Transparent,
                ),
                startY = 0f,
                endY = topOverlayPx,
            ),
            topLeft = Offset.Zero,
            size = Size(size.width, topOverlayPx),
        )
    }

    if (bottomAlpha > 0f && bottomOverlayPx > 0f) {
        val bottomTop = (size.height - bottomOverlayPx).coerceAtLeast(0f)
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to Color.Transparent,
                    0.55f to backgroundColor.copy(alpha = 0.72f * bottomAlpha),
                    1.0f to backgroundColor.copy(alpha = 0.94f * bottomAlpha),
                ),
                startY = bottomTop,
                endY = size.height,
            ),
            topLeft = Offset(0f, bottomTop),
            size = Size(size.width, size.height - bottomTop),
        )
    }
}
