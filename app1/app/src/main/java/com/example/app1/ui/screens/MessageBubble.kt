package com.example.app1.ui.components // 确保包名与你的项目结构一致

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.app1.data.models.Message
import com.example.app1.data.models.Sender
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll

@Composable
fun MessageBubble(
    message: Message,
    isReasoningStreaming: Boolean, // 推理过程是否正在流式输出
    isReasoningComplete: Boolean,  // 推理过程是否已完成
    isManuallyExpanded: Boolean,   // 用户是否手动展开
    onToggleReasoning: () -> Unit, // 手动切换展开的回调
    modifier: Modifier = Modifier,
    showLoadingBubble: Boolean = false // 是否显示初始的"连接..."气泡
) {
    val isAI = message.sender == Sender.AI
    val hasReasoning = !message.reasoning.isNullOrBlank()
    val pageBackgroundColor = MaterialTheme.colorScheme.background
    val userBubbleColor = MaterialTheme.colorScheme.primary
    val isLoadingContent = message.text.isBlank() && !showLoadingBubble && isAI
    val actualBubbleColor = if (isAI) pageBackgroundColor else userBubbleColor
    val actualContentColor =
        if (isAI) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onPrimary
    val loadingDotColor = MaterialTheme.colorScheme.primary

    Column(
        horizontalAlignment = if (isAI) Alignment.Start else Alignment.End,
        modifier = modifier.fillMaxWidth()
    ) {
        // 连接大模型loading气泡
        if (isAI && showLoadingBubble) {
            Row(
                modifier = Modifier
                    .padding(vertical = 3.dp)
                    .wrapContentWidth()
                    .align(Alignment.Start)
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 1.dp
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "正在连接大模型",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            return
        }

        // 推理过程区域
        if (isAI && hasReasoning) {
            OutlinedButton(
                onClick = onToggleReasoning,
                enabled = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .height(40.dp)
                    .defaultMinSize(minWidth = 110.dp)
                    .padding(top = 0.dp, bottom = 4.dp)
            ) {
                val expandIcon =
                    if (isManuallyExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore
                Icon(expandIcon, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (isManuallyExpanded) "收起推理过程" else "展开推理过程",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            // 推理内容气泡
            AnimatedVisibility(
                visible = isReasoningStreaming || isManuallyExpanded,
                modifier = Modifier.padding(bottom = 8.dp),
                enter = fadeIn(tween(250)) + expandVertically(
                    tween(250),
                    expandFrom = Alignment.Top
                ),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(180))
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                    modifier = Modifier
                        .heightIn(max = 200.dp)
                        .wrapContentWidth()
                        .align(Alignment.Start)
                ) {
                    val scrollState = rememberScrollState()
                    // 自动滚动到底逻辑
                    LaunchedEffect(
                        isReasoningStreaming,
                        isReasoningComplete,
                        scrollState.maxValue
                    ) {
                        if (isReasoningComplete) {
                            delay(50)
                            if (isActive) {
                                scrollState.animateScrollTo(
                                    value = scrollState.maxValue,
                                    animationSpec = tween(durationMillis = 300)
                                )
                            }
                        } else if (isReasoningStreaming) {
                            while (isActive) {
                                scrollState.animateScrollTo(
                                    value = scrollState.maxValue,
                                    animationSpec = tween(
                                        durationMillis = 600,
                                        easing = LinearEasing
                                    )
                                )
                                delay(700L)
                            }
                        }
                    }
                    val nestedScrollConnection = remember {
                        object : NestedScrollConnection {
                            override fun onPreScroll(
                                available: Offset,
                                source: NestedScrollSource
                            ): Offset {
                                val delta = available.y
                                scrollState.dispatchRawDelta(-delta)
                                return Offset(x = 0f, y = available.y)
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
                        SelectionContainer {
                            Text(
                                text = message.reasoning.trimEnd(),
                                color = Color(0xFF444444),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }

        // 主体气泡区（外围padding和内部padding都已缩小）
        if (!showLoadingBubble) {
            Surface(
                color = actualBubbleColor,
                contentColor = actualContentColor,
                tonalElevation = if (isAI) 0.dp else 1.dp,
                shadowElevation = 0.dp,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .wrapContentWidth()
                    .align(if (isAI) Alignment.Start else Alignment.End)
            ) {
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 5.dp)  // <-- 这里较小
                        .wrapContentWidth()
                        .defaultMinSize(minHeight = 32.dp)             // <-- 这里较小
                ) {
                    if (isLoadingContent) {
                        ThreeDotsLoadingAnimation(
                            dotColor = loadingDotColor,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(y = (-6).dp)
                        )
                    } else {
                        SelectionContainer {
                            Text(
                                text = message.text.trimEnd(),
                                textAlign = TextAlign.Start,
                                modifier = Modifier // padding已去除
                            )
                        }
                    }
                }
            }
        }
    }
}


// Composable for the three dots loading animation
@Composable
private fun ThreeDotsLoadingAnimation(
    modifier: Modifier = Modifier,
    dotSize: Dp = 10.dp,
    dotColor: Color = MaterialTheme.colorScheme.primary,
    spacing: Dp = 10.dp,
    bounceHeight: Dp = 10.dp,
    scaleAmount: Float = 1.25f,
    animationDuration: Int = 450
) {
    val infiniteTransition = rememberInfiniteTransition(label = "dots_loader")

    @Composable
    fun animateDot(delayMillis: Int): Pair<Float, Float> {
        val key = remember { "dot_anim_$delayMillis" }
        val yOffset by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = animationDuration * 3
                    0f at (animationDuration * 0) + delayMillis
                    -bounceHeight.value at (animationDuration * 1) + delayMillis
                    0f at (animationDuration * 2) + delayMillis
                    0f at (animationDuration * 3) + delayMillis
                }, repeatMode = RepeatMode.Restart
            ),
            label = key
        )
        val scale by infiniteTransition.animateFloat(
            initialValue = 1f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = animationDuration * 3
                    1f at (animationDuration * 0) + delayMillis
                    scaleAmount at (animationDuration * 1) + delayMillis
                    1f at (animationDuration * 2) + delayMillis
                    1f at (animationDuration * 3) + delayMillis
                }, repeatMode = RepeatMode.Restart
            ),
            label = key
        )
        return Pair(yOffset, scale)
    }

    val dotsAnim = listOf(
        animateDot(0),
        animateDot(animationDuration / 2),
        animateDot(animationDuration)
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        dotsAnim.forEachIndexed { index, (yOffset, scale) ->
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        translationY = yOffset
                        scaleX = scale
                        scaleY = scale
                    }
                    .size(dotSize)
                    .background(dotColor.copy(alpha = 1f), shape = CircleShape)
            )
            if (index < dotsAnim.lastIndex) {
                Spacer(Modifier.width(spacing))
            }
        }
    }
}
