package com.android.everytalk.ui.components.sheet

import androidx.compose.foundation.ScrollState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal const val AppModalBottomSheetMaximumHeightFraction = 0.9f
internal const val AppModalBottomSheetDefaultHeightFraction = 0.55f

// Material 3 默认拖动条高 4dp，并带上下各 22dp 内边距。
private val AppModalBottomSheetDragHandleSpace = 48.dp

private fun appModalBottomSheetMappedTop(
    rawOffset: Float,
    windowHeightPx: Float,
): Float {
    if (!rawOffset.isFinite() || windowHeightPx <= 0f) return rawOffset
    val expandedTop = windowHeightPx * (1f - AppModalBottomSheetMaximumHeightFraction)
    val materialPartialTop = windowHeightPx * 0.5f
    val desiredPartialTop = windowHeightPx * (1f - AppModalBottomSheetDefaultHeightFraction)
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
    stateModifier: (SheetValue, Boolean) -> Modifier = { _, _ -> Modifier },
    dragHandleModifier: Modifier = Modifier,
    bottomIndicatorModifier: Modifier = Modifier,
    header: @Composable ColumnScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
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
                AppModalBottomSheetDefaultHeightFraction)
        ).toInt()
    val overflowTolerancePx = with(density) { 1.dp.roundToPx() }
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
    val openExpanded = initiallyExpanded == true
    var measuredContentHeightPx by remember { mutableIntStateOf(0) }
    var contentViewportHeightPx by remember { mutableIntStateOf(0) }
    var contentMeasured by remember { mutableStateOf(false) }
    var expansionEnabled by remember { mutableStateOf(openExpanded) }
    var presentationReady by remember { mutableStateOf(false) }
    var expansionInProgress by remember { mutableStateOf(false) }
    val initialTarget = if (openExpanded) {
        SheetValue.Expanded
    } else {
        SheetValue.PartiallyExpanded
    }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = openExpanded,
        confirmValueChange = { targetValue ->
            when {
                !presentationReady -> targetValue == initialTarget
                targetValue == SheetValue.Expanded -> expansionEnabled
                targetValue == SheetValue.Hidden ||
                    targetValue == SheetValue.PartiallyExpanded ->
                    !expansionInProgress && scrollState.value == 0
                else -> true
            }
        },
    )
    val interactionLocked = !presentationReady ||
        expansionInProgress ||
        sheetState.isAnimationRunning
    val canDismissSheet = !interactionLocked && scrollState.value == 0
    val visibleContentViewportHeightPx =
        (contentViewportHeightPx - partiallyExpandedHiddenHeightPx).coerceAtLeast(0)

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
        expansionEnabled,
    ) {
        if (
            !sheetState.isAnimationRunning &&
            sheetState.currentValue == SheetValue.PartiallyExpanded &&
            visibleContentViewportHeightPx > 0 &&
            contentMeasured &&
            !expansionEnabled &&
            measuredContentHeightPx > visibleContentViewportHeightPx + overflowTolerancePx
        ) {
            expansionEnabled = true
        }
    }
    LaunchedEffect(expansionEnabled, openExpanded) {
        if (expansionEnabled && !openExpanded) {
            expansionInProgress = true
            try {
                sheetState.expand()
            } finally {
                expansionInProgress = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.graphicsLayer {
            // ponytail: Material 3 未开放自定义半展开锚点，用连续位移映射保留原生手势和关闭逻辑。
            val rawOffset = runCatching { sheetState.requireOffset() }.getOrNull()
            translationY = rawOffset?.let {
                appModalBottomSheetMappedTop(it, windowHeightPx.toFloat()) - it
            } ?: 0f
        },
        sheetState = sheetState,
        sheetGesturesEnabled = !interactionLocked,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        properties = ModalBottomSheetProperties(
            shouldDismissOnBackPress = canDismissSheet,
            shouldDismissOnClickOutside = canDismissSheet,
        ),
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                modifier = dragHandleModifier,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(maximumContentHeight)
                .then(sheetContentModifier),
        ) {
            header()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .then(stateModifier(sheetState.currentValue, expansionEnabled)),
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
                                ) - expandedTop).coerceAtLeast(0f)
                            } ?: 0f
                        }
                        .then(bottomIndicatorModifier),
                )
            }
        }
    }
}
