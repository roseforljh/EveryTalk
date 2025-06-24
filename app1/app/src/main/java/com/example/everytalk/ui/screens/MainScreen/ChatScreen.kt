package com.example.everytalk.ui.screens.MainScreen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.navigation.Screen
import com.example.everytalk.statecontroler.AppViewModel
import com.example.everytalk.statecontroler.ConversationScrollState
import com.example.everytalk.ui.components.AppTopBar
import com.example.everytalk.ui.components.ScrollToBottomButton
import com.example.everytalk.ui.components.WebSourcesDialog
import com.example.everytalk.ui.screens.MainScreen.chat.ChatInputArea
import com.example.everytalk.ui.screens.MainScreen.chat.ChatMessagesList
import com.example.everytalk.ui.screens.MainScreen.chat.EditMessageDialog
import com.example.everytalk.ui.screens.MainScreen.chat.ChatScrollStateManager
import com.example.everytalk.ui.screens.MainScreen.chat.EmptyChatView
import com.example.everytalk.ui.screens.MainScreen.chat.ModelSelectionBottomSheet
import com.example.everytalk.ui.screens.MainScreen.chat.rememberChatScrollStateManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.cancellation.CancellationException


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
    val selectedMediaItems = viewModel.selectedMediaItems
    val isLoadingHistory by viewModel.isLoadingHistory.collectAsState()
    val conversationId by viewModel.currentConversationId.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    val loadedHistoryIndex by viewModel.loadedHistoryIndex.collectAsState()


    val listState = remember(conversationId) {
        LazyListState(0, 0)
    }

    LaunchedEffect(conversationId) {
        val savedState = viewModel.getScrollState(conversationId)
        if (savedState != null && savedState.firstVisibleItemIndex > 0) {
            snapshotFlow { isLoadingHistory }
                .filter { !it }
                .first()

            listState.scrollToItem(
                index = savedState.firstVisibleItemIndex,
                scrollOffset = savedState.firstVisibleItemScrollOffset
            )
        }
    }

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val bottomScrollThreshold = with(density) { 8.dp.toPx() }
    val isAtBottom = {
        val layoutInfo = listState.layoutInfo
        if (layoutInfo.visibleItemsInfo.isEmpty()) {
            true
        } else {
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisibleItem != null && lastVisibleItem.index >= layoutInfo.totalItemsCount - 1 &&
                    lastVisibleItem.offset + lastVisibleItem.size <= layoutInfo.viewportEndOffset + bottomScrollThreshold
        }
    }

    var showScrollToBottomButton by remember { mutableStateOf(false) }
    var activityCounter by remember { mutableStateOf(0) }

    val resetFabTimeout = {
        activityCounter++
    }

    val scrollStateManager = rememberChatScrollStateManager(listState, coroutineScope)
    val isAutoScrolling by scrollStateManager::isAutoScrolling
    var userManuallyScrolledAwayFromBottom by scrollStateManager::userManuallyScrolledAwayFromBottom

    LaunchedEffect(activityCounter) {
        if (!isAtBottom()) {
            showScrollToBottomButton = true
            delay(3000)
            showScrollToBottomButton = false
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { isAtBottom() }
            .distinctUntilChanged()
            .collect { atBottom ->
                if (atBottom) {
                    showScrollToBottomButton = false
                }
            }
    }

    DisposableEffect(conversationId) {
        val idToSaveFor = conversationId

        onDispose {
            val stateToSave = ConversationScrollState(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                userScrolledAway = showScrollToBottomButton
            )
            viewModel.saveScrollState(idToSaveFor, stateToSave)
        }
    }

    val focusRequester = remember { FocusRequester() }


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


    val scrollToBottomGuaranteed = scrollStateManager::scrollToBottomGuaranteed


    LaunchedEffect(messages.size, isLoadingHistory) {
        if (isLoadingHistory || messages.isEmpty()) return@LaunchedEffect

        val lastMessage = messages.last()
        val isNewSession = loadedHistoryIndex == null

        if (lastMessage.sender == Sender.User && isNewSession) {
            userManuallyScrolledAwayFromBottom = false
            scrollToBottomGuaranteed("New user message")
        }
    }

    val screenWidth = configuration.screenWidthDp.dp
    val bubbleMaxWidth = remember(screenWidth) { screenWidth.coerceAtMost(600.dp) }

    LaunchedEffect(Unit) {
        viewModel.scrollToBottomEvent.collectLatest {
            userManuallyScrolledAwayFromBottom = false
            scrollToBottomGuaranteed("ViewModelEvent")
        }
    }

    LaunchedEffect(currentStreamingAiMessageId) {
        if (currentStreamingAiMessageId == null) return@LaunchedEffect
        val scrollThrottleMs = 250L
        var lastScrollTime = 0L
        snapshotFlow { messages.find { it.id == currentStreamingAiMessageId }?.text }
            .distinctUntilChanged()
            .collect {
                val currentTime = System.currentTimeMillis()
                // 只有当用户没有手动滚动离开底部时才自动滚动
                if (isActive && !userManuallyScrolledAwayFromBottom
                    && currentTime - lastScrollTime > scrollThrottleMs) {
                    lastScrollTime = currentTime
                    scrollToBottomGuaranteed("AI_Streaming_ContentChanged")
                }
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
        modifier = modifier.fillMaxSize(),
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
            ScrollToBottomButton(
                visible = showScrollToBottomButton,
                isAutoScrolling = scrollStateManager.isAutoScrolling,
                activityTrigger = activityCounter,
                onClick = {
                    userManuallyScrolledAwayFromBottom = false
                    scrollToBottomGuaranteed("FAB_Click")
                }
            )
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
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            resetFabTimeout()
                        }
                    }
            ) {
                when {
                    isLoadingHistory -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    messages.isEmpty() -> {
                        EmptyChatView(density = density)
                    }
                    else -> {
                        val chatListItems by viewModel.chatListItems.collectAsState()

                        ChatMessagesList(
                            chatItems = chatListItems,
                            viewModel = viewModel,
                            listState = listState,
                            nestedScrollConnection = scrollStateManager.nestedScrollConnection,
                            bubbleMaxWidth = bubbleMaxWidth,
                            onShowAiMessageOptions = { msg ->
                                selectedMessageForOptions = msg
                                showAiMessageOptionsBottomSheet = true
                            },
                            onImageLoaded = {
                                if (!userManuallyScrolledAwayFromBottom) {
                                    scrollToBottomGuaranteed("Image loaded after check")
                                }
                            }
                        )
                    }
                }

            }
            ChatInputArea(
                text = text,
                onTextChange = {
                    viewModel.onTextChange(it)
                    resetFabTimeout()
                },
                onSendMessageRequest = { messageText, _, attachments ->
                    viewModel.onSendMessage(messageText = messageText, attachments = attachments)
                },
                selectedMediaItems = selectedMediaItems,
                onAddMediaItem = { viewModel.addMediaItem(it) },
                onRemoveMediaItemAtIndex = { viewModel.removeMediaItemAtIndex(it) },
                onClearMediaItems = { viewModel.clearMediaItems() },
                isApiCalling = isApiCalling,
                isWebSearchEnabled = isWebSearchEnabled,
                onToggleWebSearch = {
                    viewModel.toggleWebSearchMode(!isWebSearchEnabled)
                },
                onStopApiCall = { viewModel.onCancelAPICall() },
                focusRequester = focusRequester,
                selectedApiConfig = selectedApiConfig,
                onShowSnackbar = { viewModel.showSnackbar(it) },
                imeInsets = imeInsets,
                density = density,
                keyboardController = keyboardController,
                onFocusChange = { resetFabTimeout() }
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
                    viewModel.selectConfig(modelConfig)
                    coroutineScope.launch {
                        bottomSheetState.hide()
                    }.invokeOnCompletion {
                        if (!bottomSheetState.isVisible) {
                            showModelSelectionBottomSheet = false
                        }
                    }
                },
                allApiConfigs = availableModels,
                onPlatformSelected = { platformConfig ->
                    viewModel.selectConfig(platformConfig)
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