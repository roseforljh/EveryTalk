package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.FileProvider
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.models.ImageSourceOption
import com.android.everytalk.models.MoreOptionsType
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.util.audio.AudioRecorderHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private fun createImageFileUri(context: Context): Uri {
    val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val imageFileName = "JPEG_${timeStamp}_"
    val storageDir: File? = File(context.filesDir, "chat_images_temp")
    if (storageDir != null && !storageDir.exists()) {
        storageDir.mkdirs()
    }
    val imageFile = File.createTempFile(imageFileName, ".jpg", storageDir)
    return FileProvider.getUriForFile(context, "${context.packageName}.provider", imageFile)
}

private suspend fun getFileDetailsFromUri(
    context: Context,
    uri: Uri
): Triple<String, String?, String?> {
    return withContext(Dispatchers.IO) {
        var displayName: String? = null
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        displayName = cursor.getString(nameIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FileDetails", "Error querying URI for display name: $uri", e)
        }
        if (displayName == null) {
            displayName = uri.lastPathSegment
        }
        var mimeType: String? = try {
            context.contentResolver.getType(uri)
        } catch (e: Exception) {
            Log.e("FileDetails", "Error getting MIME type for URI: $uri", e)
            null
        }
        if (mimeType == null && displayName != null) {
            val fileExtension = displayName!!.substringAfterLast('.', "").lowercase(Locale.getDefault())
            if (fileExtension.isNotEmpty()) {
                mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension)
            }
        }
        Triple(displayName ?: "Unknown File", mimeType, uri.toString())
    }
}

private suspend fun checkFileSizeAndShowError(
    context: Context,
    uri: Uri,
    fileName: String,
    onShowSnackbar: (String) -> Unit
): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            var fileSize = 0L
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1) {
                        fileSize = cursor.getLong(sizeIndex)
                    }
                }
            }
            
            // 如果无法从cursor获取大小，尝试通过输入流获取
            if (fileSize <= 0) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        fileSize = inputStream.available().toLong()
                    }
                } catch (e: Exception) {
                    Log.w("FileSizeCheck", "Failed to get file size from input stream", e)
                }
            }
            
            val maxFileSize = 50 * 1024 * 1024 // 50MB
            if (fileSize > maxFileSize) {
                val fileSizeFormatted = when {
                    fileSize < 1024 -> "${fileSize}B"
                    fileSize < 1024 * 1024 -> "${fileSize / 1024}KB"
                    fileSize < 1024 * 1024 * 1024 -> "${fileSize / (1024 * 1024)}MB"
                    else -> "${fileSize / (1024 * 1024 * 1024)}GB"
                }
                withContext(Dispatchers.Main) {
                    onShowSnackbar("文件 \"$fileName\" 过大 ($fileSizeFormatted)，最大支持50MB")
                }
                return@withContext false
            }
            return@withContext true
        } catch (e: Exception) {
            Log.e("FileSizeCheck", "Error checking file size for $fileName", e)
            withContext(Dispatchers.Main) {
                onShowSnackbar("无法检查文件大小，请选择较小的文件")
            }
            return@withContext false
        }
    }
}

private fun safeDeleteTempFile(context: Context, uri: Uri?) {
    uri?.let {
        try {
            context.contentResolver.delete(it, null, null)
        } catch (e: SecurityException) {
            Log.w("FileCleanup", "无法删除临时文件: $uri", e)
        } catch (e: Exception) {
            Log.e("FileCleanup", "删除临时文件时发生错误: $uri", e)
        }
    }
}



