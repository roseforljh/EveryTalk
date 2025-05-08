package com.example.app1.ui.components

import android.util.Log
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle // Import explicitly
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.app1.AppViewModel
import com.example.app1.data.models.Message
import com.example.app1.data.models.Sender
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.CodeBlockStyle
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import com.halilibo.richtext.ui.string.RichTextStringStyle
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
private const val TYPEWRITER_DELAY_MS_MAIN_CONTENT = 30L

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    viewModel: AppViewModel,
    isMainContentStreaming: Boolean,
    isReasoningStreaming: Boolean,
    isReasoningComplete: Boolean,
    isManuallyExpanded: Boolean,
    onToggleReasoning: () -> Unit,
    maxWidth: Dp, // <-- ADDED: Max width constraint from parent
    modifier: Modifier = Modifier,
    showLoadingBubble: Boolean = false
) {
    // ... (Log, State Variables, LaunchedEffects - unchanged) ...
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

    var displayedMainTextState by remember(currentMessageId) {
        mutableStateOf(
            if (isAI && isMainContentStreaming && message.contentStarted && !message.isError) {
                ""
            } else {
                message.text.trim()
            }
        )
    }

    var displayedReasoningText by remember(currentMessageId) {
        mutableStateOf(if (message.contentStarted) message.reasoning?.trim() ?: "" else "")
    }

    val showMainBubbleLoadingDots = isAI &&
            !showLoadingBubble &&
            !message.isError &&
            !message.contentStarted &&
            (message.text.isBlank() && message.reasoning.isNullOrBlank())


    LaunchedEffect(
        currentMessageId,
        message.text,
        isMainContentStreaming,
        isAI,
        message.contentStarted,
        message.isError,
        showLoadingBubble
    ) {
        // ... (Main Text Typewriter Logic - unchanged) ...
        if (showLoadingBubble) {
            if (displayedMainTextState.isNotEmpty()) displayedMainTextState = ""
            return@LaunchedEffect
        }
        val fullMainText = message.text.trim()
        if (message.isError) {
            if (displayedMainTextState != fullMainText) displayedMainTextState = fullMainText
            if (!localAnimationTriggeredOrCompleted) {
                localAnimationTriggeredOrCompleted = true
                if (!animationInitiallyPlayedByVM) viewModel.onAnimationComplete(currentMessageId)
            }
            return@LaunchedEffect
        }
        if (isAI && isMainContentStreaming && message.contentStarted) {
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
                        if (isActive && displayedMainTextState != fullMainText) displayedMainTextState =
                            fullMainText
                    } catch (e: CancellationException) {
                        if (isActive && displayedMainTextState != fullMainText) displayedMainTextState =
                            fullMainText
                    } finally {
                        if (isActive && !localAnimationTriggeredOrCompleted && displayedMainTextState.isNotBlank()) {
                            localAnimationTriggeredOrCompleted = true
                            if (!animationInitiallyPlayedByVM) viewModel.onAnimationComplete(
                                currentMessageId
                            )
                        }
                    }
                } else if (displayedMainTextState != fullMainText) {
                    displayedMainTextState = fullMainText
                    if (!localAnimationTriggeredOrCompleted && fullMainText.isNotEmpty()) {
                        localAnimationTriggeredOrCompleted = true
                        if (!animationInitiallyPlayedByVM) viewModel.onAnimationComplete(
                            currentMessageId
                        )
                    }
                } else if (fullMainText.isNotEmpty() && !localAnimationTriggeredOrCompleted) {
                    localAnimationTriggeredOrCompleted = true
                    if (!animationInitiallyPlayedByVM) viewModel.onAnimationComplete(
                        currentMessageId
                    )
                }
            } else {
                if (displayedMainTextState.isNotEmpty()) displayedMainTextState = ""
            }
        } else if (isAI && !message.contentStarted && displayedMainTextState.isNotEmpty()) {
            displayedMainTextState = ""
        } else {
            if (displayedMainTextState != fullMainText) displayedMainTextState = fullMainText
            if (!localAnimationTriggeredOrCompleted) {
                if (fullMainText.isNotBlank() || !isAI) {
                    localAnimationTriggeredOrCompleted = true
                    if (!animationInitiallyPlayedByVM) viewModel.onAnimationComplete(
                        currentMessageId
                    )
                } else if (isAI && message.contentStarted && fullMainText.isBlank()) {
                    localAnimationTriggeredOrCompleted = true
                    if (!animationInitiallyPlayedByVM) viewModel.onAnimationComplete(
                        currentMessageId
                    )
                }
            }
        }
    }

    LaunchedEffect(
        currentMessageId,
        message.reasoning,
        isReasoningStreaming,
        message.contentStarted
    ) {
        // ... (Reasoning Typewriter Logic - unchanged) ...
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

    // --- Color Definitions ---
    val aiBubbleColor = Color.White
    val aiContentColor = Color.Black
    val errorTextColor = Color.Red
    val unifiedBackgroundColor = Color(0xFFF0F0F0) // Light Gray for User Bubble & Code Block Bg
    val unifiedContentColor = Color.Black       // Black text/icons for unified background
    val codeBlockBarColor = Color(0xFFE5E5E5)     // Slightly darker gray for the top bar
    val codeBlockBarContentColor = Color.Black                 // Pure Black for bar text/icon
    val reasoningTextColor = Color(0xFF444444)
    val codeBlockCornerRadius = 12.dp                          // Unified radius for code blocks

    Column(
        horizontalAlignment = if (isAI) Alignment.Start else Alignment.End,
        modifier = modifier.fillMaxWidth()
    ) {
        if (isAI && showLoadingBubble) {
            // ... (Loading bubble - unchanged) ...
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
            // ... (Reasoning toggle and content - unchanged) ...
            val userBubbleGreyColorForReasoning =
                Color(red = 200, green = 200, blue = 200, alpha = 128) // Specific for reasoning
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
                        .background(Color.White) // Reasoning toggle button background
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
                    color = userBubbleGreyColorForReasoning,
                    tonalElevation = 1.dp,
                    shadowElevation = 0.dp,
                    modifier = Modifier
                        .heightIn(max = 200.dp)
                        .wrapContentWidth(align = Alignment.Start)
                        .align(Alignment.Start)
                ) {
                    val scrollState = rememberScrollState();
                    val coroutineScope = rememberCoroutineScope();
                    var autoScrollJob by remember { mutableStateOf<Job?>(null) };
                    var userHasScrolledUp by remember { mutableStateOf(false) }
                    LaunchedEffect(
                        currentMessageId,
                        isReasoningStreaming,
                        userHasScrolledUp,
                        message.contentStarted,
                        message.isError
                    ) {
                        if (isReasoningStreaming && message.contentStarted && !userHasScrolledUp && !message.isError) {
                            autoScrollJob?.cancel(); autoScrollJob = coroutineScope.launch {
                                try {
                                    while (isActive) {
                                        delay(REASONING_STREAM_SCROLL_ATTEMPT_INTERVAL_MS); if (!isActive) break;
                                        val currentMaxValue =
                                            scrollState.maxValue; if (scrollState.value < currentMaxValue) {
                                            scrollState.animateScrollTo(
                                                currentMaxValue,
                                                animationSpec = tween(
                                                    durationMillis = REASONING_STREAM_SCROLL_ANIMATION_MS,
                                                    easing = LinearEasing
                                                )
                                            )
                                        }
                                    }
                                } catch (e: CancellationException) { /* ... */
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
                        message.isError
                    ) {
                        if (isReasoningComplete && !message.isError && displayedReasoningText == (message.reasoning?.trim()
                                ?: "")
                        ) {
                            autoScrollJob?.cancel(); delay(100); if (isActive && scrollState.value < scrollState.maxValue) {
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
                                }; return Offset.Zero
                            }

                            override fun onPostScroll(
                                consumed: Offset,
                                available: Offset,
                                source: NestedScrollSource
                            ): Offset {
                                if (source == NestedScrollSource.Drag && available.y < 0 && (scrollState.value >= scrollState.maxValue - 1)) {
                                    if (userHasScrolledUp) userHasScrolledUp = false
                                }; return Offset.Zero
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


        val shouldShowMainBubbleSurface =
            !showLoadingBubble && ((isAI && message.contentStarted) || !isAI || message.isError)

        if (shouldShowMainBubbleSurface) {
            val actualBubbleColor = if (isAI) aiBubbleColor else unifiedBackgroundColor
            val actualContentColor = when {
                message.isError && isAI -> errorTextColor
                isAI -> aiContentColor
                else -> unifiedContentColor
            }
            val context = LocalContext.current
            val clipboardManager = LocalClipboardManager.current

            val isMessageEffectivelyCodeBlock =
                isAI && !message.isError && displayedMainTextState.trim().startsWith("```")

            Surface(
                color = if (isMessageEffectivelyCodeBlock) unifiedBackgroundColor else actualBubbleColor,
                contentColor = if (isMessageEffectivelyCodeBlock) unifiedContentColor else actualContentColor,
                shadowElevation = 0.dp,
                tonalElevation = if (isAI && !isMessageEffectivelyCodeBlock) 0.dp else 1.dp,
                shape = if (isMessageEffectivelyCodeBlock) RoundedCornerShape(codeBlockCornerRadius) else RoundedCornerShape(
                    18.dp
                ),
                modifier = Modifier
                    // ** Apply max width constraint **
                    .widthIn(max = maxWidth) // Use the passed-in max width
                    // Apply padding for margin around the bubble
                    .padding(
                        start = if (isAI) 8.dp else 0.dp,
                        end = if (!isAI) 8.dp else 0.dp,
                        top = 2.dp,
                        bottom = 2.dp
                    )
                    // Align the bubble itself
                    .align(if (isAI) Alignment.Start else Alignment.End)
            ) {
                // --- Conditional Layout ---
                if (isMessageEffectivelyCodeBlock) {
                    // ** Layout for AI Message that IS primarily a Code Block **
                    Column {
                        // Column arranges Top Bar and Content vertically
                        // --- Top Bar (Language Label + Copy Button) ---
                        fun extractCodeFromMarkdown(markdown: String): Pair<String, String?> {
                            val GFM_CODE_BLOCK_PATTERN = "```([a-zA-Z0-9_.-]*)\\n([\\s\\S]*?)\\n```"
                            val match =
                                Regex(GFM_CODE_BLOCK_PATTERN, RegexOption.MULTILINE).find(markdown)
                            val language = match?.groups?.get(1)?.value?.takeIf { it.isNotBlank() }
                            val code = match?.groups?.get(2)?.value?.trim() ?: markdown.trim()
                            return Pair(code, language)
                        }
                        val (codeContentForCopy, _) = extractCodeFromMarkdown(displayedMainTextState)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(codeBlockBarColor) // Bar background
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Markdown",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    color = codeBlockBarContentColor, // Black
                                    fontWeight = FontWeight.Bold
                                ),
                                modifier = Modifier.padding(start = 4.dp)
                            )
                            TextButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(codeContentForCopy))
                                    Toast.makeText(context, "代码已复制", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.heightIn(min = 28.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    Icons.Filled.ContentCopy, contentDescription = "复制",
                                    modifier = Modifier.size(18.dp),
                                    tint = codeBlockBarContentColor // Black
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "复制", style = MaterialTheme.typography.labelMedium.copy(
                                        color = codeBlockBarContentColor, // Black
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                            }
                        } // End Top Bar Row

                        // --- RichText Content Area for Code Block ---
                        Box(
                            modifier = Modifier.padding(
                                start = 12.dp,
                                top = 8.dp,
                                end = 12.dp,
                                bottom = 12.dp
                            )
                        ) {
                            SelectionContainer {
                                RichText(
                                    style = RichTextStyle.Default.copy(
                                        stringStyle = RichTextStringStyle.Default.copy(
                                            linkStyle = SpanStyle(
                                                color = Color.Blue,
                                                textDecoration = TextDecoration.Underline
                                            ),
                                            codeStyle = SpanStyle(
                                                fontFamily = FontFamily.Monospace,
                                                background = Color.Black.copy(alpha = 0.1f),
                                                color = unifiedContentColor // Black text
                                            )
                                        ),
                                        codeBlockStyle = CodeBlockStyle(
                                            textStyle = TextStyle( // Style text *inside* code block
                                                fontFamily = FontFamily.Monospace,
                                                color = unifiedContentColor // Black text
                                            ),
                                            // Background/Clip handled by parent Column
                                            modifier = Modifier.padding(0.dp)
                                        ),
                                        headingStyle = { _, inherited -> inherited.copy(color = unifiedContentColor) }
                                    )
                                ) {
                                    Markdown(content = displayedMainTextState)
                                }
                            }
                        } // End Content Box
                    } // End Column for Code Block structure
                } else {
                    // ** Layout for Standard AI Message / User Message **
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier
                            // Basic padding needed for content within standard bubble
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .wrapContentWidth() // Allow standard bubbles to wrap
                            .defaultMinSize(minHeight = 28.dp)
                    ) {
                        if (showMainBubbleLoadingDots) {
                            ThreeDotsLoadingAnimation(
                                dotColor = if (isAI) MaterialTheme.colorScheme.primary else unifiedContentColor,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .offset(y = (-6).dp)
                            )
                        } else if (displayedMainTextState.isNotBlank() || (message.isError && isAI)) {
                            SelectionContainer {
                                if (isAI && !message.isError) { // Standard AI non-error: Use RichText
                                    RichText(
                                        style = RichTextStyle.Default.copy(
                                            // Code blocks *within* standard AI messages get styled
                                            codeBlockStyle = CodeBlockStyle(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(codeBlockCornerRadius))
                                                    .background(unifiedBackgroundColor) // Light gray bg
                                                    .padding(
                                                        horizontal = 12.dp,
                                                        vertical = 8.dp
                                                    ), // Padding inside
                                                textStyle = TextStyle(
                                                    fontFamily = FontFamily.Monospace,
                                                    color = unifiedContentColor // Black text
                                                )
                                            ),
                                            // Other styles use standard AI content color (Black on White)
                                            stringStyle = RichTextStringStyle.Default.copy(
                                                linkStyle = SpanStyle(
                                                    color = Color.Blue,
                                                    textDecoration = TextDecoration.Underline
                                                ),
                                                codeStyle = SpanStyle(
                                                    fontFamily = FontFamily.Monospace,
                                                    background = aiContentColor.copy(alpha = 0.1f),
                                                    color = aiContentColor
                                                )
                                            ),
                                            headingStyle = { level, inheritedTextStyle ->
                                                val baseStyle =
                                                    inheritedTextStyle.copy(color = aiContentColor)
                                                when (level) {
                                                    1 -> baseStyle.copy(
                                                        fontSize = 20.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )

                                                    2 -> baseStyle.copy(
                                                        fontSize = 18.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )

                                                    else -> baseStyle
                                                }
                                            }
                                        )
                                    ) {
                                        Markdown(content = displayedMainTextState)
                                    }
                                } else { // User message or AI error: Use normal Text
                                    Text(
                                        text = displayedMainTextState,
                                        textAlign = TextAlign.Start,
                                        color = actualContentColor // unifiedContentColor or errorTextColor
                                    )
                                }
                            }
                        }
                    } // End Box for standard content
                } // End Conditional Layout
            } // End Surface
        } // End if shouldShowMainBubbleSurface
    } // End Main Column
}

@Composable
private fun ThreeDotsLoadingAnimation(
    // ... (ThreeDotsLoadingAnimation - unchanged) ...
    modifier: Modifier = Modifier,
    dotSize: Dp = 10.dp,
    dotColor: Color = MaterialTheme.colorScheme.primary,
    spacing: Dp = 10.dp,
    bounceHeight: Dp = 10.dp,
    scaleAmount: Float = 1.25f,
    animationDuration: Int = 450
) {
    // ... (Implementation unchanged) ...
    val infiniteTransition =
        rememberInfiniteTransition(label = "three_dots_loader_bubble_${dotColor.value}")

    @Composable
    fun animateDot(delayMillis: Int): Pair<Float, Float> {
        val key = remember { "dot_anim_bubble_${dotColor.value}_$delayMillis" }
        val yOffset by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = 0f,
            animationSpec = infiniteRepeatable(animation = keyframes {
                durationMillis = animationDuration * 3
                0f at (animationDuration * 0) + delayMillis with LinearEasing
                -bounceHeight.value at (animationDuration * 1) + delayMillis with LinearEasing
                0f at (animationDuration * 2) + delayMillis with LinearEasing
                0f at (animationDuration * 3) + delayMillis with LinearEasing
            }, repeatMode = RepeatMode.Restart), label = "${key}_yOffset_bubble"
        )
        val scale by infiniteTransition.animateFloat(
            initialValue = 1f, targetValue = 1f,
            animationSpec = infiniteRepeatable(animation = keyframes {
                durationMillis = animationDuration * 3
                1f at (animationDuration * 0) + delayMillis with LinearEasing
                scaleAmount at (animationDuration * 1) + delayMillis with LinearEasing
                1f at (animationDuration * 2) + delayMillis with LinearEasing
                1f at (animationDuration * 3) + delayMillis with LinearEasing
            }, repeatMode = RepeatMode.Restart), label = "${key}_scale_bubble"
        )
        return Pair(yOffset, scale)
    }

    val dotsAnim =
        listOf(animateDot(0), animateDot(animationDuration / 2), animateDot(animationDuration))
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        dotsAnim.forEachIndexed { index, (yOffset, scale) ->
            Box(modifier = Modifier
                .graphicsLayer {
                    translationY = yOffset; scaleX = scale; scaleY = scale
                }
                .size(dotSize)
                .background(dotColor.copy(alpha = 1f), shape = CircleShape))
            if (index < dotsAnim.lastIndex) {
                Spacer(Modifier.width(spacing))
            }
        }
    }
}