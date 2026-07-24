package com.android.everytalk.util.storage

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import androidx.core.net.toUri
import com.android.everytalk.data.network.SafeHttpDownloader
import com.android.everytalk.util.AppLogger
import com.android.everytalk.util.image.ImageScaleCalculator
import com.android.everytalk.util.image.ImageScaleConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 统一的文件管理类，用于处理所有文件操作
 * 减少文件处理的冗余代码
 */
internal fun guessExtensionFromMime(mime: String?): String {
        val normalized = (mime ?: "").substringBefore(';').trim().lowercase()
        return when (normalized) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/heic" -> "heic"
            "image/heif" -> "heif"
            "image/avif" -> "avif"
            else -> "bin"
        }
    }

    /**
     * 获取聊天附件目录
     * @return 聊天附件目录
     */
internal fun FileManager.getChatAttachmentsDir(): File {
        val dir = File(context.filesDir, FileManager.CHAT_ATTACHMENTS_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            logger.error("Failed to create chat attachments directory")
        }
        return dir
    }
    
    /**
     * 根据使用场景获取图片缩放配置
     * @param isImageGeneration 是否为图像生成模式
     * @return 对应的图片缩放配置
     */
internal fun FileManager.getImageConfigForMode(isImageGeneration: Boolean): ImageScaleConfig {
        return if (isImageGeneration) ImageScaleConfig.IMAGE_GENERATION_MODE else ImageScaleConfig.CHAT_MODE
    }
internal fun FileManager.calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1
        
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        
        return inSampleSize
    }
