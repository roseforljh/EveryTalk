package com.example.everytalk.ui.screens.ImageGeneration

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
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.models.ImageSourceOption
import com.example.everytalk.models.SelectedMediaItem
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

private suspend fun checkFileSizeAndShowError(
    context: Context,
    uri: Uri,
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

@OptIn(ExperimentalMaterial3Api::class)
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
    editingMessage: Message?,
    onCancelEdit: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var showImageSelectionPanel by remember { mutableStateOf(false) }
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
        showImageSelectionPanel = !showImageSelectionPanel
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
                        onSendMessageRequest(text, selectedMediaItems.toList())
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
                if (editingMessage != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("正在编辑", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text(
                                text = editingMessage.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = onCancelEdit, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, "取消编辑", tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
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
                            if (focusState.isFocused) {
                                onFocusChange(true)
                            }
                        }
                        .padding(bottom = 4.dp),
                    placeholder = { Text("Enter a prompt...") },
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
                        IconButton(onClick = onToggleImagePanel) {
                            Icon(
                                Icons.Outlined.Image,
                                if (showImageSelectionPanel) "Close image options" else "Select image",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (text.isNotEmpty() || selectedMediaItems.isNotEmpty()) {
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
        }

        val yOffsetPx = -chatInputContentHeightPx.toFloat() - with(density) { 8.dp.toPx() }

        if (showImageSelectionPanel) {
            val iconButtonApproxWidth = 48.dp
            val columnStartPadding = 8.dp
            val imageButtonCenterX = columnStartPadding + (iconButtonApproxWidth / 2)
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
    }

    DisposableEffect(Unit) {
        onDispose {
            tempCameraImageUri?.let { uri ->
                safeDeleteTempFile(context, uri)
            }
        }
    }
}