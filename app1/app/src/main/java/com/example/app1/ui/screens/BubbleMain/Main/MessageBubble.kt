package com.example.app1.ui.screens.BubbleMain.Main // 你的实际包名

import android.util.Log
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.app1.AppViewModel
import com.example.app1.data.models.Message
import com.example.app1.data.models.Sender
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.cancellation.CancellationException

// 主要常量保留或根据需要移至更具体的文件
private const val TYPEWRITER_DELAY_MS_REASONING = 15L
private const val TYPEWRITER_DELAY_MS_MAIN_CONTENT = 10L

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
    onUserInteraction: () -> Unit,
    maxWidth: Dp,
    codeBlockFixedWidth: Dp,
    onEditRequest: (Message) -> Unit,
    onRegenerateRequest: (Message) -> Unit,
    modifier: Modifier = Modifier,
    showLoadingBubble: Boolean = false
) {
    Log.d(
        "MessageBubbleRecomp",
        "ID: ${message.id.take(8)}, Sender: ${message.sender}, TextLen: ${message.text.length}, Streaming: $isMainContentStreaming, ContentStarted: ${message.contentStarted}, Error: ${message.isError}"
    )

    val isAI = message.sender == Sender.AI
    val currentMessageId = message.id

    val animationInitiallyPlayedByVM =
        remember(currentMessageId) { viewModel.hasAnimationBeenPlayed(currentMessageId) }
    var localAnimationTriggeredOrCompleted by remember(currentMessageId, message.sender) {
        mutableStateOf(animationInitiallyPlayedByVM)
    }

    var displayedMainTextState by remember(currentMessageId, message.sender) {
        mutableStateOf(
            if (isAI && isMainContentStreaming && message.contentStarted && !message.isError) ""
            else message.text.trim()
        )
    }
    var displayedReasoningText by remember(currentMessageId, message.sender) {
        mutableStateOf(if (isAI && message.contentStarted) message.reasoning?.trim() ?: "" else "")
    }

    val showMainBubbleLoadingDots = isAI && !showLoadingBubble && !message.isError &&
            !message.contentStarted && (message.text.isBlank() && message.reasoning.isNullOrBlank())

    // 主内容打字机效果
    LaunchedEffect(
        currentMessageId,
        message.text,
        isMainContentStreaming,
        isAI,
        message.contentStarted,
        message.isError,
        showLoadingBubble
    ) {
        if (showLoadingBubble) {
            if (displayedMainTextState.isNotEmpty()) displayedMainTextState = ""
            return@LaunchedEffect
        }
        val fullMainTextTrimmed = message.text.trim()
        if (message.isError) {
            if (displayedMainTextState != fullMainTextTrimmed) displayedMainTextState =
                fullMainTextTrimmed
            if (!localAnimationTriggeredOrCompleted) {
                localAnimationTriggeredOrCompleted = true
                if (!animationInitiallyPlayedByVM) viewModel.onAnimationComplete(currentMessageId)
            }
            return@LaunchedEffect
        }
        if (isAI && isMainContentStreaming && message.contentStarted) {
            val rawFullText = message.text
            if (rawFullText.isNotEmpty()) {
                if (displayedMainTextState.length < rawFullText.length) {
                    var currentDisplayProgress = displayedMainTextState
                    try {
                        for (i in currentDisplayProgress.length until rawFullText.length) {
                            if (!isActive) throw CancellationException("Main text typewriter for $currentMessageId cancelled (not active)")
                            currentDisplayProgress = rawFullText.substring(0, i + 1)
                            displayedMainTextState = currentDisplayProgress
                            delay(TYPEWRITER_DELAY_MS_MAIN_CONTENT)
                        }
                        if (isActive && displayedMainTextState.trim() != fullMainTextTrimmed) displayedMainTextState =
                            fullMainTextTrimmed
                    } catch (e: CancellationException) {
                        if (isActive && displayedMainTextState.trim() != fullMainTextTrimmed) displayedMainTextState =
                            fullMainTextTrimmed
                    } finally {
                        if (isActive && !localAnimationTriggeredOrCompleted && displayedMainTextState.trim() == fullMainTextTrimmed && fullMainTextTrimmed.isNotBlank()) {
                            localAnimationTriggeredOrCompleted = true
                            if (!animationInitiallyPlayedByVM) viewModel.onAnimationComplete(
                                currentMessageId
                            )
                        }
                    }
                } else if (displayedMainTextState.trim() != fullMainTextTrimmed) {
                    displayedMainTextState = fullMainTextTrimmed
                    if (!localAnimationTriggeredOrCompleted && fullMainTextTrimmed.isNotEmpty()) {
                        localAnimationTriggeredOrCompleted = true
                        if (!animationInitiallyPlayedByVM) viewModel.onAnimationComplete(
                            currentMessageId
                        )
                    }
                } else if (fullMainTextTrimmed.isNotEmpty() && !localAnimationTriggeredOrCompleted) {
                    localAnimationTriggeredOrCompleted = true
                    if (!animationInitiallyPlayedByVM) viewModel.onAnimationComplete(
                        currentMessageId
                    )
                }
            } else {
                if (displayedMainTextState.isNotEmpty()) displayedMainTextState = ""
                if (!localAnimationTriggeredOrCompleted && message.contentStarted) {
                    localAnimationTriggeredOrCompleted = true
                    if (!animationInitiallyPlayedByVM) viewModel.onAnimationComplete(
                        currentMessageId
                    )
                }
            }
        } else if (isAI && !message.contentStarted && displayedMainTextState.isNotEmpty()) {
            displayedMainTextState = ""
        } else {
            if (displayedMainTextState.trim() != fullMainTextTrimmed) displayedMainTextState =
                fullMainTextTrimmed
            if (!localAnimationTriggeredOrCompleted) {
                if (fullMainTextTrimmed.isNotBlank() || !isAI || message.isError) {
                    localAnimationTriggeredOrCompleted = true
                    if (!animationInitiallyPlayedByVM) viewModel.onAnimationComplete(
                        currentMessageId
                    )
                } else if (isAI && message.contentStarted && fullMainTextTrimmed.isBlank()) {
                    localAnimationTriggeredOrCompleted = true
                    if (!animationInitiallyPlayedByVM) viewModel.onAnimationComplete(
                        currentMessageId
                    )
                }
            }
        }
    }

    // 推理文本打字机效果
    LaunchedEffect(
        currentMessageId,
        message.reasoning,
        isReasoningStreaming,
        isAI,
        message.contentStarted
    ) {
        if (isAI && message.contentStarted) {
            val fullReasoningTextTrimmed = message.reasoning?.trim() ?: ""
            if (fullReasoningTextTrimmed.isNotEmpty()) {
                val rawReasoningText = message.reasoning ?: ""
                if (isReasoningStreaming && displayedReasoningText.length < rawReasoningText.length) {
                    var currentDisplay = displayedReasoningText
                    try {
                        for (i in displayedReasoningText.length until rawReasoningText.length) {
                            if (!isActive) throw CancellationException("Reasoning typewriter for $currentMessageId cancelled (not active)")
                            currentDisplay = rawReasoningText.substring(0, i + 1)
                            displayedReasoningText = currentDisplay
                            delay(TYPEWRITER_DELAY_MS_REASONING)
                        }
                        if (isActive && displayedReasoningText.trim() != fullReasoningTextTrimmed) displayedReasoningText =
                            fullReasoningTextTrimmed
                    } catch (e: CancellationException) {
                        if (isActive && displayedReasoningText.trim() != fullReasoningTextTrimmed) displayedReasoningText =
                            fullReasoningTextTrimmed
                    }
                } else {
                    if (displayedReasoningText.trim() != fullReasoningTextTrimmed) displayedReasoningText =
                        fullReasoningTextTrimmed
                }
            } else {
                if (displayedReasoningText.isNotEmpty()) displayedReasoningText = ""
            }
        } else if (isAI && !message.contentStarted && displayedReasoningText.isNotEmpty()) {
            displayedReasoningText = ""
        } else if (!isAI && displayedReasoningText.isNotEmpty()) {
            displayedReasoningText = ""
        }
    }

    val aiBubbleColor = Color.White
    val aiContentColor = Color.Black
    val errorTextColor = Color.Red
    val userBubbleBackgroundColor = Color(0xFFF0F0F0)
    val userContentColor = Color.Black
    val codeBlockUnifiedBackgroundColor = Color(0xFFF0F0F0)
    val codeBlockUnifiedContentColor = Color.Black
    val reasoningTextColor = Color(0xFF444444)
    val codeBlockCornerRadius = 16.dp
    val reasoningBubbleColor = Color(red = 200, green = 200, blue = 200, alpha = 128)


    Column(modifier = modifier.fillMaxWidth()) {
        if (isAI && showLoadingBubble) {
            Row(
                modifier = Modifier
                    .padding(vertical = 1.dp)
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
                        Text(
                            text = "正在连接大模型...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            return@Column
        }

        val shouldShowReasoningToggle =
            isAI && message.contentStarted && (message.reasoning != null || isReasoningStreaming)
        if (shouldShowReasoningToggle) {
            ReasoningToggleAndContent(
                currentMessageId = currentMessageId,
                displayedReasoningText = displayedReasoningText,
                isReasoningStreaming = isReasoningStreaming,
                isReasoningComplete = isReasoningComplete,
                isManuallyExpanded = isManuallyExpanded,
                messageContentStarted = message.contentStarted,
                messageIsError = message.isError,
                fullReasoningTextProvider = { message.reasoning },
                onToggleReasoning = onToggleReasoning,
                reasoningBubbleColor = reasoningBubbleColor,
                reasoningTextColor = reasoningTextColor,
                reasoningToggleDotColor = aiContentColor, // Toggle dot color matches AI content
                modifier = Modifier // ReasoningToggleAndContent handles its own alignment via .align(Alignment.Start)
            )
        }

        val shouldShowMainBubbleSurface =
            !showLoadingBubble && ((isAI && message.contentStarted) || !isAI || message.isError)
        if (shouldShowMainBubbleSurface) {
            if (isAI && !message.isError) {
                AiMessageContent(
                    messageText = message.text,
                    displayedText = displayedMainTextState,
                    isStreaming = isMainContentStreaming,
                    showLoadingDots = showMainBubbleLoadingDots,
                    bubbleColor = aiBubbleColor,
                    contentColor = aiContentColor,
                    codeBlockBackgroundColor = codeBlockUnifiedBackgroundColor,
                    codeBlockContentColor = codeBlockUnifiedContentColor,
                    codeBlockCornerRadius = codeBlockCornerRadius,
                    codeBlockFixedWidth = codeBlockFixedWidth,
                    modifier = Modifier.align(Alignment.Start) // AI content is generally start aligned
                )
            } else { // User bubble OR AI Error bubble
                val actualBubbleColor =
                    if (message.isError) aiBubbleColor else userBubbleBackgroundColor
                val actualContentColor = if (message.isError) errorTextColor else userContentColor
                UserOrErrorMessageContent(
                    message = message,
                    displayedText = displayedMainTextState,
                    showLoadingDots = showMainBubbleLoadingDots && !isAI, // User bubble specific loading dots
                    bubbleColor = actualBubbleColor,
                    contentColor = actualContentColor,
                    isError = message.isError, // Pass error state for context menu logic
                    maxWidth = maxWidth,
                    onUserInteraction = onUserInteraction,
                    onEditRequest = onEditRequest,
                    onRegenerateRequest = onRegenerateRequest,
                    modifier = Modifier.align(if (isAI && message.isError) Alignment.Start else Alignment.End)
                )
            }
        }
    }
}