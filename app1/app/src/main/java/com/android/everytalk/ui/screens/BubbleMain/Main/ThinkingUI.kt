package com.android.everytalk.ui.screens.BubbleMain.Main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val REASONING_SHEET_TALL_HEIGHT_FRACTION = 0.9f
// Material 3 默认拖动条高 4dp，并带上下各 22dp 内边距。
private val ReasoningSheetDragHandleSpace = 48.dp

internal fun reasoningSheetTallHeightFraction(): Float = REASONING_SHEET_TALL_HEIGHT_FRACTION

internal fun reasoningSheetText(
    displayedReasoningText: String,
    isReasoningActive: Boolean,
    messageIsError: Boolean,
): String = when {
    displayedReasoningText.isNotBlank() -> displayedReasoningText
    messageIsError -> "思考过程中发生错误"
    isReasoningActive -> ""
    else -> "暂无详细思考内容"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReasoningToggleAndContent(
    currentMessageId: String,
    displayedReasoningText: String,
    activityStatusText: String? = null,
    isReasoningStreaming: Boolean,
    isReasoningComplete: Boolean,
    messageIsError: Boolean,
    mainContentHasStarted: Boolean,
    reasoningTextColor: Color,
    reasoningToggleDotColor: Color,
    modifier: Modifier = Modifier,
    streamingScrollState: ScrollState = rememberScrollState(),
    onVisibilityChanged: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    var manualExpansionEnabled by remember(currentMessageId) { mutableStateOf(false) }
    var expandOnInitialOverflow by remember(currentMessageId) { mutableStateOf(false) }
    var expandReasoningSheetRequested by remember(currentMessageId) { mutableStateOf(false) }
    val reasoningSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false,
        confirmValueChange = { targetValue ->
            targetValue != SheetValue.Expanded || manualExpansionEnabled
        },
    )
    var showReasoningSheet by remember(currentMessageId) { mutableStateOf(false) }
    var visibilityNotified by remember(currentMessageId) { mutableStateOf(false) }

    val showInlineThinkingStatus = !messageIsError &&
        !mainContentHasStarted &&
        (isReasoningStreaming || displayedReasoningText.isNotBlank() || activityStatusText != null)
    val shouldShowReviewDotToggle = displayedReasoningText.isNotBlank() &&
        !messageIsError &&
        (isReasoningComplete || !isReasoningStreaming)
    val openReasoningSheet: (Boolean) -> Unit = { shouldExpandOnInitialOverflow ->
        focusManager.clearFocus()
        manualExpansionEnabled = false
        expandOnInitialOverflow = shouldExpandOnInitialOverflow
        expandReasoningSheetRequested = false
        showReasoningSheet = true
    }
    LaunchedEffect(showReasoningSheet, expandReasoningSheetRequested) {
        if (showReasoningSheet && expandReasoningSheetRequested) {
            reasoningSheetState.expand()
            expandReasoningSheetRequested = false
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
    ) {
        AnimatedVisibility(
            visible = showInlineThinkingStatus,
            enter = fadeIn(tween(150)) + expandVertically(
                animationSpec = tween(220),
                expandFrom = Alignment.Top,
            ),
            exit = fadeOut(tween(180)) + shrinkVertically(
                animationSpec = tween(240, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Top,
            ),
        ) {
            ThinkingStatusRow(
                textColor = reasoningTextColor,
                onClick = { openReasoningSheet(false) },
                modifier = Modifier.onSizeChanged {
                    if (it.height > 0 && !visibilityNotified) {
                        view.post { onVisibilityChanged() }
                        visibilityNotified = true
                    }
                },
            )
        }

        var showDotDelayed by remember(currentMessageId) {
            mutableStateOf(!showInlineThinkingStatus)
        }
        LaunchedEffect(showInlineThinkingStatus) {
            if (showInlineThinkingStatus) {
                showDotDelayed = false
            } else {
                delay(280)
                showDotDelayed = true
            }
        }

        AnimatedVisibility(
            visible = shouldShowReviewDotToggle && showDotDelayed,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(150)),
        ) {
            Box(
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .testTag("reasoning-sheet-review-toggle")
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = { openReasoningSheet(true) },
                        ),
                ) {
                    val circleIconSize by animateDpAsState(
                        targetValue = if (showReasoningSheet) 10.dp else 7.dp,
                        animationSpec = tween(
                            durationMillis = 250,
                            easing = FastOutSlowInEasing,
                        ),
                        label = "reasoningSheetToggleIconSize_$currentMessageId",
                    )
                    Box(
                        modifier = Modifier
                            .size(circleIconSize)
                            .background(reasoningToggleDotColor, CircleShape),
                    )
                }
            }
        }
    }

    if (showReasoningSheet) {
        ReasoningBottomSheet(
            displayedReasoningText = displayedReasoningText,
            activityStatusText = activityStatusText,
            isReasoningActive = !messageIsError && !mainContentHasStarted,
            messageIsError = messageIsError,
            reasoningTextColor = reasoningTextColor,
            scrollState = streamingScrollState,
            sheetState = reasoningSheetState,
            manualExpansionEnabled = manualExpansionEnabled,
            expandOnInitialOverflow = expandOnInitialOverflow,
            onContentOverflow = { shouldExpand ->
                manualExpansionEnabled = true
                expandOnInitialOverflow = false
                if (shouldExpand) expandReasoningSheetRequested = true
            },
            onDismissRequest = { showReasoningSheet = false },
        )
    }
}

