package com.example.everytalk.ui.screens.MainScreen.chat

import android.Manifest
import android.content.Context // Needed for FileProvider and createImageFileUri
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext // Needed for Context
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.example.everytalk.data.DataClass.ApiConfig
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.io.File // Needed for File
import java.text.SimpleDateFormat // Needed for SimpleDateFormat
import java.util.Date // Needed for Date
import java.util.Locale // Needed for Locale
import java.util.UUID // Needed for UUID

// Helper function to create an image file and its FileProvider URI
// This function can also be placed in AppViewModel or a utility class if it's reused.
private fun createImageFileUri(context: Context): Uri {
    val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val imageFileName = "JPEG_${timeStamp}_"
    // Use app's internal files directory for images
    val storageDir: File? = File(context.filesDir, "chat_images_temp") // Use a temp dir for new captures
    if (storageDir != null && !storageDir.exists()) {
        storageDir.mkdirs()
    }
    val imageFile = File.createTempFile(
        imageFileName, /* prefix */
        ".jpg",        /* suffix */
        storageDir      /* directory */
    )
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.provider", // Make sure this matches your AndroidManifest.xml
        imageFile
    )
}

// 定义图片来源选项的枚举
enum class ImageSourceOption(val label: String, val icon: ImageVector) {
    ALBUM("相册", Icons.Outlined.PhotoLibrary),
    CAMERA("相机", Icons.Outlined.PhotoCamera)
}

// 数据类，用于管理选中的图片，可以是Bitmap或Uri
sealed class SelectedMedia {
    data class FromUri(val uri: Uri) : SelectedMedia()
    data class FromBitmap(val bitmap: Bitmap) : SelectedMedia() // This will now mainly be for old logic or other cases
}


