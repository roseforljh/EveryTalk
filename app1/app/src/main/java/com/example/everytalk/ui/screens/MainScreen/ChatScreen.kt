package com.example.everytalk.ui.screens.MainScreen

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
// import androidx.compose.animation.ExperimentalAnimationApi // 如果您没有用到相关的实验性API，可以注释掉或移除
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
// import androidx.compose.ui.text.style.TextAlign // 如果 EmptyChatAnimation 中 TextAlign 没有用到，可以移除
// import androidx.compose.ui.text.style.TextOverflow // 如果没有用到 TextOverflow，可以移除
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
import com.example.everytalk.ui.components.WebSourcesDialog // 确保这个路径和你在上一步创建的文件路径一致
import com.example.everytalk.ui.screens.BubbleMain.Main.MessageBubble
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlin.coroutines.cancellation.CancellationException // 确保导入
import kotlin.math.pow
import kotlinx.coroutines.yield // 确保导入

// 文件顶部的常量保持不变
private const val USER_INACTIVITY_TIMEOUT_MS = 2000L // 用户不活动超时时间 (毫秒)
private const val REALTIME_SCROLL_CHECK_DELAY_MS = 50L // AI流式输出时，检查是否需要实时滚动的延迟 (毫秒)
private const val FINAL_SCROLL_DELAY_MS = 150L       // AI响应完全结束后，执行最终滚动的延迟 (毫秒)

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class
) // ExperimentalLayoutApi 可能来自 imePadding() 或 navigationBarsPadding()
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
    val reasoningCompleteMap = viewModel.reasoningCompleteMap // 来自ViewModel，用于判断思考过程是否完成

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

    // 滚动到底部函数的实现保持不变
    fun scrollToBottomGuaranteed(reason: String = "Unknown") {
        ongoingScrollJob?.cancel()
        ongoingScrollJob = coroutineScope.launch {
            programmaticallyScrolling = true
            val bottomIndex = messages.size    // footer index (LazyColumn中 items 的数量 + footer item)
            var reached = false
            // 尝试多次滚动确保到达底部
            // (这里的具体实现与之前提供的代码一致，此处省略以保持简洁，实际代码中应保留完整实现)
            repeat(12) { i ->
                yield() // 允许其他协程运行
                if (i == 0) {
                    listState.scrollToItem(bottomIndex)
                } else {
                    // 使用动画滚动，避免生硬跳转
                    listState.animateScrollToItem(bottomIndex)
                }
                delay(16L) // 等待一帧的时间
                val items = listState.layoutInfo.visibleItemsInfo
                reached = items.any { it.index == bottomIndex } // 检查占位符是否可见
                if (reached && items.isNotEmpty()) {
                    Log.d("ScrollJob", "成功将 footer 滚动可见 ($reason retry=$i)")
                    userManuallyScrolledAwayFromBottom = false // 到达底部，重置手动滚动状态
                    programmaticallyScrolling = false
                    ongoingScrollJob = null
                    return@launch
                }
            }
            // 如果多次尝试后仍未成功，执行最终的动画滚动作为兜底
            delay(40) // 短暂延迟
            listState.animateScrollToItem(bottomIndex)
            userManuallyScrolledAwayFromBottom = false
            Log.d("ScrollJob", "最终兜底滚动 footer ($reason)")
            programmaticallyScrolling = false
            ongoingScrollJob = null
        }
    }

    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var isUserConsideredActive by remember { mutableStateOf(true) }

    val imeInsets = WindowInsets.ime // 获取输入法窗口的 Insets
    var pendingMessageText by remember { mutableStateOf<String?>(null) } // 用于键盘隐藏时发送消息

    val normalBottomPaddingForAIChat = 16.dp // AI聊天区域底部内边距
    val estimatedInputAreaHeight = remember { 100.dp } // 估算的输入区域高度，用于FAB定位

    // 重置用户不活动计时器的lambda表达式
    val resetInactivityTimer: () -> Unit = {
        lastInteractionTime = System.currentTimeMillis()
        if (!isUserConsideredActive) isUserConsideredActive = true
    }

    // 用户不活动计时器副作用
    LaunchedEffect(lastInteractionTime) {
        isUserConsideredActive = true // 每次交互后都认为是活跃的
        delay(USER_INACTIVITY_TIMEOUT_MS) // 延迟一段时间
        if (isActive) isUserConsideredActive = false // 如果协程仍然活跃（未被取消），则认为用户不活跃
    }

    // 键盘隐藏时发送待发送消息的副作用
    LaunchedEffect(pendingMessageText, imeInsets, density) {
        snapshotFlow { imeInsets.getBottom(density) > 0 } // 观察键盘是否可见
            .distinctUntilChanged() // 仅在可见性变化时触发
            .filter { isVisible -> !isVisible && pendingMessageText != null } // 当键盘从可见变为不可见，且有待发送消息时
            .collect {
                pendingMessageText?.let { msg ->
                    viewModel.onSendMessage(msg) // 发送消息
                    pendingMessageText = null // 清空待发送消息
                }
            }
    }

    // 输入框文本变化时重置不活动计时器
    LaunchedEffect(text) {
        if (text.isNotEmpty() || (text.isEmpty() && viewModel.text.value.isNotEmpty())) { // 包含了清空文本的情况
            resetInactivityTimer()
        }
    }

    val screenWidth = configuration.screenWidthDp.dp
    val bubbleMaxWidth =
        remember(screenWidth) { (screenWidth * 0.8f).coerceAtMost(600.dp) } // 消息气泡最大宽度
    val codeBlockViewWidth =
        remember(screenWidth) { (screenWidth * 0.9f).coerceAtMost(700.dp) } // 代码块固定宽度

    // 嵌套滚动连接器，用于检测用户滚动行为
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag) { // 用户拖动
                    resetInactivityTimer()
                    if (available.y < 0 && listState.canScrollForward && !userManuallyScrolledAwayFromBottom) {
                        userManuallyScrolledAwayFromBottom = true // 标记用户手动向上滚动，离开了底部
                        Log.d("ScrollState", "用户向上拖动，标记手动离开底部。")
                    }
                }
                return Offset.Zero // 不消耗滚动事件
            }

            override suspend fun onPreFling(available: Velocity): Velocity { // 用户快速滑动 (Fling)
                if (listState.isScrollInProgress) {
                    resetInactivityTimer()
                    if (available.y < -50 && listState.canScrollForward && !userManuallyScrolledAwayFromBottom) {
                        userManuallyScrolledAwayFromBottom = true // 标记用户手动向上Fling，离开了底部
                        Log.d("ScrollState", "用户向上Fling，标记手动离开底部。")
                    }
                }
                return Velocity.Zero // 不消耗Fling事件
            }
        }
    }

    // 判断当前是否滚动在底部
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            if (messages.isEmpty() || layoutInfo.visibleItemsInfo.isEmpty()) true // 无消息或无可见项，视为在底部
            else layoutInfo.visibleItemsInfo.any { it.index == messages.size } // 检查占位符 footer 是否可见
        }
    }

    // 当滚动停止在底部时，重置手动滚动标记
    LaunchedEffect(isAtBottom, listState.isScrollInProgress, programmaticallyScrolling) {
        if (!listState.isScrollInProgress && !programmaticallyScrolling && isAtBottom && userManuallyScrolledAwayFromBottom) {
            userManuallyScrolledAwayFromBottom = false
            Log.d("ScrollState", "到达底部，重置 userManuallyScrolledAwayFromBottom = false")
        }
    }

    // 监听 ViewModel 发出的滚动到底部事件
    LaunchedEffect(Unit) {
        viewModel.scrollToBottomEvent.collectLatest {
            resetInactivityTimer()
            if (!isAtBottom || messages.isEmpty()) { // 如果不在底部或消息为空
                scrollToBottomGuaranteed("ViewModelEvent") // 保证滚动到底部
            } else if (userManuallyScrolledAwayFromBottom) { // 如果在底部但之前是用户手动滚开的
                userManuallyScrolledAwayFromBottom = false // 重置手动滚动标记
            }
        }
    }

    // 获取当前正在流式传输的AI消息对象
    val streamingAiMessage = remember(messages, currentStreamingAiMessageId) {
        messages.find { it.id == currentStreamingAiMessageId }
    }

    // AI流式输出时，如果用户未手动滚动且未在底部，则自动滚动
    LaunchedEffect(
        streamingAiMessage?.text,
        isApiCalling,
        userManuallyScrolledAwayFromBottom,
        isAtBottom
    ) {
        if (isApiCalling && streamingAiMessage != null && streamingAiMessage.sender == Sender.AI && !userManuallyScrolledAwayFromBottom) {
            delay(REALTIME_SCROLL_CHECK_DELAY_MS) // 短暂延迟检查
            if (isActive && !isAtBottom && !userManuallyScrolledAwayFromBottom) { // 确保协程活跃且条件满足
                Log.d("RealtimeScroll", "AI流式输出内容变化, 自动滚动...")
                scrollToBottomGuaranteed("AI_Streaming_RealTime_Fix")
            }
        }
    }

    // AI响应完全结束后，如果用户未手动滚动且未在底部，则执行最终滚动
    LaunchedEffect(isApiCalling) {
        snapshotFlow { isApiCalling }
            .filter { !it } // 当 isApiCalling 从 true 变为 false 时
            .distinctUntilChanged() // 避免重复触发
            .collectLatest {
                if (messages.isNotEmpty()) {
                    val lastMessage = messages.last()
                    if (lastMessage.sender == Sender.AI && !userManuallyScrolledAwayFromBottom) {
                        Log.d(
                            "ScrollLogic",
                            "AI response likely finished, checking for final scroll."
                        )
                        delay(FINAL_SCROLL_DELAY_MS) // 延迟等待内容完全渲染
                        if (isActive && !isAtBottom && !userManuallyScrolledAwayFromBottom) {
                            Log.d("ScrollLogic", "AI response finished, performing final scroll.")
                            resetInactivityTimer()
                            scrollToBottomGuaranteed("AI_Response_Fully_Completed")
                        }
                    }
                }
            }
    }

    // “滚动到底部”按钮的可见性状态
    val scrollToBottomButtonVisible by remember {
        derivedStateOf {
            messages.isNotEmpty() && // 有消息
                    !isAtBottom &&          // 当前不在底部
                    isUserConsideredActive && // 用户被认为是活跃的 (避免在用户离开界面后还显示)
                    userManuallyScrolledAwayFromBottom // 用户之前是手动向上滚动的
        }
    }

    val showEditDialog by viewModel.showEditDialog.collectAsState() // 编辑对话框可见性
    val editDialogInputText by viewModel.editDialogInputText.collectAsState() // 编辑对话框输入文本

    val showSourcesDialog by viewModel.showSourcesDialog.collectAsState() // “查看来源”对话框可见性
    val sourcesForDialog by viewModel.sourcesForDialog.collectAsState() // “查看来源”对话框的数据

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { resetInactivityTimer() }) }, // 点击屏幕重置不活动计时器
        contentWindowInsets = WindowInsets(0, 0, 0, 0), // 消费所有窗口insets，由内部组件处理
        containerColor = Color.White, // Scaffold背景色
        topBar = {
            AppTopBar( // 自定义顶部栏
                selectedConfigName = selectedApiConfig?.model ?: "选择配置",
                onMenuClick = { coroutineScope.launch { viewModel.drawerState.open() } }, // 打开抽屉菜单
                onSettingsClick = { navController.navigate(Screen.SETTINGS_SCREEN) } // 跳转到设置界面
            )
        },
        floatingActionButton = { // 悬浮操作按钮 (滚动到底部)
            AnimatedVisibility(
                visible = scrollToBottomButtonVisible,
                enter = fadeIn(animationSpec = tween(220)), // 进入动画
                exit = fadeOut(animationSpec = tween(150))  // 退出动画
            ) {
                FloatingActionButton(
                    onClick = { resetInactivityTimer(); scrollToBottomGuaranteed("FAB_Click") },
                    modifier = Modifier.padding(bottom = estimatedInputAreaHeight + 16.dp + 15.dp), // FAB位置，避开输入区域
                    shape = RoundedCornerShape(28.dp), // FAB形状
                    containerColor = Color.White, contentColor = Color.Black, // FAB颜色
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 4.dp,
                        pressedElevation = 6.dp
                    ) // FAB阴影
                ) { Icon(Icons.Filled.ArrowDownward, "滚动到底部") }
            }
        },
        floatingActionButtonPosition = FabPosition.End // FAB位置在右下角
    ) { scaffoldPaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPaddingValues) // 应用Scaffold提供的padding，避免内容与TopBar重叠
                .background(Color.White)        // Column背景色
                .imePadding()                   // 为输入法软键盘留出空间
                .navigationBarsPadding()        // 为系统导航栏留出空间
        ) {
            Box( // 聊天消息列表区域
                modifier = Modifier
                    .weight(1f) // 占据输入框以上的所有剩余空间
                    .fillMaxWidth()
            ) {
                if (messages.isEmpty()) { // 如果没有消息
                    EmptyChatAnimation(density = density) // 显示空聊天动画
                } else { // 如果有消息
                    LazyColumn( // 消息列表
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White)
                            .nestedScroll(nestedScrollConnection) // 应用嵌套滚动连接器
                            .padding(horizontal = 8.dp), // 列表左右内边距
                        state = listState, // 控制和观察列表状态
                        contentPadding = PaddingValues(
                            top = 8.dp,
                            bottom = normalBottomPaddingForAIChat
                        ) // 列表内容上下内边距
                    ) {
                        items(items = messages, key = { it.id }) { message -> // 遍历消息并为每个消息创建UI项
                            // --- 核心：showLoading 的判断条件 ---
                            // 这个条件决定了是否为AI消息显示“加载中...”的提示气泡。
                            // 当AI正在响应、主要文本内容（message.text）尚未开始、且消息不是错误时为true。
                            val showLoading =
                                message.sender == Sender.AI &&                              // 1. 是AI发送的消息
                                        message.id == currentStreamingAiMessageId &&                // 2. 是当前ViewModel中正在流式处理的AI消息ID
                                        isApiCalling &&                                             // 3. ViewModel中标记API调用正在进行中
                                        !message.contentStarted &&                                  // 4. 关键: Message对象的contentStarted为false (表示主要文本内容还没开始)
                                        message.text.isBlank() &&                                   // 5. 关键: Message对象的文本内容当前为空
                                        !message.isError                                            // 6. 消息本身不是一个错误消息
                            // ------------------------------------

                            val isMainContentStreamingThisMessage = // 当前消息的主内容是否正在流式传输
                                message.sender == Sender.AI &&
                                        message.id == currentStreamingAiMessageId &&
                                        isApiCalling &&
                                        message.contentStarted // contentStarted 为 true 表示主要文本已开始

                            val isReasoningStreamingThisMessage = // 当前消息的思考过程是否正在流式传输
                                message.sender == Sender.AI &&
                                        message.id == currentStreamingAiMessageId &&
                                        isApiCalling &&
                                        !message.reasoning.isNullOrBlank() && // 有思考内容
                                        !(reasoningCompleteMap[message.id] ?: false) // 且思考过程未标记为完成
                            // && message.contentStarted // 如果 reasoning 可能先于 main content 出现，并且希望它独立显示，可以移除这个条件
                            // 但由于 showLoading 的存在，当 showLoading=true 时，这里不会直接影响。
                            // 当 showLoading=false 后，此条件会影响 reasoning UI 的显示。

                            MessageBubble( // 显示单个消息气泡
                                message = message,
                                viewModel = viewModel,
                                onUserInteraction = { resetInactivityTimer() },
                                isMainContentStreaming = isMainContentStreamingThisMessage,
                                isReasoningStreaming = isReasoningStreamingThisMessage,
                                isReasoningComplete = (reasoningCompleteMap[message.id]
                                    ?: false), // 思考过程是否已完成
                                maxWidth = bubbleMaxWidth,
                                codeBlockFixedWidth = codeBlockViewWidth,
                                showLoadingBubble = showLoading, // << 将计算得到的 showLoading 传递给 MessageBubble
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
                        item(key = "footer") { // 列表底部的占位符，用于精确滚动到底部
                            Spacer(modifier = Modifier
                                .fillMaxWidth()
                                .height(0.dp)) // 高度为0，不占实际空间
                        }
                    }
                }
            }

            // 输入区域 (Column 包裹，有阴影和圆角)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        6.dp,
                        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        clip = false
                    ) // 顶部阴影
                    .background(
                        Color.White,
                        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                    ) // 背景和圆角
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) // 裁剪圆角，确保阴影在外部
                    .padding(horizontal = 8.dp, vertical = 8.dp) // 内边距
                    .heightIn(min = estimatedInputAreaHeight) // 最小高度，确保即使文本很少，输入区域也有一定高度
            ) {
                OutlinedTextField( // 文本输入框
                    value = text,
                    onValueChange = { viewModel.onTextChange(it); resetInactivityTimer() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester) // 焦点请求器
                        .onFocusChanged { if (it.isFocused) resetInactivityTimer() } // 获得焦点时重置不活动计时器
                        .padding(bottom = 1.dp), // 底部微小边距
                    placeholder = { Text("输入消息…") },
                    colors = OutlinedTextFieldDefaults.colors(
                        // 自定义输入框颜色样式
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedBorderColor = Color.Transparent, // 无边框效果
                        unfocusedBorderColor = Color.Transparent,
                        disabledBorderColor = Color.Transparent,
                        errorBorderColor = MaterialTheme.colorScheme.error,
                    ),
                    minLines = 1, maxLines = 5, // 最小/最大行数
                    shape = RoundedCornerShape(16.dp), // 输入框圆角
                    trailingIcon = { // 输入框尾部图标 (清除按钮)
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
                Box( // 底部按钮行 (联网搜索、发送/停止)
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 4.dp)
                ) {
                    IconButton( // 联网搜索切换按钮
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

                    val isKeyboardVisible by remember(
                        imeInsets,
                        density
                    ) { derivedStateOf { imeInsets.getBottom(density) > 0 } }
                    FilledIconButton( // 发送/停止按钮
                        onClick = {
                            resetInactivityTimer()
                            if (isApiCalling) { // 如果正在调用API
                                viewModel.onCancelAPICall() // 取消API调用
                            } else if (text.isNotBlank() && selectedApiConfig != null) { // 如果文本不为空且已选择API配置
                                if (isKeyboardVisible) { // 如果键盘可见
                                    pendingMessageText = text // 将当前文本存为待发送
                                    viewModel.onTextChange("") // 清空输入框
                                    keyboardController?.hide()   // 隐藏键盘 (发送操作会在键盘隐藏后触发)
                                } else { // 如果键盘不可见
                                    viewModel.onSendMessage(text) // 直接发送消息
                                }
                            } else if (selectedApiConfig == null) viewModel.showSnackbar("请先选择 API 配置")
                            else viewModel.showSnackbar("请输入消息内容")
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 8.dp)
                            .size(44.dp),
                        shape = CircleShape, // 圆形按钮
                        colors = IconButtonDefaults.filledIconButtonColors( // 自定义按钮颜色
                            containerColor = Color.Black,
                            contentColor = Color.White,
                            disabledContainerColor = Color.DarkGray,
                            disabledContentColor = Color.LightGray
                        )
                    ) {
                        Icon(
                            if (isApiCalling) Icons.Filled.Stop else Icons.Filled.ArrowUpward, // 根据API调用状态显示不同图标
                            if (isApiCalling) "停止" else "发送",
                            Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // 编辑消息对话框 (逻辑保持不变)
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

        // “查看来源”对话框 (逻辑保持不变)
        if (showSourcesDialog) {
            WebSourcesDialog(
                sources = sourcesForDialog,
                onDismissRequest = { viewModel.dismissSourcesDialog() }
            )
        }
    }
}

// EmptyChatAnimation Composable 函数保持不变
@Composable
fun EmptyChatAnimation(density: Density) {
    Box(Modifier
        .fillMaxSize()
        .padding(16.dp), Alignment.Center) {
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
                        // 使用 coroutineScope 启动动画的并行执行
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