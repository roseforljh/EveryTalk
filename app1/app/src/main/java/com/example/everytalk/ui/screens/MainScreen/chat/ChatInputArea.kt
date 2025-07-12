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
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
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
import com.example.everytalk.util.AppImageLoader
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

// 修复：将文件查询移到后台线程，添加异常处理
private suspend fun getFileDetailsFromUri(
    context: Context,
    uri: Uri
): Triple<String, String?, String?> {
    return withContext(Dispatchers.IO) {
        var displayName: String? = null
        // First, try to get the display name to find the extension
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

        // Now, try to get the MIME type from the content resolver
        var mimeType: String? = try {
            context.contentResolver.getType(uri)
        } catch (e: Exception) {
            Log.e("FileDetails", "Error getting MIME type for URI: $uri", e)
            null
        }

        // If the content resolver fails, fall back to using the file extension
        if (mimeType == null && displayName != null) {
            val fileExtension = displayName!!.substringAfterLast('.', "").lowercase(Locale.getDefault())
            if (fileExtension.isNotEmpty()) {
                mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension)
            }
        }

        Triple(displayName ?: "Unknown File", mimeType, uri.toString())
    }
}

// 修复：添加安全的文件删除函数
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
    val panelBackgroundColor = Color(0xFFf4f4f4)
    val darkerBackgroundColor = Color(0xFFCCCCCC)

    Surface(
        modifier = modifier.width(150.dp),
        shape = RoundedCornerShape(20.dp),
        color = panelBackgroundColor,
        tonalElevation = 4.dp
    ) {
        Column {
            // 修复：使用 .values() 替代 .entries
            ImageSourceOption.values().forEach { option ->
                val isSelected = activeOption == option
                val animatedBackgroundColor by animateColorAsState(
                    targetValue = if (isSelected) darkerBackgroundColor else panelBackgroundColor,
                    animationSpec = tween(durationMillis = 200),
                    label = "ImageOptionPanelItemBackground"
                )

                // 修复：确保返回类型为 Unit
                val onClickCallback = remember(option) {
                    {
                        activeOption = option
                        onOptionSelected(option)
                        Unit // 明确返回 Unit
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
                        tint = Color(0xFF7b7b7b),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(text = option.label, color = Color.Black, fontSize = 16.sp)
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
    val panelBackgroundColor = Color(0xFFf4f4f4)
    val darkerBackgroundColor = Color(0xFFCCCCCC)

    Surface(
        modifier = modifier.width(150.dp),
        shape = RoundedCornerShape(20.dp),
        color = panelBackgroundColor,
        tonalElevation = 4.dp
    ) {
        Column {
            // 修复：使用 .values() 替代 .entries
            MoreOptionsType.values().forEach { option ->
                val isSelected = activeOption == option
                val animatedBackgroundColor by animateColorAsState(
                    targetValue = if (isSelected) darkerBackgroundColor else panelBackgroundColor,
                    animationSpec = tween(durationMillis = 200),
                    label = "MoreOptionPanelItemBackground"
                )

                // 修复：确保返回类型为 Unit
                val onClickCallback = remember(option) {
                    {
                        activeOption = option
                        onOptionSelected(option)
                        Unit // 明确返回 Unit
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
                        tint = Color(0xFF7b7b7b),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(text = option.label, color = Color.Black, fontSize = 16.sp)
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
            .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp))
            .background(Color.White)
    ) {
        when (mediaItem) {
            is SelectedMediaItem.ImageFromUri -> AsyncImage(
                model = mediaItem.uri,
                contentDescription = "Selected image from gallery",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                imageLoader = AppImageLoader.get(context)
            )

            is SelectedMediaItem.ImageFromBitmap -> AsyncImage(
                model = mediaItem.bitmap,
                contentDescription = "Selected image from camera",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                imageLoader = AppImageLoader.get(context)
            )

            is SelectedMediaItem.GenericFile -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    val icon = when {
                        mediaItem.mimeType?.startsWith("video/") == true -> Icons.Outlined.Videocam
                        mediaItem.mimeType?.startsWith("audio/") == true -> Icons.Outlined.Audiotrack
                        else -> Icons.Outlined.Description
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
                        color = Color.DarkGray
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
                        color = Color.DarkGray
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
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove item",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
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

    // 修复：改善相册选择的错误处理
    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        try {
            if (uri != null) {
                val mimeType = context.contentResolver.getType(uri) ?: "image/*"
                onAddMediaItem(SelectedMediaItem.ImageFromUri(uri, UUID.randomUUID().toString(), mimeType))
            } else {
                Log.d("PhotoPicker", "用户取消了图片选择")
                // 不关闭面板，让用户可以重新选择
            }
        } catch (e: Exception) {
            Log.e("PhotoPicker", "处理选择的图片时发生错误", e)
            onShowSnackbar("选择图片时发生错误")
        }
    }

    // 修复：改善相机启动器的错误处理和资源清理
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val currentUri = tempCameraImageUri
        try {
            if (success && currentUri != null) {
                onAddMediaItem(SelectedMediaItem.ImageFromUri(currentUri, UUID.randomUUID().toString(), "image/jpeg"))
            } else {
                Log.w("CameraLauncher", "相机拍照失败或被取消")
                // 修复：失败时也要清理临时文件
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
            // 修复：确保总是清理 tempCameraImageUri 引用
            tempCameraImageUri = null
        }
    }

    // 修复：改善权限处理，权限被拒绝时不关闭面板
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
            // 修复：不关闭面板，让用户可以重新尝试或选择其他选项
        }
    }

    // 修复：改善文件选择器的错误处理和后台处理
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            if (uri != null) {
                // 修复：使用 coroutineScope 处理文件操作
                coroutineScope.launch {
                    try {
                        val (displayName, mimeType, _) = getFileDetailsFromUri(context, uri)
                        Log.d(
                            "OpenDocument",
                            "Selected Document: $displayName, URI: $uri, MIME: $mimeType"
                        )
                        withContext(Dispatchers.Main) {
                            onAddMediaItem(
                                SelectedMediaItem.GenericFile(
                                    uri = uri,
                                    id = UUID.randomUUID().toString(),
                                   displayName = displayName,
                                   mimeType = mimeType,
                                   filePath = null
                                )
                            )
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
                // 不关闭面板，让用户可以重新选择
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

    // 由于我们现在直接发送消息，不再需要等待键盘隐藏
    // 这段代码保留但不再使用，以防其他地方依赖它
    LaunchedEffect(Unit) {
        snapshotFlow { imeInsets.getBottom(density) > 0 }
            .distinctUntilChanged()
            .filter { isKeyboardVisible -> !isKeyboardVisible }
            .collect { _ ->
                // 不再需要在键盘隐藏后处理消息发送
                pendingMessageTextForSend = null
            }
    }

    var chatInputContentHeightPx by remember { mutableIntStateOf(0) }
    val panelVerticalMarginFromTopInput = 16.dp

    // 修复：确保返回类型为 Unit
    val onToggleImagePanel = remember {
        {
            if (showMoreOptionsPanel) showMoreOptionsPanel = false
            showImageSelectionPanel = !showImageSelectionPanel
            Unit
        }
    }

    val onToggleMoreOptionsPanel = remember {
        {
            if (showImageSelectionPanel) showImageSelectionPanel = false
            showMoreOptionsPanel = !showMoreOptionsPanel
            Unit
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
                    } else if ((text.isNotBlank() || selectedMediaItems.isNotEmpty()) && selectedApiConfig != null) {
                        // 无论键盘是否可见，都立即发送消息并滚动到底部
                        val audioItem = selectedMediaItems.firstOrNull { it is SelectedMediaItem.Audio } as? SelectedMediaItem.Audio
                        val mimeType = audioItem?.mimeType
                        onSendMessageRequest(text, false, selectedMediaItems.toList(), mimeType)
                        onTextChange("")
                        onClearMediaItems()
                        
                        // 如果键盘可见，则隐藏键盘
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
                .background(Color.White, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                .onSizeChanged { intSize -> chatInputContentHeightPx = intSize.height }
        ) {
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
                    .onFocusChanged { onFocusChange(it.isFocused) }
                    .padding(bottom = 4.dp),
                placeholder = { Text("输入消息…") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
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
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(25.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = onToggleImagePanel) {
                        Icon(
                            Icons.Outlined.Image,
                            if (showImageSelectionPanel) "关闭图片选项" else "选择图片",
                            tint = Color(0xff2cb334),
                            modifier = Modifier.size(25.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = onToggleMoreOptionsPanel) {
                        Icon(
                            Icons.Filled.Tune,
                            if (showMoreOptionsPanel) "关闭更多选项" else "更多选项",
                            tint = Color(0xfff76213),
                            modifier = Modifier.size(25.dp)
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
                            containerColor = Color.Black,
                            contentColor = Color.White
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

        val yOffsetPx =
            -(chatInputContentHeightPx.toFloat() + with(density) { panelVerticalMarginFromTopInput.toPx() })

        // 图片选择面板
        if (showImageSelectionPanel) {
            val xOffsetPx = with(density) { 8.dp.toPx() }
            Popup(
                alignment = Alignment.BottomStart,
                offset = IntOffset(xOffsetPx.toInt(), yOffsetPx.toInt()),
                onDismissRequest = { showImageSelectionPanel = false },
                properties = PopupProperties(
                    focusable = false,
                    dismissOnClickOutside = true,
                    dismissOnBackPress = true
                )
            ) {
                AnimatedVisibility(
                    visible = showImageSelectionPanel,
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(150)),
                    label = "ImageSelectionPanelVisibility"
                ) {
                    ImageSelectionPanel { selectedOption ->
                        showImageSelectionPanel = false
                        when (selectedOption) {
                            ImageSourceOption.ALBUM -> photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )

                            ImageSourceOption.CAMERA -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                }
            }
        }

        // 更多选项面板
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
                    dismissOnClickOutside = true,
                    dismissOnBackPress = true
                )
            ) {
                AnimatedVisibility(
                    visible = showMoreOptionsPanel,
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(150)),
                    label = "MoreOptionsPanelVisibility"
                ) {
                    MoreOptionsPanel { selectedOption ->
                        showMoreOptionsPanel = false
                        Log.d(
                            "MoreOptionsPanel",
                            "Selected: ${selectedOption.label}, Launching with MIME types: ${selectedOption.mimeTypes.joinToString()}"
                        )
                        val mimeTypesArray = Array(selectedOption.mimeTypes.size) { index ->
                            selectedOption.mimeTypes[index]
                        }
                        filePickerLauncher.launch(mimeTypesArray)
                    }
                }
            }
        }
    }

    // 修复：组件销毁时清理临时文件
    DisposableEffect(Unit) {
        onDispose {
            tempCameraImageUri?.let { uri ->
                safeDeleteTempFile(context, uri)
            }
        }
    }
}