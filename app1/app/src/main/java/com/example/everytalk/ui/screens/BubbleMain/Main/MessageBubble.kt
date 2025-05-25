package com.example.everytalk.ui.screens.BubbleMain.Main // 你的包名

import android.util.Log
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
    maxWidth: Dp,
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

    val aiMessageBlockMaxWidth = maxWidth

    Log.d(
        "MessageBubbleRecomp",
        "ID: ${message.id.take(8)}, Sender: ${message.sender}, AI_Block_MaxWidth: $aiMessageBlockMaxWidth, showLoadingBubble: $showLoadingBubble, isMainStreaming: $isMainContentStreaming, msg.textBlank: ${message.text.isBlank()}, contentStarted: ${message.contentStarted}"
    )

    val isAI = message.sender == Sender.AI
    val currentMessageId = message.id

    val displayedMainTextForUserOrError = remember(message.text) { message.text.trim() }
    val displayedReasoningText = remember(message.reasoning) { message.reasoning?.trim() ?: "" }

    val animationInitiallyPlayedByVM =
        remember(currentMessageId) { viewModel.hasAnimationBeenPlayed(currentMessageId) }
    var localAnimationTriggeredOrCompleted by remember(currentMessageId) {
        mutableStateOf(animationInitiallyPlayedByVM)
    }

    LaunchedEffect(
        currentMessageId, message.text, message.reasoning, message.webSearchResults,
        message.isError, isMainContentStreaming, showLoadingBubble, message.contentStarted
    ) {
        if (showLoadingBubble) return@LaunchedEffect
        if (!localAnimationTriggeredOrCompleted) {
            val isStable =
                message.isError || !isMainContentStreaming || (isAI && message.contentStarted)
            val hasContent = message.text.trim().isNotBlank() ||
                    (isAI && message.reasoning?.isNotBlank() == true) ||
                    (isAI && !message.webSearchResults.isNullOrEmpty())
            if (isStable && (hasContent || message.isError)) {
                localAnimationTriggeredOrCompleted = true
                if (!animationInitiallyPlayedByVM) viewModel.onAnimationComplete(currentMessageId)
            } else if (isAI && !isMainContentStreaming && message.contentStarted &&
                message.text.isBlank() && message.reasoning.isNullOrBlank() &&
                message.webSearchResults.isNullOrEmpty() && !message.isError
            ) {
                localAnimationTriggeredOrCompleted = true
                if (!animationInitiallyPlayedByVM) viewModel.onAnimationComplete(currentMessageId)
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isAI) Alignment.Start else Alignment.End
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = aiMessageBlockMaxWidth)
                .then(if (!isAI) Modifier.align(Alignment.End) else Modifier.align(Alignment.Start)),
            horizontalAlignment = Alignment.Start
        ) {
            if (isAI && showLoadingBubble) {
                // ***** 在这里进行修改：添加一个Box来包裹Surface，并给Box设置左边距 *****
                Box(
                    modifier = Modifier.padding(start = 16.dp) // 例如，左边距16.dp，您可以按需调整
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp), // 您设置的圆角
                        color = aiBubbleColor,
                        shadowElevation = 4.dp, // 您设置的阴影
                        contentColor = aiContentColor,
                        modifier = Modifier
                            .fillMaxWidth(0.42f) // Surface宽度现在是其父Box(已padding)宽度的0.4倍
                            .padding(vertical = 4.dp) // Surface自身的垂直padding
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 8.dp), // 您设置的内边距
                            horizontalAlignment = Alignment.Start
                        ) {
                            val loadingText = remember(
                                message.currentWebSearchStage,
                                isReasoningStreaming,
                                message.reasoning,
                                isReasoningComplete
                            ) {
                                when (message.currentWebSearchStage) {
                                    "web_indexing_started" -> "正在索引网页..."
                                    "web_analysis_started" -> "正在分析网页..."
                                    "web_analysis_complete" -> {
                                        if (isReasoningStreaming) "大模型思考中..."
                                        else if (!message.reasoning.isNullOrBlank() && !isReasoningComplete) "大模型思考中..."
                                        else if (!message.reasoning.isNullOrBlank() && isReasoningComplete) "思考完成"
                                        else "分析完成"
                                    }

                                    else -> {
                                        if (isReasoningStreaming) "大模型思考中..."
                                        else if (!message.reasoning.isNullOrBlank() && !isReasoningComplete) "大模型思考中..."
                                        else if (!message.reasoning.isNullOrBlank() && isReasoningComplete) "思考完成"
                                        else "正在连接大模型..."
                                    }
                                }
                            }
                            Text(
                                text = loadingText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = aiContentColor
                            )
                            Spacer(Modifier.height(5.dp)) // 您设置的间距
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth() // 进度条充满其父Column的宽度
                                    .graphicsLayer {
                                        scaleY = 0.5f;  // 您设置的厚度
                                    },
                                color = Color.Black, // 您设置的颜色
                                trackColor = Color(0xffd0d0d0) // 您设置的轨道颜色
                            )
                        }
                    }
                }
                // ***** 修改结束 *****
            }

            val shouldDisplayReasoningComponentBox =
                isAI && (!message.reasoning.isNullOrBlank() || isReasoningStreaming)
            if (shouldDisplayReasoningComponentBox) {
                ReasoningToggleAndContent( // 您的实际组件
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

            val shouldShowAiMessageComponent = isAI && !message.isError && !showLoadingBubble
            if (shouldShowAiMessageComponent) {
                val showDotsInsideAiContent = isMainContentStreaming &&
                        message.text.isBlank() &&
                        !message.contentStarted &&
                        !showLoadingBubble

                if (message.text.isNotBlank() || (message.contentStarted && message.text.isBlank()) || showDotsInsideAiContent) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(
                            topStart = if (shouldDisplayReasoningComponentBox) 8.dp else 18.dp,
                            topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp
                        ),
                        color = aiBubbleColor, contentColor = aiContentColor,
                        shadowElevation = 0.dp,
                    ) {
                        AiMessageContent(
                            message = message,
                            appViewModel = viewModel,
                            fullMessageTextToCopy = message.text,
                            showLoadingDots = showDotsInsideAiContent,
                            contentColor = aiContentColor,
                            onUserInteraction = onUserInteraction,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 0.dp)
                        )
                    }
                } else if (message.contentStarted && message.text.isBlank() && isMainContentStreaming) {
                    // Fallback or placeholder if needed
                }

                val showSourcesButton = !message.webSearchResults.isNullOrEmpty() &&
                        message.contentStarted &&
                        !isMainContentStreaming &&
                        !showLoadingBubble
                if (showSourcesButton) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            onUserInteraction()
                            viewModel.showSourcesDialog(message.webSearchResults!!)
                        },
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Text("查看参考来源 (${message.webSearchResults?.size ?: 0})")
                    }
                }

                val showStreamingDotsBelowMainContent = message.contentStarted &&
                        isMainContentStreaming &&
                        !message.isError

                if (showStreamingDotsBelowMainContent) {
                    Spacer(Modifier.height(8.dp))
                    ThreeDotsWaveAnimation( // 您的实际组件
                        modifier = Modifier
                            .padding(start = 12.dp, bottom = 4.dp)
                            .align(Alignment.Start),
                        dotColor = aiContentColor,
                        dotSize = 7.dp,
                        spacing = 5.dp
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        // 用户消息 或 AI错误消息
        if (!isAI && !message.isError) {
            UserOrErrorMessageContent(
                message = message,
                displayedText = displayedMainTextForUserOrError,
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
                    displayedText = displayedMainTextForUserOrError,
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
    }
}