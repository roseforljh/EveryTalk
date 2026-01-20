package com.android.everytalk.ui.screens.MainScreen

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.SelectAll
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.navigation.Screen
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.statecontroller.ConversationScrollState
import com.android.everytalk.statecontroller.SimpleModeManager
import com.android.everytalk.ui.components.AppTopBar
import com.android.everytalk.ui.components.ScrollToBottomButton
import com.android.everytalk.ui.components.WebSourcesDialog
import com.android.everytalk.ui.components.ImagePreviewDialog
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.ChatInputArea
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.ChatMessagesList
import com.android.everytalk.ui.components.content.LocalStickyHeaderTop
import com.android.everytalk.ui.screens.MainScreen.chat.dialog.EditMessageDialog
import com.android.everytalk.ui.screens.MainScreen.chat.dialog.SystemPromptDialog
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.EmptyChatView
import com.android.everytalk.ui.screens.MainScreen.chat.models.ModelSelectionBottomSheet
import com.android.everytalk.ui.screens.MainScreen.chat.text.state.rememberChatScrollStateManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay


@OptIn(ExperimentalMaterial3Api::class)
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
    val selectedMediaItems = viewModel.selectedMediaItems
    val isLoadingHistory by viewModel.isLoadingHistory.collectAsState()
    val mcpServerStates by viewModel.mcpServerStates.collectAsState()
    val isLoadingHistoryData by viewModel.isLoadingHistoryData.collectAsState()
    val conversationId by viewModel.currentConversationId.collectAsState()
    val latestReleaseInfo by viewModel.latestReleaseInfo.collectAsState()
    val updateInfo by viewModel.updateInfo.collectAsState()
    val systemPrompt by viewModel.systemPrompt.collectAsState()
    val isSystemPromptEngaged by viewModel.isSystemPromptEngaged.collectAsState()
    val isSystemPromptExpanded by remember(conversationId) {
        derivedStateOf {
            viewModel.systemPromptExpandedState[conversationId] ?: false
        }
    }
 
     val coroutineScope = rememberCoroutineScope()
     val loadedHistoryIndex by viewModel.loadedHistoryIndex.collectAsState()

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


    val scrollStateManager = rememberChatScrollStateManager(listState, coroutineScope)
    val isAtBottom by scrollStateManager.isAtBottom
    
    LaunchedEffect(scrollStateManager, currentStreamingAiMessageId) {
        val isCurrentlyStreaming = currentStreamingAiMessageId != null
        scrollStateManager.updateStreamingState(isCurrentlyStreaming)
    }



    DisposableEffect(conversationId, isAtBottom) {
        val idToSaveFor = conversationId
        onDispose {
            val stateToSave = ConversationScrollState(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                userScrolledAway = !isAtBottom,
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
    val chatListItems by viewModel.chatListItems.collectAsState()
    LaunchedEffect(scrollStateManager) {
        viewModel.scrollToItemEvent.collect { messageId ->
            // 收到滚动请求时，列表可能尚未更新（StateFlow更新有延迟），因此需要重试等待
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
            
            if (targetIndex != -1) {
                scrollStateManager.scrollItemToTop(targetIndex)
            } else {
                // 如果找不到目标消息（例如列表更新失败），回退到滚动到底部
                scrollStateManager.smoothScrollToBottom(isUserAction = true)
            }
        }
    }

    val focusRequester = remember { FocusRequester() }


    val bottomSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    var showModelSelectionBottomSheet by remember { mutableStateOf(false) }
    
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
    val showSelectableTextDialog by viewModel.showSelectableTextDialog.collectAsState()
    val textForSelectionDialog by viewModel.textForSelectionDialog.collectAsState()
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
    val inputAreaHeightDp = with(density) { inputAreaHeightPx.toDp() }

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

    if (showSelectableTextDialog) {
        SelectableTextDialog(
            textToDisplay = textForSelectionDialog,
            onDismissRequest = { viewModel.dismissSelectableTextDialog() }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        floatingActionButton = {
            ScrollToBottomButton(
                scrollStateManager = scrollStateManager
            )
        },
        floatingActionButtonPosition = FabPosition.End
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
                    isLoadingHistory -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    messages.isEmpty() -> {
                        EmptyChatView()
                    }
                    else -> {
                        val chatListItems by viewModel.chatListItems.collectAsState()

                        var stickyHeaderTopPx by remember { mutableFloatStateOf(0f) }
                        val contentPaddingTopPx = with(density) { 8.dp.toPx() }

                        CompositionLocalProvider(LocalStickyHeaderTop provides stickyHeaderTopPx) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .onGloballyPositioned { coordinates ->
                                        val y = coordinates.positionInWindow().y
                                        if (y.isFinite() && y > 0f) {
                                            stickyHeaderTopPx = y + contentPaddingTopPx
                                        }
                                    }
                            ) {
                                ChatMessagesList(
                                chatItems = chatListItems,
                                viewModel = viewModel,
                                listState = listState,
                                scrollStateManager = scrollStateManager,
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
                                    viewModel.showImageViewer(imageUrl)
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
                    // 立即锁定自动滚动，防止 onNewAiMessageAdded 触发的 jumpToBottom 覆盖后续的 scrollItemToTop
                    scrollStateManager.lockAutoScroll()
                    
                    val initialCount = viewModel.chatListItems.value.size
                    viewModel.onSendMessage(messageText = messageText, attachments = attachments, audioBase64 = null, mimeType = mimeType)
                    keyboardController?.hide()
                    coroutineScope.launch {
                        // 等待键盘关闭
                        snapshotFlow { imeInsets.getBottom(density) > 0 }
                            .filter { isVisible -> !isVisible }
                            .first()
                        
                        // 等待列表更新（确保找到了新发送的消息）
                        var attempts = 0
                        var targetIndex = -1
                        while (attempts < 20) {
                            val items = viewModel.chatListItems.value
                            if (items.size > initialCount) {
                                targetIndex = items.indexOfLast { it is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.UserMessage }
                                if (targetIndex != -1) break
                            }
                            delay(50)
                            attempts++
                        }
                        
                        // 将用户刚发送的消息滚动到顶部，以便用户能看到完整的消息内容（包括附件）以及下方正在生成的AI回复
                        if (targetIndex != -1) {
                            scrollStateManager.scrollItemToTop(targetIndex)
                        } else {
                            scrollStateManager.smoothScrollToBottom(isUserAction = true)
                        }
                    }
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
                isCodeExecutionEnabled = isCodeExecutionEnabled,
                onToggleCodeExecution = {
                    viewModel.toggleCodeExecutionEnabled()
                },
                onStopApiCall = { viewModel.onCancelAPICall() },
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
                onToggleSystemPromptEngaged = { viewModel.toggleSystemPromptEngaged() }
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
            AiMessageOptionsBottomSheet(
                onDismissRequest = { showAiMessageOptionsBottomSheet = false },
                sheetState = aiMessageOptionsBottomSheetState,
                onOptionSelected = { option ->
                    // 🔥 关键修复：从 ViewModel 获取最新的消息对象，而不是使用长按时捕获的可能已过期的快照
                    // 这解决了"刚生成的消息内容为空"的问题，因为长按时的 Message 对象可能尚未包含流式传输完成后的最终文本
                    val latestMessage = viewModel.getMessageById(selectedMessageForOptions!!.id) ?: selectedMessageForOptions!!
                    
                    when (option) {
                        AiMessageOption.SELECT_TEXT -> viewModel.showSelectableTextDialog(latestMessage.text)
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
    val imageViewerUrl by viewModel.imageViewerUrl.collectAsState()

    if (showImageViewer && imageViewerUrl != null) {
        ImagePreviewDialog(
            url = imageViewerUrl!!,
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

    val isDarkTheme = isSystemInDarkTheme()
    val cancelButtonColor = if (isDarkTheme) Color(0xFFFF5252) else Color(0xFFD32F2F)
    val confirmButtonColor = if (isDarkTheme) Color.White else Color(0xFF212121)
    val confirmButtonTextColor = if (isDarkTheme) Color.Black else Color.White

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(32.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text("关于 EveryTalk") },
        text = {
            val uriHandler = LocalUriHandler.current
            val annotatedString = buildAnnotatedString {
                append("版本: $versionName\n\n一个开源的、可高度定制的 AI 聊天客户端。\n\nGitHub: ")
                pushStringAnnotation(tag = "URL", annotation = "https://github.com/roseforljh/KunTalkwithAi")
                withStyle(style = SpanStyle(color = Color(0xFF007eff))) {
                    append("EveryTalk")
                }
                pop()
            }

            ClickableText(
                text = annotatedString,
                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                onClick = { offset ->
                    annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                        .firstOrNull()?.let { annotation ->
                            uriHandler.openUri(annotation.item)
                        }
                }
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
                        containerColor = MaterialTheme.colorScheme.surface,
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

 @Composable
 internal fun SelectableTextDialog(textToDisplay: String, onDismissRequest: () -> Unit) {
    // 强制重组：当 textToDisplay 变化时，确保内部状态更新
    val currentText by rememberUpdatedState(textToDisplay)
    
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = true,
            dismissOnBackPress = true,
            usePlatformDefaultWidth = false
        )
    ) {
        val alpha = remember { Animatable(0f) }
        val scale = remember { Animatable(0.8f) }

        LaunchedEffect(Unit) {
            launch {
                alpha.animateTo(1f, animationSpec = tween(durationMillis = 300))
            }
            launch {
                scale.animateTo(1f, animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing))
            }
        }

        // 计算固定高度，避免 wrapContent 和 fillMaxSize 的冲突
        // 或者是使用 fillMaxWidth + heightIn，但内部必须使用 weight 或 verticalScroll 来撑开
        // 之前的失败表明：Card(heightIn) -> Box(fillMaxSize) -> SelectionContainer(fillMaxSize) -> Column(verticalScroll)
        // 这种组合下，Column虽然可滚动，但如果没有足够内容撑开，或者父容器计算高度为0，就会空白。
        // 但 Text 是有内容的。
        // 可能是 SelectionContainer 的尺寸测量问题。
        
        // 尝试：不使用 fillMaxSize，而是让 Box/SelectionContainer 自适应内容高度，同时限制最大高度。
        // 但 Card 已经限制了 max height。
        
        // 另一种可能：Dialog 的 window 布局参数问题。
        
        // 让我们尝试最稳健的布局：
        // Card (fillMaxWidth, heightIn)
        //   Box (fillMaxSize)  <-- 关键：确保 Box 填满 Card
        //     SelectionContainer (fillMaxSize) <-- 关键：确保 SelectionContainer 填满 Box
        //       Box (fillMaxSize, verticalScroll) <-- 关键：滚动容器
        //         Text
        
        // 之前的代码似乎就是这样。为什么还是空白？
        // 也许是因为 alpha 动画初始值为 0？不，动画会执行到 1。
        
        // 让我们尝试移除 SelectionContainer 看看内容是否显示，以隔离问题。
        // 或者，给 Card 一个最小高度。
        
        Card(
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 24.dp)
                .heightIn(
                    min = 200.dp, // 给定一个最小高度，确保不为0
                    max = LocalConfiguration.current.screenHeightDp.dp * 0.75f
                )
                .graphicsLayer {
                    this.alpha = alpha.value
                    this.scaleX = scale.value
                    this.scaleY = scale.value
                },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            // 直接显示内容，移除顶部标题栏
            Box(
                modifier = Modifier
                    .fillMaxSize() // 填满 Card 的大小（由 min/max height 和内容共同决定）
                    .padding(12.dp)
            ) {
                val scrollState = rememberScrollState()
                val scrimHeight = 24.dp // 加大模糊效果高度
                val scrimColor = MaterialTheme.colorScheme.surface
                
                // 自定义文本选择颜色
                val customTextSelectionColors = TextSelectionColors(
                    handleColor = Color(0xFF2196F3), // 蓝色选择手柄
                    backgroundColor = Color(0xFF2196F3).copy(alpha = 0.3f) // 半透明蓝色背景
                )
                
                CompositionLocalProvider(LocalTextSelectionColors provides customTextSelectionColors) {
                    // 关键修改：SelectionContainer 包裹 Text，而 Scroll 在 SelectionContainer 外部（或内部，取决于需求）
                    // 为了让选择手柄跟随滚动，Scroll 应该在 SelectionContainer 内部。
                    // 但是，如果 Scroll 在 SelectionContainer 内部，SelectionContainer 需要有确定的大小。
                    
                    // 尝试：SelectionContainer (fillMaxSize) -> Column (verticalScroll) -> Text
                    // 这样 SelectionContainer 占据了 Box 的剩余空间（扣除 padding 和 scrim），
                    // 内部 Column 负责滚动。
                    
                    SelectionContainer(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = scrimHeight)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                        ) {
                             androidx.compose.material3.Text(
                                 text = currentText,
                                 style = MaterialTheme.typography.bodyLarge.copy(
                                     color = MaterialTheme.colorScheme.onSurface
                                 ),
                                 modifier = Modifier.fillMaxWidth()
                             )
                        }
                    }
                }
                
                // 上边框渐变模糊效果 - 加大高度和强度
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(scrimHeight)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    scrimColor,
                                    scrimColor.copy(alpha = 0.8f),
                                    scrimColor.copy(alpha = 0.4f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                
                // 下边框渐变模糊效果 - 加大高度和强度
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(scrimHeight)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    scrimColor.copy(alpha = 0.4f),
                                    scrimColor.copy(alpha = 0.8f),
                                    scrimColor
                                )
                            )
                        )
                )
            }
        }
    }
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
    SELECT_TEXT("选择文本", Icons.Outlined.SelectAll),
    COPY_FULL_TEXT("复制全文", Icons.Filled.ContentCopy),
    REGENERATE("重新回答", Icons.Filled.Refresh),
    EXPORT_TEXT("导出文本", Icons.Filled.IosShare)
}