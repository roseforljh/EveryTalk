package com.example.app1.ui.screens.MainScreen // 确保包名正确

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.material.icons.filled.North
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
import com.example.app1.data.DataClass.Sender
import com.example.app1.navigation.Screen // 假设你的 Screen 对象在这里定义导航路由
import com.example.app1.ui.components.AppTopBar
import com.example.app1.StateControler.AppViewModel
import com.example.app1.ui.screens.BubbleMain.Main.MessageBubble // 确保这个路径正确
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow

// 常量
private const val CHAT_SCREEN_AUTO_SCROLL_DELAY_MS = 100L
private const val CHAT_SCREEN_STREAMING_SCROLL_INTERVAL_MS = 150L
private const val USER_INACTIVITY_TIMEOUT_MS = 1000L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    viewModel: AppViewModel,
    navController: NavController,
    modifier: Modifier = Modifier
) {
    Log.d("ChatScreen", "Recomposing. ViewModel ID: ${viewModel.viewModelInstanceIdForLogging}")
    val messages = viewModel.messages
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

    var userManuallyScrolledUp by remember { mutableStateOf(false) }
    var ongoingScrollJob by remember { mutableStateOf<Job?>(null) }
    var programmaticallyScrolling by remember { mutableStateOf(false) } // 新增: 标记程序是否正在滚动

    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var isUserActive by remember { mutableStateOf(true) }

    val imeWindowInsets = WindowInsets.ime
    val isKeyboardVisible by remember(imeWindowInsets, density) {
        derivedStateOf { imeWindowInsets.getBottom(density) > 0 }
    }
    var pendingMessageText by remember { mutableStateOf<String?>(null) }

    val resetInactivityTimer: () -> Unit = {
        lastInteractionTime = System.currentTimeMillis()
        if (!isUserActive) {
            isUserActive = true
            Log.d("FAB_Inactivity", "User became active. Resetting inactivity timer.")
        }
    }

    LaunchedEffect(lastInteractionTime) {
        isUserActive = true
        Log.d(
            "FAB_Inactivity",
            "User activity detected or timer restarted. Active for ${USER_INACTIVITY_TIMEOUT_MS}ms."
        )
        delay(USER_INACTIVITY_TIMEOUT_MS)
        if (isActive) {
            isUserActive = false
            Log.d(
                "FAB_Inactivity",
                "User became inactive after ${USER_INACTIVITY_TIMEOUT_MS}ms timeout. FAB may appear if not at bottom."
            )
        }
    }

    LaunchedEffect(pendingMessageText, imeWindowInsets, density) {
        snapshotFlow { imeWindowInsets.getBottom(density) > 0 }
            .distinctUntilChanged()
            .filter { isVisible: Boolean -> !isVisible && pendingMessageText != null }
            .collect { _ ->
                pendingMessageText?.let { messageToSend ->
                    Log.d(
                        "ChatScreen",
                        "Keyboard hidden or hiding, sending pending message: $messageToSend"
                    )
                    viewModel.onSendMessage(messageText = messageToSend)
                    pendingMessageText = null
                }
            }
    }

    LaunchedEffect(text) {
        if (text.isNotEmpty() || (text.isEmpty() && viewModel.text.value.isNotEmpty())) {
            resetInactivityTimer()
        }
    }

    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val bubbleMaxWidth = remember(screenWidth) { (screenWidth * 0.8f).coerceAtMost(600.dp) }
    val codeBlockViewWidth = remember(screenWidth) { (screenWidth * 0.9f).coerceAtMost(700.dp) }

    val overscrollConsumingConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag) resetInactivityTimer()
                if (!listState.canScrollBackward && available.y < 0 && source == NestedScrollSource.Drag) {
                    return available
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (listState.isScrollInProgress) resetInactivityTimer() // Fling 也是一种交互
                if (!listState.canScrollBackward && available.y < 0) {
                    return available
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (!listState.canScrollBackward && available.y < 0) {
                    return available
                }
                return super.onPostFling(consumed, available)
            }

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

    val isListActuallyScrolling by remember { // 更精确的滚动状态
        derivedStateOf { listState.isScrollInProgress }
    }

    // 检测用户手动向上滚动
    LaunchedEffect(listState, messages.size, programmaticallyScrolling) {
        var lastKnownFirstVisibleIndex = listState.firstVisibleItemIndex
        var lastKnownScrollOffset = listState.firstVisibleItemScrollOffset

        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress
            )
        }
            .collect { (currentIndex, currentOffset, isScrollingNow) ->
                if (isScrollingNow) { // 只要在滚动，就重置不活动计时器
                    resetInactivityTimer()
                }

                if (messages.isEmpty() || programmaticallyScrolling || !isScrollingNow) { // 如果是程序滚动或列表已停止，则不判断为用户手动滚动
                    if (!isScrollingNow && userManuallyScrolledUp && currentIndex == 0 && currentOffset == 0) {
                        // 如果滚动停止了，并且在底部，并且之前是手动上滑状态，则重置
                        Log.d(
                            "ChatScroll",
                            "Manually scrolled list stopped at bottom. Resetting userManuallyScrolledUp."
                        )
                        userManuallyScrolledUp = false
                    }
                    lastKnownFirstVisibleIndex = currentIndex
                    lastKnownScrollOffset = currentOffset
                    return@collect
                }


                val scrolledUp =
                    (currentIndex > lastKnownFirstVisibleIndex) || (currentIndex == lastKnownFirstVisibleIndex && currentOffset < lastKnownScrollOffset)
                val scrolledAwayFromBottom =
                    currentIndex > 0 || (currentIndex == 0 && currentOffset > 0)

                if (scrolledAwayFromBottom && scrolledUp) {
                    if (!userManuallyScrolledUp) {
                        userManuallyScrolledUp = true
                        Log.d(
                            "ChatScroll",
                            "User MANUALLY scrolled up. Cancelling ongoing auto-scroll job."
                        )
                        ongoingScrollJob?.cancel(CancellationException("User manually scrolled up (detected in snapshotFlow)"))
                    }
                }
                lastKnownFirstVisibleIndex = currentIndex
                lastKnownScrollOffset = currentOffset
            }
    }


    // 自动滚动逻辑
    LaunchedEffect(messages.size, currentStreamingAiMessageId, isApiCalling) {
        val isStreamingThisMessage = currentStreamingAiMessageId != null &&
                isApiCalling &&
                messages.firstOrNull()?.id == currentStreamingAiMessageId

        // 条件1: 用户没有手动向上滚动
        // 条件2: 或者，如果正在流式传输当前最新的消息，则忽略用户手动上滚状态，强制滚动
        if (!userManuallyScrolledUp || isStreamingThisMessage) {
            if (isListActuallyScrolling && !isStreamingThisMessage) { // 如果列表已在滚动（非流式强制），则不启动新滚动
                Log.d(
                    "ChatScroll",
                    "Auto scroll SKIPPED: List is already scrolling and not force-streaming."
                )
                return@LaunchedEffect
            }

            ongoingScrollJob?.cancel(CancellationException("New auto scroll event"))
            if (messages.isEmpty()) {
                if (userManuallyScrolledUp) userManuallyScrolledUp = false
                return@LaunchedEffect
            }

            ongoingScrollJob = coroutineScope.launch {
                programmaticallyScrolling = true // 标记程序开始滚动
                delay(CHAT_SCREEN_AUTO_SCROLL_DELAY_MS)

                // 在实际滚动前再次检查，特别是用户手动滚动状态
                if (!isActive || (userManuallyScrolledUp && !isStreamingThisMessage) || (isListActuallyScrolling && !isStreamingThisMessage)) {
                    Log.d(
                        "ChatScroll",
                        "Auto scroll CANCELLED before execution: isActive=$isActive, userManuallyScrolledUp=$userManuallyScrolledUp, isStreamingThisMessage=$isStreamingThisMessage, isListScrolling=$isListActuallyScrolling"
                    )
                    programmaticallyScrolling = false
                    return@launch
                }

                Log.d(
                    "ChatScroll",
                    "EXECUTING auto scroll. UserManuallyScrolledUp: $userManuallyScrolledUp, IsStreamingThis: $isStreamingThisMessage"
                )
                try {
                    listState.animateScrollToItem(index = 0)
                    if (isActive) {
                        val actuallyAtBottom =
                            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                        if (actuallyAtBottom) {
                            // 关键：如果是因为流式传输而强制滚动，并且用户之前是手动上滑的，
                            // 那么滚动完成后，我们不应该清除 userManuallyScrolledUp，
                            // 否则用户稍微上滑一点，FAB又不会出来了。
                            // 只有在非流式强制滚动，或者用户本来就没上滑时，才清除。
                            if (!isStreamingThisMessage && userManuallyScrolledUp) {
                                Log.d(
                                    "ChatScroll",
                                    "Auto scrolled to bottom (non-streaming override), resetting userManuallyScrolledUp."
                                )
                                userManuallyScrolledUp = false
                            } else if (userManuallyScrolledUp && !isStreamingThisMessage) { // 普通新消息滚动到底部
                                Log.d(
                                    "ChatScroll",
                                    "Auto scrolled to bottom, resetting userManuallyScrolledUp."
                                )
                                userManuallyScrolledUp = false
                            }
                        } else if (!userManuallyScrolledUp && !isStreamingThisMessage) {
                            Log.w(
                                "ChatScroll",
                                "Auto scroll completed but not precisely at bottom and was not overridden."
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    Log.d("ChatScroll", "Auto scroll animation was cancelled: ${e.message}")
                } catch (e: Exception) {
                    Log.e("ChatScroll", "Error during auto scroll animation: ${e.message}", e)
                } finally {
                    programmaticallyScrolling = false // 标记程序滚动结束
                }

                // 持续流式滚动部分
                if (isStreamingThisMessage && isActive) {
                    Log.d("ChatScroll", "Starting CONTINUOUS streaming scroll loop.")
                    try {
                        while (isActive && currentStreamingAiMessageId != null && isApiCalling && !userManuallyScrolledUp && !isListActuallyScrolling) {
                            programmaticallyScrolling = true
                            val firstVisible = listState.firstVisibleItemIndex
                            val firstVisibleOffset = listState.firstVisibleItemScrollOffset
                            if (firstVisible > 0 || firstVisibleOffset > with(density) { 1.dp.toPx() }) {
                                Log.d("ChatScroll", "Streaming loop: Not at bottom, scrolling.")
                                listState.animateScrollToItem(index = 0)
                            }
                            programmaticallyScrolling = false // 在delay前标记结束本次程序滚动
                            delay(CHAT_SCREEN_STREAMING_SCROLL_INTERVAL_MS)
                        }
                        if (userManuallyScrolledUp || isListActuallyScrolling) {
                            Log.d(
                                "ChatScroll",
                                "Streaming scroll loop TERMINATED: userManuallyScrolledUp=$userManuallyScrolledUp, isListScrolling=$isListActuallyScrolling"
                            )
                        }
                    } catch (e: CancellationException) {
                        Log.d("ChatScroll", "Continuous streaming scroll loop cancelled.")
                    } catch (e: Exception) {
                        Log.e(
                            "ChatScroll",
                            "Error in continuous streaming scroll loop: ${e.message}",
                            e
                        )
                    } finally {
                        programmaticallyScrolling = false
                    }
                }
            }.also { job ->
                job.invokeOnCompletion {
                    if (ongoingScrollJob === job) ongoingScrollJob =
                        null; programmaticallyScrolling = false
                }
            }
        } else {
            Log.d(
                "ChatScroll",
                "Auto scroll SKIPPED: userManuallyScrolledUp=$userManuallyScrolledUp, isStreamingThisMessage=$isStreamingThisMessage"
            )
        }
    }


    LaunchedEffect(Unit) {
        viewModel.scrollToBottomEvent.collectLatest {
            Log.d(
                "ChatScroll",
                "Received scrollToBottomEvent from ViewModel (e.g., after sending a message)."
            )
            ongoingScrollJob?.cancel(CancellationException("Force scroll event (scrollToBottom) from ViewModel"))
            ongoingScrollJob = coroutineScope.launch {
                programmaticallyScrolling = true
                try {
                    if (messages.isNotEmpty()) listState.animateScrollToItem(0)
                    userManuallyScrolledUp = false // ViewModel 要求滚动到底部，通常意味着用户意图在底部
                } catch (e: Exception) {
                    Log.e("ChatScroll", "Force scroll (scrollToBottom) error: ${e.message}", e)
                } finally {
                    programmaticallyScrolling = false
                }
            }.also { job ->
                job.invokeOnCompletion {
                    if (ongoingScrollJob === job) ongoingScrollJob =
                        null; programmaticallyScrolling = false
                }
            }
        }
    }

    val isAtBottom by remember {
        derivedStateOf {
            if (messages.isEmpty()) true
            else listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    // 当列表滚动停止，并且我们位于底部时，才重置 userManuallyScrolledUp
    LaunchedEffect(isAtBottom, userManuallyScrolledUp, isListActuallyScrolling) {
        if (isAtBottom && userManuallyScrolledUp && !isListActuallyScrolling) {
            Log.d("ChatScroll", "List stopped at bottom, resetting userManuallyScrolledUp.")
            userManuallyScrolledUp = false
        }
    }

    val scrollToBottomButtonVisible by remember(
        userManuallyScrolledUp,
        isAtBottom,
        isUserActive,
        messages.size,
        isListActuallyScrolling
    ) {
        derivedStateOf {
            if (messages.isEmpty() || isListActuallyScrolling) {
                false
            } else {
                !isAtBottom && (userManuallyScrolledUp || !isUserActive)
            }
        }
    }
    LaunchedEffect(scrollToBottomButtonVisible) {
        Log.d(
            "FAB_Visibility",
            "scrollToBottomButtonVisible: $scrollToBottomButtonVisible (isAtBottom: $isAtBottom, userManuallyScrolledUp: $userManuallyScrolledUp, isUserActive: $isUserActive, messagesEmpty: ${messages.isEmpty()}, isListScrolling: $isListActuallyScrolling)"
        )
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
                onSettingsClick = { navController.navigate(Screen.SETTINGS_SCREEN) })
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = scrollToBottomButtonVisible,
                enter = fadeIn(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) +
                        scaleIn(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMedium
                            ), initialScale = 0.9f
                        ) +
                        slideInVertically(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        ) { it / 4 },
                exit = fadeOut(animationSpec = tween(150, easing = FastOutSlowInEasing)) +
                        scaleOut(
                            animationSpec = tween(150, easing = FastOutSlowInEasing),
                            targetScale = 0.9f
                        ) +
                        slideOutVertically(
                            animationSpec = tween(
                                150,
                                easing = FastOutSlowInEasing
                            )
                        ) { it / 4 }
            ) {
                FloatingActionButton(
                    onClick = {
                        resetInactivityTimer()
                        coroutineScope.launch {
                            ongoingScrollJob?.cancel(CancellationException("FAB (scroll to bottom) clicked"))
                            programmaticallyScrolling = true
                            if (messages.isNotEmpty()) listState.animateScrollToItem(0)
                            userManuallyScrolledUp = false
                            programmaticallyScrolling = false // 标记结束
                        }
                    },
                    modifier = Modifier.padding(bottom = 160.dp),
                    shape = RoundedCornerShape(16.dp),
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 0.dp,
                        pressedElevation = 0.dp,
                        focusedElevation = 0.dp,
                        hoveredElevation = 0.dp
                    )
                ) { Icon(Icons.Filled.ArrowDownward, contentDescription = "滚动到底部") }
            }
        },
        floatingActionButtonPosition = FabPosition.End
    ) { scaffoldPaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPaddingValues)
                .windowInsetsPadding(WindowInsets.ime)
                .navigationBarsPadding()
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(overscrollConsumingConnection)
                    .padding(horizontal = 6.dp),
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp)
            ) {
                items(items = messages, key = { message -> message.id }) { message ->
                    val isMainContentStreaming =
                        message.sender == Sender.AI && message.id == currentStreamingAiMessageId && isApiCalling && message.contentStarted
                    MessageBubble(
                        message = message,
                        viewModel = viewModel,
                        onUserInteraction = { resetInactivityTimer() },
                        isMainContentStreaming = isMainContentStreaming,
                        isReasoningStreaming = (message.sender == Sender.AI && message.id == currentStreamingAiMessageId && isApiCalling && !message.reasoning.isNullOrBlank() && !(reasoningCompleteMap[message.id]
                            ?: false) && message.contentStarted),
                        isReasoningComplete = reasoningCompleteMap[message.id] ?: false,
                        maxWidth = bubbleMaxWidth,
                        codeBlockFixedWidth = codeBlockViewWidth,
                        showLoadingBubble = (message.sender == Sender.AI && message.id == currentStreamingAiMessageId && isApiCalling && !message.contentStarted && message.text.isBlank() && message.reasoning.isNullOrBlank()),
                        onEditRequest = { msgToEdit ->
                            resetInactivityTimer(); viewModel.requestEditMessage(
                            msgToEdit
                        )
                        },
                        onRegenerateRequest = { userMessageForRegen ->
                            resetInactivityTimer(); viewModel.regenerateAiResponse(
                            userMessageForRegen
                        )
                        }
                    )
                }
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
                                Text(text = "你好", style = textStyle)
                                val initialAmplitudeDp = (-8).dp;
                                val amplitudeDecayFactor = 0.7f;
                                val numCycles = 5;
                                val initialBouncePhaseDurationMs = 250;
                                val finalBouncePhaseDurationMs = 600;
                                val dotInterDelayMs = 150L
                                val initialAmplitudePx = with(density) { initialAmplitudeDp.toPx() }
                                val dot1OffsetY = remember { Animatable(0f) };
                                val dot2OffsetY = remember { Animatable(0f) };
                                val dot3OffsetY = remember { Animatable(0f) }
                                LaunchedEffect(Unit) {
                                    dot1OffsetY.snapTo(0f); dot2OffsetY.snapTo(0f); dot3OffsetY.snapTo(
                                    0f
                                ); repeat(numCycles) { cycleIndex ->
                                    val currentCycleAmplitudePx =
                                        initialAmplitudePx * (amplitudeDecayFactor.pow(cycleIndex));
                                    val progress =
                                        if (numCycles > 1) cycleIndex.toFloat() / (numCycles - 1) else 0f;
                                    val currentBouncePhaseDurationMs =
                                        (initialBouncePhaseDurationMs + (finalBouncePhaseDurationMs - initialBouncePhaseDurationMs) * progress).toInt();
                                    coroutineScope {
                                        launch {
                                            dot1OffsetY.animateTo(
                                                currentCycleAmplitudePx,
                                                tween(
                                                    currentBouncePhaseDurationMs,
                                                    easing = FastOutSlowInEasing
                                                )
                                            ); dot1OffsetY.animateTo(
                                            0f,
                                            tween(
                                                currentBouncePhaseDurationMs,
                                                easing = FastOutSlowInEasing
                                            )
                                        )
                                        };
                                        launch {
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
                                        launch {
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
                                Text(
                                    ".",
                                    style = textStyle,
                                    modifier = Modifier.offset(y = with(density) { dot1OffsetY.value.toDp() })
                                )
                                Text(
                                    ".",
                                    style = textStyle,
                                    modifier = Modifier.offset(y = with(density) { dot2OffsetY.value.toDp() })
                                )
                                Text(
                                    ".",
                                    style = textStyle,
                                    modifier = Modifier.offset(y = with(density) { dot3OffsetY.value.toDp() })
                                )
                            }
                        }
                    }
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        clip = false
                    )
                    .background(
                        color = Color.White,
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                    )
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = text, onValueChange = { viewModel.onTextChange(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { if (it.isFocused) resetInactivityTimer() }
                        .padding(bottom = 4.dp),
                    placeholder = { Text("输入消息…") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = Color.Gray
                    ),
                    minLines = 1, maxLines = 5,
                    trailingIcon = {
                        val showClearButton = text.isNotBlank() && !isApiCalling
                        AnimatedVisibility(
                            visible = showClearButton,
                            enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.9f),
                            exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.7f)
                        ) {
                            IconButton(
                                onClick = { viewModel.onTextChange(""); resetInactivityTimer() },
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
                        .padding(top = 0.dp, end = 8.dp, bottom = 8.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    IconButton(
                        onClick = {
                            resetInactivityTimer()
                            if (isApiCalling) {
                                viewModel.onCancelAPICall()
                            } else if (viewModel.text.value.isNotBlank() && selectedApiConfig != null) {
                                val currentText = viewModel.text.value
                                if (isKeyboardVisible) {
                                    pendingMessageText =
                                        currentText; viewModel.onTextChange(""); keyboardController?.hide(); Log.d(
                                        "ChatScreen",
                                        "Keyboard visible. Hiding keyboard, message pending: $currentText"
                                    )
                                } else {
                                    Log.d(
                                        "ChatScreen",
                                        "Keyboard hidden. Sending message directly: $currentText"
                                    ); viewModel.onSendMessage(messageText = currentText)
                                }
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.Black),
                        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                    ) {
                        Icon(
                            if (isApiCalling) Icons.Filled.Stop else Icons.Filled.North,
                            if (isApiCalling) "停止" else "发送",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
        if (showEditDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissEditDialog() },
                title = { Text("编辑消息", color = Color.Black) },
                text = {
                    OutlinedTextField(
                        value = editDialogInputText,
                        onValueChange = { viewModel.onEditDialogTextChanged(it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("消息内容", color = Color.DarkGray) },
                        textStyle = TextStyle(color = Color.Black, fontSize = 16.sp),
                        colors = OutlinedTextFieldDefaults.colors(
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
                        singleLine = false,
                        maxLines = 5,
                        shape = RoundedCornerShape(8.dp)
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.confirmMessageEdit() }) {
                        Text(
                            "确定",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissEditDialog() }) {
                        Text(
                            "取消",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                shape = RoundedCornerShape(20.dp),
                containerColor = Color.White,
                titleContentColor = Color.Black,
                textContentColor = Color.Black
            )
        }
    }
}