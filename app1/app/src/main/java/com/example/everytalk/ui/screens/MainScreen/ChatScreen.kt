package com.example.everytalk.ui.screens.MainScreen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.example.everytalk.StateControler.AppViewModel
import com.example.everytalk.data.DataClass.ApiConfig
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.navigation.Screen
import com.example.everytalk.ui.components.AppTopBar
import com.example.everytalk.ui.components.WebSourcesDialog
import com.example.everytalk.ui.screens.MainScreen.chat.ChatInputArea
import com.example.everytalk.ui.screens.MainScreen.chat.ChatListItem
import com.example.everytalk.ui.screens.MainScreen.chat.ChatMessagesList
import com.example.everytalk.ui.screens.MainScreen.chat.EditMessageDialog
import com.example.everytalk.ui.screens.MainScreen.chat.EmptyChatView
import com.example.everytalk.ui.screens.MainScreen.chat.ModelSelectionBottomSheet
import com.example.everytalk.util.parseMarkdownToBlocks
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.cancellation.CancellationException

private const val USER_INACTIVITY_TIMEOUT_MS = 2000L
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
    val selectedApiConfig by viewModel.selectedApiConfig.collectAsState()
    val isApiCalling by viewModel.isApiCalling.collectAsState()
    val currentStreamingAiMessageId by viewModel.currentStreamingAiMessageId.collectAsState()
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

    val bottomSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    var showModelSelectionBottomSheet by remember { mutableStateOf(false) }
    val availableModels by viewModel.apiConfigs.collectAsState()

    var showAiMessageOptionsBottomSheet by remember { mutableStateOf(false) }
    var selectedMessageForOptions by remember { mutableStateOf<Message?>(null) }
    val aiMessageOptionsBottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val filteredModelsForBottomSheet by remember(availableModels, selectedApiConfig) {
        derivedStateOf {
            val currentKey = selectedApiConfig?.key
            if (selectedApiConfig != null && currentKey != null && currentKey.isNotBlank()) {
                val filteredList = availableModels.filter { it.key == currentKey }
                if (filteredList.isEmpty()) {
                    listOfNotNull(selectedApiConfig)
                } else {
                    filteredList
                }
            } else {
                availableModels
            }
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
                programmaticallyScrolling = false
                userManuallyScrolledAwayFromBottom = false
                try {
                    listStateRef.scrollToItem(0)
                } catch (_: Exception) {
                }
                return@launch
            }
            programmaticallyScrolling = true
            val targetIndex = listStateRef.layoutInfo.totalItemsCount - 1
            if (targetIndex < 0) {
                programmaticallyScrolling = false
                return@launch
            }


            var reachedEnd = false
            var attempts = 0
            val maxAttempts = 12

            try {
                listStateRef.scrollToItem(targetIndex)
                delay(64)
                val layoutInfo = listStateRef.layoutInfo
                if (layoutInfo.visibleItemsInfo.any { it.index == targetIndex } ||
                    (layoutInfo.visibleItemsInfo.lastOrNull()?.index == targetIndex - 1 &&
                            layoutInfo.visibleItemsInfo.last().offset + layoutInfo.visibleItemsInfo.last().size <= layoutInfo.viewportEndOffset + 5)) {
                    reachedEnd = true
                }
            } catch (e: Exception) {
            }

            while (!reachedEnd && attempts < maxAttempts && isActive) {
                attempts++
                try {
                    listStateRef.animateScrollBy(
                        value = 1500f,
                        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing)
                    )
                    delay(150)
                    val layoutInfo = listStateRef.layoutInfo
                    if (layoutInfo.visibleItemsInfo.any { it.index == targetIndex } ||
                        (layoutInfo.visibleItemsInfo.lastOrNull()?.index == targetIndex - 1 &&
                                layoutInfo.visibleItemsInfo.last().offset + layoutInfo.visibleItemsInfo.last().size <= layoutInfo.viewportEndOffset + 5)) {
                        reachedEnd = true
                        break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    delay(100)
                }
            }

            if (reachedEnd) {
                userManuallyScrolledAwayFromBottom = false
            }
            programmaticallyScrolling = false
            ongoingScrollJob = null
        }
    }

    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }

    val resetInactivityTimer: () -> Unit = {
        lastInteractionTime = System.currentTimeMillis()
    }

    LaunchedEffect(key1 = loadedHistoryIndex, key2 = messages.hashCode()) {
        val currentMessagesSnapshot = messages.toList()
        val currentLoadedIndex = loadedHistoryIndex
        val sessionJustChanged = previousLoadedHistoryIndexState != currentLoadedIndex

        if (sessionJustChanged) {
            delay(SESSION_SWITCH_SCROLL_DELAY_MS)
            previousLoadedHistoryIndexState = currentLoadedIndex
        }

        if (currentMessagesSnapshot.isNotEmpty()) {
            scrollToBottomGuaranteed(
                "InitialOrSessionChange",
                messagesRef = currentMessagesSnapshot
            )
        } else {
            coroutineScope.launch {
                programmaticallyScrolling = true
                userManuallyScrolledAwayFromBottom = false
                try {
                    listState.scrollToItem(0)
                } catch (e: Exception) {
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
                if (source == NestedScrollSource.Drag) {
                    resetInactivityTimer()
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                resetInactivityTimer()
                return Velocity.Zero
            }
        }
    }

    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            if (layoutInfo.visibleItemsInfo.isEmpty()) {
                true
            } else {
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
                lastVisibleItem != null && lastVisibleItem.index >= layoutInfo.totalItemsCount - 1 &&
                        lastVisibleItem.offset + lastVisibleItem.size <= layoutInfo.viewportEndOffset + 10
            }
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && !programmaticallyScrolling) {
            if (isAtBottom) {
                if (userManuallyScrolledAwayFromBottom) {
                    userManuallyScrolledAwayFromBottom = false
                }
            } else {
                if (!userManuallyScrolledAwayFromBottom) {
                    userManuallyScrolledAwayFromBottom = true
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.scrollToBottomEvent.collectLatest {
            resetInactivityTimer()
            scrollToBottomGuaranteed("ViewModelEvent", messagesRef = messages.toList())
        }
    }

    LaunchedEffect(currentStreamingAiMessageId, isApiCalling, userManuallyScrolledAwayFromBottom) {
        if (isApiCalling && currentStreamingAiMessageId != null && !userManuallyScrolledAwayFromBottom) {
            // Coroutine for reasoning text
            launch {
                snapshotFlow { messages.find { it.id == currentStreamingAiMessageId }?.reasoning }
                    .distinctUntilChanged()
                    .collect { reasoning ->
                        if (reasoning?.isNotBlank() == true) {
                            scrollToBottomGuaranteed(
                                "AI_Reasoning_Updated",
                                messagesRef = messages.toList()
                            )
                        }
                    }
            }

            // Coroutine for main message text
            launch {
                snapshotFlow { messages.find { it.id == currentStreamingAiMessageId }?.text?.length }
                    .distinctUntilChanged()
                    .debounce(50)
                    .collect {
                        if (isActive) {
                            scrollToBottomGuaranteed(
                                "AI_Streaming_TextChanged",
                                messagesRef = messages.toList()
                            )
                        }
                    }
            }
        }
    }

    val scrollToBottomButtonVisible by remember {
        derivedStateOf {
            messages.isNotEmpty() && userManuallyScrolledAwayFromBottom
        }
    }

    val showEditDialog by viewModel.showEditDialog.collectAsState()
    val editDialogInputText by viewModel.editDialogInputText.collectAsState()
    val showSourcesDialog by viewModel.showSourcesDialog.collectAsState()
    val sourcesForDialog by viewModel.sourcesForDialog.collectAsState()
    val showSelectableTextDialog by viewModel.showSelectableTextDialog.collectAsState()
    val textForSelectionDialog by viewModel.textForSelectionDialog.collectAsState()
    val imeInsets = WindowInsets.ime

    if (showSelectableTextDialog) {
        SelectableTextDialog(
            textToDisplay = textForSelectionDialog,
            onDismissRequest = { viewModel.dismissSelectableTextDialog() }
        )
    }

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
                onSettingsClick = {
                    navController.navigate(Screen.SETTINGS_SCREEN) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onTitleClick = {
                    resetInactivityTimer()
                    coroutineScope.launch {
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
                        scrollToBottomGuaranteed("FAB_Click", messagesRef = messages.toList())
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
                    val chatListItems = remember(messages.toList(), currentStreamingAiMessageId, isApiCalling) {
                        messages.flatMap { message ->
                            when {
                                message.sender == Sender.User -> {
                                    listOf(ChatListItem.UserMessage(message))
                                }
                                message.isError -> {
                                    listOf(ChatListItem.ErrorMessage(message))
                                }
                                message.sender == Sender.AI -> {
                                    val showLoading = isApiCalling &&
                                            message.id == currentStreamingAiMessageId &&
                                            message.text.isBlank() &&
                                            message.reasoning.isNullOrBlank() &&
                                            !message.contentStarted

                                    if (showLoading) {
                                        listOf(ChatListItem.LoadingIndicator(message.id))
                                    } else {
                                        val reasoningItem = if (!message.reasoning.isNullOrBlank()) {
                                            listOf(ChatListItem.AiMessageReasoning(message))
                                        } else {
                                            emptyList()
                                        }

                                        val blocks = parseMarkdownToBlocks(message.text)
                                        val hasReasoning = reasoningItem.isNotEmpty()
                                        val blockItems = blocks.mapIndexed { index, block ->
                                            ChatListItem.AiMessageBlock(
                                                messageId = message.id,
                                                block = block,
                                                blockIndex = index,
                                                isFirstBlock = index == 0,
                                                isLastBlock = index == blocks.size - 1,
                                                hasReasoning = hasReasoning
                                            )
                                        }

                                        val footerItem = if (!message.webSearchResults.isNullOrEmpty() && !isApiCalling) {
                                            listOf(ChatListItem.AiMessageFooter(message))
                                        } else {
                                            emptyList()
                                        }

                                        reasoningItem + blockItems + footerItem
                                    }
                                }
                                else -> emptyList()
                            }
                        }
                    }

                    ChatMessagesList(
                        chatItems = chatListItems,
                        viewModel = viewModel,
                        listState = listState,
                        nestedScrollConnection = nestedScrollConnection,
                        bubbleMaxWidth = bubbleMaxWidth,
                        onResetInactivityTimer = { resetInactivityTimer() },
                        onShowAiMessageOptions = { msg ->
                            selectedMessageForOptions = msg
                            showAiMessageOptionsBottomSheet = true
                        }
                    )
                }
            }
            ChatInputArea(
                text = text,
                onTextChange = { viewModel.onTextChange(it); resetInactivityTimer() },
                onSendMessageRequest = { messageText, _, attachments ->
                    resetInactivityTimer()
                    viewModel.onSendMessage(messageText = messageText, attachments = attachments)
                },
                isApiCalling = isApiCalling,
                isWebSearchEnabled = isWebSearchEnabled,
                onToggleWebSearch = {
                    resetInactivityTimer()
                    viewModel.toggleWebSearchMode(!isWebSearchEnabled)
                },
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
                availableModels = filteredModelsForBottomSheet,
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

       if (showAiMessageOptionsBottomSheet && selectedMessageForOptions != null) {
           ModalBottomSheet(
               onDismissRequest = { showAiMessageOptionsBottomSheet = false },
               sheetState = aiMessageOptionsBottomSheetState,
               containerColor = Color.White,
           ) {
               Column(modifier = Modifier.padding(bottom = 32.dp)) {
                   ListItem(
                       headlineContent = { Text("选择文本") },
                       leadingContent = { Icon(Icons.Outlined.SelectAll, contentDescription = "选择文本") },
                       modifier = Modifier.clickable {
                           viewModel.showSelectableTextDialog(selectedMessageForOptions!!.text)
                           coroutineScope.launch {
                               aiMessageOptionsBottomSheetState.hide()
                           }.invokeOnCompletion {
                               if (!aiMessageOptionsBottomSheetState.isVisible) {
                                   showAiMessageOptionsBottomSheet = false
                               }
                           }
                       },
                       colors = ListItemDefaults.colors(containerColor = Color.White)
                   )
                   ListItem(
                       headlineContent = { Text("复制全文") },
                       leadingContent = { Icon(Icons.Filled.ContentCopy, contentDescription = "复制全文") },
                       modifier = Modifier.clickable {
                           viewModel.copyToClipboard(selectedMessageForOptions!!.text)
                           coroutineScope.launch {
                               aiMessageOptionsBottomSheetState.hide()
                           }.invokeOnCompletion {
                               if (!aiMessageOptionsBottomSheetState.isVisible) {
                                   showAiMessageOptionsBottomSheet = false
                               }
                           }
                       },
                       colors = ListItemDefaults.colors(containerColor = Color.White)
                   )
                   ListItem(
                       headlineContent = { Text("重新回答") },
                       leadingContent = { Icon(Icons.Filled.Refresh, contentDescription = "重新回答") },
                       modifier = Modifier.clickable {
                           viewModel.regenerateAiResponse(selectedMessageForOptions!!)
                           coroutineScope.launch {
                               aiMessageOptionsBottomSheetState.hide()
                           }.invokeOnCompletion {
                               if (!aiMessageOptionsBottomSheetState.isVisible) {
                                   showAiMessageOptionsBottomSheet = false
                               }
                           }
                       },
                       colors = ListItemDefaults.colors(containerColor = Color.White)
                   )
                   ListItem(
                       headlineContent = { Text("导出文本") },
                       leadingContent = { Icon(Icons.Filled.IosShare, contentDescription = "导出文本") },
                       modifier = Modifier.clickable {
                           viewModel.exportMessageText(selectedMessageForOptions!!.text)
                           coroutineScope.launch {
                               aiMessageOptionsBottomSheetState.hide()
                           }.invokeOnCompletion {
                               if (!aiMessageOptionsBottomSheetState.isVisible) {
                                   showAiMessageOptionsBottomSheet = false
                               }
                           }
                       },
                       colors = ListItemDefaults.colors(containerColor = Color.White)
                   )
               }
           }
       }
    }
}

@Composable
internal fun SelectableTextDialog(textToDisplay: String, onDismissRequest: () -> Unit) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 24.dp)
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.75f),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            SelectionContainer(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = textToDisplay,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}