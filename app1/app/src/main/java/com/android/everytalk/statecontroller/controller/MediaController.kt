package com.android.everytalk.statecontroller.controller

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.util.FileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MediaController
 * 抽离图片下载与保存逻辑，提供面向 ViewModel 的简洁接口。
 */
class MediaController(
    private val application: Application,
    private val fileManager: FileManager,
    private val scope: CoroutineScope,
    private val showSnackbar: (String) -> Unit
) {

    private suspend fun showToast(message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(application, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun downloadImage(url: String) {
        scope.launch {
            try {
                // 原样字节读取（支持 data:image;base64 / http(s) / content:// / file:// / 绝对路径）
                val loaded = fileManager.loadBytesFromFlexibleSource(url)
                if (loaded == null) {
                    showToast("无法获取原始图片数据")
                    return@launch
                }
                val (bytes, mime) = loaded

                // 1) 首先落地到应用内部存储（会话占用空间，原样保存，不重编码）
                val internalPath = fileManager.saveBytesToInternalImages(
                    bytes = bytes,
                    mime = mime,
                    baseName = "EveryTalk_Image",
                    messageIdHint = System.currentTimeMillis().toString().takeLast(6),
                    index = 0
                )

                // 2) 同步保存到系统媒体库（用户下载到相册/下载目录，仍保持原 MIME 与扩展名）
                val savedUri = fileManager.saveBytesToMediaStore(
                    bytes = bytes,
                    mime = mime,
                    displayNameBase = "EveryTalk_Image"
                )

                when {
                    !internalPath.isNullOrBlank() && savedUri != null ->
                        showToast("原图已保存：应用空间与相册")
                    savedUri != null ->
                        showToast("原图已保存到相册")
                    !internalPath.isNullOrBlank() ->
                        showToast("原图已保存到应用空间")
                    else ->
                        showToast("保存失败：无法写入存储")
                }
            } catch (e: Exception) {
                Log.e("MediaController", "原图保存失败", e)
                showToast("保存失败: ${e.message}")
            }
        }
    }

    fun downloadImageFromMessage(message: Message) {
        val source = message.imageUrls?.firstOrNull()
        if (source == null) {
            scope.launch { showToast("没有可下载的图片") }
            return
        }
        downloadImage(source)
    }

    fun saveBitmapToDownloads(bitmap: Bitmap) {
        scope.launch(Dispatchers.IO) {
            val contentResolver = application.contentResolver
            val imageCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val contentDetails = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "EveryTalk_Image_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val imageUri: Uri? = contentResolver.insert(imageCollection, contentDetails)
            imageUri?.let {
                try {
                    contentResolver.openOutputStream(it).use { outputStream ->
                        if (outputStream != null) {
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                        } else {
                            throw Exception("无法打开输出流")
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentDetails.clear()
                        contentDetails.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(it, contentDetails, null, null)
                    }
                    showToast("图片已保存")
                } catch (e: Exception) {
                    Log.e("MediaController", "保存图片失败", e)
                    contentResolver.delete(it, null, null) // 清理失败的条目
                    showToast("保存失败: ${e.message}")
                }
            } ?: run {
                // throw Exception("无法创建MediaStore条目")
                showToast("无法创建MediaStore条目")
            }
        }
    }
}