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
import com.example.app1.data.models.Sender
import com.example.app1.navigation.Screen
import com.example.app1.ui.components.AppTopBar
import com.example.app1.AppViewModel
import com.example.app1.ui.screens.BubbleMain.Main.MessageBubble
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow

// 常量
private const val CHAT_SCREEN_AUTO_SCROLL_DELAY_MS = 100L
private const val CHAT_SCREEN_STREAMING_SCROLL_INTERVAL_MS = 150L
private const val USER_INACTIVITY_TIMEOUT_MS = 5000L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    viewModel: AppViewModel,
    navController: NavController,
    modifier: Modifier = Modifier
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
    val density = LocalDensity.current

    var userManuallyScrolledUp by remember { mutableStateOf(false) }
    var ongoingScrollJob by remember { mutableStateOf<Job?>(null) }

    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var isUserActive by remember { mutableStateOf(true) }

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
                "User inactive after ${USER_INACTIVITY_TIMEOUT_MS}ms timeout. Hiding FAB."
            )
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
                if (!listState.canScrollBackward && available.y < 0 && source == NestedScrollSource.Drag) {
                    return available
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
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

    LaunchedEffect(listState, messages.size) {
        var lastKnownFirstVisibleIndex = listState.firstVisibleItemIndex
        var lastKnownScrollInProgress = listState.isScrollInProgress
        var lastKnownFirstVisibleScrollOffset = listState.firstVisibleItemScrollOffset

        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.isScrollInProgress,
                listState.firstVisibleItemScrollOffset
            )
        }.collect { (currentIndex, currentScrollInProgress, currentOffset) ->
            if (currentIndex != lastKnownFirstVisibleIndex ||
                currentOffset != lastKnownFirstVisibleScrollOffset ||
                (currentScrollInProgress && !lastKnownScrollInProgress) ||
                (!currentScrollInProgress && lastKnownScrollInProgress)
            ) {
                resetInactivityTimer()
            }

            if (messages.isEmpty()) {
                if (userManuallyScrolledUp) userManuallyScrolledUp = false
                lastKnownFirstVisibleIndex = currentIndex
                lastKnownScrollInProgress = currentScrollInProgress
                lastKnownFirstVisibleScrollOffset = currentOffset
                return@collect
            }

            val scrolledAwayFromBottom =
                currentIndex > 0 || (currentIndex == 0 && currentOffset > 0)

            if (scrolledAwayFromBottom && currentScrollInProgress &&
                ((currentOffset < lastKnownFirstVisibleScrollOffset && currentIndex == lastKnownFirstVisibleIndex) ||
                        (currentIndex > lastKnownFirstVisibleIndex))
            ) {
                if (!userManuallyScrolledUp) {
                    userManuallyScrolledUp = true
                    Log.d("ChatScroll", "User manually scrolled up. Cancelling ongoing scroll job.")
                    ongoingScrollJob?.cancel(CancellationException("User scrolled up manually"))
                }
            }
            lastKnownFirstVisibleIndex = currentIndex
            lastKnownScrollInProgress = currentScrollInProgress
            lastKnownFirstVisibleScrollOffset = currentOffset
        }
    }

    LaunchedEffect(messages.size, currentStreamingAiMessageId, isApiCalling) {
        ongoingScrollJob?.cancel(CancellationException("New auto-scroll event"))
        if (messages.isEmpty()) {
            if (userManuallyScrolledUp) userManuallyScrolledUp = false
            return@LaunchedEffect
        }

        val targetIndex = 0
        val isStreamingThisMessage = currentStreamingAiMessageId != null &&
                isApiCalling &&
                messages.firstOrNull()?.id == currentStreamingAiMessageId

        val shouldScrollProgrammatically = !userManuallyScrolledUp || isStreamingThisMessage

        if (shouldScrollProgrammatically) {
            ongoingScrollJob = coroutineScope.launch {
                delay(CHAT_SCREEN_AUTO_SCROLL_DELAY_MS)
                if (!isActive) return@launch

                try {
                    listState.animateScrollToItem(index = targetIndex)
                    if (isActive) {
                        val actuallyAtBottom =
                            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                        if (actuallyAtBottom) {
                            if (!(isStreamingThisMessage && userManuallyScrolledUp)) {
                                if (userManuallyScrolledUp) userManuallyScrolledUp = false
                            }
                        } else if (!userManuallyScrolledUp && !(isStreamingThisMessage && userManuallyScrolledUp)) {
                            Log.w("ChatScroll", "Auto-scroll finished but not perfectly at bottom.")
                        }
                    }
                } catch (e: CancellationException) {
                    Log.d("ChatScroll", "Auto-scroll: Animation cancelled: ${e.message}")
                } catch (e: Exception) {
                    Log.e("ChatScroll", "Auto-scroll: Error during animation: ${e.message}", e)
                }

                if (isStreamingThisMessage && isActive) {
                    try {
                        while (isActive && currentStreamingAiMessageId != null && isApiCalling && !userManuallyScrolledUp) {
                            val firstVisible = listState.firstVisibleItemIndex
                            val firstVisibleOffset = listState.firstVisibleItemScrollOffset
                            if (firstVisible > 0 || firstVisibleOffset > with(density) { 1.dp.toPx() }) {
                                listState.animateScrollToItem(index = targetIndex)
                            }
                            delay(CHAT_SCREEN_STREAMING_SCROLL_INTERVAL_MS)
                        }
                    } catch (e: CancellationException) { /* Expected */
                    } catch (e: Exception) {
                        Log.e("ChatScroll", "Streaming scroll error: ${e.message}", e)
                    }
                }
            }.also { job ->
                job.invokeOnCompletion {
                    if (ongoingScrollJob === job) ongoingScrollJob = null
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.scrollToBottomEvent.collectLatest {
            Log.d("ChatScroll", "Received scrollToBottomEvent from ViewModel.")
            ongoingScrollJob?.cancel(CancellationException("Forced scroll event (scrollToBottom) from ViewModel"))
            ongoingScrollJob = coroutineScope.launch {
                try {
                    if (messages.isNotEmpty()) listState.animateScrollToItem(0)
                    if (userManuallyScrolledUp) userManuallyScrolledUp = false
                } catch (e: Exception) {
                    Log.e("ChatScroll", "Forced scroll (scrollToBottom) error: ${e.message}", e)
                }
            }.also { job ->
                job.invokeOnCompletion {
                    if (ongoingScrollJob === job) ongoingScrollJob = null
                }
            }
        }
    }

    // The scrollToIndexEvent listener is no longer strictly needed for the new "regenerate" logic
    // as it always results in scrolling to the bottom.
    // However, if you plan to reintroduce features that require scrolling to a specific mid-list item,
    // you can uncomment and adapt it.
    /*
    val scrollToIndex by viewModel.scrollToIndexEvent.collectAsState() // Assuming you re-add this to ViewModel
    LaunchedEffect(scrollToIndex) {
        scrollToIndex?.let { index ->
            Log.d("ChatScroll", "Received scrollToIndexEvent from ViewModel: Index $index.")
            if (index >= 0 && index < messages.size) {
                ongoingScrollJob?.cancel(CancellationException("Forced scroll event (scrollToIndex) from ViewModel"))
                ongoingScrollJob = coroutineScope.launch {
                    try {
                        listState.animateScrollToItem(index)
                        Log.d("ChatScroll", "Scrolled to index $index due to event.")
                    } catch (e: Exception) {
                        Log.e("ChatScroll", "Forced scroll (scrollToIndex $index) error: ${e.message}", e)
                    }
                }.also { job -> job.invokeOnCompletion { if (ongoingScrollJob === job) ongoingScrollJob = null } }
            } else {
                Log.w("ChatScroll", "Invalid scroll to index request: $index. List size: ${messages.size}")
            }
            viewModel.consumedScrollToIndexEvent() // Assuming you re-add this to ViewModel
        }
    }
    */

    LaunchedEffect(messages.size, reasoningCompleteMap) {
        messages.forEach { msg ->
            if (msg.sender == Sender.AI && !msg.reasoning.isNullOrBlank()) {
                val isComplete = reasoningCompleteMap[msg.id] ?: false
                if (isComplete && expandedReasoningStates[msg.id] == true) {
                    viewModel.collapseReasoning(msg.id)
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

    LaunchedEffect(isAtBottom, userManuallyScrolledUp, listState.isScrollInProgress) {
        if (isAtBottom && userManuallyScrolledUp && !listState.isScrollInProgress) {
            Log.d("ChatScroll", "Reached bottom, resetting userManuallyScrolledUp.")
            userManuallyScrolledUp = false
        }
    }

    val scrollToBottomButtonVisible by remember(
        userManuallyScrolledUp,
        listState.isScrollInProgress,
        messages.size,
        isAtBottom,
        isUserActive
    ) {
        derivedStateOf {
            val baseVisibility =
                userManuallyScrolledUp && !listState.isScrollInProgress && messages.isNotEmpty()
            baseVisibility && !isAtBottom && isUserActive
        }
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
                enter = fadeIn(animationSpec = tween(300)) + scaleIn(
                    animationSpec = tween(
                        300,
                        easing = FastOutSlowInEasing
                    ), initialScale = 0.8f
                ),
                exit = fadeOut(animationSpec = tween(200)) + scaleOut(
                    animationSpec = tween(200),
                    targetScale = 0.8f
                )
            ) {
                FloatingActionButton(
                    onClick = {
                        resetInactivityTimer()
                        coroutineScope.launch {
                            ongoingScrollJob?.cancel(CancellationException("FAB (scroll to bottom) clicked"))
                            if (messages.isNotEmpty()) listState.animateScrollToItem(0)
                            if (userManuallyScrolledUp) userManuallyScrolledUp = false
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
                ) {
                    Icon(Icons.Filled.ArrowDownward, contentDescription = "滚动到底部")
                }
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
                items(
                    items = messages,
                    key = { message -> message.id }
                ) { message ->
                    val isMainContentStreaming = message.sender == Sender.AI &&
                            message.id == currentStreamingAiMessageId &&
                            isApiCalling &&
                            message.contentStarted

                    MessageBubble(
                        message = message,
                        viewModel = viewModel,
                        onUserInteraction = { resetInactivityTimer() },
                        isMainContentStreaming = isMainContentStreaming,
                        isReasoningStreaming = (message.sender == Sender.AI &&
                                message.id == currentStreamingAiMessageId &&
                                isApiCalling &&
                                !message.reasoning.isNullOrBlank() &&
                                !(reasoningCompleteMap[message.id] ?: false) &&
                                message.contentStarted),
                        isReasoningComplete = reasoningCompleteMap[message.id] ?: false,
                        isManuallyExpanded = expandedReasoningStates[message.id] ?: false,
                        onToggleReasoning = {
                            resetInactivityTimer()
                            viewModel.onToggleReasoningExpand(message.id)
                        },
                        maxWidth = bubbleMaxWidth,
                        codeBlockFixedWidth = codeBlockViewWidth,
                        showLoadingBubble = (message.sender == Sender.AI &&
                                message.id == currentStreamingAiMessageId &&
                                isApiCalling &&
                                !message.contentStarted &&
                                message.text.isBlank() &&
                                message.reasoning.isNullOrBlank()),
                        onEditRequest = { msgToEdit ->
                            resetInactivityTimer()
                            viewModel.requestEditMessage(msgToEdit)
                        },
                        onRegenerateRequest = { userMessageForRegen ->
                            resetInactivityTimer()
                            viewModel.regenerateAiResponse(userMessageForRegen)
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
                                        (initialBouncePhaseDurationMs + (finalBouncePhaseDurationMs - initialBouncePhaseDurationMs) * progress).toInt(); coroutineScope {
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
                    .animateContentSize()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { viewModel.onTextChange(it) },
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
                            } else if (text.isNotBlank() && selectedApiConfig != null) {
                                keyboardController?.hide(); viewModel.onSendMessage()
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
                        singleLine = false, maxLines = 5, shape = RoundedCornerShape(8.dp)
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