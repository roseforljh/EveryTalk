package com.example.everytalk.ui.screens.BubbleMain.Main // 您的实际包名，确保一致

import android.util.Log
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG_REASONING = "推理界面"

@Composable
internal fun ReasoningToggleAndContent(
    currentMessageId: String,
    displayedReasoningText: String,
    isReasoningStreaming: Boolean,
    isReasoningComplete: Boolean,
    messageIsError: Boolean,
    mainContentHasStarted: Boolean,    // 这个参数由 MessageBubble 传递 message.contentStarted
    reasoningTextColor: Color,
    reasoningToggleDotColor: Color,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    var showReasoningDialog by remember(currentMessageId) { mutableStateOf(false) }

    // “流式思考框”的显隐逻辑 (保持不变：思考时显示，主内容开始时消失)
    val showInlineStreamingBox = isReasoningStreaming && !messageIsError && !mainContentHasStarted

    // --- “小黑点”上三点动画的显隐逻辑 ---
    //   条件：1. 思考过程正在流式传输
    //         2. 思考过程尚未标记为完成
    //         3. 消息没有错误
    //         4. 并且主要回复内容还没有开始 (这是新增的关键条件)
    val showDotsAnimationOnToggle = isReasoningStreaming &&
            !isReasoningComplete &&
            !messageIsError &&
            !mainContentHasStarted // << 新增：主要内容开始后，停止“小黑点”上的动画

    val boxBackgroundColor = Color.White.copy(alpha = 0.95f)
    val scrimColor = boxBackgroundColor
    val scrimHeight = 28.dp
    LaunchedEffect(currentMessageId, displayedReasoningText, isReasoningStreaming) {
        Log.d("REASON_DBG", "ShowBox=$showInlineStreamingBox, streaming=$isReasoningStreaming, contentStarted=$mainContentHasStarted, text='${displayedReasoningText.take(40)}'")
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start
    ) {
        // 1. --- 流式“思考框” --- (逻辑不变)
        AnimatedVisibility(
            visible = showInlineStreamingBox,
            // ... (enter/exit 动画不变)
            enter = fadeIn(tween(150)) + expandVertically(
                animationSpec = tween(250), expandFrom = Alignment.Top
            ),
            exit = fadeOut(tween(150)) + shrinkVertically(
                animationSpec = tween(100), shrinkTowards = Alignment.Top
            )
        ) {
            Surface( /* ... Surface 样式和内部的 Box、滚动、渐变逻辑保持不变 ... */
                shape = RoundedCornerShape(16.dp),
                color = boxBackgroundColor,
                shadowElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 6.dp, top = 4.dp)
                    .heightIn(min = 50.dp, max = 180.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    val scrollState = rememberScrollState()
                    LaunchedEffect(displayedReasoningText, isReasoningStreaming) {
                        // ... 自动滚动逻辑不变 ...
                        if (isReasoningStreaming && isActive) {
                            while (isActive && isReasoningStreaming) {
                                try {
                                    scrollState.animateScrollTo(scrollState.maxValue)
                                } catch (e: Exception) {
                                    Log.w(
                                        TAG_REASONING,
                                        "[$currentMessageId] 自动滚动失败: ${e.message}"
                                    )
                                    break
                                }
                                if (!isReasoningStreaming) break
                                delay(100L)
                            }
                            if (isActive && !isReasoningStreaming && displayedReasoningText.length > (scrollState.value / 15).coerceAtLeast(
                                    10
                                )
                            ) {
                                try {
                                    scrollState.animateScrollTo(scrollState.maxValue)
                                } catch (e: Exception) {
                                    Log.w(
                                        TAG_REASONING,
                                        "[$currentMessageId] 流结束后最终滚动失败: ${e.message}"
                                    )
                                }
                            }
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 12.dp, vertical = scrimHeight)
                    ) {
                        Text(
                            text = displayedReasoningText.ifBlank { if (isReasoningStreaming) " " else "" },
                            color = reasoningTextColor,
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                        )
                    }
                    Box( // Top Scrim
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
                    Box( // Bottom Scrim
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
        } // 流式“思考框”的 AnimatedVisibility 结束

        // --- 2. “小黑点” Toggle ---
        val shouldShowReviewDotToggle = displayedReasoningText.isNotBlank() && !messageIsError
        if (shouldShowReviewDotToggle) {
            Box(
                modifier = Modifier.padding(
                    start = 8.dp,
                    top = if (showInlineStreamingBox) 2.dp else 0.dp,
                    bottom = 2.dp
                )
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(38.dp)
                        .width(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = if (showInlineStreamingBox) 0.7f else 1.0f))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }) {
                            focusManager.clearFocus()
                            showReasoningDialog = true
                        }
                ) {
                    // --- 修改“小黑点”上的动画显示条件 ---
                    if (showDotsAnimationOnToggle && !showReasoningDialog) { // << 使用新的 showDotsAnimationOnToggle 条件
                        ThreeDotsWaveAnimation(
                            dotColor = reasoningToggleDotColor, dotSize = 7.dp, spacing = 5.dp
                        )
                    } else { // 显示静态点
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
        } // “小黑点” Toggle 结束
    } // 根Column结束

    // --- 3. 推理内容的 Dialog ---
    if (showReasoningDialog) {
        Dialog(
            onDismissRequest = { showReasoningDialog = false },
            properties = DialogProperties(
                dismissOnClickOutside = true,
                dismissOnBackPress = true,
                usePlatformDefaultWidth = false
            )
        ) {
            Card( /* ... Card样式不变 ... */
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(vertical = 32.dp)
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.8f),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
                    Text(
                        text = "Thinking Process", /* ... 标题样式不变 ... */
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
                            else if (isReasoningStreaming && !isReasoningComplete && !messageIsError) "Thinking in progress..."
                            else if (messageIsError) "An error occurred during the thinking process."
                            else "No detailed thoughts available.",
                            color = reasoningTextColor,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    // 对话框底部的三点动画，也应考虑 mainContentHasStarted
                    // 如果主要内容已开始，即使思考流理论上还在（不太可能），对话框内的加载动画也应停止，因为焦点已转移。
                    val showDialogLoadingAnimation =
                        isReasoningStreaming && !isReasoningComplete && !messageIsError && !mainContentHasStarted
                    if (showDialogLoadingAnimation) { // << 使用考虑了 mainContentHasStarted 的条件
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
    } // Dialog 结束
}

// ThreeDotsWaveAnimation Composable (保持不变)
@Composable
fun ThreeDotsWaveAnimation( /* ... 代码与您之前提供的一致 ... */
                            modifier: Modifier = Modifier,
                            dotColor: Color = MaterialTheme.colorScheme.primary,
                            dotSize: Dp = 12.dp,
                            spacing: Dp = 8.dp,
                            animationDelayMillis: Int = 200,
                            animationDurationMillis: Int = 600,
                            maxOffsetY: Dp = -(dotSize / 2)
) {
    // ... (代码不变)
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
