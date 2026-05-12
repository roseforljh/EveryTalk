package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import kotlin.math.max
import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.android.everytalk.config.PerformanceConfig
import com.android.everytalk.data.mcp.McpServerState
import com.android.everytalk.data.mcp.McpServerConfig
import com.android.everytalk.ui.screens.mcp.McpServerListDialog
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
            val maxFileSize = 50 * 1024 * 1024 // 50MB
            var fileSize: Long? = null
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1) {
                        val sizeValue = cursor.getLong(sizeIndex)
                        if (sizeValue > 0) {
                            fileSize = sizeValue
                        }
                    }
                }
            }

            if (fileSize == null) {
                try {
                    val statSize = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                    if (statSize > 0) {
                        fileSize = statSize
                    }
                } catch (e: Exception) {
                    Log.w("FileSizeCheck", "Failed to get file size from file descriptor", e)
                }
            }

            if (fileSize == null) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val read = inputStream.read(buffer)
                            if (read == -1) break
                            total += read
                            if (total > maxFileSize) break
                        }
                        fileSize = total
                    }
                } catch (e: Exception) {
                    Log.w("FileSizeCheck", "Failed to get file size by streaming", e)
                }
            }

            val size = fileSize ?: 0L
            if (size > maxFileSize) {
                val fileSizeFormatted = when {
                    size < 1024 -> "${size}B"
                    size < 1024 * 1024 -> "${size / 1024}KB"
                    size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)}MB"
                    else -> "${size / (1024 * 1024 * 1024)}GB"
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

