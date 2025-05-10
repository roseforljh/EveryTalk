package com.example.app1.ui.screens // 确保包名正确

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items // AndroidX Compose Foundation
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Menu // 确保导入 Menu 图标
import androidx.compose.material.icons.filled.North
import androidx.compose.material.icons.filled.Settings // 确保导入 Settings 图标
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.app1.data.models.Sender
import com.example.app1.navigation.Screen // 假设你的 Screen 对象在这里定义导航路由
import com.example.app1.ui.components.AppTopBar
import com.example.app1.AppViewModel
import com.example.app1.ui.screens.BubbleMain.Main.MessageBubble // 确保这个路径正确
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
// import kotlinx.coroutines.flow.collect // 如果只用 collectLatest，则此导入可选
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow

// 常量
private const val CHAT_SCREEN_AUTO_SCROLL_DELAY_MS = 100L // 自动滚动延迟（毫秒）
private const val CHAT_SCREEN_STREAMING_SCROLL_INTERVAL_MS = 150L // 流式传输时滚动检查间隔（毫秒）
private const val USER_INACTIVITY_TIMEOUT_MS = 5000L // 用户不活动超时（毫秒）

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    viewModel: AppViewModel,
    navController: NavController,
    modifier: Modifier = Modifier
) {
    Log.d("ChatScreen", "Recomposing. ViewModel ID: ${viewModel.viewModelInstanceIdForLogging}")
    val messages = viewModel.messages // 从 ViewModel 获取消息列表 (MutableList)
    val text by viewModel.text.collectAsState() // 收集输入框文本状态
    val selectedApiConfig by viewModel.selectedApiConfig.collectAsState() // 收集选中的API配置状态
    val isApiCalling by viewModel.isApiCalling.collectAsState() // 收集API调用状态
    val currentStreamingAiMessageId by viewModel.currentStreamingAiMessageId.collectAsState() // 收集当前流式AI消息ID
    val reasoningCompleteMap = viewModel.reasoningCompleteMap // 获取推理完成状态图
    val expandedReasoningStates = viewModel.expandedReasoningStates // 获取推理展开状态图

    val listState = rememberLazyListState() // LazyColumn 的状态，用于控制滚动
    val coroutineScope = rememberCoroutineScope() // 获取协程作用域
    val focusRequester = remember { FocusRequester() } // 用于请求焦点
    val keyboardController = LocalSoftwareKeyboardController.current // 软键盘控制器
    val density = LocalDensity.current // 获取当前屏幕密度

    var userManuallyScrolledUp by remember { mutableStateOf(false) } // 标记用户是否手动向上滚动
    var ongoingScrollJob by remember { mutableStateOf<Job?>(null) } // 当前正在进行的滚动任务

    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) } // 最后一次用户交互时间
    var isUserActive by remember { mutableStateOf(true) } // 用户是否活跃

    // --- 键盘可见性状态 ---
    // 在 @Composable 上下文中访问 WindowInsets.ime (它是一个 @Composable getter)
    val imeWindowInsets = WindowInsets.ime
    // 使用 remember 和 derivedStateOf 创建一个响应式的 Boolean State
    // 当 imeWindowInsets 或 density 变化时，这个 State 会重新计算
    val isKeyboardVisible by remember(imeWindowInsets, density) {
        derivedStateOf { imeWindowInsets.getBottom(density) > 0 } // 如果键盘底部inset大于0，则键盘可见
    }
    // --- 结束键盘可见性状态 ---

    var pendingMessageText by remember { mutableStateOf<String?>(null) } // 待发送的消息文本（用于键盘隐藏后发送）


    // 重置用户不活动计时器
    val resetInactivityTimer: () -> Unit = {
        lastInteractionTime = System.currentTimeMillis()
        if (!isUserActive) {
            isUserActive = true
            Log.d("FAB_Inactivity", "用户变为活跃。重置不活动计时器。")
        }
    }

    // 用户不活动计时器
    LaunchedEffect(lastInteractionTime) {
        isUserActive = true // 假设用户一开始是活跃的
        Log.d(
            "FAB_Inactivity",
            "用户活动检测到或计时器重启。活跃 ${USER_INACTIVITY_TIMEOUT_MS}ms。"
        )
        delay(USER_INACTIVITY_TIMEOUT_MS) // 延迟超时时间
        if (isActive) { // 如果协程仍然活跃（未被取消）
            isUserActive = false // 将用户标记为不活跃
            Log.d(
                "FAB_Inactivity",
                "用户在 ${USER_INACTIVITY_TIMEOUT_MS}ms 超时后变为不活跃。隐藏 FAB。"
            )
        }
    }

    // 观察键盘可见性变化，并在键盘隐藏后发送待处理的消息
    LaunchedEffect(pendingMessageText, imeWindowInsets, density) { // 将 imeWindowInsets 添加到 keys
        snapshotFlow { imeWindowInsets.getBottom(density) > 0 } // 观察键盘底部inset高度
            .distinctUntilChanged() // 只在可见性状态真正改变时触发
            .filter { isVisible: Boolean -> !isVisible && pendingMessageText != null } // 当键盘不可见且有待处理消息时
            .collect { _ -> // 收集事件（键盘变为不可见）
                pendingMessageText?.let { messageToSend ->
                    Log.d(
                        "ChatScreen",
                        "键盘隐藏或正在隐藏，发送待处理消息: $messageToSend"
                    )
                    viewModel.onSendMessage(messageText = messageToSend) // 发送消息
                    pendingMessageText = null // 清除待处理消息标志
                }
            }
    }

    // 当输入框文本变化时，重置不活动计时器
    LaunchedEffect(text) {
        if (text.isNotEmpty() || (text.isEmpty() && viewModel.text.value.isNotEmpty())) { // 文本非空，或文本刚被清空
            resetInactivityTimer()
        }
    }

    val configuration = LocalConfiguration.current // 获取当前配置信息
    val screenWidth = configuration.screenWidthDp.dp // 获取屏幕宽度
    val bubbleMaxWidth =
        remember(screenWidth) { (screenWidth * 0.8f).coerceAtMost(600.dp) } // 计算消息气泡最大宽度
    val codeBlockViewWidth =
        remember(screenWidth) { (screenWidth * 0.9f).coerceAtMost(700.dp) } // 计算代码块视图宽度

    // 嵌套滚动连接，用于消耗列表顶部的过滚动
    val overscrollConsumingConnection = remember {
        object : NestedScrollConnection {
            // 在子项滚动前拦截滚动事件
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 如果列表不能向后滚动（即已到顶部）且用户向上拖动，则消耗掉这个滚动
                if (!listState.canScrollBackward && available.y < 0 && source == NestedScrollSource.Drag) {
                    return available
                }
                return Offset.Zero // 否则不消耗
            }

            // 在子项 fling 前拦截 fling 事件
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!listState.canScrollBackward && available.y < 0) { // 向上 fling 且已到顶部
                    return available // 消耗 fling
                }
                return Velocity.Zero
            }

            // 在子项 fling 后处理剩余 fling 事件
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (!listState.canScrollBackward && available.y < 0) { // 向上 fling 且已到顶部
                    return available
                }
                return super.onPostFling(consumed, available)
            }

            // 在子项滚动后处理剩余滚动事件
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (!listState.canScrollBackward && available.y < 0 && source == NestedScrollSource.Drag) {
                    return available
                }
                return Offset.Zero
            }
        }
    }

    // 观察列表滚动状态，判断用户是否手动向上滚动
    LaunchedEffect(listState, messages.size) {
        var lastKnownFirstVisibleIndex = listState.firstVisibleItemIndex
        var lastKnownScrollInProgress = listState.isScrollInProgress
        var lastKnownFirstVisibleScrollOffset = listState.firstVisibleItemScrollOffset

        snapshotFlow { // 将多个状态组合成一个流
            Triple(
                listState.firstVisibleItemIndex, // 第一个可见项的索引
                listState.isScrollInProgress,    // 列表是否正在滚动
                listState.firstVisibleItemScrollOffset // 第一个可见项的滚动偏移
            )
        }.collect { (currentIndex, currentScrollInProgress, currentOffset) ->
            // 如果任何滚动相关状态发生变化，重置不活动计时器
            if (currentIndex != lastKnownFirstVisibleIndex ||
                currentOffset != lastKnownFirstVisibleScrollOffset ||
                (currentScrollInProgress && !lastKnownScrollInProgress) || // 滚动开始
                (!currentScrollInProgress && lastKnownScrollInProgress)    // 滚动结束
            ) {
                resetInactivityTimer()
            }

            if (messages.isEmpty()) { // 如果消息列表为空
                if (userManuallyScrolledUp) userManuallyScrolledUp = false // 重置手动滚动标记
                // 更新最后已知状态
                lastKnownFirstVisibleIndex = currentIndex
                lastKnownScrollInProgress = currentScrollInProgress
                lastKnownFirstVisibleScrollOffset = currentOffset
                return@collect // 不再继续处理
            }

            // 判断是否已从底部滚动离开
            val scrolledAwayFromBottom =
                currentIndex > 0 || (currentIndex == 0 && currentOffset > 0)

            // 如果用户向上滚动（当前偏移小于上次偏移，或当前索引大于上次索引）
            if (scrolledAwayFromBottom && currentScrollInProgress &&
                ((currentOffset < lastKnownFirstVisibleScrollOffset && currentIndex == lastKnownFirstVisibleIndex) || // 在同一项内向上滚动
                        (currentIndex > lastKnownFirstVisibleIndex)) // 滚动到更早的消息
            ) {
                if (!userManuallyScrolledUp) { // 如果之前未标记为手动滚动
                    userManuallyScrolledUp = true
                    Log.d("ChatScroll", "用户手动向上滚动。取消正在进行的滚动任务。")
                    ongoingScrollJob?.cancel(CancellationException("用户手动向上滚动"))
                }
            }
            // 更新最后已知状态
            lastKnownFirstVisibleIndex = currentIndex
            lastKnownScrollInProgress = currentScrollInProgress
            lastKnownFirstVisibleScrollOffset = currentOffset
        }
    }

    // 处理自动滚动逻辑（新消息、AI流式响应）
    LaunchedEffect(messages.size, currentStreamingAiMessageId, isApiCalling) {
        ongoingScrollJob?.cancel(CancellationException("新的自动滚动事件")) // 取消之前的滚动任务
        if (messages.isEmpty()) {
            if (userManuallyScrolledUp) userManuallyScrolledUp = false
            return@LaunchedEffect
        }

        val targetIndex = 0 // 目标是滚动到列表顶部（UI上的底部）
        // 判断当前是否正在流式传输此消息（即最新消息）
        val isStreamingThisMessage = currentStreamingAiMessageId != null &&
                isApiCalling &&
                messages.firstOrNull()?.id == currentStreamingAiMessageId

        // 是否应该以编程方式滚动：如果用户没有手动向上滚动，或者正在流式传输当前消息
        val shouldScrollProgrammatically = !userManuallyScrolledUp || isStreamingThisMessage

        if (shouldScrollProgrammatically) {
            ongoingScrollJob = coroutineScope.launch {
                delay(CHAT_SCREEN_AUTO_SCROLL_DELAY_MS) // 短暂延迟后开始滚动
                if (!isActive) return@launch // 如果协程被取消，则返回

                try {
                    listState.animateScrollToItem(index = targetIndex) // 动画滚动到目标项
                    if (isActive) { // 滚动完成后，如果协程仍活跃
                        // 检查是否真的滚动到了底部
                        val actuallyAtBottom =
                            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                        if (actuallyAtBottom) {
                            // 如果是因为流式传输而强制滚动，即使之前用户手动滚动了，也不重置 userManuallyScrolledUp 状态
                            // 否则，如果滚动到底部，重置手动滚动标记
                            if (!(isStreamingThisMessage && userManuallyScrolledUp)) {
                                if (userManuallyScrolledUp) userManuallyScrolledUp = false
                            }
                        } else if (!userManuallyScrolledUp && !(isStreamingThisMessage && userManuallyScrolledUp)) {
                            // 如果没有手动滚动，但自动滚动后未精确到底部，记录警告
                            Log.w("ChatScroll", "自动滚动完成但未精确到达底部。")
                        }
                    }
                } catch (e: CancellationException) {
                    Log.d("ChatScroll", "自动滚动：动画被取消：${e.message}")
                } catch (e: Exception) {
                    Log.e("ChatScroll", "自动滚动：动画期间发生错误：${e.message}", e)
                }

                // 如果正在流式传输消息，则持续尝试滚动到底部，直到流结束或用户手动滚动
                if (isStreamingThisMessage && isActive) {
                    try {
                        while (isActive && currentStreamingAiMessageId != null && isApiCalling && !userManuallyScrolledUp) {
                            val firstVisible = listState.firstVisibleItemIndex
                            val firstVisibleOffset = listState.firstVisibleItemScrollOffset
                            // 如果未在底部（第一个可见项不是0，或有偏移），则滚动
                            if (firstVisible > 0 || firstVisibleOffset > with(density) { 1.dp.toPx() }) { // 允许1dp的误差
                                listState.animateScrollToItem(index = targetIndex)
                            }
                            delay(CHAT_SCREEN_STREAMING_SCROLL_INTERVAL_MS) // 等待一段时间再检查
                        }
                    } catch (e: CancellationException) { /* 预期中的取消 */
                    } catch (e: Exception) {
                        Log.e("ChatScroll", "流式滚动错误：${e.message}", e)
                    }
                }
            }.also { job -> // 将任务保存到 ongoingScrollJob，并在其完成时清空
                job.invokeOnCompletion {
                    if (ongoingScrollJob === job) ongoingScrollJob = null // 确保只清空当前任务
                }
            }
        }
    }

    // 监听 ViewModel 发出的强制滚动事件
    LaunchedEffect(Unit) {
        viewModel.scrollToBottomEvent.collectLatest {
            Log.d("ChatScroll", "从 ViewModel 收到 scrollToBottomEvent。")
            ongoingScrollJob?.cancel(CancellationException("来自 ViewModel 的强制滚动事件 (scrollToBottom)"))
            ongoingScrollJob = coroutineScope.launch {
                try {
                    if (messages.isNotEmpty()) listState.animateScrollToItem(0) // 滚动到顶部
                    if (userManuallyScrolledUp) userManuallyScrolledUp = false // 重置手动滚动标记
                } catch (e: Exception) {
                    Log.e("ChatScroll", "强制滚动 (scrollToBottom) 错误：${e.message}", e)
                }
            }.also { job -> // 同样，管理 ongoingScrollJob
                job.invokeOnCompletion {
                    if (ongoingScrollJob === job) ongoingScrollJob = null
                }
            }
        }
    }

    // 当AI消息的推理部分完成后，如果它是展开的，则折叠它
    LaunchedEffect(messages.size, reasoningCompleteMap) {
        messages.forEach { msg ->
            if (msg.sender == Sender.AI && !msg.reasoning.isNullOrBlank()) {
                val isComplete = reasoningCompleteMap[msg.id] ?: false // 推理是否完成
                if (isComplete && expandedReasoningStates[msg.id] == true) { // 如果完成且已展开
                    viewModel.collapseReasoning(msg.id) // 折叠推理部分
                }
            }
        }
    }

    // 判断当前是否在列表底部
    val isAtBottom by remember {
        derivedStateOf {
            if (messages.isEmpty()) true // 列表为空则认为在底部
            else listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 // 否则检查索引和偏移
        }
    }

    // 当滚动到底部且之前是手动向上滚动状态时，重置手动滚动标记
    LaunchedEffect(isAtBottom, userManuallyScrolledUp, listState.isScrollInProgress) {
        if (isAtBottom && userManuallyScrolledUp && !listState.isScrollInProgress) { // 确保不在滚动过程中
            Log.d("ChatScroll", "到达底部，重置 userManuallyScrolledUp。")
            userManuallyScrolledUp = false
        }
    }

    // 控制“滚动到底部”按钮的可见性
    val scrollToBottomButtonVisible by remember(
        userManuallyScrolledUp,
        listState.isScrollInProgress,
        messages.size,
        isAtBottom,
        isUserActive // 添加用户活跃状态作为依赖
    ) {
        derivedStateOf {
            val baseVisibility =
                userManuallyScrolledUp && !listState.isScrollInProgress && messages.isNotEmpty() // 基本条件：手动向上滚动，不在滚动中，列表非空
            baseVisibility && !isAtBottom && isUserActive // 额外条件：未在底部，且用户活跃
        }
    }

    // 收集编辑对话框相关的状态
    val showEditDialog by viewModel.showEditDialog.collectAsState()
    val editDialogInputText by viewModel.editDialogInputText.collectAsState()

    Scaffold( // 主界面脚手架
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { resetInactivityTimer() }) }, // 点击屏幕重置不活动计时器
        contentWindowInsets = WindowInsets(0, 0, 0, 0), // 通常设为0，让内部Column处理IME
        containerColor = Color.White, // 脚手架背景色
        topBar = { // 顶部应用栏
            AppTopBar(
                selectedConfigName = selectedApiConfig?.model ?: "选择配置", // 显示选中的配置名称
                onMenuClick = { coroutineScope.launch { viewModel.drawerState.open() } }, // 点击菜单按钮打开抽屉
                onSettingsClick = { navController.navigate(Screen.SETTINGS_SCREEN) } // 点击设置按钮导航到设置屏幕 (确保Screen.SETTINGS_SCREEN有route属性)
            )
        },
        floatingActionButton = { // 悬浮操作按钮 (滚动到底部)
            AnimatedVisibility( // 带动画的可见性控制
                visible = scrollToBottomButtonVisible, // 根据计算的可见性状态显示/隐藏
                enter = fadeIn(animationSpec = tween(300)) + scaleIn( // 进入动画
                    animationSpec = tween(
                        300,
                        easing = FastOutSlowInEasing
                    ), initialScale = 0.8f
                ),
                exit = fadeOut(animationSpec = tween(200)) + scaleOut( // 退出动画
                    animationSpec = tween(200),
                    targetScale = 0.8f
                )
            ) {
                FloatingActionButton(
                    onClick = {
                        resetInactivityTimer() // 重置不活动计时器
                        coroutineScope.launch {
                            ongoingScrollJob?.cancel(CancellationException("FAB (滚动到底部) 被点击")) // 取消当前滚动
                            if (messages.isNotEmpty()) listState.animateScrollToItem(0) // 滚动到顶部
                            if (userManuallyScrolledUp) userManuallyScrolledUp = false // 重置手动滚动标记
                        }
                    },
                    modifier = Modifier.padding(bottom = 160.dp), // 调整FAB位置以避开输入区域
                    shape = RoundedCornerShape(16.dp), // FAB形状
                    containerColor = Color.White, // FAB背景色
                    contentColor = Color.Black, // FAB内容颜色
                    elevation = FloatingActionButtonDefaults.elevation( // FAB阴影
                        defaultElevation = 0.dp,
                        pressedElevation = 0.dp,
                        focusedElevation = 0.dp,
                        hoveredElevation = 0.dp
                    )
                ) {
                    Icon(Icons.Filled.ArrowDownward, contentDescription = "滚动到底部")
                }
            }
        },
        floatingActionButtonPosition = FabPosition.End // FAB位置
    ) { scaffoldPaddingValues -> // Scaffold 内容区域
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPaddingValues) // 应用Scaffold提供的padding
                .windowInsetsPadding(WindowInsets.ime) // 这个Column负责响应IME的显示和隐藏
                .navigationBarsPadding() // 如果需要，也处理导航栏的padding
        ) {
            LazyColumn( // 消息列表
                modifier = Modifier
                    .weight(1f) // 占据剩余空间
                    .fillMaxWidth()
                    .nestedScroll(overscrollConsumingConnection) // 应用嵌套滚动连接
                    .padding(horizontal = 6.dp),
                state = listState, // 列表状态
                reverseLayout = true, // 反向布局，新消息在底部
                contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp) // 内容边距
            ) {
                items( // 遍历消息列表
                    items = messages,
                    key = { message -> message.id } // 为每个消息指定唯一键
                ) { message ->
                    // 判断AI消息的主要内容是否正在流式传输
                    val isMainContentStreaming = message.sender == Sender.AI &&
                            message.id == currentStreamingAiMessageId &&
                            isApiCalling &&
                            message.contentStarted

                    MessageBubble( // 显示单个消息气泡
                        message = message,
                        viewModel = viewModel,
                        onUserInteraction = { resetInactivityTimer() }, // 用户与气泡交互时重置计时器
                        isMainContentStreaming = isMainContentStreaming,
                        // 判断AI消息的推理部分是否正在流式传输
                        isReasoningStreaming = (message.sender == Sender.AI &&
                                message.id == currentStreamingAiMessageId &&
                                isApiCalling &&
                                !message.reasoning.isNullOrBlank() &&
                                !(reasoningCompleteMap[message.id] ?: false) && // 推理未完成
                                message.contentStarted), // 内容已开始
                        isReasoningComplete = reasoningCompleteMap[message.id] ?: false, // 推理是否已完成
                        isManuallyExpanded = expandedReasoningStates[message.id]
                            ?: false, // 推理是否手动展开
                        onToggleReasoning = { // 切换推理展开/折叠的回调
                            resetInactivityTimer()
                            viewModel.onToggleReasoningExpand(message.id)
                        },
                        maxWidth = bubbleMaxWidth, // 气泡最大宽度
                        codeBlockFixedWidth = codeBlockViewWidth, // 代码块固定宽度
                        // 是否显示加载中的气泡（AI消息占位符，内容未开始）
                        showLoadingBubble = (message.sender == Sender.AI &&
                                message.id == currentStreamingAiMessageId &&
                                isApiCalling &&
                                !message.contentStarted &&
                                message.text.isBlank() &&
                                message.reasoning.isNullOrBlank()),
                        onEditRequest = { msgToEdit -> // 请求编辑消息的回调
                            resetInactivityTimer()
                            viewModel.requestEditMessage(msgToEdit)
                        },
                        onRegenerateRequest = { userMessageForRegen -> // 请求重新生成AI回复的回调
                            resetInactivityTimer()
                            viewModel.regenerateAiResponse(userMessageForRegen)
                        }
                    )
                }

                // 如果消息列表为空，显示欢迎语和动画
                if (messages.isEmpty()) {
                    item {
                        Box( // 居中容器
                            modifier = Modifier
                                .fillParentMaxSize() // 填充LazyColumn的全部可用空间
                                .padding(16.dp),
                            contentAlignment = Alignment.Center // 内容居中
                        ) {
                            Row( // “你好...”文本和动画点
                                verticalAlignment = Alignment.Bottom, // 垂直底部对齐
                                horizontalArrangement = Arrangement.Center // 水平居中
                            ) {
                                val textStyle = LocalTextStyle.current.copy( // 文本样式
                                    fontSize = 36.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.Black
                                )
                                Text(text = "你好", style = textStyle) // “你好”文本
                                // --- 三个点的跳动动画 ---
                                val initialAmplitudeDp = (-8).dp; // 初始振幅
                                val amplitudeDecayFactor = 0.7f; // 振幅衰减因子
                                val numCycles = 5; // 循环次数
                                val initialBouncePhaseDurationMs = 250; // 初始弹跳阶段时长
                                val finalBouncePhaseDurationMs = 600; // 最终弹跳阶段时长
                                val dotInterDelayMs = 150L // 点之间的延迟
                                val initialAmplitudePx =
                                    with(density) { initialAmplitudeDp.toPx() } // dp转px
                                val dot1OffsetY = remember { Animatable(0f) }; // 第一个点的Y偏移
                                val dot2OffsetY = remember { Animatable(0f) }; // 第二个点的Y偏移
                                val dot3OffsetY = remember { Animatable(0f) } // 第三个点的Y偏移
                                LaunchedEffect(Unit) { // 启动动画协程
                                    dot1OffsetY.snapTo(0f); dot2OffsetY.snapTo(0f); dot3OffsetY.snapTo(
                                        0f
                                    ); repeat(numCycles) { cycleIndex -> // 重复指定次数
                                        val currentCycleAmplitudePx = // 当前循环的振幅
                                            initialAmplitudePx * (amplitudeDecayFactor.pow(
                                                cycleIndex
                                            ));
                                        val progress = // 当前进度 (0到1)
                                            if (numCycles > 1) cycleIndex.toFloat() / (numCycles - 1) else 0f;
                                        val currentBouncePhaseDurationMs = // 当前弹跳阶段时长
                                            (initialBouncePhaseDurationMs + (finalBouncePhaseDurationMs - initialBouncePhaseDurationMs) * progress).toInt();
                                        coroutineScope { // 并行启动三个点的动画
                                            launch { // 第一个点
                                                dot1OffsetY.animateTo( //向上跳
                                                    currentCycleAmplitudePx,
                                                    tween(
                                                        currentBouncePhaseDurationMs,
                                                        easing = FastOutSlowInEasing
                                                    )
                                                ); dot1OffsetY.animateTo( // 回到原位
                                                    0f,
                                                    tween(
                                                        currentBouncePhaseDurationMs,
                                                        easing = FastOutSlowInEasing
                                                    )
                                                )
                                            };
                                            launch { // 第二个点
                                                delay(dotInterDelayMs); dot2OffsetY.animateTo(
                                                    currentCycleAmplitudePx,
                                                    tween(
                                                        currentBouncePhaseDurationMs,
                                                        easing = FastOutSlowInEasing
                                                    )
                                                ); dot2OffsetY.animateTo(
                                                    0f,
                                                    tween(
                                                        currentBouncePhaseDurationMs,
                                                        easing = FastOutSlowInEasing
                                                    )
                                                )
                                            };
                                            launch { // 第三个点
                                                delay(dotInterDelayMs * 2); dot3OffsetY.animateTo(
                                                    currentCycleAmplitudePx,
                                                    tween(
                                                        currentBouncePhaseDurationMs,
                                                        easing = FastOutSlowInEasing
                                                    )
                                                ); dot3OffsetY.animateTo(
                                                    0f,
                                                    tween(
                                                        currentBouncePhaseDurationMs,
                                                        easing = FastOutSlowInEasing
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                                Text( // 第一个点
                                    ".",
                                    style = textStyle,
                                    modifier = Modifier.offset(y = with(density) { dot1OffsetY.value.toDp() }) // 应用Y偏移
                                )
                                Text( // 第二个点
                                    ".",
                                    style = textStyle,
                                    modifier = Modifier.offset(y = with(density) { dot2OffsetY.value.toDp() })
                                )
                                Text( // 第三个点
                                    ".",
                                    style = textStyle,
                                    modifier = Modifier.offset(y = with(density) { dot3OffsetY.value.toDp() })
                                )
                            }
                        }
                    }
                }
            }

            Column( // 输入区域的 Column
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow( // 顶部阴影，营造层次感
                        elevation = 8.dp,
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        clip = false // 允许阴影超出边界
                    )
                    .background( // 背景色和圆角
                        color = Color.White,
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                    )
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) // 裁剪内容以匹配圆角
                    // 确保移除了 animateContentSize()，如果之前还在的话
                    .padding(horizontal = 8.dp, vertical = 8.dp) // 内边距
            ) {
                OutlinedTextField( // 文本输入框
                    value = text, // 当前文本值
                    onValueChange = { viewModel.onTextChange(it) }, // 文本变化回调
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester) // 关联焦点请求器
                        .onFocusChanged { if (it.isFocused) resetInactivityTimer() } // 获得焦点时重置计时器
                        .padding(bottom = 4.dp),
                    placeholder = { Text("输入消息…") }, // 占位提示
                    colors = OutlinedTextFieldDefaults.colors( // 文本框颜色配置
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedBorderColor = Color.Transparent, // 无边框效果
                        unfocusedBorderColor = Color.Transparent,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = Color.Gray
                    ),
                    minLines = 1, maxLines = 5, // 最小/最大行数
                    trailingIcon = { // 尾随图标（清除按钮）
                        val showClearButton = text.isNotBlank() && !isApiCalling // 文本非空且API未调用时显示
                        AnimatedVisibility( // 带动画的可见性
                            visible = showClearButton,
                            enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.9f),
                            exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.7f)
                        ) {
                            IconButton(
                                onClick = { viewModel.onTextChange(""); resetInactivityTimer() }, // 点击清空文本并重置计时器
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
                Box( // 发送/停止按钮容器
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 0.dp, end = 8.dp, bottom = 8.dp),
                    contentAlignment = Alignment.CenterEnd // 内容靠右对齐
                ) {
                    IconButton( // 发送/停止按钮
                        onClick = {
                            resetInactivityTimer() // 重置不活动计时器
                            if (isApiCalling) { // 如果API正在调用
                                viewModel.onCancelAPICall() // 取消API调用
                            } else if (viewModel.text.value.isNotBlank() && selectedApiConfig != null) { // 如果文本非空且已选配置
                                val currentText = viewModel.text.value
                                // 直接使用 isKeyboardVisible 这个 Boolean State
                                if (isKeyboardVisible) { // 如果键盘可见
                                    pendingMessageText = currentText // 将当前文本设为待处理
                                    viewModel.onTextChange("") // 立即清空输入框的UI显示
                                    keyboardController?.hide() // 请求隐藏键盘
                                    Log.d(
                                        "ChatScreen",
                                        "键盘可见。隐藏键盘，消息待处理: $currentText"
                                    )
                                } else { // 如果键盘已经隐藏
                                    Log.d(
                                        "ChatScreen",
                                        "键盘已隐藏。直接发送消息: $currentText"
                                    )
                                    viewModel.onSendMessage(messageText = currentText) // 直接发送
                                }
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape) // 圆形裁剪
                            .background(Color.Black), // 背景色
                        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White) // 图标颜色
                    ) {
                        Icon( // 根据API调用状态显示不同图标
                            if (isApiCalling) Icons.Filled.Stop else Icons.Filled.North, // 停止或发送图标
                            if (isApiCalling) "停止" else "发送", // 内容描述
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        // 编辑消息对话框
        if (showEditDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissEditDialog() }, // 点击外部或返回键时关闭
                title = { Text("编辑消息", color = Color.Black) },
                text = { // 对话框内容区域
                    OutlinedTextField(
                        value = editDialogInputText, // 当前编辑框文本
                        onValueChange = { viewModel.onEditDialogTextChanged(it) }, // 文本变化回调
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("消息内容", color = Color.DarkGray) },
                        textStyle = TextStyle(color = Color.Black, fontSize = 16.sp),
                        colors = OutlinedTextFieldDefaults.colors( // 编辑框颜色
                            focusedTextColor = Color.Black,
                            unfocusedTextColor = Color.Black,
                            cursorColor = MaterialTheme.colorScheme.primary,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.LightGray,
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                            disabledContainerColor = Color.White,
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            unfocusedLabelColor = Color.Gray
                        ),
                        singleLine = false, maxLines = 5, // 多行输入
                        shape = RoundedCornerShape(8.dp) // 圆角
                    )
                },
                confirmButton = { // 确认按钮
                    TextButton(onClick = { viewModel.confirmMessageEdit() }) {
                        Text(
                            "确定",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                dismissButton = { // 取消按钮
                    TextButton(onClick = { viewModel.dismissEditDialog() }) {
                        Text(
                            "取消",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                shape = RoundedCornerShape(20.dp), // 对话框整体圆角
                containerColor = Color.White, // 对话框背景色
                titleContentColor = Color.Black, // 标题颜色
                textContentColor = Color.Black // 内容文本颜色 (此处text Composable的颜色已单独设置)
            )
        }
    }
}