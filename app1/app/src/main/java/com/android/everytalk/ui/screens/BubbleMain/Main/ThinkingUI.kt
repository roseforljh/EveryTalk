package com.android.everytalk.ui.screens.BubbleMain.Main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    val reasoningSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var showReasoningSheet by remember(currentMessageId) { mutableStateOf(false) }
    var visibilityNotified by remember(currentMessageId) { mutableStateOf(false) }

    val showInlineThinkingStatus = !messageIsError &&
        !mainContentHasStarted &&
        (isReasoningStreaming || displayedReasoningText.isNotBlank())
    val shouldShowReviewDotToggle = displayedReasoningText.isNotBlank() &&
        !messageIsError &&
        (isReasoningComplete || !isReasoningStreaming)
    val openReasoningSheet = {
        focusManager.clearFocus()
        showReasoningSheet = true
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
                onClick = openReasoningSheet,
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
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = openReasoningSheet,
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
            isReasoningActive = !messageIsError && !mainContentHasStarted,
            messageIsError = messageIsError,
            reasoningTextColor = reasoningTextColor,
            loadingDotColor = reasoningToggleDotColor,
            scrollState = streamingScrollState,
            sheetState = reasoningSheetState,
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
    val transition = rememberInfiniteTransition(label = "reasoningTextShimmer")
    val shimmerProgress by transition.animateFloat(
        initialValue = -0.55f,
        targetValue = 1.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_650, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "reasoningTextShimmerProgress",
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

    Row(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                contentDescription = "思考中"
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
        Text(
            text = "思考中",
            style = MaterialTheme.typography.bodyMedium.copy(brush = shimmerBrush),
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.onSizeChanged { textWidthPx = it.width.coerceAtLeast(1).toFloat() },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReasoningBottomSheet(
    displayedReasoningText: String,
    isReasoningActive: Boolean,
    messageIsError: Boolean,
    reasoningTextColor: Color,
    loadingDotColor: Color,
    scrollState: ScrollState,
    sheetState: SheetState,
    onDismissRequest: () -> Unit,
) {
    val density = LocalDensity.current
    val maxSheetHeight = with(density) {
        LocalWindowInfo.current.containerSize.height.toDp() * 0.9f
    }
    val sheetText = reasoningSheetText(
        displayedReasoningText = displayedReasoningText,
        isReasoningActive = isReasoningActive,
        messageIsError = messageIsError,
    )

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .testTag("reasoning-sheet-content"),
        ) {
            Text(
                text = "思考过程",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            if (sheetText.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(scrollState)
                        .testTag("reasoning-sheet-scroll")
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    Text(
                        text = sheetText,
                        color = reasoningTextColor,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                    )
                }
            }
            if (isReasoningActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "思考内容加载中" }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ThreeDotsWaveAnimation(
                        modifier = Modifier.testTag("reasoning-sheet-loader-dots"),
                        dotColor = loadingDotColor,
                        dotSize = 4.dp,
                        spacing = 4.dp,
                        maxOffsetY = (-2).dp,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun ThreeDotsWaveAnimation(
    modifier: Modifier = Modifier,
    dotColor: Color = MaterialTheme.colorScheme.primary,
    dotSize: Dp = 12.dp,
    spacing: Dp = 8.dp,
    animationDelayMillis: Int = 200,
    animationDurationMillis: Int = 600,
    maxOffsetY: Dp = -(dotSize / 2),
) {
    val dots = listOf(
        remember { Animatable(0f) },
        remember { Animatable(0f) },
        remember { Animatable(0f) },
    )
    val maxOffsetYPx = with(LocalDensity.current) { maxOffsetY.toPx() }
    dots.forEachIndexed { index, animatable ->
        LaunchedEffect(animatable) {
            delay(index * (animationDelayMillis / 2).toLong())
            launch {
                while (isActive) {
                    animatable.animateTo(
                        maxOffsetYPx,
                        tween(animationDurationMillis / 2, easing = FastOutSlowInEasing),
                    )
                    if (!isActive) break
                    animatable.animateTo(
                        0f,
                        tween(animationDurationMillis / 2, easing = FastOutSlowInEasing),
                    )
                    if (!isActive) break
                }
            }
        }
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        dots.forEach { animatable ->
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .graphicsLayer { translationY = animatable.value }
                    .background(color = dotColor, shape = CircleShape),
            )
        }
    }
}
