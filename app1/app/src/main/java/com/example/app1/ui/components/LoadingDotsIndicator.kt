package com.example.app1.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun LoadingDotsIndicator(
    modifier: Modifier = Modifier,
    textColor: Color = LocalContentColor.current // 默认使用本地内容颜色
) {
    val dotSize = 6.dp
    val bounceHeight = 4.dp
    val animationDuration = 600
    val delayBetweenDots = animationDuration / 4
    val dotColors = listOf(
        textColor.copy(alpha = 0.9f),
        textColor.copy(alpha = 0.7f),
        textColor.copy(alpha = 0.5f)
    )
    val density = LocalDensity.current
    val bounceHeightPx = remember(density, bounceHeight) { with(density) { bounceHeight.toPx() } }
    val transition = rememberInfiniteTransition(label = "LoadingDots")
    val offsetsY = List(3) { index ->
        transition.animateFloat(
            0f, -bounceHeightPx,
            infiniteRepeatable(
                animation = keyframes {
                    durationMillis = animationDuration
                    0f at 0
                    -bounceHeightPx at animationDuration / 2
                    0f at animationDuration
                },
                repeatMode = RepeatMode.Restart,
                initialStartOffset = StartOffset(index * delayBetweenDots)
            ),
            label = "DotBounceY$index"
        )
    }
    Row(
        modifier = modifier
            .height(dotSize + bounceHeight) // Ensure enough height for bounce
            .padding(vertical = (bounceHeight / 2)), // Center vertically
        verticalAlignment = Alignment.Bottom // Align dots to bottom for consistent bounce baseline
    ) {
        offsetsY.forEachIndexed { index, offsetY ->
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .offset { IntOffset(0, offsetY.value.roundToInt()) }
                    .size(dotSize)
                    .background(dotColors[index % dotColors.size], CircleShape)
            )
            // No explicit Spacer needed if padding is applied correctly
        }
    }
}