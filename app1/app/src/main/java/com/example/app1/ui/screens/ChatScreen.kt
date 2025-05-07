package com.example.app1.ui.screens // 确保包名正确

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background // 用于按钮背景
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items // 确保导入 items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape // 用于按钮形状
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.North // 向上箭头
import androidx.compose.material.icons.automirrored.filled.Send // 保留
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip // 用于按钮 clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.app1.data.models.Message // 确保 Message 导入
import com.example.app1.data.models.Sender
import com.example.app1.ui.components.AppTopBar // 确保 AppTopBar 导入
import com.example.app1.ui.components.MessageBubble // 确保 MessageBubble 导入
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.pow

// --- 定义锚点 Item 的 Key ---
private const val SCROLL_ANCHOR_KEY = "scroll_anchor"
// --- 结束 ---

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalAnimationApi::class
)
@Composable
fun ChatScreen(
    viewModel: AppViewModel,
) {
    val messages = viewModel.messages
    val text by viewModel.text.collectAsState()
    val selectedApiConfig by viewModel.selectedApiConfig.collectAsState()
    val isApiCalling by viewModel.isApiCalling.collectAsState()
    val currentStreamingAiMessageId by viewModel.currentStreamingAiMessageId.collectAsState()
    val reasoningCompleteMap = viewModel.reasoningCompleteMap
    val expandedReasoningStates = viewModel.expandedReasoningStates

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val canScrollDown by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItemsInfo = layoutInfo.visibleItemsInfo
            if (visibleItemsInfo.isEmpty()) {
                false
            } else {
                val lastVisibleItem = visibleItemsInfo.last()
                val totalItemsCount = layoutInfo.totalItemsCount
                lastVisibleItem.index < totalItemsCount - 1 ||
                        (lastVisibleItem.offset + lastVisibleItem.size) > layoutInfo.viewportEndOffset - 8
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.scrollToBottomEvent.collectLatest {
            coroutineScope.launch {
                val targetIndex = messages.size
                Log.d(
                    "ChatScreenScroll",
                    "ScrollToBottom Event Received. Scrolling to anchor index: $targetIndex"
                )
                if (targetIndex >= 0 && listState.layoutInfo.totalItemsCount > targetIndex) {
                    listState.animateScrollToItem(targetIndex)
                } else if (listState.layoutInfo.totalItemsCount > 0) {
                    listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                }
            }
        }
    }

    LaunchedEffect(messages, reasoningCompleteMap) {
        messages.forEach { msg ->
            if (msg.sender == Sender.AI && !msg.reasoning.isNullOrBlank()) {
                val isComplete = reasoningCompleteMap[msg.id] ?: false
                if (isComplete && expandedReasoningStates[msg.id] == true) {
                    viewModel.collapseReasoning(msg.id)
                }
            }
        }
    }

    Scaffold(
        containerColor = Color.White,
        topBar = {
            AppTopBar(
                selectedConfigName = selectedApiConfig?.model ?: "选择配置",
                onHistoryClick = viewModel::navigateToHistory,
                onSettingsClick = viewModel::showSettingsDialog
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = canScrollDown && messages.isNotEmpty(),
                enter = fadeIn(animationSpec = tween(150)) + slideInVertically(
                    animationSpec = tween(200),
                    initialOffsetY = { it / 2 }),
                exit = fadeOut(animationSpec = tween(150)) + slideOutVertically(
                    animationSpec = tween(200),
                    targetOffsetY = { it / 2 })
            ) {
                FloatingActionButton(
                    onClick = { viewModel.triggerScrollToBottom() },
                    modifier = Modifier.padding(bottom = 72.dp),
                    containerColor = Color.White,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Filled.ArrowDownward, contentDescription = "滚动到底部")
                }
            }
        },
        floatingActionButtonPosition = FabPosition.End
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
                    .padding(top = paddingValues.calculateTopPadding()),
                state = listState,
                contentPadding = PaddingValues(top = 12.dp, bottom = 4.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillParentMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                val textStyle = LocalTextStyle.current.copy(
                                    fontSize = 36.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.Black
                                )
                                Text(
                                    text = "你好",
                                    style = textStyle
                                )

                                val density = LocalDensity.current
                                // --- 动画参数调整 ---
                                val initialAmplitudeDp = (-8).dp
                                val amplitudeDecayFactor = 0.7f
                                val numCycles = 5

                                val initialBouncePhaseDurationMs = 250 // 初始弹跳阶段时长 (ms) - 较快
                                val finalBouncePhaseDurationMs = 600   // 最终弹跳阶段时长 (ms) - 较慢

                                val dotInterDelayMs = 150L // 点之间的错开延迟 (可以根据总时长调整)
                                // --- 结束动画参数调整 ---

                                val initialAmplitudePx = with(density) { initialAmplitudeDp.toPx() }

                                val dot1OffsetY = remember { Animatable(0f) }
                                val dot2OffsetY = remember { Animatable(0f) }
                                val dot3OffsetY = remember { Animatable(0f) }

                                LaunchedEffect(Unit) {
                                    dot1OffsetY.snapTo(0f)
                                    dot2OffsetY.snapTo(0f)
                                    dot3OffsetY.snapTo(0f)

                                    repeat(numCycles) { cycleIndex ->
                                        val currentCycleAmplitudePx =
                                            initialAmplitudePx * (amplitudeDecayFactor.pow(
                                                cycleIndex
                                            ))

                                        // 计算当前循环的弹跳阶段时长
                                        val progress =
                                            if (numCycles > 1) cycleIndex.toFloat() / (numCycles - 1) else 0f
                                        val currentBouncePhaseDurationMs =
                                            (initialBouncePhaseDurationMs +
                                                    (finalBouncePhaseDurationMs - initialBouncePhaseDurationMs) * progress).toInt()


                                        coroutineScope {
                                            launch {
                                                dot1OffsetY.animateTo(
                                                    targetValue = currentCycleAmplitudePx,
                                                    animationSpec = tween(
                                                        durationMillis = currentBouncePhaseDurationMs,
                                                        easing = FastOutSlowInEasing
                                                    )
                                                )
                                                dot1OffsetY.animateTo(
                                                    targetValue = 0f,
                                                    animationSpec = tween(
                                                        durationMillis = currentBouncePhaseDurationMs,
                                                        easing = FastOutSlowInEasing
                                                    )
                                                )
                                            }
                                            launch {
                                                delay(dotInterDelayMs)
                                                dot2OffsetY.animateTo(
                                                    targetValue = currentCycleAmplitudePx,
                                                    animationSpec = tween(
                                                        durationMillis = currentBouncePhaseDurationMs,
                                                        easing = FastOutSlowInEasing
                                                    )
                                                )
                                                dot2OffsetY.animateTo(
                                                    targetValue = 0f,
                                                    animationSpec = tween(
                                                        durationMillis = currentBouncePhaseDurationMs,
                                                        easing = FastOutSlowInEasing
                                                    )
                                                )
                                            }
                                            launch {
                                                delay(dotInterDelayMs * 2) // 确保第二个点和第三个点之间也有延迟
                                                dot3OffsetY.animateTo(
                                                    targetValue = currentCycleAmplitudePx,
                                                    animationSpec = tween(
                                                        durationMillis = currentBouncePhaseDurationMs,
                                                        easing = FastOutSlowInEasing
                                                    )
                                                )
                                                dot3OffsetY.animateTo(
                                                    targetValue = 0f,
                                                    animationSpec = tween(
                                                        durationMillis = currentBouncePhaseDurationMs,
                                                        easing = FastOutSlowInEasing
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }

                                Text(
                                    text = ".",
                                    style = textStyle,
                                    modifier = Modifier.offset(y = with(density) { dot1OffsetY.value.toDp() })
                                )
                                Text(
                                    text = ".",
                                    style = textStyle,
                                    modifier = Modifier.offset(y = with(density) { dot2OffsetY.value.toDp() })
                                )
                                Text(
                                    text = ".",
                                    style = textStyle,
                                    modifier = Modifier.offset(y = with(density) { dot3OffsetY.value.toDp() })
                                )
                            }
                        }
                    }
                } else {
                    items(messages, key = { message -> message.id }) { message ->
                        val isAI = message.sender == Sender.AI
                        val hasReasoning = !message.reasoning.isNullOrBlank()
                        val isCurrentlyStreamingThisMessage =
                            isAI && message.id == currentStreamingAiMessageId && isApiCalling
                        val showLoadingBubbleForThisMessage =
                            isCurrentlyStreamingThisMessage && !message.contentStarted && message.text.isBlank() && message.reasoning.isNullOrBlank()
                        val isCurrentReasoningComplete = reasoningCompleteMap[message.id] ?: false
                        val isCurrentReasoningStreaming =
                            isCurrentlyStreamingThisMessage && hasReasoning && !isCurrentReasoningComplete && message.contentStarted
                        val isMessageManuallyExpanded = expandedReasoningStates[message.id] ?: false

                        MessageBubble(
                            message = message,
                            viewModel = viewModel,
                            isReasoningStreaming = isCurrentReasoningStreaming,
                            isReasoningComplete = isCurrentReasoningComplete,
                            isManuallyExpanded = isMessageManuallyExpanded,
                            onToggleReasoning = { viewModel.onToggleReasoningExpand(message.id) },
                            showLoadingBubble = showLoadingBubbleForThisMessage
                        )
                    }

                    item(key = SCROLL_ANCHOR_KEY) {
                        Spacer(modifier = Modifier.height(1.dp))
                    }
                }
            } // End LazyColumn

            Surface( // 输入框 Surface
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = paddingValues.calculateBottomPadding())
                    .imePadding()
                    .navigationBarsPadding(),
                color = Color.White,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                shadowElevation = 20.dp,
                tonalElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    TextField(
                        value = text,
                        onValueChange = viewModel::onTextChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged {}
                            .padding(bottom = 48.dp),
                        shape = RoundedCornerShape(24.dp),
                        placeholder = {
                            Text(
                                "输入消息…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                            disabledContainerColor = Color.White.copy(alpha = 0.6f),
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
                            val showClearButton = text.isNotBlank() && !isApiCalling
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
                                IconButton(
                                    onClick = { viewModel.onTextChange("") },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Clear,
                                        "清除内容",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 20.dp, end = 20.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        val isSendEnabled =
                            text.isNotBlank() && selectedApiConfig != null && !isApiCalling

                        IconButton(
                            enabled = isSendEnabled || isApiCalling,
                            onClick = {
                                if (isApiCalling) viewModel.onCancelAPICall()
                                else if (isSendEnabled) {
                                    keyboardController?.hide()
                                    viewModel.onSendMessage()
                                }
                            },
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(
                                    Color.Black

                                )
                        ) {
                            Icon(
                                imageVector = if (isApiCalling) Icons.Filled.Stop else Icons.Filled.North,
                                contentDescription = if (isApiCalling) "停止" else "发送",
                                tint = Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            } // End Surface for Input Bar
        } // End Column
    } // End Scaffold
}