@Composable
private fun ThinkingStatusRow(
    textColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                contentDescription = "正在执行"
                role = Role.Button
            }
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScanningHighlightText(
            text = "正在执行",
            textColor = textColor,
            useSmallStyle = false,
        )
    }
}

@Composable
private fun ScanningHighlightText(
    text: String,
    textColor: Color,
    modifier: Modifier = Modifier,
    useSmallStyle: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "scanningHighlightText")
    val shimmerProgress by transition.animateFloat(
        initialValue = -0.55f,
        targetValue = 1.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_650, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "scanningHighlightProgress",
    )
    var textWidthPx by remember { mutableFloatStateOf(1f) }
    val shimmerCenter = shimmerProgress * textWidthPx
    val baseColor = textColor.copy(alpha = 0.58f)
    val highlightColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.98f)
    val shimmerBrush = Brush.linearGradient(
        colorStops = arrayOf(
            0f to baseColor,
            0.34f to baseColor,
            0.5f to highlightColor,
            0.66f to baseColor,
            1f to baseColor,
        ),
        start = Offset(shimmerCenter - textWidthPx * 0.65f, 0f),
        end = Offset(shimmerCenter + textWidthPx * 0.65f, 0f),
    )

    Text(
        text = text,
        style = (if (useSmallStyle) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium)
            .copy(brush = shimmerBrush),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.onSizeChanged { textWidthPx = it.width.coerceAtLeast(1).toFloat() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReasoningBottomSheet(
    displayedReasoningText: String,
    activityStatusText: String?,
    isReasoningActive: Boolean,
    messageIsError: Boolean,
    reasoningTextColor: Color,
    scrollState: ScrollState,
    sheetState: SheetState,
    manualExpansionEnabled: Boolean,
    expandOnInitialOverflow: Boolean,
    onContentOverflow: (Boolean) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val density = LocalDensity.current
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    val tallSheetContentHeight = with(density) {
        (windowHeightPx.toDp() * REASONING_SHEET_TALL_HEIGHT_FRACTION -
            ReasoningSheetDragHandleSpace).coerceAtLeast(0.dp)
    }
    val contentTopPaddingPx = with(density) { 16.dp.roundToPx() }
    val overflowTolerancePx = with(density) { 1.dp.roundToPx() }
    var processContentHeightPx by remember { mutableIntStateOf(0) }
    var visibleProcessViewportHeightPx by remember { mutableIntStateOf(0) }
    var initialOverflowEvaluated by remember { mutableStateOf(false) }
    val sheetText = reasoningSheetText(
        displayedReasoningText = displayedReasoningText,
        isReasoningActive = isReasoningActive,
        messageIsError = messageIsError,
    )
    val sheetGesturesEnabled =
        sheetState.currentValue != SheetValue.Expanded || scrollState.value == 0
    LaunchedEffect(
        sheetState.currentValue,
        processContentHeightPx,
        visibleProcessViewportHeightPx,
        manualExpansionEnabled,
        sheetState.isAnimationRunning,
    ) {
        val contentMeasurementReady = sheetText.isBlank() || processContentHeightPx > 0
        if (
            !sheetState.isAnimationRunning &&
            sheetState.currentValue == SheetValue.PartiallyExpanded &&
            visibleProcessViewportHeightPx > 0 &&
            contentMeasurementReady
        ) {
            val contentOverflows = processContentHeightPx + contentTopPaddingPx >
                visibleProcessViewportHeightPx + overflowTolerancePx
            if (!initialOverflowEvaluated) {
                initialOverflowEvaluated = true
                if (contentOverflows) onContentOverflow(expandOnInitialOverflow)
            } else if (!manualExpansionEnabled && contentOverflows) {
                onContentOverflow(false)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.testTag("reasoning-sheet-surface"),
        sheetState = sheetState,
        sheetGesturesEnabled = sheetGesturesEnabled,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                modifier = Modifier.testTag(
                    if (sheetGesturesEnabled) {
                        "reasoning-sheet-drag-handle-enabled"
                    } else {
                        "reasoning-sheet-drag-handle-disabled"
                    },
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(tallSheetContentHeight)
                .testTag("reasoning-sheet-content"),
        ) {
            Text(
                text = "执行过程",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reasoning-sheet-header")
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
            if (isReasoningActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "执行过程加载中" }
                        .testTag("reasoning-sheet-activity-status")
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScanningHighlightText(
                        text = activityStatusText ?: "正在执行",
                        textColor = reasoningTextColor,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .onGloballyPositioned { coordinates ->
                            val bounds = coordinates.boundsInWindow()
                            val visibleTop = bounds.top.coerceAtLeast(0f)
                            val visibleBottom = bounds.bottom.coerceAtMost(windowHeightPx.toFloat())
                            visibleProcessViewportHeightPx =
                                (visibleBottom - visibleTop).coerceAtLeast(0f).toInt()
                        }
                        .testTag("reasoning-sheet-scroll")
                        .padding(start = 20.dp, top = 16.dp, end = 20.dp),
                ) {
                    if (sheetText.isNotBlank()) {
                        Text(
                            text = sheetText,
                            color = reasoningTextColor,
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                            modifier = Modifier.onSizeChanged { processContentHeightPx = it.height },
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.88f))
                        .testTag("reasoning-sheet-bottom-indicator"),
                )
            }
        }
    }
}
