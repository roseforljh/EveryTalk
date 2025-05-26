package com.example.everytalk.ui.screens.MainScreen

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.everytalk.StateControler.AppViewModel
import com.example.everytalk.data.DataClass.ApiConfig // <-- 确保 ApiConfig 被导入
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.navigation.Screen
import com.example.everytalk.ui.components.AppTopBar
import com.example.everytalk.ui.components.WebSourcesDialog
import com.example.everytalk.ui.screens.MainScreen.chat.ChatInputArea
import com.example.everytalk.ui.screens.MainScreen.chat.ChatMessagesList
import com.example.everytalk.ui.screens.MainScreen.chat.EditMessageDialog
import com.example.everytalk.ui.screens.MainScreen.chat.EmptyChatView
import com.example.everytalk.ui.screens.MainScreen.chat.ModelSelectionBottomSheet
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.cancellation.CancellationException

private const val USER_INACTIVITY_TIMEOUT_MS = 2000L
private const val REALTIME_SCROLL_CHECK_DELAY_MS = 100L
private const val FINAL_SCROLL_DELAY_MS = 150L
private const val SESSION_SWITCH_SCROLL_DELAY_MS = 250L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: AppViewModel,
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val messages: List<Message> = viewModel.messages
    val text by viewModel.text.collectAsState()
    val selectedApiConfig by viewModel.selectedApiConfig.collectAsState() // 当前选中的配置
    val isApiCalling by viewModel.isApiCalling.collectAsState()
    val currentStreamingAiMessageId by viewModel.currentStreamingAiMessageId.collectAsState()
    val reasoningCompleteMap = viewModel.reasoningCompleteMap
    val isWebSearchEnabled by viewModel.isWebSearchEnabled.collectAsState()

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val loadedHistoryIndex by viewModel.loadedHistoryIndex.collectAsState()
    var previousLoadedHistoryIndexState by remember(loadedHistoryIndex) {
        mutableStateOf(loadedHistoryIndex)
    }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    var userManuallyScrolledAwayFromBottom by remember { mutableStateOf(false) }
    var ongoingScrollJob by remember { mutableStateOf<Job?>(null) }
    var programmaticallyScrolling by remember { mutableStateOf(false) }
    // --- BottomSheet State (remains in ChatScreen) ---
    val bottomSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    var showModelSelectionBottomSheet by remember { mutableStateOf(false) }
    // 'availableModels' 是从 ViewModel 获取的所有配置列表
    val availableModels by viewModel.apiConfigs.collectAsState()

    Log.d("ChatScreen_FilterDebug", "Recomposing ChatScreen or relevant state changed.")
    Log.d(
        "ChatScreen_FilterDebug",
        "Current selectedApiConfig: name='${selectedApiConfig?.name}', key='${selectedApiConfig?.key}', model='${selectedApiConfig?.model}'"
    )
    Log.d(
        "ChatScreen_FilterDebug",
        "Total 'availableModels' (all configs) count: ${availableModels.size}"
    )
    if (availableModels.isEmpty()) {
        Log.d("ChatScreen_FilterDebug", "'availableModels' is empty.")
    } else {
        Log.d("ChatScreen_FilterDebug", "Listing all 'availableModels':")
        availableModels.forEachIndexed { index, config ->
            Log.d(
                "ChatScreen_FilterDebug",
                "  [$index] Name='${config.name}', Key='${config.key}', Model='${config.model}', ID='${config.id}'"
            )
        }
    }

    // VVVVVV 根据当前选中配置的Key来过滤底部弹窗中显示的模型列表 VVVVVV
    val filteredModelsForBottomSheet by remember(availableModels, selectedApiConfig) {
        derivedStateOf {
            Log.d(
                "ChatScreen_FilterDebug",
                "--- derivedStateOf for filteredModelsForBottomSheet ---"
            )
            val currentKey = selectedApiConfig?.key
            Log.d("ChatScreen_FilterDebug", "Using key for filtering: '$currentKey'")

            val resultList =
                if (selectedApiConfig != null && currentKey != null && currentKey.isNotBlank()) {
                    Log.d(
                        "ChatScreen_FilterDebug",
                        "Attempting to filter ${availableModels.size} models by key: '$currentKey'"
                    )
                    val filteredList = availableModels.filter { it.key == currentKey }
                    Log.d(
                        "ChatScreen_FilterDebug",
                        "Filter result count for key '$currentKey': ${filteredList.size}"
                    )
                    if (filteredList.isEmpty()) {
                        Log.d(
                            "ChatScreen_FilterDebug",
                            "Filtered list is empty, using listOfNotNull(selectedApiConfig)."
                        )
                        listOfNotNull(selectedApiConfig)
                    } else {
                        Log.d("ChatScreen_FilterDebug", "Filtered list is NOT empty, using it.")
                        filteredList
                    }
                } else {
                    Log.d(
                        "ChatScreen_FilterDebug",
                        "No valid selected config or key, using all 'availableModels' (${availableModels.size} items)."
                    )
                    availableModels
                }
            Log.d(
                "ChatScreen_FilterDebug",
                "Final 'filteredModelsForBottomSheet' count: ${resultList.size}"
            )
            resultList.forEachIndexed { index, config ->
                Log.d(
                    "ChatScreen_FilterDebug",
                    "  Result Item [$index]: Name='${config.name}', Key='${config.key}', Model='${config.model}'"
                )
            }
            Log.d("ChatScreen_FilterDebug", "--- end of derivedStateOf ---")
            resultList
        }
    }


    fun scrollToBottomGuaranteed(
        reason: String = "Unknown",
        listStateRef: LazyListState = listState,
        messagesRef: List<Message> = messages
    ) {
        ongoingScrollJob?.cancel(CancellationException("New scroll request: $reason"))
        ongoingScrollJob = coroutineScope.launch {
            if (messagesRef.isEmpty()) {
                Log.d("ScrollJob", "Messages empty, cannot scroll to bottom ($reason)")
                programmaticallyScrolling = false
                return@launch
            }
            programmaticallyScrolling = true
            val targetIndex = messagesRef.size

            var reachedEnd = false
            var attempts = 0
            val maxAttempts = 12

            try {
                Log.d(
                    "ScrollJob",
                    "Attempting immediate scrollToItem to $targetIndex ($reason, attempt 0)"
                )
                listStateRef.scrollToItem(targetIndex)
                delay(32)
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

            while (!reachedEnd && attempts < maxAttempts && isActive) {
                attempts++
                try {
                    Log.d(
                        "ScrollJob",
                        "Attempting animateScrollToItem to $targetIndex ($reason, attempt $attempts)"
                    )
                    listStateRef.animateScrollToItem(targetIndex)
                    delay(100)
                    val layoutInfo = listStateRef.layoutInfo
                    if (layoutInfo.visibleItemsInfo.isNotEmpty() && layoutInfo.visibleItemsInfo.any { it.index == targetIndex }) {
                        reachedEnd = true
                        Log.d(
                            "ScrollJob",
                            "AnimateScrollToItem SUCCESS to $targetIndex ($reason, attempt $attempts)"
                        )
                        break
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
                    throw e
                } catch (e: Exception) {
                    Log.e(
                        "ScrollJob",
                        "Scroll attempt to $targetIndex FAILED ($reason, attempt $attempts): ${e.message}",
                        e
                    )
                    delay(50)
                }
            }

            if (reachedEnd) {
                userManuallyScrolledAwayFromBottom = false
            } else if (isActive) {
                Log.w(
                    "ScrollJob",
                    "Failed to scroll to $targetIndex after $maxAttempts attempts ($reason)."
                )
            }
            programmaticallyScrolling = false
            ongoingScrollJob = null
        }
    }

    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var isUserConsideredActive by remember { mutableStateOf(true) }

    val resetInactivityTimer: () -> Unit = {
        lastInteractionTime = System.currentTimeMillis()
    }

    LaunchedEffect(lastInteractionTime) {
        isUserConsideredActive = true
        delay(USER_INACTIVITY_TIMEOUT_MS)
        if (isActive) {
            isUserConsideredActive = false
        }
    }

    LaunchedEffect(text) {
        if (text.isNotEmpty()) {
            resetInactivityTimer()
        }
    }

    LaunchedEffect(key1 = loadedHistoryIndex, key2 = messages.size) {
        val currentLoadedIndex = loadedHistoryIndex
        val sessionJustChanged = previousLoadedHistoryIndexState != currentLoadedIndex

        if (sessionJustChanged) {
            Log.d(
                "ChatScreenInitScroll",
                "Session changed ($previousLoadedHistoryIndexState -> $currentLoadedIndex). Delaying scroll by ${SESSION_SWITCH_SCROLL_DELAY_MS}ms."
            )
            delay(SESSION_SWITCH_SCROLL_DELAY_MS)
            previousLoadedHistoryIndexState = currentLoadedIndex
        }

        if (messages.isNotEmpty()) {
            Log.d(
                "ChatScreenInitScroll",
                "Messages not empty (size: ${messages.size}). Scrolling to bottom. loadedHistoryIndex: $currentLoadedIndex"
            )
            scrollToBottomGuaranteed("InitialOrSessionChange")
        } else {
            Log.d(
                "ChatScreenInitScroll",
                "Messages empty. Scrolling to top. loadedHistoryIndex: $currentLoadedIndex"
            )
            coroutineScope.launch {
                programmaticallyScrolling = true
                userManuallyScrolledAwayFromBottom = false
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

    val screenWidth = configuration.screenWidthDp.dp
    val bubbleMaxWidth = remember(screenWidth) { screenWidth.coerceAtMost(600.dp) }

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
                true
            } else {
                layoutInfo.visibleItemsInfo.any { it.index == messages.size } // Check footer
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

    LaunchedEffect(Unit) {
        viewModel.scrollToBottomEvent.collectLatest {
            Log.d("ChatScreen", "Received scrollToBottomEvent from ViewModel.")
            resetInactivityTimer()
            scrollToBottomGuaranteed("ViewModelEvent")
        }
    }

    val streamingAiMessage = remember(messages, currentStreamingAiMessageId) {
        messages.find { it.id == currentStreamingAiMessageId }
    }

    LaunchedEffect(
        currentStreamingAiMessageId,
        isApiCalling,
        userManuallyScrolledAwayFromBottom,
        isAtBottom
    ) {
        if (isApiCalling && currentStreamingAiMessageId != null && !userManuallyScrolledAwayFromBottom) {
            snapshotFlow { streamingAiMessage?.text?.length }
                .distinctUntilChanged()
                .collect { _ ->
                    if (isActive && isApiCalling && !userManuallyScrolledAwayFromBottom && !isAtBottom) {
                        delay(REALTIME_SCROLL_CHECK_DELAY_MS)
                        if (isActive && !isAtBottom && !userManuallyScrolledAwayFromBottom) {
                            Log.d("RealtimeScroll", "AI streaming, text changed, auto-scrolling...")
                            scrollToBottomGuaranteed("AI_Streaming_TextChanged")
                        }
                    }
                }
        }
    }

    val scrollToBottomButtonVisible by remember {
        derivedStateOf {
            Log.d("FAB_VISIBILITY_DEBUG", "Checking FAB: messagesNotEmpty=${messages.isNotEmpty()}, isAtBottom=$isAtBottom, userActive=$isUserConsideredActive, scrolledUp=$userManuallyScrolledAwayFromBottom")
            messages.isNotEmpty() &&
                    userManuallyScrolledAwayFromBottom // 只保留这两个，先看滚动后是否出现
        }
    }

    val showEditDialog by viewModel.showEditDialog.collectAsState()
    val editDialogInputText by viewModel.editDialogInputText.collectAsState()
    val showSourcesDialog by viewModel.showSourcesDialog.collectAsState()
    val sourcesForDialog by viewModel.sourcesForDialog.collectAsState()
    val imeInsets = WindowInsets.ime

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { resetInactivityTimer() }) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            AppTopBar(
                selectedConfigName = selectedApiConfig?.name?.takeIf { it.isNotBlank() }
                    ?: selectedApiConfig?.model ?: "选择配置",
                onMenuClick = { coroutineScope.launch { viewModel.drawerState.open() } },
                onSettingsClick = { navController.navigate(Screen.SETTINGS_SCREEN) },
                onTitleClick = {
                    resetInactivityTimer()
                    coroutineScope.launch {
                        Log.d(
                            "ChatScreen_FilterDebug",
                            "TopBar clicked. 'filteredModelsForBottomSheet' has ${filteredModelsForBottomSheet.size} items."
                        )
                        if (filteredModelsForBottomSheet.isNotEmpty()) {
                            showModelSelectionBottomSheet = true
                        } else {
                            viewModel.showSnackbar("当前无可用模型配置")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = scrollToBottomButtonVisible,
                enter = fadeIn(
                    animationSpec = tween(
                        durationMillis = 150,
                        easing = LinearOutSlowInEasing
                    )
                ),
                exit = fadeOut(
                    animationSpec = tween(
                        durationMillis = 150,
                        easing = FastOutLinearInEasing
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
                    EmptyChatView(density = density)
                } else {
                    ChatMessagesList(
                        messages = messages,
                        listState = listState,
                        viewModel = viewModel,
                        currentStreamingAiMessageId = currentStreamingAiMessageId,
                        isApiCalling = isApiCalling,
                        reasoningCompleteMap = reasoningCompleteMap,
                        bubbleMaxWidth = bubbleMaxWidth,
                        nestedScrollConnection = nestedScrollConnection,
                        onUserInteraction = { resetInactivityTimer() },
                        onRequestEditMessage = { msg ->
                            resetInactivityTimer()
                            viewModel.requestEditMessage(msg)
                        },
                        onRequestRegenerateAiResponse = { userMsg ->
                            resetInactivityTimer()
                            viewModel.regenerateAiResponse(userMsg)
                        }
                    )
                }
            }

            ChatInputArea(
                text = text,
                onTextChange = { viewModel.onTextChange(it); resetInactivityTimer() },
                onSendMessageRequest = { messageText,isKeyboardVisible, images ->
                    resetInactivityTimer()
                    viewModel.onSendMessage(messageText, images=images)
                },
                isApiCalling = isApiCalling,
                isWebSearchEnabled = isWebSearchEnabled,
                onToggleWebSearch = {
                    resetInactivityTimer()
                    viewModel.toggleWebSearchMode(!isWebSearchEnabled)
                },
                onClearText = { viewModel.onTextChange(""); resetInactivityTimer() },
                onStopApiCall = { viewModel.onCancelAPICall(); resetInactivityTimer() },
                focusRequester = focusRequester,
                selectedApiConfig = selectedApiConfig,
                onShowSnackbar = { viewModel.showSnackbar(it) },
                imeInsets = imeInsets,
                density = density,
                keyboardController = keyboardController,
                onFocusChange = { isFocused -> if (isFocused) resetInactivityTimer() }
            )
        }

        if (showEditDialog) {
            EditMessageDialog(
                editDialogInputText = editDialogInputText,
                onDismissRequest = { viewModel.dismissEditDialog() },
                onEditDialogTextChanged = viewModel::onEditDialogTextChanged,
                onConfirmMessageEdit = { viewModel.confirmMessageEdit() }
            )
        }

        if (showSourcesDialog) {
            WebSourcesDialog(
                sources = sourcesForDialog,
                onDismissRequest = { viewModel.dismissSourcesDialog() }
            )
        }

        if (showModelSelectionBottomSheet) {
            ModelSelectionBottomSheet(
                onDismissRequest = { showModelSelectionBottomSheet = false },
                sheetState = bottomSheetState,
                availableModels = filteredModelsForBottomSheet, // <-- 使用过滤后的列表
                selectedApiConfig = selectedApiConfig,
                onModelSelected = { modelConfig ->
                    resetInactivityTimer()
                    viewModel.selectConfig(modelConfig)
                    coroutineScope.launch {
                        bottomSheetState.hide()
                    }.invokeOnCompletion {
                        if (!bottomSheetState.isVisible) {
                            showModelSelectionBottomSheet = false
                        }
                    }
                }
            )
        }
    }
}