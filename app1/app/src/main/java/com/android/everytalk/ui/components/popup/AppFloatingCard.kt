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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val AppFloatingCardShape = RoundedCornerShape(28.dp)
val AppFloatingCardElevation = 8.dp
val AppFloatingCardTransformOrigin = TransformOrigin(0.5f, 0f)

private val AppFloatingCardEmphasizedDecelerate = CubicBezierEasing(0f, 0f, 0.2f, 1f)
private val AppFloatingCardDecelerate = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
private val AppFloatingCardAccelerate = CubicBezierEasing(0.4f, 0f, 1f, 1f)
private const val AppFloatingCardExitDurationMillis = 80

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
    visible: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scale = remember { Animatable(0.8f) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(visible) {
        if (visible) {
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
        } else {
            alpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = AppFloatingCardExitDurationMillis,
                    easing = AppFloatingCardAccelerate,
                ),
            )
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

@Composable
fun AppFloatingCardPopup(
    visible: Boolean,
    onDismissRequest: (() -> Unit)?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopStart,
    offset: IntOffset = IntOffset.Zero,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit,
) {
    val shouldRender = rememberAppFloatingCardShouldRender(visible)
    if (shouldRender) {
        Popup(
            alignment = alignment,
            offset = offset,
            onDismissRequest = onDismissRequest,
            properties = properties,
        ) {
            AppFloatingCard(
                visible = visible,
                modifier = modifier,
                content = content,
            )
        }
    }
}

@Composable
fun AppFloatingCardPopup(
    visible: Boolean,
    popupPositionProvider: PopupPositionProvider,
    onDismissRequest: (() -> Unit)?,
    modifier: Modifier = Modifier,
    properties: PopupProperties = PopupProperties(),
    content: @Composable () -> Unit,
) {
    val shouldRender = rememberAppFloatingCardShouldRender(visible)
    if (shouldRender) {
        Popup(
            popupPositionProvider = popupPositionProvider,
            onDismissRequest = onDismissRequest,
            properties = properties,
        ) {
            AppFloatingCard(
                visible = visible,
                modifier = modifier,
                content = content,
            )
        }
    }
}

@Composable
private fun rememberAppFloatingCardShouldRender(
    visible: Boolean,
): Boolean {
    var shouldRender by remember { mutableStateOf(visible) }
    LaunchedEffect(visible) {
        if (visible) {
            shouldRender = true
        } else {
            delay(AppFloatingCardExitDurationMillis.toLong())
            shouldRender = false
        }
    }
    return shouldRender
}
