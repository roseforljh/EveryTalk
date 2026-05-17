package com.android.everytalk.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
