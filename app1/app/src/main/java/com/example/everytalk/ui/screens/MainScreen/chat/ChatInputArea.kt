package com.example.everytalk.ui.screens.MainScreen.chat

import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.everytalk.ui.theme.SeaBlue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.example.everytalk.data.DataClass.ApiConfig
import com.example.everytalk.models.ImageSourceOption
import com.example.everytalk.models.MoreOptionsType
import com.example.everytalk.models.SelectedMediaItem
import com.example.everytalk.util.AudioRecorderHelper
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

@Composable
fun ImageSelectionPanel(
    modifier: Modifier = Modifier,
    onOptionSelected: (ImageSourceOption) -> Unit
) {
    var activeOption by remember { mutableStateOf<ImageSourceOption?>(null) }
    val panelBackgroundColor = MaterialTheme.colorScheme.surfaceDim
    val darkerBackgroundColor = MaterialTheme.colorScheme.surfaceVariant

    Surface(
        modifier = modifier
            .width(150.dp)
            .wrapContentHeight(),
        shape = RoundedCornerShape(20.dp),
        color = panelBackgroundColor
    ) {
        Column {
            ImageSourceOption.values().forEach { option ->
                val isSelected = activeOption == option
                val animatedBackgroundColor by animateColorAsState(
                    targetValue = if (isSelected) darkerBackgroundColor else panelBackgroundColor,
                    animationSpec = tween(durationMillis = 200),
                    label = "ImageOptionPanelItemBackground"
                )
                val onClickCallback = remember(option) {
                    {
                        activeOption = option
                        onOptionSelected(option)
                        Unit
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onClickCallback)
                        .background(animatedBackgroundColor)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = option.icon,
                        contentDescription = option.label,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(text = option.label, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
fun MoreOptionsPanel(
    modifier: Modifier = Modifier,
    onOptionSelected: (MoreOptionsType) -> Unit
) {
    var activeOption by remember { mutableStateOf<MoreOptionsType?>(null) }
    val panelBackgroundColor = MaterialTheme.colorScheme.surfaceDim
    val darkerBackgroundColor = MaterialTheme.colorScheme.surfaceVariant

    Surface(
        modifier = modifier
            .width(150.dp)
            .wrapContentHeight(),
        shape = RoundedCornerShape(20.dp),
        color = panelBackgroundColor
    ) {
        Column {
            MoreOptionsType.values().forEach { option ->
                val isSelected = activeOption == option
                val animatedBackgroundColor by animateColorAsState(
                    targetValue = if (isSelected) darkerBackgroundColor else panelBackgroundColor,
                    animationSpec = tween(durationMillis = 200),
                    label = "MoreOptionPanelItemBackground"
                )
                val onClickCallback = remember(option) {
                    {
                        activeOption = option
                        onOptionSelected(option)
                        Unit
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onClickCallback)
                        .background(animatedBackgroundColor)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = option.icon,
                        contentDescription = option.label,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(text = option.label, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
fun SelectedItemPreview(
    mediaItem: SelectedMediaItem,
    onRemoveClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .size(width = 100.dp, height = 80.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        when (mediaItem) {
            is SelectedMediaItem.ImageFromUri -> AsyncImage(
                model = mediaItem.uri,
                contentDescription = "Selected image from gallery",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            is SelectedMediaItem.ImageFromBitmap -> AsyncImage(
                model = mediaItem.bitmap,
                contentDescription = "Selected image from camera",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            is SelectedMediaItem.GenericFile -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    val icon = when (mediaItem.mimeType) {
                        "application/pdf" -> Icons.Outlined.PictureAsPdf
                        "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> Icons.Outlined.Description
                        "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> Icons.Outlined.TableChart
                        "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> Icons.Outlined.Slideshow
                        "application/zip", "application/x-rar-compressed" -> Icons.Outlined.Archive
                        else -> when {
                            mediaItem.mimeType?.startsWith("video/") == true -> Icons.Outlined.Videocam
                            mediaItem.mimeType?.startsWith("audio/") == true -> Icons.Outlined.Audiotrack
                            mediaItem.mimeType?.startsWith("image/") == true -> Icons.Outlined.Image
                            else -> Icons.Outlined.AttachFile
                        }
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = mediaItem.displayName,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = mediaItem.displayName,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            is SelectedMediaItem.Audio -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Audiotrack,
                        contentDescription = "Audio file",
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Audio",
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        IconButton(
            onClick = onRemoveClicked,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(20.dp)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f), CircleShape)
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove item",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier =
    composed {
        this.then(
            Modifier.clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }) {
                onClick()
            }
        )
    }

@OptIn(ExperimentalMaterial3Api::class)
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
    onStopApiCall: () -> Unit,
    focusRequester: FocusRequester,
    selectedApiConfig: ApiConfig?,
    onShowSnackbar: (String) -> Unit,
    imeInsets: WindowInsets,
    density: Density,
    keyboardController: SoftwareKeyboardController?,
    onFocusChange: (isFocused: Boolean) -> Unit,
    onSendMessage: (messageText: String, isFromRegeneration: Boolean, attachments: List<SelectedMediaItem>, audioBase64: String?, mimeType: String?) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isRecording by remember { mutableStateOf(false) }
    val audioRecorderHelper = remember { AudioRecorderHelper(context) }

    var pendingMessageTextForSend by remember { mutableStateOf<String?>(null) }
    var showImageSelectionPanel by remember { mutableStateOf(false) }
    var showMoreOptionsPanel by remember { mutableStateOf(false) }
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
        showImageSelectionPanel = !showImageSelectionPanel
    }
    val onToggleMoreOptionsPanel = {
        if (showImageSelectionPanel) showImageSelectionPanel = false
        showMoreOptionsPanel = !showMoreOptionsPanel
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
                    } else if ((text.isNotBlank() || selectedMediaItems.isNotEmpty()) && selectedApiConfig != null) {
                        val audioItem = selectedMediaItems.firstOrNull { it is SelectedMediaItem.Audio } as? SelectedMediaItem.Audio
                        val mimeType = audioItem?.mimeType
                        onSendMessageRequest(text, false, selectedMediaItems.toList(), mimeType)
                        onTextChange("")
                        onClearMediaItems()
                        
                        if (imeInsets.getBottom(density) > 0) {
                            keyboardController?.hide()
                        }
                    } else if (selectedApiConfig == null) {
                        Log.w("SendMessage", "请先选择 API 配置")
                        onShowSnackbar("请先选择 API 配置")
                    } else {
                        Log.w("SendMessage", "请输入消息内容或选择项目")
                        onShowSnackbar("请输入消息内容或选择项目")
                    }
                } catch (e: Exception) {
                    Log.e("SendMessage", "发送消息时发生错误", e)
                    onShowSnackbar("发送消息失败")
                }
                Unit
            }
        }

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .shadow(
                    elevation = 6.dp,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    clip = false
                )
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .onSizeChanged { intSize -> chatInputContentHeightPx = intSize.height }
        ) {
            Column(modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)) {
                if (selectedMediaItems.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(selectedMediaItems, key = { _, item -> item.id }) { index, media ->
                            SelectedItemPreview(
                                mediaItem = media,
                                onRemoveClicked = { onRemoveMediaItemAtIndex(index) }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { focusState ->
                            // 只有当输入框获得焦点时才滚动到底部，失去焦点时不滚动
                            if (focusState.isFocused) {
                                onFocusChange(true)
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
                    shape = RoundedCornerShape(16.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onToggleWebSearch) {
                            Icon(
                                if (isWebSearchEnabled) Icons.Outlined.TravelExplore else Icons.Filled.Language,
                                if (isWebSearchEnabled) "网页搜索已开启" else "网页搜索已关闭",
                                tint = SeaBlue,
                                modifier = Modifier.size(25.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = onToggleImagePanel) {
                            Icon(
                                Icons.Outlined.Image,
                                if (showImageSelectionPanel) "关闭图片选项" else "选择图片",
                                tint = Color(0xff2cb334)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = onToggleMoreOptionsPanel) {
                            Icon(
                                Icons.Filled.Tune,
                                if (showMoreOptionsPanel) "关闭更多选项" else "更多选项",
                                tint = Color(0xfff76213)
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (text.isNotEmpty() || selectedMediaItems.isNotEmpty()) {
                            IconButton(onClick = onClearContent) {
                                Icon(
                                    Icons.Filled.Clear,
                                    "清除内容和所选项目",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        FilledIconButton(
                            onClick = onSendClick,
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                if (isApiCalling) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                                if (isApiCalling) "停止" else "发送"
                            )
                        }
                    }
                }
            }
        }

        val yOffsetPx = -chatInputContentHeightPx.toFloat() - with(density) { 8.dp.toPx() }

        if (showImageSelectionPanel) {
            val iconButtonApproxWidth = 48.dp
            val spacerWidth = 8.dp
            val columnStartPadding = 8.dp
            val imageButtonCenterX = columnStartPadding + iconButtonApproxWidth + spacerWidth + (iconButtonApproxWidth / 2)
            val panelWidthDp = 150.dp
            val xOffsetForPopup = imageButtonCenterX - (panelWidthDp / 2)
            val xOffsetPx = with(density) { xOffsetForPopup.toPx() }
            Popup(
                alignment = Alignment.BottomStart,
                offset = IntOffset(xOffsetPx.toInt(), yOffsetPx.toInt()),
                onDismissRequest = { showImageSelectionPanel = false },
                properties = PopupProperties(
                    focusable = false,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                val alpha = remember { Animatable(0f) }
                val scale = remember { Animatable(0.8f) }

                LaunchedEffect(showImageSelectionPanel) {
                    if (showImageSelectionPanel) {
                        launch {
                            alpha.animateTo(1f, animationSpec = tween(durationMillis = 300))
                        }
                        launch {
                            scale.animateTo(1f, animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing))
                        }
                    }
                }

                Box(modifier = Modifier.graphicsLayer {
                    this.alpha = alpha.value
                    this.scaleX = scale.value
                    this.scaleY = scale.value
                    this.transformOrigin = TransformOrigin(0.5f, 1f)
                }) {
                    ImageSelectionPanel { selectedOption ->
                        showImageSelectionPanel = false
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

        if (showMoreOptionsPanel) {
            val iconButtonApproxWidth = 48.dp
            val spacerWidth = 8.dp
            val columnStartPadding = 8.dp
            val tuneButtonCenterX =
                columnStartPadding + iconButtonApproxWidth + spacerWidth + iconButtonApproxWidth + spacerWidth + (iconButtonApproxWidth / 2)
            val panelWidthDp = 150.dp
            val xOffsetForPopup = tuneButtonCenterX - (panelWidthDp / 2)
            val xOffsetForMoreOptionsPanelPx = with(density) { xOffsetForPopup.toPx() }

            Popup(
                alignment = Alignment.BottomStart,
                offset = IntOffset(xOffsetForMoreOptionsPanelPx.toInt(), yOffsetPx.toInt()),
                onDismissRequest = { showMoreOptionsPanel = false },
                properties = PopupProperties(
                    focusable = false,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                val alpha = remember { Animatable(0f) }
                val scale = remember { Animatable(0.8f) }

                LaunchedEffect(showMoreOptionsPanel) {
                    if (showMoreOptionsPanel) {
                        launch {
                            alpha.animateTo(1f, animationSpec = tween(durationMillis = 300))
                        }
                        launch {
                            scale.animateTo(1f, animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing))
                        }
                    }
                }

                Box(modifier = Modifier.graphicsLayer {
                    this.alpha = alpha.value
                    this.scaleX = scale.value
                    this.scaleY = scale.value
                    this.transformOrigin = TransformOrigin(0.5f, 1f)
                }) {
                    MoreOptionsPanel { selectedOption ->
                        showMoreOptionsPanel = false
                        val mimeTypesArray = Array(selectedOption.mimeTypes.size) { index ->
                            selectedOption.mimeTypes[index]
                        }
                        filePickerLauncher.launch(mimeTypesArray)
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            tempCameraImageUri?.let { uri ->
                safeDeleteTempFile(context, uri)
            }
        }
    }
}