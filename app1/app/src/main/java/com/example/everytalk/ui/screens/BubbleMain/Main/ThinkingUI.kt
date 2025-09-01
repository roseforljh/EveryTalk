package com.example.everytalk.ui.screens.BubbleMain.Main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    onVisibilityChanged: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    var showReasoningDialog by remember(currentMessageId) { mutableStateOf(false) }

    var visibilityNotified by remember(currentMessageId) { mutableStateOf(false) }
    // 修改显示条件：当有推理内容且正在流式传输时显示思考框，但当AI开始输出正式内容时应该收起
    // 这样可以在<think>标签内容正在输出时实时显示，但在开始输出正式内容时自动收起变成小黑点
    val showInlineStreamingBox = isReasoningStreaming && !messageIsError && !isReasoningComplete && !mainContentHasStarted

    val showDotsAnimationOnToggle = false

    val boxBackgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    val scrimColor = boxBackgroundColor
    val scrimHeight = 28.dp

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start
    ) {
        AnimatedVisibility(
            visible = showInlineStreamingBox,
            enter = fadeIn(tween(150)) + expandVertically(
                animationSpec = tween(250), expandFrom = Alignment.Top
            ),
            exit = fadeOut(tween(150)) + shrinkVertically(
                animationSpec = tween(100), shrinkTowards = Alignment.Top
            )
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = boxBackgroundColor,
                shadowElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 6.dp, top = 4.dp)
                    .heightIn(min = 50.dp, max = 180.dp)
                    .onSizeChanged {
                        if (it.height > 0 && !visibilityNotified) {
                            view.post {
                                onVisibilityChanged()
                            }
                            visibilityNotified = true
                        }
                    }
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    val scrollState = rememberScrollState()
                    LaunchedEffect(scrollState.maxValue) {
                        if (isReasoningStreaming) {
                            scrollState.animateScrollTo(scrollState.maxValue)
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 12.dp, vertical = scrimHeight)
                    ) {
                        Text(
                            text = displayedReasoningText.ifBlank { if (isReasoningStreaming) "正在连接图像大模型..." else "" },
                            color = reasoningTextColor,
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(scrimHeight)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        scrimColor,
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(scrimHeight)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        scrimColor
                                    )
                                )
                            )
                    )
                }
            }
        }
        // 修复思考完成后的圆点显示逻辑：确保有推理内容时就显示圆点
        val shouldShowReviewDotToggle = displayedReasoningText.isNotBlank() && !messageIsError &&
            (isReasoningComplete || (!isReasoningStreaming && displayedReasoningText.isNotEmpty()))
        if (shouldShowReviewDotToggle) {
            Box(
                modifier = Modifier.padding(
                    start = 8.dp,
                    top = if (showInlineStreamingBox) 2.dp else 0.dp,
                    bottom = 0.dp
                )
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(16.dp)
                        .width(16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = if (showInlineStreamingBox) 0.7f else 1.0f))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }) {
                            focusManager.clearFocus()
                            showReasoningDialog = true
                        }
                ) {
                    if (showDotsAnimationOnToggle && !showReasoningDialog) {
                        ThreeDotsWaveAnimation(
                            dotColor = reasoningToggleDotColor, dotSize = 7.dp, spacing = 5.dp
                        )
                    } else {
                        val circleIconSize by animateDpAsState(
                            targetValue = if (showReasoningDialog) 10.dp else 7.dp,
                            animationSpec = tween(
                                durationMillis = 250,
                                easing = FastOutSlowInEasing
                            ),
                            label = "reasoningDialogToggleIconSize_${currentMessageId}"
                        )
                        Box(
                            modifier = Modifier
                                .size(circleIconSize)
                                .background(reasoningToggleDotColor, CircleShape)
                        )
                    }
                }
            }
        }
    }
    if (showReasoningDialog) {
        Dialog(
            onDismissRequest = { showReasoningDialog = false },
            properties = DialogProperties(
                dismissOnClickOutside = true,
                dismissOnBackPress = true,
                usePlatformDefaultWidth = false
            )
        ) {
            val alpha = remember { Animatable(0f) }
            val scale = remember { Animatable(0.8f) }

            LaunchedEffect(Unit) {
                launch {
                    alpha.animateTo(1f, animationSpec = tween(durationMillis = 300))
                }
                launch {
                    scale.animateTo(1f, animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing))
                }
            }

            Card(
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(vertical = 32.dp)
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.8f)
                    .graphicsLayer {
                        this.alpha = alpha.value
                        this.scaleX = scale.value
                        this.scaleY = scale.value
                    },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
                    Text(
                        text = "Thinking Process",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier
                            .padding(bottom = 16.dp)
                            .align(Alignment.CenterHorizontally)
                    )
                    HorizontalDivider(thickness = 1.dp, color = Color.Gray.copy(alpha = 0.2f))
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = if (displayedReasoningText.isNotBlank()) displayedReasoningText
                            else if (isReasoningStreaming && !isReasoningComplete && !messageIsError) "正在连接图像大模型..."
                            else if (messageIsError) "思考过程中发生错误"
                            else "暂无详细思考内容",
                            color = reasoningTextColor,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    val showDialogLoadingAnimation =
                        isReasoningStreaming && !isReasoningComplete && !messageIsError && !mainContentHasStarted
                    if (showDialogLoadingAnimation) {
                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider(thickness = 1.dp, color = Color.Gray.copy(alpha = 0.2f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ThreeDotsWaveAnimation(
                                dotColor = reasoningToggleDotColor,
                                dotSize = 10.dp,
                                spacing = 8.dp
                            )
                        }
                    }
                }
            }
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
    maxOffsetY: Dp = -(dotSize / 2)
) {
    val dots = listOf(
        remember { Animatable(0f) },
        remember { Animatable(0f) },
        remember { Animatable(0f) })
    val maxOffsetYPx = with(LocalDensity.current) { maxOffsetY.toPx() }
    dots.forEachIndexed { index, animatable ->
        LaunchedEffect(animatable) {
            delay(index * (animationDelayMillis / 2).toLong())
            launch {
                while (isActive) {
                    animatable.animateTo(
                        maxOffsetYPx,
                        tween(animationDurationMillis / 2, easing = FastOutSlowInEasing)
                    )
                    if (!isActive) break
                    animatable.animateTo(
                        0f,
                        tween(animationDurationMillis / 2, easing = FastOutSlowInEasing)
                    )
                    if (!isActive) break
                }
            }
        }
    }
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(spacing)) {
        dots.forEach { animatable ->
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .graphicsLayer { translationY = animatable.value }
                    .background(color = dotColor, shape = CircleShape)
            )
        }
    }
}
