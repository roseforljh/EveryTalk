package com.android.everytalk.ui.components.popup

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

val AppFloatingCardShape = RoundedCornerShape(28.dp)
val AppFloatingCardElevation = 8.dp
val AppFloatingCardTransformOrigin = TransformOrigin(0.5f, 0f)

private val AppFloatingCardEmphasizedDecelerate = CubicBezierEasing(0f, 0f, 0.2f, 1f)
private val AppFloatingCardDecelerate = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

@Composable
fun appFloatingCardContainerColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFF212121) else Color.White

@Composable
fun appFloatingCardBorderColor(): Color =
    if (isSystemInDarkTheme()) {
        Color.White.copy(alpha = 0.10f)
    } else {
        Color(0xFF0D0D0D).copy(alpha = 0.05f)
    }

@Composable
fun AppFloatingCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scale = remember { Animatable(0.8f) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        coroutineScope {
            launch {
                scale.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 120,
                        easing = AppFloatingCardEmphasizedDecelerate,
                    ),
                )
            }
            launch {
                alpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 30,
                        easing = AppFloatingCardDecelerate,
                    ),
                )
            }
        }
    }

    Surface(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
                transformOrigin = AppFloatingCardTransformOrigin
            }
            .shadow(AppFloatingCardElevation, AppFloatingCardShape)
            .border(1.dp, appFloatingCardBorderColor(), AppFloatingCardShape),
        shape = AppFloatingCardShape,
        color = appFloatingCardContainerColor(),
        content = content,
    )
}
