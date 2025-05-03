package com.example.app1.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape // 导入 CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
// 移除非必要的 material import (如果有)
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.* // 导入所有 filled 图标
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.example.app1.data.network.ApiConfig
import com.example.app1.data.models.Message
import com.example.app1.data.models.Sender // Add Sender import - Needed for logic
import com.example.app1.ui.components.AppTopBar // 确认 AppTopBar 导入正确
import kotlinx.coroutines.launch

/**
 * 聊天界面的 UI 内容 Composable (Stateless)。
 *
 * 注意：currentStreamingAiMessageId 参数应由父级 Composable 收集 ViewModel 的 StateFlow 并传递。
 */
@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen( // 重命名，去掉 Content 后缀
    messages: List<Message>,
    text: String,
    onTextChange: (String) -> Unit,
    selectedApiConfig: ApiConfig?,
    isApiCalling: Boolean, // 接收整体 API 调用状态 (来自 ViewModel StateFlow)
    currentStreamingAiMessageId: String?, // <-- 当前正在流式输出的 AI 消息的 ID (来自 ViewModel StateFlow)
    showScrollToBottomButton: Boolean,
    onScrollToBottomClick: () -> Unit, // 现在由 ChatScreen 内部实现滚动逻辑
    onHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSendMessage: () -> Unit, // 不再需要 text 参数
    onCancelAPICall: () -> Unit, // <-- 这个就是我们需要传递下去的
    expandedReasoningStates: Map<String, Boolean>,
    onToggleReasoningExpand: (String) -> Unit,
    onUserScrolledAwayChange: (Boolean) -> Unit, // 新增回调
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    // --- 滚动逻辑 (UI 层负责检测和触发滚动) ---
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItemsInfo = layoutInfo.visibleItemsInfo
            val totalItemsCount = layoutInfo.totalItemsCount
            if (totalItemsCount == 0) true
            else {
                val lastVisibleItem = visibleItemsInfo.lastOrNull()
                if (lastVisibleItem == null) true // Should not happen with items
                else {
                    val isLastItemVisible = lastVisibleItem.index == totalItemsCount - 1
                    // Check if the bottom edge of the last item is visible or nearly visible
                    val bottomEdge = lastVisibleItem.offset + lastVisibleItem.size
                    val viewportEnd = layoutInfo.viewportEndOffset
                    // Add a small tolerance (e.g., 5 dp)
                    isLastItemVisible && bottomEdge <= viewportEnd + 5
                }
            }
        }
    }

    // 监听滚动，通知 ViewModel 用户是否离开底部
    LaunchedEffect(listState.isScrollInProgress, isAtBottom) {
        if (!listState.isScrollInProgress) {
            onUserScrolledAwayChange(!isAtBottom) // 滚动停止时，如果不在底部，则用户已离开
        }
    }

    // 监听消息列表大小变化，如果用户在底部，则自动滚动
    LaunchedEffect(messages.size) {
        // 只在消息增加且用户停留在底部时自动滚动
        // Note: This auto-scroll on *any* new message might need adjustment
        // if you only want it for AI streaming messages.
        // To scroll ONLY when the new message is the one being streamed:
        // val lastMessage = messages.lastOrNull()
        // if (lastMessage != null && lastMessage.id == currentStreamingAiMessageId && isAtBottom) { ... }
        if (messages.isNotEmpty() && isAtBottom) {
            coroutineScope.launch {
                try {
                    val lastIndex = messages.lastIndex
                    if (lastIndex >= 0) {
                        println("ChatScreen: Scrolling to bottom due to new message (was at bottom).")
                        // Use animateScrollToItem with max scrollOffset to ensure it goes to the absolute bottom
                        listState.animateScrollToItem(lastIndex, scrollOffset = Int.MAX_VALUE)
                    }
                } catch (e: Exception) { println("ChatScreen: Auto-scroll error: ${e.message}") }
            }
        } else if (messages.isNotEmpty() && !isAtBottom) {
            println("ChatScreen: New message arrived, but user scrolled away. Not auto-scrolling.")
        }
    }

    // 强制滚动到底部的函数 (由按钮调用)
    fun forceScrollToBottom() {
        coroutineScope.launch {
            try {
                val lastIndex = listState.layoutInfo.totalItemsCount - 1
                if (lastIndex >= 0) {
                    println("ChatScreen: Force scrolling to bottom...")
                    // Use animateScrollToItem with max scrollOffset to ensure it goes to the absolute bottom
                    listState.animateScrollToItem(index = lastIndex, scrollOffset = Int.MAX_VALUE)
                    onUserScrolledAwayChange(false) // 强制滚动后，用户就在底部了
                }
            } catch (e: Exception) { println("ChatScreen: Force scroll error: ${e.message}") }
        }
    }

    // --- 定义一个空的字符输入回调，因为不再有打字机效果 ---
    // Removed characterTypedCallback as TypewriterText is removed


    Scaffold( // ChatScreen 使用自己的 Scaffold
        topBar = {
            AppTopBar(
                selectedConfigName = selectedApiConfig?.model ?: "选择配置", // Use the correct parameter name
                onHistoryClick = onHistoryClick, // Pass the callback directly
                onSettingsClick = onSettingsClick  // Pass the callback directly
            )
        },
        floatingActionButton = {} // 置空，让 Box 处理
    ) { paddingValues -> // Scaffold 提供的内边距
        Box(modifier = Modifier.fillMaxSize()) { // Box用于放置FAB
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(paddingValues) // 应用 Scaffold 的内边边距
            ) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    state = listState,
                    contentPadding = PaddingValues(
                        top = 8.dp,
                        bottom = 8.dp // LazyColumn 本身的底部 Padding 可以小一些
                    )
                ) {
                    items(messages, key = { it.id }) { message ->
                        // Determine if cancel should be allowed for THIS message's loading state.
                        // We only allow cancel for the *currently* streaming AI message that is NOT an error.
                        // Whether it shows dots or typing effect is determined inside MessageBubble.
                        val shouldAllowLoadingCancel = isApiCalling &&
                                message.sender == Sender.AI &&
                                message.id == currentStreamingAiMessageId && // <-- Check against the current streaming ID
                                !message.isError


                        MessageBubble(
                            message = message,
                            isExpanded = expandedReasoningStates[message.id] ?: false,
                            onToggleExpand = { onToggleReasoningExpand(message.id) },
                            currentStreamingAiMessageId = currentStreamingAiMessageId, // <-- Pass current streaming ID (now from StateFlow)
                            isApiCurrentlyCalling = isApiCalling, // <-- Pass global API calling state (now from StateFlow)
                            onCancelLoading = if (shouldAllowLoadingCancel) onCancelAPICall else null, // <-- Pass cancel callback conditionally
                            // onCharacterTyped is removed from MessageBubble parameters
                        )
                    }
                }

                // --- 输入区域 Surface ---
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding() // handles keyboard padding
                        .navigationBarsPadding(), // handles system navigation bar padding
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 0.dp // Use elevation if needed
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
                            trailingIcon = {
                                val isSendEnabled = text.isNotBlank() && selectedApiConfig != null && !isApiCalling
                                val interactionSource = remember { MutableInteractionSource() }
                                IconButton(
                                    enabled = isSendEnabled || isApiCalling, // Allow cancel when calling, or send when enabled
                                    interactionSource = interactionSource,
                                    onClick = {
                                        if (isApiCalling) {
                                            onCancelAPICall() // <-- Click cancels
                                        } else if (isSendEnabled) {
                                            keyboardController?.hide() // Hide keyboard before sending
                                            onSendMessage() // Call ViewModel's send method
                                        }
                                    }
                                ) {
                                    AnimatedContent(
                                        targetState = isApiCalling,
                                        transitionSpec = {
                                            fadeIn(tween(200)) togetherWith fadeOut(tween(200)) using SizeTransform(clip = false)
                                        },
                                        label = "SendStopIconTransition"
                                    ) { apiCallingState ->
                                        if (apiCallingState) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(20.dp),
                                                    strokeWidth = 2.dp,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Icon(
                                                    imageVector = Icons.Filled.Close,
                                                    contentDescription = "取消",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                            }
                                        } else {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.Send,
                                                contentDescription = "发送",
                                                tint = if (isSendEnabled) MaterialTheme.colorScheme.primary else LocalContentColor.current.copy(alpha = 0.38f)
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    }
                } // Surface End
            } // Column End

            // --- 滚动到底部按钮 (使用 Box 对齐) ---
            AnimatedVisibility(
                visible = showScrollToBottomButton,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 88.dp), // Position above input area
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = slideOutVertically { it / 2 } + fadeOut()
            ) {
                FloatingActionButton(
                    onClick = {
                        println("ChatScreen: Scroll to bottom button clicked.")
                        forceScrollToBottom() // Call internal scroll function
                    },
                    shape = CircleShape,
                    modifier = Modifier.size(40.dp),
                    containerColor = Color.White, // Or use theme colors
                    contentColor = Color.Black // Or use theme colors
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = "滚动到底部"
                    )
                }
            }
        } // Box End
    } // Scaffold End
}