package com.example.everytalk.ui.screens.BubbleMain.Main

import android.util.Log
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.background // 确保导入，因为 ThreeDotsWaveAnimation 占位符使用
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage // Coil 导入
import coil3.request.ImageRequest // Coil 导入
import coil3.request.crossfade
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.StateControler.AppViewModel
import com.example.everytalk.ui.screens.BubbleMain.AiMessageContent // 你的 AI 内容组件

fun Color.toHexCss(): String {
    return String.format("#%06X", 0xFFFFFF and this.toArgb())
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MessageBubble(
    message: Message, viewModel: AppViewModel,
    isMainContentStreaming: Boolean, isReasoningStreaming: Boolean, isReasoningComplete: Boolean,
    onUserInteraction: () -> Unit, maxWidth: Dp,
    onEditRequest: (Message) -> Unit, onRegenerateRequest: (Message) -> Unit,
    modifier: Modifier = Modifier, showLoadingBubble: Boolean = false
) {
    // --- 保持你原有的颜色和样式定义 ---
    val aiBubbleColor = Color.White
    val aiContentColor = Color.Black
    val errorTextColor = Color.Red
    val userBubbleBackgroundColor = Color(0xFFF3F3F3)
    val userContentColor = Color.Black
    val reasoningTextColor = Color(0xFF444444)
    // --- 样式定义结束 ---

    val aiMessageBlockMaxWidth = maxWidth
    val userMessageBlockMaxWidth = maxWidth * 0.85f // 用户消息块的最大宽度

    Log.d(
        "MessageBubbleRecomp",
        "ID: ${message.id.take(8)}, Sender: ${message.sender}, MaxWidth: $maxWidth, UserMsgMaxWidth: $userMessageBlockMaxWidth, ImageUrls: ${message.imageUrls?.size ?: 0}"
    )

    val isAI = message.sender == Sender.AI
    val currentMessageId = message.id
    val displayedMainTextForUserOrError = remember(message.text) { message.text.trim() }
    val displayedReasoningText = remember(message.reasoning) { message.reasoning?.trim() ?: "" }

    // --- 动画状态逻辑 (保持不变) ---
    val animationInitiallyPlayedByVM =
        remember(currentMessageId) { viewModel.hasAnimationBeenPlayed(currentMessageId) }
    var localAnimationTriggeredOrCompleted by remember(currentMessageId) {
        mutableStateOf(
            animationInitiallyPlayedByVM
        )
    }
    LaunchedEffect(
        currentMessageId, message.text, message.reasoning, message.webSearchResults,
        message.isError, isMainContentStreaming, showLoadingBubble, message.contentStarted
    ) {
        if (showLoadingBubble) return@LaunchedEffect
        if (!localAnimationTriggeredOrCompleted) {
            val isStable =
                message.isError || !isMainContentStreaming || (isAI && message.contentStarted)
            val hasContent = message.text.trim()
                .isNotBlank() || (isAI && message.reasoning?.isNotBlank() == true) || (isAI && !message.webSearchResults.isNullOrEmpty())
            if (isStable && (hasContent || message.isError)) {
                localAnimationTriggeredOrCompleted = true
                if (!animationInitiallyPlayedByVM) viewModel.onAnimationComplete(currentMessageId)
            } else if (isAI && !isMainContentStreaming && message.contentStarted && message.text.isBlank() && message.reasoning.isNullOrBlank() && message.webSearchResults.isNullOrEmpty() && !message.isError) {
                localAnimationTriggeredOrCompleted = true
                if (!animationInitiallyPlayedByVM) viewModel.onAnimationComplete(currentMessageId)
            }
        }
    }
    // --- 动画状态逻辑结束 ---

    Column( // 最外层 Column，控制整体左右对齐
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isAI) Alignment.Start else Alignment.End
    ) {
        // --- 用户发送的图片显示区域 (在文本气泡上方) ---
        if (!isAI && !message.imageUrls.isNullOrEmpty()) {
            Column( // 垂直排列多张图片
                modifier = Modifier
                    .widthIn(max = userMessageBlockMaxWidth) // 图片区域也限制宽度
                    .padding(bottom = 6.dp) // 图片和下方文本气泡的间距
            ) {
                message.imageUrls.forEachIndexed { index, imageUrl ->
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(imageUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = "用户发送的图片 ${index + 1}",
                        modifier = Modifier
                            // 保持图片自身宽度（在固定高度下）
                            .height(100.dp) // 固定高度
                            .clip(RoundedCornerShape(12.dp)), // 图片圆角
                        contentScale = ContentScale.Fit // 保持宽高比，完整显示在100.dp高度内
                    )
                    if (index < message.imageUrls.size - 1) {
                        Spacer(Modifier.height(6.dp)) // 多张图片之间的间距
                    }
                }
            }
        }
        // --- 图片显示区域结束 ---

        // --- AI 消息或用户文本消息的气泡主体 ---
        Column(
            modifier = Modifier.widthIn(max = if (isAI) aiMessageBlockMaxWidth else userMessageBlockMaxWidth),
        ) {
            // AI 加载占位符气泡 (你原来的逻辑)
            if (isAI && showLoadingBubble) {
                Box(modifier = Modifier.padding(start = 16.dp)) {
                    Surface(
                        shape = RoundedCornerShape(14.dp), color = aiBubbleColor,
                        shadowElevation = 4.dp, contentColor = aiContentColor,
                        modifier = Modifier
                            .fillMaxWidth(0.42f)
                            .padding(vertical = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
                            Spacer(Modifier.height(5.dp))
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer { scaleY = 0.5f; },
                                color = Color.Black, trackColor = Color(0xffd0d0d0)
                            )
                        }
                    }
                }
            }

            val shouldDisplayReasoningComponentBox =
                isAI && (!message.reasoning.isNullOrBlank() || isReasoningStreaming)
            if (shouldDisplayReasoningComponentBox) {
                ReasoningToggleAndContent( // 你的实际组件
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
                val showDotsInsideAiContent =
                    isMainContentStreaming && message.text.isBlank() && !message.contentStarted && !showLoadingBubble
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
                } else if (message.contentStarted && message.text.isBlank() && isMainContentStreaming) { /* Fallback or placeholder if needed */
                }

                val showSourcesButton =
                    !message.webSearchResults.isNullOrEmpty() && message.contentStarted && !isMainContentStreaming && !showLoadingBubble
                if (showSourcesButton) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { onUserInteraction(); viewModel.showSourcesDialog(message.webSearchResults!!) },
                        modifier = Modifier.align(Alignment.Start)
                    ) { Text("查看参考来源 (${message.webSearchResults?.size ?: 0})") }
                }

                val showStreamingDotsBelowMainContent =
                    message.contentStarted && isMainContentStreaming && !message.isError
                if (showStreamingDotsBelowMainContent) {
                    Spacer(Modifier.height(8.dp))
                    ThreeDotsWaveAnimation(
                        modifier = Modifier
                            .padding(start = 12.dp, bottom = 4.dp)
                            .align(Alignment.Start),
                        dotColor = aiContentColor, dotSize = 7.dp, spacing = 5.dp
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }

            // 用户消息文本 或 AI错误消息
            if (!isAI && !message.isError) {
                UserOrErrorMessageContent(
                    message = message,
                    displayedText = displayedMainTextForUserOrError,
                    showLoadingDots = false,
                    bubbleColor = userBubbleBackgroundColor,
                    contentColor = userContentColor,
                    isError = false,
                    maxWidth = userMessageBlockMaxWidth,
                    onUserInteraction = onUserInteraction,
                    onEditRequest = onEditRequest,
                    onRegenerateRequest = onRegenerateRequest,
                    modifier = Modifier
                )
            } else if (isAI && message.isError && !showLoadingBubble) {
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
}