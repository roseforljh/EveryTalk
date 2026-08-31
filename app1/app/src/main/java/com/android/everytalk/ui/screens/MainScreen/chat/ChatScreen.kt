package com.android.everytalk.ui.screens.MainScreen
import com.android.everytalk.statecontroller.*

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.android.everytalk.R
import com.android.everytalk.data.DataClass.Message
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
import com.android.everytalk.ui.components.image.buildImagePreviewSelection
import com.android.everytalk.ui.screens.MainScreen.chat.dialog.EditMessageDialog
import com.android.everytalk.ui.screens.MainScreen.chat.dialog.SystemPromptDialog
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.EmptyChatView
import com.android.everytalk.ui.screens.MainScreen.chat.models.ModelSelectionBottomSheet
import com.android.everytalk.ui.screens.MainScreen.chat.text.state.rememberChatScrollStateManager
import com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem
import com.android.everytalk.ui.screens.MainScreen.chat.core.PlaceholderRole
import com.android.everytalk.ui.screens.MainScreen.search.ConversationSearchContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    viewModel: AppViewModel,
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val messages: List<Message> = viewModel.messages
    val text by viewModel.text.collectAsState()
    val isConversationSearchActive by viewModel.isConversationSearchActive.collectAsState()
    val conversationSearchQuery by viewModel.conversationSearchQuery.collectAsState()
    val historicalConversations by viewModel.historicalConversations.collectAsState()

    // Dynamic config selection based on mode
    val uiMode by viewModel.uiModeFlow.collectAsState()
    val textConfig by viewModel.selectedApiConfig.collectAsState()
    val imageConfig by viewModel.selectedImageGenApiConfig.collectAsState()

    val selectedApiConfig by remember(uiMode, textConfig, imageConfig) {
        derivedStateOf {
            if (uiMode == SimpleModeManager.ModeType.IMAGE) imageConfig else textConfig
        }
    }

    val isRemoteCancellationPending by viewModel.isRemoteCancellationPending.collectAsState()
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
    val isMcpEnabled by viewModel.stateHolder._isMcpEnabledForNextRequest.collectAsState()
    val isLoadingHistoryData by viewModel.isLoadingHistoryData.collectAsState()
    val conversationId by viewModel.currentConversationId.collectAsState()
    val pendingHostCommand by viewModel.pendingComputerHostCommand.collectAsState()
    val pendingAgentEnableApproval by viewModel.pendingAgentEnableApproval.collectAsState()
    val pendingSkillSecretApproval by viewModel.pendingSkillSecretApproval.collectAsState()
    val currentHostCommand = pendingHostCommand?.takeIf { request ->
        request.context.conversationId == conversationId
    }
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
    var isHostCommandCardRendered by remember(conversationId) { mutableStateOf(false) }

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

    // 获取抽屉相关状态
    val isDrawerOpen = !viewModel.drawerState.isClosed
    val expandedDrawerItemIndex by viewModel.expandedDrawerItemIndex.collectAsState()

    // 处理返回键逻辑 - 优先处理抽屉相关操作，再处理页面导航
    BackHandler(enabled = isDrawerOpen && expandedDrawerItemIndex != null) {
        // 最高优先级：收起展开的历史项
        viewModel.setExpandedDrawerItemIndex(null)
    }

    BackHandler(enabled = isDrawerOpen && expandedDrawerItemIndex == null) {
        // 低优先级：关闭抽屉
        coroutineScope.launch {
            viewModel.drawerState.close()
        }
    }

    BackHandler(enabled = !isDrawerOpen && isConversationSearchActive) {
        viewModel.setConversationSearchActive(false)
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

    var initialListIndex by remember(scrollSessionKey) { mutableStateOf<Int?>(null) }
    var initialScrollHandled by remember(scrollSessionKey) { mutableStateOf(false) }
    var initialReadyToken by remember(scrollSessionKey) {
        mutableStateOf<Pair<Long, String?>?>(null)
    }
    val initialContentReady = initialListIndex != null
    val listState = remember(scrollSessionKey, initialListIndex) {
        LazyListState(firstVisibleItemIndex = initialListIndex ?: 0)
    }
    val scrollStateManager = rememberChatScrollStateManager(listState, coroutineScope)

    val suppressScrollButtonForHostCard =
        currentHostCommand != null ||
            isHostCommandCardRendered

    LaunchedEffect(scrollSessionKey, historyLoadGeneration) {
        initialListIndex = null
        initialReadyToken = null
        initialScrollHandled = false
        val effectGeneration = historyLoadGeneration
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
                laidOutItemCount = 0,
                requireLaidOutItemCount = false,
            )
            if (ready) currentGeneration to historyLoadingOverlayKeyState.value else null
        }
            .filterNotNull()
            .first()

        // 先让加载圆圈完整提交一帧，再挂载消息列表，避免两次重工作落在同一帧。
        withFrameNanos { }
        initialListIndex = chatListItemsState.value.lastIndex.coerceAtLeast(0)
        initialReadyToken = readyToken
    }

    LaunchedEffect(scrollSessionKey, listState, initialReadyToken) {
        val readyToken = initialReadyToken ?: return@LaunchedEffect
        if (chatListItemsState.value.isNotEmpty()) {
            scrollStateManager.pinToRealBottomUntilUserScroll()
            val layoutReadyGeneration = snapshotFlow {
                val currentGeneration = historyLoadGenerationState.value
                if (currentGeneration != readyToken.first) {
                    currentGeneration
                } else {
                    val currentChatItems = chatListItemsState.value
                    val expectedItemCount = currentChatItems.size
                    val layoutInfo = listState.layoutInfo
                    val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index
                    val layoutReady = isHistoryConversationReadyForInitialBottom(
                        currentConversationId = conversationIdState.value,
                        scrollSessionKey = scrollSessionKey,
                        isLoadingHistory = isLoadingHistoryState.value,
                        isLoadingHistoryData = isLoadingHistoryDataState.value,
                        requireMatchingScrollSession = readyToken.second != null ||
                            isHistoryLoadingOverlayVisibleState.value,
                        messages = messagesState.value,
                        chatItems = currentChatItems,
                        laidOutItemCount = layoutInfo.totalItemsCount,
                    ) &&
                        lastVisibleIndex == expectedItemCount - 1 &&
                        !listState.canScrollForward
                    if (layoutReady) currentGeneration else null
                }
            }
                .filterNotNull()
                .first()
            if (layoutReadyGeneration != readyToken.first) return@LaunchedEffect
        }

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

    val isInitialConversationLoading = shouldShowInitialConversationLoadingOverlay(
        isHistoryLoadingOverlayVisible = isHistoryLoadingOverlayVisible,
        initialScrollHandled = initialScrollHandled,
    )

    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val keyboardController = LocalSoftwareKeyboardController.current


    val isAtBottom by scrollStateManager.isAtBottom
    val isAtBottomState = rememberUpdatedState(isAtBottom)

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
                            userScrolledAway = !isAtBottomState.value,
                            firstBubbleScreenY = existing?.firstBubbleScreenY ?: -1
                        )
                    )
                }
            }
    }

    DisposableEffect(conversationId, listState) {
        val idToSaveFor = conversationId
        onDispose {
            val existing = viewModel.getScrollState(idToSaveFor)
            val stateToSave = ConversationScrollState(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                userScrolledAway = !isAtBottomState.value,
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
                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageSources -> it.messageId == messageId
                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMarkdownNode -> it.messageId == messageId
                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageReasoning -> it.message.id == messageId
                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageContentSegment -> it.sourceMessageId == messageId
                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessageProcessSegment -> it.messageId == messageId
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
    var modelParametersTarget by remember { mutableStateOf<com.android.everytalk.data.DataClass.ApiConfig?>(null) }

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
                    it.key == sel.key
                }
                if (filtered.isNotEmpty()) filtered else listOfNotNull(sel)
            } ?: availableModels
        }
    }





    val screenWidth = with(density) { windowSize.width.toDp() }
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
            if (isConversationSearchActive) {
                ConversationSearchContent(
                    query = conversationSearchQuery,
                    conversations = historicalConversations,
                    getConversationTitle = { index ->
                        viewModel.getConversationPreviewText(index, isImageGeneration = false)
                    },
                    onQueryChange = viewModel::onConversationSearchQueryChange,
                    onConversationClick = viewModel::loadConversationFromHistory,
                )
            } else {
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
                    !initialContentReady -> {
                        Box(modifier = Modifier.fillMaxSize())
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
                                    val messageSnapshot = messages.toList()
                                    coroutineScope.launch {
                                        val selection = withContext(Dispatchers.Default) {
                                            buildImagePreviewSelection(imageUrl, messageSnapshot)
                                        }
                                        viewModel.showImageViewer(
                                            urls = selection.candidates,
                                            index = selection.initialIndex,
                                        )
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
                onSendMessageRequest = { messageText, _, attachments, mimeType, contentParts ->
                    scrollStateManager.lockAutoScroll()
                    viewModel.onSendMessage(
                        messageText = messageText,
                        attachments = attachments,
                        audioBase64 = null,
                        mimeType = mimeType,
                        contentParts = contentParts,
                    )
                    keyboardController?.hide()
                },
                selectedMediaItems = selectedMediaItems,
                onAddMediaItem = { viewModel.addMediaItem(it) },
                onRemoveMediaItemAtIndex = { viewModel.removeMediaItemAtIndex(it) },
                onClearMediaItems = { viewModel.clearMediaItems() },
                isRemoteCancellationPending = isRemoteCancellationPending,
                isWebSearchEnabled = isWebSearchEnabled,
                isWebSearchAvailable = canUseWebSearch,
                onToggleWebSearch = {
                    if (canUseWebSearch) {
                        viewModel.toggleWebSearchMode(!isWebSearchEnabled)
                    } else {
                        viewModel.showSnackbar(context.getString(R.string.chat_native_search_unavailable))
                    }
                },
                isCodeExecutionEnabled = isCodeExecutionEnabled,
                onToggleCodeExecution = {
                    viewModel.toggleCodeExecutionEnabled()
                },
                onToggleStreamingPause = viewModel::toggleStreamingPause,
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
                hostCommandConfirmationRequest = currentHostCommand,
                agentEnableApprovalRequest = pendingAgentEnableApproval,
                skillSecretApprovalRequest = pendingSkillSecretApproval,
                onOpenComputerSettings = { navController.navigate(Screen.COMPUTER_SCREEN) },
                onHostCommandCardVisibilityChange = { isVisible ->
                    isHostCommandCardRendered = isVisible
                },
                // MCP 相关参数
                mcpServerStates = mcpServerStates,
                onAddMcpServer = { viewModel.addMcpServer(it) },
                onRemoveMcpServer = { viewModel.removeMcpServer(it) },
                onToggleMcpServer = { id, enabled -> viewModel.toggleMcpServer(id, enabled) }
            )
            }

            ScrollToBottomButton(
                scrollStateManager = scrollStateManager,
                bottomPadding = inputAreaHeightDp + 12.dp,
                suppressed = suppressScrollButtonForHostCard,
            )
            }

            // 浮动顶栏 - 覆盖在内容上方
            AppTopBar(
                selectedConfigName = selectedApiConfig?.name?.takeIf { it.isNotBlank() }
                    ?: selectedApiConfig?.model ?: stringResource(R.string.chat_select_configuration),
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
                            viewModel.showSnackbar(context.getString(R.string.chat_no_available_model_configuration))
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
                onModelLongClick = { modelConfig ->
                    modelParametersTarget = modelConfig
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

            AnimatedVisibility(
                visible = isInitialConversationLoading,
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

        modelParametersTarget?.let { target ->
            com.android.everytalk.ui.screens.settings.ModelParametersDialog(
                config = target,
                onDismissRequest = { modelParametersTarget = null },
                onConfirm = { updatedConfig ->
                    viewModel.updateConfig(
                        config = updatedConfig,
                        isImageGen = uiMode == SimpleModeManager.ModeType.IMAGE,
                    )
                    modelParametersTarget = null
                },
                onAutoLoad = { config -> viewModel.loadModelParameters(config) },
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
