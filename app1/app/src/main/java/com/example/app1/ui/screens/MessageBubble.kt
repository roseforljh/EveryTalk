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
import androidx.compose.foundation.layout.offset // Ensure this is androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.geometry.Offset // Ensure this is androidx.compose.ui.geometry.Offset
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
private const val REASONING_STREAM_SCROLL_ATTEMPT_INTERVAL_MS = 100L
private const val REASONING_STREAM_SCROLL_ANIMATION_MS = 250
private const val REASONING_COMPLETE_SCROLL_ANIMATION_MS = 300

// Typewriter delay for different parts
private const val TYPEWRITER_DELAY_MS_REASONING = 15L
private const val TYPEWRITER_DELAY_MS_MAIN_CONTENT = 30L // Delay for main message content

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    viewModel: AppViewModel,
    isMainContentStreaming: Boolean, // <-- New parameter from ChatScreen
    isReasoningStreaming: Boolean,
    isReasoningComplete: Boolean,
    isManuallyExpanded: Boolean,
    onToggleReasoning: () -> Unit,
    modifier: Modifier = Modifier,
    showLoadingBubble: Boolean = false
) {
    Log.d(
        "MessageBubbleRecomp",
        "Bubble ID: ${message.id}, Text len: ${message.text.length}, Reasoning len: ${message.reasoning?.length ?: 0}, isMainStreaming: $isMainContentStreaming, isReasoningStreaming: $isReasoningStreaming, isComplete: $isReasoningComplete, contentStarted: ${message.contentStarted}"
    )

    val isAI = message.sender == Sender.AI
    val currentMessageId = message.id

    val animationInitiallyPlayedByVM =
        remember(currentMessageId) { viewModel.hasAnimationBeenPlayed(currentMessageId) }

    var localAnimationTriggeredOrCompleted by remember(currentMessageId) {
        mutableStateOf(animationInitiallyPlayedByVM)
    }

    // State for the displayed main text, potentially animated by typewriter
    var displayedMainTextState by remember(currentMessageId) {
        mutableStateOf(
            if (isAI && isMainContentStreaming && message.contentStarted && !message.isError) {
                "" // Start empty if AI is streaming main content
            } else {
                message.text.trim() // Otherwise, show current full text
            }
        )
    }

    // State for displayed reasoning text (remains the same)
    var displayedReasoningText by remember(currentMessageId) {
        mutableStateOf(if (message.contentStarted) message.reasoning?.trim() ?: "" else "")
    }

    val showMainBubbleLoadingDots = isAI &&
            !showLoadingBubble && // This is the "overall loading" bubble, not the dots in an empty text bubble
            !message.isError &&
            !message.contentStarted && // Only show if content stream hasn't even begun for this message
            (message.text.isBlank() && message.reasoning.isNullOrBlank()) // And there's no content at all in the Message object yet


    // --- LaunchedEffect for Main Text (Typewriter for AI, direct set for User/Completed AI) ---
    LaunchedEffect(
        currentMessageId,
        message.text,             // Full target text from ViewModel
        isMainContentStreaming,   // Controls whether typewriter should run
        isAI,
        message.contentStarted,
        message.isError,
        showLoadingBubble         // If true, this bubble shouldn't show text
    ) {
        if (showLoadingBubble) { // If the global loading bubble is shown, don't process text here
            if (displayedMainTextState.isNotEmpty()) displayedMainTextState =
                "" // Clear if any was there
            return@LaunchedEffect
        }

        val fullMainText = message.text.trim()

        if (message.isError) {
            if (displayedMainTextState != fullMainText) {
                displayedMainTextState = fullMainText // Show error message immediately
            }
            if (!localAnimationTriggeredOrCompleted) {
                localAnimationTriggeredOrCompleted = true
                if (!animationInitiallyPlayedByVM) viewModel.onAnimationComplete(currentMessageId)
                Log.d(
                    "MessageBubble",
                    "Error text set, animation marked completed for $currentMessageId."
                )
            }
            return@LaunchedEffect
        }

        if (isAI && isMainContentStreaming && message.contentStarted) {
            // AI Message, Main Content is Streaming, and content has started
            if (fullMainText.isNotEmpty()) {
                if (displayedMainTextState.length < fullMainText.length) {
                    var currentDisplay = displayedMainTextState
                    try {
                        for (i in currentDisplay.length until fullMainText.length) {
                            if (!isActive) throw CancellationException("Main text typewriter for $currentMessageId cancelled (not active)")
                            currentDisplay = fullMainText.substring(0, i + 1)
                            displayedMainTextState = currentDisplay
                            delay(TYPEWRITER_DELAY_MS_MAIN_CONTENT)
                        }
                        if (isActive && displayedMainTextState != fullMainText) {
                            displayedMainTextState =
                                fullMainText // Ensure full text is set if loop completes
                        }
                    } catch (e: CancellationException) {
                        Log.d(
                            "MessageBubble",
                            "Main text typewriter for $currentMessageId cancelled: ${e.message}"
                        )
                        if (isActive && displayedMainTextState != fullMainText) {
                            displayedMainTextState =
                                fullMainText // Ensure full text is set on cancellation
                        }
                    } finally {
                        if (isActive) {
                            // Mark animation as complete once typing finishes or is cancelled but text is present
                            if (!localAnimationTriggeredOrCompleted && displayedMainTextState.isNotBlank()) {
                                localAnimationTriggeredOrCompleted = true
                                if (!animationInitiallyPlayedByVM) {
                                    viewModel.onAnimationComplete(currentMessageId)
                                }
                                Log.d(
                                    "MessageBubble",
                                    "Main text typewriter animation marked completed for $currentMessageId."
                                )
                            }
                        }
                    }
                } else if (displayedMainTextState != fullMainText) {
                    // Stream flag is on, but displayed text might be out of sync (e.g. message updated)
                    displayedMainTextState = fullMainText
                    if (!localAnimationTriggeredOrCompleted && fullMainText.isNotEmpty()) {
                        localAnimationTriggeredOrCompleted = true
                        if (!animationInitiallyPlayedByVM) viewModel.onAnimationComplete(
                            currentMessageId
                        )
                        Log.d(
                            "MessageBubble",
                            "Main text (synced during stream) marked completed for $currentMessageId."
                        )
                    }
                } else if (fullMainText.isNotEmpty() && !localAnimationTriggeredOrCompleted) {
                    // Already fully displayed but not marked complete
                    localAnimationTriggeredOrCompleted = true
                    if (!animationInitiallyPlayedByVM) viewModel.onAnimationComplete(
                        currentMessageId
                    )
                    Log.d(
                        "MessageBubble",
                        "Main text (already full, stream flag on) marked completed for $currentMessageId."
                    )
                }
            } else { // fullMainText is empty, but we are "streaming" (e.g. waiting for first chunk)
                if (displayedMainTextState.isNotEmpty()) displayedMainTextState =
                    "" // Clear if anything was there
                // Don't mark localAnimationTriggeredOrCompleted yet if fullMainText is empty, wait for content
            }
        } else if (isAI && !message.contentStarted && displayedMainTextState.isNotEmpty()) {
            // AI message, but content has not started (or was reset), clear any displayed text
            displayedMainTextState = ""
            // localAnimationTriggeredOrCompleted should ideally be false if content is reset
            // but this effect might run multiple times. The key is initial state.
        } else {
            // Not Streaming AI Main Content OR User Message OR AI content not started (but not actively streaming)
            // Set text directly.
            if (displayedMainTextState != fullMainText) {
                displayedMainTextState = fullMainText
            }

            // Mark animation complete if content is present or it's a user message
            // or if AI content has started and text is confirmed blank (e.g. only reasoning)
            if (!localAnimationTriggeredOrCompleted) {
                if (fullMainText.isNotBlank() || !isAI) { // User message or AI with text
                    localAnimationTriggeredOrCompleted = true
                    if (!animationInitiallyPlayedByVM) viewModel.onAnimationComplete(
                        currentMessageId
                    )
                    Log.d(
                        "MessageBubble",
                        "Main text (direct set) animation marked completed for $currentMessageId."
                    )
                } else if (isAI && message.contentStarted && fullMainText.isBlank()) { // AI, content started, but text is empty
                    localAnimationTriggeredOrCompleted = true
                    if (!animationInitiallyPlayedByVM) viewModel.onAnimationComplete(
                        currentMessageId
                    )
                    Log.d(
                        "MessageBubble",
                        "Main text (direct set, empty AI) marked completed for $currentMessageId."
                    )
                }
            }
        }
    }


    // LaunchedEffect for Reasoning Text (Typewriter) - REMAINS THE SAME
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
                } else { // Not streaming or already past full length
                    if (displayedReasoningText != fullReasoningText) {
                        displayedReasoningText = fullReasoningText
                    }
                }
            } else { // fullReasoningText is empty
                if (displayedReasoningText.isNotEmpty()) {
                    displayedReasoningText = ""
                }
            }
        } else if (isAI && !message.contentStarted && displayedReasoningText.isNotEmpty()) {
            // Content not started for AI, clear reasoning
            displayedReasoningText = ""
        } else if (message.reasoning.isNullOrBlank() && displayedReasoningText.isNotEmpty()) {
            // Reasoning is null/blank in source, clear displayed
            displayedReasoningText = ""
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
            // ... Loading bubble ... (no change from your provided code)
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
            return@Column // Exit if global loading bubble is shown
        }

        val shouldShowReasoningToggle =
            isAI && message.contentStarted && (message.reasoning != null || isReasoningStreaming) // Keep original logic

        if (shouldShowReasoningToggle) {
            // ... Reasoning Toggle Button ... (no change from your provided code)
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
                    color = userBubbleGreyColor, // Or another distinct color for reasoning
                    tonalElevation = 1.dp,
                    shadowElevation = 0.dp,
                    modifier = Modifier
                        .heightIn(max = 200.dp)
                        .wrapContentWidth(align = Alignment.Start)
                        .align(Alignment.Start) // Ensure reasoning bubble aligns start for AI
                ) {
                    val scrollState = rememberScrollState()
                    val coroutineScope = rememberCoroutineScope()
                    var autoScrollJob by remember { mutableStateOf<Job?>(null) }
                    var userHasScrolledUp by remember { mutableStateOf(false) }

                    // Auto-scroll for reasoning text during streaming (no change from your provided code)
                    LaunchedEffect(
                        currentMessageId,
                        isReasoningStreaming,
                        userHasScrolledUp,
                        message.contentStarted,
                        message.isError
                    ) {
                        if (isReasoningStreaming && message.contentStarted && !userHasScrolledUp && !message.isError) {
                            autoScrollJob?.cancel()
                            autoScrollJob = coroutineScope.launch {
                                Log.d(
                                    "ReasoningScroll",
                                    "Auto-scroll job STARTED for ${message.id}"
                                )
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
                            autoScrollJob?.cancel()
                            Log.d(
                                "ReasoningScroll",
                                "Auto-scroll conditions NOT MET for ${message.id}. Cancelling. Streaming: $isReasoningStreaming, ScrolledUp: $userHasScrolledUp"
                            )
                        }
                    }

                    // Scroll to bottom when reasoning is fully complete (no change from your provided code)
                    LaunchedEffect(
                        currentMessageId,
                        isReasoningComplete,
                        displayedReasoningText,
                        message.isError
                    ) {
                        if (isReasoningComplete && !message.isError && displayedReasoningText == (message.reasoning?.trim()
                                ?: "")
                        ) {
                            autoScrollJob?.cancel()
                            Log.d(
                                "ReasoningScroll",
                                "Reasoning COMPLETE for ${message.id}. Final scroll."
                            )
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

                    val nestedScrollConnection = remember { // (no change from your provided code)
                        object : NestedScrollConnection {
                            override fun onPreScroll(
                                available: Offset,
                                source: NestedScrollSource
                            ): Offset {
                                if (source == NestedScrollSource.Drag && available.y > 0 && scrollState.canScrollForward) {
                                    if (!userHasScrolledUp) {
                                        userHasScrolledUp = true
                                        Log.d(
                                            "ReasoningScroll",
                                            "User scrolled UP ${message.id}. Auto-scroll DISABLED."
                                        )
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
                                    if (userHasScrolledUp) {
                                        userHasScrolledUp = false
                                        Log.d(
                                            "ReasoningScroll",
                                            "User scrolled to BOTTOM ${message.id}. Auto-scroll RE-ENABLED (if streaming)."
                                        )
                                    }
                                }
                                return Offset.Zero
                            }
                        }
                    }

                    Column( // (no change from your provided code)
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


        // --- Main Message Bubble ---
        // Condition to show the main bubble surface
        val shouldShowMainBubbleSurface = !showLoadingBubble && // Not the global loading bubble
                (
                        (isAI && message.contentStarted) || // AI message and content has started (even if text is initially empty for typewriter)
                                !isAI || // User message always shows
                                message.isError // Error message always shows
                        )


        if (shouldShowMainBubbleSurface) {
            val actualBubbleColor = if (isAI) aiBubbleColor else userBubbleGreyColor
            val actualContentColor =
                if (message.isError && isAI) errorTextColor else contentBlackColor

            Surface(
                color = actualBubbleColor,
                contentColor = actualContentColor,
                shadowElevation = 0.dp, // Subtle shadow for AI bubbles
                tonalElevation = if (isAI) 0.dp else 1.dp,  // Or use elevation for AI too if preferred
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
                        .defaultMinSize(minHeight = 28.dp) // Ensure bubble has some min height
                ) {
                    if (showMainBubbleLoadingDots) { // This is for the dots *before* contentStarted
                        ThreeDotsLoadingAnimation(
                            dotColor = MaterialTheme.colorScheme.primary, // Or contentBlackColor
                            modifier = Modifier
                                .align(Alignment.Center) // Or Alignment.CenterStart
                                .offset(y = (-6).dp) // Adjust as needed
                        )
                    } else if (displayedMainTextState.isNotBlank() || (message.isError && isAI)) {
                        // Show text if it's available or if it's an AI error message
                        SelectionContainer {
                            Text(
                                text = displayedMainTextState,
                                textAlign = TextAlign.Start,
                                color = if (message.isError) errorTextColor else actualContentColor
                            )
                        }
                    } else if (isAI && message.contentStarted && !localAnimationTriggeredOrCompleted && !message.isError) {
                        // This case is for when AI content has started, text is currently blank (about to type or truly empty),
                        // and it's not an error. Renders an empty box that will fill.
                        // A Spacer(Modifier.size(0.dp)) or just an empty Box is fine.
                        // The defaultMinSize on the parent Box will ensure it's not invisible.
                    }
                    // If none of the above, the box remains (potentially with minHeight), and will be empty if displayedMainTextState is empty.
                }
            }
        }
    }
}

// ThreeDotsLoadingAnimation - (no change from your provided code)
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