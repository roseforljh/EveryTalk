package com.example.everytalk.ui.screens.BubbleMain.Main // 您的实际包名

import android.util.Log
import androidx.compose.animation.ExperimentalAnimationApi // 实验性动画API
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.everytalk.data.DataClass.Message // 确保 Message 类被正确导入
import com.example.everytalk.data.DataClass.Sender  // 确保 Sender 枚举被正确导入
import com.example.everytalk.StateControler.AppViewModel // 确保 AppViewModel 被正确导入


@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    viewModel: AppViewModel,
    isMainContentStreaming: Boolean,    // 主内容是否正在流式传输
    isReasoningStreaming: Boolean,     // 推理过程是否正在流式传输
    isReasoningComplete: Boolean,      // 推理过程是否已完成
    onUserInteraction: () -> Unit,     // 用户交互回调 (将传递给 AI 和用户气泡)
    maxWidth: Dp,                      // 气泡最大宽度
    codeBlockFixedWidth: Dp,           // 代码块固定宽度
    onEditRequest: (Message) -> Unit,  // 请求编辑消息的回调 (仅用户消息使用)
    onRegenerateRequest: (Message) -> Unit, // 请求重新生成AI回答的回调 (仅用户消息使用)
    modifier: Modifier = Modifier,
    showLoadingBubble: Boolean = false // 是否显示“连接中”的加载气泡
) {
    Log.d(
        "MessageBubbleRecomp",
        "ID: ${message.id.take(8)}, Sender: ${message.sender}, TextLen: ${message.text.length}, MainStream: $isMainContentStreaming, ReasoningStream: $isReasoningStreaming, ContentStarted: ${message.contentStarted}, Error: ${message.isError}, ReasoningTextLen: ${message.reasoning?.length ?: 0}"
    )

    val isAI = message.sender == Sender.AI
    val currentMessageId = message.id

    val animationInitiallyPlayedByVM =
        remember(currentMessageId) { viewModel.hasAnimationBeenPlayed(currentMessageId) }
    var localAnimationTriggeredOrCompleted by remember(currentMessageId, message.sender) {
        mutableStateOf(animationInitiallyPlayedByVM)
    }

    var displayedMainTextState by remember(currentMessageId, message.sender, message.text) {
        mutableStateOf(message.text.trim())
    }
    var displayedReasoningText by remember(currentMessageId, message.sender, message.reasoning) {
        mutableStateOf(
            if (isAI && message.contentStarted) message.reasoning?.trim() ?: ""
            else ""
        )
    }

    val showMainBubbleLoadingDots = isAI && !showLoadingBubble && !message.isError &&
            !message.contentStarted && (message.text.isBlank() && message.reasoning.isNullOrBlank())

    LaunchedEffect(
        currentMessageId,
        message.text,
        isAI,
        message.contentStarted,
        message.isError,
        showLoadingBubble,
        isMainContentStreaming
    ) {
        Log.d(
            "MessageBubbleDirectDisplay",
            "Effect for ID: ${currentMessageId.take(8)}. message.text length: ${message.text.length}, isMainStreaming: $isMainContentStreaming, contentStarted: ${message.contentStarted}, isError: ${message.isError}"
        )
        if (showLoadingBubble) {
            if (displayedMainTextState.isNotEmpty()) displayedMainTextState = ""
            return@LaunchedEffect
        }
        val fullMainTextTrimmed = message.text.trim()
        if (displayedMainTextState != fullMainTextTrimmed) {
            displayedMainTextState = fullMainTextTrimmed
            Log.d(
                "MessageBubbleDirectDisplay",
                "Updated displayedMainTextState for ${currentMessageId.take(8)} to: '${
                    fullMainTextTrimmed.take(50)
                }'"
            )
        }
        if (!localAnimationTriggeredOrCompleted) {
            val isStable =
                message.isError || !isMainContentStreaming || (isAI && message.contentStarted)
            val hasContent =
                fullMainTextTrimmed.isNotBlank() || (isAI && message.reasoning?.isNotBlank() == true)
            if (isStable && (hasContent || message.isError)) {
                Log.d(
                    "MessageBubbleAnimation",
                    "Animation marked complete for ${currentMessageId.take(8)} because stable and has content/error."
                )
                localAnimationTriggeredOrCompleted = true
                if (!animationInitiallyPlayedByVM) {
                    viewModel.onAnimationComplete(currentMessageId)
                }
            } else if (isAI && !isMainContentStreaming && message.contentStarted && fullMainTextTrimmed.isBlank() && message.reasoning.isNullOrBlank() && !message.isError) {
                Log.d(
                    "MessageBubbleAnimation",
                    "Animation marked complete for ${currentMessageId.take(8)} (AI finished with empty content)."
                )
                localAnimationTriggeredOrCompleted = true
                if (!animationInitiallyPlayedByVM) {
                    viewModel.onAnimationComplete(currentMessageId)
                }
            }
        }
    }

    LaunchedEffect(currentMessageId, message.reasoning, isAI, message.contentStarted) {
        if (isAI && message.contentStarted) {
            val fullReasoningTextTrimmed = message.reasoning?.trim() ?: ""
            if (displayedReasoningText != fullReasoningTextTrimmed) {
                displayedReasoningText = fullReasoningTextTrimmed
            }
        } else if (displayedReasoningText.isNotEmpty()) {
            displayedReasoningText = ""
        }
    }

    val aiBubbleColor = Color.White
    val aiContentColor = Color.Black
    val errorTextColor = Color.Red
    val userBubbleBackgroundColor = Color(0xFFF3F3F3)
    val userContentColor = Color.Black
    val codeBlockUnifiedBackgroundColor = Color(0xFFF0F0F0)
    val codeBlockUnifiedContentColor = Color.Black
    val reasoningTextColor = Color(0xFF444444)
    val codeBlockCornerRadius = 16.dp

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isAI) Alignment.Start else Alignment.End
    ) {
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
                        Text("正在连接大模型...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            return@Column
        }

        val shouldShowReasoningComponents = isAI && message.contentStarted &&
                (displayedReasoningText.isNotBlank() || isReasoningStreaming || viewModel.expandedReasoningStates[currentMessageId] == true)

        // --- 新增日志 ---
        Log.d(
            "MsgBubbleReasoning", "ID: ${message.id.take(8)}, " +
                    "shouldShowReasoningComponents: $shouldShowReasoningComponents, " +
                    "isAI: $isAI, contentStarted: ${message.contentStarted}, " +
                    "displayedReasoningText.isNotBlank: ${displayedReasoningText.isNotBlank()}, " +
                    "isReasoningStreaming: $isReasoningStreaming, " +
                    "expanded: ${viewModel.expandedReasoningStates[currentMessageId]}"
        )
        // --- 新增日志结束 ---

        if (shouldShowReasoningComponents) {
            ReasoningToggleAndContent(
                currentMessageId = currentMessageId,
                displayedReasoningText = displayedReasoningText,
                isReasoningStreaming = isReasoningStreaming,
                isReasoningComplete = isReasoningComplete,
                messageContentStarted = message.contentStarted,
                messageIsError = message.isError,
                reasoningTextColor = reasoningTextColor,
                reasoningToggleDotColor = aiContentColor,
                modifier = Modifier.align(Alignment.Start)
            )
        }

        val shouldShowMainBubbleSurface = !showLoadingBubble &&
                ((isAI && message.contentStarted) || !isAI || message.isError || (isAI && !message.contentStarted && (message.text.isNotBlank() || !message.reasoning.isNullOrBlank())))

        if (shouldShowMainBubbleSurface) {
            if (isAI && !message.isError) {
                AiMessageContent(
                    fullMessageTextToCopy = message.text,
                    displayedText = displayedMainTextState,
                    isStreaming = isMainContentStreaming,
                    showLoadingDots = showMainBubbleLoadingDots,
                    bubbleColor = aiBubbleColor,
                    contentColor = aiContentColor,
                    codeBlockBackgroundColor = codeBlockUnifiedBackgroundColor,
                    codeBlockContentColor = codeBlockUnifiedContentColor,
                    codeBlockCornerRadius = codeBlockCornerRadius,
                    codeBlockFixedWidth = codeBlockFixedWidth,
                    onUserInteraction = onUserInteraction,
                    modifier = Modifier.align(Alignment.Start)
                )
            } else {
                val actualBubbleColor =
                    if (message.isError) aiBubbleColor else userBubbleBackgroundColor
                val actualContentColor = if (message.isError) errorTextColor else userContentColor
                UserOrErrorMessageContent(
                    message = message,
                    displayedText = displayedMainTextState,
                    showLoadingDots = showMainBubbleLoadingDots && !isAI,
                    bubbleColor = actualBubbleColor,
                    contentColor = actualContentColor,
                    isError = message.isError,
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