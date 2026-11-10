package com.android.everytalk.ui.components.popup

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

val AppFloatingCardShape = RoundedCornerShape(28.dp)
val AppFloatingCardTransformOrigin = TransformOrigin(0.5f, 0f)

// ChatGPT 1.2026.279 默认弹层（xuk0 → ror0.d → evs.e）：
// 亮色黑色20%、90dp 模糊、零偏移零扩散投影、无描边；暗色无投影、0.5dp #26FFFFFF 描边。
private val GptShadowRadius = 90.dp
private const val GptShadowAlpha = 0.2f
// 90dp 模糊实际外扩约 1.7 倍半径，留白不足会在边缘切出硬边。
private val GptShadowInset = 160.dp

// 不能关闭的非模态卡片仍用卡片大小的窗口（外部触摸要穿透），只能用不会被窗口裁切的小投影。
private val SmallShadowRadius = 12.dp
private const val SmallShadowAlpha = 0.16f
private val SmallShadowInset = 32.dp

private val AppFloatingCardEmphasizedDecelerate = CubicBezierEasing(0f, 0f, 0.2f, 1f)
private val AppFloatingCardDecelerate = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
private val AppFloatingCardAccelerate = CubicBezierEasing(0.4f, 0f, 1f, 1f)
private const val AppFloatingCardExitDurationMillis = 80

/** 跟随应用实际主题（含手动切换），而不是系统主题。 */
@Composable
internal fun appFloatingCardIsDark(): Boolean =
    MaterialTheme.colorScheme.background.luminance() < 0.5f

@Composable
fun appFloatingCardContainerColor(): Color =
    resolveAppFloatingCardContainerColor(appFloatingCardIsDark())

/** GPT pcm.a：暗色 #303030，亮色白。 */
internal fun resolveAppFloatingCardContainerColor(isDarkTheme: Boolean): Color =
    if (isDarkTheme) Color(0xFF303030) else Color.White

@Composable
fun appFloatingCardBorderColor(): Color =
    if (appFloatingCardIsDark()) Color(0x26FFFFFF) else Color(0x1A000000)

/** 暗色才描边；亮色靠投影区分层级。 */
@Composable
fun appFloatingCardBorder(): BorderStroke? =
    if (appFloatingCardIsDark()) BorderStroke(0.5.dp, Color(0x26FFFFFF)) else null

@Composable
fun appFloatingCardShadowAlpha(): Float =
    if (appFloatingCardIsDark()) 0f else GptShadowAlpha

/** 页面内（非 Popup）浮动卡片的同款效果：亮色 90dp 投影，暗色 0.5dp 描边。 */
@Composable
fun Modifier.appFloatingCardShadow(shape: Shape): Modifier =
    if (appFloatingCardIsDark()) {
        border(0.5.dp, Color(0x26FFFFFF), shape)
    } else {
        dropShadow(shape) {
            radius = GptShadowRadius.toPx()
            spread = 0f
            offset = Offset.Zero
            color = Color.Black
            alpha = GptShadowAlpha
        }
    }

@Composable
fun AppFloatingCardContainer(
    visible: Boolean,
    modifier: Modifier,
    onExitAnimationFinished: () -> Unit,
    shape: RoundedCornerShape = AppFloatingCardShape,
    containerColor: Color = appFloatingCardContainerColor(),
    border: BorderStroke? = appFloatingCardBorder(),
    shadowRadius: Dp = GptShadowRadius,
    shadowAlpha: Float = appFloatingCardShadowAlpha(),
    shadowInset: Dp = if (shadowAlpha > 0f) GptShadowInset else 0.dp,
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
                val shadowInset = shadowInset.roundToPx()
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
            .padding(shadowInset)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                transformOrigin = AppFloatingCardTransformOrigin
            }
            .then(
                if (shadowAlpha > 0f) Modifier.dropShadow(shape) {
                    radius = shadowRadius.toPx()
                    spread = 0f
                    offset = Offset.Zero
                    color = Color.Black
                    this.alpha = shadowAlpha
                } else Modifier,
            )
            .then(if (border != null) Modifier.border(border, shape) else Modifier),
        shape = shape,
        color = containerColor,
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

/** 与 Compose 内置 `Popup(alignment, offset)` 相同的定位规则。 */
private class AlignmentOffsetProvider(
    private val alignment: Alignment,
    private val offset: IntOffset,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val anchorPoint = alignment.align(IntSize.Zero, anchorBounds.size, layoutDirection)
        val popupPoint = alignment.align(IntSize.Zero, popupContentSize, layoutDirection)
        val dx = if (layoutDirection == LayoutDirection.Ltr) offset.x else -offset.x
        return anchorBounds.topLeft + anchorPoint - popupPoint + IntOffset(dx, offset.y)
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
    AppFloatingCardPopup(
        visible = visible,
        popupPositionProvider = remember(alignment, offset) { AlignmentOffsetProvider(alignment, offset) },
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        properties = properties,
        onExitAnimationFinished = onExitAnimationFinished,
        content = content,
    )
}

/**
 * 统一悬浮卡片 Popup。
 *
 * 可关闭的弹层使用铺满屏幕的透明窗口：Popup 窗口按内容大小裁剪，窗口只有卡片大时
 * 90dp 投影只剩圆角外的直角。卡片在窗口内按 [popupPositionProvider] 摆放，按下卡片外关闭。
 */
