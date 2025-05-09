package com.example.app1.ui.screens.BubbleMain.Main // 确保这是你正确的包名

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.example.app1.ui.screens.BubbleMain.ThreeDotsLoadingAnimation // 假设这是你 Animations 的正确路径
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

private const val REASONING_STREAM_SCROLL_ATTEMPT_INTERVAL_MS = 100L
private const val REASONING_STREAM_SCROLL_ANIMATION_MS = 250
private const val REASONING_COMPLETE_SCROLL_ANIMATION_MS = 300

/**
 * Composable 用于显示推理过程的切换按钮和内容。
 * 这个 Composable 整体会被其父 Composable (BubbleMain.kt 中的 Column) 对齐。
 */
@Composable
internal fun ReasoningToggleAndContent(
    currentMessageId: String,
    displayedReasoningText: String,
    isReasoningStreaming: Boolean,
    isReasoningComplete: Boolean,
    isManuallyExpanded: Boolean,
    messageContentStarted: Boolean,
    messageIsError: Boolean,
    fullReasoningTextProvider: () -> String?,
    onToggleReasoning: () -> Unit,
    reasoningBubbleColor: Color,
    reasoningTextColor: Color,
    reasoningToggleDotColor: Color,
    modifier: Modifier = Modifier // 这个 modifier 会应用到根 Column
) {
    val focusManager = LocalFocusManager.current
    val showThreeDotAnimationOnButton =
        isReasoningStreaming && !isReasoningComplete && !messageIsError

    // 使用一个 Column 来包裹按钮和内容，以便它们可以相对于彼此正确布局。
    // 从外部传入的 modifier 会应用到这个 Column 上。
    // 这个 Column 的对齐由 BubbleMain.kt 中的父布局决定。
    Column(modifier = modifier) {
        // 推理过程切换按钮
        Box(
            // 这个 Box 是 Column 的直接子元素，所以可以在 ColumnScope 内对齐
            modifier = Modifier
                .align(Alignment.Start) // 按钮本身总是左对齐（相对于这个内部 Column）
                .padding(start = 8.dp, bottom = 6.dp, top = 2.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(38.dp)
                    .width(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {
                            onToggleReasoning()
                            focusManager.clearFocus()
                        }
                    )
            ) {
                if (showThreeDotAnimationOnButton) {
                    ThreeDotsLoadingAnimation(
                        dotColor = reasoningToggleDotColor,
                        dotSize = 6.dp,
                        spacing = 6.dp,
                        bounceHeight = 4.dp,
                        scaleAmount = 1.1f,
                        animationDuration = 350,
                        modifier = Modifier.offset(y = 0.dp)
                    )
                } else {
                    val circleIconSize by animateDpAsState(
                        targetValue = if (isManuallyExpanded) 12.dp else 8.dp,
                        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
                        label = "reasoningToggleIconSize_${currentMessageId}"
                    )
                    Box(
                        modifier = Modifier
                            .size(circleIconSize)
                            .background(reasoningToggleDotColor, CircleShape)
                    )
                }
            }
        }

        // 推理内容区域
        AnimatedVisibility(
            visible = (isManuallyExpanded && displayedReasoningText.isNotBlank()) ||
                    (isReasoningStreaming && messageContentStarted && !messageIsError && displayedReasoningText.isNotBlank()),
            // AnimatedVisibility 是 Column 的直接子元素，可以在 ColumnScope 内对齐
            modifier = Modifier
                .align(Alignment.Start) // 推理内容本身也总是左对齐（相对于这个内部 Column）
                .padding(start = 8.dp, bottom = 8.dp),
            enter = fadeIn(tween(250)) + expandVertically(tween(250), expandFrom = Alignment.Top),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(180))
        ) {
            // AnimatedVisibility 的内容（Surface）会填充 AnimatedVisibility 的区域。
            // Surface 的对齐是相对于 AnimatedVisibility 的，而 AnimatedVisibility 已经对齐了。
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = reasoningBubbleColor,
                tonalElevation = 1.dp,
                shadowElevation = 0.dp,
                modifier = Modifier // 这个 Modifier 是 Surface 自身的修饰
                    .heightIn(max = 200.dp)
                    .wrapContentWidth(align = Alignment.Start) // 控制 Surface 内容的包裹和对齐方式
                // .align(Alignment.Start) // 这个 align 是多余的，因为父 AnimatedVisibility 已经对齐了
            ) {
                val scrollState = rememberScrollState()
                val coroutineScope = rememberCoroutineScope()
                var autoScrollJob by remember(currentMessageId) { mutableStateOf<Job?>(null) }
                var userHasScrolledUp by remember(currentMessageId) { mutableStateOf(false) }

                LaunchedEffect(
                    currentMessageId,
                    isReasoningStreaming,
                    userHasScrolledUp,
                    messageContentStarted,
                    messageIsError
                ) {
                    if (isReasoningStreaming && messageContentStarted && !userHasScrolledUp && !messageIsError) {
                        autoScrollJob?.cancel()
                        autoScrollJob = coroutineScope.launch {
                            try {
                                while (isActive) {
                                    delay(REASONING_STREAM_SCROLL_ATTEMPT_INTERVAL_MS)
                                    if (!isActive) break
                                    val currentMaxValue = scrollState.maxValue
                                    if (scrollState.value < currentMaxValue) {
                                        scrollState.animateScrollTo(
                                            currentMaxValue,
                                            animationSpec = tween(
                                                durationMillis = REASONING_STREAM_SCROLL_ANIMATION_MS,
                                                easing = LinearEasing
                                            )
                                        )
                                    }
                                }
                            } catch (e: CancellationException) { /* Expected */
                            }
                        }
                    } else {
                        autoScrollJob?.cancel()
                    }
                }

                LaunchedEffect(
                    currentMessageId,
                    isReasoningComplete,
                    displayedReasoningText,
                    messageIsError
                ) {
                    val fullText = fullReasoningTextProvider()?.trim() ?: ""
                    if (isReasoningComplete && !messageIsError && displayedReasoningText == fullText && fullText.isNotEmpty()) {
                        autoScrollJob?.cancel()
                        delay(100)
                        if (isActive && scrollState.value < scrollState.maxValue) {
                            scrollState.animateScrollTo(
                                scrollState.maxValue,
                                tween(
                                    REASONING_COMPLETE_SCROLL_ANIMATION_MS,
                                    easing = FastOutSlowInEasing
                                )
                            )
                        }
                    }
                }

                val nestedScrollConnection = remember {
                    object : NestedScrollConnection {
                        override fun onPreScroll(
                            available: Offset,
                            source: NestedScrollSource
                        ): Offset {
                            if (source == NestedScrollSource.Drag && available.y > 0 && scrollState.canScrollForward) {
                                if (!userHasScrolledUp) userHasScrolledUp = true
                            }
                            return Offset.Zero
                        }

                        override fun onPostScroll(
                            consumed: Offset,
                            available: Offset,
                            source: NestedScrollSource
                        ): Offset {
                            if (source == NestedScrollSource.Drag && available.y < 0 && (scrollState.value >= scrollState.maxValue - 1)) {
                                if (userHasScrolledUp) userHasScrolledUp = false
                            }
                            return Offset.Zero
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .nestedScroll(nestedScrollConnection)
                        .verticalScroll(scrollState)
                        .padding(horizontal = 15.dp, vertical = 10.dp)
                ) {
                    if (displayedReasoningText.isNotBlank()) {
                        Text(
                            text = displayedReasoningText,
                            color = reasoningTextColor,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}