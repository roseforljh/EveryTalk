package com.android.everytalk.ui.screens.MainScreen

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.navigation.Screen
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.statecontroller.ConversationScrollState
import com.android.everytalk.statecontroller.SimpleModeManager
import com.android.everytalk.ui.components.AppTopBar
import com.android.everytalk.ui.components.AnimatedWebSourcesDialog
import com.android.everytalk.ui.components.EveryTalkLoadingIndicator
import com.android.everytalk.ui.components.ScrollToBottomButton
import com.android.everytalk.ui.components.WebSourcesDialogEdgeGap
import com.android.everytalk.ui.components.ImagePreviewDialog
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogCancelColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.ChatInputArea
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.ChatMessagesList
import com.android.everytalk.ui.components.content.LocalStickyHeaderTop
import com.android.everytalk.ui.screens.MainScreen.chat.dialog.EditMessageDialog
import com.android.everytalk.ui.screens.MainScreen.chat.dialog.SystemPromptDialog
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.EmptyChatView
import com.android.everytalk.ui.screens.MainScreen.chat.models.ModelSelectionBottomSheet
import com.android.everytalk.ui.screens.MainScreen.chat.text.state.rememberChatScrollStateManager
import com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem
import com.android.everytalk.ui.screens.MainScreen.chat.core.PlaceholderRole
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay

internal fun shouldPreserveScrollSessionOnConversationIdChange(
    previousConversationId: String?,
    newConversationId: String,
    messages: List<Message>,
    isHistoryConversationLoad: Boolean = false,
): Boolean {
    if (isHistoryConversationLoad) return false
    if (previousConversationId.isNullOrBlank()) {
        return false
    }
    if (previousConversationId == newConversationId) {
        return true
    }

    val derivedStableConversationId = messages.firstOrNull { it.sender == Sender.User }?.id
        ?: messages.firstOrNull { it.sender == Sender.System && !it.isPlaceholderName }?.id
        ?: messages.firstOrNull()?.id

    val previousIdWasTemporary = previousConversationId.startsWith("new_chat_") ||
        previousConversationId.startsWith("chat_") ||
        previousConversationId.startsWith("user_temp_")
    return previousIdWasTemporary && derivedStableConversationId == newConversationId
}

internal fun buildHistoryLoadingBubblePlaceholders(conversationId: String): List<ChatListItem> {
    val keyPrefix = conversationId.ifBlank { "history" }
    return listOf(
        ChatListItem.LoadingBubblePlaceholder(
            id = "$keyPrefix-history-placeholder-0",
            role = PlaceholderRole.User,
            widthFraction = 0.58f,
            estimatedHeightDp = 52,
        ),
        ChatListItem.LoadingBubblePlaceholder(
            id = "$keyPrefix-history-placeholder-1",
            role = PlaceholderRole.Assistant,
            widthFraction = 0.82f,
            estimatedHeightDp = 92,
        ),
        ChatListItem.LoadingBubblePlaceholder(
            id = "$keyPrefix-history-placeholder-2",
            role = PlaceholderRole.User,
            widthFraction = 0.46f,
            estimatedHeightDp = 48,
        ),
        ChatListItem.LoadingBubblePlaceholder(
            id = "$keyPrefix-history-placeholder-3",
            role = PlaceholderRole.Assistant,
            widthFraction = 0.76f,
            estimatedHeightDp = 128,
        ),
        ChatListItem.LoadingBubblePlaceholder(
            id = "$keyPrefix-history-placeholder-4",
            role = PlaceholderRole.Assistant,
            widthFraction = 0.64f,
            estimatedHeightDp = 72,
        ),
    )
}

internal fun shouldHideHistoryLoadingSkeleton(
    chatItems: List<ChatListItem>,
): Boolean {
    return chatItems.isNotEmpty()
}

internal fun shouldShowHistoryConversationLoadingOverlay(
    isLoadingHistory: Boolean,
    overlayKey: String?,
    observedLoadGeneration: Long,
    currentLoadGeneration: Long,
): Boolean = isLoadingHistory ||
    overlayKey != null ||
    shouldStartHistoryConversationLoadingOverlay(
        observedLoadGeneration = observedLoadGeneration,
        currentLoadGeneration = currentLoadGeneration,
    )

internal fun shouldStartHistoryConversationLoadingOverlay(
    observedLoadGeneration: Long,
    currentLoadGeneration: Long,
): Boolean = currentLoadGeneration > 0L && currentLoadGeneration != observedLoadGeneration

internal fun shouldClearHistoryLoadingOverlay(
    completedGeneration: Long,
    currentGeneration: Long,
    completedOverlayKey: String?,
    currentOverlayKey: String?,
): Boolean = completedOverlayKey != null &&
    completedGeneration == currentGeneration &&
    completedOverlayKey == currentOverlayKey

