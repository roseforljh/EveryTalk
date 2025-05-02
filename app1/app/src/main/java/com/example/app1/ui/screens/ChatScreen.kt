package com.example.app1.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape // 导入 CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ContentAlpha
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward // 导入向下箭头图标
import androidx.compose.material.icons.filled.Close // 或 StopCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.app1.data.models.ApiConfig
import com.example.app1.data.models.Message
import com.example.app1.data.models.Sender
import com.example.app1.ui.components.AppTopBar
import com.example.app1.ui.components.MessageBubble
// import com.example.app1.ui.components.ReasoningItem // 如果 Reasoning 处理移到 MessageBubble 内部，这里就不需要了
import kotlinx.coroutines.launch

/**
 * 聊天界面的 UI 内容 Composable。
 */
@OptIn(ExperimentalAnimationApi::class) // 为了 AnimatedVisibility 和 AnimatedContent
@Composable
fun ChatScreenContent(
    messages: List<Message>,
    text: String,
    onTextChange: (String) -> Unit,
    selectedApiConfig: ApiConfig?,
    isApiCalling: Boolean,
    listState: LazyListState,                   // 列表状态
    expandedReasoningStates: Map<String, Boolean>, // Reasoning 展开状态
    // --- 新增状态和回调 ---
    showScrollToBottomButton: Boolean,          // 是否显示滚动到底部按钮
    onScrollToBottomClick: () -> Unit,          // 点击滚动到底部按钮的回调
    // --- 新增结束 ---
    onHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSendMessage: (String) -> Unit,
    onCancelAPICall: () -> Unit,
    snackbarHostState: SnackbarHostState, // 传递 SnackbarHostState 用于显示消息
    onToggleReasoningExpand: (messageId: String) -> Unit, // 传递切换 Reasoning 展开状态的回调
    currentStreamingAiMessageId: String?, // 传递当前正在流式处理的消息 ID
    isApiCallingOverall: Boolean // 传递整体 API 调用状态
) {
    val coroutineScope = rememberCoroutineScope()

    // --- 使用 Box 布局，方便放置滚动按钮 ---
    Box(modifier = Modifier.fillMaxSize()) {

        Column(modifier = Modifier.fillMaxSize()) {
            // --- 顶部应用栏 (不变) ---
            AppTopBar(
                onHistoryClick = onHistoryClick,
                onSettingsClick = onSettingsClick,
                selectedConfigName = selectedApiConfig?.let { "${it.model} (${it.provider})" }
                    ?: "未选择配置"
            )

            // --- 聊天消息列表 ---
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                state = listState,
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = 80.dp // 增大底部 Padding 给输入框和按钮留空间
                )
            ) {
                // 直接遍历 messages 列表，让 MessageBubble 自己决定如何渲染
                items(messages, key = { it.id }) { message ->
                    // MessageBubble 现在内部处理所有消息的渲染，包括占位符、错误、内容、以及带 Reasoning 的 AI 消息
                    // 传递 isApiCallingOverall 和 currentStreamingAiMessageId
                    MessageBubble(
                        message = message,
                        isExpanded = expandedReasoningStates[message.id] ?: false, // 从 Map 中获取展开状态
                        onToggleExpand = { onToggleReasoningExpand(message.id) }, // 将回调传递下去
                        isApiCalling = isApiCallingOverall, // 传递整体 API 调用状态
                        currentStreamingAiMessageId = currentStreamingAiMessageId // 传递当前流式消息 ID
                    )
                    // 移除了之前单独渲染 ReasoningItem 的逻辑，因为它现在应该在 MessageBubble 内部
                    // 移除了 shouldShowBubble 逻辑，所有消息都通过 MessageBubble 渲染
                }
            }

            // --- 输入区域 ---
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 0.dp
            ) {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    TextField(
                        value = text,
                        onValueChange = onTextChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                0.5.dp,
                                MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(28.dp)
                            ),
                        shape = RoundedCornerShape(28.dp),
                        placeholder = {
                            Text(
                                "输入消息...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            errorIndicatorColor = Color.Transparent
                        ),
                        maxLines = 5,
                        trailingIcon = { // 发送/停止按钮
                            // 这里使用 isApiCallingOverall 来判断是否显示停止按钮
                            val isSendEnabled =
                                text.isNotBlank() && selectedApiConfig != null && !isApiCallingOverall
                            val interactionSource = remember { MutableInteractionSource() }
                            IconButton(
                                enabled = isSendEnabled || isApiCallingOverall, // API 调用中允许点击取消
                                interactionSource = interactionSource,
                                onClick = {
                                    if (isApiCallingOverall) { // 根据整体状态判断是取消还是发送
                                        onCancelAPICall() // 调用取消 API 的回调
                                    } else if (isSendEnabled) {
                                        onSendMessage(text.trim()) // 调用发送消息的回调
                                    } else {
                                        // 如果发送按钮被禁用 (文本为空 或 未选择配置)
                                        coroutineScope.launch {
                                            if (selectedApiConfig == null) {
                                                snackbarHostState.showSnackbar(
                                                    "请先选择或添加API配置",
                                                    duration = SnackbarDuration.Long
                                                )
                                            }
                                            // 如果是文本为空禁用，不显示 Snackbar
                                        }
                                    }
                                }
                            ) {
                                // 根据整体 API 调用状态显示不同的图标
                                AnimatedContent(
                                    targetState = isApiCallingOverall,
                                    transitionSpec = {
                                        fadeIn(tween(200)) togetherWith fadeOut(
                                            tween(200)
                                        ) using SizeTransform(clip = false)
                                    },
                                    label = "SendStopIconTransition"
                                ) { apiCallingState ->
                                    if (apiCallingState) { // 加载/停止图标
                                        // 使用一个 Box 来叠放 CircularProgressIndicator 和中间的停止图标
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.size(24.dp) // 图标容器大小
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp), // 进度圈大小
                                                strokeWidth = 2.dp, // 进度圈线条粗细
                                                color = MaterialTheme.colorScheme.primary // 进度圈颜色
                                            )
                                            // 中间的停止图标
                                            Icon(
                                                imageVector = Icons.Filled.Close, // 使用 Close 或 StopCircle
                                                contentDescription = "取消",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(12.dp) // 停止图标大小
                                            )
                                        }
                                    } else { // 发送图标
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Send,
                                            contentDescription = "发送",
                                            tint = if (isSendEnabled) MaterialTheme.colorScheme.primary else LocalContentColor.current.copy(
                                                alpha = ContentAlpha.disabled // 禁用状态下图标颜色变灰
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            } // Surface End
        } // Column End

        // --- 滚动到底部按钮 ---
        AnimatedVisibility(
            visible = showScrollToBottomButton, // 根据 showScrollToBottomButton 状态控制可见性
            modifier = Modifier
                .align(Alignment.BottomEnd) // 对齐到 Box 的右下角
                .padding(end = 16.dp, bottom = 88.dp), // 调整边距，放在输入框上方（输入框高约 56dp + 8dp*2 padding = 72dp，88dp 留出一点空间）
            enter = fadeIn() + slideInVertically { it / 2 }, // 从底部滑入并淡入
            exit = slideOutVertically { it / 2 } + fadeOut() // 滑出并淡出
        ) {
            FloatingActionButton(
                onClick = onScrollToBottomClick, // 点击时调用回调，执行滚动到底部操作
                shape = CircleShape, // 圆形
                modifier = Modifier.size(40.dp), // 较小的 FAB
                containerColor = Color.White, // 白色背景
                contentColor = Color.Black // 黑色图标
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowDownward,
                    contentDescription = "滚动到底部"
                )
            }
        }
    } // Box End
}