@Composable
private fun FunctionPanelContent(
    isWebSearchEnabled: Boolean,
    isWebSearchAvailable: Boolean,
    onToggleWebSearch: () -> Unit,
    isCodeExecutionEnabled: Boolean,
    onToggleCodeExecution: () -> Unit,
    isGeminiChannel: Boolean,
    onToggleImagePanel: () -> Unit,
    onToggleMoreOptionsPanel: () -> Unit,
    hasContent: Boolean,
    onClearContent: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(150.dp)
            .wrapContentHeight(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceDim,
        shadowElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            // 网页搜索
            FunctionPanelItem(
                icon = Icons.Filled.Language,
                label = webSearchToggleLabel(isWebSearchAvailable, isWebSearchEnabled),
                tint = if (isWebSearchEnabled && isWebSearchAvailable) com.android.everytalk.ui.theme.SeaBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                enabled = isWebSearchAvailable,
                onClick = { onToggleWebSearch() }
            )
            // 代码执行 (仅 Gemini)
            if (isGeminiChannel) {
                FunctionPanelItem(
                    icon = Icons.Filled.Code,
                    label = if (isCodeExecutionEnabled) "关闭执行" else "代码执行",
                    tint = if (isCodeExecutionEnabled) Color(0xFF9C27B0) else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { onToggleCodeExecution() }
                )
            }
            // 选择图片
            FunctionPanelItem(
                icon = Icons.Outlined.Image,
                label = "选择图片",
                tint = Color(0xff2cb334),
                onClick = {
                    onDismiss()
                    onToggleImagePanel()
                }
            )
            // 更多选项
            FunctionPanelItem(
                icon = Icons.Filled.Tune,
                label = "更多选项",
                tint = Color(0xfff76213),
                onClick = {
                    onDismiss()
                    onToggleMoreOptionsPanel()
                }
            )
            // 清除内容
            if (hasContent) {
                FunctionPanelItem(
                    icon = Icons.Filled.Clear,
                    label = "清除内容",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = {
                        onClearContent()
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun FunctionPanelItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    // 颜色渐变动画
    val animatedTint by animateColorAsState(
        targetValue = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "FunctionPanelItemTint"
    )

    // 点击缩放动画
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "FunctionPanelItemScale"
    )

    Surface(
        onClick = {
            if (!enabled) return@Surface
            isPressed = true
            onClick()
        },
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = animatedTint,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }

    // 重置按压状态
    LaunchedEffect(isPressed) {
        if (isPressed) {
            kotlinx.coroutines.delay(150)
            isPressed = false
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
    isWebSearchAvailable: Boolean,
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
    onShowVoiceInput: () -> Unit,
    onHeightChange: (Int) -> Unit = {},
    // MCP 相关参数
    mcpServerStates: Map<String, McpServerState> = emptyMap(),
    onAddMcpServer: (McpServerConfig) -> Unit = {},
    onRemoveMcpServer: (String) -> Unit = {},
    onToggleMcpServer: (String, Boolean) -> Unit = { _, _ -> }
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
    var showMcpServerListDialog by remember { mutableStateOf(false) }
    var tempCameraImageUri by remember { mutableStateOf<Uri?>(null) }
    val isMcpEnabled by viewModel.stateHolder._isMcpEnabledForNextRequest.collectAsState()

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
                                if (mimeType.startsWith("video/")) {
                                    onAddMediaItem(SelectedMediaItem.GenericFile(
                                        uri = uri,
                                        id = UUID.randomUUID().toString(),
                                        displayName = fileName,
                                        mimeType = mimeType,
                                        filePath = null
                                    ))
                                } else {
                                    onAddMediaItem(SelectedMediaItem.ImageFromUri(uri, UUID.randomUUID().toString(), mimeType))
                                }
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


    // 🎯 性能优化：使用本地状态管理输入文本，避免每次按键都触发 ViewModel 更新
    // 这样可以大幅减少 ChatScreen 的重组次数，解决长文本输入卡顿问题
    // 🔧 修复：使用 TextFieldValue 替代 String，以更好地兼容华为小艺输入法等 IME 的剪贴板粘贴行为
    var localTextFieldValue by remember {
        mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    }
    
    // 防抖同步 Job，用于取消上一次未完成的同步
    var syncJob by remember { mutableStateOf<Job?>(null) }
    
    // 当外部 text 变化时（如清空、恢复草稿），同步到本地状态
    // 使用 key 来区分外部变化和本地变化
    var lastExternalText by remember { mutableStateOf(text) }
    LaunchedEffect(text) {
        if (text != lastExternalText) {
            lastExternalText = text
            // 更新 TextFieldValue，保持光标在末尾
            localTextFieldValue = TextFieldValue(text, TextRange(text.length))
        }
    }
    
    // 防抖同步到 ViewModel（使用 PerformanceConfig 中定义的延迟）
    val localText = localTextFieldValue.text
    LaunchedEffect(localText) {
        // 取消上一次的同步任务
        syncJob?.cancel()
        syncJob = coroutineScope.launch {
            delay(PerformanceConfig.STATE_DEBOUNCE_DELAY_MS)
            if (localText != text) {
                onTextChange(localText)
                lastExternalText = localText
            }
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

    // 🎯 性能优化：发送时使用本地文本，确保发送最新内容
    val onSendClick =
        remember(isApiCalling, localText, selectedMediaItems, selectedApiConfig, imeInsets, density) {
            {
                try {
                    if (isApiCalling) {
                        onStopApiCall()
                    } else if (localText.isBlank() && selectedMediaItems.isEmpty()) {
                        onShowVoiceInput()
                    } else if (selectedApiConfig != null) {
                        val audioItem = selectedMediaItems.firstOrNull { it is SelectedMediaItem.Audio } as? SelectedMediaItem.Audio
                        val mimeType = audioItem?.mimeType
                        // 使用本地文本发送消息
                        onSendMessageRequest(localText, false, selectedMediaItems.toList(), mimeType)
                        // 同时清空本地状态和 ViewModel 状态
                        localTextFieldValue = TextFieldValue("", TextRange(0))
                        lastExternalText = ""
                        onTextChange("")
                        onClearMediaItems()
                        // 取消待处理的同步任务
                        syncJob?.cancel()
                        
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

    val inputBackgroundColor = MaterialTheme.colorScheme.background
    
    // 使用 WindowInsets 组合逻辑来统一处理底部间距，消除手动计算带来的动画抖动
    val navInsets = WindowInsets.navigationBarsIgnoringVisibility
    val baseInsets = navInsets.add(WindowInsets(bottom = 24.dp))
    val targetInsets = WindowInsets.ime.union(baseInsets)

    Box(modifier = Modifier
        .fillMaxWidth()
        .background(
            brush = Brush.verticalGradient(
                colors = listOf(
                    inputBackgroundColor.copy(alpha = 0f),
                    inputBackgroundColor
                )
            )
        )
        // 统一按 ime ∪ (navigationBars + 24dp) 处理，交由系统 Layout 阶段平滑过渡
        .onSizeChanged { intSize -> 
            chatInputContentHeightPx = intSize.height 
            onHeightChange(intSize.height)
        }
        .windowInsetsPadding(targetInsets)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(1f) // 稍微加宽
                .align(Alignment.BottomCenter)
                // 仅保留左右 padding，底部由外层 WindowInsets 统一控制
                .padding(start = 6.dp, end = 6.dp)
        ) {
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

                // 🎯 性能优化：使用本地状态驱动 TextField，避免每次按键触发 ViewModel 更新
                // 🔧 修复：使用 TextFieldValue 以更好地兼容各种 IME（包括华为小艺输入法）的剪贴板粘贴
                // 🎨 使用 BasicTextField 以完全控制内部 padding，实现更紧凑的 UI
                val hasContent = localText.isNotEmpty() || selectedMediaItems.isNotEmpty()
                val isDarkTheme = isSystemInDarkTheme()
                var isFocused by remember { mutableStateOf(false) }
                var showFunctionPanel by remember { mutableStateOf(false) }
                var lastFunctionPanelDismissAt by remember { mutableStateOf(0L) }

                // 功能面板动画状态
                var renderFunctionPanel by remember { mutableStateOf(false) }
                val functionPanelAlpha = remember { Animatable(0f) }
                val functionPanelScale = remember { Animatable(0.8f) }

                // 图片选择面板动画状态
                var renderImageSelectionPanel by remember { mutableStateOf(false) }
                val imageAlpha = remember { Animatable(0f) }
                val imageScale = remember { Animatable(0.8f) }

                // 更多选项面板动画状态
                var renderMoreOptionsPanel by remember { mutableStateOf(false) }
                val moreAlpha = remember { Animatable(0f) }
                val moreScale = remember { Animatable(0.8f) }

                LaunchedEffect(showFunctionPanel) {
                    if (showFunctionPanel) {
                        renderFunctionPanel = true
                        launch { functionPanelAlpha.animateTo(1f, animationSpec = tween(durationMillis = 150)) }
                        launch { functionPanelScale.animateTo(1f, animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)) }
                    } else if (renderFunctionPanel) {
                        launch { functionPanelAlpha.animateTo(0f, animationSpec = tween(durationMillis = 140)) }
                        launch { functionPanelScale.animateTo(0.8f, animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)) }
                            .invokeOnCompletion { renderFunctionPanel = false }
                    }
                }

                // 图片选择面板动画
                LaunchedEffect(showImageSelectionPanel) {
                    if (showImageSelectionPanel) {
                        renderImageSelectionPanel = true
                        launch { imageAlpha.animateTo(1f, animationSpec = tween(durationMillis = 150)) }
                        launch { imageScale.animateTo(1f, animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)) }
                    } else if (renderImageSelectionPanel) {
                        launch { imageAlpha.animateTo(0f, animationSpec = tween(durationMillis = 140)) }
                        launch { imageScale.animateTo(0.9f, animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)) }
                            .invokeOnCompletion { renderImageSelectionPanel = false }
                    }
                }

                // 更多选项面板动画
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

                val buttonBackgroundColor by animateColorAsState(
                    targetValue = if (isDarkTheme) Color(0xFFB3B3B3) else Color.Black,
                    animationSpec = tween(durationMillis = 200),
                    label = "SendButtonBackground"
                )
                val iconColor by animateColorAsState(
                    targetValue = if (isDarkTheme) Color.Black else Color.White,
                    animationSpec = tween(durationMillis = 200),
                    label = "SendButtonIcon"
                )

                // 增强 Gemini 渠道检测
                val isGeminiChannel = selectedApiConfig?.let { config ->
                    com.android.everytalk.data.network.WebSearchSupport.isGeminiNativeSearch(config)
                } == true
                val supportsNativeWebSearch = selectedApiConfig?.let { config ->
                    com.android.everytalk.data.network.WebSearchSupport.supportsNativeWebSearch(config)
                } == true
                val effectiveWebSearchAvailable = isWebSearchAvailable || supportsNativeWebSearch

                // 输入框 + 加号按钮在同一行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 加号按钮
                    Box {
                        val addButtonBackground = if (isDarkTheme) Color(0xFF3B3B3B) else Color(0xFFE8E8E8)
                        val addButtonBorderColor = if (isDarkTheme) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.15f)
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(addButtonBackground, CircleShape)
                                .border(1.dp, addButtonBorderColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(
                                onClick = {
                                    val now = android.os.SystemClock.uptimeMillis()
                                    // 防止长按松开后立即重新打开
                                    if (!showFunctionPanel && now - lastFunctionPanelDismissAt < 200L) {
                                        return@IconButton
                                    }
                                    showFunctionPanel = !showFunctionPanel
                                },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = if (showFunctionPanel) "收起功能面板" else "展开功能面板",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        // 功能按钮面板弹出（带动画）
                        if (renderFunctionPanel) {
                            Popup(
                                alignment = Alignment.BottomStart,
                                offset = IntOffset(0, with(density) { (-56).dp.toPx().toInt() }),
                                onDismissRequest = {
                                    lastFunctionPanelDismissAt = android.os.SystemClock.uptimeMillis()
                                    if (showFunctionPanel) showFunctionPanel = false
                                },
                                properties = PopupProperties(
                                    focusable = false,
                                    dismissOnBackPress = true,
                                    dismissOnClickOutside = true
                                )
                            ) {
                                Box(modifier = Modifier.graphicsLayer {
                                    alpha = functionPanelAlpha.value
                                    scaleX = functionPanelScale.value
                                    scaleY = functionPanelScale.value
                                    transformOrigin = TransformOrigin(0f, 1f)
                                }) {
                                    FunctionPanelContent(
                                        isWebSearchEnabled = isWebSearchEnabled,
                                        isWebSearchAvailable = effectiveWebSearchAvailable,
                                        onToggleWebSearch = onToggleWebSearch,
                                        isCodeExecutionEnabled = isCodeExecutionEnabled,
                                        onToggleCodeExecution = onToggleCodeExecution,
                                        isGeminiChannel = isGeminiChannel,
                                        onToggleImagePanel = onToggleImagePanel,
                                        onToggleMoreOptionsPanel = onToggleMoreOptionsPanel,
                                        hasContent = hasContent,
                                        onClearContent = {
                                            localTextFieldValue = TextFieldValue("", TextRange(0))
                                            lastExternalText = ""
                                            onTextChange("")
                                            onClearMediaItems()
                                            syncJob?.cancel()
                                        },
                                        onDismiss = {
                                            lastFunctionPanelDismissAt = android.os.SystemClock.uptimeMillis()
                                            showFunctionPanel = false
                                        }
                                    )
                                }
                            }
                        }

                        // 图片选择面板弹出（带动画）- 在加号按钮上方
                        if (renderImageSelectionPanel) {
                            Popup(
                                alignment = Alignment.BottomStart,
                                offset = IntOffset(0, with(density) { (-56).dp.toPx().toInt() }),
                                onDismissRequest = {
                                    lastImagePanelDismissAt = android.os.SystemClock.uptimeMillis()
                                    if (showImageSelectionPanel) showImageSelectionPanel = false
                                },
                                properties = PopupProperties(
                                    focusable = false,
                                    dismissOnBackPress = true,
                                    dismissOnClickOutside = true
                                )
                            ) {
                                Box(modifier = Modifier.graphicsLayer {
                                    alpha = imageAlpha.value
                                    scaleX = imageScale.value
                                    scaleY = imageScale.value
                                    transformOrigin = TransformOrigin(0f, 1f)
                                }) {
                                    OptimizedImageSelectionPanel { selectedOption ->
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

                        // 更多选项面板弹出（带动画）- 在加号按钮上方
                        if (renderMoreOptionsPanel) {
                            Popup(
                                alignment = Alignment.BottomStart,
                                offset = IntOffset(0, with(density) { (-56).dp.toPx().toInt() }),
                                onDismissRequest = {
                                    lastMorePanelDismissAt = android.os.SystemClock.uptimeMillis()
                                    if (showMoreOptionsPanel) showMoreOptionsPanel = false
                                },
                                properties = PopupProperties(
                                    focusable = false,
                                    dismissOnBackPress = true,
                                    dismissOnClickOutside = true
                                )
                            ) {
                                Box(modifier = Modifier.graphicsLayer {
                                    alpha = moreAlpha.value
                                    scaleX = moreScale.value
                                    scaleY = moreScale.value
                                    transformOrigin = TransformOrigin(0f, 1f)
                                }) {
                                    OptimizedMoreOptionsPanel(isMcpEnabled = isMcpEnabled) { selectedOption ->
                                        when (selectedOption) {
                                            MoreOptionsType.CONVERSATION_PARAMS -> {
                                                if (showMoreOptionsPanel) showMoreOptionsPanel = false
                                                showConversationParamsDialog = true
                                            }
                                            MoreOptionsType.MCP -> {
                                                viewModel.setMcpEnabledForNextRequest(!isMcpEnabled)
                                            }
                                            else -> {
                                                if (showMoreOptionsPanel) showMoreOptionsPanel = false
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

                    Spacer(Modifier.width(10.dp))

                    // 输入框
                    BasicTextField(
                        value = localTextFieldValue,
                        onValueChange = { newValue ->
                            localTextFieldValue = newValue
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .onFocusChanged { focusState ->
                                isFocused = focusState.isFocused
                                if (!focusState.isFocused) {
                                    onFocusChange(false)
                                }
                            },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 5,
                        decorationBox = { innerTextField ->
                            val inputBackground = if (isDarkTheme) Color(0xFF3B3B3B) else Color(0xFFE8E8E8)
                            val inputBorderColor = if (isDarkTheme) {
                                if (isFocused) Color.White.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.3f)
                            } else {
                                if (isFocused) Color.Black.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.15f)
                            }
                            // 根据文本行数动态选择形状：单行用圆形，多行用圆角矩形
                            val isMultiLine = localText.contains('\n') || localText.length > 40
                            val inputShape = if (isMultiLine) RoundedCornerShape(20.dp) else CircleShape
                            Row(
                                modifier = Modifier
                                    .background(inputBackground, inputShape)
                                    .border(
                                        width = 1.dp,
                                        color = inputBorderColor,
                                        shape = inputShape
                                    )
                                    .padding(start = 14.dp, end = 5.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    if (localText.isEmpty()) {
                                        Text(
                                            "输入消息...",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                    }
                                    innerTextField()
                                }
                                Spacer(Modifier.width(8.dp))
                                FilledIconButton(
                                    onClick = onSendClick,
                                    shape = CircleShape,
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = buttonBackgroundColor,
                                        contentColor = iconColor
                                    ),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = when {
                                            isApiCalling -> Icons.Filled.Stop
                                            hasContent -> Icons.Filled.KeyboardArrowUp
                                            else -> Icons.Filled.GraphicEq
                                        },
                                        contentDescription = when {
                                            isApiCalling -> "停止"
                                            hasContent -> "发送"
                                            else -> "语音输入"
                                        },
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    )
                }
            }
            
            // 已由 Column 自身处理 navigationBars + ime 内边距，移除额外 spacer
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

    // MCP Server List Dialog
    if (showMcpServerListDialog) {
        McpServerListDialog(
            serverStates = mcpServerStates,
            onAddServer = onAddMcpServer,
            onUpdateServer = onAddMcpServer,
            onRemoveServer = onRemoveMcpServer,
            onToggleServer = onToggleMcpServer,
            onDismiss = { showMcpServerListDialog = false }
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