internal fun isHistoryConversationReadyForInitialBottom(
    currentConversationId: String,
    scrollSessionKey: String,
    isLoadingHistory: Boolean,
    isLoadingHistoryData: Boolean = false,
    requireMatchingScrollSession: Boolean = true,
    messages: List<Message>,
    chatItems: List<ChatListItem>,
    laidOutItemCount: Int,
): Boolean {
    if (isLoadingHistory || isLoadingHistoryData) return false
    if (requireMatchingScrollSession && currentConversationId != scrollSessionKey) return false

    val expectedMessageIds = messages
        .dropWhile { message ->
            message.sender == Sender.System && !message.isPlaceholderName && message.text.isNotBlank()
        }
        .filter { message -> message.sender != Sender.Tool || message.isError }
        .mapTo(mutableSetOf()) { message -> message.id }
    val renderedMessageIds = chatItems.mapNotNullTo(mutableSetOf()) { item ->
        when (item) {
            is ChatListItem.UserMessage -> item.messageId
            is ChatListItem.SystemMessage -> item.messageId
            is ChatListItem.AiMessage -> item.messageId
            is ChatListItem.AiMessageCode -> item.messageId
            is ChatListItem.AiMessageStreaming -> item.messageId
            is ChatListItem.AiMessageCodeStreaming -> item.messageId
            is ChatListItem.AiMessageReasoning -> item.message.id
            is ChatListItem.AiMessageFooter -> item.message.id
            is ChatListItem.ErrorMessage -> item.messageId
            is ChatListItem.LoadingIndicator -> item.messageId
            is ChatListItem.StatusIndicator -> item.messageId
            is ChatListItem.LoadingBubblePlaceholder -> null
        }
    }

    return expectedMessageIds == renderedMessageIds &&
        laidOutItemCount >= chatItems.size
}

@Composable
internal fun HistoryConversationLoadingOverlay(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.88f))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { change ->
                            change.consume()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        EveryTalkLoadingIndicator(
            size = 32.dp,
            strokeWidth = 3.dp,
            contentDescription = "正在加载会话",
        )
    }
}

internal fun calculateSourcesDialogBottomAvoidance(
    inputAreaHeight: Dp,
    inputBottomInset: Dp,
    edgeGap: Dp = WebSourcesDialogEdgeGap
): Dp {
    val inputContentHeight = if (inputAreaHeight > inputBottomInset) {
        inputAreaHeight - inputBottomInset
    } else {
        0.dp
    }
    return inputContentHeight + edgeGap
}

