package com.android.everytalk.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL

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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
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
                            contentScale = ContentScale.Fit,
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

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = Color.White
                )
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