package com.example.app1.ui.screens.BubbleMain.Main // 你的实际包名

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG_REASONING = "推理界面"

@Composable
internal fun ReasoningToggleAndContent(
    currentMessageId: String,
    displayedReasoningText: String,
    isReasoningStreaming: Boolean,
    isReasoningComplete: Boolean,
    messageContentStarted: Boolean,
    messageIsError: Boolean,
    reasoningTextColor: Color,
    reasoningToggleDotColor: Color,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val showLoadingAnimationGlobal =
        isReasoningStreaming && !isReasoningComplete && !messageIsError // 全局加载状态

    var showReasoningDialog by remember(currentMessageId) { mutableStateOf(false) }

    val onDismissDialogRequest = {
        Log.d(TAG_REASONING, "[$currentMessageId] Dialog dismiss requested.")
        showReasoningDialog = false
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start
    ) {
        // 1. 主界面临时显示的思考过程 (仅在流式输出期间)
        AnimatedVisibility(
            visible = isReasoningStreaming && !isReasoningComplete && messageContentStarted && !messageIsError,
            enter = fadeIn(tween(150)) + expandVertically(
                animationSpec = tween(250),
                expandFrom = Alignment.Top
            ),
            exit = fadeOut(tween(150)) + shrinkVertically(
                animationSpec = tween(250),
                shrinkTowards = Alignment.Top
            )
        ) {
            Surface(
                shape = RoundedCornerShape(
                    bottomStart = 12.dp,
                    bottomEnd = 12.dp,
                    topStart = 4.dp,
                    topEnd = 4.dp
                ),
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 6.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 15.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(
                                style = SpanStyle(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = MaterialTheme.typography.bodyMedium.fontSize * 1.1
                                )
                            ) {
                                append("")
                            }
                            // 如果 displayedReasoningText 为空且正在加载，之前会显示 "..." 或动画
                            // 现在如果为空，则什么都不追加，或者可以追加一个静态的 "Loading..." 文本
                            append(displayedReasoningText.ifBlank {
                                if (showLoadingAnimationGlobal) " " // 可以留空或加少量占位符，如单个空格，避免Thinking:后面直接是空的
                                else ""
                            })
                        },
                        color = reasoningTextColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // 2. 切换按钮（小黑点），用于打开Dialog
        val shouldShowToggleButton =
            displayedReasoningText.isNotBlank() || (isReasoningStreaming && messageContentStarted && !messageIsError)
        if (shouldShowToggleButton) {
            Box(
                modifier = Modifier.padding(start = 8.dp, top = 0.dp, bottom = 2.dp)
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
                                focusManager.clearFocus()
                                showReasoningDialog = true
                            }
                        )
                ) {
                    // 按钮上的加载动画逻辑不变
                    if (showLoadingAnimationGlobal && !showReasoningDialog) {
                        ThreeDotsWaveAnimation(
                            dotColor = reasoningToggleDotColor,
                            dotSize = 7.dp,
                            spacing = 5.dp
                        )
                    } else {
                        val circleIconSize by animateDpAsState(
                            targetValue = if (showReasoningDialog) 12.dp else 8.dp,
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

    // 3. 推理内容的 Dialog (逻辑不变)
    if (showReasoningDialog) {
        Dialog(
            onDismissRequest = { onDismissDialogRequest() },
            properties = DialogProperties(
                dismissOnClickOutside = true,
                dismissOnBackPress = true,
                usePlatformDefaultWidth = false
            )
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(vertical = 32.dp)
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.8f),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
                ) {
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
                            else if (showLoadingAnimationGlobal) "" // Dialog内也不显示文本加载提示，让动画处理
                            else if (messageIsError) "An error occurred during the thinking process."
                            else "No detailed thoughts available.",
                            color = reasoningTextColor,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    if (showLoadingAnimationGlobal) { // Dialog 内的加载动画逻辑不变
                        Spacer(Modifier.height(16.dp))
                        if (displayedReasoningText.isNotBlank() || !showLoadingAnimationGlobal /* ensure divider logic is sound */) { // Avoid double divider if text is blank and loading
                            // Only show divider if there was text OR if not loading (i.e. error/no content state after loading)
                        }
                        if (displayedReasoningText.isNotBlank() && showLoadingAnimationGlobal) { // Divider before loading animation if there was text
                            HorizontalDivider(
                                thickness = 1.dp,
                                color = Color.Gray.copy(alpha = 0.2f)
                            )
                        } else if (!displayedReasoningText.isNotBlank() && !showLoadingAnimationGlobal && !messageIsError) {
                            // No divider if no text, not loading, and not an error (e.g. "No detailed thoughts")
                        } else if (messageIsError || (!displayedReasoningText.isNotBlank() && showLoadingAnimationGlobal)) {
                            // Divider for error or if loading and text is blank
                            HorizontalDivider(
                                thickness = 1.dp,
                                color = Color.Gray.copy(alpha = 0.2f)
                            )
                        }


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

// ThreeDotsWaveAnimation 和 HorizontalDivider 实现保持不变
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
        remember { Animatable(0f) }
    )
    val maxOffsetYPx = with(LocalDensity.current) { maxOffsetY.toPx() }

    dots.forEachIndexed { index, animatable ->
        LaunchedEffect(animatable) {
            delay(index * (animationDelayMillis / 2).toLong())
            launch {
                while (true) {
                    animatable.animateTo(
                        targetValue = maxOffsetYPx,
                        animationSpec = tween(
                            durationMillis = animationDurationMillis / 2,
                            easing = FastOutSlowInEasing
                        )
                    )
                    animatable.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(
                            durationMillis = animationDurationMillis / 2,
                            easing = FastOutSlowInEasing
                        )
                    )
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
