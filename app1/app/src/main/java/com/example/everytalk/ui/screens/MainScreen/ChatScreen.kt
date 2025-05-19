// 文件: com.example.everytalk.ui.screens.MainScreen.ChatScreen.kt
package com.example.everytalk.ui.screens.MainScreen

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState // 导入
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.everytalk.StateControler.AppViewModel
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.navigation.Screen
import com.example.everytalk.ui.components.AppTopBar
import com.example.everytalk.ui.components.WebSourcesDialog
import com.example.everytalk.ui.screens.BubbleMain.Main.MessageBubble
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow
import kotlinx.coroutines.yield

private const val USER_INACTIVITY_TIMEOUT_MS = 2000L
private const val REALTIME_SCROLL_CHECK_DELAY_MS = 50L
private const val FINAL_SCROLL_DELAY_MS = 150L
private const val SESSION_SWITCH_SCROLL_DELAY_MS = 250L // 新增：会话切换时的滚动延迟

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class
)
@Composable
fun ChatScreen(
    viewModel: AppViewModel,
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val messages: List<Message> = viewModel.messages
    val text by viewModel.text.collectAsState()
    val selectedApiConfig by viewModel.selectedApiConfig.collectAsState()
    val isApiCalling by viewModel.isApiCalling.collectAsState()
    val currentStreamingAiMessageId by viewModel.currentStreamingAiMessageId.collectAsState()
    val reasoningCompleteMap = viewModel.reasoningCompleteMap
    val isWebSearchEnabled by viewModel.isWebSearchEnabled.collectAsState()

    val listState = rememberLazyListState()

    val loadedHistoryIndex by viewModel.loadedHistoryIndex.collectAsState()
    // 用于跟踪 loadedHistoryIndex 的上一个值，以判断是否发生了会话切换
    var previousLoadedHistoryIndexState by remember { mutableStateOf(loadedHistoryIndex) }

    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    var userManuallyScrolledAwayFromBottom by remember { mutableStateOf(false) }
    var ongoingScrollJob by remember { mutableStateOf<Job?>(null) }
    var programmaticallyScrolling by remember { mutableStateOf(false) }

    fun scrollToBottomGuaranteed(reason: String = "Unknown") {
        ongoingScrollJob?.cancel()
        ongoingScrollJob = coroutineScope.launch {
            programmaticallyScrolling = true
            val bottomIndex = messages.size
            var reached = false
            repeat(12) { i ->
                yield()
                if (i == 0) {
                    listState.scrollToItem(bottomIndex)
                } else {
                    listState.animateScrollToItem(bottomIndex)
                }
                delay(16L)
                val items = listState.layoutInfo.visibleItemsInfo
                reached = items.any { it.index == bottomIndex }
                if (reached && items.isNotEmpty()) {
                    Log.d("ScrollJob", "成功将 footer 滚动可见 ($reason retry=$i)")
                    userManuallyScrolledAwayFromBottom = false
                    programmaticallyScrolling = false
                    ongoingScrollJob = null
                    return@launch
                }
            }
            delay(40)
            listState.animateScrollToItem(bottomIndex)
            userManuallyScrolledAwayFromBottom = false
            Log.d("ScrollJob", "最终兜底滚动 footer ($reason)")
            programmaticallyScrolling = false
            ongoingScrollJob = null
        }
    }

    // --- ★★★ 修改点 2: 调整初始滚动逻辑，为会话切换添加延迟 ★★★ ---
    LaunchedEffect(key1 = loadedHistoryIndex, key2 = messages) {
        val currentLoadedIndex = loadedHistoryIndex
        // 判断是否是由于 loadedHistoryIndex 变化（即会话切换）导致的 Effect 触发
        val sessionJustChanged = previousLoadedHistoryIndexState != currentLoadedIndex

        if (sessionJustChanged) {
            Log.d(
                "ChatScreenInitScroll",
                "会话已切换 (loadedHistoryIndex: $previousLoadedHistoryIndexState -> $currentLoadedIndex)。应用延迟 (${SESSION_SWITCH_SCROLL_DELAY_MS}ms)。"
            )
            delay(SESSION_SWITCH_SCROLL_DELAY_MS) // 为侧边栏动画等留出时间
        }
        previousLoadedHistoryIndexState = currentLoadedIndex

        if (messages.isNotEmpty()) {
            val targetIndex = messages.size
            Log.d(
                "ChatScreenInitScroll",
                "会话变更/消息列表变更 (延迟后)。尝试立即滚动到底部 (目标索引: $targetIndex)。 loadedHistoryIndex: $currentLoadedIndex, messages.size: ${messages.size}"
            )

            coroutineScope.launch {
                programmaticallyScrolling = true
                userManuallyScrolledAwayFromBottom = false

                var attempts = 0
                var successfullyScrolled = false
                val maxImmediateAttempts = 3

                while (attempts < maxImmediateAttempts && !successfullyScrolled && isActive) {
                    attempts++
                    try {
                        Log.d(
                            "ChatScreenInitScroll",
                            "尝试无动画 scrollToItem (第 $attempts 次) 到索引 $targetIndex"
                        )
                        listState.scrollToItem(targetIndex)
                        delay(if (attempts == 1) 50L else 32L)

                        val layoutInfo = listState.layoutInfo
                        if (layoutInfo.visibleItemsInfo.isNotEmpty()) {
                            successfullyScrolled =
                                layoutInfo.visibleItemsInfo.any { it.index == targetIndex }
                        }

                        if (successfullyScrolled) {
                            Log.d(
                                "ChatScreenInitScroll",
                                "无动画 scrollToItem 在第 $attempts 次尝试后成功到达底部。"
                            )
                        } else if (isActive) {
                            Log.d(
                                "ChatScreenInitScroll",
                                "无动画 scrollToItem 第 $attempts 次尝试后未到达底部。"
                            )
                        }
                    } catch (e: CancellationException) {
                        Log.d("ChatScreenInitScroll", "初始滚动 scrollToItem 尝试被取消。")
                        throw e
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e(
                                "ChatScreenInitScroll",
                                "无动画 scrollToItem 第 $attempts 次尝试失败: ${e.message}",
                                e
                            )
                        }
                        break
                    }
                }

                if (!successfullyScrolled && isActive) {
                    Log.w(
                        "ChatScreenInitScroll",
                        "多次无动画 scrollToItem 尝试后仍未到达底部。将调用 scrollToBottomGuaranteed 作为最终手段。"
                    )
                    scrollToBottomGuaranteed("InitialScroll_FallbackAfterImmediateAttempts")
                } else if (successfullyScrolled) {
                    programmaticallyScrolling = false
                } else if (!isActive) {
                    Log.d(
                        "ChatScreenInitScroll",
                        "初始滚动协程在完成前被取消。确保 programmaticallyScrolling 已重置。"
                    )
                    if (programmaticallyScrolling) programmaticallyScrolling = false
                }
            }

        } else { // messages is empty
            Log.d(
                "ChatScreenInitScroll",
                "消息列表为空 (延迟后)。滚动到顶部。loadedHistoryIndex: $currentLoadedIndex, messages.size: ${messages.size}"
            )
            coroutineScope.launch {
                programmaticallyScrolling = true
                userManuallyScrolledAwayFromBottom = false
                try {
                    listState.scrollToItem(0)
                } catch (e: Exception) {
                    Log.e("ChatScreenInitScroll", "滚动到顶部失败 (空列表): ${e.message}", e)
                } finally {
                    programmaticallyScrolling = false
                }
            }
        }
    }


    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var isUserConsideredActive by remember { mutableStateOf(true) }

    val imeInsets = WindowInsets.ime
    var pendingMessageText by remember { mutableStateOf<String?>(null) }

    val normalBottomPaddingForAIChat = 16.dp
    val estimatedInputAreaHeight = remember { 100.dp }

    val resetInactivityTimer: () -> Unit = {
        lastInteractionTime = System.currentTimeMillis()
        if (!isUserConsideredActive) isUserConsideredActive = true
    }

    LaunchedEffect(lastInteractionTime) {
        isUserConsideredActive = true
        delay(USER_INACTIVITY_TIMEOUT_MS)
        if (isActive) isUserConsideredActive = false
    }

    LaunchedEffect(pendingMessageText, imeInsets, density) {
        snapshotFlow { imeInsets.getBottom(density) > 0 }
            .distinctUntilChanged()
            .filter { isVisible -> !isVisible && pendingMessageText != null }
            .collect {
                pendingMessageText?.let { msg ->
                    viewModel.onSendMessage(msg)
                    pendingMessageText = null
                }
            }
    }

    LaunchedEffect(text) {
        if (text.isNotEmpty() || (text.isEmpty() && viewModel.text.value.isNotEmpty())) {
            resetInactivityTimer()
        }
    }

    val screenWidth = configuration.screenWidthDp.dp
    val bubbleMaxWidth =
        remember(screenWidth) { (screenWidth * 0.8f).coerceAtMost(600.dp) }
    val codeBlockViewWidth =
        remember(screenWidth) { (screenWidth * 0.9f).coerceAtMost(700.dp) }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag) {
                    resetInactivityTimer()
                    if (available.y < -0.5f && !userManuallyScrolledAwayFromBottom) {
                        userManuallyScrolledAwayFromBottom = true
                        Log.d(
                            "ScrollState",
                            "用户向上拖动 (available.y: ${available.y}), 设置 userManuallyScrolledAwayFromBottom = true"
                        )
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (listState.isScrollInProgress) {
                    resetInactivityTimer()
                    if (available.y < -50f && !userManuallyScrolledAwayFromBottom) {
                        userManuallyScrolledAwayFromBottom = true
                        Log.d(
                            "ScrollState",
                            "用户向上Fling (available.y: ${available.y}), 设置 userManuallyScrolledAwayFromBottom = true"
                        )
                    }
                }
                return Velocity.Zero
            }
        }
    }

    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            if (messages.isEmpty() || layoutInfo.visibleItemsInfo.isEmpty()) true
            else layoutInfo.visibleItemsInfo.any { it.index == messages.size }
        }
    }

    LaunchedEffect(isAtBottom, listState.isScrollInProgress, programmaticallyScrolling) {
        if (!listState.isScrollInProgress && !programmaticallyScrolling && isAtBottom && userManuallyScrolledAwayFromBottom) {
            Log.d(
                "ScrollState",
                "用户手动滚动到底部或程序滚动结束后在底部，重置 userManuallyScrolledAwayFromBottom = false"
            )
            userManuallyScrolledAwayFromBottom = false
        }
    }

    LaunchedEffect(Unit) {
        viewModel.scrollToBottomEvent.collectLatest {
            Log.d("ChatScreen", "收到 scrollToBottomEvent from ViewModel.")
            resetInactivityTimer()
            if (!isAtBottom || messages.isEmpty()) {
                scrollToBottomGuaranteed("ViewModelEvent")
            } else if (userManuallyScrolledAwayFromBottom) {
                userManuallyScrolledAwayFromBottom = false
            }
        }
    }

    val streamingAiMessage = remember(messages, currentStreamingAiMessageId) {
        messages.find { it.id == currentStreamingAiMessageId }
    }

    LaunchedEffect(
        streamingAiMessage?.text,
        isApiCalling,
        userManuallyScrolledAwayFromBottom,
        isAtBottom
    ) {
        if (isApiCalling && streamingAiMessage != null && streamingAiMessage.sender == Sender.AI && !userManuallyScrolledAwayFromBottom) {
            delay(REALTIME_SCROLL_CHECK_DELAY_MS)
            if (isActive && !isAtBottom && !userManuallyScrolledAwayFromBottom) {
                Log.d("RealtimeScroll", "AI流式输出内容变化, 自动滚动...")
                scrollToBottomGuaranteed("AI_Streaming_RealTime_Fix")
            }
        }
    }

    LaunchedEffect(isApiCalling) {
        snapshotFlow { isApiCalling }
            .filter { !it }
            .distinctUntilChanged()
            .collectLatest {
                if (messages.isNotEmpty()) {
                    val lastMessage = messages.last()
                    if (lastMessage.sender == Sender.AI && !userManuallyScrolledAwayFromBottom) {
                        Log.d(
                            "ScrollLogic",
                            "AI response likely finished, checking for final scroll."
                        )
                        delay(FINAL_SCROLL_DELAY_MS)
                        if (isActive && !isAtBottom && !userManuallyScrolledAwayFromBottom) {
                            Log.d("ScrollLogic", "AI response finished, performing final scroll.")
                            resetInactivityTimer()
                            scrollToBottomGuaranteed("AI_Response_Fully_Completed")
                        }
                    }
                }
            }
    }

    val scrollToBottomButtonVisible by remember {
        derivedStateOf {
            val result = messages.isNotEmpty() &&
                    !isAtBottom &&
                    isUserConsideredActive &&
                    userManuallyScrolledAwayFromBottom
            Log.d(
                "FABVisibility",
                "messagesNotEmpty: ${messages.isNotEmpty()}, notAtBottom: ${!isAtBottom}, userActive: $isUserConsideredActive, manuallyScrolledAway: $userManuallyScrolledAwayFromBottom -> Visible: $result"
            )
            result
        }
    }

    val showEditDialog by viewModel.showEditDialog.collectAsState()
    val editDialogInputText by viewModel.editDialogInputText.collectAsState()
    val showSourcesDialog by viewModel.showSourcesDialog.collectAsState()
    val sourcesForDialog by viewModel.sourcesForDialog.collectAsState()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { resetInactivityTimer() }) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.White,
        topBar = {
            AppTopBar(
                selectedConfigName = selectedApiConfig?.model ?: "选择配置",
                onMenuClick = { coroutineScope.launch { viewModel.drawerState.open() } },
                onSettingsClick = { navController.navigate(Screen.SETTINGS_SCREEN) }
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = scrollToBottomButtonVisible,
                enter = fadeIn(animationSpec = tween(220)),
                exit = fadeOut(animationSpec = tween(150))
            ) {
                FloatingActionButton(
                    onClick = { resetInactivityTimer(); scrollToBottomGuaranteed("FAB_Click") },
                    modifier = Modifier.padding(bottom = estimatedInputAreaHeight + 16.dp + 15.dp),
                    shape = RoundedCornerShape(28.dp),
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 4.dp,
                        pressedElevation = 6.dp
                    )
                ) { Icon(Icons.Filled.ArrowDownward, "滚动到底部") }
            }
        },
        floatingActionButtonPosition = FabPosition.End
    ) { scaffoldPaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPaddingValues)
                .background(Color.White)
                .imePadding()
                .navigationBarsPadding()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (messages.isEmpty()) {
                    EmptyChatAnimation(density = density)
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White)
                            .nestedScroll(nestedScrollConnection)
                            .padding(horizontal = 8.dp),
                        state = listState,
                        contentPadding = PaddingValues(
                            top = 8.dp,
                            bottom = normalBottomPaddingForAIChat
                        )
                    ) {
                        items(items = messages, key = { it.id }) { message ->
                            val showLoading =
                                message.sender == Sender.AI &&
                                        message.id == currentStreamingAiMessageId &&
                                        isApiCalling &&
                                        !message.contentStarted &&
                                        message.text.isBlank() &&
                                        !message.isError

                            val isMainContentStreamingThisMessage =
                                message.sender == Sender.AI &&
                                        message.id == currentStreamingAiMessageId &&
                                        isApiCalling &&
                                        message.contentStarted

                            val isReasoningStreamingThisMessage =
                                message.sender == Sender.AI &&
                                        message.id == currentStreamingAiMessageId &&
                                        isApiCalling &&
                                        !message.reasoning.isNullOrBlank() &&
                                        !(reasoningCompleteMap[message.id] ?: false)

                            MessageBubble(
                                message = message,
                                viewModel = viewModel,
                                onUserInteraction = { resetInactivityTimer() },
                                isMainContentStreaming = isMainContentStreamingThisMessage,
                                isReasoningStreaming = isReasoningStreamingThisMessage,
                                isReasoningComplete = (reasoningCompleteMap[message.id] ?: false),
                                maxWidth = bubbleMaxWidth,
                                codeBlockFixedWidth = codeBlockViewWidth,
                                showLoadingBubble = showLoading,
                                onEditRequest = { msg ->
                                    resetInactivityTimer(); viewModel.requestEditMessage(msg)
                                },
                                onRegenerateRequest = { userMsg ->
                                    resetInactivityTimer(); viewModel.regenerateAiResponse(userMsg)
                                }
                            )
                        }
                        item(key = "footer") {
                            Spacer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(0.dp)
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        6.dp,
                        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        clip = false
                    )
                    .background(
                        Color.White,
                        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                    )
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .heightIn(min = estimatedInputAreaHeight)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { viewModel.onTextChange(it); resetInactivityTimer() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { if (it.isFocused) resetInactivityTimer() }
                        .padding(bottom = 1.dp),
                    placeholder = { Text("输入消息…") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                        errorBorderColor = MaterialTheme.colorScheme.error,
                    ),
                    minLines = 1, maxLines = 5,
                    shape = RoundedCornerShape(16.dp),
                    trailingIcon = {
                        if (text.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel.onTextChange(""); resetInactivityTimer() },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Clear,
                                    "清除内容",
                                    Modifier.size(20.dp),
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 4.dp)
                ) {
                    IconButton(
                        onClick = { resetInactivityTimer(); viewModel.toggleWebSearchMode(!isWebSearchEnabled) },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 8.dp)
                            .size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.TravelExplore,
                            contentDescription = if (isWebSearchEnabled) "关闭联网搜索" else "开启联网搜索",
                            tint = if (isWebSearchEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    val isKeyboardVisible by remember(imeInsets, density) {
                        derivedStateOf { imeInsets.getBottom(density) > 0 }
                    }
                    FilledIconButton(
                        onClick = {
                            resetInactivityTimer()
                            if (isApiCalling) {
                                viewModel.onCancelAPICall()
                            } else if (text.isNotBlank() && selectedApiConfig != null) {
                                if (isKeyboardVisible) {
                                    pendingMessageText = text
                                    viewModel.onTextChange("")
                                    keyboardController?.hide()
                                } else {
                                    viewModel.onSendMessage(text)
                                }
                            } else if (selectedApiConfig == null) viewModel.showSnackbar("请先选择 API 配置")
                            else viewModel.showSnackbar("请输入消息内容")
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 8.dp)
                            .size(44.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color.Black,
                            contentColor = Color.White,
                            disabledContainerColor = Color.DarkGray,
                            disabledContentColor = Color.LightGray
                        )
                    ) {
                        Icon(
                            if (isApiCalling) Icons.Filled.Stop else Icons.Filled.ArrowUpward,
                            if (isApiCalling) "停止" else "发送",
                            Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        if (showEditDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissEditDialog() },
                containerColor = Color.White,
                title = { Text("编辑消息", color = Color.Black) },
                text = {
                    OutlinedTextField(
                        value = editDialogInputText,
                        onValueChange = viewModel::onEditDialogTextChanged,
                        modifier = Modifier.fillMaxWidth(), label = { Text("消息内容") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.Black,
                            unfocusedTextColor = Color.Black,
                            cursorColor = Color.Black,
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                            disabledContainerColor = Color.White,
                            focusedBorderColor = Color.Black,
                            unfocusedBorderColor = Color.Black,
                        ),
                        singleLine = false, maxLines = 5, shape = RoundedCornerShape(8.dp)
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.confirmMessageEdit() },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.Black)
                    ) { Text("确定") }
                },
                dismissButton = {
                    TextButton(
                        onClick = { viewModel.dismissEditDialog() },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.Black)
                    ) { Text("取消") }
                },
                shape = RoundedCornerShape(20.dp)
            )
        }

        if (showSourcesDialog) {
            WebSourcesDialog(
                sources = sourcesForDialog,
                onDismissRequest = { viewModel.dismissSourcesDialog() }
            )
        }
    }
}

@Composable
fun EmptyChatAnimation(density: Density) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(16.dp), Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
            val style = LocalTextStyle.current.copy(
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.Black,
                fontFamily = FontFamily.SansSerif
            )
            Text("你好", style = style)
            val animY = remember { List(3) { Animatable(0f) } }
            LaunchedEffect(Unit) {
                animY.forEach { it.snapTo(0f) }
                try {
                    repeat(5) { cycle ->
                        if (!isActive) throw CancellationException("你好动画取消")
                        val amp = with(density) { (-8).dp.toPx() } * (0.7f.pow(cycle))
                        val dur = (250 + (600 - 250) * (cycle.toFloat() / 4f)).toInt()
                        coroutineScope {
                            animY.forEachIndexed { i, a ->
                                launch {
                                    delay(150L * i); a.animateTo(
                                    amp,
                                    tween(dur, easing = FastOutSlowInEasing)
                                ); a.animateTo(0f, tween(dur, easing = FastOutSlowInEasing))
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    Log.d("Animation", "你好动画已取消"); animY.forEach { it.snapTo(0f) }
                }
            }
            animY.forEach {
                Text(
                    ".",
                    style = style,
                    modifier = Modifier.offset(y = with(density) { it.value.toDp() })
                )
            }
        }
    }
}