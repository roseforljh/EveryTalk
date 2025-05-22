// 文件: com.example.everytalk.ui.screens.MainScreen.ChatScreen.kt
package com.example.everytalk.ui.screens.MainScreen

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward
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
import kotlinx.coroutines.flow.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow

private const val USER_INACTIVITY_TIMEOUT_MS = 2000L
private const val REALTIME_SCROLL_CHECK_DELAY_MS = 100L // 稍微增加延迟，减少 Effect 重启频率
private const val FINAL_SCROLL_DELAY_MS = 150L
private const val SESSION_SWITCH_SCROLL_DELAY_MS = 250L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: AppViewModel,
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val messages: List<Message> = viewModel.messages // messages is a SnapshotStateList, good!
    val text by viewModel.text.collectAsState()
    val selectedApiConfig by viewModel.selectedApiConfig.collectAsState()
    val isApiCalling by viewModel.isApiCalling.collectAsState()
    val currentStreamingAiMessageId by viewModel.currentStreamingAiMessageId.collectAsState()
    val reasoningCompleteMap = viewModel.reasoningCompleteMap
    val isWebSearchEnabled by viewModel.isWebSearchEnabled.collectAsState()

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val loadedHistoryIndex by viewModel.loadedHistoryIndex.collectAsState()
    var previousLoadedHistoryIndexState by remember(loadedHistoryIndex) {
        mutableStateOf(
            loadedHistoryIndex
        )
    }


    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    var userManuallyScrolledAwayFromBottom by remember { mutableStateOf(false) }
    var ongoingScrollJob by remember { mutableStateOf<Job?>(null) }
    var programmaticallyScrolling by remember { mutableStateOf(false) }

    fun scrollToBottomGuaranteed(
        reason: String = "Unknown",
        listStateRef: LazyListState = listState,
        messagesRef: List<Message> = messages
    ) {
        ongoingScrollJob?.cancel(CancellationException("New scroll request: $reason"))
        ongoingScrollJob = coroutineScope.launch {
            if (messagesRef.isEmpty()) {
                Log.d("ScrollJob", "Messages empty, cannot scroll to bottom ($reason)")
                programmaticallyScrolling = false // Ensure reset if no scroll happens
                return@launch
            }
            programmaticallyScrolling = true
            // Target the actual last item index, or a "footer" index if you have one beyond messages.size
            // Assuming 'messages.size' is the key for the footer spacer or the index after the last message.
            val targetIndex = messagesRef.size // If footer is item messages.size

            var reachedEnd = false
            var attempts = 0
            val maxAttempts = 12 // Max retries for scrolling

            // Initial immediate scroll attempt
            try {
                Log.d(
                    "ScrollJob",
                    "Attempting immediate scrollToItem to $targetIndex ($reason, attempt 0)"
                )
                listStateRef.scrollToItem(targetIndex)
                delay(32) // Short delay to allow layout pass
                val layoutInfo = listStateRef.layoutInfo
                if (layoutInfo.visibleItemsInfo.isNotEmpty() && layoutInfo.visibleItemsInfo.any { it.index == targetIndex }) {
                    reachedEnd = true
                    Log.d(
                        "ScrollJob",
                        "Immediate scrollToItem SUCCESS to $targetIndex ($reason, attempt 0)"
                    )
                } else {
                    Log.d(
                        "ScrollJob",
                        "Immediate scrollToItem to $targetIndex might not have reached ($reason, attempt 0)"
                    )
                }
            } catch (e: Exception) {
                Log.e(
                    "ScrollJob",
                    "Immediate scrollToItem to $targetIndex FAILED ($reason, attempt 0): ${e.message}",
                    e
                )
            }


            // Animated scroll attempts if immediate failed or to ensure visibility
            while (!reachedEnd && attempts < maxAttempts && isActive) {
                attempts++
                try {
                    Log.d(
                        "ScrollJob",
                        "Attempting animateScrollToItem to $targetIndex ($reason, attempt $attempts)"
                    )
                    listStateRef.animateScrollToItem(targetIndex) // animateScrollToItem has its own internal retry/check
                    delay(100) // Shorter delay, animateScrollToItem is more robust

                    val layoutInfo = listStateRef.layoutInfo
                    if (layoutInfo.visibleItemsInfo.isNotEmpty() && layoutInfo.visibleItemsInfo.any { it.index == targetIndex }) {
                        reachedEnd = true
                        Log.d(
                            "ScrollJob",
                            "AnimateScrollToItem SUCCESS to $targetIndex ($reason, attempt $attempts)"
                        )
                        break // Exit loop if reached
                    } else {
                        Log.d(
                            "ScrollJob",
                            "AnimateScrollToItem to $targetIndex might not have reached ($reason, attempt $attempts)"
                        )
                    }
                } catch (e: CancellationException) {
                    Log.d(
                        "ScrollJob",
                        "Scroll attempt to $targetIndex CANCELLED ($reason, attempt $attempts)"
                    )
                    throw e // Re-throw cancellation
                } catch (e: Exception) {
                    Log.e(
                        "ScrollJob",
                        "Scroll attempt to $targetIndex FAILED ($reason, attempt $attempts): ${e.message}",
                        e
                    )
                    delay(50) // Delay before next retry on generic exception
                }
            }

            if (reachedEnd) {
                userManuallyScrolledAwayFromBottom = false
            } else if (isActive) { // Check isActive before logging final failure
                Log.w(
                    "ScrollJob",
                    "Failed to scroll to $targetIndex after $maxAttempts attempts ($reason)."
                )
            }
            programmaticallyScrolling = false
            ongoingScrollJob = null // Clear the job reference
        }
    }

    // Initial scroll logic when conversation or messages change
    LaunchedEffect(
        key1 = loadedHistoryIndex,
        key2 = messages.size
    ) { // Use messages.size for more stable key if messages is SnapshotStateList
        val currentLoadedIndex = loadedHistoryIndex
        val sessionJustChanged = previousLoadedHistoryIndexState != currentLoadedIndex

        if (sessionJustChanged) {
            Log.d(
                "ChatScreenInitScroll",
                "Session changed ($previousLoadedHistoryIndexState -> $currentLoadedIndex). Delaying scroll by ${SESSION_SWITCH_SCROLL_DELAY_MS}ms."
            )
            delay(SESSION_SWITCH_SCROLL_DELAY_MS)
            previousLoadedHistoryIndexState =
                currentLoadedIndex // Update after delay, before scroll
        }


        if (messages.isNotEmpty()) {
            Log.d(
                "ChatScreenInitScroll",
                "Messages not empty (size: ${messages.size}). Scrolling to bottom. loadedHistoryIndex: $currentLoadedIndex"
            )
            // For initial load or session switch, a slightly more robust scroll might be needed
            // than just one immediate attempt.
            scrollToBottomGuaranteed("InitialOrSessionChange")
        } else {
            Log.d(
                "ChatScreenInitScroll",
                "Messages empty. Scrolling to top. loadedHistoryIndex: $currentLoadedIndex"
            )
            coroutineScope.launch { // Ensure this is also on a coroutine
                programmaticallyScrolling = true
                userManuallyScrolledAwayFromBottom = false // Reset this too
                try {
                    listState.scrollToItem(0)
                } catch (e: Exception) {
                    Log.e(
                        "ChatScreenInitScroll",
                        "Scroll to top (empty list) failed: ${e.message}",
                        e
                    )
                } finally {
                    programmaticallyScrolling = false
                }
            }
        }
    }

    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var isUserConsideredActive by remember { mutableStateOf(true) }

    val resetInactivityTimer: () -> Unit = {
        lastInteractionTime = System.currentTimeMillis()
        // No need to set isUserConsideredActive = true here,
        // the LaunchedEffect(lastInteractionTime) will handle it.
    }

    LaunchedEffect(lastInteractionTime) {
        isUserConsideredActive = true
        delay(USER_INACTIVITY_TIMEOUT_MS)
        if (isActive) { // Check isActive before changing state
            isUserConsideredActive = false
        }
    }

    val imeInsets = WindowInsets.ime
    var pendingMessageText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { // Observe IME and pending text
        snapshotFlow { imeInsets.getBottom(density) > 0 }
            .distinctUntilChanged()
            .filter { isKeyboardVisible -> !isKeyboardVisible && pendingMessageText != null }
            .collect {
                pendingMessageText?.let { msg ->
                    Log.d("ChatScreen", "Keyboard hidden, sending pending message.")
                    viewModel.onSendMessage(msg)
                    pendingMessageText = null // Clear after sending
                }
            }
    }
    LaunchedEffect(text) { // Reset inactivity on text input
        if (text.isNotEmpty()) { // Only reset if text actually changes to non-empty
            resetInactivityTimer()
        }
    }


    val screenWidth = configuration.screenWidthDp.dp
    val bubbleMaxWidth = remember(screenWidth) { screenWidth.coerceAtMost(600.dp) }
    // Removed unused second remember for screenWidth


    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag && !programmaticallyScrolling) {
                    resetInactivityTimer()
                    if (available.y < -0.5f && !userManuallyScrolledAwayFromBottom) {
                        userManuallyScrolledAwayFromBottom = true
                        Log.d(
                            "ScrollState",
                            "User dragging up (available.y: ${available.y}), userManuallyScrolledAwayFromBottom = true"
                        )
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // Check !programmaticallyScrolling to avoid interference
                if (listState.isScrollInProgress && !programmaticallyScrolling) {
                    resetInactivityTimer()
                    if (available.y < -50f && !userManuallyScrolledAwayFromBottom) {
                        userManuallyScrolledAwayFromBottom = true
                        Log.d(
                            "ScrollState",
                            "User flinging up (available.y: ${available.y}), userManuallyScrolledAwayFromBottom = true"
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
            if (messages.isEmpty() || layoutInfo.visibleItemsInfo.isEmpty()) {
                true // Considered at bottom if list is empty
            } else {
                // Check if the "footer" item (index messages.size) is visible
                layoutInfo.visibleItemsInfo.any { it.index == messages.size }
            }
        }
    }

    LaunchedEffect(isAtBottom, listState.isScrollInProgress, programmaticallyScrolling) {
        if (!listState.isScrollInProgress && !programmaticallyScrolling && isAtBottom && userManuallyScrolledAwayFromBottom) {
            Log.d(
                "ScrollState",
                "Reached bottom by user or after programmatic scroll, resetting userManuallyScrolledAwayFromBottom = false"
            )
            userManuallyScrolledAwayFromBottom = false
        }
    }

    LaunchedEffect(Unit) { // For ViewModel events
        viewModel.scrollToBottomEvent.collectLatest {
            Log.d("ChatScreen", "Received scrollToBottomEvent from ViewModel.")
            resetInactivityTimer()
            // No need to check isAtBottom here, scrollToBottomGuaranteed will handle empty list
            scrollToBottomGuaranteed("ViewModelEvent")
            // If it was a user initiated scroll event from ViewModel, we might want to clear userManuallyScrolledAwayFromBottom
            // but scrollToBottomGuaranteed already does it if successful.
        }
    }

    val streamingAiMessage = remember(messages, currentStreamingAiMessageId) {
        messages.find { it.id == currentStreamingAiMessageId }
    }

    // Simpler key, actual text changes are frequent. Let's react to ID and API state.
    // Scroll will happen if needed.
    LaunchedEffect(
        currentStreamingAiMessageId,
        isApiCalling,
        userManuallyScrolledAwayFromBottom,
        isAtBottom
    ) {
        if (isApiCalling && currentStreamingAiMessageId != null && !userManuallyScrolledAwayFromBottom) {
            // This effect will re-evaluate if any of these keys change.
            // If AI is streaming (isApiCalling true, currentStreamingAiMessageId not null)
            // and user hasn't scrolled away, and we are not at bottom, then try to scroll.
            // The actual text content change will make `isAtBottom` potentially false.
            snapshotFlow { streamingAiMessage?.text?.length } // Observe text length
                .distinctUntilChanged()
                .collect { _ -> // We only care that it changed
                    if (isActive && isApiCalling && !userManuallyScrolledAwayFromBottom && !isAtBottom) {
                        delay(REALTIME_SCROLL_CHECK_DELAY_MS) // Small delay to batch UI updates
                        if (isActive && !isAtBottom && !userManuallyScrolledAwayFromBottom) { // Re-check after delay
                            Log.d("RealtimeScroll", "AI streaming, text changed, auto-scrolling...")
                            scrollToBottomGuaranteed("AI_Streaming_TextChanged")
                        }
                    }
                }
        }
    }


    LaunchedEffect(isApiCalling) { // Scroll when API call finishes
        snapshotFlow { isApiCalling }
            .filter { !it } // Only when isApiCalling becomes false
            .distinctUntilChanged()
            .collectLatest { // Use collectLatest to cancel previous if isApiCalling toggles quickly
                // Check if the last message is an AI message and we should scroll
                if (messages.isNotEmpty()) {
                    val lastMessage = messages.last()
                    if (lastMessage.sender == Sender.AI && !userManuallyScrolledAwayFromBottom) {
                        Log.d(
                            "ScrollLogic",
                            "AI response finished, delaying for final scroll check."
                        )
                        delay(FINAL_SCROLL_DELAY_MS)
                        if (isActive && !isAtBottom && !userManuallyScrolledAwayFromBottom) { // Re-check after delay
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
            messages.isNotEmpty() &&
                    !isAtBottom && // Only show if not already at the bottom
                    isUserConsideredActive && // Only if user is active
                    userManuallyScrolledAwayFromBottom // Only if user has scrolled away
        }
    }
    // Removed Log from derivedStateOf for scrollToBottomButtonVisible for cleaner release logs

    val showEditDialog by viewModel.showEditDialog.collectAsState()
    val editDialogInputText by viewModel.editDialogInputText.collectAsState()
    val showSourcesDialog by viewModel.showSourcesDialog.collectAsState()
    val sourcesForDialog by viewModel.sourcesForDialog.collectAsState()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { resetInactivityTimer() }) },
        contentWindowInsets = WindowInsets(
            0,
            0,
            0,
            0
        ), // Handle insets manually with imePadding etc.
        containerColor = MaterialTheme.colorScheme.surface, // Use theme color
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
                enter = fadeIn(
                    animationSpec = tween(
                        durationMillis = 150, // 你可以调整动画时长
                        easing = LinearOutSlowInEasing // 可选，选择一个缓动函数
                    )
                ),
                exit = fadeOut(
                    animationSpec = tween(
                        durationMillis = 150, // 你可以调整动画时长
                        easing = FastOutLinearInEasing // 可选
                    )
                )

            ) {
                FloatingActionButton(
                    onClick = {
                        resetInactivityTimer()
                        scrollToBottomGuaranteed("FAB_Click")
                    },
                    modifier = Modifier.padding(bottom = 100.dp + 16.dp + 15.dp),
                    shape = CircleShape,
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 0.dp,
                        pressedElevation = 0.dp,
                        focusedElevation = 0.dp,
                        hoveredElevation = 0.dp
                    )
                ) { Icon(Icons.Filled.ArrowDownward, "滚动到底部") }
            }
        },
        floatingActionButtonPosition = FabPosition.End
    ) { scaffoldPaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPaddingValues) // Apply padding from Scaffold
                .background(Color.White) // Use theme color
                .imePadding() // Handles IME insets
                .navigationBarsPadding() // Handles navigation bar insets
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
                            // .background(MaterialTheme.colorScheme.surface) // Already on parent Column
                            .nestedScroll(nestedScrollConnection)
                            .padding(horizontal = 8.dp), // Keep horizontal padding
                        state = listState,
                        contentPadding = PaddingValues(
                            top = 8.dp,
                            // normalBottomPaddingForAIChat was 16.dp, let's keep it or make it more dynamic
                            bottom = 16.dp
                        )
                    ) {
                        items(items = messages, key = { message -> message.id }) { message ->
                            // Determine loading states based on ViewModel state
                            val isLoadingMessage =
                                message.id == currentStreamingAiMessageId && isApiCalling
                            val showLoadingDots =
                                isLoadingMessage && message.text.isBlank() && !message.contentStarted && !message.isError

                            MessageBubble(
                                message = message,
                                viewModel = viewModel,
                                onUserInteraction = { resetInactivityTimer() },
                                isMainContentStreaming = isLoadingMessage && message.contentStarted, // Simplified
                                isReasoningStreaming = isLoadingMessage && !message.reasoning.isNullOrBlank() && !(reasoningCompleteMap[message.id]
                                    ?: false), // Simplified
                                isReasoningComplete = (reasoningCompleteMap[message.id] ?: false),
                                maxWidth = bubbleMaxWidth,
                                showLoadingBubble = showLoadingDots, // Use the calculated var
                                onEditRequest = { msg ->
                                    resetInactivityTimer()
                                    viewModel.requestEditMessage(msg)
                                },
                                onRegenerateRequest = { userMsg ->
                                    resetInactivityTimer()
                                    viewModel.regenerateAiResponse(userMsg)
                                }
                            )
                        }
                        // Footer Spacer for robust scrolling to the very end
                        item(key = "chat_screen_footer_spacer") { // More descriptive key
                            Spacer(modifier = Modifier.height(1.dp)) // Minimal height, just needs to be targetable
                        }
                    }
                }
            }

            // Input Area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow( // Shadow for visual separation
                        elevation = 6.dp,
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        clip = false
                    )
                    .background(
                        Color.White,
                        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                    )
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) // Clip contents
                    .padding(
                        start = 8.dp,
                        end = 8.dp,
                        top = 8.dp,
                        bottom = 8.dp
                    ) // Consistent padding
                // .heightIn(min = estimatedInputAreaHeight) // Let it wrap content naturally
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { viewModel.onTextChange(it); resetInactivityTimer() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { if (it.isFocused) resetInactivityTimer() }
                        .padding(bottom = 4.dp), // Space before buttons
                    placeholder = { Text("输入消息…") },
                    colors = OutlinedTextFieldDefaults.colors(
                        // Use OutlinedTextFieldDefaults.colors
                        focusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedBorderColor = Color.Transparent, // No border when focused
                        unfocusedBorderColor = Color.Transparent, // No border when unfocused
                    ),
                    minLines = 1, maxLines = 5,
                    shape = RoundedCornerShape(16.dp) // Rounded corners for text field
                )
                Row( // Buttons in a Row for better alignment
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            resetInactivityTimer()
                            viewModel.toggleWebSearchMode(!isWebSearchEnabled)
                        },
                        // modifier = Modifier.size(44.dp) // Let IconButton define its own size usually
                    ) {
                        Icon(
                            imageVector = Icons.Filled.TravelExplore,
                            contentDescription = if (isWebSearchEnabled) "关闭联网搜索" else "开启联网搜索",
                            tint = if (isWebSearchEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            // modifier = Modifier.size(24.dp) // Default size is fine
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (text.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel.onTextChange(""); resetInactivityTimer() }
                            ) {
                                Icon(
                                    Icons.Filled.Clear, "清除内容",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.width(4.dp)) // Space between clear and send
                        FilledIconButton(
                            onClick = {
                                resetInactivityTimer()
                                if (isApiCalling) {
                                    viewModel.onCancelAPICall()
                                } else if (text.isNotBlank() && selectedApiConfig != null) {
                                    val isKeyboardCurrentlyVisible =
                                        imeInsets.getBottom(density) > 0
                                    if (isKeyboardCurrentlyVisible) {
                                        pendingMessageText = text // Store text
                                        viewModel.onTextChange("") // Clear current input
                                        keyboardController?.hide() // Hide keyboard
                                    } else {
                                        viewModel.onSendMessage(text) // Send directly
                                    }
                                } else if (selectedApiConfig == null) {
                                    viewModel.showSnackbar("请先选择 API 配置")
                                } else {
                                    viewModel.showSnackbar("请输入消息内容")
                                }
                            },
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Color.Black,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                if (isApiCalling) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send, // AutoMirrored.Filled.Send for LTR/RTL
                                if (isApiCalling) "停止" else "发送"
                            )
                        }
                    }
                }
            }
        }

        if (showEditDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissEditDialog() },
                containerColor = Color.White,
                title = { Text("编辑消息", color = MaterialTheme.colorScheme.onSurface) },
                text = {
                    OutlinedTextField(
                        value = editDialogInputText,
                        onValueChange = viewModel::onEditDialogTextChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("消息内容") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            // other colors will use defaults or inherit
                        ),
                        singleLine = false, maxLines = 5,
                        shape = RoundedCornerShape(8.dp)
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.confirmMessageEdit() },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                    ) { Text("确定") }
                },
                dismissButton = {
                    TextButton(
                        onClick = { viewModel.dismissEditDialog() },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
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
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
            val style = MaterialTheme.typography.displayMedium.copy(
                // Use MaterialTheme typography
                fontWeight = FontWeight.ExtraBold,
                // color = MaterialTheme.colorScheme.onSurface // Color will be inherited
            )
            Text("你好", style = style)
            val animY = remember { List(3) { Animatable(0f) } }
            LaunchedEffect(Unit) {
                animY.forEach { it.snapTo(0f) } // Initialize
                try {
                    // Simplified animation, can be made more elaborate if needed
                    repeat(Int.MAX_VALUE) { // Loop indefinitely or for a fixed number of cycles
                        if (!isActive) throw CancellationException("你好动画取消")
                        animY.forEachIndexed { index, anim ->
                            launch {
                                delay((index * 150L) % 450) // Staggered start
                                anim.animateTo(
                                    targetValue = with(density) { (-6).dp.toPx() },
                                    animationSpec = tween(
                                        durationMillis = 300,
                                        easing = FastOutSlowInEasing
                                    )
                                )
                                anim.animateTo(
                                    targetValue = 0f,
                                    animationSpec = tween(
                                        durationMillis = 450,
                                        easing = FastOutSlowInEasing
                                    )
                                )
                                if (index == animY.lastIndex) delay(600) // Pause at the end of a full cycle
                            }
                        }
                        delay(1200) // Wait for one full cycle of all dots to roughly complete + pause
                    }
                } catch (e: CancellationException) {
                    Log.d("Animation", "你好动画已取消")
                    // Ensure dots reset on cancellation
                    coroutineScope { animY.forEach { launch { it.snapTo(0f) } } }
                }
            }
            animY.forEach {
                Text(
                    text = ".",
                    style = style,
                    modifier = Modifier.offset(y = with(density) { it.value.toDp() })
                )
            }
        }
    }
}