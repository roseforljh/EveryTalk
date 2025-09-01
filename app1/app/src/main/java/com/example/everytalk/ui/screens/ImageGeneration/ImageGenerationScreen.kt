package com.example.everytalk.ui.screens.ImageGeneration

import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.everytalk.navigation.Screen
import com.example.everytalk.statecontroller.AppViewModel
import com.example.everytalk.ui.screens.MainScreen.chat.ModelSelectionBottomSheet
import com.example.everytalk.ui.screens.MainScreen.chat.rememberChatScrollStateManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageGenerationScreen(viewModel: AppViewModel, navController: NavController) {
    val selectedApiConfig by viewModel.selectedImageGenApiConfig.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val text by viewModel.text.collectAsState()
    val editingMessage by viewModel.editingMessage.collectAsState()
    val selectedMediaItems = viewModel.selectedMediaItems
    val isApiCalling by viewModel.isImageApiCalling.collectAsState()
    val shouldShowImageGenerationError by viewModel.shouldShowImageGenerationError.collectAsState()
    val imageGenerationError by viewModel.imageGenerationError.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = remember { androidx.compose.foundation.lazy.LazyListState() }
    val scrollStateManager = rememberChatScrollStateManager(listState, coroutineScope)
    val imageGenerationChatListItems by viewModel.imageGenerationChatListItems.collectAsState()
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val bubbleMaxWidth = remember(screenWidth) { screenWidth.coerceAtMost(600.dp) }

    var showModelSelection by remember { mutableStateOf(false) }

    // 图像生成错误提示对话框
    if (shouldShowImageGenerationError && !imageGenerationError.isNullOrBlank()) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { 
                viewModel.dismissImageGenerationErrorDialog()
            },
            title = { 
                androidx.compose.material3.Text("图像生成失败") 
            },
            text = { 
                androidx.compose.material3.Text(imageGenerationError.orEmpty()) 
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { 
                        viewModel.dismissImageGenerationErrorDialog()
                    }
                ) {
                    androidx.compose.material3.Text("确定")
                }
            }
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
        topBar = {
            ImageGenerationTopBar(
                selectedConfigName = selectedApiConfig?.name?.takeIf { it.isNotBlank() }
                    ?: selectedApiConfig?.model ?: "选择配置",
                onMenuClick = { coroutineScope.launch { viewModel.drawerState.open() } },
                onSettingsClick = {
                    navController.navigate(Screen.IMAGE_GENERATION_SETTINGS_SCREEN)
                },
                onTitleClick = {
                    showModelSelection = true
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                ImageGenerationMessagesList(
                    chatItems = imageGenerationChatListItems,
                    viewModel = viewModel,
                    listState = listState,
                    scrollStateManager = scrollStateManager,
                    bubbleMaxWidth = bubbleMaxWidth,
                    onShowAiMessageOptions = { /*TODO*/ },
                    onImageLoaded = {
                        if (!scrollStateManager.userInteracted) {
                            scrollStateManager.jumpToBottom()
                        }
                    },
                )
            }
            Box(modifier = Modifier.imePadding()) {
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
                    onCancelEdit = { viewModel.cancelEditing() }
                )
            }
        }
    }
}