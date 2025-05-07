package com.example.app1.ui.components

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.app1.data.models.Message
import com.example.app1.data.models.Sender
import com.example.app1.ui.screens.AppViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException


private const val TYPEWRITER_DELAY_MS_REASONING = 20L

// --- 推理过程定时滚动相关常量 ---
private const val REASONING_AUTO_SCROLL_INTERVAL_MS = 500L
private const val REASONING_AUTO_SCROLL_ANIMATION_MS = 400
// --- 结束 ---

@Composable
fun MessageBubble(
    message: Message,
    viewModel: AppViewModel,
    isReasoningStreaming: Boolean,
    isReasoningComplete: Boolean,
    isManuallyExpanded: Boolean,
    onToggleReasoning: () -> Unit,
    modifier: Modifier = Modifier,
    showLoadingBubble: Boolean = false
) {
    val isAI = message.sender == Sender.AI
    val currentMessageId = message.id

    val animationPlayedVM = viewModel.hasAnimationBeenPlayed(currentMessageId)
    var displayedMainTextState by remember(currentMessageId) {
        mutableStateOf(
            if (animationPlayedVM && message.text.isNotBlank()) message.text.trim()
            else if (!isAI && message.text.isNotBlank()) message.text.trim()
            else ""
        )
    }
    var localAnimationCompleted by remember(currentMessageId) { mutableStateOf(animationPlayedVM) }
    val showMainBubbleLoadingDots = isAI && !showLoadingBubble && !message.contentStarted &&
            message.text.isBlank() && !animationPlayedVM

    var displayedReasoningText by remember(currentMessageId, message.reasoning) {
        mutableStateOf(
            message.reasoning?.trim() ?: ""
        )
    }

    // --- 推理文本打印效果 ---
    LaunchedEffect(
        message.reasoning,
        isReasoningStreaming,
        isAI,
        currentMessageId,
        message.contentStarted
    ) {
        if (isAI && !message.reasoning.isNullOrBlank()) {
            val fullReasoningText = message.reasoning!!.trim()
            if (isReasoningStreaming && message.contentStarted) {
                if (fullReasoningText.length > displayedReasoningText.length) {
                    var tempReasoning = displayedReasoningText
                    for (i in displayedReasoningText.length until fullReasoningText.length) {
                        if (!isActive) break
                        tempReasoning = fullReasoningText.substring(0, i + 1)
                        displayedReasoningText = tempReasoning
                        delay(TYPEWRITER_DELAY_MS_REASONING)
                    }
                    if (displayedReasoningText != tempReasoning && isActive) displayedReasoningText =
                        tempReasoning
                } else if (displayedReasoningText != fullReasoningText) {
                    displayedReasoningText = fullReasoningText
                }
            } else if (!isReasoningStreaming) {
                if (displayedReasoningText != fullReasoningText) displayedReasoningText =
                    fullReasoningText
            }
        } else if (message.reasoning.isNullOrBlank() && displayedReasoningText.isNotEmpty()) {
            displayedReasoningText = ""
        }
    }

    // --- 主文本简化打印效果 ---
    LaunchedEffect(
        message.text,
        currentMessageId,
        isAI,
        showLoadingBubble,
        message.contentStarted
    ) {
        val fullMainText = message.text.trim()
        val animationShouldPlay =
            isAI && !showLoadingBubble && fullMainText.isNotBlank() && message.contentStarted
        val vmCompleted = viewModel.hasAnimationBeenPlayed(currentMessageId)

        if (animationShouldPlay) {
            if (!vmCompleted && !localAnimationCompleted) {
                displayedMainTextState = fullMainText
                localAnimationCompleted = true
                viewModel.onAnimationComplete(currentMessageId)
            } else {
                if (displayedMainTextState != fullMainText) {
                    displayedMainTextState = fullMainText
                }
                if (!vmCompleted && localAnimationCompleted) {
                    viewModel.onAnimationComplete(currentMessageId)
                }
            }
        } else if (!isAI && fullMainText.isNotBlank()) {
            if (displayedMainTextState != fullMainText) displayedMainTextState = fullMainText
            if (!vmCompleted && !localAnimationCompleted) {
                localAnimationCompleted = true; viewModel.onAnimationComplete(currentMessageId); }
        } else if (fullMainText.isBlank()) {
            if (displayedMainTextState.isNotEmpty()) displayedMainTextState = ""
            if (isAI && !vmCompleted && !localAnimationCompleted && message.contentStarted) {
                localAnimationCompleted = true; viewModel.onAnimationComplete(currentMessageId); }
        }
    }


    val userBubbleGreyColor = Color(red = 200, green = 200, blue = 200, alpha = 128)

    // --- UI 结构 ---
    Column(
        horizontalAlignment = if (isAI) Alignment.Start else Alignment.End,
        modifier = modifier.fillMaxWidth()
    ) {
        // 加载气泡
        if (isAI && showLoadingBubble) {
            Row(
                modifier = Modifier
                    .padding(vertical = 3.dp)
                    .wrapContentWidth()
                    .align(Alignment.Start)
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White, // MODIFIED: Loading bubble background to White
                    shadowElevation = 4.dp,
                    contentColor = Color.Black // Ensure content (text) is black
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),//思考加载圈！
                            color = Color.Black,
                            strokeWidth = 1.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "正在连接大模型",
                            // color = Color.Black, // Will inherit from Surface's contentColor
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            return@Column
        }

        // 推理过程
        val hasReasoning = !message.reasoning.isNullOrBlank()
        if (isAI && hasReasoning && message.contentStarted) {
            val showThreeDotAnimationOnButton =
                isReasoningStreaming && !isReasoningComplete && message.contentStarted

            Box( // Outer Box for padding of the button
                modifier = Modifier.padding(bottom = 6.dp, top = 2.dp)
            ) {
                Box( // The actual button with background and click handling
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(38.dp)
                        .width(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .clickable(
                            onClick = onToggleReasoning,
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        )
                ) {
                    if (showThreeDotAnimationOnButton) {
                        ThreeDotsLoadingAnimation(
                            dotColor = Color.Black,
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
                            label = "circleIconSizeAnimation",
                            animationSpec = tween(
                                durationMillis = 250,
                                easing = FastOutSlowInEasing
                            )
                        )
                        Box(
                            modifier = Modifier
                                .size(circleIconSize)
                                .background(Color.Black, CircleShape)
                        )
                    }
                }
            }


            AnimatedVisibility(
                visible = (isReasoningStreaming && message.contentStarted) || isManuallyExpanded,
                modifier = Modifier.padding(bottom = 8.dp),
                enter = fadeIn(tween(250)) + expandVertically(
                    tween(250),
                    expandFrom = Alignment.Top
                ),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(180))
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = userBubbleGreyColor,
                    tonalElevation = 1.dp,
                    modifier = Modifier
                        .heightIn(max = 200.dp)
                        .wrapContentWidth()
                        .align(Alignment.Start)
                ) {
                    val scrollState = rememberScrollState()
                    val coroutineScope = rememberCoroutineScope()
                    var autoScrollJob by remember { mutableStateOf<Job?>(null) }
                    var userHasScrolledUp by remember { mutableStateOf(false) }

                    // Scroll logic
                    LaunchedEffect(
                        isReasoningComplete,
                        displayedReasoningText,
                        scrollState.maxValue
                    ) {
                        if (isReasoningComplete && displayedReasoningText == (message.reasoning?.trim()
                                ?: "")
                        ) {
                            autoScrollJob?.cancel(); delay(50)
                            if (isActive && scrollState.value < scrollState.maxValue) {
                                scrollState.animateScrollTo(
                                    scrollState.maxValue,
                                    tween(300, easing = FastOutSlowInEasing)
                                )
                            }
                        }
                    }
                    LaunchedEffect(
                        isReasoningStreaming,
                        message.contentStarted,
                        userHasScrolledUp
                    ) {
                        if (isReasoningStreaming && message.contentStarted && !userHasScrolledUp) {
                            autoScrollJob?.cancel()
                            autoScrollJob = coroutineScope.launch {
                                try {
                                    while (isActive) {
                                        if (scrollState.value < scrollState.maxValue) {
                                            scrollState.animateScrollTo(
                                                scrollState.maxValue,
                                                tween(
                                                    REASONING_AUTO_SCROLL_ANIMATION_MS,
                                                    easing = LinearOutSlowInEasing
                                                )
                                            )
                                        }
                                        delay(REASONING_AUTO_SCROLL_INTERVAL_MS)
                                    }
                                } catch (e: CancellationException) { /* Allowed */
                                }
                            }
                        } else {
                            autoScrollJob?.cancel()
                        }
                    }
                    val nestedScrollConnection = remember {
                        object : NestedScrollConnection {
                            override fun onPreScroll(
                                available: Offset,
                                source: NestedScrollSource
                            ): Offset {
                                if (available.y > 0 && scrollState.canScrollForward) {
                                    userHasScrolledUp = true; autoScrollJob?.cancel()
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
                            SelectionContainer {
                                Text(
                                    text = displayedReasoningText,
                                    color = Color(0xFF444444),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }

        // 主体气泡区
        if (!showLoadingBubble) {
            val aiBubbleColor = Color.White
            val actualBubbleColor = if (isAI) aiBubbleColor else userBubbleGreyColor
            val actualContentColor = Color.Black

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
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                        .wrapContentWidth()
                        .defaultMinSize(minHeight = 32.dp)
                ) {
                    if (showMainBubbleLoadingDots) {
                        ThreeDotsLoadingAnimation(
                            dotColor = MaterialTheme.colorScheme.primary, // This one stays primary as per no change request
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(y = (-6).dp)
                        )
                    } else {
                        SelectionContainer {
                            Text(
                                text = displayedMainTextState,
                                textAlign = TextAlign.Start,
                                modifier = Modifier
                            )
                        }
                    }
                }
            }
        }
    }
}

// ThreeDotsLoadingAnimation
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
    val infiniteTransition =
        rememberInfiniteTransition(label = "dots_loader_bubble_${dotColor.value}")

    @Composable
    fun animateDot(delayMillis: Int): Pair<Float, Float> {
        val key = remember { "dot_anim_bubble_${dotColor.value}_$delayMillis" };
        val yOffset by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(animation = keyframes {
                durationMillis =
                    animationDuration * 3; 0f at (animationDuration * 0) + delayMillis using LinearEasing; -bounceHeight.value at (animationDuration * 1) + delayMillis using LinearEasing; 0f at (animationDuration * 2) + delayMillis using LinearEasing; 0f at (animationDuration * 3) + delayMillis using LinearEasing
            }, repeatMode = RepeatMode.Restart),
            label = "${key}_yOffset_bubble"
        );
        val scale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(animation = keyframes {
                durationMillis =
                    animationDuration * 3; 1f at (animationDuration * 0) + delayMillis using LinearEasing; scaleAmount at (animationDuration * 1) + delayMillis using LinearEasing; 1f at (animationDuration * 2) + delayMillis using LinearEasing; 1f at (animationDuration * 3) + delayMillis using LinearEasing
            }, repeatMode = RepeatMode.Restart),
            label = "${key}_scale_bubble"
        ); return Pair(yOffset, scale)
    }

    val dotsAnim =
        listOf(animateDot(0), animateDot(animationDuration / 2), animateDot(animationDuration))
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        dotsAnim.forEachIndexed { index, (yOffset, scale) ->
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        translationY = yOffset; scaleX = scale; scaleY = scale
                    }
                    .size(dotSize)
                    .background(dotColor.copy(alpha = 1f), shape = CircleShape)
            ); if (index < dotsAnim.lastIndex) {
            Spacer(Modifier.width(spacing))
        }
        }
    }
}