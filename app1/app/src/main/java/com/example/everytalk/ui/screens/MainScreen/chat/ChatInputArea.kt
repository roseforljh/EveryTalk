package com.example.everytalk.ui.screens.MainScreen.chat

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.example.everytalk.model.ImageSourceOption
import com.example.everytalk.model.MoreOptionsType
import com.example.everytalk.model.SelectedMediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

private fun getFileDetailsFromUri(context: Context, uri: Uri): Triple<String, String?, String?> {
    var displayName: String? = null
    val mimeType: String? = context.contentResolver.getType(uri)
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    displayName = cursor.getString(nameIndex)
                }
            }
        }
    } catch (e: Exception) {
        Log.e("FileDetails", "Error querying URI metadata: $uri", e)
    }
    if (displayName == null) {
        displayName = uri.lastPathSegment ?: "Unknown File"
    }
    return Triple(displayName ?: "Unknown File", mimeType, uri.toString())
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
            ImageSourceOption.values().forEach { option ->
                val isSelected = activeOption == option
                val animatedBackgroundColor by animateColorAsState(
                    targetValue = if (isSelected) darkerBackgroundColor else panelBackgroundColor,
                    animationSpec = tween(durationMillis = 200),
                    label = "ImageOptionPanelItemBackground"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            activeOption = option
                            onOptionSelected(option)
                        }
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
            MoreOptionsType.values().forEach { option ->
                val isSelected = activeOption == option
                val animatedBackgroundColor by animateColorAsState(
                    targetValue = if (isSelected) darkerBackgroundColor else panelBackgroundColor,
                    animationSpec = tween(durationMillis = 200),
                    label = "MoreOptionPanelItemBackground"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            activeOption = option
                            onOptionSelected(option)
                        }
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
                contentScale = ContentScale.Crop
            )

            is SelectedMediaItem.ImageFromBitmap -> AsyncImage(
                model = mediaItem.bitmap,
                contentDescription = "Selected image from camera",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
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
    onSendMessageRequest: (messageText: String, isKeyboardVisible: Boolean, attachments: List<SelectedMediaItem>) -> Unit,
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
    onFocusChange: (isFocused: Boolean) -> Unit
) {
    val context = LocalContext.current
    var pendingMessageTextForSend by remember { mutableStateOf<String?>(null) }
    var showImageSelectionPanelIntent by remember { mutableStateOf(false) }
    var isImageSelectionPopupComposed by remember { mutableStateOf(false) }
    var imageSelectionPanelAnimatedVisible by remember { mutableStateOf(false) }
    var showMoreOptionsPanelIntent by remember { mutableStateOf(false) }
    var isMoreOptionsPopupComposed by remember { mutableStateOf(false) }
    var moreOptionsPanelAnimatedVisible by remember { mutableStateOf(false) }
    val selectedMediaItems = remember { mutableStateListOf<SelectedMediaItem>() }
    var tempCameraImageUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(showImageSelectionPanelIntent) {
        if (showImageSelectionPanelIntent) {
            isImageSelectionPopupComposed = true; imageSelectionPanelAnimatedVisible = true
        } else {
            imageSelectionPanelAnimatedVisible = false
        }
    }
    LaunchedEffect(showMoreOptionsPanelIntent) {
        if (showMoreOptionsPanelIntent) {
            isMoreOptionsPopupComposed = true; moreOptionsPanelAnimatedVisible = true
        } else {
            moreOptionsPanelAnimatedVisible = false
        }
    }
    val imagePopupDismissRequestHandler = { showImageSelectionPanelIntent = false }
    val moreOptionsPopupDismissRequestHandler = { showMoreOptionsPanelIntent = false }

    val photoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { selectedMediaItems.add(SelectedMediaItem.ImageFromUri(it)) }
            showImageSelectionPanelIntent = false
        }
    val cameraLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && tempCameraImageUri != null) {
                selectedMediaItems.add(SelectedMediaItem.ImageFromUri(tempCameraImageUri!!))
                tempCameraImageUri = null
            } else {
                onShowSnackbar("无法获取相机照片"); tempCameraImageUri = null
            }
            showImageSelectionPanelIntent = false
        }
    val cameraPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                val newUri = createImageFileUri(context); tempCameraImageUri =
                    newUri; cameraLauncher.launch(newUri)
            } else {
                onShowSnackbar("相机权限被拒绝"); showImageSelectionPanelIntent = false
            }
        }

    // --- MODIFIED: Use OpenDocument for filePickerLauncher ---
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(), // Changed to OpenDocument
        onResult = { uri: Uri? ->
            uri?.let {
                val (displayName, mimeType, _) = getFileDetailsFromUri(context, it)
                Log.d("OpenDocument", "Selected Document: $displayName, URI: $it, MIME: $mimeType")
                selectedMediaItems.add(SelectedMediaItem.GenericFile(it, displayName, mimeType))
            } ?: Log.d("OpenDocument", "No document selected")
            showMoreOptionsPanelIntent = false
        }
    )
    // --- END OF MODIFICATION ---

    LaunchedEffect(Unit) {
        snapshotFlow { imeInsets.getBottom(density) > 0 }
            .distinctUntilChanged()
            .filter { isKeyboardVisible -> !isKeyboardVisible && (pendingMessageTextForSend != null || selectedMediaItems.isNotEmpty()) }
            .collect {
                val messageToSend = pendingMessageTextForSend ?: text
                onSendMessageRequest(messageToSend, false, selectedMediaItems.toList())
                pendingMessageTextForSend = null
                if (text == messageToSend) onTextChange("")
                selectedMediaItems.clear()
            }
    }
    var chatInputContentHeightPx by remember { mutableStateOf(0) }
    val panelVerticalMarginFromTopInput = 16.dp

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
                            onRemoveClicked = { selectedMediaItems.removeAt(index) })
                    }
                }
            }
            OutlinedTextField(
                value = text, onValueChange = onTextChange,
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
                minLines = 1, maxLines = 5, shape = RoundedCornerShape(16.dp)
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
                    IconButton(onClick = {
                        if (showMoreOptionsPanelIntent) showMoreOptionsPanelIntent =
                            false; showImageSelectionPanelIntent =
                        !showImageSelectionPanelIntent; if (showImageSelectionPanelIntent) keyboardController?.hide()
                    }) {
                        Icon(
                            Icons.Outlined.Image,
                            if (showImageSelectionPanelIntent) "关闭图片选项" else "选择图片",
                            tint = Color(0xff2cb334),
                            modifier = Modifier.size(25.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = {
                        if (showImageSelectionPanelIntent) showImageSelectionPanelIntent =
                            false; showMoreOptionsPanelIntent =
                        !showMoreOptionsPanelIntent; if (showMoreOptionsPanelIntent) keyboardController?.hide()
                    }) {
                        Icon(
                            Icons.Filled.Tune,
                            if (showMoreOptionsPanelIntent) "关闭更多选项" else "更多选项",
                            tint = Color(0xfff76213),
                            modifier = Modifier.size(25.dp)
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (text.isNotEmpty() || selectedMediaItems.isNotEmpty()) {
                        IconButton(onClick = { onTextChange(""); selectedMediaItems.clear() }) {
                            Icon(
                                Icons.Filled.Clear,
                                "清除内容和所选项目",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    FilledIconButton(
                        onClick = {
                            if (isApiCalling) onStopApiCall()
                            else if ((text.isNotBlank() || selectedMediaItems.isNotEmpty()) && selectedApiConfig != null) {
                                if (imeInsets.getBottom(density) > 0) {
                                    pendingMessageTextForSend = text; keyboardController?.hide()
                                } else {
                                    onSendMessageRequest(
                                        text,
                                        false,
                                        selectedMediaItems.toList()
                                    ); onTextChange(""); selectedMediaItems.clear()
                                }
                            } else if (selectedApiConfig == null) onShowSnackbar("请先选择 API 配置")
                            else onShowSnackbar("请输入消息内容或选择项目")
                        },
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
            -(chatInputContentHeightPx + with(density) { panelVerticalMarginFromTopInput.toPx() })
        if (isImageSelectionPopupComposed) {
            val xOffsetPx = with(density) { 8.dp.toPx() }
            Popup(
                alignment = Alignment.BottomStart,
                offset = IntOffset(xOffsetPx.toInt(), yOffsetPx.toInt()),
                onDismissRequest = imagePopupDismissRequestHandler,
                properties = PopupProperties(
                    focusable = true,
                    dismissOnClickOutside = true,
                    dismissOnBackPress = true
                )
            ) {
                AnimatedVisibility(
                    visible = imageSelectionPanelAnimatedVisible,
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(150))
                ) {
                    ImageSelectionPanel { selectedOption ->
                        when (selectedOption) {
                            ImageSourceOption.ALBUM -> photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )

                            ImageSourceOption.CAMERA -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                }
                LaunchedEffect(imageSelectionPanelAnimatedVisible) {
                    if (!imageSelectionPanelAnimatedVisible) {
                        delay(150L); if (!showImageSelectionPanelIntent) isImageSelectionPopupComposed =
                            false
                    }
                }
            }
        }
        if (isMoreOptionsPopupComposed) {
            val iconButtonApproxWidth = 48.dp;
            val spacerWidth = 8.dp;
            val columnStartPadding = 8.dp
            val tuneButtonCenterX =
                columnStartPadding + iconButtonApproxWidth + spacerWidth + iconButtonApproxWidth + spacerWidth + (iconButtonApproxWidth / 2)
            val panelWidthDp = 150.dp;
            val xOffsetForPopup = tuneButtonCenterX - (panelWidthDp / 2)
            val xOffsetForMoreOptionsPanelPx = with(density) { xOffsetForPopup.toPx() }
            Popup(
                alignment = Alignment.BottomStart,
                offset = IntOffset(xOffsetForMoreOptionsPanelPx.toInt(), yOffsetPx.toInt()),
                onDismissRequest = moreOptionsPopupDismissRequestHandler,
                properties = PopupProperties(
                    focusable = true,
                    dismissOnClickOutside = true,
                    dismissOnBackPress = true
                )
            ) {
                AnimatedVisibility(
                    visible = moreOptionsPanelAnimatedVisible,
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(150))
                ) {
                    MoreOptionsPanel { selectedOption ->
                        Log.d(
                            "MoreOptionsPanel",
                            "Selected: ${selectedOption.label}, Launching with MIME types: ${selectedOption.mimeTypes.joinToString()}"
                        )
                        // --- MODIFIED: Launch filePickerLauncher (OpenDocument) with Array<String> ---
                        filePickerLauncher.launch(selectedOption.mimeTypes)
                        // --- END OF MODIFICATION ---
                    }
                }
                LaunchedEffect(moreOptionsPanelAnimatedVisible) {
                    if (!moreOptionsPanelAnimatedVisible) {
                        delay(150L); if (!showMoreOptionsPanelIntent) isMoreOptionsPopupComposed =
                            false
                    }
                }
            }
        }
    }
}