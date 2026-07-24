package com.android.everytalk.ui.screens.ImageGeneration
import com.android.everytalk.statecontroller.*

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.ui.res.painterResource
import com.android.everytalk.R
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.android.everytalk.navigation.Screen
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.statecontroller.ConversationScrollState
import com.android.everytalk.ui.components.AppTopBar
import com.android.everytalk.statecontroller.SimpleModeManager
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogCancelColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.screens.MainScreen.buildHistoryLoadingBubblePlaceholders
import com.android.everytalk.ui.screens.MainScreen.shouldHideHistoryLoadingSkeleton
import com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem
import com.android.everytalk.ui.screens.MainScreen.chat.text.state.rememberChatScrollStateManager
import com.android.everytalk.ui.screens.MainScreen.chat.text.ui.HistoryLoadingBubblePlaceholderItem
import com.android.everytalk.ui.screens.MainScreen.chat.dialog.EditMessageDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.android.everytalk.ui.components.ScrollToBottomButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageGenerationScreen(viewModel: AppViewModel, navController: NavController) {
    val selectedApiConfig by viewModel.selectedImageGenApiConfig.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val text by viewModel.text.collectAsState()
    val selectedMediaItems = viewModel.selectedMediaItems
    val isApiCalling by viewModel.isImageApiCalling.collectAsState()
    val isStreamingPaused by viewModel.isStreamingPaused.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    var inputAreaHeightPx by remember { mutableIntStateOf(0) }
    val inputAreaHeightDp = with(density) { inputAreaHeightPx.toDp() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val imageGenerationChatListItems by viewModel.imageGenerationChatListItems.collectAsState()
    // 当前图像会话ID：用于切换历史项时的过渡动画 key
    val currentImageConvId by viewModel.currentImageGenerationConversationId.collectAsState()
    val restoredScrollState = remember(currentImageConvId) {
        viewModel.getScrollState(currentImageConvId)?.takeIf { it.userScrolledAway }
    }
    val listState = remember(currentImageConvId) {
        androidx.compose.foundation.lazy.LazyListState(
            firstVisibleItemIndex = restoredScrollState?.firstVisibleItemIndex ?: 0,
            firstVisibleItemScrollOffset = restoredScrollState?.firstVisibleItemScrollOffset ?: 0,
        )
    }
    val scrollStateManager = rememberChatScrollStateManager(listState, coroutineScope)
    val screenWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val bubbleMaxWidth = remember(screenWidth) { screenWidth.coerceAtMost(600.dp) }

    // 统一振动反馈（与文本模式一致）
    val haptic = LocalHapticFeedback.current

    // 图像模式下的 AI 消息选项 BottomSheet（与文本模式一致的交互体验）
    var showImageMessageOptionsBottomSheet by remember { mutableStateOf(false) }
    var selectedMessageForOptions by remember { mutableStateOf<Message?>(null) }
    val imageMessageOptionsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    var showModelSelection by remember { mutableStateOf(false) }
    val allImageConfigs by viewModel.imageGenApiConfigs.collectAsState()
    val filteredModelsForDropdown = remember(selectedApiConfig, allImageConfigs) {
        val currentSelectedConfig = selectedApiConfig
        if (currentSelectedConfig != null) {
            allImageConfigs.filter {
                it.provider == currentSelectedConfig.provider &&
                it.address == currentSelectedConfig.address &&
                it.key == currentSelectedConfig.key &&
                it.channel == currentSelectedConfig.channel
            }
        } else {
            allImageConfigs
        }
    }
    val loadedImageHistoryIndex by viewModel.loadedImageGenerationHistoryIndex.collectAsState()
    val isLoadingHistory by viewModel.isLoadingHistory.collectAsState()
    var historySkeletonKey by remember { mutableStateOf<String?>(null) }
    var isHistorySkeletonReadyToReveal by remember { mutableStateOf(false) }
    var previousConversationIdForSkeleton by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isLoadingHistory, currentImageConvId) {
        if (isLoadingHistory) {
            historySkeletonKey = currentImageConvId.ifBlank { "image_history" }
            isHistorySkeletonReadyToReveal = false
        } else if (previousConversationIdForSkeleton != null &&
            previousConversationIdForSkeleton != currentImageConvId
        ) {
            historySkeletonKey = null
            isHistorySkeletonReadyToReveal = false
        }
        previousConversationIdForSkeleton = currentImageConvId
    }

    LaunchedEffect(isLoadingHistory, historySkeletonKey, imageGenerationChatListItems) {
        if (!isLoadingHistory && historySkeletonKey != null) {
            if (shouldHideHistoryLoadingSkeleton(imageGenerationChatListItems)) {
                isHistorySkeletonReadyToReveal = true
            }
        }
    }

    LaunchedEffect(historySkeletonKey) {
        if (historySkeletonKey != null) {
            delay(5_000L)
            if (historySkeletonKey != null) {
                historySkeletonKey = null
                isHistorySkeletonReadyToReveal = false
            }
        }
    }

    val historySkeletonRenderKey = historySkeletonKey ?: currentImageConvId.ifBlank { "image_history" }
    val shouldRenderHistorySkeletonItems = isLoadingHistory ||
        (historySkeletonKey != null && !isHistorySkeletonReadyToReveal)
    val shouldShowHistorySkeletonOverlay = historySkeletonKey != null && isHistorySkeletonReadyToReveal
    val isHistorySkeletonVisible = shouldRenderHistorySkeletonItems || shouldShowHistorySkeletonOverlay
    val isLoadingHistoryState = rememberUpdatedState(isLoadingHistory)
    val isHistorySkeletonVisibleState = rememberUpdatedState(isHistorySkeletonVisible)
    val isHistorySkeletonItemListVisibleState = rememberUpdatedState(shouldRenderHistorySkeletonItems)
    val historySkeletonKeyState = rememberUpdatedState(historySkeletonKey)
    val imageGenerationChatListItemsState = rememberUpdatedState(imageGenerationChatListItems)
    val visibleImageGenerationChatListItems = remember(
        shouldRenderHistorySkeletonItems,
        historySkeletonRenderKey,
        imageGenerationChatListItems
    ) {
        if (shouldRenderHistorySkeletonItems) {
            buildHistoryLoadingBubblePlaceholders(historySkeletonRenderKey)
        } else {
            imageGenerationChatListItems
        }
    }
    
    // 图像比例状态（使用全局StateHolder，便于下游请求读取）
    val selectedImageRatio by viewModel.stateHolder._selectedImageRatio.collectAsState()
    val gptImageQuality by viewModel.stateHolder._gptImageQuality.collectAsState()

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
    
    // 🔧 关键修复：处理从图像模式返回文本模式的导航
    // 当抽屉关闭时，返回手势应该正确切换到文本模式
    BackHandler(enabled = !isDrawerOpen) {
        // 切换到文本模式
        viewModel.simpleModeManager.setIntendedMode(SimpleModeManager.ModeType.TEXT)
        // 导航返回到文本聊天页面
        navController.popBackStack()
    }

    // 关于对话框 - 修复图像模式下的显示bug
    val showAboutDialog by viewModel.showAboutDialog.collectAsState()
    val showEditDialog by viewModel.showEditDialog.collectAsState()
    val editDialogInputText by viewModel.editDialogInputText.collectAsState()

    // 标记是否刚关闭编辑对话框，用于防止输入框重新获焦时自动滚动到底部
    var justClosedEditDialog by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        // 强制同步模式状态为 IMAGE
        viewModel.simpleModeManager.setIntendedMode(SimpleModeManager.ModeType.IMAGE)
    }

    LaunchedEffect(showEditDialog) {
        if (!showEditDialog) {
            justClosedEditDialog = true
            kotlinx.coroutines.delay(500)
            justClosedEditDialog = false
        }
    }

    // 监听滚动到指定消息的事件（重新回答时置顶）
    LaunchedEffect(scrollStateManager) {
        viewModel.scrollToItemEvent.collect { messageId ->
            // 收到滚动请求时，列表可能尚未更新（StateFlow更新有延迟），因此需要重试等待
            var attempts = 0
            var targetIndex = -1
            while (attempts < 20) {
                val currentItems = viewModel.imageGenerationChatListItems.value
                targetIndex = currentItems.indexOfFirst {
                    when (it) {
                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.UserMessage -> it.messageId == messageId
                        is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.AiMessage -> it.messageId == messageId
                        else -> false
                    }
                }

                if (targetIndex != -1) {
                    break
                }
                kotlinx.coroutines.delay(50)
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

    var initialScrollHandled by remember(currentImageConvId) { mutableStateOf(false) }

    LaunchedEffect(currentImageConvId, listState) {
        if (initialScrollHandled) return@LaunchedEffect

        snapshotFlow {
            val noSkeleton = historySkeletonKeyState.value == null
            if (noSkeleton) return@snapshotFlow true
            val realItemCount = imageGenerationChatListItemsState.value.size
            !isLoadingHistoryState.value &&
                !isHistorySkeletonItemListVisibleState.value &&
                realItemCount > 0 &&
                listState.layoutInfo.totalItemsCount >= realItemCount
        }
            .first { it }

        withFrameNanos { }
        val lastIndex = imageGenerationChatListItemsState.value.lastIndex
        if (lastIndex >= 0) {
            val savedState = restoredScrollState
            if (savedState != null) {
                listState.scrollToItem(
                    index = savedState.firstVisibleItemIndex.coerceIn(0, lastIndex),
                    scrollOffset = savedState.firstVisibleItemScrollOffset.coerceAtLeast(0),
                )
            } else {
                listState.scrollToItem(lastIndex)
                listState.scrollBy(Float.MAX_VALUE)
                scrollStateManager.pinToRealBottomUntilUserScroll()
            }
        }
        if (historySkeletonKeyState.value != null) {
            historySkeletonKey = null
            isHistorySkeletonReadyToReveal = false
        }
        initialScrollHandled = true
    }

    val isAtBottom by scrollStateManager.isAtBottom
    val isAtBottomState = rememberUpdatedState(isAtBottom)

    LaunchedEffect(currentImageConvId, listState) {
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress,
            )
        }
            .distinctUntilChanged()
            .filter { (_, _, isScrolling) ->
                !isScrolling && !isLoadingHistoryState.value && !isHistorySkeletonVisibleState.value
            }
            .collect { (index, offset, _) ->
                if (currentImageConvId.isNotBlank() && listState.layoutInfo.totalItemsCount > 0) {
                    val existing = viewModel.getScrollState(currentImageConvId)
                    viewModel.cacheScrollState(
                        currentImageConvId,
                        ConversationScrollState(
                            firstVisibleItemIndex = index,
                            firstVisibleItemScrollOffset = offset,
                            userScrolledAway = !isAtBottomState.value,
                            firstBubbleScreenY = existing?.firstBubbleScreenY ?: -1,
                        ),
                    )
                }
            }
    }

    DisposableEffect(currentImageConvId, listState) {
        val idToSaveFor = currentImageConvId
        onDispose {
            if (idToSaveFor.isNotBlank()) {
                val existing = viewModel.getScrollState(idToSaveFor)
                viewModel.saveScrollState(
                    idToSaveFor,
                    ConversationScrollState(
                        firstVisibleItemIndex = listState.firstVisibleItemIndex,
                        firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                        userScrolledAway = !isAtBottomState.value,
                        firstBubbleScreenY = existing?.firstBubbleScreenY ?: -1,
                    ),
                )
            }
        }
    }

    if (showAboutDialog) {
        AboutDialog(
            viewModel = viewModel,
            onDismiss = { viewModel.dismissAboutDialog() }
        )
    }
    
    if (showEditDialog) {
        EditMessageDialog(
            editDialogInputText = editDialogInputText,
            onDismissRequest = { viewModel.dismissEditDialog() },
            onEditDialogTextChanged = viewModel::onEditDialogTextChanged,
            onConfirmMessageEdit = { viewModel.confirmImageGenerationMessageEdit() }
        )
    }



    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            ImageGenerationMessagesList(
                chatItems = visibleImageGenerationChatListItems,
                viewModel = viewModel,
                listState = listState,
                scrollStateManager = scrollStateManager,
                scrollSessionKey = currentImageConvId,
                bubbleMaxWidth = bubbleMaxWidth,
                onShowAiMessageOptions = { msg ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    selectedMessageForOptions = msg
                    showImageMessageOptionsBottomSheet = true
                },
                onImageLoaded = {
                    if (!isApiCalling && scrollStateManager.isAtBottom.value) {
                        scrollStateManager.jumpToBottom()
                    }
                },
                additionalBottomPadding = inputAreaHeightDp
            )

            if (shouldShowHistorySkeletonOverlay) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(
                            start = 6.dp,
                            top = 8.dp,
                            end = 16.dp,
                            bottom = inputAreaHeightDp + 12.dp,
                        ),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        buildHistoryLoadingBubblePlaceholders(historySkeletonRenderKey)
                            .forEach { placeholder ->
                                if (placeholder is ChatListItem.LoadingBubblePlaceholder) {
                                    HistoryLoadingBubblePlaceholderItem(
                                        role = placeholder.role,
                                        widthFraction = placeholder.widthFraction,
                                        estimatedHeight = placeholder.estimatedHeightDp.dp,
                                    )
                                }
                            }
                    }
                }
            }

            ScrollToBottomButton(
                scrollStateManager = scrollStateManager,
                bottomPadding = inputAreaHeightDp + 12.dp,
                endPadding = 16.dp
            )

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter
            ) {
                ImageGenerationInputArea(
                    text = text,
                    onTextChange = { viewModel.onTextChange(it) },
                    onSendMessageRequest = { messageText, attachments ->
                        viewModel.onSendMessage(
                            messageText = messageText,
                            attachments = attachments,
                            isImageGeneration = true
                        )
                        keyboardController?.hide()
                    },
                    selectedMediaItems = selectedMediaItems,
                    onAddMediaItem = { viewModel.addMediaItem(it) },
                    onRemoveMediaItemAtIndex = { viewModel.removeMediaItemAtIndex(it) },
                    onClearMediaItems = { viewModel.clearMediaItems() },
                    isApiCalling = isApiCalling,
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
                        if (!justClosedEditDialog) {
                            scrollStateManager.jumpToBottom()
                        }
                    },
                    selectedImageRatio = selectedImageRatio,
                    onImageRatioChanged = { viewModel.stateHolder._selectedImageRatio.value = it },
                    currentImageSteps = selectedApiConfig?.numInferenceSteps,
                    onChangeImageSteps = { steps ->
                        viewModel.updateImageNumInferenceStepsForSelectedConfig(steps)
                    },
                    currentImageGuidance = selectedApiConfig?.guidanceScale,
                    onChangeImageParams = { steps, guidance ->
                        viewModel.updateImageGenerationParamsForSelectedConfig(steps, guidance)
                    },
                    onGeminiImageSizeChanged = { size ->
                        viewModel.updateGeminiImageSizeForSelectedConfig(size)
                    },
                    currentGptImageQuality = gptImageQuality,
                    onGptImageQualityChanged = { viewModel.stateHolder._gptImageQuality.value = it },
                    onHeightChange = { height -> inputAreaHeightPx = height },
                    onShowVoiceInput = { navController.navigate(Screen.VOICE_INPUT_SCREEN) }
                )
            }

            AppTopBar(
                selectedConfigName = selectedApiConfig?.name?.takeIf { it.isNotBlank() }
                    ?: selectedApiConfig?.model ?: "选择配置",
                onMenuClick = { coroutineScope.launch { viewModel.drawerState.open() } },
                onSettingsClick = {
                    navController.navigate(Screen.IMAGE_GENERATION_SETTINGS_SCREEN)
                },
                onTitleClick = { showModelSelection = !showModelSelection },
                onSystemPromptClick = {},
                systemPrompt = "",
                isSystemPromptExpanded = false,
                hasContent = imageGenerationChatListItems.isNotEmpty(),
                onNewChat = { viewModel.startNewImageGeneration() },
                onShareChat = {
                    val idx = loadedImageHistoryIndex
                    if (idx != null) viewModel.shareConversation(idx, true)
                },
                onPinChat = {
                    val idx = loadedImageHistoryIndex
                    if (idx != null) viewModel.togglePinForConversation(idx, true)
                },
                onDeleteChat = {
                    val idx = loadedImageHistoryIndex
                    if (idx != null) {
                        viewModel.deleteImageGenerationConversation(idx)
                        viewModel.startNewImageGeneration()
                    }
                },
                showModelSelection = showModelSelection,
                modelList = filteredModelsForDropdown,
                selectedApiConfig = selectedApiConfig,
                onModelSelected = { modelConfig ->
                    viewModel.selectConfig(modelConfig, isImageGen = true)
                    showModelSelection = false
                },
                onDismissModelSelection = { showModelSelection = false },
                allApiConfigs = allImageConfigs,
                onConfigModelSelected = { config ->
                    viewModel.selectConfig(config, isImageGen = true)
                }
            )
        }
    }

    // 图像模式下的 AI 消息选项 BottomSheet：查看图片 / 下载图片（与文本模式交互一致）
    if (showImageMessageOptionsBottomSheet && selectedMessageForOptions != null) {
        ModalBottomSheet(
            onDismissRequest = { showImageMessageOptionsBottomSheet = false },
            sheetState = imageMessageOptionsSheetState,
            containerColor = MaterialTheme.colorScheme.surfaceDim,
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                // 查看图片
                ListItem(
                    headlineContent = { Text("查看图片", color = MaterialTheme.colorScheme.onSurface) },
                    leadingContent = {
                        Icon(
                            painter = painterResource(R.drawable.ic_image_gallery),
                            contentDescription = "查看图片",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            // 这里可扩展为内置预览对话框；先保持简单提示
                            viewModel.showSnackbar("即将支持图片预览")
                            coroutineScope.launch {
                                imageMessageOptionsSheetState.hide()
                            }.invokeOnCompletion {
                                if (!imageMessageOptionsSheetState.isVisible) {
                                    showImageMessageOptionsBottomSheet = false
                                }
                            }
                        },
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceDim,
                        headlineColor = MaterialTheme.colorScheme.onSurface,
                        leadingIconColor = MaterialTheme.colorScheme.onSurface
                    )
                )
                // 下载图片
                ListItem(
                    headlineContent = { Text("下载图片", color = MaterialTheme.colorScheme.onSurface) },
                    leadingContent = {
                        Icon(
                            painter = painterResource(R.drawable.ic_download),
                            contentDescription = "下载图片",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedMessageForOptions?.let { viewModel.downloadImageFromMessage(it) }
                            coroutineScope.launch {
                                imageMessageOptionsSheetState.hide()
                            }.invokeOnCompletion {
                                if (!imageMessageOptionsSheetState.isVisible) {
                                    showImageMessageOptionsBottomSheet = false
                                }
                            }
                        },
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

@Composable
private fun AboutDialog(
    viewModel: AppViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
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
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
            ) {
                // 关闭按钮：统一红色描边取消样式
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
