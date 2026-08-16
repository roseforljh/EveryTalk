package com.android.everytalk.ui.components.popup

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.android.everytalk.ui.theme.LightPopupBackground
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

val AppFloatingCardShape = RoundedCornerShape(28.dp)
val AppFloatingCardElevation = 8.dp
val AppFloatingCardTransformOrigin = TransformOrigin(0.5f, 0f)
private val AppFloatingCardShadowInset = 32.dp

private val AppFloatingCardEmphasizedDecelerate = CubicBezierEasing(0f, 0f, 0.2f, 1f)
private val AppFloatingCardDecelerate = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
private val AppFloatingCardAccelerate = CubicBezierEasing(0.4f, 0f, 1f, 1f)
private const val AppFloatingCardExitDurationMillis = 80

@Composable
fun appFloatingCardContainerColor(): Color =
    resolveAppFloatingCardContainerColor(isSystemInDarkTheme())

internal fun resolveAppFloatingCardContainerColor(isDarkTheme: Boolean): Color =
    if (isDarkTheme) Color(0xFF242424) else Color.White

@Composable
fun appFloatingCardBorderColor(): Color =
    if (isSystemInDarkTheme()) {
        Color.White.copy(alpha = 0.12f)
    } else {
        Color(0xFF0D0D0D).copy(alpha = 0.08f)
    }

@Composable
fun AppFloatingCardContainer(
    visible: Boolean,
    modifier: Modifier,
    onExitAnimationFinished: () -> Unit,
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
            withFrameNanos { }
            onExitAnimationFinished()
        }
    }

    Surface(
        modifier = modifier
            .layout { measurable, constraints ->
                val shadowInset = AppFloatingCardShadowInset.roundToPx()
                val totalInset = shadowInset * 2
                val placeable = measurable.measure(
                    constraints.offset(
                        horizontal = totalInset,
                        vertical = totalInset,
                    ),
                )
                layout(
                    width = (placeable.width - totalInset)
                        .coerceIn(constraints.minWidth, constraints.maxWidth),
                    height = (placeable.height - totalInset)
                        .coerceIn(constraints.minHeight, constraints.maxHeight),
                ) {
                    placeable.place(-shadowInset, -shadowInset)
                }
            }
            .graphicsLayer {
                this.alpha = alpha.value
            }
            .padding(AppFloatingCardShadowInset)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                transformOrigin = AppFloatingCardTransformOrigin
            }
            .dropShadow(AppFloatingCardShape) {
                radius = 12.dp.toPx()
                offset = Offset.Zero
                color = Color.Black
                this.alpha = 0.16f
            }
            .border(1.dp, appFloatingCardBorderColor(), AppFloatingCardShape),
        shape = AppFloatingCardShape,
        color = appFloatingCardContainerColor(),
        content = content,
    )
}

/**
 * 统一的固定头尾悬浮卡片布局。
 * 外层只由 [AppFloatingCardContainer] 绘制一次背景，中间内容独立滚动，避免头尾再套 Surface 形成色块。
 */
@Composable
fun AppFloatingCardScaffold(
    visible: Boolean,
    modifier: Modifier,
    onExitAnimationFinished: () -> Unit,
    header: @Composable ColumnScope.() -> Unit,
    footer: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppFloatingCardContainer(
        visible = visible,
        modifier = modifier,
        onExitAnimationFinished = onExitAnimationFinished,
    ) {
        AppFloatingCardScaffoldContent(header, footer, content)
    }
}

/**
 * 把固定头尾卡片放入真正的 Popup。
 * Popup 不参与宿主布局测量，适合输入框上方这类不应推动消息列表的卡片。
 */
@Composable
fun AppFloatingCardScaffoldPopup(
    visible: Boolean,
    popupPositionProvider: PopupPositionProvider,
    modifier: Modifier,
    onExitAnimationFinished: () -> Unit,
    properties: PopupProperties = PopupProperties(),
    header: @Composable ColumnScope.() -> Unit,
    footer: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppFloatingCardPopup(
        visible = visible,
        popupPositionProvider = popupPositionProvider,
        onDismissRequest = null,
        modifier = modifier,
        properties = properties,
        onExitAnimationFinished = onExitAnimationFinished,
    ) {
        AppFloatingCardScaffoldContent(header, footer, content)
    }
}

@Composable
private fun AppFloatingCardScaffoldContent(
    header: @Composable ColumnScope.() -> Unit,
    footer: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val containerColor = appFloatingCardContainerColor()
    // 卡片内部只有这一层背景，头部、滚动区和底部不再各自生成白色 Surface。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(containerColor),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = header,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState()),
            content = content,
        )
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            content = footer,
        )
    }
}

@Composable
fun AppFloatingCardPopup(
    visible: Boolean,
    onDismissRequest: (() -> Unit)?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.TopStart,
    offset: IntOffset = IntOffset.Zero,
    properties: PopupProperties = PopupProperties(),
    onExitAnimationFinished: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var shouldRender by remember { mutableStateOf(visible) }
    LaunchedEffect(visible) {
        if (visible) shouldRender = true
    }
    if (shouldRender) {
        Popup(
            alignment = alignment,
            offset = offset,
            onDismissRequest = onDismissRequest,
            properties = properties,
        ) {
            AppFloatingCardContainer(
                visible = visible,
                modifier = modifier,
                onExitAnimationFinished = {
                    shouldRender = false
                    onExitAnimationFinished()
                },
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
    onExitAnimationFinished: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var shouldRender by remember { mutableStateOf(visible) }
    LaunchedEffect(visible) {
        if (visible) shouldRender = true
    }
    if (shouldRender) {
        Popup(
            popupPositionProvider = popupPositionProvider,
            onDismissRequest = onDismissRequest,
            properties = properties,
        ) {
            AppFloatingCardContainer(
                visible = visible,
                modifier = modifier,
                onExitAnimationFinished = {
                    shouldRender = false
                    onExitAnimationFinished()
                },
                content = content,
            )
        }
    }
}
