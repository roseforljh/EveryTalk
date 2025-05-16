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
        "MessageBubbleRecomp", // 消息气泡重组日志标签
        "ID: ${message.id.take(8)}, Sender: ${message.sender}, TextLen: ${message.text.length}, MainStream: $isMainContentStreaming, ReasoningStream: $isReasoningStreaming, ContentStarted: ${message.contentStarted}, Error: ${message.isError}"
    )

    val isAI = message.sender == Sender.AI // 判断是否为AI发送的消息
    val currentMessageId = message.id      // 当前消息ID

    // 从ViewModel中获取此消息的动画是否已经播放过的状态
    val animationInitiallyPlayedByVM =
        remember(currentMessageId) { viewModel.hasAnimationBeenPlayed(currentMessageId) }
    // 本地状态，用于追踪动画是否被触发或已完成（结合ViewModel的状态）
    var localAnimationTriggeredOrCompleted by remember(currentMessageId, message.sender) {
        mutableStateOf(animationInitiallyPlayedByVM)
    }

    // 用于显示的主文本状态，依赖于原始消息的 message.text
    var displayedMainTextState by remember(currentMessageId, message.sender, message.text) {
        mutableStateOf(message.text.trim())
    }
    // 用于显示的推理文本状态，依赖于原始消息的 message.reasoning
    var displayedReasoningText by remember(currentMessageId, message.sender, message.reasoning) {
        mutableStateOf(
            if (isAI && message.contentStarted) message.reasoning?.trim()
                ?: "" // AI消息且内容已开始，则显示推理，否则为空
            else ""
        )
    }

    // 是否在AI气泡主内容区域显示加载点（三个小点动画）
    val showMainBubbleLoadingDots = isAI && !showLoadingBubble && !message.isError &&
            !message.contentStarted && (message.text.isBlank() && message.reasoning.isNullOrBlank())

    // 主内容直接显示逻辑的副作用处理
    LaunchedEffect(
        currentMessageId, message.text,
        isAI, message.contentStarted, message.isError, showLoadingBubble, isMainContentStreaming
    ) {
        Log.d(
            "MessageBubbleDirectDisplay",
            "Effect for ID: ${currentMessageId.take(8)}. " + // 日志：主内容显示逻辑触发
                    "message.text length: ${message.text.length}, " +
                    "isMainStreaming: $isMainContentStreaming, contentStarted: ${message.contentStarted}, isError: ${message.isError}"
        )

        if (showLoadingBubble) { // 如果显示的是“连接中”的加载气泡
            if (displayedMainTextState.isNotEmpty()) displayedMainTextState = "" // 清空已显示的文本
            return@LaunchedEffect // 不再执行后续逻辑
        }

        val fullMainTextTrimmed = message.text.trim() // 获取并裁剪完整的原始主文本
        if (displayedMainTextState != fullMainTextTrimmed) { // 如果当前显示的文本与最新文本不同
            displayedMainTextState = fullMainTextTrimmed // 更新显示的文本状态
            Log.d(
                "MessageBubbleDirectDisplay", // 日志：更新了 displayedMainTextState
                "Updated displayedMainTextState for ${currentMessageId.take(8)} to: '${
                    fullMainTextTrimmed.take(
                        50
                    )
                }'"
            )
        }

        if (!localAnimationTriggeredOrCompleted) { // 如果动画尚未完成或触发
            val isStable =
                message.isError || !isMainContentStreaming || (isAI && message.contentStarted)
            val hasContent =
                fullMainTextTrimmed.isNotBlank() || (isAI && message.reasoning?.isNotBlank() == true)

            if (isStable && (hasContent || message.isError)) { // 稳定且有内容或出错
                Log.d(
                    "MessageBubbleAnimation",
                    "Animation marked complete for ${currentMessageId.take(8)} because stable and has content/error."
                ) // 日志：动画标记完成 - 稳定且有内容/错误
                localAnimationTriggeredOrCompleted = true // 标记本地动画已完成
                if (!animationInitiallyPlayedByVM) { // 如果ViewModel中记录的动画未播放
                    viewModel.onAnimationComplete(currentMessageId) // 通知ViewModel动画已完成
                }
            } else if (isAI && !isMainContentStreaming && message.contentStarted && fullMainTextTrimmed.isBlank() && message.reasoning.isNullOrBlank() && !message.isError) {
                Log.d(
                    "MessageBubbleAnimation",
                    "Animation marked complete for ${currentMessageId.take(8)} (AI finished with empty content)."
                ) // 日志：动画标记完成 - AI完成但内容为空
                localAnimationTriggeredOrCompleted = true
                if (!animationInitiallyPlayedByVM) {
                    viewModel.onAnimationComplete(currentMessageId)
                }
            }
        }
    }

    // 推理文本更新逻辑的副作用处理
    LaunchedEffect(currentMessageId, message.reasoning, isAI, message.contentStarted) {
        if (isAI && message.contentStarted) { // 如果是AI消息且内容已开始
            val fullReasoningTextTrimmed = message.reasoning?.trim() ?: "" // 获取并裁剪推理文本
            if (displayedReasoningText != fullReasoningTextTrimmed) { // 如果显示的推理文本与最新不同
                displayedReasoningText = fullReasoningTextTrimmed // 更新状态
            }
        } else if (displayedReasoningText.isNotEmpty()) { // 如果不是AI或内容未开始，但当前显示了推理文本，则清空
            displayedReasoningText = ""
        }
    }

    // 定义颜色常量
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

        val shouldShowReasoningComponents =
            isAI && message.contentStarted && (displayedReasoningText.isNotBlank() || isReasoningStreaming || viewModel.expandedReasoningStates[currentMessageId] == true)

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

        val shouldShowMainBubbleSurface =
            !showLoadingBubble && ((isAI && message.contentStarted) || !isAI || message.isError || (isAI && !message.contentStarted && (message.text.isNotBlank() || !message.reasoning.isNullOrBlank())))

        if (shouldShowMainBubbleSurface) {
            if (isAI && !message.isError) {
                AiMessageContent(
                    fullMessageTextToCopy = message.text, // <-- 传递完整的原始文本用于复制
                    displayedText = displayedMainTextState,
                    isStreaming = isMainContentStreaming,
                    showLoadingDots = showMainBubbleLoadingDots,
                    bubbleColor = aiBubbleColor,
                    contentColor = aiContentColor,
                    codeBlockBackgroundColor = codeBlockUnifiedBackgroundColor,
                    codeBlockContentColor = codeBlockUnifiedContentColor,
                    codeBlockCornerRadius = codeBlockCornerRadius,
                    codeBlockFixedWidth = codeBlockFixedWidth,
                    onUserInteraction = onUserInteraction, // <-- 传递交互回调
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
                    onUserInteraction = onUserInteraction, // 用户消息也接收此回调
                    onEditRequest = onEditRequest,
                    onRegenerateRequest = onRegenerateRequest,
                    modifier = Modifier.align(if (isAI && message.isError) Alignment.Start else Alignment.End)
                )
            }
        }
    }
}