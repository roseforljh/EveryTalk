package com.android.everytalk.ui.components.sheet

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** 全屏档位，允许抽屉顶边贴近窗口顶部。 */
internal const val AppModalBottomSheetMaximumHeightFraction = 1.0f

/** 当前档位，保留顶部约 10% 的空间作为可继续上拉的视觉提示。 */
internal const val AppModalBottomSheetDefaultHeightFraction = 0.9f

/** 返回操作的最低可见档位；再返回一次才隐藏。 */
private const val AppModalBottomSheetLowHeightFraction = 0.5f
private const val AppModalBottomSheetBackAnimationMillis = 280

// Material 3 默认拖动条高 4dp，并带上下各 22dp 内边距。
private val AppModalBottomSheetDragHandleSpace = 48.dp

private fun appModalBottomSheetMappedTop(
    rawOffset: Float,
    windowHeightPx: Float,
    partialHeightFraction: Float,
): Float {
    if (!rawOffset.isFinite() || windowHeightPx <= 0f) return rawOffset
    val expandedTop = windowHeightPx * (1f - AppModalBottomSheetMaximumHeightFraction)
    val materialPartialTop = windowHeightPx * 0.5f
    val desiredPartialTop = windowHeightPx * (1f - partialHeightFraction)
    return if (rawOffset <= materialPartialTop) {
        val progress = ((rawOffset - expandedTop) / (materialPartialTop - expandedTop))
            .coerceIn(0f, 1f)
        expandedTop + (desiredPartialTop - expandedTop) * progress
    } else {
        val progress = ((rawOffset - materialPartialTop) /
            (windowHeightPx - materialPartialTop)).coerceIn(0f, 1f)
        desiredPartialTop + (windowHeightPx - desiredPartialTop) * progress
    }
}