@Composable
fun AppFloatingCardPopup(
    visible: Boolean,
    popupPositionProvider: PopupPositionProvider,
    onDismissRequest: (() -> Unit)?,
    modifier: Modifier = Modifier,
    properties: PopupProperties = PopupProperties(),
    onExitAnimationFinished: () -> Unit = {},
    shape: RoundedCornerShape = AppFloatingCardShape,
    containerColor: Color = appFloatingCardContainerColor(),
    border: BorderStroke? = appFloatingCardBorder(),
    matchAnchorWidth: Boolean = false,
    content: @Composable () -> Unit,
) {
    var shouldRender by remember { mutableStateOf(visible) }
    LaunchedEffect(visible) {
        if (visible) shouldRender = true
    }
    if (!shouldRender) return
    val onExit = {
        shouldRender = false
        onExitAnimationFinished()
    }
    val fullWindow = onDismissRequest != null &&
        (properties.focusable || properties.dismissOnClickOutside)

    if (!fullWindow) {
        Popup(
            popupPositionProvider = popupPositionProvider,
            onDismissRequest = onDismissRequest,
            properties = properties,
        ) {
            AppFloatingCardContainer(
                visible = visible,
                modifier = modifier,
                onExitAnimationFinished = onExit,
                shape = shape,
                containerColor = containerColor,
                border = border,
                shadowRadius = SmallShadowRadius,
                shadowAlpha = if (appFloatingCardIsDark()) 0f else SmallShadowAlpha,
                shadowInset = SmallShadowInset,
                content = content,
            )
        }
        return
    }

    var anchor by remember { mutableStateOf<Triple<IntRect, IntSize, LayoutDirection>?>(null) }
    val windowProvider = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                anchor = Triple(anchorBounds, windowSize, layoutDirection)
                return IntOffset.Zero
            }
        }
    }
    val cardRect = remember { arrayOf(IntRect.Zero) }
    val clampToWindow = properties.clippingEnabled
    Popup(
        popupPositionProvider = windowProvider,
        onDismissRequest = onDismissRequest,
        // 窗口固定在左上角铺满，不能让系统再挪动它，否则卡片坐标会整体偏移。
        properties = PopupProperties(
            focusable = properties.focusable,
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = properties.dismissOnClickOutside,
            securePolicy = properties.securePolicy,
            excludeFromSystemGesture = properties.excludeFromSystemGesture,
            clippingEnabled = false,
        ),
    ) {
        Layout(
            content = {
                AppFloatingCardContainer(
                    visible = visible,
                    modifier = modifier,
                    onExitAnimationFinished = onExit,
                    shape = shape,
                    containerColor = containerColor,
                    border = border,
                    content = content,
                )
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(onDismissRequest, properties.dismissOnClickOutside) {
                    awaitEachGesture {
                        val p = awaitFirstDown(requireUnconsumed = false).position
                        val inCard = cardRect[0].contains(IntOffset(p.x.toInt(), p.y.toInt()))
                        if (!inCard && properties.dismissOnClickOutside) onDismissRequest()
                    }
                },
        ) { measurables, constraints ->
            val a = anchor
            val cardConstraints = if (matchAnchorWidth && a != null) {
                val w = a.first.width.coerceAtMost(constraints.maxWidth)
                Constraints(minWidth = w, maxWidth = w, maxHeight = constraints.maxHeight)
            } else {
                constraints.copy(minWidth = 0, minHeight = 0)
            }
            val card = measurables.first().measure(cardConstraints)
            val size = IntSize(card.width, card.height)
            var pos = if (a == null) IntOffset.Zero else popupPositionProvider.calculatePosition(a.first, a.second, a.third, size)
            if (clampToWindow) {
                pos = IntOffset(
                    pos.x.coerceIn(0, (constraints.maxWidth - size.width).coerceAtLeast(0)),
                    pos.y.coerceIn(0, (constraints.maxHeight - size.height).coerceAtLeast(0)),
                )
            }
            cardRect[0] = IntRect(pos, size)
            layout(constraints.maxWidth, constraints.maxHeight) {
                if (a != null) card.place(pos)
            }
        }
    }
}

/** 下拉菜单定位：贴锚点下方起始边对齐，下方放不下时翻到上方。 */
private class DropdownBelowProvider(
    private val offset: IntOffset,
    private val margin: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.left + offset.x
        } else {
            anchorBounds.right - popupContentSize.width - offset.x
        }
        val below = anchorBounds.bottom + offset.y
        val above = anchorBounds.top - offset.y - popupContentSize.height
        val y = if (below + popupContentSize.height <= windowSize.height - margin || above < margin) below else above
        return IntOffset(x, y)
    }
}

/**
 * 替代 Material DropdownMenu / ExposedDropdownMenu 的统一下拉菜单。
 * Material 菜单自带的窗口只有菜单大，GPT 的大半径投影会被裁成直角，因此统一走 [AppFloatingCardPopup]。
 */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
    matchAnchorWidth: Boolean = false,
    focusable: Boolean = true,
    maxHeight: Dp = 280.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val provider = remember(offset, density) {
        with(density) {
            DropdownBelowProvider(IntOffset(offset.x.roundToPx(), offset.y.roundToPx()), 8.dp.roundToPx())
        }
    }
    AppFloatingCardPopup(
        visible = expanded,
        popupPositionProvider = provider,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        properties = PopupProperties(focusable = focusable, dismissOnClickOutside = true),
        matchAnchorWidth = matchAnchorWidth,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = maxHeight)
                .width(IntrinsicSize.Max)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            content = content,
        )
    }
}
