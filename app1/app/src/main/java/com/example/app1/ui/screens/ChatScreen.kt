package com.example.app1.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward // 导入向下箭头图标
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Stop // 使用 Stop 图标
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester // 导入 FocusRequester
import androidx.compose.ui.focus.focusRequester // 导入 focusRequester Modifier
import androidx.compose.ui.focus.onFocusChanged // 导入 onFocusChanged Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController // 导入 KeyboardController
import androidx.compose.ui.unit.dp
import com.example.app1.data.models.Sender
import com.example.app1.ui.components.AppTopBar
import com.example.app1.ui.components.MessageBubble
import kotlinx.coroutines.delay // 导入 delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class) // 保留 ExperimentalAnimationApi
@Composable
fun ChatScreen(
    viewModel: AppViewModel,
    modifier: Modifier = Modifier
) {
    val messages = viewModel.messages
    val text by viewModel.text.collectAsState()
    val selectedApiConfig by viewModel.selectedApiConfig.collectAsState()
    val isApiCalling by viewModel.isApiCalling.collectAsState()
    val currentStreamingAiMessageId by viewModel.currentStreamingAiMessageId.collectAsState()
    val reasoningCompleteMap = viewModel.reasoningCompleteMap
    val expandedReasoningStates = viewModel.expandedReasoningStates
    val showScrollToBottomButton by viewModel.showScrollToBottomButton.collectAsState()

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current // 获取键盘控制器

    var lazyColumnBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var inputBarBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var lastAiBubbleBottomY by remember { mutableStateOf<Float?>(null) }
    val lastAiMessage = messages.lastOrNull { it.sender == Sender.AI }
    val lastAiMessageId = lastAiMessage?.id

    // -- 自动收起已完成的推理框 --
    LaunchedEffect(messages, reasoningCompleteMap) {
        messages.forEach { msg ->
            if (msg.sender == Sender.AI && !msg.reasoning.isNullOrBlank()) {
                val isComplete = reasoningCompleteMap[msg.id] ?: false
                if (isComplete) {
                    if (expandedReasoningStates[msg.id] == true) {
                        println("ChatScreen: Reasoning complete for ${msg.id}, collapsing.")
                        viewModel.collapseReasoning(msg.id) // 调用 ViewModel 收起
                    }
                }
            }
        }
    }

    // -- 用户滚动状态监听 --
    val userScrolledAway by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            // 条件：列表已初始化，且最后可见项的索引小于总数减1（表示没在最底部）
            layoutInfo.visibleItemsInfo.isNotEmpty() &&
                    layoutInfo.visibleItemsInfo.last().index < layoutInfo.totalItemsCount - 1
        }
    }
    LaunchedEffect(userScrolledAway) {
        viewModel.onUserScrolledAwayChange(userScrolledAway)
    }

    // -- 滚动到底部/顶部事件监听 --
    LaunchedEffect(Unit) {
        viewModel.scrollToBottomEvent.collectLatest {
            // 当 ViewModel 发出滚动事件时 (通常是发送新消息后)
            coroutineScope.launch {
                if (messages.isNotEmpty()) {
                    val targetIndex = messages.lastIndex // 总是滚动到最新的一条消息
                    println("ChatScreen: scrollToBottomEvent received, scrolling to index $targetIndex (Attempt 1)")
                    listState.animateScrollToItem(targetIndex)
                    // 短暂延迟后再次确保滚动到位，有时布局变化需要时间
                    delay(150) // 调整延迟时间
                    // 检查目标项是否完全可见并且在顶部
                    val targetItemInfo = listState.layoutInfo.visibleItemsInfo.find { it.index == targetIndex }
                    if (targetItemInfo == null || targetItemInfo.offset != 0) {
                        println("ChatScreen: Scrolling to index $targetIndex (Attempt 2 to ensure top visibility)")
                        listState.animateScrollToItem(targetIndex, scrollOffset = 0) // 滚动到项的顶部
                    } else {
                        println("ChatScreen: Index $targetIndex is already at top or visible.")
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                selectedConfigName = selectedApiConfig?.model ?: "选择配置",
                onHistoryClick = viewModel::navigateToHistory,
                onSettingsClick = viewModel::showSettingsDialog
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = showScrollToBottomButton,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
            ) {
                FloatingActionButton(
                    onClick = {
                        viewModel.triggerScrollToBottom() // 通过 ViewModel 触发滚动
                    },
                    modifier = Modifier.padding(bottom = 85.dp), // 调整按钮位置
                    containerColor = Color.White.copy(alpha = 0.9f), // 半透明白色背景
                    contentColor = MaterialTheme.colorScheme.primary // 主色图标
                ) {
                    Icon(Icons.Filled.ArrowDownward, "滚动到底部")
                }
            }
        },
        floatingActionButtonPosition = FabPosition.End // 按钮位置在右下角
    ) { paddingValues -> // 从 Scaffold 获取内边距
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize()
                // 不在此处应用 Scaffold 的 padding，分别处理
            ) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f) // 占据剩余空间
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp) // 左右内边距
                        .padding(top = paddingValues.calculateTopPadding()) // 应用 Scaffold 的顶部内边距
                        .onGloballyPositioned { lazyColumnBounds = it.boundsInWindow() },
                    state = listState, // 关联 LazyListState
                    // 顶部留白，底部不留白（由输入框的 Surface 处理）
                    contentPadding = PaddingValues(top = 12.dp, bottom = 0.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        // --- 计算 MessageBubble 所需的状态 ---
                        val isAI = message.sender == Sender.AI
                        val hasReasoning = !message.reasoning.isNullOrBlank()
                        val isStreaming = isAI && message.id == currentStreamingAiMessageId && isApiCalling
                        val showLoadingBubble = isStreaming && message.reasoning.isNullOrBlank() && message.text.isBlank() && !message.contentStarted
                        val isReasoningComplete = reasoningCompleteMap[message.id] ?: false
                        val isReasoningStreaming = isStreaming && hasReasoning && !isReasoningComplete
                        val isManuallyExpanded = expandedReasoningStates[message.id] ?: false

                        // --- 渲染消息气泡 ---
                        MessageBubble(
                            message = message,
                            isReasoningStreaming = isReasoningStreaming,
                            isReasoningComplete = isReasoningComplete,
                            isManuallyExpanded = isManuallyExpanded,
                            onToggleReasoning = { viewModel.onToggleReasoningExpand(message.id) },
                            showLoadingBubble = showLoadingBubble,
                            modifier = if (message.id == lastAiMessageId && message.sender == Sender.AI)
                                Modifier.onGloballyPositioned { coords ->
                                    lastAiBubbleBottomY = coords.boundsInWindow().bottom
                                }
                            else Modifier
                        )
                    }
                    // --- 底部 Spacer，防止最后一个气泡紧贴输入框 ---
                    item {
                        Spacer(modifier = Modifier.height(8.dp)) // 保留一个最小间距
                        val needSpacerPx = run {
                            val inputY = inputBarBounds?.top
                            val bubbleY = lastAiBubbleBottomY
                            if (inputY != null && bubbleY != null && lastAiMessageId != null) {
                                val desiredGapDp = 8.dp
                                val desiredGapPx = with(density) { desiredGapDp.toPx() }
                                val currentGapPx = inputY - bubbleY
                                (desiredGapPx - currentGapPx).coerceAtLeast(0f)
                            } else { 0f }
                        }
                        if (needSpacerPx > 0f) {
                            Spacer(Modifier.height(with(density) { needSpacerPx.toDp() }))
                        }
                    }
                    // --- END 底部Spacer ---
                } // End LazyColumn

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 应用 Scaffold 的底部内边距
                        .padding(bottom = paddingValues.calculateBottomPadding())
                        // 应用键盘内边距，将 Surface 顶起
                        .imePadding()
                        // 可选：应用导航栏内边距
                        .navigationBarsPadding(),
                    color = MaterialTheme.colorScheme.surface, // 输入框区域背景色
                    shadowElevation = 0.dp, // 无阴影
                    tonalElevation = 0.dp  // 无色调叠加
                ) {
                    Box { // 使用 Box 包裹 TextField，方便对齐
                        // 输入栏本体
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 8.dp) // 输入栏内边距
                                .align(Alignment.BottomCenter) // 确保输入栏在 Surface 底部
                        ) {
                            TextField(
                                value = text,
                                onValueChange = viewModel::onTextChange,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(24.dp)
                                    )
                                    .focusRequester(focusRequester) // 关联 FocusRequester
                                    .onFocusChanged { focusState ->
                                        if (focusState.isFocused) {
                                            // 获得焦点时，触发滚动到底部
                                            coroutineScope.launch {
                                                delay(250) // 等待键盘和 imePadding 生效
                                                if (messages.isNotEmpty()) {
                                                    val targetIndex = messages.lastIndex
                                                    println("ChatScreen: TextField focused, scrolling to $targetIndex (Attempt 1)")
                                                    listState.animateScrollToItem(targetIndex)
                                                    delay(150)
                                                    val targetItemInfo = listState.layoutInfo.visibleItemsInfo.find { it.index == targetIndex }
                                                    if (targetItemInfo == null || targetItemInfo.offset != 0) {
                                                        println("ChatScreen: TextField focused, scrolling to $targetIndex (Attempt 2 to ensure top visibility)")
                                                        listState.animateScrollToItem(targetIndex, scrollOffset = 0)
                                                    } else {
                                                        println("ChatScreen: TextField focused, index $targetIndex already at top or visible.")
                                                    }
                                                }
                                                viewModel.onUserScrolledAwayChange(false) // 获得焦点时，认为用户在底部
                                            }
                                        }
                                    },
                                shape = RoundedCornerShape(24.dp),
                                placeholder = {
                                    Text(
                                        "输入消息…",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                    disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                    errorIndicatorColor = Color.Transparent,
                                    cursorColor = MaterialTheme.colorScheme.primary,
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                ),
                                maxLines = 5,
                                trailingIcon = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(end = 3.dp)
                                    ) {
                                        val showClearButton = text.isNotBlank() && !isApiCalling
                                        val isSendEnabled = text.isNotBlank() && selectedApiConfig != null && !isApiCalling
                                        val interactionSource = remember { MutableInteractionSource() }

                                        AnimatedVisibility(
                                            visible = showClearButton,
                                            enter = fadeIn(tween(200)) + scaleIn( tween(200), initialScale = 0.9f ),
                                            exit = fadeOut(tween(150)) + scaleOut( tween(150), targetScale = 0.7f )
                                        ) {
                                            IconButton(
                                                onClick = { viewModel.onTextChange("") },
                                                modifier = Modifier.size(30.dp) // 稍微增大点击区域
                                            ) {
                                                Icon(
                                                    Icons.Filled.Clear,
                                                    "清除内容",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(20.dp) // 图标大小
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(4.dp)) // 清除和发送/停止按钮之间的间距

                                        IconButton(
                                            enabled = isSendEnabled || isApiCalling,
                                            interactionSource = interactionSource,
                                            onClick = {
                                                if (isApiCalling) {
                                                    viewModel.onCancelAPICall()
                                                } else if (isSendEnabled) {
                                                    keyboardController?.hide() // 发送前隐藏键盘
                                                    viewModel.onSendMessage()
                                                }
                                            },
                                            modifier = Modifier.size(40.dp) // 增大发送/停止按钮的点击区域
                                        ) {
                                            if (isApiCalling) {
                                                // 使用停止图标
                                                Icon(
                                                    Icons.Filled.Stop, // 使用 Stop 图标
                                                    "停止",
                                                    tint = MaterialTheme.colorScheme.primary, // 与发送图标颜色一致
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            } else {
                                                Icon(
                                                    Icons.AutoMirrored.Filled.Send,
                                                    "发送",
                                                    tint = if (isSendEnabled) MaterialTheme.colorScheme.primary else LocalContentColor.current.copy(
                                                        alpha = 0.38f
                                                    ),
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}