internal fun calculateSourcesDialogTopAvoidance(
    topControlsBottom: Dp,
    statusBarHeight: Dp,
    edgeGap: Dp = WebSourcesDialogEdgeGap
): Dp {
    val controlsBottomBelowStatusBar = if (topControlsBottom > statusBarHeight) {
        topControlsBottom - statusBarHeight
    } else {
        0.dp
    }
    return controlsBottomBelowStatusBar + edgeGap
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    viewModel: AppViewModel,
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val messages: List<Message> = viewModel.messages
    val text by viewModel.text.collectAsState()
    
    // Dynamic config selection based on mode
    val uiMode by viewModel.uiModeFlow.collectAsState()
    val textConfig by viewModel.selectedApiConfig.collectAsState()
    val imageConfig by viewModel.selectedImageGenApiConfig.collectAsState()
    
    val selectedApiConfig by remember(uiMode, textConfig, imageConfig) {
        derivedStateOf {
            if (uiMode == SimpleModeManager.ModeType.IMAGE) imageConfig else textConfig
        }
    }

    val isApiCalling by viewModel.isTextApiCalling.collectAsState()
    val currentStreamingAiMessageId by viewModel.currentTextStreamingAiMessageId.collectAsState()
    val isWebSearchEnabled by viewModel.isWebSearchEnabled.collectAsState()
    val isCodeExecutionEnabled by viewModel.isCodeExecutionEnabled.collectAsState()
    val supportsNativeWebSearch by remember(selectedApiConfig) {
        derivedStateOf { com.android.everytalk.data.network.WebSearchSupport.supportsNativeWebSearch(selectedApiConfig) }
    }
    val selectedExternalWebSearchProviderId by viewModel.selectedExternalWebSearchProviderId.collectAsState()
    val canUseWebSearch by remember(selectedApiConfig, selectedExternalWebSearchProviderId) {
        derivedStateOf {
            supportsNativeWebSearch ||
                viewModel.canUseSelectedExternalWebSearchProvider() ||
                com.android.everytalk.data.network.WebSearchSupport.canUseJinaSearch()
        }
    }
    val selectedMediaItems = viewModel.selectedMediaItems
    val isLoadingHistory by viewModel.isLoadingHistory.collectAsState()
    val historyLoadGeneration by viewModel.historyLoadGeneration.collectAsState()
    val mcpServerStates by viewModel.mcpServerStates.collectAsState()
    val isLoadingHistoryData by viewModel.isLoadingHistoryData.collectAsState()
    val conversationId by viewModel.currentConversationId.collectAsState()
    val latestReleaseInfo by viewModel.latestReleaseInfo.collectAsState()
    val updateInfo by viewModel.updateInfo.collectAsState()
    val mcpUiStage by viewModel.mcpUiStage.collectAsState()
    val systemPrompt by viewModel.systemPrompt.collectAsState()
    val isSystemPromptEngaged by viewModel.isSystemPromptEngaged.collectAsState()
    val isSystemPromptExpanded by remember(conversationId) {
        derivedStateOf {
            viewModel.systemPromptExpandedState[conversationId] ?: false
        }
    }
 
     val coroutineScope = rememberCoroutineScope()
     val loadedHistoryIndex by viewModel.loadedHistoryIndex.collectAsState()
    val chatListItems by viewModel.chatListItems.collectAsState()
    var observedHistoryLoadGeneration by remember {
        mutableLongStateOf(historyLoadGeneration)
    }
    var historyLoadingOverlayKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(historyLoadGeneration) {
        if (shouldStartHistoryConversationLoadingOverlay(
                observedLoadGeneration = observedHistoryLoadGeneration,
                currentLoadGeneration = historyLoadGeneration,
            )
        ) {
            historyLoadingOverlayKey = "history-load-$historyLoadGeneration"
            observedHistoryLoadGeneration = historyLoadGeneration
        }
    }

    val isLoadingHistoryState = rememberUpdatedState(isLoadingHistory)
    LaunchedEffect(historyLoadingOverlayKey) {
        val key = historyLoadingOverlayKey ?: return@LaunchedEffect
        delay(15_000L)
        if (historyLoadingOverlayKey == key && !isLoadingHistoryState.value) {
            historyLoadingOverlayKey = null
        }
    }

    val isHistoryLoadingOverlayVisible = shouldShowHistoryConversationLoadingOverlay(
        isLoadingHistory = isLoadingHistory,
        overlayKey = historyLoadingOverlayKey,
        observedLoadGeneration = observedHistoryLoadGeneration,
        currentLoadGeneration = historyLoadGeneration,
    )
    val isHistoryLoadingOverlayVisibleState = rememberUpdatedState(isHistoryLoadingOverlayVisible)
    val historyLoadingOverlayKeyState = rememberUpdatedState(historyLoadingOverlayKey)
    val observedHistoryLoadGenerationState = rememberUpdatedState(observedHistoryLoadGeneration)
    val historyLoadGenerationState = rememberUpdatedState(historyLoadGeneration)
    val chatListItemsState = rememberUpdatedState(chatListItems)
    val messagesState = rememberUpdatedState(messages.toList())
    val conversationIdState = rememberUpdatedState(conversationId)
    val isLoadingHistoryDataState = rememberUpdatedState(isLoadingHistoryData)

    // 获取抽屉和搜索相关状态
    val isDrawerOpen = !viewModel.drawerState.isClosed
    val isSearchActiveInDrawer by viewModel.isSearchActiveInDrawer.collectAsState()
    val expandedDrawerItemIndex by viewModel.expandedDrawerItemIndex.collectAsState()
    
    // 处理返回键逻辑 - 优先处理抽屉相关操作，再处理页面导航
    BackHandler(enabled = isDrawerOpen && expandedDrawerItemIndex != null) {
        // 最高优先级：收起展开的历史项
        viewModel.setExpandedDrawerItemIndex(null)
    }
    
    BackHandler(enabled = isDrawerOpen && isSearchActiveInDrawer) {
        // 中等优先级：退出搜索模式
        viewModel.setSearchActiveInDrawer(false)
    }
    
    BackHandler(enabled = isDrawerOpen && expandedDrawerItemIndex == null && !isSearchActiveInDrawer) {
        // 低优先级：关闭抽屉
        coroutineScope.launch {
            viewModel.drawerState.close()
        }
    }


    var scrollSessionKey by remember { mutableStateOf(conversationId) }
    var previousConversationIdForScroll by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(
        conversationId,
        messages.size,
        messages.firstOrNull()?.id,
        isHistoryLoadingOverlayVisible,
    ) {
        val shouldPreserveScrollSession = shouldPreserveScrollSessionOnConversationIdChange(
            previousConversationId = previousConversationIdForScroll,
            newConversationId = conversationId,
            messages = messages.toList(),
            isHistoryConversationLoad = isHistoryLoadingOverlayVisible,
        )

        if (!shouldPreserveScrollSession) {
            scrollSessionKey = conversationId
        }

        previousConversationIdForScroll = conversationId
    }

    val listState = remember(scrollSessionKey) {
        LazyListState(0, 0)
    }
    val scrollStateManager = rememberChatScrollStateManager(listState, coroutineScope)

    var initialScrollHandled by remember(scrollSessionKey) { mutableStateOf(false) }

    LaunchedEffect(scrollSessionKey, listState, historyLoadGeneration) {
        if (initialScrollHandled) return@LaunchedEffect

        val effectGeneration = historyLoadGeneration
        while (!initialScrollHandled) {
            val readyToken = snapshotFlow {
                val currentGeneration = historyLoadGenerationState.value
                if (currentGeneration != effectGeneration) return@snapshotFlow null

                val generationObserved = !shouldStartHistoryConversationLoadingOverlay(
                    observedLoadGeneration = observedHistoryLoadGenerationState.value,
                    currentLoadGeneration = currentGeneration,
                )
                if (!generationObserved) return@snapshotFlow null

                val overlayVisible = isHistoryLoadingOverlayVisibleState.value
                val ready = isHistoryConversationReadyForInitialBottom(
                    currentConversationId = conversationIdState.value,
                    scrollSessionKey = scrollSessionKey,
                    isLoadingHistory = isLoadingHistoryState.value,
                    isLoadingHistoryData = isLoadingHistoryDataState.value,
                    requireMatchingScrollSession = overlayVisible,
                    messages = messagesState.value,
                    chatItems = chatListItemsState.value,
                    laidOutItemCount = listState.layoutInfo.totalItemsCount,
                )
                if (ready) currentGeneration to historyLoadingOverlayKeyState.value else null
            }
                .filterNotNull()
                .first()

            withFrameNanos { }
            if (historyLoadGenerationState.value != readyToken.first) {
                return@LaunchedEffect
            }

            val stillReady = isHistoryConversationReadyForInitialBottom(
                currentConversationId = conversationIdState.value,
                scrollSessionKey = scrollSessionKey,
                isLoadingHistory = isLoadingHistoryState.value,
                isLoadingHistoryData = isLoadingHistoryDataState.value,
                requireMatchingScrollSession = readyToken.second != null ||
                    isHistoryLoadingOverlayVisibleState.value,
                messages = messagesState.value,
                chatItems = chatListItemsState.value,
                laidOutItemCount = listState.layoutInfo.totalItemsCount,
            )
            if (!stillReady) continue

            val targetIndex = listState.layoutInfo.totalItemsCount - 1
            if (targetIndex >= 0) {
                listState.scrollToItem(targetIndex)
                listState.scrollBy(Float.MAX_VALUE)
            }
            scrollStateManager.pinToRealBottomUntilUserScroll()
            if (shouldClearHistoryLoadingOverlay(
                    completedGeneration = readyToken.first,
                    currentGeneration = historyLoadGenerationState.value,
                    completedOverlayKey = readyToken.second,
                    currentOverlayKey = historyLoadingOverlayKeyState.value,
                )
            ) {
                historyLoadingOverlayKey = null
            }
            initialScrollHandled = true
        }
    }

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val keyboardController = LocalSoftwareKeyboardController.current


    val isAtBottom by scrollStateManager.isAtBottom
    
    LaunchedEffect(scrollStateManager, currentStreamingAiMessageId) {
        val isCurrentlyStreaming = currentStreamingAiMessageId != null
        scrollStateManager.updateStreamingState(isCurrentlyStreaming)
    }



    LaunchedEffect(conversationId, listState) {
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress
            )
        }
            .distinctUntilChanged()
            .filter { (_, _, isScrolling) ->
                !isScrolling && !isLoadingHistoryState.value && !isHistoryLoadingOverlayVisibleState.value
            }
            .collect { (index, offset, _) ->
                if (listState.layoutInfo.totalItemsCount > 0) {
                    val existing = viewModel.getScrollState(conversationId)
                    viewModel.cacheScrollState(
                        conversationId,
                        ConversationScrollState(
                            firstVisibleItemIndex = index,
                            firstVisibleItemScrollOffset = offset,
                            userScrolledAway = !isAtBottom,
                            firstBubbleScreenY = existing?.firstBubbleScreenY ?: -1
                        )
                    )
                }
            }
    }

    DisposableEffect(conversationId, isAtBottom) {
        val idToSaveFor = conversationId
        onDispose {
            val existing = viewModel.getScrollState(idToSaveFor)
            val stateToSave = ConversationScrollState(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                userScrolledAway = !isAtBottom,
                firstBubbleScreenY = existing?.firstBubbleScreenY ?: -1
            )
            viewModel.saveScrollState(idToSaveFor, stateToSave)
        }
    }


    LaunchedEffect(scrollStateManager) {
        viewModel.scrollToBottomEvent.collect {
            scrollStateManager.jumpToBottom()
        }
    }

    // 监听滚动到指定消息的事件
    LaunchedEffect(scrollStateManager) {
        viewModel.scrollToItemEvent.collect { messageId ->
            android.util.Log.d("ChatScreen", "scrollToItemEvent received: messageId=$messageId")
            var attempts = 0
            var targetIndex = -1
            while (attempts < 20) {
                val currentItems = viewModel.chatListItems.value
                targetIndex = currentItems.indexOfFirst {
                    when (it) {
                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.UserMessage -> it.messageId == messageId
                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage -> it.messageId == messageId
                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageCode -> it.messageId == messageId
                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageReasoning -> it.message.id == messageId
                        else -> false
                    }
                }
                
                if (targetIndex != -1) {
                    break
                }
                delay(50)
                attempts++
            }
            
            android.util.Log.d("ChatScreen", "scrollToItemEvent: targetIndex=$targetIndex, totalItems=${listState.layoutInfo.totalItemsCount}")
            if (targetIndex != -1) {
                scrollStateManager.scrollItemToTop(targetIndex)
            } else {
                scrollStateManager.smoothScrollToBottom(isUserAction = true)
            }
        }
    }

    val focusRequester = remember { FocusRequester() }


    val bottomSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    var showModelSelectionBottomSheet by remember { mutableStateOf(false) }
    var showEditConfigDialog by remember { mutableStateOf(false) }
    
    val textModels by viewModel.apiConfigs.collectAsState()
    val imageModels by viewModel.imageGenApiConfigs.collectAsState()
    
    val availableModels by remember(uiMode, textModels, imageModels) {
        derivedStateOf {
            if (uiMode == SimpleModeManager.ModeType.IMAGE) imageModels else textModels
        }
    }

    var showAiMessageOptionsBottomSheet by remember { mutableStateOf(false) }
    var selectedMessageForOptions by remember { mutableStateOf<Message?>(null) }
    val aiMessageOptionsBottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val filteredModelsForBottomSheet by remember(availableModels, selectedApiConfig) {
        derivedStateOf {
            selectedApiConfig?.let { sel ->
                val filtered = availableModels.filter {
                    it.provider == sel.provider &&
                    it.address == sel.address &&
                    it.key == sel.key &&
                    it.channel == sel.channel
                }
                if (filtered.isNotEmpty()) filtered else listOfNotNull(sel)
            } ?: availableModels
        }
    }





    val screenWidth = configuration.screenWidthDp.dp
    // 放宽列表传入的上限为整屏，由子项根据角色再做 60%/80% 约束
    val bubbleMaxWidth = remember(screenWidth) { screenWidth }




    val showEditDialog by viewModel.showEditDialog.collectAsState()
    val editDialogInputText by viewModel.editDialogInputText.collectAsState()
    val showSourcesDialog by viewModel.showSourcesDialog.collectAsState()
    val sourcesForDialog by viewModel.sourcesForDialog.collectAsState()
    val imeInsets = WindowInsets.ime
    
    // 获取输入法高度用于整体布局偏移
    val imeHeightPx by remember {
        derivedStateOf { imeInsets.getBottom(density) }
    }
    val imeHeightDp = with(density) { imeHeightPx.toDp() }
    
    // 计算输入法是否可见
    val isKeyboardVisible by remember {
        derivedStateOf { imeHeightPx > 0 }
    }

    var inputAreaHeightPx by remember { mutableIntStateOf(0) }
    var topControlsBottomPx by remember { mutableIntStateOf(0) }
    val inputAreaHeightDp = with(density) { inputAreaHeightPx.toDp() }
    val topControlsBottomDp = with(density) { topControlsBottomPx.toDp() }
    val statusBarHeightDp = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val inputBaseBottomInsetDp = with(density) {
        WindowInsets.navigationBarsIgnoringVisibility.getBottom(density).toDp() + 12.dp
    }
    val inputBottomInsetDp = if (imeHeightDp > inputBaseBottomInsetDp) {
        imeHeightDp
    } else {
        inputBaseBottomInsetDp
    }
    val sourcesDialogBottomAvoidance = calculateSourcesDialogBottomAvoidance(
        inputAreaHeight = inputAreaHeightDp,
        inputBottomInset = inputBottomInsetDp
    )
    val sourcesDialogTopAvoidance = calculateSourcesDialogTopAvoidance(
        topControlsBottom = topControlsBottomDp,
        statusBarHeight = statusBarHeightDp
    )

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            if (isGranted) {
                // TODO: 权限授予后开始录音
                viewModel.showSnackbar("录音权限已授予")
            } else {
                viewModel.showSnackbar("需要录音权限才能使用此功能")
            }
        }
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    ) { scaffoldPaddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPaddingValues)
        ) {
            // 主内容区域 - 消息列表填满整个区域
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter
            ) {
                when {
                    messages.isEmpty() && !isHistoryLoadingOverlayVisible -> {
                        EmptyChatView(
                            onNavigateToImageGen = {
                                viewModel.simpleModeManager.setIntendedMode(com.android.everytalk.statecontroller.SimpleModeManager.ModeType.IMAGE)
                                navController.navigate(Screen.IMAGE_GENERATION_SCREEN) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            onNavigateToVoice = {
                                navController.navigate(Screen.VOICE_INPUT_SCREEN)
                            },
                            onNavigateToSettings = {
                                navController.navigate(Screen.SETTINGS_SCREEN) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            onShowSystemPrompt = {
                                viewModel.toggleSystemPromptExpanded()
                                viewModel.showSystemPromptDialog()
                            }
                        )
                    }
                    else -> {

                        var stickyHeaderTopPx by remember { mutableFloatStateOf(0f) }
                        val contentPaddingTopPx = with(density) { 8.dp.toPx() }
                        // 添加顶栏高度偏移：AppTopBar 是 85dp 高，浮动在内容上方
                        // 代码块 Header 需要吸附在顶栏下方，而不是屏幕顶部
                        val topBarHeightPx = with(density) { 85.dp.toPx() }

                        CompositionLocalProvider(LocalStickyHeaderTop provides stickyHeaderTopPx) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .onGloballyPositioned { coordinates ->
                                        val y = coordinates.positionInWindow().y
                                        if (y.isFinite() && y >= 0f) {
                                            // 吸顶位置 = 容器顶部 + 顶栏高度 + 内容 padding
                                            stickyHeaderTopPx = y + topBarHeightPx + contentPaddingTopPx
                                        }
                                    }
                            ) {
                                ChatMessagesList(
                                chatItems = chatListItems,
                                viewModel = viewModel,
                                listState = listState,
                                scrollStateManager = scrollStateManager,
                                scrollSessionKey = scrollSessionKey,
                                conversationId = conversationId,
                                bubbleMaxWidth = bubbleMaxWidth,
                                onShowAiMessageOptions = { msg ->
                                    selectedMessageForOptions = msg
                                    showAiMessageOptionsBottomSheet = true
                                },
                                onImageLoaded = {
                                    if (scrollStateManager.isAtBottom.value) {
                                        scrollStateManager.jumpToBottom()
                                    }
                                },
                                onImageClick = { imageUrl ->
                                    val allUrls = chatListItems.flatMap { item ->
                                        when (item) {
                                            is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.UserMessage -> {
                                                item.attachments.mapNotNull { att ->
                                                    when (att) {
                                                        is com.android.everytalk.models.SelectedMediaItem.ImageFromUri ->
                                                            att.uri.toString()
                                                        else -> null
                                                    }
                                                }
                                            }
                                            is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage -> {
                                                viewModel.getMessageById(item.messageId)?.imageUrls ?: emptyList()
                                            }
                                            else -> emptyList()
                                        }
                                    }
                                    val index = allUrls.indexOf(imageUrl).coerceAtLeast(0)
                                    if (allUrls.size > 1) {
                                        viewModel.showImageViewer(allUrls, index)
                                    } else {
                                        viewModel.showImageViewer(imageUrl)
                                    }
                                },
                                additionalBottomPadding = inputAreaHeightDp
                            )
                            }
                        }
                    }
                }
            }

            // 浮动输入框 - 对齐到底部
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.BottomCenter
            ) {
                ChatInputArea(
                text = text,
                onTextChange = {
                    viewModel.onTextChange(it)
                },
                onSendMessageRequest = { messageText, _, attachments, mimeType ->
                    scrollStateManager.lockAutoScroll()
                    viewModel.onSendMessage(messageText = messageText, attachments = attachments, audioBase64 = null, mimeType = mimeType)
                    keyboardController?.hide()
                },
                selectedMediaItems = selectedMediaItems,
                onAddMediaItem = { viewModel.addMediaItem(it) },
                onRemoveMediaItemAtIndex = { viewModel.removeMediaItemAtIndex(it) },
                onClearMediaItems = { viewModel.clearMediaItems() },
                isApiCalling = isApiCalling,
                isWebSearchEnabled = isWebSearchEnabled,
                isWebSearchAvailable = canUseWebSearch,
                onToggleWebSearch = {
                    if (canUseWebSearch) {
                        viewModel.toggleWebSearchMode(!isWebSearchEnabled)
                    } else {
                        viewModel.showSnackbar("当前模型无原生联网，请先在设置中配置外部搜索商")
                    }
                },
                isCodeExecutionEnabled = isCodeExecutionEnabled,
                onToggleCodeExecution = {
                    viewModel.toggleCodeExecutionEnabled()
                },
                onStopApiCall = {
                    viewModel.onCancelAPICall()
                    scrollStateManager.stopStreamingAndJumpToRealBottom()
                },
                focusRequester = focusRequester,
                selectedApiConfig = selectedApiConfig,
                onShowSnackbar = { viewModel.showSnackbar(it) },
                imeInsets = imeInsets,
                density = density,
                keyboardController = keyboardController,
                onFocusChange = {
                    scrollStateManager.jumpToBottom()
                },
                onSendMessage = { messageText, isFromRegeneration, attachments, audioBase64, mimeType ->
                    viewModel.onSendMessage(
                        messageText = messageText,
                        isFromRegeneration = isFromRegeneration,
                        attachments = attachments,
                        audioBase64 = audioBase64,
                        mimeType = mimeType
                    )
                },
                viewModel = viewModel,
                onShowVoiceInput = { navController.navigate(Screen.VOICE_INPUT_SCREEN) },
                onHeightChange = { height -> inputAreaHeightPx = height },
                // MCP 相关参数
                mcpServerStates = mcpServerStates,
                onAddMcpServer = { viewModel.addMcpServer(it) },
                onRemoveMcpServer = { viewModel.removeMcpServer(it) },
                onToggleMcpServer = { id, enabled -> viewModel.toggleMcpServer(id, enabled) }
            )
            }

            ScrollToBottomButton(
                scrollStateManager = scrollStateManager,
                bottomPadding = inputAreaHeightDp + 12.dp
            )

            // 浮动顶栏 - 覆盖在内容上方
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
                },
                onSystemPromptClick = {
                    viewModel.toggleSystemPromptExpanded()
                    viewModel.showSystemPromptDialog()
                },
                systemPrompt = systemPrompt,
                isSystemPromptExpanded = isSystemPromptExpanded,
                isSystemPromptEngaged = isSystemPromptEngaged,
                onToggleSystemPromptEngaged = { viewModel.toggleSystemPromptEngaged() },
                hasContent = messages.isNotEmpty(),
                onNewChat = { viewModel.startNewChat() },
                onShareChat = {
                    val idx = viewModel.loadedHistoryIndex.value
                    if (idx != null) viewModel.shareConversation(idx, false)
                },
                onPinChat = {
                    val idx = viewModel.loadedHistoryIndex.value
                    if (idx != null) viewModel.togglePinForConversation(idx, false)
                },
                onDeleteChat = {
                    val idx = viewModel.loadedHistoryIndex.value
                    if (idx != null) {
                        viewModel.deleteConversation(idx)
                        viewModel.startNewChat()
                    }
                },
                showModelSelection = showModelSelectionBottomSheet,
                modelList = filteredModelsForBottomSheet,
                selectedApiConfig = selectedApiConfig,
                onModelSelected = { modelConfig ->
                    viewModel.selectConfig(modelConfig)
                    showModelSelectionBottomSheet = false
                },
                onDismissModelSelection = { showModelSelectionBottomSheet = false },
                allApiConfigs = availableModels,
                onConfigModelSelected = { config ->
                    viewModel.selectConfig(config)
                },
                onTitleLongClick = {
                    if (selectedApiConfig != null) {
                        showEditConfigDialog = true
                    }
                },
                onControlsBottomChange = { bottom ->
                    if (topControlsBottomPx != bottom) {
                        topControlsBottomPx = bottom
                    }
                }
            )

            mcpUiStage?.takeIf { it.userVisibleText.isNotBlank() }?.let { stage ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 72.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    tonalElevation = 2.dp,
                ) {
                    Text(
                        text = stage.userVisibleText,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            AnimatedVisibility(
                visible = isHistoryLoadingOverlayVisible,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(animationSpec = tween(durationMillis = 90)),
                exit = fadeOut(animationSpec = tween(durationMillis = 120)),
            ) {
                HistoryConversationLoadingOverlay()
            }
        }

        if (showEditDialog) {
            EditMessageDialog(
                editDialogInputText = editDialogInputText,
                onDismissRequest = { viewModel.dismissEditDialog() },
                onEditDialogTextChanged = viewModel::onEditDialogTextChanged,
                onConfirmMessageEdit = { viewModel.confirmMessageEdit() }
            )
        }

        AnimatedWebSourcesDialog(
            visible = showSourcesDialog,
            sources = sourcesForDialog,
            topAvoidance = sourcesDialogTopAvoidance,
            bottomAvoidance = sourcesDialogBottomAvoidance,
            onDismissRequest = { viewModel.dismissSourcesDialog() }
        )

        if (showEditConfigDialog && selectedApiConfig != null) {
            com.android.everytalk.ui.screens.settings.EditConfigDialog(
                representativeConfig = selectedApiConfig!!,
                allProviders = availableModels.map { it.provider }.distinct(),
                onDismissRequest = { showEditConfigDialog = false },
                onConfirm = { newProvider, newAddress, newKey, newChannel, _, _ ->
                    viewModel.updateConfigGroup(
                        representativeConfig = selectedApiConfig!!,
                        newProvider = newProvider,
                        newAddress = newAddress,
                        newKey = newKey,
                        newChannel = newChannel
                    )
                    showEditConfigDialog = false
                }
            )
        }

        if (showAiMessageOptionsBottomSheet && selectedMessageForOptions != null) {
            AiMessageOptionsBottomSheet(
                onDismissRequest = { showAiMessageOptionsBottomSheet = false },
                sheetState = aiMessageOptionsBottomSheetState,
                onOptionSelected = { option ->
                    // 🔥 关键修复：从 ViewModel 获取最新的消息对象，而不是使用长按时捕获的可能已过期的快照
                    // 这解决了"刚生成的消息内容为空"的问题，因为长按时的 Message 对象可能尚未包含流式传输完成后的最终文本
                    val latestMessage = viewModel.getMessageById(selectedMessageForOptions!!.id) ?: selectedMessageForOptions!!
                    
                    when (option) {
                        AiMessageOption.COPY_FULL_TEXT -> viewModel.copyToClipboard(latestMessage.text)
                        AiMessageOption.REGENERATE -> {
                            keyboardController?.hide()
                            scrollStateManager.lockAutoScroll()
                            viewModel.regenerateAiResponse(latestMessage, scrollToNewMessage = true)
                        }
                        AiMessageOption.EXPORT_TEXT -> viewModel.exportMessageText(latestMessage.text)
                    }
                    coroutineScope.launch {
                        aiMessageOptionsBottomSheetState.hide()
                    }.invokeOnCompletion {
                        if (!aiMessageOptionsBottomSheetState.isVisible) {
                            showAiMessageOptionsBottomSheet = false
                        }
                    }
                }
            )
        }
    }
 
    val showAboutDialog by viewModel.showAboutDialog.collectAsState()

    if (showAboutDialog) {
        AboutDialog(
            viewModel = viewModel,
            onDismiss = { viewModel.dismissAboutDialog() }
        )
    }

    // 图片查看器
    val showImageViewer by viewModel.showImageViewer.collectAsState()
    val imageViewerUrls by viewModel.imageViewerUrls.collectAsState()
    val imageViewerIndex by viewModel.imageViewerIndex.collectAsState()

    if (showImageViewer && imageViewerUrls.isNotEmpty()) {
        ImagePreviewDialog(
            urls = imageViewerUrls,
            initialIndex = imageViewerIndex,
            onDismiss = { viewModel.dismissImageViewer() }
        )
    }

    if (latestReleaseInfo != null) {
        val uriHandler = LocalUriHandler.current
        com.android.everytalk.ui.screens.settings.dialogs.UpdateDialog(
            showDialog = true,
            latestVersion = latestReleaseInfo!!.tagName,
            changelog = latestReleaseInfo!!.body,
            force = (updateInfo?.isForceUpdate == true),
            onDismiss = { viewModel.clearUpdateInfo() },
            onUpdateNow = {
                uriHandler.openUri(latestReleaseInfo!!.htmlUrl)
                viewModel.clearUpdateInfo()
            },
            onRemindLater = { viewModel.clearUpdateInfo() }
        )
    }
   val showSystemPromptDialog by viewModel.showSystemPromptDialog.collectAsState()

   if (showSystemPromptDialog) {
       SystemPromptDialog(
           prompt = systemPrompt,
           isEngaged = isSystemPromptEngaged,
           onToggleEngaged = { viewModel.toggleSystemPromptEngaged() },
           onDismissRequest = { viewModel.dismissSystemPromptDialog() },
           onPromptChange = { newPrompt -> viewModel.onSystemPromptChange(newPrompt) },
           onConfirm = { viewModel.saveSystemPrompt() },
           onClear = { viewModel.clearSystemPrompt() }
       )
   }
}
 
