package com.android.everytalk.ui.components

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL
import com.android.everytalk.util.storage.FileManager

/**
 * 全屏图片预览对话框（不依赖三方图片库；支持 http/https、content/file、data URI）
 */
@Composable
fun ImagePreviewDialog(
    url: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    val bitmapState by produceState<Result<Bitmap?>?>(initialValue = null, url) {
        value = runCatching {
            withContext(Dispatchers.IO) {
                loadBitmap(context = context, url = url)
            }
        }
    }

    val coroutineScope = rememberCoroutineScope()
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        // 检测是否为深色主题
        val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
        ) {
            when (val res = bitmapState) {
                null -> {
                    // 正在启动加载
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(36.dp)
                            .align(Alignment.Center),
                        color = Color.White
                    )
                }
                else -> {
                    val bmp = res.getOrNull()
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "preview image",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.Center)
                        )
                    } else {
                        Text(
                            text = "图片加载失败",
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }

            // 关闭按钮（右上角）
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 16.dp, top = 16.dp)
                    .size(48.dp)
                    .zIndex(2f)
                    .background(
                        color = Color.Black.copy(alpha = 0.35f),
                        shape = CircleShape
                    )
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = Color.White
                    )
                }
            }
            
            // 下载按钮（右下角）
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp)
                    .size(48.dp)
                    .zIndex(2f)
                    .background(
                        color = Color.Black.copy(alpha = 0.35f),
                        shape = CircleShape
                    )
            ) {
                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            saveImageToGallery(context, url)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "下载图片",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

private fun loadBitmap(context: android.content.Context, url: String): Bitmap? {
    return when {
        url.startsWith("data:", ignoreCase = true) -> {
            decodeDataUrlToBitmap(url)
        }
        url.startsWith("content://") || url.startsWith("file://") -> {
            context.contentResolver.openInputStream(Uri.parse(url)).use { input ->
                input?.let { BitmapFactory.decodeStream(it) }
            }
        }
        url.startsWith("http://") || url.startsWith("https://") -> {
            URL(url).openStream().use { input ->
                BitmapFactory.decodeStream(input)
            }
        }
        else -> {
            // 尝试将其当作文件路径
            runCatching {
                val uri = Uri.parse(url)
                val input: InputStream? = when {
                    uri.scheme.isNullOrBlank() -> java.io.File(url).inputStream()
                    else -> context.contentResolver.openInputStream(uri)
                }
                input.use { stream -> stream?.let { BitmapFactory.decodeStream(it) } }
            }.getOrNull()
        }
    }
}

private fun decodeDataUrlToBitmap(dataUrl: String): Bitmap? {
    // data:[<mediatype>][;base64],<data>
    val commaIndex = dataUrl.indexOf(',')
    if (commaIndex == -1) return null
    val meta = dataUrl.substring(0, commaIndex)
    val dataPart = dataUrl.substring(commaIndex + 1)
    val isBase64 = meta.contains(";base64", ignoreCase = true)
    val bytes: ByteArray = if (isBase64) {
        Base64.decode(dataPart, Base64.DEFAULT)
    } else {
        // URL encoded data
        java.net.URLDecoder.decode(dataPart, "UTF-8").toByteArray()
    }
    return ByteArrayInputStream(bytes).use { input -> BitmapFactory.decodeStream(input) }
}

/**
 * 保存图片到相册
 */
private suspend fun saveImageToGallery(context: Context, url: String) {
    withContext(Dispatchers.IO) {
        try {
            val fileManager = FileManager(context)
            val loaded = fileManager.loadBytesFromFlexibleSource(url)
            if (loaded == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "图片保存失败", Toast.LENGTH_SHORT).show()
                }
                return@withContext
            }
            val (bytes, mime) = loaded
            val savedUri = fileManager.saveBytesToMediaStore(
                bytes = bytes,
                mime = mime,
                displayNameBase = "everytalk"
            )

            withContext(Dispatchers.Main) {
                if (savedUri != null) {
                    Toast.makeText(context, "图片已保存到相册", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "图片保存失败", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // 在主线程显示错误提示
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "图片保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}