package com.android.everytalk.ui.screens.ImageGeneration

import kotlin.math.max
import android.Manifest
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.res.painterResource
import com.android.everytalk.R
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.ImageRatio
import com.android.everytalk.ui.components.modifier.diffuseShadow
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.components.ImageRatioSelector
import com.android.everytalk.ui.components.ImageGenCapabilities
import com.android.everytalk.ui.components.ImageGenCapabilities.ModelFamily
import com.android.everytalk.ui.components.ImageGenCapabilities.QualityTier
import com.android.everytalk.ui.components.dialog.AppDialogShape
import com.android.everytalk.ui.components.dialog.AppDialogTextFieldShape
import com.android.everytalk.ui.components.dialog.appDialogBorderColor
import com.android.everytalk.ui.components.dialog.appDialogContainerColor
import com.android.everytalk.ui.components.dialog.appDialogContentColor
import com.android.everytalk.ui.components.dialog.appDialogTextFieldDefaultBorderColor
import com.android.everytalk.ui.components.dialog.appDialogTextFieldBorderColor
import com.android.everytalk.ui.components.dialog.appDialogTextFieldColors
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

internal fun resolveImageFunctionPanelMaxHeightDp(imeVisible: Boolean): Int =
    if (imeVisible) 300 else 370

internal fun resolveImageFunctionPanelPopupY(
    windowHeightPx: Int,
    anchorTopPx: Int,
    inputContentHeightPx: Int,
    popupHeightPx: Int,
    marginPx: Int
): Int {
    val maxY = (windowHeightPx - popupHeightPx).coerceAtLeast(0)
    val anchorBasedY = anchorTopPx - marginPx - popupHeightPx
    val fallbackY = windowHeightPx - inputContentHeightPx - marginPx - popupHeightPx
    val rawY = if (anchorTopPx > 0) anchorBasedY else fallbackY
    return rawY.coerceIn(0, maxY)
}

