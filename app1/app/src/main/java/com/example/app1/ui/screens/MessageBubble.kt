package com.example.app1.ui.screens // 替换为你的实际包名

// --- 必要的 Import 语句 ---
import android.annotation.SuppressLint
import androidx.compose.animation.*
import androidx.compose.animation.core.* // 导入更多动画相关的类，包括 Easing, tween
// 导入常用的 Easing 函数
import androidx.compose.animation.core.FastOutSlowInEasing // <-- 导入 FastOutSlowInEasing
// 注意：EaseIn 和 EaseOut 在 androidx.compose.animation.core.* 里已经包含了
// 如果你明确需要它们的名字，也可以单独导入：
// import androidx.compose.animation.core.EaseIn
// import androidx.compose.animation.core.EaseOut


import androidx.compose.foundation.ScrollState // 导入 ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border // ChatScreen 需要
import androidx.compose.foundation.clickable // <-- Import clickable
import androidx.compose.foundation.interaction.MutableInteractionSource // ChatScreen 需要
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn // ChatScreen 需要
import androidx.compose.foundation.lazy.items // ChatScreen 需要
import androidx.compose.foundation.lazy.rememberLazyListState // ChatScreen 需要
import androidx.compose.foundation.rememberScrollState // 导入 rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer // Import for text selection
import androidx.compose.foundation.verticalScroll // 导入 verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send // ChatScreen 需要
import androidx.compose.material.icons.filled.* // 导入所有 filled 图标
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.app1.data.models.Message // 确保路径正确
import com.example.app1.data.models.Sender   // 确保路径正确
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch // 导入 launch


// --- 辅助函数 ---

private fun removeLeadingBlankLines(text: String): String {
    return text.replaceFirst(Regex("^[\\s\\uFEFF\\xA0]+"), "")
}

// This checks if the message is AI AND has non-blank reasoning
private fun hasReasoning(message: Message): Boolean {
    return message.sender == Sender.AI && !message.reasoning.isNullOrBlank()
}

@Composable
private fun noFontPaddingStyle(originalStyle: TextStyle): TextStyle {
    return originalStyle.copy(
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )
}

// REMOVED TypewriterText Composable


