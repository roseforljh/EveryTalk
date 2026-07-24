package com.android.everytalk.ui.screens.MainScreen.chat.text.ui
import com.android.everytalk.statecontroller.*

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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.res.painterResource
import com.android.everytalk.R
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.FileProvider
import com.android.everytalk.data.DataClass.ApiConfig
import com.android.everytalk.models.ImageSourceOption
import com.android.everytalk.models.MoreOptionsType
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.components.modifier.diffuseShadow
import kotlinx.coroutines.CancellationException
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

internal fun chatInputPopupPositionProvider(
    contentHeightPx: Int,
    density: Density,
): PopupPositionProvider = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val marginPx = with(density) { 8.dp.roundToPx() }
        val x = ((windowSize.width - popupContentSize.width) / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (windowSize.height - contentHeightPx - marginPx - popupContentSize.height)
            .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}

internal fun createImageFileUri(context: Context): Uri {
    val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val imageFileName = "JPEG_${timeStamp}_"
    val storageDir: File? = File(context.filesDir, "chat_images_temp")
    if (storageDir != null && !storageDir.exists()) {
        storageDir.mkdirs()
    }
    val imageFile = File.createTempFile(imageFileName, ".jpg", storageDir)
    return FileProvider.getUriForFile(context, "${context.packageName}.provider", imageFile)
}

internal suspend fun getFileDetailsFromUri(
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

internal suspend fun checkFileSizeAndShowError(
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

internal fun safeDeleteTempFile(context: Context, uri: Uri?) {
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

