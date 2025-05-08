package com.example.app1.ui.screens // 确保包名正确

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalConfiguration // <-- ** Import LocalConfiguration **
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp // <-- ** Import Dp **
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.app1.AppViewModel
import com.example.app1.data.models.Message
import com.example.app1.data.models.Sender
import com.example.app1.navigation.Screen
import com.example.app1.ui.components.AppTopBar
import com.example.app1.ui.components.MessageBubble
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow

// Constants remain the same
private const val CHAT_SCREEN_AUTO_SCROLL_DELAY_MS = 100L
private const val CHAT_SCREEN_STREAMING_SCROLL_INTERVAL_MS = 150L

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

    // --- *** Calculate Max Bubble Width *** ---
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val bubbleMaxWidth = remember(screenWidth) { // Remember based on screen width
        (screenWidth * 0.8f).coerceAtMost(600.dp) // Example: 80% of screen, max 600dp
    }
    // --- *** End Calculate Max Bubble Width *** ---


    // ... (NestedScrollConnection - unchanged) ...
    val overscrollConsumingConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!listState.canScrollBackward && available.y < 0 && source == NestedScrollSource.Drag) {
                    return available
                }; return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!listState.canScrollBackward && available.y < 0) {
                    return available
                }; return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (!listState.canScrollBackward && available.y < 0) {
                    return available
                }; return super.onPostFling(consumed, available)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (!listState.canScrollBackward && available.y < 0 && source == NestedScrollSource.Drag) {
                    return available
                }; return Offset.Zero
            }
        }
    }

    // ... (LaunchedEffect for user scroll detection - unchanged) ...
    LaunchedEffect(listState) {
        snapshotFlow {
            if (messages.isEmpty()) return@snapshotFlow false
            val layoutInfo = listState.layoutInfo
            val visibleItemsInfo = layoutInfo.visibleItemsInfo
            if (visibleItemsInfo.isEmpty()) return@snapshotFlow false
            val firstVisibleItemIndex = visibleItemsInfo.first().index
            firstVisibleItemIndex > 0
        }.collect { scrolledUp ->
            Log.d("ChatScroll", "SnapshotFlow - ScrolledUp: $scrolledUp")
            if (userManuallyScrolledUp != scrolledUp) {
                userManuallyScrolledUp = scrolledUp
                if (scrolledUp) ongoingScrollJob?.cancel()
            }
        }
    }


    // ... (Auto-scroll logic - unchanged) ...
    LaunchedEffect(
        messages.size,
        currentStreamingAiMessageId,
        isApiCalling,
        userManuallyScrolledUp
    ) {
        ongoingScrollJob?.cancel()
        if (messages.isEmpty()) return@LaunchedEffect

        val targetIndex = 0
        val isStreamingThisMessage = currentStreamingAiMessageId != null && isApiCalling
        val shouldScroll = !userManuallyScrolledUp || isStreamingThisMessage

        if (shouldScroll) {
            ongoingScrollJob = coroutineScope.launch {
                delay(CHAT_SCREEN_AUTO_SCROLL_DELAY_MS)
                if (!isActive) return@launch
                try {
                    listState.animateScrollToItem(index = targetIndex)
                } catch (e: Exception) {
                    Log.e("ChatScroll", "Auto-scroll error: ${e.message}", e)
                }

                if (isStreamingThisMessage && !userManuallyScrolledUp) {
                    try {
                        while (isActive && currentStreamingAiMessageId != null && isApiCalling && !userManuallyScrolledUp) {
                            val firstVisible = listState.firstVisibleItemIndex
                            val firstVisibleOffset = listState.firstVisibleItemScrollOffset
                            if (firstVisible > 0 || firstVisibleOffset > with(density) { 4.dp.toPx() }) {
                                listState.animateScrollToItem(index = targetIndex)
                            }
                            delay(CHAT_SCREEN_STREAMING_SCROLL_INTERVAL_MS)
                        }
                    } catch (e: CancellationException) { /* Expected */
                    } catch (e: Exception) {
                        Log.e("ChatScroll", "Streaming scroll error: ${e.message}", e)
                    }
                }
            }
        }
    }

    // ... (Forced scroll to bottom event - unchanged) ...
    LaunchedEffect(Unit) {
        viewModel.scrollToBottomEvent.collectLatest {
            ongoingScrollJob?.cancel()
            ongoingScrollJob = coroutineScope.launch {
                try {
                    listState.animateScrollToItem(0)
                    if (userManuallyScrolledUp) userManuallyScrolledUp = false
                } catch (e: Exception) {
                    Log.e("ChatScroll", "Forced scroll error: ${e.message}", e)
                }
            }
        }
    }

    // ... (Collapse reasoning - unchanged) ...
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

    // ... (Scroll to bottom button logic - unchanged) ...
    val isUserActuallyScrolling = listState.isScrollInProgress
    val firstVisibleItemIndex = listState.firstVisibleItemIndex
    val scrollToBottomButtonVisible by remember(
        messages.size,
        firstVisibleItemIndex,
        isUserActuallyScrolling
    ) {
        derivedStateOf {
            val visible = messages.isNotEmpty() &&
                    firstVisibleItemIndex > 0 &&
                    !isUserActuallyScrolling
            Log.d(
                "FAB_Visibility",
                "Messages: ${messages.isNotEmpty()}, FirstVisibleIndex: $firstVisibleItemIndex (>0 -> ${firstVisibleItemIndex > 0}), NotScrolling: ${!isUserActuallyScrolling} => Visible: $visible"
            )
            visible
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
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
                        coroutineScope.launch {
                            listState.animateScrollToItem(0)
                            if (userManuallyScrolledUp) userManuallyScrolledUp = false
                        }
                    },
                    modifier = Modifier.padding(bottom = 160.dp),
                    shape = RoundedCornerShape(16.dp),
                    containerColor = Color.White,
                    contentColor = Color.Black
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
                    messages,
                    key = { message -> message.id }
                ) { message ->
                    val isMainContentStreaming = message.sender == Sender.AI &&
                            message.id == currentStreamingAiMessageId &&
                            isApiCalling &&
                            message.contentStarted

                    MessageBubble(
                        message = message,
                        viewModel = viewModel,
                        isMainContentStreaming = isMainContentStreaming,
                        isReasoningStreaming = (message.sender == Sender.AI &&
                                message.id == currentStreamingAiMessageId &&
                                isApiCalling &&
                                !message.reasoning.isNullOrBlank() &&
                                !(reasoningCompleteMap[message.id] ?: false) &&
                                message.contentStarted),
                        isReasoningComplete = reasoningCompleteMap[message.id] ?: false,
                        isManuallyExpanded = expandedReasoningStates[message.id] ?: false,
                        onToggleReasoning = { viewModel.onToggleReasoningExpand(message.id) },
                        maxWidth = bubbleMaxWidth, // <-- *** PASS MAX WIDTH ***
                        showLoadingBubble = (message.sender == Sender.AI &&
                                message.id == currentStreamingAiMessageId &&
                                isApiCalling &&
                                !message.contentStarted &&
                                message.text.isBlank() &&
                                message.reasoning.isNullOrBlank())
                    )
                }
                if (messages.isEmpty()) {
                    item {
                        // ... (Empty state "你好..." - unchanged) ...
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
                                val dotInterDelayMs = 150L;
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
                                    }; launch {
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
                                }; launch {
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
            } // End LazyColumn

            // ... (Input Area - unchanged) ...
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
                    onValueChange = viewModel::onTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged {}
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
                    minLines = 1,
                    maxLines = 5,
                    trailingIcon = {
                        val showClearButton =
                            text.isNotBlank() && !isApiCalling; AnimatedVisibility(
                        visible = showClearButton,
                        enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.9f),
                        exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.7f)
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
                    })
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 0.dp, end = 8.dp, bottom = 8.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    IconButton(
                        enabled = true,
                        onClick = {
                            if (isApiCalling) viewModel.onCancelAPICall() else if (text.isNotBlank() && selectedApiConfig != null) {
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
                            imageVector = if (isApiCalling) Icons.Filled.Stop else Icons.Filled.North,
                            contentDescription = if (isApiCalling) "停止" else "发送",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        } // End Root Column
    } // End Scaffold
}