package com.example.everytalk.ui.screens.MainScreen

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender
import com.example.everytalk.navigation.Screen
import com.example.everytalk.statecontroller.AppViewModel
import com.example.everytalk.statecontroller.ConversationScrollState
import com.example.everytalk.statecontroller.SimpleModeManager
import com.example.everytalk.ui.components.AppTopBar
import com.example.everytalk.ui.components.ScrollToBottomButton
import com.example.everytalk.ui.components.WebSourcesDialog
import com.example.everytalk.ui.screens.MainScreen.chat.ChatInputArea
import com.example.everytalk.ui.screens.MainScreen.chat.ChatMessagesList
import com.example.everytalk.ui.screens.MainScreen.chat.EditMessageDialog
import com.example.everytalk.ui.screens.MainScreen.chat.SystemPromptDialog
import com.example.everytalk.ui.screens.MainScreen.chat.EmptyChatView
import com.example.everytalk.ui.screens.MainScreen.chat.ModelSelectionBottomSheet
import com.example.everytalk.ui.screens.MainScreen.chat.rememberChatScrollStateManager
import com.example.everytalk.ui.components.EnhancedMarkdownText
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
    val selectedApiConfig by viewModel.selectedApiConfig.collectAsState()
    val isApiCalling by viewModel.isTextApiCalling.collectAsState()
    val currentStreamingAiMessageId by viewModel.currentTextStreamingAiMessageId.collectAsState()
    val isWebSearchEnabled by viewModel.isWebSearchEnabled.collectAsState()
    val selectedMediaItems = viewModel.selectedMediaItems
    val isLoadingHistory by viewModel.isLoadingHistory.collectAsState()
    val isLoadingHistoryData by viewModel.isLoadingHistoryData.collectAsState()
    val conversationId by viewModel.currentConversationId.collectAsState()
    val latestReleaseInfo by viewModel.latestReleaseInfo.collectAsState()
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


    LaunchedEffect(isApiCalling) {
        if (isApiCalling) {
            scrollStateManager.onStreamingStarted()
        } else {
            // Add a small delay to allow the UI to settle before finishing streaming mode
            delay(100)
            scrollStateManager.onStreamingFinished()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.scrollToBottomEvent.collect {
            scrollStateManager.jumpToBottom()
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
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            AppTopBar(
                selectedConfigName = selectedApiConfig?.name?.takeIf { it.isNotBlank() }
                    ?: selectedApiConfig?.model ?: "选择配置",
                onMenuClick = { coroutineScope.launch { viewModel.drawerState.open() } },
                onSettingsClick = {
                    // 根据应用的当前模式状态决定跳转到哪个设置页面
                    // 使用更可靠的模式检测，基于uiModeFlow而不是消息内容
                    val currentMode = viewModel.getCurrentMode()
                    val targetScreen = if (currentMode == SimpleModeManager.ModeType.IMAGE) {
                        Screen.IMAGE_GENERATION_SETTINGS_SCREEN
                    } else {
                        Screen.SETTINGS_SCREEN
                    }
                    navController.navigate(targetScreen) {
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
        },
        floatingActionButton = {
            ScrollToBottomButton(
                scrollStateManager = scrollStateManager
            )
        },
        floatingActionButtonPosition = FabPosition.End
    ) { scaffoldPaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPaddingValues)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
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
                                if (!scrollStateManager.userInteracted) {
                                    scrollStateManager.jumpToBottom()
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
                },
                onSendMessageRequest = { messageText, _, attachments, mimeType ->
                    viewModel.onSendMessage(messageText = messageText, attachments = attachments, audioBase64 = null, mimeType = mimeType)
                    keyboardController?.hide()
                    coroutineScope.launch {
                        // 等待键盘关闭
                        snapshotFlow { imeInsets.getBottom(density) > 0 }
                            .filter { isVisible -> !isVisible }
                            .first()
                        // 滚动到底部
                        scrollStateManager.jumpToBottom()
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
                viewModel = viewModel
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
                    when (option) {
                        AiMessageOption.SELECT_TEXT -> viewModel.showSelectableTextDialog(selectedMessageForOptions!!.text)
                        AiMessageOption.COPY_FULL_TEXT -> viewModel.copyToClipboard(selectedMessageForOptions!!.text)
                        AiMessageOption.REGENERATE -> {
                            // 确保键盘隐藏，避免重新回答时弹出输入法
                            keyboardController?.hide()
                            viewModel.regenerateAiResponse(selectedMessageForOptions!!)
                            // 不立即滚动，让regenerateAiResponse内部的逻辑处理滚动
                        }
                        AiMessageOption.EXPORT_TEXT -> viewModel.exportMessageText(selectedMessageForOptions!!.text)
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

    if (latestReleaseInfo != null) {
        val uriHandler = LocalUriHandler.current
        UpdateAvailableDialog(
            releaseInfo = latestReleaseInfo!!,
            onDismiss = { viewModel.clearUpdateInfo() },
            onUpdate = {
                uriHandler.openUri(it)
                viewModel.clearUpdateInfo()
            }
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

    AlertDialog(
        onDismissRequest = onDismiss,
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
            Button(
                onClick = {
                    viewModel.checkForUpdates()
                    onDismiss() // Immediately close the about dialog
                },
                shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("检查更新")
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            ) {
                Text("关闭")
            }
        },
        shape = RoundedCornerShape(32.dp),
        containerColor = MaterialTheme.colorScheme.surface
    )
}

 @Composable
private fun UpdateAvailableDialog(
    releaseInfo: com.example.everytalk.data.DataClass.GithubRelease,
    onDismiss: () -> Unit,
    onUpdate: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本: ${releaseInfo.tagName}") },
        text = {
            Text("有新的更新可用。建议您更新到最新版本以获得最佳体验。\n\n更新日志:\n${releaseInfo.body}")
        },
        confirmButton = {
            Button(
                onClick = { onUpdate(releaseInfo.htmlUrl) },
                shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("立即更新")
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary
                )
            ) {
                Text("稍后")
            }
        },
        shape = RoundedCornerShape(32.dp),
        containerColor = MaterialTheme.colorScheme.surface
    )
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

        Card(
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 24.dp)
                .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.75f)
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
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                val scrollState = rememberScrollState()
                val scrimHeight = 32.dp // 加大模糊效果高度
                val scrimColor = MaterialTheme.colorScheme.surface
                
                // 自定义文本选择颜色
                val customTextSelectionColors = TextSelectionColors(
                    handleColor = Color(0xFF2196F3), // 蓝色选择手柄
                    backgroundColor = Color(0xFF2196F3).copy(alpha = 0.3f) // 半透明蓝色背景
                )
                
                CompositionLocalProvider(LocalTextSelectionColors provides customTextSelectionColors) {
                    SelectionContainer(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(vertical = scrimHeight)
                    ) {
                        // 🎯 显示AI输出的原始格式（保留Markdown标记），使文本可以被正确选择
                        androidx.compose.material3.Text(
                            text = textToDisplay,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
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