@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatInputArea(
    text: String,
    onTextChange: (String) -> Unit,
    onSendMessageRequest: (messageText: String, isKeyboardVisible: Boolean, attachments: List<SelectedMediaItem>, mimeType: String?) -> Unit,
    selectedMediaItems: List<SelectedMediaItem>,
    onAddMediaItem: (SelectedMediaItem) -> Unit,
    onRemoveMediaItemAtIndex: (Int) -> Unit,
    onClearMediaItems: () -> Unit,
    isApiCalling: Boolean,
    isWebSearchEnabled: Boolean,
    onToggleWebSearch: () -> Unit,
    isCodeExecutionEnabled: Boolean = false,
    onToggleCodeExecution: () -> Unit = {},
    onStopApiCall: () -> Unit,
    focusRequester: FocusRequester,
    selectedApiConfig: ApiConfig? = null,
    onShowSnackbar: (String) -> Unit,
    imeInsets: WindowInsets,
    density: Density,
    keyboardController: SoftwareKeyboardController? = null,
    onFocusChange: (isFocused: Boolean) -> Unit,
    onSendMessage: (messageText: String, isFromRegeneration: Boolean, attachments: List<SelectedMediaItem>, audioBase64: String?, mimeType: String?) -> Unit,
    viewModel: com.android.everytalk.statecontroller.AppViewModel,
    onShowVoiceInput: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isRecording by remember { mutableStateOf(false) }
    val audioRecorderHelper = remember { AudioRecorderHelper(context) }

    var pendingMessageTextForSend by remember { mutableStateOf<String?>(null) }
    var showImageSelectionPanel by remember { mutableStateOf(false) }
    var showMoreOptionsPanel by remember { mutableStateOf(false) }
    // 记录由外点关闭触发的时间戳，用于忽略紧随其后的按钮抬起点击，避免"先关后开"
    var lastImagePanelDismissAt by remember { mutableStateOf(0L) }
    var lastMorePanelDismissAt by remember { mutableStateOf(0L) }
    var showConversationParamsDialog by remember { mutableStateOf(false) }
    var tempCameraImageUri by remember { mutableStateOf<Uri?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                try {
                    uris.forEach { uri ->
                        val mimeType = context.contentResolver.getType(uri) ?: "image/*"
                        val fileName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (nameIndex != -1) cursor.getString(nameIndex) else null
                            } else null
                        } ?: "图片"

                        // 检查文件大小
                        val isFileSizeValid = checkFileSizeAndShowError(context, uri, fileName, onShowSnackbar)
                        if (isFileSizeValid) {
                            withContext(Dispatchers.Main) {
                                onAddMediaItem(SelectedMediaItem.ImageFromUri(uri, UUID.randomUUID().toString(), mimeType))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PhotoPicker", "处理选择的图片时发生错误", e)
                    withContext(Dispatchers.Main) {
                        onShowSnackbar("选择图片时发生错误")
                    }
                }
            }
        } else {
            Log.d("PhotoPicker", "用户取消了图片选择")
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val currentUri = tempCameraImageUri
        try {
            if (success && currentUri != null) {
                onAddMediaItem(SelectedMediaItem.ImageFromUri(currentUri, UUID.randomUUID().toString(), "image/jpeg"))
            } else {
                Log.w("CameraLauncher", "相机拍照失败或被取消")
                if (currentUri != null) {
                    safeDeleteTempFile(context, currentUri)
                }
            }
        } catch (e: Exception) {
            Log.e("CameraLauncher", "处理相机照片时发生错误", e)
            onShowSnackbar("拍照时发生错误")
            if (currentUri != null) {
                safeDeleteTempFile(context, currentUri)
            }
        } finally {
            tempCameraImageUri = null
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            try {
                val newUri = createImageFileUri(context)
                tempCameraImageUri = newUri
                cameraLauncher.launch(newUri)
            } catch (e: Exception) {
                Log.e("CameraPermission", "创建相机文件 URI 时发生错误", e)
                onShowSnackbar("启动相机时发生错误")
            }
        } else {
            Log.w("CameraPermission", "相机权限被拒绝")
            onShowSnackbar("需要相机权限才能拍照")
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                coroutineScope.launch {
                    try {
                        uris.forEach { uri ->
                            val (displayName, mimeType, _) = getFileDetailsFromUri(context, uri)
                            Log.d(
                                "OpenDocument",
                                "Selected Document: $displayName, URI: $uri, MIME: $mimeType"
                            )
                            
                            // 检查文件大小
                            val isFileSizeValid = checkFileSizeAndShowError(context, uri, displayName, onShowSnackbar)
                            if (isFileSizeValid) {
                                withContext(Dispatchers.Main) {
                                    onAddMediaItem(
                                        SelectedMediaItem.GenericFile(
                                            uri = uri,
                                            id = UUID.randomUUID().toString(),
                                            displayName = displayName,
                                            mimeType = mimeType ?: "*/*",
                                            filePath = null
                                        )
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("OpenDocument", "处理选择的文件时发生错误", e)
                        withContext(Dispatchers.Main) {
                            onShowSnackbar("处理文件时发生错误")
                        }
                    }
                }
            } else {
                Log.d("OpenDocument", "用户取消了文件选择")
            }
        }
    )

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            audioRecorderHelper.startRecording()
            isRecording = true
        } else {
            onShowSnackbar("需要录音权限才能录制音频")
        }
    }


    LaunchedEffect(Unit) {
        snapshotFlow { imeInsets.getBottom(density) > 0 }
            .distinctUntilChanged()
            .filter { isKeyboardVisible -> !isKeyboardVisible }
            .collect { _ ->
                pendingMessageTextForSend = null
            }
    }

    var chatInputContentHeightPx by remember { mutableIntStateOf(0) }

    val onToggleImagePanel = {
        if (showMoreOptionsPanel) showMoreOptionsPanel = false
        val now = android.os.SystemClock.uptimeMillis()
        if (!showImageSelectionPanel && now - lastImagePanelDismissAt < 200L) {
            // 忽略由外点关闭触发后紧随的按钮抬起 reopen
        } else {
            showImageSelectionPanel = !showImageSelectionPanel
        }
    }
    val onToggleMoreOptionsPanel = {
        if (showImageSelectionPanel) showImageSelectionPanel = false
        val now = android.os.SystemClock.uptimeMillis()
        if (!showMoreOptionsPanel && now - lastMorePanelDismissAt < 200L) {
            // 忽略由外点关闭触发后紧随的按钮抬起 reopen
        } else {
            showMoreOptionsPanel = !showMoreOptionsPanel
        }
    }

    val onClearContent = remember {
        {
            onTextChange("")
            onClearMediaItems()
            Unit
        }
    }

    val onSendClick =
        remember(isApiCalling, text, selectedMediaItems, selectedApiConfig, imeInsets, density) {
            {
                try {
                    if (isApiCalling) {
                        onStopApiCall()
                    } else if (text.isBlank() && selectedMediaItems.isEmpty()) {
                        onShowVoiceInput()
                    } else if (selectedApiConfig != null) {
                        val audioItem = selectedMediaItems.firstOrNull { it is SelectedMediaItem.Audio } as? SelectedMediaItem.Audio
                        val mimeType = audioItem?.mimeType
                        onSendMessageRequest(text, false, selectedMediaItems.toList(), mimeType)
                        onTextChange("")
                        onClearMediaItems()
                        
                        if (imeInsets.getBottom(density) > 0) {
                            keyboardController?.hide()
                        }
                    } else {
                        Log.w("SendMessage", "请先选择 API 配置")
                        onShowSnackbar("请先选择 API 配置")
                    }
                } catch (e: Exception) {
                    Log.e("SendMessage", "发送消息时发生错误", e)
                    onShowSnackbar("发送消息失败")
                }
                Unit
            }
        }

    Box(modifier = Modifier
        .fillMaxWidth()
        // 统一按 ime ∪ navigationBarsIgnoringVisibility 平滑上移，避免收起时回弹
        .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBarsIgnoringVisibility))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(1f) // 稍微加宽
                .align(Alignment.BottomCenter)
                // 与底部留更大空间（使用 start/end/bottom 以匹配重载）
                .padding(start = 6.dp, end = 6.dp, bottom = 10.dp)
                .background(
                    MaterialTheme.colorScheme.background
                )
                // 外层已统一处理 ime 与导航栏内边距
                .onSizeChanged { intSize -> chatInputContentHeightPx = intSize.height }
        ) {
            val borderColor = if (isSystemInDarkTheme()) Color.Gray.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.2f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isSystemInDarkTheme()) 1.dp else 0.5.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                borderColor,
                                borderColor,
                                Color.Transparent
                            ),
                            startX = 0f,
                            endX = Float.POSITIVE_INFINITY
                        )
                    )
            )
            Column(
                modifier = Modifier
                    // 略减整体高度：上下内边距更紧凑
                    .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 4.dp)
            ) {
// 使用优化的组件
                OptimizedMediaItemsList(
                    selectedMediaItems = selectedMediaItems,
                    onRemoveMediaItemAtIndex = onRemoveMediaItemAtIndex
                )

                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { focusState ->
                            // 获得焦点时滚动至底部，失去焦点时通知外层
                            if (focusState.isFocused) {
                                // 移除自动滚动到底部，避免打扰用户查看历史消息
                                // onFocusChange(true)
                            } else {
                                onFocusChange(false)
                            }
                        }
                        .padding(bottom = 4.dp),
                    placeholder = { Text("输入消息…") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                    ),
                    minLines = 1,
                    maxLines = 5,
                    shape = RoundedCornerShape(32.dp)
                )

                // 使用优化的控制按钮行组件
                // 增强 Gemini 渠道检测：要求渠道为 Gemini 且模型名称包含 Gemini
                val isGeminiChannel = selectedApiConfig?.let { config ->
                    config.channel.lowercase().contains("gemini") &&
                    config.model.lowercase().contains("gemini")
                } == true
                
                OptimizedControlButtonsRow(
                    isWebSearchEnabled = isWebSearchEnabled,
                    onToggleWebSearch = onToggleWebSearch,
                    isCodeExecutionEnabled = isCodeExecutionEnabled,
                    onToggleCodeExecution = onToggleCodeExecution,
                    showCodeExecutionToggle = isGeminiChannel,
                    onToggleImagePanel = onToggleImagePanel,
                    onToggleMoreOptionsPanel = onToggleMoreOptionsPanel,
                    showImageSelectionPanel = showImageSelectionPanel,
                    showMoreOptionsPanel = showMoreOptionsPanel,
                    text = text,
                    selectedMediaItems = selectedMediaItems,
                    onClearContent = onClearContent,
                    onSendClick = onSendClick,
                    isApiCalling = isApiCalling
                )
            }
            
            // 已由 Column 自身处理 navigationBars + ime 内边距，移除额外 spacer
        }

        val yOffsetPx = -chatInputContentHeightPx.toFloat() - with(density) { 8.dp.toPx() }

        // 带入场/退场动画的"相册"面板（使用渲染可见标志以支持退出动画）
        var renderImageSelectionPanel by remember { mutableStateOf(false) }
        val imageAlpha = remember { Animatable(0f) }
        val imageScale = remember { Animatable(0.8f) }

        LaunchedEffect(showImageSelectionPanel) {
            if (showImageSelectionPanel) {
                renderImageSelectionPanel = true
                launch { imageAlpha.animateTo(1f, animationSpec = tween(durationMillis = 150)) }
                launch { imageScale.animateTo(1f, animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)) }
            } else if (renderImageSelectionPanel) {
                // 退出动画
                launch { imageAlpha.animateTo(0f, animationSpec = tween(durationMillis = 140)) }
                launch { imageScale.animateTo(0.9f, animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)) }
                    .invokeOnCompletion { renderImageSelectionPanel = false }
            }
        }

        if (renderImageSelectionPanel) {
            // 计算相册按钮在控制栏中的实际位置
            // 左侧有: 网页搜索按钮(约70dp) + Spacer(8dp) + IconButton位置中心(24dp)
            val webSearchButtonWidth = 70.dp
            val spacerWidth = 8.dp
            val iconButtonSize = 48.dp
            val imageButtonCenterX = webSearchButtonWidth + spacerWidth + (iconButtonSize / 2)
            val panelWidthDp = 150.dp
            val xOffsetForPopup = imageButtonCenterX - (panelWidthDp / 2) + 45.dp // 向右偏移60dp微调
            val xOffsetPx = with(density) { xOffsetForPopup.toPx() }
            Popup(
                alignment = Alignment.BottomStart,
                offset = IntOffset(xOffsetPx.toInt(), yOffsetPx.toInt()),
                onDismissRequest = {
                    lastImagePanelDismissAt = android.os.SystemClock.uptimeMillis()
                    // 将"目标状态"置为关闭，触发退场动画，动画结束后再移除渲染
                    if (showImageSelectionPanel) showImageSelectionPanel = false
                },
                properties = PopupProperties(
                    // 非可聚焦以避免收起输入法
                    focusable = false,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                Box(modifier = Modifier.graphicsLayer {
                    this.alpha = imageAlpha.value
                    this.scaleX = imageScale.value
                    this.scaleY = imageScale.value
                    this.transformOrigin = TransformOrigin(0.5f, 1f)
                }) {
                    OptimizedImageSelectionPanel { selectedOption ->
                        // 点击选项后也触发优雅退场
                        if (showImageSelectionPanel) showImageSelectionPanel = false
                        when (selectedOption) {
                            ImageSourceOption.ALBUM -> photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                            ImageSourceOption.CAMERA -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                }
            }
        }

        // 带入场/退场动画的"更多"面板
        var renderMoreOptionsPanel by remember { mutableStateOf(false) }
        val moreAlpha = remember { Animatable(0f) }
        val moreScale = remember { Animatable(0.8f) }

        LaunchedEffect(showMoreOptionsPanel) {
            if (showMoreOptionsPanel) {
                renderMoreOptionsPanel = true
                launch { moreAlpha.animateTo(1f, animationSpec = tween(durationMillis = 150)) }
                launch { moreScale.animateTo(1f, animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)) }
            } else if (renderMoreOptionsPanel) {
                launch { moreAlpha.animateTo(0f, animationSpec = tween(durationMillis = 140)) }
                launch { moreScale.animateTo(0.9f, animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)) }
                    .invokeOnCompletion { renderMoreOptionsPanel = false }
            }
        }

        if (renderMoreOptionsPanel) {
            // 计算更多选项按钮在控制栏中的实际位置
            // 左侧有: 网页搜索按钮(约70dp) + Spacer(8dp) + 相册按钮(48dp) + Spacer(8dp) + IconButton位置中心(24dp)
            val webSearchButtonWidth = 70.dp
            val spacerWidth = 8.dp
            val iconButtonSize = 48.dp
            val tuneButtonCenterX = webSearchButtonWidth + spacerWidth + iconButtonSize + spacerWidth + (iconButtonSize / 2)
            val panelWidthDp = 150.dp
            val xOffsetForPopup = tuneButtonCenterX - (panelWidthDp / 2) + 30.dp // 向右偏移30dp微调
            val xOffsetForMoreOptionsPanelPx = with(density) { xOffsetForPopup.toPx() }

            Popup(
                alignment = Alignment.BottomStart,
                offset = IntOffset(xOffsetForMoreOptionsPanelPx.toInt(), yOffsetPx.toInt()),
                onDismissRequest = {
                    lastMorePanelDismissAt = android.os.SystemClock.uptimeMillis()
                    if (showMoreOptionsPanel) showMoreOptionsPanel = false
                },
                properties = PopupProperties(
                    // 非可聚焦以避免收起输入法
                    focusable = false,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                Box(modifier = Modifier.graphicsLayer {
                    this.alpha = moreAlpha.value
                    this.scaleX = moreScale.value
                    this.scaleY = moreScale.value
                    this.transformOrigin = TransformOrigin(0.5f, 1f)
                }) {
                    OptimizedMoreOptionsPanel { selectedOption ->
                        if (showMoreOptionsPanel) showMoreOptionsPanel = false
                        when (selectedOption) {
                            MoreOptionsType.CONVERSATION_PARAMS -> {
                                showConversationParamsDialog = true
                            }
                            else -> {
                                val mimeTypesArray = Array(selectedOption.mimeTypes.size) { index ->
                                    selectedOption.mimeTypes[index]
                                }
                                filePickerLauncher.launch(mimeTypesArray)
                            }
                        }
                    }
                }
            }
        }
    }

    // Conversation Parameters Dialog
    if (showConversationParamsDialog) {
        // Get current conversation parameters if they exist
        val currentParams = viewModel.getCurrentConversationParameters()
        
        com.android.everytalk.ui.screens.MainScreen.chat.dialog.ConversationParametersDialog(
            onDismissRequest = { showConversationParamsDialog = false },
            onConfirm = { temperature, topP, maxTokens ->
                // Save parameters to current conversation
                viewModel.updateConversationParameters(temperature, topP, maxTokens)
                showConversationParamsDialog = false
            },
            initialTemperature = currentParams?.temperature,
            initialTopP = currentParams?.topP,
            initialMaxTokens = currentParams?.maxOutputTokens
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            tempCameraImageUri?.let { uri ->
                safeDeleteTempFile(context, uri)
            }
        }
    }
}