package com.example.everytalk.util

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 统一的文件管理类，用于处理所有文件操作
 * 减少文件处理的冗余代码
 */
class FileManager(private val context: Context) {
    private val logger = AppLogger.forComponent("FileManager")
    
    companion object {
        private const val CHAT_ATTACHMENTS_DIR = "chat_attachments"
        private const val MAX_IMAGE_SIZE_BYTES = 4 * 1024 * 1024
        private const val TARGET_IMAGE_WIDTH = 1024
        private const val TARGET_IMAGE_HEIGHT = 1024
        private const val JPEG_COMPRESSION_QUALITY = 80
    }
    
    /**
     * 获取聊天附件目录
     * @return 聊天附件目录
     */
    private fun getChatAttachmentsDir(): File {
        val dir = File(context.filesDir, CHAT_ATTACHMENTS_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            logger.error("Failed to create chat attachments directory")
        }
        return dir
    }
    
    /**
     * 从Uri加载并压缩位图
     * @param uri 图片Uri
     * @return 压缩后的位图，如果加载失败则返回null
     */
    suspend fun loadAndCompressBitmapFromUri(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (uri == Uri.EMPTY) return@withContext null
            
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            
            options.inSampleSize = calculateInSampleSize(options, TARGET_IMAGE_WIDTH, TARGET_IMAGE_HEIGHT)
            options.inJustDecodeBounds = false
            options.inMutable = true
            
            var bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            
            if (bitmap != null && (bitmap.width > TARGET_IMAGE_WIDTH || bitmap.height > TARGET_IMAGE_HEIGHT)) {
                val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val newWidth: Int
                val newHeight: Int
                
                if (bitmap.width > bitmap.height) {
                    newWidth = TARGET_IMAGE_WIDTH
                    newHeight = (newWidth / aspectRatio).toInt()
                } else {
                    newHeight = TARGET_IMAGE_HEIGHT
                    newWidth = (newHeight * aspectRatio).toInt()
                }
                
                if (newWidth > 0 && newHeight > 0) {
                    val scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                    if (scaledBitmap != bitmap) {
                        bitmap.recycle()
                    }
                    bitmap = scaledBitmap
                }
            }
            
            bitmap
        } catch (e: Exception) {
            logger.error("Failed to load and compress bitmap", e)
            null
        }
    }
    
    /**
     * 计算图片采样大小
     * @param options BitmapFactory.Options
     * @param reqWidth 目标宽度
     * @param reqHeight 目标高度
     * @return 采样大小
     */
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
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
    
    /**
     * 将Uri复制到应用内部存储
     * @param sourceUri 源Uri
     * @param messageIdHint 消息ID提示
     * @param attachmentIndex 附件索引
     * @param originalFileName 原始文件名
     * @return 复制后的文件路径，如果复制失败则返回null
     */
    suspend fun copyUriToAppInternalStorage(
        sourceUri: Uri,
        messageIdHint: String,
        attachmentIndex: Int,
        originalFileName: String?
    ): String? = withContext(Dispatchers.IO) {
        try {
            val MimeTypeMap = android.webkit.MimeTypeMap.getSingleton()
            val contentType = context.contentResolver.getType(sourceUri)
            val extension = MimeTypeMap.getExtensionFromMimeType(contentType)
                ?: originalFileName?.substringAfterLast('.', "")
                ?: "bin"
            
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val safeOriginalName = originalFileName?.replace("[^a-zA-Z0-9._-]".toRegex(), "_")?.take(30) ?: "file"
            val uniqueFileName = "${safeOriginalName}_${messageIdHint}_${attachmentIndex}_${timeStamp}_${
                UUID.randomUUID().toString().take(4)
            }.$extension"
            
            val attachmentDir = getChatAttachmentsDir()
            val destinationFile = File(attachmentDir, uniqueFileName)
            
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                FileOutputStream(destinationFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: run {
                logger.error("Failed to open input stream for URI: $sourceUri")
                return@withContext null
            }
            
            if (!destinationFile.exists() || destinationFile.length() == 0L) {
                if (destinationFile.exists()) destinationFile.delete()
                logger.error("Destination file is empty or does not exist: ${destinationFile.absolutePath}")
                return@withContext null
            }
            
            logger.debug("File copied successfully: ${destinationFile.absolutePath}")
            destinationFile.absolutePath
        } catch (e: Exception) {
            logger.error("Failed to copy URI to internal storage", e)
            null
        }
    }
    
    /**
     * 将位图保存到应用内部存储
     * @param bitmapToSave 要保存的位图
     * @param messageIdHint 消息ID提示
     * @param attachmentIndex 附件索引
     * @param originalFileNameHint 原始文件名提示
     * @return 保存后的文件路径，如果保存失败则返回null
     */
    suspend fun saveBitmapToAppInternalStorage(
        bitmapToSave: Bitmap,
        messageIdHint: String,
        attachmentIndex: Int,
        originalFileNameHint: String? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            if (bitmapToSave.isRecycled) {
                logger.error("Cannot save recycled bitmap")
                return@withContext null
            }
            
            val outputStream = ByteArrayOutputStream()
            val fileExtension: String
            val compressFormat = if (bitmapToSave.hasAlpha()) {
                fileExtension = "png"; Bitmap.CompressFormat.PNG
            } else {
                fileExtension = "jpg"; Bitmap.CompressFormat.JPEG
            }
            
            bitmapToSave.compress(compressFormat, JPEG_COMPRESSION_QUALITY, outputStream)
            val bytes = outputStream.toByteArray()
            
            if (!bitmapToSave.isRecycled) {
                bitmapToSave.recycle()
            }
            
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val baseName = originalFileNameHint?.substringBeforeLast('.')
                ?.replace("[^a-zA-Z0-9._-]".toRegex(), "_")?.take(20) ?: "IMG"
            val uniqueFileName = "${baseName}_${messageIdHint}_${attachmentIndex}_${timeStamp}_${
                UUID.randomUUID().toString().take(4)
            }.$fileExtension"
            
            val attachmentDir = getChatAttachmentsDir()
            val destinationFile = File(attachmentDir, uniqueFileName)
            
            FileOutputStream(destinationFile).use { it.write(bytes) }
            
            if (!destinationFile.exists() || destinationFile.length() == 0L) {
                if (destinationFile.exists()) destinationFile.delete()
                logger.error("Saved bitmap file is empty or does not exist: ${destinationFile.absolutePath}")
                return@withContext null
            }
            
            logger.debug("Bitmap saved successfully: ${destinationFile.absolutePath}")
            destinationFile.absolutePath
        } catch (e: Exception) {
            logger.error("Failed to save bitmap to internal storage", e)
            null
        }
    }
    
    /**
     * 删除文件
     * @param paths 要删除的文件路径列表
     * @return 成功删除的文件数量
     */
    suspend fun deleteFiles(paths: List<String>): Int = withContext(Dispatchers.IO) {
        var deletedCount = 0
        
        paths.forEach { path ->
            try {
                val file = File(path)
                if (file.exists()) {
                    if (file.delete()) {
                        logger.debug("Successfully deleted file: $path")
                        deletedCount++
                    } else {
                        logger.warn("Failed to delete file: $path")
                    }
                } else {
                    logger.warn("File to delete does not exist: $path")
                }
            } catch (e: Exception) {
                logger.error("Error deleting file: $path", e)
            }
        }
        
        deletedCount
    }
    
    /**
     * 获取文件名
     * @param uri 文件Uri
     * @return 文件名，如果无法获取则返回默认名称
     */
    fun getFileName(uri: Uri): String? {
        if (uri == Uri.EMPTY) return null
        
        var fileName: String? = null
        try {
            if (ContentResolver.SCHEME_CONTENT == uri.scheme) {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val displayNameIndex = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                            fileName = cursor.getString(displayNameIndex)
                        }
                    }
            }
            
            if (fileName == null) {
                fileName = uri.lastPathSegment
            }
        } catch (e: Exception) {
            logger.error("Error getting file name from URI", e)
            fileName = uri.lastPathSegment
        }
        
        return fileName ?: "file_${System.currentTimeMillis()}"
    }
    
    /**
     * 获取文件提供者Uri
     * @param file 文件
     * @return 文件提供者Uri
     */
    fun getFileProviderUri(file: File): Uri {
        val authority = "${context.packageName}.provider"
        return FileProvider.getUriForFile(context, authority, file)
    }
}