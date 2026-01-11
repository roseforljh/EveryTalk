package com.android.everytalk.ui.screens.ImageGeneration

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.android.everytalk.navigation.Screen
import com.android.everytalk.statecontroller.AppViewModel
import com.android.everytalk.ui.components.AppTopBar
import com.android.everytalk.statecontroller.SimpleModeManager
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.ui.screens.MainScreen.chat.models.ModelSelectionBottomSheet
import com.android.everytalk.ui.screens.MainScreen.chat.text.state.rememberChatScrollStateManager
import com.android.everytalk.ui.screens.MainScreen.chat.dialog.EditMessageDialog
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
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = remember { androidx.compose.foundation.lazy.LazyListState() }
    val scrollStateManager = rememberChatScrollStateManager(listState, coroutineScope)
    val imageGenerationChatListItems by viewModel.imageGenerationChatListItems.collectAsState()
    // 当前图像会话ID：用于切换历史项时的过渡动画 key
    val currentImageConvId by viewModel.currentImageGenerationConversationId.collectAsState()
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val bubbleMaxWidth = remember(screenWidth) { screenWidth.coerceAtMost(600.dp) }

    // 统一振动反馈（与文本模式一致）
    val haptic = LocalHapticFeedback.current

    // 图像模式下的 AI 消息选项 BottomSheet（与文本模式一致的交互体验）
    var showImageMessageOptionsBottomSheet by remember { mutableStateOf(false) }
    var selectedMessageForOptions by remember { mutableStateOf<Message?>(null) }
    val imageMessageOptionsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    var showModelSelection by remember { mutableStateOf(false) }
    
    // 图像比例状态（使用全局StateHolder，便于下游请求读取）
    val selectedImageRatio by viewModel.stateHolder._selectedImageRatio.collectAsState()

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

    if (showModelSelection) {
        val allImageConfigs by viewModel.imageGenApiConfigs.collectAsState()
        val availableModels = remember(selectedApiConfig, allImageConfigs) {
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

        ModelSelectionBottomSheet(
            onDismissRequest = { showModelSelection = false },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(),
            availableModels = availableModels,
            onModelSelected = {
                viewModel.selectConfig(it, isImageGen = true)
                showModelSelection = false
            },
            selectedApiConfig = selectedApiConfig,
            allApiConfigs = viewModel.imageGenApiConfigs.collectAsState().value,
            onPlatformSelected = {
                viewModel.selectConfig(it, isImageGen = true)
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            AppTopBar(
                selectedConfigName = selectedApiConfig?.name?.takeIf { it.isNotBlank() }
                    ?: selectedApiConfig?.model ?: "选择配置",
                onMenuClick = { coroutineScope.launch { viewModel.drawerState.open() } },
                onSettingsClick = {
                    navController.navigate(Screen.IMAGE_GENERATION_SETTINGS_SCREEN)
                },
                onTitleClick = { showModelSelection = true },
                onSystemPromptClick = {},
                systemPrompt = "",
                isSystemPromptExpanded = false
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                // 移除 Crossfade 以避免编辑消息时因状态更新导致的列表重组和滚动丢失
                // 直接展示列表，确保 LazyColumn 状态稳定性
                ImageGenerationMessagesList(
                    chatItems = imageGenerationChatListItems,
                    viewModel = viewModel,
                    listState = listState,
                    scrollStateManager = scrollStateManager,
                    bubbleMaxWidth = bubbleMaxWidth,
                    onShowAiMessageOptions = { msg ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        selectedMessageForOptions = msg
                        showImageMessageOptionsBottomSheet = true
                    },
                    onImageLoaded = {
                        if (scrollStateManager.isAtBottom.value) {
                            scrollStateManager.jumpToBottom()
                        }
                    },
                )

                // 复用文本模式的“返回底部”按钮，悬浮于列表右下角
                ScrollToBottomButton(
                    scrollStateManager = scrollStateManager,
                    bottomPadding = 24.dp,
                    endPadding = 16.dp
                )
            }
            ImageGenerationInputArea(
                text = text,
                onTextChange = { viewModel.onTextChange(it) },
                onSendMessageRequest = { messageText, attachments ->
                    val initialCount = viewModel.imageGenerationChatListItems.value.size
                    viewModel.onSendMessage(
                        messageText = messageText,
                        attachments = attachments,
                        isImageGeneration = true
                    )
                    keyboardController?.hide()
                    coroutineScope.launch {
                        // Wait for list update (ensure new message is found)
                        var attempts = 0
                        var targetIndex = -1
                        while (attempts < 20) {
                            val items = viewModel.imageGenerationChatListItems.value
                            if (items.size > initialCount) {
                                targetIndex = items.indexOfLast { it is com.android.everytalk.ui.screens.MainScreen.chat.core.ChatListItem.UserMessage }
                                if (targetIndex != -1) break
                            }
                            kotlinx.coroutines.delay(50)
                            attempts++
                        }
                        
                        if (targetIndex != -1) {
                            scrollStateManager.scrollItemToTop(targetIndex)
                        } else {
                            // Fallback if not found
                            scrollStateManager.jumpToBottom()
                        }
                    }
                },
                selectedMediaItems = selectedMediaItems,
                onAddMediaItem = { viewModel.addMediaItem(it) },
                onRemoveMediaItemAtIndex = { viewModel.removeMediaItemAtIndex(it) },
                onClearMediaItems = { viewModel.clearMediaItems() },
                isApiCalling = isApiCalling,
                onStopApiCall = { viewModel.onCancelAPICall() },
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
                            imageVector = Icons.Outlined.Image,
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
                            imageVector = Icons.Outlined.Download,
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