@Composable
fun ImageSelectionPanel(
    modifier: Modifier = Modifier,
    onOptionSelected: (ImageSourceOption) -> Unit,
    onDismissRequest: () -> Unit
) {
    var activeOption by remember { mutableStateOf<ImageSourceOption?>(null) }
    val panelBackgroundColor = Color(0xFFf4f4f4)
    val darkerBackgroundColor = Color(0xFFCCCCCC)

    Surface(
        modifier = modifier
            .width(150.dp),
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
                    Text(
                        text = option.label,
                        color = Color.Black,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
fun SelectedImagePreviewItem(
    media: SelectedMedia,
    onRemoveClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(80.dp) // 预览图大小
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp))
    ) {
        // Coil can load both Uri and Bitmap (through its ImageRequest Builder).
        // It's generally better to use AsyncImage for both for consistent loading.
        // If media is FromBitmap, convert to Uri if possible, or use AsyncImage's Bitmap loading capability.
        // For simplicity, we keep Image for Bitmap for now.
        when (media) {
            is SelectedMedia.FromUri -> {
                AsyncImage(
                    model = media.uri,
                    contentDescription = "Selected image from gallery",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            is SelectedMedia.FromBitmap -> {
                // AsyncImage can also take a Bitmap directly for display
                AsyncImage( // Changed from Image to AsyncImage for consistency
                    model = media.bitmap, // AsyncImage can directly load a Bitmap
                    contentDescription = "Selected image from camera",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
        IconButton(
            onClick = onRemoveClicked,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(20.dp) // 叉叉按钮大小
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove image",
                tint = Color.White,
                modifier = Modifier.size(14.dp) // 叉叉图标大小
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInputArea(
    text: String,
    onTextChange: (String) -> Unit,
    onSendMessageRequest: (messageText: String, isKeyboardVisible: Boolean, images: List<SelectedMedia>) -> Unit,
    isApiCalling: Boolean,
    isWebSearchEnabled: Boolean,
    onToggleWebSearch: () -> Unit,
    onClearText: () -> Unit,
    onStopApiCall: () -> Unit,
    focusRequester: FocusRequester,
    selectedApiConfig: ApiConfig?,
    onShowSnackbar: (String) -> Unit,
    imeInsets: WindowInsets,
    density: Density,
    keyboardController: SoftwareKeyboardController?,
    onFocusChange: (isFocused: Boolean) -> Unit
) {
    val context = LocalContext.current // Get Context here
    var pendingMessageTextForSend by remember { mutableStateOf<String?>(null) }
    var isTuneModeEnabled by remember { mutableStateOf(false) }
    var showImageSelectionPanel by remember { mutableStateOf(false) }

    val selectedImages = remember { mutableStateListOf<SelectedMedia>() }
    var tempCameraImageUri by remember { mutableStateOf<Uri?>(null) } // To store the URI for camera capture

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri: Uri? ->
            if (uri != null) {
                Log.d("PhotoPicker", "Selected URI: $uri")
                selectedImages.add(SelectedMedia.FromUri(uri)) // Still FromUri for gallery
            } else {
                Log.d("PhotoPicker", "No media selected")
            }
            showImageSelectionPanel = false
        }
    )

    // Modified camera launcher to use TakePicture (for full image URI)
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(), // <<< Use TakePicture
        onResult = { success: Boolean ->
            if (success && tempCameraImageUri != null) {
                Log.d("Camera", "Image captured and saved to URI: $tempCameraImageUri")
                selectedImages.add(SelectedMedia.FromUri(tempCameraImageUri!!)) // Store as FromUri
                tempCameraImageUri = null // Reset temp URI
            } else {
                Log.d("Camera", "Image capture failed or URI was null.")
                onShowSnackbar("无法获取相机照片")
                tempCameraImageUri = null // Reset temp URI
            }
            showImageSelectionPanel = false
        }
    )

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted: Boolean ->
            if (isGranted) {
                Log.d("Permission", "Camera permission granted")
                val newCameraUri = createImageFileUri(context) // <<< Use helper to create URI
                tempCameraImageUri = newCameraUri // Assign to the state variable
                cameraLauncher.launch(newCameraUri) // <<< Launch camera, pass this local URI
            } else {
                Log.d("Permission", "Camera permission denied")
                onShowSnackbar("相机权限被拒绝")
                showImageSelectionPanel = false
            }
        }
    )

    LaunchedEffect(Unit) {
        snapshotFlow { imeInsets.getBottom(density) > 0 }
            .distinctUntilChanged()
            .filter { isKeyboardVisible -> !isKeyboardVisible && (pendingMessageTextForSend != null || selectedImages.isNotEmpty()) }
            .collect {
                val messageToSend = pendingMessageTextForSend ?: text
                Log.d("ChatInputArea", "Keyboard hidden, sending pending message: $messageToSend with ${selectedImages.size} images")
                onSendMessageRequest(messageToSend, false, selectedImages.toList()) // Pass selectedImages here
                pendingMessageTextForSend = null
                if (text == messageToSend) onTextChange("")
                selectedImages.clear() // Clear after sending
            }
    }

    Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .shadow(
                    elevation = 6.dp,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    clip = false
                )
                .background(
                    Color.White,
                    RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                )
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
        ) {
            if (selectedImages.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(selectedImages, key = { index, item ->
                        when(item) {
                            is SelectedMedia.FromBitmap -> "bitmap_$index"
                            is SelectedMedia.FromUri -> item.uri.toString()
                        }
                    }) { index, media ->
                        SelectedImagePreviewItem(
                            media = media,
                            onRemoveClicked = {
                                selectedImages.removeAt(index)
                            }
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
                minLines = 1, maxLines = 5,
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
                            imageVector = if (isWebSearchEnabled) Icons.Outlined.TravelExplore else Icons.Filled.Language,
                            contentDescription = if (isWebSearchEnabled) "网页搜索已开启" else "网页搜索已关闭",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(25.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            showImageSelectionPanel = !showImageSelectionPanel
                            if (showImageSelectionPanel) keyboardController?.hide()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Image,
                            contentDescription = if (showImageSelectionPanel) "关闭图片选项" else "选择图片",
                            tint = Color(0xff2cb334),
                            modifier = Modifier.size(25.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { isTuneModeEnabled = !isTuneModeEnabled }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = if (isTuneModeEnabled) "关闭更多选项" else "更多选项",
                            tint = Color(0xfff76213),
                            modifier = Modifier.size(25.dp)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (text.isNotEmpty() || selectedImages.isNotEmpty()) {
                        IconButton(onClick = {
                            onTextChange("")
                            selectedImages.clear()
                        }) {
                            Icon(
                                Icons.Filled.Clear, "清除内容和图片",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    FilledIconButton(
                        onClick = {
                            if (isApiCalling) {
                                onStopApiCall()
                            } else if ((text.isNotBlank() || selectedImages.isNotEmpty()) && selectedApiConfig != null) {
                                val isKeyboardCurrentlyVisible = imeInsets.getBottom(density) > 0
                                if (isKeyboardCurrentlyVisible) {
                                    pendingMessageTextForSend = text
                                    onTextChange("")
                                    keyboardController?.hide()
                                } else {
                                    onSendMessageRequest(text, false, selectedImages.toList())
                                    onTextChange("")
                                    selectedImages.clear()
                                }
                            } else if (selectedApiConfig == null) {
                                onShowSnackbar("请先选择 API 配置")
                            } else {
                                onShowSnackbar("请输入消息内容或选择图片")
                            }
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

        val estimatedInputAreaContentHeight = 120.dp
        val panelVerticalMarginFromTopInput = 8.dp
        val previewHeight = if (selectedImages.isNotEmpty()) 80.dp + 8.dp else 0.dp

        AnimatedVisibility(
            visible = showImageSelectionPanel,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp)
                .offset(y = -(estimatedInputAreaContentHeight + panelVerticalMarginFromTopInput + previewHeight)),
            enter = fadeIn(animationSpec = tween(durationMillis = 200)),
            exit = fadeOut(animationSpec = tween(durationMillis = 150))
        ) {
            ImageSelectionPanel(
                onOptionSelected = { selectedOption ->
                    when (selectedOption) {
                        ImageSourceOption.ALBUM -> {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                        ImageSourceOption.CAMERA -> {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                },
                onDismissRequest = {
                    showImageSelectionPanel = false
                }
            )
        }
    }
}