@Composable
fun SelectedItemPreview(
    mediaItem: SelectedMediaItem,
    onRemoveClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
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
            is SelectedMediaItem.ImageFromBitmap -> {
                val imageModel = remember(mediaItem) { mediaItem.model }
                AsyncImage(
                    model = imageModel,
                    contentDescription = "Selected image from camera",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            else -> {}
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-2).dp, y = 2.dp)
                .size(22.dp)
                .background(
                    color = Color(0xFF616161),
                    shape = CircleShape
                )
                .clip(CircleShape)
                .clickable(onClick = onRemoveClicked),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_close_bold),
                contentDescription = "Remove item",
                tint = Color.White,
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
    onGeminiImageSizeChanged: ((String) -> Unit)? = null,
    // GPT-Image-2 质量参数
    currentGptImageQuality: ImageGenCapabilities.GptImageQuality = ImageGenCapabilities.GptImageQuality.AUTO,
    onGptImageQualityChanged: ((ImageGenCapabilities.GptImageQuality) -> Unit)? = null,
    onHeightChange: (Int) -> Unit = {},
    onShowVoiceInput: () -> Unit = {}
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

    // 步数调整对话框状态
    var showStepsDialog by remember { mutableStateOf(false) }
    var showGptQualityDialog by remember { mutableStateOf(false) }
    var showRatioDialog by remember { mutableStateOf(false) }
    // 参数调整对话框状态 (Qwen Edit)
    var showParamsDialog by remember { mutableStateOf(false) }

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
                    } else if (localText.isBlank() && selectedMediaItems.isEmpty()) {
                        onShowVoiceInput()
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

    val isDarkTheme = isSystemInDarkTheme()
    var isFocused by remember { mutableStateOf(false) }
    var showFunctionPanel by remember { mutableStateOf(false) }
    var lastFunctionPanelDismissAt by remember { mutableLongStateOf(0L) }

    BackHandler(enabled = showFunctionPanel) {
        lastFunctionPanelDismissAt = android.os.SystemClock.uptimeMillis()
        showFunctionPanel = false
    }

    var renderFunctionPanel by remember { mutableStateOf(false) }
    val functionPanelAlpha = remember { Animatable(0f) }
    val functionPanelScale = remember { Animatable(0.8f) }

    // 输入法展开进度
    val imeBottomPx = imeInsets.getBottom(density)
    var maxImeBottomPx by remember { mutableIntStateOf(0) }
    if (imeBottomPx > maxImeBottomPx) {
        maxImeBottomPx = imeBottomPx
    }
    val imeProgress = if (maxImeBottomPx > 0) {
        (imeBottomPx.toFloat() / maxImeBottomPx.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val isImeVisible = imeProgress > 0.01f
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(isImeVisible, localText) {
        if (!isImeVisible && !showFunctionPanel && localText.isEmpty()) {
            focusManager.clearFocus()
        }
    }
    LaunchedEffect(localText, isFocused) {
        if (localText.isNotEmpty() && !isFocused) {
            focusRequester.requestFocus()
        }
    }

    val keepInputSeparated = localText.isNotEmpty() || selectedMediaItems.isNotEmpty()
    val separationTarget = if (keepInputSeparated) 1f else imeProgress
    val separationProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = separationTarget,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.857f,
            stiffness = 150f
        ),
        label = "separationProgress"
    )
    val sizeProgress = separationProgress
    val inputMinHeight = ((48f - 4f * sizeProgress).coerceIn(44f, 48f)).dp

    val functionPanelMaxHeight = resolveImageFunctionPanelMaxHeightDp(isImeVisible).dp
    val functionPanelPositionProvider = remember(chatInputContentHeightPx, density, isImeVisible) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val marginPx = with(density) { 8.dp.roundToPx() }
                val x = ((windowSize.width - popupContentSize.width) / 2)
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val y = resolveImageFunctionPanelPopupY(
                    windowHeightPx = windowSize.height,
                    anchorTopPx = anchorBounds.top,
                    inputContentHeightPx = chatInputContentHeightPx,
                    popupHeightPx = popupContentSize.height,
                    marginPx = marginPx
                )
                return IntOffset(x, y)
            }
        }
    }

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

    val buttonBackgroundColor by animateColorAsState(
        targetValue = if (isDarkTheme) Color.White else Color.Black,
        animationSpec = tween(durationMillis = 200),
        label = "SendButtonBackground"
    )
    val iconColor by animateColorAsState(
        targetValue = if (isDarkTheme) Color.Black else Color.White,
        animationSpec = tween(durationMillis = 200),
        label = "SendButtonIcon"
    )

    val isQwenEdit = selectedApiConfig?.model?.contains("Image-Edit", ignoreCase = true) == true
    val hasContent = localText.isNotEmpty() || selectedMediaItems.isNotEmpty()

    val inputBackgroundColor = MaterialTheme.colorScheme.background
    val navInsets = WindowInsets.navigationBarsIgnoringVisibility
    val baseInsets = navInsets.add(WindowInsets(bottom = 12.dp))
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
        .onSizeChanged { intSize ->
            chatInputContentHeightPx = intSize.height
            onHeightChange(intSize.height)
        }
        .windowInsetsPadding(targetInsets)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(1f)
                .align(Alignment.BottomCenter)
                .padding(start = 6.dp, end = 6.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
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

                val inputBackground = if (isDarkTheme) Color(0xFF1F1F1F) else Color.White

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.CenterHorizontally)
                        .graphicsLayer { clip = false },
                    contentAlignment = Alignment.Center
                ) {
                    val sep = separationProgress.coerceIn(0f, 1f)
                    val layoutProgress = sep * sep * (3f - 2f * sep)
                    val plusMotionProgress = layoutProgress
                    val textPaddingProgress = layoutProgress * layoutProgress
                    val expandedInputFieldWidth = (maxWidth * 0.82f).coerceAtMost(maxWidth - 48.dp)
                    val collapsedInputFieldWidth = expandedInputFieldWidth + 16.dp
                    val inputFieldWidth = collapsedInputFieldWidth + (expandedInputFieldWidth - collapsedInputFieldWidth) * layoutProgress
                    val plusBoxWidth = 64.dp

                    val plusStretchProgress = (plusMotionProgress * 2f).coerceIn(0f, 1f)
                    val plusRecoverProgress = ((plusMotionProgress - 0.5f) * 2f).coerceIn(0f, 1f)
                    val plusBorderInset = if (isDarkTheme) 1.dp * (1f - plusMotionProgress) else 0.dp
                    val plusWidth = (48f + 16f * plusStretchProgress - 20f * plusRecoverProgress).dp - plusBorderInset * 2
                    val plusOffset = plusBorderInset + (-20f * plusStretchProgress - 40f * plusRecoverProgress).dp
                    val groupLeft = if (plusOffset < 0.dp) plusOffset * layoutProgress else 0.dp
                    val groupWidth = inputFieldWidth - groupLeft
                    val plusHeight = inputMinHeight - plusBorderInset * 2
                    val plusCorner = plusHeight / 2
                    val plusShape = RoundedCornerShape(plusCorner)
                    val plusBg = inputBackground
                    val borderColor = if (isDarkTheme) Color(0xFF48474C) else Color(0xFFD6D6D6)
                    val separatedBorderAlpha = if (isDarkTheme) ((layoutProgress - 0.15f) / 0.35f).coerceIn(0f, 1f) else 0f
                    val plusBorderAlpha = if (isDarkTheme && separationTarget > 0.5f) separatedBorderAlpha else 0f
                    val collapsedInputBorderAlpha = if (isDarkTheme) ((0.35f - layoutProgress) / 0.35f).coerceIn(0f, 1f) else 0f
                    val inputBorderAlpha = kotlin.math.max(separatedBorderAlpha, collapsedInputBorderAlpha)

                    val inputShape = RoundedCornerShape(inputMinHeight / 2)
                    val textStartPadding = 48.dp - (48.dp - 16.dp) * textPaddingProgress

                    Box(
                        modifier = Modifier
                            .width(groupWidth)
                            .wrapContentHeight(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        // 加号按钮
                        Box(
                            modifier = Modifier
                                .offset(x = -groupLeft)
                                .zIndex(2f)
                                .graphicsLayer { clip = false }
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(plusBoxWidth)
                                    .height(plusHeight)
                                    .wrapContentWidth(Alignment.Start)
                                    .graphicsLayer { clip = false }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .offset(x = plusOffset)
                                        .width(plusWidth)
                                        .height(plusHeight)
                                        .diffuseShadow(
                                            color = Color.Black,
                                            alpha = 0.12f * layoutProgress,
                                            borderRadius = plusCorner,
                                            shadowRadius = 24.dp,
                                            offsetY = 0.dp,
                                            offsetX = 0.dp
                                        )
                                        .background(plusBg, plusShape)
                                        .border(1.dp, borderColor.copy(alpha = plusBorderAlpha), plusShape),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    IconButton(
                                        onClick = {
                                            val now = android.os.SystemClock.uptimeMillis()
                                            if (!showFunctionPanel && now - lastFunctionPanelDismissAt < 200L) {
                                                return@IconButton
                                            }
                                            showFunctionPanel = !showFunctionPanel
                                        },
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_plus),
                                            contentDescription = "功能面板",
                                            tint = if (isDarkTheme) Color.White else Color(0xFF0D0D0D),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }

                            if (renderFunctionPanel) {
                                Popup(
                                    popupPositionProvider = functionPanelPositionProvider,
                                    onDismissRequest = {
                                        lastFunctionPanelDismissAt = android.os.SystemClock.uptimeMillis()
                                        if (showFunctionPanel) showFunctionPanel = false
                                    },
                                    properties = PopupProperties(
                                        focusable = false,
                                        dismissOnBackPress = false,
                                        dismissOnClickOutside = true
                                    )
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .widthIn(max = 320.dp)
                                            .wrapContentHeight()
                                            .graphicsLayer {
                                                alpha = functionPanelAlpha.value
                                                scaleX = functionPanelScale.value
                                                scaleY = functionPanelScale.value
                                                transformOrigin = TransformOrigin(0.5f, 1f)
                                            }
                                    ) {
                                        ImageFunctionPanelContent(
                                            supportsImageEditing = supportsImageEditing,
                                            hasContent = hasContent,
                                            isQwenEdit = isQwenEdit,
                                            detectedFamily = detectedFamily,
                                            onChangeImageSteps = onChangeImageSteps,
                                            onChangeImageParams = onChangeImageParams,
                                            onOpenGallery = {
                                                lastFunctionPanelDismissAt = android.os.SystemClock.uptimeMillis()
                                                showFunctionPanel = false
                                                photoPickerLauncher.launch(
                                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                                )
                                            },
                                            onOpenCamera = {
                                                lastFunctionPanelDismissAt = android.os.SystemClock.uptimeMillis()
                                                showFunctionPanel = false
                                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                            },
                                            onShowRatioDialog = {
                                                lastFunctionPanelDismissAt = android.os.SystemClock.uptimeMillis()
                                                showFunctionPanel = false
                                                showRatioDialog = true
                                            },
                                            onShowStepsDialog = {
                                                lastFunctionPanelDismissAt = android.os.SystemClock.uptimeMillis()
                                                showFunctionPanel = false
                                                showStepsDialog = true
                                            },
                                            onShowParamsDialog = {
                                                lastFunctionPanelDismissAt = android.os.SystemClock.uptimeMillis()
                                                showFunctionPanel = false
                                                showParamsDialog = true
                                            },
                                            onClearContent = {
                                                onClearContent()
                                                lastFunctionPanelDismissAt = android.os.SystemClock.uptimeMillis()
                                                showFunctionPanel = false
                                            },
                                            currentImageSteps = currentImageSteps,
                                            selectedImageRatio = selectedImageRatio,
                                            maxHeight = functionPanelMaxHeight,
                                            isGptImage = detectedFamily == ImageGenCapabilities.ModelFamily.GPT_IMAGE,
                                            currentGptImageQuality = currentGptImageQuality,
                                            onShowQualityDialog = {
                                                lastFunctionPanelDismissAt = android.os.SystemClock.uptimeMillis()
                                                showFunctionPanel = false
                                                showGptQualityDialog = true
                                            }
                                        )
                                    }
                                }
                            }

                        }

                        // 输入框
                        val inputModifier = Modifier
                            .offset(x = -groupLeft)
                            .width(inputFieldWidth)
                            .align(Alignment.CenterStart)
                            .zIndex(1f)

                        val shadowedInputModifier = if (!isDarkTheme) {
                            inputModifier.diffuseShadow(
                                color = Color.Black,
                                alpha = 0.12f,
                                borderRadius = inputMinHeight / 2,
                                shadowRadius = 24.dp,
                                offsetY = 0.dp,
                                offsetX = 0.dp
                            )
                        } else {
                            inputModifier
                        }

                        BasicTextField(
                            value = localText,
                            onValueChange = { newText -> localText = newText },
                            modifier = shadowedInputModifier
                                .focusRequester(focusRequester)
                                .onFocusChanged { focusState ->
                                    isFocused = focusState.isFocused
                                    if (focusState.isFocused) onFocusChange(true)
                                },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(
                                if (isDarkTheme) Color(0xFF99CEFF) else Color(0xFF0285FF)
                            ),
                            maxLines = 5,
                            decorationBox = { innerTextField ->
                                val safeVerticalPadding = ((4f - 1f * sizeProgress).coerceAtLeast(0f)).dp
                                Column(
                                    modifier = Modifier
                                        .heightIn(min = inputMinHeight)
                                        .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                                        .background(inputBackground, inputShape)
                                        .border(1.dp, borderColor.copy(alpha = inputBorderAlpha), inputShape)
                                        .padding(start = textStartPadding, end = 5.dp, top = safeVerticalPadding, bottom = safeVerticalPadding)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = (inputMinHeight - safeVerticalPadding * 2).coerceAtLeast(0.dp)),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            if (localText.isEmpty()) {
                                                Text(
                                                    "输入消息...",
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = if (isDarkTheme) Color(0xFFAFAFAF) else Color(0xFF8F8F8F)
                                                )
                                            }
                                            innerTextField()
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        val buttonState = when {
                                            isApiCalling -> 2
                                            hasContent -> 1
                                            else -> 0
                                        }
                                        AnimatedContent(
                                            targetState = buttonState,
                                            transitionSpec = {
                                                (fadeIn(tween(220)) + scaleIn(
                                                    tween(220),
                                                    initialScale = 0.8f
                                                )).togetherWith(
                                                    fadeOut(tween(150)) + scaleOut(
                                                        tween(150),
                                                        targetScale = 0.6f
                                                    )
                                                )
                                            },
                                            label = "ImageGenSendButton"
                                        ) { state ->
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
                                                    painter = when (state) {
                                                        2 -> painterResource(R.drawable.ic_stop)
                                                        1 -> painterResource(R.drawable.ic_arrow_up)
                                                        else -> painterResource(R.drawable.ic_voice_bold)
                                                    },
                                                    contentDescription = when (state) {
                                                        2 -> "停止"
                                                        1 -> "发送"
                                                        else -> "语音输入"
                                                    },
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showStepsDialog && onChangeImageSteps != null) {
            var stepsValue by remember(currentImageSteps) { mutableFloatStateOf((currentImageSteps ?: 4).toFloat()) }
            var stepsText by remember(currentImageSteps) { mutableStateOf((currentImageSteps ?: 4).toString()) }
            val textFieldBorderColor = appDialogTextFieldBorderColor()
            val textFieldDefaultBorderColor = appDialogTextFieldDefaultBorderColor()

            AlertDialog(
                onDismissRequest = { showStepsDialog = false },
                modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
                shape = AppDialogShape,
                containerColor = appDialogContainerColor(),
                titleContentColor = appDialogContentColor(),
                textContentColor = appDialogContentColor(),
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
                                    focusedBorderColor = textFieldBorderColor,
                                    unfocusedBorderColor = textFieldDefaultBorderColor,
                                    disabledBorderColor = textFieldDefaultBorderColor.copy(alpha = 0.5f),
                                    cursorColor = textFieldBorderColor,
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

    if (showParamsDialog && onChangeImageParams != null) {
        var stepsText by remember(currentImageSteps) { mutableStateOf((currentImageSteps ?: 30).toString()) }
        var guidanceText by remember(currentImageGuidance) { mutableStateOf((currentImageGuidance ?: 7.5f).toString()) }

        AlertDialog(
            onDismissRequest = { showParamsDialog = false },
            modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
            shape = AppDialogShape,
            containerColor = appDialogContainerColor(),
            titleContentColor = appDialogContentColor(),
            textContentColor = appDialogContentColor(),
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
                        shape = AppDialogTextFieldShape,
                        placeholder = { Text("推荐值: 30") },
                        colors = appDialogTextFieldColors()
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
                        shape = AppDialogTextFieldShape,
                        placeholder = { Text("推荐值: 7.5") },
                        colors = appDialogTextFieldColors()
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

    if (showGptQualityDialog && onGptImageQualityChanged != null) {
        AlertDialog(
            onDismissRequest = { showGptQualityDialog = false },
            modifier = Modifier.border(1.dp, appDialogBorderColor(), AppDialogShape),
            shape = AppDialogShape,
            containerColor = appDialogContainerColor(),
            titleContentColor = appDialogContentColor(),
            textContentColor = appDialogContentColor(),
            title = { Text("选择图像质量") },
            text = {
                Column {
                    Text(
                        text = "质量越高生成越慢，费用越高",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    ImageGenCapabilities.getGptImageQualities().forEach { quality ->
                        val isSelected = quality == currentGptImageQuality
                        Surface(
                            onClick = {
                                onGptImageQualityChanged(quality)
                                showGptQualityDialog = false
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = quality.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showGptQualityDialog = false }) { Text("取消") }
            }
        )
    }

    if (showRatioDialog) {
        com.android.everytalk.ui.components.ImageRatioSelectionDialog(
            selectedRatio = selectedImageRatio,
            onRatioSelected = onImageRatioChanged,
            onDismiss = { showRatioDialog = false },
            allowedRatioNames = allowedRatioNames,
            family = detectedFamily,
            seedreamQuality = seedreamQuality,
            onQualityChange = { seedreamQuality = it },
            geminiImageSize = selectedApiConfig?.imageSize,
            onGeminiImageSizeChange = onGeminiImageSizeChanged
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

@Composable
private fun ImageFunctionPanelContent(
    supportsImageEditing: Boolean,
    hasContent: Boolean,
    isQwenEdit: Boolean,
    detectedFamily: ImageGenCapabilities.ModelFamily?,
    onChangeImageSteps: ((Int) -> Unit)?,
    onChangeImageParams: ((Int, Float) -> Unit)?,
    onOpenGallery: () -> Unit,
    onOpenCamera: () -> Unit,
    onShowRatioDialog: () -> Unit,
    onShowStepsDialog: () -> Unit,
    onShowParamsDialog: () -> Unit,
    onClearContent: () -> Unit,
    currentImageSteps: Int?,
    selectedImageRatio: ImageRatio,
    maxHeight: androidx.compose.ui.unit.Dp = 370.dp,
    isGptImage: Boolean = false,
    currentGptImageQuality: ImageGenCapabilities.GptImageQuality = ImageGenCapabilities.GptImageQuality.AUTO,
    onShowQualityDialog: () -> Unit = {}
) {
    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0xFF212121) else Color(0xFFFFFFFF)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color(0xFF0D0D0D).copy(alpha = 0.05f)
    val iconBg = if (isDark) Color(0xFF3B3B3B) else Color(0xFFE8E8E8)
    val textColor = if (isDark) Color.White else Color(0xFF0D0D0D)
    val iconTint = if (isDark) Color.White else Color(0xFF0D0D0D)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .shadow(8.dp, RoundedCornerShape(28.dp))
            .border(1.dp, borderColor, RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
        color = cardBg
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            if (!isQwenEdit) {
                ImageFunctionPanelRow(
                    iconRes = R.drawable.ic_aspect_ratio,
                    label = "比例: ${selectedImageRatio.displayName}",
                    iconBg = iconBg,
                    iconTint = Color(0xFF66B5FF),
                    textColor = textColor,
                    onClick = onShowRatioDialog
                )
            }
            if (isGptImage) {
                ImageFunctionPanelRow(
                    iconRes = R.drawable.ic_settings_slider,
                    label = "质量: ${currentGptImageQuality.displayName}",
                    iconBg = iconBg,
                    iconTint = Color(0xFF9C27B0),
                    textColor = textColor,
                    onClick = onShowQualityDialog
                )
            }
            if (supportsImageEditing) {
                ImageFunctionPanelRow(
                    iconRes = R.drawable.ic_image_gallery,
                    label = "选择图片",
                    iconBg = iconBg,
                    iconTint = Color(0xff2cb334),
                    textColor = textColor,
                    onClick = onOpenGallery
                )
                ImageFunctionPanelRow(
                    iconRes = R.drawable.ic_camera,
                    label = "拍照",
                    iconBg = iconBg,
                    iconTint = Color(0xFF2196F3),
                    textColor = textColor,
                    onClick = onOpenCamera
                )
            }
            if (detectedFamily == ImageGenCapabilities.ModelFamily.MODAL_Z_IMAGE && onChangeImageSteps != null) {
                ImageFunctionPanelRow(
                    iconRes = R.drawable.ic_tuning,
                    label = "步数: ${currentImageSteps ?: 4}",
                    iconBg = iconBg,
                    iconTint = Color(0xFF66B5FF),
                    textColor = textColor,
                    onClick = onShowStepsDialog
                )
            }
            if (isQwenEdit && onChangeImageParams != null) {
                ImageFunctionPanelRow(
                    iconRes = R.drawable.ic_tuning,
                    label = "参数调节",
                    iconBg = iconBg,
                    iconTint = Color(0xFFFF9800),
                    textColor = textColor,
                    onClick = onShowParamsDialog
                )
            }
            if (hasContent) {
                ImageFunctionPanelRow(
                    iconRes = R.drawable.ic_close,
                    label = "清除内容",
                    iconBg = iconBg,
                    iconTint = iconTint,
                    textColor = textColor,
                    onClick = onClearContent
                )
            }
        }
    }
}

@Composable
private fun ImageFunctionPanelRow(
    iconRes: Int,
    label: String,
    iconBg: Color,
    iconTint: Color,
    textColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(iconBg, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            fontSize = 18.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
            color = textColor,
            modifier = Modifier.weight(1f)
        )
    }
}