@Composable
private fun AboutDialog(
    viewModel: AppViewModel,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val packageInfo = remember { context.packageManager.getPackageInfo(context.packageName, 0) }
    val versionName = packageInfo.versionName

    val dialogBg = appDialogContainerColor()
    val contentColor = appDialogContentColor()
    val cancelButtonColor = appDialogCancelColor()
    val confirmButtonColor = contentColor
    val confirmButtonTextColor = dialogBg

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
        shape = AppDialogShape,
        containerColor = dialogBg,
        titleContentColor = contentColor,
        textContentColor = contentColor,
        title = { Text("关于 EveryTalk") },
        text = {
            val annotatedString = buildAnnotatedString {
                append("版本: $versionName\n\n一个开源的、可高度定制的 AI 聊天客户端。\n\nGitHub: ")
                withLink(
                    LinkAnnotation.Url(
                        url = "https://github.com/roseforljh/KunTalkwithAi",
                        styles = TextLinkStyles(style = SpanStyle(color = Color(0xFF007eff)))
                    )
                ) {
                    append("EveryTalk")
                }
            }

            Text(
                text = annotatedString,
                style = MaterialTheme.typography.bodyMedium.copy(color = contentColor)
            )
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 关闭按钮：统一取消样式（红色描边）
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = dialogBg,
                        contentColor = cancelButtonColor
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, cancelButtonColor)
                ) {
                    Text(
                        text = "关闭",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }

                // 检查更新按钮：统一确认样式
                Button(
                    onClick = {
                        viewModel.checkForUpdates()
                        onDismiss()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = confirmButtonColor,
                        contentColor = confirmButtonTextColor,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Text(
                        text = "检查更新",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }
        },
        dismissButton = {}
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiMessageOptionsBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    onOptionSelected: (AiMessageOption) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceDim,
    ) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            AiMessageOption.values().forEach { option ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = option.title,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = option.icon,
                            contentDescription = option.title,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    modifier = Modifier.clickable { onOptionSelected(option) },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceDim,
                        headlineColor = MaterialTheme.colorScheme.onSurface,
                        leadingIconColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        }
    }
}

private enum class AiMessageOption(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    COPY_FULL_TEXT("复制全文", Icons.Filled.ContentCopy),
    REGENERATE("重新回答", Icons.Filled.Refresh),
    EXPORT_TEXT("导出文本", Icons.Filled.IosShare)
}
