package com.android.everytalk.ui.screens.ImageGeneration

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import com.android.everytalk.ui.screens.MainScreen.chat.ModelSelectionBottomSheet
import com.android.everytalk.ui.screens.MainScreen.chat.rememberChatScrollStateManager
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
    val editingMessage by viewModel.editingMessage.collectAsState()
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


    // 关于对话框 - 修复图像模式下的显示bug
    val showAboutDialog by viewModel.showAboutDialog.collectAsState()

    if (showAboutDialog) {
        AboutDialog(
            viewModel = viewModel,
            onDismiss = { viewModel.dismissAboutDialog() }
        )
    }

    if (showModelSelection) {
        val allImageConfigs by viewModel.imageGenApiConfigs.collectAsState()
        val availableModels = remember(selectedApiConfig, allImageConfigs) {
            val currentSelectedConfig = selectedApiConfig
            if (currentSelectedConfig != null) {
                allImageConfigs.filter { it.key == currentSelectedConfig.key }
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
                    val currentMode = viewModel.getCurrentMode()
                    val targetScreen = if (currentMode == SimpleModeManager.ModeType.IMAGE) {
                        Screen.IMAGE_GENERATION_SETTINGS_SCREEN
                    } else {
                        Screen.SETTINGS_SCREEN
                    }
                    navController.navigate(targetScreen)
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
                // 使用 Crossfade 在不同历史项之间平滑过渡（以会话ID为 key）
                Crossfade(
                    targetState = currentImageConvId,
                    animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
                    label = "ImageHistoryCrossfade"
                ) {
                    // 注意：列表数据仍从 imageGenerationChatListItems 读取；
                    // Crossfade 仅用会话ID作为切换触发点，避免闪烁与硬切换
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
                }

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
                    viewModel.onSendMessage(
                        messageText = messageText,
                        attachments = attachments,
                        isImageGeneration = true
                    )
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
                    scrollStateManager.jumpToBottom()
                },
                editingMessage = editingMessage,
                onCancelEdit = { viewModel.cancelEditing() },
                selectedImageRatio = selectedImageRatio,
                onImageRatioChanged = { viewModel.stateHolder._selectedImageRatio.value = it }
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
                    onDismiss()
                },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.height(52.dp).padding(horizontal = 4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            ) {
                Text("检查更新", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.height(52.dp).padding(horizontal = 4.dp)
            ) {
                Text("关闭", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
            }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface
    )
}