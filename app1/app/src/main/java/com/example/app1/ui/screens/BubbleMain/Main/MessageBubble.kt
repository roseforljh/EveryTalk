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
import com.example.app1.StateControler.AppViewModel
import com.example.app1.data.DataClass.Message
import com.example.app1.data.DataClass.Sender

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    viewModel: AppViewModel,
    isMainContentStreaming: Boolean, // 仍然可以用来判断是否正在接收，以显示加载指示等
    isReasoningStreaming: Boolean,
    isReasoningComplete: Boolean,
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
        "ID: ${message.id.take(8)}, Sender: ${message.sender}, TextLen: ${message.text.length}, MainStream: $isMainContentStreaming, ReasoningStream: $isReasoningStreaming, ContentStarted: ${message.contentStarted}, Error: ${message.isError}"
    )

    val isAI = message.sender == Sender.AI
    val currentMessageId = message.id

    val animationInitiallyPlayedByVM =
        remember(currentMessageId) { viewModel.hasAnimationBeenPlayed(currentMessageId) }
    var localAnimationTriggeredOrCompleted by remember(currentMessageId, message.sender) {
        mutableStateOf(animationInitiallyPlayedByVM)
    }

    // displayedMainTextState 将直接反映 message.text (trimmed)
    var displayedMainTextState by remember(currentMessageId, message.sender) {
        mutableStateOf(message.text.trim()) // 初始化为当前 message.text
    }
    var displayedReasoningText by remember(currentMessageId, message.sender) {
        mutableStateOf(
            if (isAI && message.contentStarted) message.reasoning?.trim() ?: ""
            else ""
        )
    }

    val showMainBubbleLoadingDots = isAI && !showLoadingBubble && !message.isError &&
            !message.contentStarted && (message.text.isBlank() && message.reasoning.isNullOrBlank())

    // 主内容更新逻辑 - 直接显示，取消打字机
    LaunchedEffect(
        currentMessageId, message.text, // 主要依赖 message.text 的变化
        isAI, message.contentStarted, message.isError, showLoadingBubble
    ) {
        Log.d(
            "MessageBubbleDirectDisplay", "Effect launched for ID: ${currentMessageId.take(8)}. " +
                    "message.text length: ${message.text.length}, " +
                    "isStreaming: $isMainContentStreaming, contentStarted: ${message.contentStarted}, isError: ${message.isError}"
        )

        if (showLoadingBubble) {
            if (displayedMainTextState.isNotEmpty()) displayedMainTextState = ""
            return@LaunchedEffect
        }

        val fullMainTextTrimmed = message.text.trim()

        // 直接更新 displayedMainTextState 为最新的 message.text (trimmed)
        if (displayedMainTextState != fullMainTextTrimmed) {
            displayedMainTextState = fullMainTextTrimmed
            Log.d(
                "MessageBubbleDirectDisplay",
                "Updated displayedMainTextState for ${currentMessageId.take(8)} to: '${
                    fullMainTextTrimmed.take(50)
                }'"
            )
        }

        // 动画完成标记逻辑 (当文本最终确定，并且非空或内容已开始或错误时)
        if (!localAnimationTriggeredOrCompleted) {
            if (message.isError || !isAI || (isAI && message.contentStarted && !isMainContentStreaming)) {
                // 如果是错误，或者不是AI，或者AI内容已开始且流已结束
                if (fullMainTextTrimmed.isNotBlank() || message.isError || (isAI && message.contentStarted && fullMainTextTrimmed.isBlank() && !isMainContentStreaming)) {
                    // 有文本，或者是错误，或者是AI回复完成但为空
                    Log.d(
                        "MessageBubbleDirectDisplay",
                        "Animation marked complete for ${currentMessageId.take(8)}"
                    )
                    localAnimationTriggeredOrCompleted = true
                    if (!animationInitiallyPlayedByVM) viewModel.onAnimationComplete(
                        currentMessageId
                    )
                }
            }
        }
    }

    // 推理文本更新逻辑 (保持不变)
    LaunchedEffect(currentMessageId, message.reasoning, isAI, message.contentStarted) {
        if (isAI && message.contentStarted) {
            val fullReasoningTextTrimmed = message.reasoning?.trim() ?: ""
            if (displayedReasoningText != fullReasoningTextTrimmed) {
                displayedReasoningText = fullReasoningTextTrimmed
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
                        Text("正在连接大模型...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            return@Column
        }

        val shouldShowReasoningComponents =
            isAI && message.contentStarted && (displayedReasoningText.isNotBlank() || isReasoningStreaming)

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
                modifier = Modifier
            )
        }

        val shouldShowMainBubbleSurface =
            !showLoadingBubble && ((isAI && message.contentStarted) || !isAI || message.isError)

        if (shouldShowMainBubbleSurface) {
            if (isAI && !message.isError) {
                AiMessageContent(
                    messageText = message.text, // 传递原始文本供 Markdown 解析
                    displayedText = displayedMainTextState, // 直接显示处理后的文本
                    isStreaming = isMainContentStreaming, // 用于UI指示，例如省略号或其他加载状态
                    showLoadingDots = showMainBubbleLoadingDots,
                    bubbleColor = aiBubbleColor,
                    contentColor = aiContentColor,
                    codeBlockBackgroundColor = codeBlockUnifiedBackgroundColor,
                    codeBlockContentColor = codeBlockUnifiedContentColor,
                    codeBlockCornerRadius = codeBlockCornerRadius,
                    codeBlockFixedWidth = codeBlockFixedWidth,
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
