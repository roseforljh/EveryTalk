package com.example.everytalk.ui.screens.BubbleMain.Main

import android.util.Log
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.StateControler.AppViewModel
import com.example.everytalk.ui.screens.BubbleMain.AiMessageContent

fun Color.toHexCss(): String {
    return String.format("#%06X", 0xFFFFFF and this.toArgb())
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    viewModel: AppViewModel,
    isMainContentStreaming: Boolean,
    isReasoningStreaming: Boolean,
    isReasoningComplete: Boolean,
    onUserInteraction: () -> Unit,
    maxWidth: Dp, // 这个是 ChatScreen 传来的 bubbleMaxWidth，AI 消息块的最大宽度
    onEditRequest: (Message) -> Unit,
    onRegenerateRequest: (Message) -> Unit,
    modifier: Modifier = Modifier,
    showLoadingBubble: Boolean = false
) {
    val aiBubbleColor = Color.White
    val aiContentColor = Color.Black
    val errorTextColor = Color.Red
    val userBubbleBackgroundColor = Color(0xFFF3F3F3)
    val userContentColor = Color.Black
    val reasoningTextColor = Color(0xFF444444)
    val codeBlockBackgroundColor = Color(0xFF2B2B2B)
    val codeBlockContentColor = Color(0xFFA9B7C6)
    val codeBlockCornerRadius = 8.dp

    val aiMessageBlockMaxWidth = maxWidth

    Log.d(
        "MessageBubbleRecomp",
        "ID: ${message.id.take(8)}, Sender: ${message.sender}, AI_Block_MaxWidth: $aiMessageBlockMaxWidth"
    )

    val isAI = message.sender == Sender.AI
    val currentMessageId = message.id

    // ✅ 直接绑定原始文本字段，不再使用 remember + mutableStateOf 缓存
    val displayedMainTextState = remember(message.text) { message.text.trim() }
    val displayedReasoningText = remember(message.reasoning) { message.reasoning?.trim() ?: "" }

    val animationInitiallyPlayedByVM =
        remember(currentMessageId) { viewModel.hasAnimationBeenPlayed(currentMessageId) }
    var localAnimationTriggeredOrCompleted by remember(currentMessageId) {
        mutableStateOf(animationInitiallyPlayedByVM)
    }

    LaunchedEffect(
        currentMessageId, message.text, message.reasoning, message.webSearchResults,
        message.isError, isMainContentStreaming, showLoadingBubble
    ) {
        if (showLoadingBubble) return@LaunchedEffect
        if (!localAnimationTriggeredOrCompleted) {
            val isStable = message.isError || !isMainContentStreaming || (isAI && message.contentStarted)
            val hasContent = message.text.trim().isNotBlank() ||
                    (isAI && message.reasoning?.isNotBlank() == true) ||
                    (isAI && !message.webSearchResults.isNullOrEmpty())
            if (isStable && (hasContent || message.isError)) {
                localAnimationTriggeredOrCompleted = true
                if (!animationInitiallyPlayedByVM) viewModel.onAnimationComplete(currentMessageId)
            } else if (isAI && !isMainContentStreaming && message.contentStarted && message.text.isBlank() && message.reasoning.isNullOrBlank() && message.webSearchResults.isNullOrEmpty() && !message.isError) {
                localAnimationTriggeredOrCompleted = true
                if (!animationInitiallyPlayedByVM) viewModel.onAnimationComplete(currentMessageId)
            }
        }
    }

    // 根 Column：AI消息整体靠左，用户消息整体靠右
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isAI) Alignment.Start else Alignment.End
    ) {
        // 内部 Column 用于包裹 AI 的所有组件，并应用 widthIn(max) 约束
        Column(
            modifier = Modifier
                .widthIn(max = aiMessageBlockMaxWidth) // 【关键】AI 内容块的最大宽度
                .then(if (!isAI) Modifier.align(Alignment.End) else Modifier.align(Alignment.Start)),
            horizontalAlignment = Alignment.Start // AI 组件在其块内部左对齐
        ) {
            if (isAI && showLoadingBubble) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = aiBubbleColor,
                    shadowElevation = 0.dp,
                    contentColor = aiContentColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = aiContentColor,
                            strokeWidth = 1.5.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        val loadingText = remember {
                            when (message.currentWebSearchStage) {
                                "web_indexing_started" -> "正在索引网页..."
                                "web_analysis_started" -> "正在分析网页..."
                                "web_analysis_complete" -> {
                                    if (isReasoningStreaming) {
                                        "大模型思考中..."
                                    } else if (!message.reasoning.isNullOrBlank() && !isReasoningComplete) {
                                        "大模型思考中..."
                                    } else if (!message.reasoning.isNullOrBlank() && isReasoningComplete) {
                                        "思考完成"
                                    } else {
                                        "分析完成"
                                    }
                                }
                                else -> {
                                    if (isReasoningStreaming) {
                                        "大模型思考中..."
                                    } else if (!message.reasoning.isNullOrBlank() && !isReasoningComplete) {
                                        "大模型思考中..."
                                    } else if (!message.reasoning.isNullOrBlank() && isReasoningComplete) {
                                        "思考完成"
                                    } else {
                                        "正在连接大模型..."
                                    }
                                }
                            }
                        }
                        Text(text = loadingText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            val shouldDisplayReasoningComponentBox = isAI &&
                    (!message.reasoning.isNullOrBlank() || isReasoningStreaming)

            if (shouldDisplayReasoningComponentBox) {
                ReasoningToggleAndContent(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            top = if (isAI && showLoadingBubble) 6.dp else 0.dp,
                            bottom = 4.dp
                        ),
                    currentMessageId = currentMessageId,
                    displayedReasoningText = displayedReasoningText,
                    isReasoningStreaming = isReasoningStreaming,
                    isReasoningComplete = isReasoningComplete,
                    messageIsError = message.isError,
                    mainContentHasStarted = message.contentStarted,
                    reasoningTextColor = reasoningTextColor,
                    reasoningToggleDotColor = aiContentColor
                )
            }

            if (isAI && showLoadingBubble && !message.contentStarted) {
                // Skip rendering main content
            }

            val showActualAiTextContent = isAI && !message.isError &&
                    (message.contentStarted && message.text.isNotBlank()) &&
                    !showLoadingBubble

            if (showActualAiTextContent) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(
                        topStart = if (shouldDisplayReasoningComponentBox) 8.dp else 18.dp,
                        topEnd = 18.dp,
                        bottomStart = 18.dp,
                        bottomEnd = 18.dp
                    ),
                    color = aiBubbleColor,
                    contentColor = aiContentColor,
                    shadowElevation = 0.dp,
                    border = null
                ) {
                    AiMessageContent(
                        message = message,
                        appViewModel = viewModel,
                        fullMessageTextToCopy = message.text,
                        displayedText = displayedMainTextState,
                        isStreaming = isMainContentStreaming,
                        showLoadingDots = isAI && !showLoadingBubble && !message.isError && !message.contentStarted && message.text.isBlank() && message.reasoning.isNullOrBlank(),
                        contentColor = aiContentColor,
                        codeBlockBackgroundColor = codeBlockBackgroundColor,
                        codeBlockContentColor = codeBlockContentColor,
                        codeBlockCornerRadius = codeBlockCornerRadius,
                        onUserInteraction = onUserInteraction,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }

                val showSourcesButton = !message.webSearchResults.isNullOrEmpty() &&
                        !isMainContentStreaming &&
                        !showLoadingBubble
                if (showSourcesButton) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            onUserInteraction()
                            message.webSearchResults?.let { results ->
                                viewModel.showSourcesDialog(results)
                            }
                        },
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Text("查看参考来源 (${message.webSearchResults?.size ?: 0})")
                    }
                }
            }
        } // AI 消息内部 Column 结束

        // 用户消息和错误消息（直接在根Column下，由其Alignment控制）
        if (!isAI && !message.isError) {
            UserOrErrorMessageContent(
                message = message,
                displayedText = displayedMainTextState,
                showLoadingDots = false,
                bubbleColor = userBubbleBackgroundColor,
                contentColor = userContentColor,
                isError = false,
                maxWidth = maxWidth,
                onUserInteraction = onUserInteraction,
                onEditRequest = onEditRequest,
                onRegenerateRequest = onRegenerateRequest,
                modifier = Modifier
            )
        } else if (message.isError && !showLoadingBubble) {
            Column(
                modifier = Modifier
                    .widthIn(max = aiMessageBlockMaxWidth)
                    .align(Alignment.Start),
                horizontalAlignment = Alignment.Start
            ) {
                UserOrErrorMessageContent(
                    message = message,
                    displayedText = displayedMainTextState,
                    showLoadingDots = false,
                    bubbleColor = aiBubbleColor,
                    contentColor = errorTextColor,
                    isError = true,
                    maxWidth = aiMessageBlockMaxWidth,
                    onUserInteraction = onUserInteraction,
                    onEditRequest = onEditRequest,
                    onRegenerateRequest = onRegenerateRequest,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    } // 根 Column 结束
}
