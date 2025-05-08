package com.example.app1.ui.components

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
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
import com.example.app1.AppViewModel
import com.example.app1.data.models.Message
import com.example.app1.data.models.Sender
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

// --- Constants for Reasoning Scroll ---
// How often to attempt to scroll during streaming.
private const val REASONING_STREAM_SCROLL_ATTEMPT_INTERVAL_MS = 100L // Try to scroll every 100ms

// Animation duration for each incremental scroll during streaming.
private const val REASONING_STREAM_SCROLL_ANIMATION_MS = 250 // Animate the scroll over 250ms

// Animation duration for the final scroll when reasoning is complete.
private const val REASONING_COMPLETE_SCROLL_ANIMATION_MS = 300


// Typewriter delay remains the same
private const val TYPEWRITER_DELAY_MS_REASONING = 15L


@OptIn(ExperimentalAnimationApi::class)
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
    Log.d(
        "MessageBubbleRecomp",
        "Bubble ID: ${message.id}, Text len: ${message.text.length}, Reasoning len: ${message.reasoning?.length ?: 0}, isStreaming: $isReasoningStreaming, isComplete: $isReasoningComplete"
    )

    val isAI = message.sender == Sender.AI
    val currentMessageId = message.id

    val animationInitiallyPlayedByVM =
        remember(currentMessageId) { viewModel.hasAnimationBeenPlayed(currentMessageId) }

    var localAnimationTriggeredOrCompleted by remember(currentMessageId) {
        mutableStateOf(animationInitiallyPlayedByVM)
    }

    var displayedMainTextState by remember(currentMessageId, animationInitiallyPlayedByVM, isAI) {
        mutableStateOf(
            if ((animationInitiallyPlayedByVM || !isAI) && message.text.isNotBlank()) {
                message.text.trim()
            } else {
                ""
            }
        )
    }

    var displayedReasoningText by remember(currentMessageId) {
        mutableStateOf(if (message.contentStarted) message.reasoning?.trim() ?: "" else "")
    }

    val showMainBubbleLoadingDots = isAI &&
            !showLoadingBubble &&
            !message.isError &&
            message.text.isBlank() &&
            !message.contentStarted &&
            !localAnimationTriggeredOrCompleted

    // LaunchedEffect for Reasoning Text (Typewriter) - NO CHANGE HERE
    LaunchedEffect(
        currentMessageId,
        message.reasoning,
        isReasoningStreaming,
        message.contentStarted
    ) {
        if (isAI && message.contentStarted) {
            val fullReasoningText = message.reasoning?.trim() ?: ""
            if (fullReasoningText.isNotEmpty()) {
                if (isReasoningStreaming && displayedReasoningText.length < fullReasoningText.length) {
                    var currentDisplay = displayedReasoningText
                    try {
                        for (i in displayedReasoningText.length until fullReasoningText.length) {
                            if (!isActive) throw CancellationException("Reasoning typewriter for $currentMessageId cancelled (not active)")
                            currentDisplay = fullReasoningText.substring(0, i + 1)
                            displayedReasoningText = currentDisplay
                            delay(TYPEWRITER_DELAY_MS_REASONING)
                        }
                        if (isActive && displayedReasoningText != fullReasoningText) {
                            displayedReasoningText = fullReasoningText
                        }
                    } catch (e: CancellationException) {
                        Log.d(
                            "MessageBubble",
                            "Reasoning typewriter for $currentMessageId cancelled: ${e.message}"
                        )
                        if (isActive && displayedReasoningText != fullReasoningText) {
                            displayedReasoningText = fullReasoningText
                        }
                    }
                } else {
                    if (displayedReasoningText != fullReasoningText) {
                        displayedReasoningText = fullReasoningText
                    }
                }
            } else {
                if (displayedReasoningText.isNotEmpty()) {
                    displayedReasoningText = ""
                }
            }
        } else if (isAI && !message.contentStarted && displayedReasoningText.isNotEmpty()) {
            displayedReasoningText = ""
        } else if (message.reasoning.isNullOrBlank() && displayedReasoningText.isNotEmpty()) {
            displayedReasoningText = ""
        }
    }

    // LaunchedEffect for Main Text (Direct Set) - NO CHANGE HERE
    LaunchedEffect(
        currentMessageId,
        message.text,
        message.contentStarted,
        isAI,
        showLoadingBubble,
        message.isError
    ) {
        val fullMainText = message.text.trim()
        if (showLoadingBubble) return@LaunchedEffect
        if (message.contentStarted || !isAI) {
            if (displayedMainTextState != fullMainText) {
                displayedMainTextState = fullMainText
            }
            if (!localAnimationTriggeredOrCompleted && (fullMainText.isNotBlank() || message.isError)) {
                localAnimationTriggeredOrCompleted = true
                if (!animationInitiallyPlayedByVM) {
                    viewModel.onAnimationComplete(currentMessageId)
                }
                Log.d(
                    "MessageBubble",
                    "Main text 'animation' (direct set) marked completed for $currentMessageId."
                )
            } else if (!localAnimationTriggeredOrCompleted && fullMainText.isBlank() && isAI && !message.isError && message.contentStarted) {
                localAnimationTriggeredOrCompleted = true
                if (!animationInitiallyPlayedByVM) {
                    viewModel.onAnimationComplete(currentMessageId)
                }
                Log.d(
                    "MessageBubble",
                    "Main text (empty) 'animation' marked completed for AI $currentMessageId."
                )
            }
        } else if (!message.contentStarted && isAI && displayedMainTextState.isNotEmpty()) {
            displayedMainTextState = ""
        }
    }

    val userBubbleGreyColor = Color(red = 200, green = 200, blue = 200, alpha = 128)
    val aiBubbleColor = Color.White
    val contentBlackColor = Color.Black
    val reasoningTextColor = Color(0xFF444444)
    val errorTextColor = Color.Red

    Column(
        horizontalAlignment = if (isAI) Alignment.Start else Alignment.End,
        modifier = modifier.fillMaxWidth()
    ) {
        if (isAI && showLoadingBubble) {
            // ... Loading bubble ... (no change)
            Row(
                modifier = Modifier
                    .padding(vertical = 3.dp)
                    .wrapContentWidth()
                    .align(Alignment.Start)
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White,
                    shadowElevation = 4.dp,
                    contentColor = Color.Black
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.Black,
                            strokeWidth = 1.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(text = "正在连接大模型", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            return@Column
        }

        val shouldShowReasoningToggle =
            isAI && message.contentStarted && (message.reasoning != null || isReasoningStreaming)

        if (shouldShowReasoningToggle) {
            // ... Reasoning Toggle Button ... (no change)
            val showThreeDotAnimationOnButton =
                isReasoningStreaming && !isReasoningComplete && !message.isError
            Box(
                modifier = Modifier.padding(
                    start = if (isAI) 8.dp else 0.dp,
                    bottom = 6.dp,
                    top = 2.dp
                )
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(38.dp)
                        .width(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .clickable(
                            onClick = onToggleReasoning,
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() })
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
                            label = "reasoningToggleIconSize",
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


            val isReasoningTextVisible =
                (isManuallyExpanded && displayedReasoningText.isNotBlank()) ||
                        (isReasoningStreaming && message.contentStarted && !message.isError && displayedReasoningText.isNotBlank())

            AnimatedVisibility(
                visible = isReasoningTextVisible,
                modifier = Modifier.padding(start = if (isAI) 8.dp else 0.dp, bottom = 8.dp),
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
                    shadowElevation = 0.dp,
                    modifier = Modifier
                        .heightIn(max = 200.dp)
                        .wrapContentWidth(align = Alignment.Start)
                        .align(Alignment.Start)
                ) {
                    val scrollState = rememberScrollState()
                    val coroutineScope = rememberCoroutineScope()
                    var autoScrollJob by remember { mutableStateOf<Job?>(null) }
                    var userHasScrolledUp by remember { mutableStateOf(false) }

                    // --- MODIFIED: Auto-scroll for reasoning text during streaming ---
                    LaunchedEffect(
                        currentMessageId, // Ensure job restarts for new messages
                        isReasoningStreaming,
                        userHasScrolledUp,
                        message.contentStarted,
                        message.isError
                        // Note: Not including displayedReasoningText or scrollState.maxValue directly as keys
                        // for the *looping* job, to prevent job cancellation/restart on every char.
                        // The loop itself will check scrollState.value and scrollState.maxValue.
                    ) {
                        if (isReasoningStreaming && message.contentStarted && !userHasScrolledUp && !message.isError) {
                            autoScrollJob?.cancel() // Cancel any existing job
                            autoScrollJob = coroutineScope.launch {
                                Log.d(
                                    "ReasoningScroll",
                                    "Auto-scroll job STARTED for ${message.id}"
                                )
                                try {
                                    while (isActive) { // Loop while this job is active and conditions met
                                        // Give a moment for potential layout changes and text updates
                                        delay(REASONING_STREAM_SCROLL_ATTEMPT_INTERVAL_MS)

                                        if (!isActive) break // Check again after delay

                                        val currentMaxValue = scrollState.maxValue
                                        if (scrollState.value < currentMaxValue) {
                                            Log.d(
                                                "ReasoningScroll",
                                                "Scrolling ${message.id} from ${scrollState.value} to $currentMaxValue"
                                            )
                                            scrollState.animateScrollTo(
                                                currentMaxValue,
                                                animationSpec = tween(
                                                    durationMillis = REASONING_STREAM_SCROLL_ANIMATION_MS,
                                                    easing = LinearEasing // Use LinearEasing for smoother continuous scroll
                                                )
                                            )
                                        } else {
                                            Log.d(
                                                "ReasoningScroll",
                                                "Scroll unnecessary for ${message.id}: value=${scrollState.value}, max=$currentMaxValue"
                                            )
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    Log.d(
                                        "ReasoningScroll",
                                        "Auto-scroll job CANCELLED for ${message.id}: ${e.message}"
                                    )
                                } finally {
                                    Log.d(
                                        "ReasoningScroll",
                                        "Auto-scroll job FINISHED for ${message.id}"
                                    )
                                }
                            }
                        } else {
                            autoScrollJob?.cancel() // Conditions not met, cancel job
                            Log.d(
                                "ReasoningScroll",
                                "Auto-scroll conditions NOT MET for ${message.id}. Cancelling job. Streaming: $isReasoningStreaming, ScrolledUp: $userHasScrolledUp"
                            )
                        }
                    }

                    // Scroll to bottom when reasoning is fully complete (separate from streaming scroll)
                    LaunchedEffect(
                        currentMessageId,
                        isReasoningComplete,
                        displayedReasoningText, // Trigger when all text is displayed
                        message.isError
                    ) {
                        if (isReasoningComplete && !message.isError && displayedReasoningText == (message.reasoning?.trim()
                                ?: "")
                        ) {
                            // Ensure the streaming scroll job is stopped before attempting final scroll
                            autoScrollJob?.cancel()
                            Log.d(
                                "ReasoningScroll",
                                "Reasoning COMPLETE for ${message.id}. Final scroll."
                            )
                            // Small delay to ensure UI has settled after text is fully displayed
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
                                    if (!userHasScrolledUp) { // Only log and set if state changes
                                        userHasScrolledUp = true
                                        Log.d(
                                            "ReasoningScroll",
                                            "User scrolled UP ${message.id}. Auto-scroll DISABLED."
                                        )
                                        // autoScrollJob is cancelled by the LaunchedEffect reacting to userHasScrolledUp
                                    }
                                }
                                return Offset.Zero
                            }

                            override fun onPostScroll(
                                consumed: Offset,
                                available: Offset,
                                source: NestedScrollSource
                            ): Offset {
                                if (source == NestedScrollSource.Drag && available.y < 0 && (scrollState.value >= scrollState.maxValue - 1)) {
                                    if (userHasScrolledUp) { // Only log and set if state changes
                                        userHasScrolledUp = false
                                        Log.d(
                                            "ReasoningScroll",
                                            "User scrolled to BOTTOM ${message.id}. Auto-scroll RE-ENABLED (if streaming)."
                                        )
                                        // The LaunchedEffect will pick up userHasScrolledUp=false and restart job if streaming
                                    }
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
                                    color = reasoningTextColor,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }

        val shouldShowMainBubble =
            !showLoadingBubble && ((message.contentStarted || !isAI) || message.isError)
        if (shouldShowMainBubble) {
            // ... Main Message Bubble Surface and Box ... (no change)
            val actualBubbleColor = if (isAI) aiBubbleColor else userBubbleGreyColor
            val actualContentColor =
                if (message.isError && isAI) errorTextColor else contentBlackColor

            Surface(
                color = actualBubbleColor,
                contentColor = actualContentColor,
                shadowElevation = 0.dp,
                tonalElevation = if (isAI) 0.dp else 1.dp,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .padding(
                        start = if (isAI) 8.dp else 0.dp,
                        end = if (!isAI) 8.dp else 0.dp,
                        top = 2.dp,
                        bottom = 2.dp
                    )
                    .wrapContentWidth()
                    .align(if (isAI) Alignment.Start else Alignment.End)
            ) {
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .wrapContentWidth()
                        .defaultMinSize(minHeight = 28.dp)
                ) {
                    if (showMainBubbleLoadingDots) {
                        ThreeDotsLoadingAnimation(
                            dotColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(y = (-6).dp)
                        )
                    } else if (displayedMainTextState.isNotBlank() || (isAI && message.isError)) {
                        SelectionContainer {
                            Text(
                                text = displayedMainTextState,
                                textAlign = TextAlign.Start,
                                color = if (message.isError) errorTextColor else actualContentColor
                            )
                        }
                    } else if (isAI && message.contentStarted && !localAnimationTriggeredOrCompleted && !message.isError) {
                        Spacer(Modifier.size(0.dp))
                    }
                }
            }
        }
    }
}

// ThreeDotsLoadingAnimation - NO CHANGE HERE
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
        rememberInfiniteTransition(label = "three_dots_loader_bubble_${dotColor.value}")

    @Composable
    fun animateDot(delayMillis: Int): Pair<Float, Float> {
        val key = remember { "dot_anim_bubble_${dotColor.value}_$delayMillis" };
        val yOffset by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(animation = keyframes {
                durationMillis = animationDuration * 3
                0f at (animationDuration * 0) + delayMillis with LinearEasing
                -bounceHeight.value at (animationDuration * 1) + delayMillis with LinearEasing
                0f at (animationDuration * 2) + delayMillis with LinearEasing
                0f at (animationDuration * 3) + delayMillis with LinearEasing
            }, repeatMode = RepeatMode.Restart),
            label = "${key}_yOffset_bubble"
        );
        val scale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(animation = keyframes {
                durationMillis = animationDuration * 3
                1f at (animationDuration * 0) + delayMillis with LinearEasing
                scaleAmount at (animationDuration * 1) + delayMillis with LinearEasing
                1f at (animationDuration * 2) + delayMillis with LinearEasing
                1f at (animationDuration * 3) + delayMillis with LinearEasing
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