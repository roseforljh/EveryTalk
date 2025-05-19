package com.example.everytalk.ui.screens.BubbleMain.Main // 您的实际包名

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
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.StateControler.AppViewModel

// 确保您的 ReasoningToggleAndContent 子组件导入路径正确
// import com.example.everytalk.ui.screens.BubbleMain.Reasoning.ReasoningToggleAndContent
// 其他子组件 AiMessageContent, UserOrErrorMessageContent 也确保路径正确

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
    codeBlockFixedWidth: Dp,
    onEditRequest: (Message) -> Unit,
    onRegenerateRequest: (Message) -> Unit,
    modifier: Modifier = Modifier,
    showLoadingBubble: Boolean = false
) {
    Log.d(
        "MessageBubbleRecomp",
        "ID: ${message.id.take(8)}, Sender: ${message.sender}, TextLen: ${message.text.length}, " +
                "MainStream: $isMainContentStreaming, ReasoningStream: $isReasoningStreaming, " +
                "MsgCS: ${message.contentStarted}, Err: ${message.isError}, " +
                "ReasonLen: ${message.reasoning?.length ?: 0}, WebRes: ${message.webSearchResults?.size ?: "N/A"}, " +
                "WebStage: ${message.currentWebSearchStage}, showLoadingBubbleFlag: $showLoadingBubble"
    )

    val isAI = message.sender == Sender.AI
    val currentMessageId = message.id

    var displayedMainTextState by remember(currentMessageId, message.text) {
        mutableStateOf(message.text.trim())
    }
    var displayedReasoningText = message.reasoning?.trim() ?: ""


    LaunchedEffect(currentMessageId, message.text, showLoadingBubble) {
        if (!showLoadingBubble) {
            val fullMainTextTrimmed = message.text.trim()
            if (displayedMainTextState != fullMainTextTrimmed) {
                displayedMainTextState = fullMainTextTrimmed
            }
        } else {
            if (displayedMainTextState.isNotEmpty()) displayedMainTextState = ""
        }
    }

    LaunchedEffect(currentMessageId, message.reasoning) {
        val fullReasoningTextTrimmed = message.reasoning?.trim() ?: ""
        if (displayedReasoningText != fullReasoningTextTrimmed) {
            displayedReasoningText = fullReasoningTextTrimmed
        }
    }

    val animationInitiallyPlayedByVM =
        remember(currentMessageId) { viewModel.hasAnimationBeenPlayed(currentMessageId) }
    var localAnimationTriggeredOrCompleted by remember(currentMessageId) {
        mutableStateOf(
            animationInitiallyPlayedByVM
        )
    }

    LaunchedEffect(
        currentMessageId,
        message.text,
        message.reasoning,
        message.webSearchResults,
        message.isError,
        isMainContentStreaming,
        showLoadingBubble
    ) {
        if (showLoadingBubble) {
            return@LaunchedEffect
        }
        if (!localAnimationTriggeredOrCompleted) {
            val isStable =
                message.isError || !isMainContentStreaming || (isAI && message.contentStarted)
            val hasContent = message.text.trim().isNotBlank() ||
                    (isAI && message.reasoning?.isNotBlank() == true) ||
                    (isAI && !message.webSearchResults.isNullOrEmpty())
            if (isStable && (hasContent || message.isError)) {
                localAnimationTriggeredOrCompleted = true
                if (!animationInitiallyPlayedByVM) {
                    viewModel.onAnimationComplete(currentMessageId)
                }
            } else if (isAI && !isMainContentStreaming && message.contentStarted && message.text.isBlank() && message.reasoning.isNullOrBlank() && message.webSearchResults.isNullOrEmpty() && !message.isError) {
                localAnimationTriggeredOrCompleted = true
                if (!animationInitiallyPlayedByVM) {
                    viewModel.onAnimationComplete(currentMessageId)
                }
            }
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
        // --- 1. 显示主加载状态文本行 ---
        if (isAI && showLoadingBubble) {
            Row(
                modifier = Modifier
                    .align(Alignment.Start)
                    .wrapContentWidth()
                    .padding(vertical = 4.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = aiBubbleColor,
                    shadowElevation = 2.dp,
                    contentColor = aiContentColor
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
                        val loadingText = remember(
                            message.currentWebSearchStage,
                            message.webSearchResults,
                            message.reasoning,
                            isReasoningStreaming,
                            isReasoningComplete // << 新增依赖：确保思考完成后文本能正确更新
                        ) {
                            when (message.currentWebSearchStage) {
                                "web_indexing_started" -> "正在索引网页..."
                                "web_analysis_started" -> "正在分析网页..."
                                "web_analysis_complete" -> {
                                    if (isReasoningStreaming) { // 如果仍在流式传输思考过程
                                        "大模型思考中..."
                                    } else if (!message.reasoning.isNullOrBlank() && !isReasoningComplete) { // 有思考文本但未完成(可能暂停)
                                        "大模型思考中..." // 或者可以考虑 "思考暂停..."
                                    } else if (!message.reasoning.isNullOrBlank() && isReasoningComplete) { // 有思考文本且已完成
                                        "思考完成" // 思考结束，等待主要内容
                                    } else { // 没有思考文本，或思考文本为空且已完成
                                        "分析完成" // 网页分析完成，没有后续思考，等待主要内容
                                    }
                                }

                                else -> { // currentWebSearchStage 为 null (非联网或非常初始阶段)
                                    if (isReasoningStreaming) {
                                        "大模型思考中..."
                                    } else if (!message.reasoning.isNullOrBlank() && !isReasoningComplete) {
                                        "大模型思考中..."
                                    } else if (!message.reasoning.isNullOrBlank() && isReasoningComplete) {
                                        "思考完成"
                                    } else {
                                        "正在连接大模型..." // 默认初始状态
                                    }
                                }
                            }
                        }
                        Text(text = loadingText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } // 主加载状态文本显示结束

        // --- 2. 显示“思考过程框” (即改造后的 ReasoningToggleAndContent) ---
        val shouldDisplayReasoningComponentBox = isAI &&
                (!message.reasoning.isNullOrBlank() || isReasoningStreaming)

        if (shouldDisplayReasoningComponentBox) {
            ReasoningToggleAndContent(
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(top = if (isAI && showLoadingBubble) 6.dp else 0.dp),
                currentMessageId = currentMessageId,
                displayedReasoningText = displayedReasoningText,
                isReasoningStreaming = isReasoningStreaming,
                isReasoningComplete = isReasoningComplete,
                messageIsError = message.isError,
                // --- 修复点：正确传递 mainContentHasStarted 参数 ---
                // message.contentStarted 准确反映了主要回复文本是否已开始
                mainContentHasStarted = message.contentStarted,
                // -------------------------------------------------
                reasoningTextColor = reasoningTextColor,
                reasoningToggleDotColor = aiContentColor
            )
        }

        // --- 3. 守卫逻辑：如果仍在主加载阶段且主要文本未开始，则不渲染后续的主要内容区 ---
        if (isAI && showLoadingBubble && !message.contentStarted) {
            return@Column
        }

        // --- 4. 显示主要消息内容区 ---
        val showActualAiTextContent = isAI && !message.isError &&
                (message.contentStarted && message.text.isNotBlank()) &&
                !showLoadingBubble

        val showUserMessage = !isAI && !message.isError
        val showErrorBubble = message.isError && !showLoadingBubble

        if (showActualAiTextContent) {
            AiMessageContent(
                fullMessageTextToCopy = message.text,
                displayedText = displayedMainTextState,
                isStreaming = isMainContentStreaming,
                showLoadingDots = isAI && !showLoadingBubble && !message.isError && !message.contentStarted && message.text.isBlank() && message.reasoning.isNullOrBlank(),
                bubbleColor = aiBubbleColor,
                contentColor = aiContentColor,
                codeBlockBackgroundColor = codeBlockUnifiedBackgroundColor,
                codeBlockContentColor = codeBlockUnifiedContentColor,
                codeBlockCornerRadius = codeBlockCornerRadius,
                codeBlockFixedWidth = codeBlockFixedWidth,
                onUserInteraction = onUserInteraction,
                modifier = Modifier.align(Alignment.Start)
            )

            val showSourcesButton = !message.webSearchResults.isNullOrEmpty() &&
                    !isMainContentStreaming &&
                    !showLoadingBubble
            if (showSourcesButton) {
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = {
                        onUserInteraction()
                        viewModel.showSourcesDialog(message.webSearchResults!!)
                    },
                    modifier = Modifier
                        .align(Alignment.Start)
                        .padding(start = 8.dp, end = 8.dp)
                ) {
                    Text("查看参考来源 (${message.webSearchResults!!.size})")
                }
            }

        } else if (showUserMessage) {
            UserOrErrorMessageContent( // (参数与之前一致)
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
                modifier = Modifier.align(Alignment.End)
            )
        } else if (showErrorBubble) {
            UserOrErrorMessageContent( // (参数与之前一致)
                message = message,
                displayedText = displayedMainTextState,
                showLoadingDots = false,
                bubbleColor = aiBubbleColor,
                contentColor = errorTextColor,
                isError = true,
                maxWidth = maxWidth,
                onUserInteraction = onUserInteraction,
                onEditRequest = onEditRequest,
                onRegenerateRequest = onRegenerateRequest,
                modifier = Modifier.align(Alignment.Start)
            )
        }
    } // 根Column结束
}