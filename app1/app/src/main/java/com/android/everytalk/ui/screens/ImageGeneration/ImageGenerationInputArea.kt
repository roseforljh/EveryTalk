package com.android.everytalk.ui.screens.ImageGeneration

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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.ImageRatio
import com.android.everytalk.models.ImageSourceOption
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.components.ImageRatioSelector
import com.android.everytalk.ui.components.ImageGenCapabilities
import com.android.everytalk.ui.components.ImageGenCapabilities.ModelFamily
import com.android.everytalk.ui.components.ImageGenCapabilities.QualityTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.android.everytalk.config.PerformanceConfig
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

private suspend fun checkFileSizeAndShowError(
    context: Context,
    uri: Uri,
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
                    onShowSnackbar("File is too large ($fileSizeFormatted), max size is 50MB")
                }
                return@withContext false
            }
            return@withContext true
        } catch (e: Exception) {
            Log.e("FileSizeCheck", "Error checking file size for $uri", e)
            withContext(Dispatchers.Main) {
                onShowSnackbar("Could not check file size, please select a smaller file")
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
            Log.w("FileCleanup", "Could not delete temp file: $uri", e)
        } catch (e: Exception) {
            Log.e("FileCleanup", "Error deleting temp file: $uri", e)
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
            else -> {}
        }
        IconButton(
            onClick = onRemoveClicked,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(1.dp)
                .size(16.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.32f),
                    shape = CircleShape
                ),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = Color.Transparent,
                contentColor = Color.White
            )
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove item",
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ImageGenerationInputArea(
    text: String,
    onTextChange: (String) -> Unit,
    onSendMessageRequest: (messageText: String, attachments: List<SelectedMediaItem>) -> Unit,
    selectedMediaItems: List<SelectedMediaItem>,
    onAddMediaItem: (SelectedMediaItem) -> Unit,
    onRemoveMediaItemAtIndex: (Int) -> Unit,
    onClearMediaItems: () -> Unit,
    isApiCalling: Boolean,
    onStopApiCall: () -> Unit,
    focusRequester: FocusRequester,
    selectedApiConfig: ApiConfig?,
    onShowSnackbar: (String) -> Unit,
    imeInsets: WindowInsets,
    density: Density,
    keyboardController: SoftwareKeyboardController?,
    onFocusChange: (isFocused: Boolean) -> Unit,
    selectedImageRatio: ImageRatio = ImageRatio.DEFAULT_SELECTED,
    onImageRatioChanged: (ImageRatio) -> Unit = {},
    // 在选择比例后，把解析出的最终分辨率（如 "960x1280"）回传给上层；无法解析时传 null
    onResolvedImageSize: (String?) -> Unit = {},
    // 当前图像推理步数（仅在 z-image 模型下使用）
    currentImageSteps: Int? = null,
    // 更新当前图像推理步数的回调
    onChangeImageSteps: ((Int) -> Unit)? = null,
    // 当前图像引导系数（仅在 Qwen-Image-Edit 模型下使用）
    currentImageGuidance: Float? = null,
    // 更新当前图像参数（步数+引导系数）的回调
    onChangeImageParams: ((Int, Float) -> Unit)? = null,
    // 新增：Gemini 尺寸变更回调
    onGeminiImageSizeChanged: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // 🎯 性能优化：使用本地状态管理输入文本，避免每次按键都触发 ViewModel 更新
    var localText by remember { mutableStateOf(text) }
    var syncJob by remember { mutableStateOf<Job?>(null) }
    var lastExternalText by remember { mutableStateOf(text) }
    
    // 当外部 text 变化时（如清空），同步到本地状态
    LaunchedEffect(text) {
        if (text != lastExternalText) {
            lastExternalText = text
            localText = text
        }
    }
    
    // 防抖同步到 ViewModel
    LaunchedEffect(localText) {
        syncJob?.cancel()
        syncJob = coroutineScope.launch {
            delay(PerformanceConfig.STATE_DEBOUNCE_DELAY_MS)
            if (localText != text) {
                onTextChange(localText)
                lastExternalText = localText
            }
        }
    }

    // 计算最终分辨率字符串（仅对 Kolors/Qwen 生效），其余家族返回 null（由后端/其它逻辑处理）
    fun resolveFinalImageSizeForFamily(ratio: ImageRatio, family: ModelFamily?): String? {
        return when (family) {
            ModelFamily.KOLORS -> {
                // 若弹窗展开了两个 3:4 分辨率，则 ratio.width/height 已是具体值；否则按映射取第一个推荐值
                val labelFromRatio = "${ratio.width}x${ratio.height}"
                val kolorsMapped = ImageGenCapabilities.getKolorsSizesByRatio(ratio.displayName).firstOrNull()?.label
                // 优先使用更精确的 ratio 宽高；若为默认比例（非精确推荐），回退映射表
                if (kolorsMapped.isNullOrBlank()) labelFromRatio else kolorsMapped
            }
            ModelFamily.QWEN -> {
                // 按文档比例→推荐分辨率，取第一个（官方推荐集中只有一个匹配）
                ImageGenCapabilities.getQwenSizesByRatio(ratio.displayName).firstOrNull()?.label
            }
            else -> null
        }
    }

    // 基于当前配置检测模型家族，并派生“可用比例候选”与（Seedream专属）清晰度
    val detectedFamily: ModelFamily? = remember(selectedApiConfig) {
        ImageGenCapabilities.detectFamily(
            modelName = selectedApiConfig?.model,
            provider = selectedApiConfig?.provider,
            apiAddress = selectedApiConfig?.address
        )
    }
    val familyCapabilities = remember(detectedFamily) {
        detectedFamily?.let { ImageGenCapabilities.getCapabilities(it) }
    }
    val allowedRatioNames: List<String>? = remember(familyCapabilities) {
        val r = familyCapabilities?.ratios.orEmpty()
        if (r.isEmpty()) null else r.map { it.ratio }
    }

    // 当前图像模型是否支持图像编辑（z-image 不支持上传/编辑本地图片）
    val supportsImageEditing: Boolean = remember(detectedFamily) {
        detectedFamily != ModelFamily.MODAL_Z_IMAGE
    }

    // 当模型家族变化导致可用比例列表变更时，校验当前选中比例是否合法
    // 若当前比例不在新模型的允许列表中（且非 AUTO），则重置为 AUTO
    LaunchedEffect(allowedRatioNames) {
        if (allowedRatioNames != null && !selectedImageRatio.isAuto) {
            if (selectedImageRatio.displayName !in allowedRatioNames) {
                onImageRatioChanged(ImageRatio.AUTO)
            }
        }
    }

    var seedreamQuality by remember(detectedFamily) {
        mutableStateOf(QualityTier.Q2K)
    }
 
    var showImageSelectionPanel by remember { mutableStateOf(false) }
    // 记录外点关闭的时间戳，用于忽略随后紧邻的按钮抬起点击，避免“先关后又开”
    var lastImagePanelDismissAt by remember { mutableStateOf(0L) }

    // 步数调整对话框状态
    var showStepsDialog by remember { mutableStateOf(false) }
    // 参数调整对话框状态 (Qwen Edit)
    var showParamsDialog by remember { mutableStateOf(false) }

    // 当当前图像模型不支持图像编辑时，确保关闭相册选择面板
    LaunchedEffect(supportsImageEditing) {
        if (!supportsImageEditing && showImageSelectionPanel) {
            showImageSelectionPanel = false
        }
    }
    var tempCameraImageUri by remember { mutableStateOf<Uri?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                try {
                    uris.forEach { uri ->
                        val mimeType = context.contentResolver.getType(uri) ?: "image/*"

                        val isFileSizeValid = checkFileSizeAndShowError(context, uri, onShowSnackbar)
                        if (isFileSizeValid) {
                            withContext(Dispatchers.Main) {
                                onAddMediaItem(SelectedMediaItem.ImageFromUri(uri, UUID.randomUUID().toString(), mimeType))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PhotoPicker", "Error processing selected image", e)
                    withContext(Dispatchers.Main) {
                        onShowSnackbar("Error selecting image")
                    }
                }
            }
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
                if (currentUri != null) {
                    safeDeleteTempFile(context, currentUri)
                }
            }
        } catch (e: Exception) {
            Log.e("CameraLauncher", "Error processing camera photo", e)
            onShowSnackbar("Error taking photo")
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
                Log.e("CameraPermission", "Error creating camera file URI", e)
                onShowSnackbar("Error starting camera")
            }
        } else {
            onShowSnackbar("Camera permission is required to take photos")
        }
    }

    var chatInputContentHeightPx by remember { mutableIntStateOf(0) }

    val onToggleImagePanel = {
        // 若刚刚由外点关闭，忽略紧随其后的按钮抬起点击，避免“先关后开”
        val now = android.os.SystemClock.uptimeMillis()
        if (!showImageSelectionPanel && now - lastImagePanelDismissAt < 200L) {
            // ignore reopen right after outside-dismiss
        } else {
            showImageSelectionPanel = !showImageSelectionPanel
        }
    }

    val onClearContent = remember {
        {
            // 清空时同时清空本地状态
            localText = ""
            lastExternalText = ""
            syncJob?.cancel()
            onTextChange("")
            onClearMediaItems()
            Unit
        }
    }

    // 🎯 性能优化：发送时使用本地文本
    val onSendClick =
        remember(isApiCalling, localText, selectedMediaItems, selectedApiConfig, imeInsets, density) {
            {
                try {
                    if (isApiCalling) {
                        onStopApiCall()
                    } else if ((localText.isNotBlank() || selectedMediaItems.isNotEmpty()) && selectedApiConfig != null) {
                        onSendMessageRequest(localText, selectedMediaItems.toList())
                        // 同时清空本地状态和 ViewModel 状态
                        localText = ""
                        lastExternalText = ""
                        syncJob?.cancel()
                        onTextChange("")
                        onClearMediaItems()
                        
                        if (imeInsets.getBottom(density) > 0) {
                            keyboardController?.hide()
                        }
                    } else if (selectedApiConfig == null) {
                        onShowSnackbar("Please select an API configuration first")
                    } else {
                        onShowSnackbar("Please enter a message or select an item")
                    }
                } catch (e: Exception) {
                    Log.e("SendMessage", "Error sending message", e)
                    onShowSnackbar("Failed to send message")
                }
                Unit
            }
        }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBarsIgnoringVisibility))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(1f)
                .align(Alignment.BottomCenter)
                .padding(start = 6.dp, end = 6.dp, bottom = 10.dp)
                .background(
                    MaterialTheme.colorScheme.background
                )
                .onSizeChanged { intSize -> chatInputContentHeightPx = intSize.height }
        ) {
            val borderColor = if (isSystemInDarkTheme()) Color.Gray.copy(alpha = 0.3f) else Color.Gray.copy(alpha = 0.2f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                borderColor,
                                Color.Transparent
                            )
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 4.dp)
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

                // 🎯 性能优化：使用本地状态驱动 TextField
                OutlinedTextField(
                    value = localText,
                    onValueChange = { newText ->
                        // 立即更新本地状态，无延迟
                        localText = newText
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { focusState ->
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
                    shape = RoundedCornerShape(32.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (supportsImageEditing) {
                            IconButton(onClick = onToggleImagePanel) {
                                Icon(
                                    Icons.Outlined.Image,
                                    if (showImageSelectionPanel) "Close image options" else "Select image",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        
                        // Qwen-Image-Edit 模型下的参数调节按钮
                        val isQwenEdit = selectedApiConfig?.model?.contains("Image-Edit", ignoreCase = true) == true

                        // 比例选择按钮（按家族动态候选；仅 Seedream 显示 2K/4K 清晰度）
                        // Qwen-Image-Edit 模型不显示分辨率选择
                        if (!isQwenEdit) {
                            ImageRatioSelector(
                                selectedRatio = selectedImageRatio,
                                onRatioChanged = onImageRatioChanged,
                                modifier = Modifier.padding(start = 4.dp),
                                allowedRatioNames = allowedRatioNames,
                                family = detectedFamily,
                                seedreamQuality = seedreamQuality,
                                onQualityChange = { seedreamQuality = it },
                                geminiImageSize = selectedApiConfig?.imageSize,
                                onGeminiImageSizeChange = onGeminiImageSizeChanged
                            )
                        }

                        // z-image 模型下的推理步数调节按钮
                        if (detectedFamily == ModelFamily.MODAL_Z_IMAGE && onChangeImageSteps != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            AssistChip(
                                onClick = { showStepsDialog = true },
                                label = {
                                    Text(
                                        text = "步数 ${currentImageSteps ?: 4}"
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Tune,
                                        contentDescription = "调整推理步数"
                                    )
                                }
                            )
                        }

                        if (isQwenEdit && onChangeImageParams != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            AssistChip(
                                onClick = { showParamsDialog = true },
                                label = {
                                    Text(
                                        text = "参数调节",
                                        color = Color(0xFFFF9800)
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Tune,
                                        contentDescription = "调整生成参数",
                                        tint = Color(0xFFFF9800)
                                    )
                                },
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = Color(0xFFFF9800).copy(alpha = 0.5f)
                                )
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (localText.isNotEmpty() || selectedMediaItems.isNotEmpty()) {
                            IconButton(onClick = onClearContent) {
                                Icon(
                                    Icons.Filled.Clear,
                                    "Clear content and selected items",
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
                                if (isApiCalling) "Stop" else "Send"
                            )
                        }
                    }
                }
            }
            
            // 已由外层 windowInsetsPadding 统一处理底部系统栏，无需额外 spacer
        }

        val yOffsetPx = -chatInputContentHeightPx.toFloat() - with(density) { 8.dp.toPx() }
 
        // 为相册面板加入退出动画：渲染标志 + 动画控制
        var renderImageSelectionPanel by remember { mutableStateOf(false) }
        val imageAlpha = remember { Animatable(0f) }
        val imageScale = remember { Animatable(0.8f) }
 
        LaunchedEffect(showImageSelectionPanel) {
            if (showImageSelectionPanel) {
                renderImageSelectionPanel = true
                launch { imageAlpha.animateTo(1f, animationSpec = tween(durationMillis = 150)) }
                launch { imageScale.animateTo(1f, animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)) }
            } else if (renderImageSelectionPanel) {
                // 退场动画后再移除渲染，避免“瞬间闪掉”
                launch { imageAlpha.animateTo(0f, animationSpec = tween(durationMillis = 140)) }
                launch { imageScale.animateTo(0.9f, animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)) }
                    .invokeOnCompletion { renderImageSelectionPanel = false }
            }
        }
 
        if (renderImageSelectionPanel) {
            val iconButtonApproxWidth = 48.dp
            val columnStartPadding = 8.dp
            val imageButtonCenterX = columnStartPadding + (iconButtonApproxWidth / 2)
            val panelWidthDp = 150.dp
            val xOffsetForPopup = imageButtonCenterX - (panelWidthDp / 2)
            val xOffsetPx = with(density) { xOffsetForPopup.toPx() }
            Popup(
                alignment = Alignment.BottomStart,
                offset = IntOffset(xOffsetPx.toInt(), yOffsetPx.toInt()),
                onDismissRequest = {
                    // 记录外点关闭时间，并触发退场动画
                    lastImagePanelDismissAt = android.os.SystemClock.uptimeMillis()
                    if (showImageSelectionPanel) showImageSelectionPanel = false
                },
                properties = PopupProperties(
                    // 非可聚焦，避免收起输入法；仍支持外点与返回键关闭
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
                    ImageSelectionPanel { selectedOption ->
                        // 点击选项后优雅退场，然后发起对应动作
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

        if (showStepsDialog && onChangeImageSteps != null) {
            var stepsValue by remember(currentImageSteps) { mutableFloatStateOf((currentImageSteps ?: 4).toFloat()) }
            var stepsText by remember(currentImageSteps) { mutableStateOf((currentImageSteps ?: 4).toString()) }

            AlertDialog(
                onDismissRequest = { showStepsDialog = false },
                title = { Text("调整推理步数") },
                text = {
                    Column {
                        Text(
                            text = "步数越高生成越慢，但细节可能更丰富 (1-20)\n推荐步数为 4",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Slider(
                                value = stepsValue,
                                onValueChange = {
                                    stepsValue = it
                                    stepsText = it.toInt().toString()
                                },
                                valueRange = 1f..20f,
                                steps = 19,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = Color.White,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                                    activeTickColor = Color.Transparent,
                                    inactiveTickColor = Color.Transparent
                                )
                            )
                            
                            Spacer(modifier = Modifier.width(12.dp))
                            
                            OutlinedTextField(
                                value = stepsText,
                                onValueChange = { newValue: String ->
                                    if (newValue.all { char -> char.isDigit() }) {
                                        stepsText = newValue
                                        val num = newValue.toIntOrNull()
                                        if (num != null) {
                                            stepsValue = num.coerceIn(1, 20).toFloat()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .width(56.dp)
                                    .height(48.dp),
                                singleLine = true,
                                shape = CircleShape,
                                textStyle = LocalTextStyle.current.copy(
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                )
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val finalSteps = stepsValue.toInt().coerceIn(1, 20)
                            onChangeImageSteps(finalSteps)
                            showStepsDialog = false
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("确定")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showStepsDialog = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Text("取消")
                    }
                }
            )
        }
    }

    if (showParamsDialog && onChangeImageParams != null) {
        var stepsText by remember(currentImageSteps) { mutableStateOf((currentImageSteps ?: 30).toString()) }
        var guidanceText by remember(currentImageGuidance) { mutableStateOf((currentImageGuidance ?: 7.5f).toString()) }

        AlertDialog(
            onDismissRequest = { showParamsDialog = false },
            containerColor = if (isSystemInDarkTheme()) Color(0xFF2C2C2C) else Color(0xFFF0F0F0),
            title = { Text("调整生成参数") },
            text = {
                Column {
                    // 推理步数
                    Text(
                        text = "推理步数 (Steps)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    OutlinedTextField(
                        value = stepsText,
                        onValueChange = { newValue ->
                            if (newValue.all { it.isDigit() }) {
                                stepsText = newValue
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        placeholder = { Text("推荐值: 30") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Black,
                            unfocusedContainerColor = Color.Black,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.Black,
                            cursorColor = Color.White
                        )
                    )
                    Text(
                        text = "推荐值: 30 (范围 1-50)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                    )

                    // 引导系数
                    Text(
                        text = "引导系数 (Guidance)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    OutlinedTextField(
                        value = guidanceText,
                        onValueChange = { newValue ->
                            if (newValue.all { it.isDigit() || it == '.' }) {
                                guidanceText = newValue
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        placeholder = { Text("推荐值: 7.5") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Black,
                            unfocusedContainerColor = Color.Black,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.Black,
                            cursorColor = Color.White
                        )
                    )
                    Text(
                        text = "推荐值: 7.5 (范围 1.0-10.0)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val finalSteps = stepsText.toIntOrNull()?.coerceIn(1, 50) ?: 30
                        val finalGuidance = guidanceText.toFloatOrNull()?.coerceIn(1f, 10f) ?: 7.5f
                        onChangeImageParams(finalSteps, finalGuidance)
                        showParamsDialog = false
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showParamsDialog = false }) { Text("取消") }
            }
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