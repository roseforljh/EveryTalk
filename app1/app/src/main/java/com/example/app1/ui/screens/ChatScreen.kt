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
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.example.app1.data.models.Sender
import com.example.app1.ui.components.AppTopBar
import com.example.app1.ui.components.MessageBubble
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
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
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }

    var lazyColumnBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var inputBarBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var lastAiBubbleBottomY by remember { mutableStateOf<Float?>(null) }
    val lastAiMessage = messages.lastOrNull { it.sender == Sender.AI }
    val lastAiMessageId = lastAiMessage?.id

    // --- 监听 ViewModel 的滚动到底部事件 ---
    LaunchedEffect(Unit) {
        viewModel.scrollToBottomEvent.collectLatest {
            println("ChatScreen: Received scrollToBottomEvent")
            coroutineScope.launch {
                delay(100)
                if (messages.isNotEmpty()) {
                    val targetIndex = messages.lastIndex
                    println("ChatScreen: Scrolling to index $targetIndex (Event Attempt 1)")
                    listState.animateScrollToItem(index = targetIndex)
                    delay(150)
                    if (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index != targetIndex) {
                        println("ChatScreen: Scrolling to index $targetIndex (Event Attempt 2)")
                        listState.animateScrollToItem(index = targetIndex)
                    } else {
                        println("ChatScreen: Already at bottom after first event scroll.")
                    }
                }
            }
        }
    }

    // --- 自动收起已完成的推理框 ---
    LaunchedEffect(messages, reasoningCompleteMap) {
        messages.forEach { msg ->
            if (msg.sender == Sender.AI && !msg.reasoning.isNullOrBlank()) {
                val isComplete = reasoningCompleteMap[msg.id] ?: false
                if (isComplete) {
                    if (expandedReasoningStates[msg.id] == true) {
                        println("ChatScreen: Reasoning complete for ${msg.id}, collapsing.")
                        viewModel.collapseReasoning(msg.id)
                    }
                }
            }
        }
    }

    // --- 监听列表滚动状态，更新ViewModel中的 userScrolledAway ---
    val userScrolledAway by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            layoutInfo.visibleItemsInfo.isNotEmpty() &&
                    layoutInfo.visibleItemsInfo.last().index < layoutInfo.totalItemsCount - 1
        }
    }
    LaunchedEffect(userScrolledAway) {
        viewModel.onUserScrolledAwayChange(userScrolledAway)
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
                        viewModel.triggerScrollToBottom()
                    },
                    modifier = Modifier.padding(bottom = 80.dp),
                    containerColor = Color.White.copy(alpha = 0.9f),
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Filled.ArrowDownward, contentDescription = "滚动到底部")
                }
            }
        },
        floatingActionButtonPosition = FabPosition.End
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = modifier
                    .fillMaxSize()
            ) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp)
                        .padding(top = paddingValues.calculateTopPadding())
                        .onGloballyPositioned { lazyColumnBounds = it.boundsInWindow() },
                    state = listState,
                    contentPadding = PaddingValues(top = 12.dp, bottom = 0.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        val isAI = message.sender == Sender.AI
                        val hasReasoning = !message.reasoning.isNullOrBlank()
                        val isStreaming =
                            isAI && message.id == currentStreamingAiMessageId && isApiCalling
                        val showLoadingBubble =
                            isStreaming && message.reasoning.isNullOrBlank() && message.text.isBlank() && !message.contentStarted
                        val isReasoningComplete = reasoningCompleteMap[message.id] ?: false
                        val isReasoningStreaming =
                            isStreaming && hasReasoning && !isReasoningComplete
                        val isManuallyExpanded = expandedReasoningStates[message.id] ?: false

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
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        val needSpacerPx = run {
                            val inputY = inputBarBounds?.top
                            val bubbleY = lastAiBubbleBottomY
                            if (inputY != null && bubbleY != null && lastAiMessageId != null) {
                                val desiredGapDp = 8.dp
                                val desiredGapPx = with(density) { desiredGapDp.toPx() }
                                val currentGapPx = inputY - bubbleY
                                (desiredGapPx - currentGapPx).coerceAtLeast(0f)
                            } else {
                                0f
                            }
                        }
                        if (needSpacerPx > 0f) {
                            Spacer(Modifier.height(with(density) { needSpacerPx.toDp() }))
                        }
                    }
                }

                // ---- 输入框区域（含模糊层） ----
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = paddingValues.calculateBottomPadding())
                        .imePadding()
                        .navigationBarsPadding()
                        .onGloballyPositioned { inputBarBounds = it.boundsInWindow() },
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 0.dp,
                    tonalElevation = 0.dp
                ) {
                    Box {
                        // 顶部模糊带
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(18.dp)
                                .align(Alignment.TopCenter)
                                .background(MaterialTheme.colorScheme.surface)
                                .blur(18.dp),
                        )
                        // 输入栏本体
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .align(Alignment.BottomCenter)
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
                                    .focusRequester(focusRequester)
                                    .onFocusChanged { focusState ->
                                        if (focusState.isFocused) {
                                            coroutineScope.launch {
                                                delay(250)
                                                if (messages.isNotEmpty()) {
                                                    val targetIndex = messages.lastIndex
                                                    listState.animateScrollToItem(targetIndex)
                                                    delay(150)
                                                    if (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index != targetIndex) {
                                                        listState.animateScrollToItem(targetIndex)
                                                    }
                                                }
                                                viewModel.onUserScrolledAwayChange(false)
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
                                    disabledContainerColor = MaterialTheme.colorScheme.surface.copy(
                                        alpha = 0.6f
                                    ),
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
                                        val showClearButton =
                                            text.isNotBlank() && !isApiCalling
                                        val clearButtonAreaSize = 30.dp
                                        AnimatedVisibility(
                                            visible = showClearButton,
                                            enter = fadeIn(tween(200)) + scaleIn(
                                                tween(200),
                                                initialScale = 0.9f
                                            ),
                                            exit = fadeOut(tween(150)) + scaleOut(
                                                tween(150),
                                                targetScale = 0.7f
                                            )
                                        ) {
                                            Row {
                                                Box(
                                                    modifier = Modifier
                                                        .size(clearButtonAreaSize)
                                                        .background(
                                                            MaterialTheme.colorScheme.surfaceVariant.copy(
                                                                alpha = 0.78f
                                                            ),
                                                            CircleShape
                                                        ),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    IconButton(
                                                        onClick = { viewModel.onTextChange("") },
                                                        modifier = Modifier.size(clearButtonAreaSize * 0.7f)
                                                    ) {
                                                        Icon(
                                                            Icons.Filled.Clear,
                                                            "Clear text",
                                                            modifier = Modifier.size(18.dp),
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                                Spacer(Modifier.width(6.dp))
                                            }
                                        }
                                        val isSendEnabled =
                                            text.isNotBlank() && selectedApiConfig != null && !isApiCalling
                                        val interactionSource =
                                            remember { MutableInteractionSource() }
                                        IconButton(
                                            enabled = isSendEnabled || isApiCalling,
                                            interactionSource = interactionSource,
                                            onClick = {
                                                if (isApiCalling) {
                                                    viewModel.onCancelAPICall()
                                                } else if (isSendEnabled) {
                                                    keyboardController?.hide(); viewModel.onSendMessage()
                                                }
                                            }
                                        ) {
                                            AnimatedContent(
                                                targetState = isApiCalling,
                                                transitionSpec = {
                                                    fadeIn(
                                                        animationSpec = tween(
                                                            220,
                                                            delayMillis = 90
                                                        )
                                                    ) togetherWith fadeOut(animationSpec = tween(90)) using SizeTransform(
                                                        clip = false
                                                    )
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
                                                            Icons.Filled.Close,
                                                            "取消",
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                    }
                                                } else {
                                                    Icon(
                                                        Icons.AutoMirrored.Filled.Send,
                                                        "发送",
                                                        tint = if (isSendEnabled) MaterialTheme.colorScheme.primary else LocalContentColor.current.copy(
                                                            alpha = 0.38f
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
                // ---- END 输入框区域 ----
            }
        }
    }
}