@Composable
private fun AppModalBottomSheetInitialMeasure(
    sheetWidth: Dp,
    defaultContentHeightPx: Int,
    overflowTolerancePx: Int,
    contentModifier: Modifier,
    header: @Composable ColumnScope.() -> Unit,
    content: @Composable () -> Unit,
    onMeasured: (Boolean) -> Unit,
) {
    var headerHeightPx by remember { mutableIntStateOf(0) }
    var contentHeightPx by remember { mutableIntStateOf(0) }
    var headerMeasured by remember { mutableStateOf(false) }
    var contentMeasured by remember { mutableStateOf(false) }

    LaunchedEffect(
        headerHeightPx,
        contentHeightPx,
        headerMeasured,
        contentMeasured,
    ) {
        if (headerMeasured && contentMeasured) {
            val defaultViewportHeightPx =
                (defaultContentHeightPx - headerHeightPx).coerceAtLeast(0)
            onMeasured(contentHeightPx > defaultViewportHeightPx + overflowTolerancePx)
        }
    }

    Box(
        modifier = Modifier
            .size(0.dp)
            .clipToBounds()
            .clearAndSetSemantics {},
    ) {
        Column(
            modifier = Modifier
                .requiredWidth(sheetWidth)
                .wrapContentHeight(unbounded = true)
                .alpha(0f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged {
                        headerHeightPx = it.height
                        headerMeasured = true
                    },
                content = header,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged {
                        contentHeightPx = it.height
                        contentMeasured = true
                    }
                    .then(contentModifier),
            ) {
                content()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    sheetContentModifier: Modifier = Modifier,
    scrollModifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    /** 标题保持实体底色，仅在标题下边缘让上滚的正文渐隐。 */
    topGradient: Boolean = false,
    stateModifier: (SheetValue, Boolean) -> Modifier = { _, _ -> Modifier },
    dragHandleModifier: Modifier = Modifier,
    bottomIndicatorModifier: Modifier = Modifier,
    header: @Composable ColumnScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // Material 只提供一个半展开锚点，复用现有位移映射，在 90% 中档和 50% 低档间平滑切换。
    val partialHeightFraction = remember { Animatable(AppModalBottomSheetDefaultHeightFraction) }
    var backTransitionRunning by remember { mutableStateOf(false) }
    val windowSize = LocalWindowInfo.current.containerSize
    val windowHeightPx = windowSize.height
    val maximumContentHeight = with(density) {
        (windowHeightPx.toDp() * AppModalBottomSheetMaximumHeightFraction -
            AppModalBottomSheetDragHandleSpace).coerceAtLeast(0.dp)
    }
    val defaultContentHeightPx = with(density) {
        (windowHeightPx.toDp() * AppModalBottomSheetDefaultHeightFraction -
            AppModalBottomSheetDragHandleSpace).roundToPx().coerceAtLeast(0)
    }
    val sheetWidth = with(density) { windowSize.width.toDp() }
        .coerceAtMost(BottomSheetDefaults.SheetMaxWidth)
    val partiallyExpandedHiddenHeightPx = (
        windowHeightPx *
            (AppModalBottomSheetMaximumHeightFraction -
                partialHeightFraction.value)
        ).toInt()
    // 内容盒包含标题/内边距在不同 Compose 测量阶段的舍入差异；保留一小段缓冲，
    // 避免刚好贴近默认高度的短内容被误判为需要全屏展开。
    val overflowTolerancePx = with(density) { 16.dp.roundToPx() }
    var initiallyExpanded by remember { mutableStateOf<Boolean?>(null) }
    if (initiallyExpanded == null) {
        AppModalBottomSheetInitialMeasure(
            sheetWidth = sheetWidth,
            defaultContentHeightPx = defaultContentHeightPx,
            overflowTolerancePx = overflowTolerancePx,
            contentModifier = contentModifier,
            header = header,
            content = content,
            onMeasured = { initiallyExpanded = it },
        )
        return
    }
    var measuredContentHeightPx by remember { mutableIntStateOf(0) }
    var contentViewportHeightPx by remember { mutableIntStateOf(0) }
    var contentMeasured by remember { mutableStateOf(false) }
    var presentationReady by remember { mutableStateOf(false) }
    // 长内容只用于标记内容是否可滚动，不能改变初始档位；全屏由用户上拉进入。
    var contentOverflows by remember { mutableStateOf(initiallyExpanded == true) }
    val initialTarget = SheetValue.PartiallyExpanded
    var gestureFromHandle by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(
        // 始终保留 Hidden、PartiallyExpanded、Expanded 三个锚点。
        // 长内容也必须先停在中间档，用户继续上拉后才进入 Expanded。
        skipPartiallyExpanded = false,
        confirmValueChange = { targetValue ->
            when {
                !presentationReady -> targetValue == initialTarget
                targetValue == SheetValue.Expanded -> true
                targetValue == SheetValue.Hidden ||
                    targetValue == SheetValue.PartiallyExpanded ->
                    backTransitionRunning || gestureFromHandle || scrollState.value == 0
                else -> true
            }
        },
    )
    val interactionLocked = !presentationReady || sheetState.isAnimationRunning || backTransitionRunning
    val canDismissSheet = !interactionLocked && scrollState.value == 0
    val visibleContentViewportHeightPx =
        (contentViewportHeightPx - partiallyExpandedHiddenHeightPx).coerceAtLeast(0)

    LaunchedEffect(sheetState.currentValue) {
        // 从低档拖回全屏后，下一次返回仍先落到中档，不能沿用上一次的低档高度。
        if (sheetState.currentValue == SheetValue.Expanded) {
            partialHeightFraction.snapTo(AppModalBottomSheetDefaultHeightFraction)
        }
    }

    LaunchedEffect(
        sheetState.currentValue,
        sheetState.isAnimationRunning,
        initialTarget,
        presentationReady,
    ) {
        if (
            !presentationReady &&
            !sheetState.isAnimationRunning &&
            sheetState.currentValue == initialTarget
        ) {
            presentationReady = true
        }
    }

    LaunchedEffect(
        sheetState.currentValue,
        sheetState.isAnimationRunning,
        measuredContentHeightPx,
        visibleContentViewportHeightPx,
        contentMeasured,
    ) {
        if (
            !sheetState.isAnimationRunning &&
            sheetState.currentValue == SheetValue.PartiallyExpanded &&
            visibleContentViewportHeightPx > 0 &&
            contentMeasured &&
            measuredContentHeightPx > visibleContentViewportHeightPx + overflowTolerancePx
        ) {
            contentOverflows = true
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.graphicsLayer {
            // ponytail: Material 3 未开放自定义半展开锚点，用连续位移映射保留原生手势和关闭逻辑。
            val rawOffset = runCatching { sheetState.requireOffset() }.getOrNull()
            translationY = rawOffset?.let {
                appModalBottomSheetMappedTop(it, windowHeightPx.toFloat(), partialHeightFraction.value) - it
            } ?: 0f
        },
        sheetState = sheetState,
        // 普通吸附动画必须能被下一次拖动打断。若跟随 isAnimationRunning 禁用手势，
        // Material 会移除嵌套滚动连接和拖动处理，正文到顶后的连续下拉会失去接收方。
        // 仅在初次展示和返回逐档下降期间锁定，继续使用 Material 原生的动画打断逻辑。
        sheetGesturesEnabled = presentationReady && !backTransitionRunning,
        // 抽屉本身必须保持实体底色，避免顶部把下面的聊天内容透出来。
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        properties = ModalBottomSheetProperties(
            // 返回由抽屉窗口内的 BackHandler 逐档处理，不受正文滚动位置限制。
            shouldDismissOnBackPress = false,
            shouldDismissOnClickOutside = canDismissSheet,
        ),
        dragHandle = {
            // 整行留白都是把手区域，而不只是中间细小的灰条。
            Box(
                modifier = dragHandleModifier
                    .fillMaxWidth()
                    .height(AppModalBottomSheetDragHandleSpace)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            // 只观察，不消费事件；仍由 Material 处理拖动和关闭动画。
                            // 松手后的 settle 还会检查关闭权限，因此保留到下一次手势。
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            gestureFromHandle = true
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                BottomSheetDefaults.DragHandle(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        },
    ) {
        BackHandler {
            // 动画期间消费额外返回，避免一次连滑跨过多档或退出下面的聊天页面。
            if (!interactionLocked && !backTransitionRunning) {
                backTransitionRunning = true
                scope.launch {
                    try {
                        when (sheetState.currentValue) {
                            SheetValue.Expanded -> sheetState.partialExpand()
                            SheetValue.PartiallyExpanded -> {
                                if (partialHeightFraction.value > AppModalBottomSheetLowHeightFraction) {
                                    partialHeightFraction.animateTo(
                                        AppModalBottomSheetLowHeightFraction,
                                        animationSpec = tween(AppModalBottomSheetBackAnimationMillis),
                                    )
                                } else {
                                    sheetState.hide()
                                    if (!sheetState.isVisible) onDismissRequest()
                                }
                            }
                            SheetValue.Hidden -> Unit
                        }
                    } finally {
                        backTransitionRunning = false
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(maximumContentHeight)
                // 标题和把手保持实体底色，渐变只叠加在标题下方的正文边缘。
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        // 正文的下一次手势重新恢复滚动限制，不沿用把手的关闭权限。
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        gestureFromHandle = false
                    }
                }
                .then(sheetContentModifier),
        ) {
            header()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .then(stateModifier(sheetState.currentValue, contentOverflows)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { contentViewportHeightPx = it.height },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .then(scrollModifier),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onSizeChanged {
                                    measuredContentHeightPx = it.height
                                    contentMeasured = true
                                }
                                .then(contentModifier),
                        ) {
                            content()
                        }
                    }
                }
                if (topGradient && scrollState.value > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(24.dp)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.surface,
                                        Color.Transparent,
                                    ),
                                    startY = 0f,
                                    endY = with(density) { 24.dp.toPx() },
                                ),
                            ),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.88f))
                        .graphicsLayer {
                            val rawOffset = runCatching { sheetState.requireOffset() }.getOrNull()
                            val expandedTop = windowHeightPx *
                                (1f - AppModalBottomSheetMaximumHeightFraction)
                            translationY = rawOffset?.let {
                                -(appModalBottomSheetMappedTop(
                                    rawOffset = it,
                                    windowHeightPx = windowHeightPx.toFloat(),
                                    partialHeightFraction = partialHeightFraction.value,
                                ) - expandedTop).coerceAtLeast(0f)
                            } ?: 0f
                        }
                        .then(bottomIndicatorModifier),
                )
            }
        }
    }
}
