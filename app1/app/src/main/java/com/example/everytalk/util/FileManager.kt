package com.example.everytalk.util

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
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
        private const val MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024 // 50MB 最大文件大小
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
     * 检查文件大小是否超过限制
     * @param uri 文件Uri
     * @return Pair<Boolean, Long> - 第一个值表示是否超过限制，第二个值是文件大小（字节）
     */
    suspend fun checkFileSize(uri: Uri): Pair<Boolean, Long> = withContext(Dispatchers.IO) {
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
            
            // 如果无法从cursor获取大小，尝试通过输入流获取
            if (fileSize <= 0) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        fileSize = inputStream.available().toLong()
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to get file size from input stream")
                }
            }
            
            val isOverLimit = fileSize > MAX_FILE_SIZE_BYTES
            if (isOverLimit) {
                logger.warn("File size $fileSize bytes exceeds limit $MAX_FILE_SIZE_BYTES bytes")
            }
            
            Pair(isOverLimit, fileSize)
        } catch (e: Exception) {
            logger.error("Error checking file size", e)
            // 如果无法检查大小，假设文件过大以确保安全
            Pair(true, 0L)
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
     * 从Uri加载并压缩位图
     * @param uri 图片Uri
     * @return 压缩后的位图，如果加载失败则返回null
     */
    suspend fun loadAndCompressBitmapFromUri(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        var bitmap: Bitmap? = null
        try {
            if (uri == Uri.EMPTY) return@withContext null
            
            // 首先检查文件大小
            val sizePair = checkFileSize(uri)
            val isOverLimit = sizePair.first
            val fileSize = sizePair.second
            if (isOverLimit) {
                logger.error("Image file size $fileSize bytes exceeds limit $MAX_FILE_SIZE_BYTES bytes")
                return@withContext null
            }
            
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            
            // 计算合适的采样率以避免内存问题
            options.inSampleSize = calculateInSampleSize(options, TARGET_IMAGE_WIDTH, TARGET_IMAGE_HEIGHT)
            options.inJustDecodeBounds = false
            options.inMutable = true
            options.inPreferredConfig = Bitmap.Config.RGB_565 // 使用更少内存的配置
            
            bitmap = context.contentResolver.openInputStream(uri)?.use {
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
                    try {
                        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                        if (scaledBitmap != bitmap) {
                            bitmap.recycle()
                        }
                        bitmap = scaledBitmap
                    } catch (e: OutOfMemoryError) {
                        // 如果缩放失败，使用原图但记录警告
                        logger.warn("Failed to scale bitmap due to memory constraints, using original size")
                        System.gc()
                    }
                }
            }
            
            bitmap
        } catch (e: OutOfMemoryError) {
            bitmap?.recycle()
            System.gc() // 建议垃圾回收
            logger.error("Out of memory while loading bitmap", e)
            null
        } catch (e: Exception) {
            bitmap?.recycle()
            logger.error("Failed to load and compress bitmap", e)
            null
        }
    }
    
    /**
     * 直接从网络URL下载并压缩位图
     */
    suspend fun loadAndCompressBitmapFromUrl(urlStr: String): Bitmap? = withContext(Dispatchers.IO) {
        if (urlStr.isBlank()) return@withContext null
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 30000
                readTimeout = 30000
                instanceFollowRedirects = true
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                logger.error("HTTP ${'$'}{conn.responseCode} while downloading image: ${'$'}urlStr")
                return@withContext null
            }

            // 读取为字节数组并限制最大大小
            val bos = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(8192)
                var n: Int
                var total = 0L
                while (input.read(buf).also { n = it } != -1) {
                    bos.write(buf, 0, n)
                    total += n
                    if (total > MAX_FILE_SIZE_BYTES) {
                        logger.warn("Image bytes exceed limit during download: ${'$'}total")
                        return@withContext null
                    }
                }
            }
            val bytes = bos.toByteArray()

            // 解析尺寸
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

            // 实际解码并压缩
            val opts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds, TARGET_IMAGE_WIDTH, TARGET_IMAGE_HEIGHT)
                inJustDecodeBounds = false
                inMutable = true
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)

            // 如有必要，再缩放一遍
            if (bitmap != null && (bitmap.width > TARGET_IMAGE_WIDTH || bitmap.height > TARGET_IMAGE_HEIGHT)) {
                val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
                val (newW, newH) = if (bitmap.width > bitmap.height) {
                    TARGET_IMAGE_WIDTH to (TARGET_IMAGE_WIDTH / aspect).toInt()
                } else {
                    (TARGET_IMAGE_HEIGHT * aspect).toInt() to TARGET_IMAGE_HEIGHT
                }
                if (newW > 0 && newH > 0) {
                    try {
                        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
                        if (scaled != bitmap) bitmap.recycle()
                        bitmap = scaled
                    } catch (_: OutOfMemoryError) {
                        logger.warn("OOM when scaling downloaded bitmap; using decoded size")
                        System.gc()
                    }
                }
            }
            bitmap
        } catch (e: Exception) {
            logger.error("Failed to download and compress bitmap from URL: ${'$'}urlStr", e)
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * 从 data:image/(any);base64,XXX 字符串解码位图
     */
    suspend fun loadBitmapFromDataUrl(dataUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val comma = dataUrl.indexOf(',')
            if (comma <= 0) return@withContext null
            val base64Part = dataUrl.substring(comma + 1)
            val bytes = Base64.decode(base64Part, Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds, TARGET_IMAGE_WIDTH, TARGET_IMAGE_HEIGHT)
                inJustDecodeBounds = false
                inPreferredConfig = Bitmap.Config.RGB_565
                inMutable = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            logger.error("Failed to decode bitmap from data URL", e)
            null
        }
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
            // 首先检查文件大小
            val (isOverLimit, fileSize) = checkFileSize(sourceUri)
            if (isOverLimit) {
                logger.error("File size $fileSize bytes exceeds limit $MAX_FILE_SIZE_BYTES bytes")
                return@withContext null
            }
            
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
            
            // 使用缓冲复制以避免内存问题
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                FileOutputStream(destinationFile).use { outputStream ->
                    val buffer = ByteArray(8192) // 8KB 缓冲区
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        
                        // 额外的安全检查，防止文件大小超出预期
                        if (totalBytesRead > MAX_FILE_SIZE_BYTES) {
                            logger.error("File size exceeded during copy: $totalBytesRead bytes")
                            destinationFile.delete()
                            return@withContext null
                        }
                    }
                    outputStream.flush()
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
        } catch (e: OutOfMemoryError) {
            logger.error("Out of memory while copying file", e)
            System.gc() // 建议垃圾回收
            null
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
     * 格式化文件大小为可读字符串
     * @param bytes 文件大小（字节）
     * @return 格式化后的文件大小字符串
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)}MB"
            else -> "${bytes / (1024 * 1024 * 1024)}GB"
        }
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