// =============== 消息气泡组件 (传递 onCancelLoading 回调) ====================
@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
fun MessageBubble(
    message: Message,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    currentStreamingAiMessageId: String?, // <-- ChatScreen 传进来 (哪个AI消息正在流式输出 - 来自 StateFlow)
    isApiCurrentlyCalling: Boolean, // <-- ChatScreen 传进来 (API是否正在调用)
    onCancelLoading: (() -> Unit)? = null // <-- ChatScreen 传进来 (点击加载点时触发取消)
) {
    // Conditions
    val isAiMessage = message.sender == Sender.AI // Simple check for AI sender
    val isAiMessageWithoutError = isAiMessage && !message.isError

    // 条件：这条消息是否是当前正在流式输出的 AI 消息
    val isStreamingThisMessage = isAiMessageWithoutError && message.id == currentStreamingAiMessageId

    // Does the message have reasoning text available?
    val messageHasReasoning = hasReasoning(message) // Use the helper function


    // 条件：这条消息是否应该显示加载点动画 (PulsingLoadingDots) 在主要文本区域
    // (是当前正在流式的 AI 消息，API正在调用，该消息 *没有* 推理文本，并且其主要内容 *尚未开始* )
    val isShowingLoadingDotsInMainText = isStreamingThisMessage &&
            isApiCurrentlyCalling &&
            !messageHasReasoning && // No reasoning text available yet
            !message.contentStarted // ** <-- Primary content has NOT started yet**


    // Cancellation is now handled ONLY by the loading dots area and the input bar button.


    val alignment =
        if (message.sender == Sender.User) Alignment.CenterEnd else Alignment.CenterStart

    // --- Fixed When Expressions ---
    val bubbleBackgroundColor = when {
        message.isError -> MaterialTheme.colorScheme.errorContainer
        message.sender == Sender.User -> MaterialTheme.colorScheme.primaryContainer // <-- 已修改
        else -> MaterialTheme.colorScheme.surfaceVariant // Handles Sender.AI
    }
    val contentColor = when {
        message.isError -> MaterialTheme.colorScheme.onErrorContainer
        message.sender == Sender.User -> MaterialTheme.colorScheme.onPrimaryContainer // <-- 已修改
        else -> MaterialTheme.colorScheme.onSurfaceVariant // Handles Sender.AI
    }
    // --- End Fixed When Expressions ---


    val bubbleShape = if (message.sender == Sender.User) RoundedCornerShape(
        topStart = 20.dp, topEnd = 2.dp, bottomStart = 20.dp, bottomEnd = 20.dp
    ) else RoundedCornerShape(
        topStart = 2.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = alignment
    ) {
        Surface(
            modifier = Modifier.wrapContentWidth(),
            shape = bubbleShape,
            color = bubbleBackgroundColor,
            // Apply shadow only if it's an AI message, not an error, AND not currently showing loading dots in main text.
            // Also exclude shadow for messages with reasoning (since they use reasoning as indicator)
            tonalElevation = if (isAiMessageWithoutError && !isShowingLoadingDotsInMainText && !messageHasReasoning) 1.dp else 0.dp
        ) {
            Column(
                modifier = Modifier
                    .animateContentSize(animationSpec = spring(stiffness = 300f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                // **关键修正：确保此 Column 没有 clickable**
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // --- 思考过程 (Reasoning) 部分 ---
                // Show reasoning if message has it AND it's not blank
                if (messageHasReasoning) { // Use the helper function here
                    Row( // 标题行
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            // **关键修正：此 clickable 只用于切换展开/折叠，无需 enabled 条件（已在 if 块内）**
                            .clickable { onToggleExpand() }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isExpanded) "思考过程(点击折叠)" else "思考过程(点击展开)",
                            style = noFontPaddingStyle(
                                MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            ),
                            color = contentColor.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .padding(end = 8.dp)
                        )
                        Icon(
                            imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (isExpanded) "折叠" else "展开",
                            tint = contentColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    AnimatedVisibility(visible = isExpanded) {
                        val reasoningScrollState = rememberScrollState()
                        // **定义平滑滚动的动画规格**
                        val smoothScrollSpec: AnimationSpec<Float> = remember {
                            tween(durationMillis = 500, easing = FastOutSlowInEasing)
                            // 或者尝试: tween(durationMillis = 700, easing = LinearOutSlowInEasing)
                            // 或者: spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)
                        }

                        Column {
                            HorizontalDivider(
                                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                                thickness = 0.5.dp,
                                color = contentColor.copy(alpha = 0.3f)
                            )
                            Box( // 思考过程 Box (有限高，内部滚动)
                                modifier = Modifier
                                    .heightIn(max = 500.dp)
                                    .verticalScroll(reasoningScrollState)
                            ) {
                                // Reasoning Text - Display statically now
                                SelectionContainer { // Keep SelectionContainer for copy/paste
                                    Text(
                                        text = removeLeadingBlankLines(message.reasoning!!), // reasoning is guaranteed non-blank here
                                        style = noFontPaddingStyle(LocalTextStyle.current.copy(fontSize = 14.sp)),
                                        color = contentColor.copy(alpha = 0.9f),
                                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            // --- Timed Reasoning Auto-Scroll Effect (with smooth animation) ---
                            val scrollIntervalMillis = 2400L // 2.4 seconds
                            LaunchedEffect(isExpanded, isStreamingThisMessage, reasoningScrollState) {
                                // Only run the timer if the reasoning area is expanded and streaming is active for this message
                                // and the message is not in an error state (errors shouldn't auto-scroll)
                                if (isExpanded && isStreamingThisMessage && !message.isError) {
                                    println("MessageBubble(${message.id}): Starting timed reasoning scroll.")
                                    while (isStreamingThisMessage && isExpanded && !message.isError) { // Continue as long as expanded, streaming, and not error
                                        // Check if there's content that overflows
                                        if (reasoningScrollState.maxValue > 0) {
                                            // **核心修正：使用自定义的动画规格**
                                            reasoningScrollState.animateScrollTo(
                                                value = reasoningScrollState.maxValue,
                                                animationSpec = smoothScrollSpec // Apply the smooth spec
                                            )
                                            println("MessageBubble(${message.id}): Timed scroll executed.")
                                        } else {
                                            println("MessageBubble(${message.id}): Timed scroll checked, no overflow.")
                                        }
                                        delay(scrollIntervalMillis) // Wait for the interval
                                    }
                                    println("MessageBubble(${message.id}): Timed reasoning scroll stopped.")
                                } else {
                                    println("MessageBubble(${message.id}): Timed reasoning scroll not started or stopped (isExpanded=$isExpanded, isStreamingThisMessage=$isStreamingThisMessage, isError=${message.isError}).")
                                }
                            }
                            // --- End Timed Reasoning Auto-Scroll Effect ---
                        }
                    }
                } // Reasoning section end

                // --- 主要消息文本内容区域 ---
                Box(
                    modifier = Modifier.align(Alignment.Start),
                    contentAlignment = Alignment.CenterStart
                ) {
                    // Display loading dots ONLY if they are meant to be shown in the main text area
                    if (isShowingLoadingDotsInMainText) {
                        // **核心修正：取消点击逻辑只放在这个 Box 上**
                        Box(
                            modifier = Modifier
                                .padding(vertical = 8.dp) // <-- Maintain padding
                                // Clickable ONLY when showing loading dots AND cancel callback is provided
                                .clickable(enabled = isShowingLoadingDotsInMainText && onCancelLoading != null) {
                                    onCancelLoading?.invoke() // Trigger cancel via callback
                                },
                            contentAlignment = Alignment.Center // Center the dots within the clickable box
                        ) {
                            PulsingLoadingDots(
                                dotColor = contentColor
                            )
                        }
                        // **核心修正：显示文本的条件**
                        // Only show text if dots are NOT showing AND primary content has started AND text is not blank AND not an error
                    } else if (!isShowingLoadingDotsInMainText && message.contentStarted && message.text.isNotBlank() && !message.isError) { // <-- Use !isShowingLoadingDotsInMainText and message.contentStarted and isNotBlank and not error
                        Box(modifier = Modifier) { // AI Main Text Box (no size constraint here)
                            // Display main text statically
                            SelectionContainer { // Keep SelectionContainer for copy/paste
                                Text(
                                    // Message text is guaranteed non-empty if contentStarted is true and it's not an error
                                    text = removeLeadingBlankLines(message.text),
                                    style = noFontPaddingStyle(MaterialTheme.typography.bodyLarge),
                                    color = contentColor
                                )
                            }
                        }
                    } else if (message.sender == Sender.User && message.text.isNotBlank()) {
                        // Handle User messages separately, always show if not blank
                        Box(modifier = Modifier) { // User Main Text Box
                            SelectionContainer { // Keep SelectionContainer for copy/paste
                                Text(
                                    text = removeLeadingBlankLines(message.text),
                                    style = noFontPaddingStyle(MaterialTheme.typography.bodyLarge),
                                    color = contentColor
                                )
                            }
                        }
                    } else if (message.isError) {
                        // Handle Error messages separately
                        Box(modifier = Modifier) { // Error Main Text Box
                            SelectionContainer { // Keep SelectionContainer for copy/paste
                                Text(
                                    text = removeLeadingBlankLines(message.text), // Error text includes the prefix
                                    style = noFontPaddingStyle(MaterialTheme.typography.bodyLarge),
                                    color = contentColor // Error colors handled by bubble background/content color
                                )
                            }
                        }
                    }
                    // If none of the above conditions are met (e.g., AI message initial empty state),
                    // nothing is displayed in the main text area.
                } // Main Content Box end
            } // Column end
        } // Surface end
    } // Box end
}
// ==================================================================


// =============== 动画/工具 可组合项 (确保存在且正确) ====================

// 美化后的 PulsingLoadingDots
@Composable
fun PulsingLoadingDots(
    modifier: Modifier = Modifier,
    dotCount: Int = 3,
    dotColor: Color = MaterialTheme.colorScheme.primary,
    dotSize: Dp = 9.dp,
    dotSpacing: Dp = 6.dp,
    // 动画总时长 (单个点从开始到结束一个完整循环)
    animationDurationMillis: Int = 1500, // <-- Increased duration to 1.5 seconds
    // 点之间的动画开始延迟
    animationDelayMillis: Int = 300, // <-- Adjusted delay slightly for smoother wave with longer duration
    minScale: Float = 0.8f, // <-- Adjusted min scale for potentially softer pulse
    maxScale: Float = 1.0f, // <-- Adjusted max scale for potentially softer pulse
    minAlpha: Float = 0.6f, // <-- Adjusted min alpha for potentially softer pulse
    maxAlpha: Float = 1.0f, // Adjusted max alpha
    // 使用标准 Easing 函数，默认使用 FastOutSlowInEasing，通常更平滑自然
    // 重新添加 easing 参数，并应用到 keyframes 的结果
    easing: Easing = FastOutSlowInEasing // <-- Re-added easing parameter
) {
    // Ensure animatables are remembered per instance of this composable
    val animatables = remember { List(dotCount) { Animatable(0f) } } // Animating from 0f to 1f

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(dotSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        animatables.forEachIndexed { index, anim ->
            LaunchedEffect(anim) {
                // Initial delay for staggered start
                delay((index * animationDelayMillis).toLong())

                // Start infinite animation: animate from 0f to 1f, then back to 0f, repeatedly
                anim.animateTo(
                    targetValue = 1f, // Target value doesn't define the whole animation with keyframes
                    animationSpec = infiniteRepeatable(
                        animation = keyframes {
                            // Define value changes over time. Value goes 0 -> 1 -> 0.
                            // Easing is applied *after* these raw values are generated.
                            0f at 0 // Start at 0
                            1f at animationDurationMillis / 2 // Reach peak (1f) halfway
                            0f at animationDurationMillis // End at 0, completing the cycle
                            durationMillis = animationDurationMillis // Total duration for one full cycle
                        },
                        repeatMode = RepeatMode.Restart // Restart the animation from the beginning
                    )
                )
            }
            // Apply the specified easing to the animation value before using it for scale and alpha
            val easedAnimationProgress = easing.transform(anim.value) // <-- Apply easing here

            // Calculate animated scale and alpha based on the eased value
            val scale = lerp(minScale, maxScale, easedAnimationProgress) // <-- Use eased value
            val alpha = lerp(minAlpha, maxAlpha, easedAnimationProgress) // <-- Use eased value

            Box(
                modifier = Modifier
                    .size(dotSize)
                    // Apply graphicsLayer for scale and alpha
                    .graphicsLayer(scaleX = scale, scaleY = scale, alpha = alpha)
                    .background(dotColor, CircleShape)
            )
        }
    }
}

// Helper function for linear interpolation (already exists in androidx.compose.ui.util, but keeping local for clarity)
private fun lerp(start: Float, stop: Float, fraction: Float): Float {
    return (start * (1 - fraction)) + (stop * fraction)
}


// WavyLoadingDots is not currently used in MessageBubble main content, but keeping it
@Composable
fun WavyLoadingDots(
    modifier: Modifier = Modifier,
    dotColor: Color,
    dotCount: Int = 3,
    dotSpacing: Dp = 4.dp
) {
    val dotSize = 8.dp
    val peakScale = 1.2f
    val animationDuration = 400
    val delayBetweenDots = 150
    val animatables = List(dotCount) { remember { Animatable(1f) } }

    LaunchedEffect(dotColor, dotCount, dotSpacing) {
        animatables.forEach { it.stop() }
        animatables.forEach { it.snapTo(1f) }

        animatables.forEachIndexed { index, animatable ->
            launch {
                delay(index * delayBetweenDots.toLong())
                try {
                    animatable.animateTo(
                        1f,
                        infiniteRepeatable(
                            keyframes {
                                durationMillis = animationDuration * 2 // Total duration for one dot's cycle
                                1f at 0 // Start at normal size
                                peakScale at animationDuration // Reach peak halfway
                                1f at animationDuration * 2 // Return to normal size
                            },
                            RepeatMode.Restart
                        )
                    )
                } catch (e: Exception) {
                    animatable.snapTo(1f)
                }
            }
        }
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dotSpacing)
    ) {
        animatables.forEach { animatable ->
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .graphicsLayer(scaleX = animatable.value, scaleY = animatable.value)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
    }
}
// ==================================================================