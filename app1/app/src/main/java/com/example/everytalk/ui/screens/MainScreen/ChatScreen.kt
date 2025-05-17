package com.example.everytalk.ui.screens.MainScreen

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.example.everytalk.ui.screens.BubbleMain.Main.MessageBubble
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow
import kotlinx.coroutines.yield

private const val USER_INACTIVITY_TIMEOUT_MS = 2000L // 用户不活动超时时间 (毫秒)
private const val REALTIME_SCROLL_CHECK_DELAY_MS = 50L // AI流式输出时，检查是否需要实时滚动的延迟 (毫秒)
private const val FINAL_SCROLL_DELAY_MS = 150L       // AI响应完全结束后，执行最终滚动的延迟 (毫秒)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    var userManuallyScrolledAwayFromBottom by remember { mutableStateOf(false) }
    var ongoingScrollJob by remember { mutableStateOf<Job?>(null) }
    var programmaticallyScrolling by remember { mutableStateOf(false) }

    // 新: 更强大的滚动到底部实现
    fun scrollToBottomGuaranteed(reason: String = "Unknown") {
        ongoingScrollJob?.cancel()
        ongoingScrollJob = coroutineScope.launch {
            programmaticallyScrolling = true
            val bottomIndex = messages.size    // footer index
            var reached = false
            repeat(12) { i -> // 最多12帧内保证到底, 足够保险
                yield()
                if (i == 0) {
                    listState.scrollToItem(bottomIndex)
                } else {
                    listState.animateScrollToItem(bottomIndex)
                }
                delay(16L)
                val items = listState.layoutInfo.visibleItemsInfo
                reached = items.any { it.index == bottomIndex }
                // 首帧 visibleItemsInfo 为空要继续重试
                if (reached && items.isNotEmpty()) {
                    Log.d("ScrollJob", "成功将 footer 滚动可见 ($reason retry=$i)")
                    userManuallyScrolledAwayFromBottom = false
                    programmaticallyScrolling = false
                    ongoingScrollJob = null
                    return@launch
                }
            }
            // 还没到底，再最后兜底一波
            delay(40)
            listState.animateScrollToItem(bottomIndex)
            userManuallyScrolledAwayFromBottom = false
            Log.d("ScrollJob", "最终兜底滚动 footer ($reason)")
            programmaticallyScrolling = false
            ongoingScrollJob = null
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
    val bubbleMaxWidth = remember(screenWidth) { (screenWidth * 0.8f).coerceAtMost(600.dp) }
    val codeBlockViewWidth = remember(screenWidth) { (screenWidth * 0.9f).coerceAtMost(700.dp) }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag) {
                    resetInactivityTimer()
                    if (available.y < 0 && listState.canScrollForward && !userManuallyScrolledAwayFromBottom) {
                        userManuallyScrolledAwayFromBottom = true
                        Log.d("ScrollState", "用户向上拖动，标记手动离开底部。")
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (listState.isScrollInProgress) {
                    resetInactivityTimer()
                    if (available.y < -50 && listState.canScrollForward && !userManuallyScrolledAwayFromBottom) {
                        userManuallyScrolledAwayFromBottom = true
                        Log.d("ScrollState", "用户向上Fling，标记手动离开底部。")
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
            userManuallyScrolledAwayFromBottom = false
            Log.d("ScrollState", "到达底部，重置 userManuallyScrolledAwayFromBottom = false")
        }
    }

    LaunchedEffect(Unit) {
        viewModel.scrollToBottomEvent.collectLatest {
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
        derivedStateOf { messages.isNotEmpty() && !isAtBottom && isUserConsideredActive && userManuallyScrolledAwayFromBottom }
    }

    val showEditDialog by viewModel.showEditDialog.collectAsState()
    val editDialogInputText by viewModel.editDialogInputText.collectAsState()

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
                    containerColor = Color.White, contentColor = Color.Black,
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
                            val isStreamingThis =
                                message.sender == Sender.AI && message.id == currentStreamingAiMessageId && isApiCalling && message.contentStarted
                            val showLoading =
                                message.sender == Sender.AI && message.id == currentStreamingAiMessageId && isApiCalling && !message.contentStarted && message.text.isBlank() && message.reasoning.isNullOrBlank()
                            MessageBubble(
                                message = message,
                                viewModel = viewModel,
                                onUserInteraction = { resetInactivityTimer() },
                                isMainContentStreaming = isStreamingThis,
                                isReasoningStreaming = (message.sender == Sender.AI && message.id == currentStreamingAiMessageId && isApiCalling && !message.reasoning.isNullOrBlank() && !(reasoningCompleteMap[message.id]
                                    ?: false) && message.contentStarted),
                                isReasoningComplete = (reasoningCompleteMap[message.id] ?: false),
                                maxWidth = bubbleMaxWidth,
                                codeBlockFixedWidth = codeBlockViewWidth,
                                showLoadingBubble = showLoading,
                                onEditRequest = { msg ->
                                    resetInactivityTimer(); viewModel.requestEditMessage(
                                    msg
                                )
                                },
                                onRegenerateRequest = { userMsg ->
                                    resetInactivityTimer(); viewModel.regenerateAiResponse(
                                    userMsg
                                )
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

            // 输入区域
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(6.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), false)
                    .background(Color.White, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
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
                    // 联网搜索按钮
                    IconButton(
                        onClick = {
                            resetInactivityTimer()
                            viewModel.toggleWebSearchMode(!isWebSearchEnabled)
                        },
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

                    // 发送/停止按钮
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
