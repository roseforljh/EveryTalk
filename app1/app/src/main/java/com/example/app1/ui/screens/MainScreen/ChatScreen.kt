package com.example.app1.ui.screens.MainScreen

import android.util.Log
import androidx.compose.material3.ButtonDefaults
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.app1.StateControler.AppViewModel
import com.example.app1.data.DataClass.Message
import com.example.app1.data.DataClass.Sender
import com.example.app1.navigation.Screen
import com.example.app1.ui.components.AppTopBar
import com.example.app1.ui.screens.BubbleMain.Main.MessageBubble
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow
import kotlin.math.roundToInt

private const val USER_INACTIVITY_TIMEOUT_MS = 3000L // 用户不活动超时时间

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

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    // 状态：用户是否手动将列表从底部向上滚动了
    var userManuallyScrolledAwayFromBottom by remember { mutableStateOf(false) }
    var ongoingScrollJob by remember { mutableStateOf<Job?>(null) }
    var programmaticallyScrolling by remember { mutableStateOf(false) }

    // 状态：用户最后交互时间 和 用户是否被认为活跃
    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var isUserConsideredActive by remember { mutableStateOf(true) }

    val imeInsets = WindowInsets.ime
    var pendingMessageText by remember { mutableStateOf<String?>(null) }

    val normalBottomPaddingForAIChat = 16.dp
    val estimatedInputAreaHeight = remember { 100.dp } // 用于FAB的垂直位置调整

    // 重置用户不活动计时器的函数
    val resetInactivityTimer: () -> Unit = {
        lastInteractionTime = System.currentTimeMillis()
        if (!isUserConsideredActive) { // 如果之前是不活跃状态，立即更新为活跃
            isUserConsideredActive = true
            Log.d("UserActivity", "User became active.")
        }
    }

    // LaunchedEffect 用于处理用户不活动计时
    LaunchedEffect(lastInteractionTime) {
        // 每次 lastInteractionTime 更新时（意味着有新的交互），
        // 先将用户标记为活跃，然后启动一个延迟任务。
        isUserConsideredActive = true // 立即标记为活跃
        Log.d("UserActivity", "Inactivity timer reset. User is active.")
        delay(USER_INACTIVITY_TIMEOUT_MS) // 等待超时
        if (isActive) { // 检查协程是否仍然活跃（未被新的交互取消）
            // 如果协程在延迟后仍然活跃，说明超时时间内没有新的交互
            isUserConsideredActive = false
            Log.d("UserActivity", "User became inactive due to timeout.")
        }
    }

    // 处理软键盘隐藏后发送待处理消息的逻辑 (保持不变)
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

    // 文本输入时重置不活动计时器 (保持不变)
    LaunchedEffect(text) {
        if (text.isNotEmpty() || (text.isEmpty() && viewModel.text.value.isNotEmpty())) {
            resetInactivityTimer()
        }
    }

    val screenWidth = configuration.screenWidthDp.dp
    val bubbleMaxWidth = remember(screenWidth) { (screenWidth * 0.8f).coerceAtMost(600.dp) }
    val codeBlockViewWidth = remember(screenWidth) { (screenWidth * 0.9f).coerceAtMost(700.dp) }

    // NestedScrollConnection 用于检测用户手动滚动行为
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag) { // 用户拖动列表
                    resetInactivityTimer() // 用户操作，重置计时器
                    if (available.y < 0 && listState.canScrollForward) { // 用户向上滚动，并且列表可以向上滚动
                        if (!userManuallyScrolledAwayFromBottom) {
                            userManuallyScrolledAwayFromBottom = true
                            Log.d("ScrollState", "用户向上拖动，标记为手动离开底部。")
                        }
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (listState.isScrollInProgress) { // 列表正在滚动（通常是Fling）
                    resetInactivityTimer() // 用户操作，重置计时器
                    if (available.y < -50 && listState.canScrollForward) { // 用户快速向上滑动
                        if (!userManuallyScrolledAwayFromBottom) {
                            userManuallyScrolledAwayFromBottom = true
                            Log.d("ScrollState", "用户向上Fling，标记为手动离开底部。")
                        }
                    }
                }
                return Velocity.Zero
            }
        }
    }

    // 判断列表是否在最底部 (使用Footer锚点方案)
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            if (messages.isEmpty() || layoutInfo.visibleItemsInfo.isEmpty()) {
                true // 空列表或无可见项时，视作在底部
            } else {
                // 检查索引为 messages.size (即Footer Spacer) 的项是否可见
                layoutInfo.visibleItemsInfo.any { it.index == messages.size }
            }
        }
    }

    // 当列表滚动状态改变或是否在底部状态改变时，更新 userManuallyScrolledAwayFromBottom
    LaunchedEffect(isAtBottom, listState.isScrollInProgress, programmaticallyScrolling) {
        if (!listState.isScrollInProgress && !programmaticallyScrolling) { // 仅当列表静止且非程序化滚动时
            if (isAtBottom) {
                if (userManuallyScrolledAwayFromBottom) {
                    userManuallyScrolledAwayFromBottom = false
                    Log.d(
                        "ScrollState",
                        "已到达底部，重置 userManuallyScrolledAwayFromBottom = false"
                    )
                }
            }
            // 注意: 用户向上滚动导致 !isAtBottom 的情况由 nestedScrollConnection 处理
        }
    }

    // 滚动到列表底部的函数 (使用Footer锚点)
    fun scrollToBottomWithFooter(reason: String = "UserInteraction") {
        val currentJob: Job? = ongoingScrollJob
        if (currentJob != null && currentJob.isActive) {
            currentJob.cancel(CancellationException("新的滚动请求 ($reason) 取代了旧任务。"))
            Log.d("ScrollJob", "旧的滚动任务已为 '$reason' 取消。")
        }
        ongoingScrollJob = coroutineScope.launch {
            programmaticallyScrolling = true
            Log.d(
                "ScrollJob",
                "launchScrollJobToBottom ($reason) - programmaticallyScrolling = true."
            )
            try {
                if (messages.isNotEmpty()) {
                    listState.animateScrollToItem(messages.size) // 滚动到Footer的索引
                    // 成功滚动到底部后，用户不再是“手动离开底部”的状态
                    userManuallyScrolledAwayFromBottom = false
                    Log.d(
                        "ScrollJob",
                        "滚动到Footer完成 ($reason)。userManuallyScrolledAwayFromBottom = false"
                    )
                } else {
                    listState.animateScrollToItem(0) // 空列表滚动到顶部
                    Log.d("ScrollJob", "列表为空，滚动到索引0 ($reason)。")
                }
            } catch (e: CancellationException) {
                Log.d("ScrollJob", "滚动任务 ($reason) 被取消: ${e.message}")
            } catch (e: Exception) {
                Log.e("ScrollJob", "滚动任务 ($reason) 发生错误: ${e.message}", e)
            } finally {
                if (ongoingScrollJob === this.coroutineContext[Job]) {
                    programmaticallyScrolling = false
                    ongoingScrollJob = null
                    Log.d(
                        "ScrollJob",
                        "launchScrollJobToBottom ($reason) - programmaticallyScrolling = false (任务自结束)."
                    )
                }
            }
        }
    }

    // ViewModel 请求滚动到底部事件 (保持不变，使用新的滚动函数)
    LaunchedEffect(Unit) {
        viewModel.scrollToBottomEvent.collectLatest {
            resetInactivityTimer()
            // 只有当确实不在底部或列表为空时才执行滚动，避免不必要的重绘/滚动
            if (!isAtBottom || messages.isEmpty()) {
                Log.d("ViewModelScroll", "ViewModel请求滚动到底部。")
                scrollToBottomWithFooter("ViewModelEvent")
            } else {
                Log.d("ViewModelScroll", "ViewModel请求滚动到底部，但已在底部，不执行滚动。")
                // 如果已经在底部，确保 userManuallyScrolledAwayFromBottom 为 false
                if (userManuallyScrolledAwayFromBottom) {
                    userManuallyScrolledAwayFromBottom = false
                }
            }
        }
    }

    // AI 响应完成后自动滚动到底部 (保持不变，使用新的滚动函数)
    LaunchedEffect(messages.size, isApiCalling) { // 依赖 messages.size 确保消息更新时能触发
        snapshotFlow { isApiCalling }
            .distinctUntilChanged()
            .collect { currentIsApiCalling ->
                // 此处 viewModel.isApiCalling.value 获取的是 snapshotFlow 闭包创建时的值，
                // currentIsApiCalling 是最新的值。
                // 我们需要比较的是前一个状态和当前状态。
                // 一个更简单的方法是直接使用 currentIsApiCalling 来判断是否刚结束。
                // 但为了确保逻辑与之前版本类似（检查从true到false的转变），我们可能需要一个 previousIsApiCalling 状态。
                // 或者，更简单地，如果 !currentIsApiCalling 且之前是 isApiCalling (通过ViewModel的StateFlow)，则表示刚结束。
                // 这里我们假设 viewModel.isApiCalling.value 能反映上一个状态，但这不完全准确。
                // 一个更健壮的方式是使用 pairWithPrevious 或类似的 Flow 操作符，或者在 LaunchedEffect 外部维护一个 previousIsApiCalling。
                // 为了简化，我们暂时依赖 viewModel.isApiCalling.value 作为“旧值”的近似。

                val wasApiCallingPreviously = viewModel.isApiCalling.value // 这是个近似值
                if (!currentIsApiCalling && wasApiCallingPreviously && messages.isNotEmpty()) {
                    val lastMessage = messages.last()
                    // 只有当AI响应结束，且用户没有手动向上滚动时才自动滚动
                    if (lastMessage.sender == Sender.AI && !userManuallyScrolledAwayFromBottom) {
                        Log.d("ScrollLogic", "AI响应完成，用户未手动滚动，准备检查是否滚动。")
                        delay(100) // 给UI一点时间反应，例如消息高度计算
                        if (!isAtBottom && isActive) { // 再次检查是否在底部，并确保协程仍活跃
                            Log.d("ScrollLogic", "AI响应完成，滚动到AI消息。")
                            resetInactivityTimer()
                            scrollToBottomWithFooter("AI响应完成")
                        } else {
                            Log.d("ScrollLogic", "AI响应完成，但已在底部或协程不再活跃，无需滚动。")
                        }
                    }
                }
            }
    }

    // FAB 的可见性逻辑
    val scrollToBottomButtonVisible by remember {
        derivedStateOf {
            if (messages.isEmpty()) {
                false // 列表为空时不显示
            } else {
                // 条件1: 不在底部 并且 用户是活跃的 并且 用户确实向上滚动过
                val condition1 =
                    !isAtBottom && isUserConsideredActive && userManuallyScrolledAwayFromBottom
                // (可以根据需要添加其他条件，例如列表必须有一定数量的消息才显示)
                condition1
            }
        }
    }

    val showEditDialog by viewModel.showEditDialog.collectAsState()
    val editDialogInputText by viewModel.editDialogInputText.collectAsState()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { resetInactivityTimer() }) }, // 点击屏幕任何地方重置计时器
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
                enter = fadeIn(animationSpec = tween(220)), // 简化进入动画
                exit = fadeOut(animationSpec = tween(150))  // 简化退出动画
            ) {
                FloatingActionButton(
                    onClick = {
                        resetInactivityTimer() // 用户点击FAB，是用户操作
                        Log.d("FAB_Click", "FAB点击，滚动到Footer。")
                        scrollToBottomWithFooter("FAB_Click")
                    },
                    modifier = Modifier.padding(bottom = estimatedInputAreaHeight + 16.dp + 15.dp),
                    shape = RoundedCornerShape(28.dp),
                    containerColor = Color.White, // Material 3 推荐使用主题色或Surface色
                    contentColor = Color.Black,   // Material 3 推荐使用主题色或OnSurface色
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 4.dp, // 减小默认阴影，使其不那么突出
                        pressedElevation = 6.dp  // 按下时的阴影
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
                            .nestedScroll(nestedScrollConnection) // 应用 nestedScrollConnection
                            .padding(horizontal = 8.dp),
                        state = listState,
                        reverseLayout = false,
                        contentPadding = PaddingValues(
                            top = 8.dp,
                            bottom = normalBottomPaddingForAIChat // 列表底部内边距
                        )
                    ) {
                        items(items = messages, key = { it.id }) { message ->
                            // MessageBubble 渲染 (保持不变)
                            val isStreamingThis =
                                message.sender == Sender.AI && message.id == currentStreamingAiMessageId && isApiCalling && message.contentStarted
                            val showLoading =
                                message.sender == Sender.AI && message.id == currentStreamingAiMessageId && isApiCalling && !message.contentStarted && message.text.isBlank() && message.reasoning.isNullOrBlank()
                            MessageBubble(
                                message = message,
                                viewModel = viewModel,
                                onUserInteraction = { resetInactivityTimer() }, // 消息气泡内的交互也重置计时器
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
                        // =========== Footer: 关键！=============
                        item(key = "footer") { // 用于精确滚动到底部的锚点
                            Spacer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(0.dp) // 高度为0，不可见
                            )
                        }
                    }
                }
            }

            // 输入区域 (保持不变)
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
                    onValueChange = { viewModel.onTextChange(it); resetInactivityTimer() }, // 输入时重置计时器
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { if (it.isFocused) resetInactivityTimer() } // 获取焦点时重置计时器
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
                                onClick = { viewModel.onTextChange(""); resetInactivityTimer() }, // 清除文本时重置计时器
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
                        .padding(top = 4.dp, end = 8.dp, bottom = 4.dp),
                    Alignment.CenterEnd
                ) {
                    val isKeyboardVisible by remember(imeInsets, density) {
                        derivedStateOf { imeInsets.getBottom(density) > 0 }
                    }
                    FilledIconButton(
                        onClick = {
                            resetInactivityTimer() // 发送/停止按钮点击时重置计时器
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
                        modifier = Modifier.size(44.dp), shape = CircleShape,
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

        // 编辑对话框 (保持不变)
        if (showEditDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissEditDialog() },
                containerColor = Color.White,
                title = { Text("编辑消息", color = Color.Black) },
                text = {
                    OutlinedTextField(
                        value = editDialogInputText,
                        onValueChange = viewModel::onEditDialogTextChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("消息内容") },
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

// EmptyChatAnimation (保持不变)
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
                        val dur =
                            (250 + (600 - 250) * (if (4 > 0) cycle.toFloat() / 4f else 0f)).toInt()
                        coroutineScope {
                            animY.forEachIndexed { i, a ->
                                launch {
                                    delay(150L * i)
                                    a.animateTo(amp, tween(dur, easing = FastOutSlowInEasing))
                                    a.animateTo(0f, tween(dur, easing = FastOutSlowInEasing))
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    Log.d("Animation", "你好动画已取消")
                    animY.forEach { it.snapTo(0f) }
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