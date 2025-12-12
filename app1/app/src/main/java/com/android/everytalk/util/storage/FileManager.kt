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
import com.android.everytalk.util.AppLogger
import com.android.everytalk.util.image.ImageScaleCalculator
import com.android.everytalk.util.image.ImageScaleConfig
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
        private const val MAX_FILE_SIZE_BYTES = 100 * 1024 * 1024 // 100MB 最大文件大小
        
        // 保留兼容性的常量，但标记为过时
        @Deprecated("Use ImageScaleConfig instead", ReplaceWith("ImageScaleConfig.CHAT_MODE.maxFileSize"))
        private const val MAX_IMAGE_SIZE_BYTES = 4 * 1024 * 1024
        @Deprecated("Use ImageScaleConfig instead", ReplaceWith("ImageScaleConfig.CHAT_MODE.maxDimension"))
        private const val TARGET_IMAGE_WIDTH = 1024
        @Deprecated("Use ImageScaleConfig instead", ReplaceWith("ImageScaleConfig.CHAT_MODE.maxDimension"))
        private const val TARGET_IMAGE_HEIGHT = 1024
        @Deprecated("Use ImageScaleConfig instead", ReplaceWith("ImageScaleConfig.CHAT_MODE.compressionQuality"))
        private const val JPEG_COMPRESSION_QUALITY = 80
    }

    private fun guessExtensionFromMime(mime: String?): String {
        val normalized = (mime ?: "").substringBefore(';').trim().lowercase()
        return when (normalized) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/heic" -> "heic"
            "image/heif" -> "heif"
            else -> "bin"
        }
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
     * 根据使用场景获取图片缩放配置
     * @param isImageGeneration 是否为图像生成模式
     * @return 对应的图片缩放配置
     */
    private fun getImageConfigForMode(isImageGeneration: Boolean): ImageScaleConfig {
        return if (isImageGeneration) ImageScaleConfig.IMAGE_GENERATION_MODE else ImageScaleConfig.CHAT_MODE
    }
    
    /**
     * 检查文件大小是否超过限制
     * @param uri 文件Uri
     * @return Pair<Boolean, Long> - 第一个值表示是否超过限制，第二个值是文件大小（字节）
     */
    suspend fun checkFileSize(uri: Uri): Pair<Boolean, Long> = withContext(Dispatchers.IO) {
        try {
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
                } catch (_: Exception) {
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
                            if (total > MAX_FILE_SIZE_BYTES) {
                                break
                            }
                        }
                        fileSize = total
                    }
                } catch (_: Exception) {
                    logger.warn("Failed to get file size")
                }
            }

            val size = fileSize ?: 0L
            val isOverLimit = size > MAX_FILE_SIZE_BYTES
            if (isOverLimit) {
                logger.warn("File size $size bytes exceeds limit $MAX_FILE_SIZE_BYTES bytes")
            }
            
            Pair(isOverLimit, size)
        } catch (e: Exception) {
            logger.error("Error checking file size", e)
            Pair(false, 0L)
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
     * 从Uri加载并压缩位图 - 新版本支持等比缩放
     * @param uri 图片Uri
     * @param isImageGeneration 是否为图像生成模式，将使用对应的配置
     * @return 压缩后的位图，如果加载失败则返回null
     */
    suspend fun loadAndCompressBitmapFromUri(
        uri: Uri, 
        isImageGeneration: Boolean = false
    ): Bitmap? = withContext(Dispatchers.IO) {
        var bitmap: Bitmap? = null
        try {
            if (uri == Uri.EMPTY) return@withContext null
            
            // 获取适当的配置
            val config = getImageConfigForMode(isImageGeneration)
            
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
            
            val originalWidth = options.outWidth
            val originalHeight = options.outHeight
            
            // 使用新的等比缩放算法计算目标尺寸
            val (targetWidth, targetHeight) = ImageScaleCalculator.calculateProportionalScale(
                originalWidth, originalHeight, config
            )
            
            // 计算合适的采样率以避免内存问题
            options.inSampleSize = ImageScaleCalculator.calculateInSampleSize(
                originalWidth, originalHeight, targetWidth, targetHeight
            )
            options.inJustDecodeBounds = false
            options.inMutable = true
            options.inPreferredConfig = Bitmap.Config.RGB_565 // 使用更少内存的配置
            
            bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            
            // 如果需要进一步缩放到精确尺寸
            if (bitmap != null) {
                val currentWidth = bitmap.width
                val currentHeight = bitmap.height
                
                // 重新计算精确的目标尺寸（基于采样后的实际尺寸）
                val (finalWidth, finalHeight) = ImageScaleCalculator.calculateProportionalScale(
                    currentWidth, currentHeight, config
                )
                
                // 只有当目标尺寸与当前尺寸不同时才进行缩放
                if ((finalWidth != currentWidth || finalHeight != currentHeight) && finalWidth > 0 && finalHeight > 0) {
                    try {
                        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, finalWidth, finalHeight, true)
                        if (scaledBitmap != bitmap) {
                            bitmap.recycle()
                        }
                        bitmap = scaledBitmap
                        logger.debug("Image scaled from ${currentWidth}x${currentHeight} to ${finalWidth}x${finalHeight}")
                    } catch (e: OutOfMemoryError) {
                        // 如果缩放失败，使用原图但记录警告
                        logger.warn("Failed to scale bitmap due to memory constraints, using sampled size")
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
     * 直接从网络URL下载并压缩位图 - 新版本支持等比缩放
     * @param urlStr 图片URL
     * @param isImageGeneration 是否为图像生成模式，将使用对应的配置
     * @return 压缩后的位图，如果加载失败则返回null
     */
    suspend fun loadAndCompressBitmapFromUrl(
        urlStr: String,
        isImageGeneration: Boolean = false
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (urlStr.isBlank()) return@withContext null
        
        // 获取适当的配置
        val config = getImageConfigForMode(isImageGeneration)
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

            val originalWidth = bounds.outWidth
            val originalHeight = bounds.outHeight
            
            // 使用新的等比缩放算法计算目标尺寸
            val (targetWidth, targetHeight) = ImageScaleCalculator.calculateProportionalScale(
                originalWidth, originalHeight, config
            )

            // 实际解码并压缩
            val opts = BitmapFactory.Options().apply {
                inSampleSize = ImageScaleCalculator.calculateInSampleSize(
                    originalWidth, originalHeight, targetWidth, targetHeight
                )
                inJustDecodeBounds = false
                inMutable = true
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)

            // 如有必要，再缩放一遍到精确尺寸
            if (bitmap != null) {
                val currentWidth = bitmap.width
                val currentHeight = bitmap.height
                
                // 重新计算精确的目标尺寸
                val (finalWidth, finalHeight) = ImageScaleCalculator.calculateProportionalScale(
                    currentWidth, currentHeight, config
                )
                
                if ((finalWidth != currentWidth || finalHeight != currentHeight) && finalWidth > 0 && finalHeight > 0) {
                    try {
                        val scaled = Bitmap.createScaledBitmap(bitmap, finalWidth, finalHeight, true)
                        if (scaled != bitmap) bitmap.recycle()
                        bitmap = scaled
                        logger.debug("URL image scaled from ${currentWidth}x${currentHeight} to ${finalWidth}x${finalHeight}")
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
     * 从 data:image/(any);base64,XXX 字符串解码位图 - 新版本支持等比缩放
     * @param dataUrl Data URL字符串
     * @param isImageGeneration 是否为图像生成模式，将使用对应的配置
     * @return 解码后的位图，如果解码失败则返回null
     */
    suspend fun loadBitmapFromDataUrl(
        dataUrl: String,
        isImageGeneration: Boolean = false
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // 获取适当的配置
            val config = getImageConfigForMode(isImageGeneration)
            val comma = dataUrl.indexOf(',')
            if (comma <= 0) return@withContext null
            val base64Part = dataUrl.substring(comma + 1)
            val bytes = Base64.decode(base64Part, Base64.DEFAULT)
            
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            
            val originalWidth = bounds.outWidth
            val originalHeight = bounds.outHeight
            
            // 使用新的等比缩放算法计算目标尺寸
            val (targetWidth, targetHeight) = ImageScaleCalculator.calculateProportionalScale(
                originalWidth, originalHeight, config
            )
            
            val opts = BitmapFactory.Options().apply {
                inSampleSize = ImageScaleCalculator.calculateInSampleSize(
                    originalWidth, originalHeight, targetWidth, targetHeight
                )
                inJustDecodeBounds = false
                inPreferredConfig = Bitmap.Config.RGB_565
                inMutable = true
            }
            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            
            // 如果需要精确缩放
            if (bitmap != null) {
                val currentWidth = bitmap.width
                val currentHeight = bitmap.height
                
                val (finalWidth, finalHeight) = ImageScaleCalculator.calculateProportionalScale(
                    currentWidth, currentHeight, config
                )
                
                if ((finalWidth != currentWidth || finalHeight != currentHeight) && finalWidth > 0 && finalHeight > 0) {
                    try {
                        val scaled = Bitmap.createScaledBitmap(bitmap, finalWidth, finalHeight, true)
                        if (scaled != bitmap) bitmap.recycle()
                        bitmap = scaled
                        logger.debug("Data URL image scaled from ${currentWidth}x${currentHeight} to ${finalWidth}x${finalHeight}")
                    } catch (_: OutOfMemoryError) {
                        logger.warn("OOM when scaling data URL bitmap; using decoded size")
                        System.gc()
                    }
                }
            }
            
            bitmap
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
            val safeOriginalName = originalFileName?.filter { it.isLetterOrDigit() || it in "._-" }?.take(30) ?: "file"
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
     * 将位图保存到应用内部存储 - 新版本支持智能压缩
     * @param bitmapToSave 要保存的位图
     * @param messageIdHint 消息ID提示
     * @param attachmentIndex 附件索引
     * @param originalFileNameHint 原始文件名提示
     * @param isImageGeneration 是否为图像生成模式，将使用对应的配置
     * @return 保存后的文件路径，如果保存失败则返回null
     */
    suspend fun saveBitmapToAppInternalStorage(
        bitmapToSave: Bitmap,
        messageIdHint: String,
        attachmentIndex: Int,
        originalFileNameHint: String? = null,
        isImageGeneration: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        try {
            if (bitmapToSave.isRecycled) {
                logger.error("Cannot save recycled bitmap")
                return@withContext null
            }
            
            // 获取适当的配置
            val config = getImageConfigForMode(isImageGeneration)
            
            val outputStream = ByteArrayOutputStream()
            val fileExtension: String
            val compressFormat = if (bitmapToSave.hasAlpha()) {
                fileExtension = "png"; Bitmap.CompressFormat.PNG
            } else {
                fileExtension = "jpg"; Bitmap.CompressFormat.JPEG
            }
            
            // 使用智能压缩质量
            val compressionQuality = ImageScaleCalculator.calculateSmartCompressionQuality(
                bitmapToSave.width,
                bitmapToSave.height,
                config.compressionQuality,
                config.enableSmartCompression
            )
            
            bitmapToSave.compress(compressFormat, compressionQuality, outputStream)
            val bytes = outputStream.toByteArray()
            
            if (!bitmapToSave.isRecycled) {
                bitmapToSave.recycle()
            }
            
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val baseName = originalFileNameHint?.substringBeforeLast('.')
                ?.filter { it.isLetterOrDigit() || it in "._-" }?.take(20) ?: "IMG"
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
            
            logger.debug("Bitmap saved successfully: ${destinationFile.absolutePath} (quality: $compressionQuality)")
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

    // ===================== 会话/缓存清理 =====================

    /**
     * 递归安全删除，返回删除的文件数量
     */
    private fun deleteRecursivelySafe(target: File?): Int {
        if (target == null || !target.exists()) return 0
        var count = 0
        try {
            if (target.isDirectory) {
                target.listFiles()?.forEach { child ->
                    count += deleteRecursivelySafe(child)
                }
            }
            if (target.delete()) count++
        } catch (_: Exception) {
            // 忽略单个文件删除失败，避免中断整体清理
        }
        return count
    }

    /**
     * 清空聊天附件目录（图片/文档/视频/音频均存于此）
     * 用于“删除会话/全部会话”后释放存储空间。
     * @return 实际删除的文件数量
     */
    suspend fun clearAllChatAttachments(): Int = withContext(Dispatchers.IO) {
        val dir = getChatAttachmentsDir()
        var deleted = 0
        dir.listFiles()?.forEach { f ->
            deleted += deleteRecursivelySafe(f)
        }
        deleted
    }

    /**
     * 按消息ID提示前缀批量删除附件文件。
     * 我们保存文件名包含: _{messageIdHint}_{attachmentIndex}_，据此匹配。
     * @param messageIdHints 消息ID提示（如消息ID或其可识别前缀）
     * @return 删除数量
     */
    suspend fun deleteAttachmentsByMessageHints(messageIdHints: List<String>): Int = withContext(Dispatchers.IO) {
        if (messageIdHints.isEmpty()) return@withContext 0
        val dir = getChatAttachmentsDir()
        val files = dir.listFiles().orEmpty()
        var deleted = 0
        files.forEach { f ->
            val name = f.name
            if (messageIdHints.any { hint -> name.contains("_${'$'}hint" + "_") }) {
                deleted += deleteRecursivelySafe(f)
            }
        }
        deleted
    }

    /**
     * 删除不在“保留路径集合”中的附件（清理孤儿文件）
     * @param keepAbsolutePaths 需要保留的绝对路径集合
     * @return 实际删除数量
     */
    suspend fun deleteOrphanAttachments(keepAbsolutePaths: Set<String>): Int = withContext(Dispatchers.IO) {
        val keep = keepAbsolutePaths.mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }.toSet()
        val dir = getChatAttachmentsDir()
        var deleted = 0
        dir.listFiles()?.forEach { f ->
            val path = runCatching { f.canonicalPath }.getOrNull()
            if (path != null && !keep.contains(path)) {
                deleted += deleteRecursivelySafe(f)
            }
        }
        deleted
    }

    /**
     * 统计聊天附件目录占用大小（字节）
     */
    suspend fun getChatAttachmentsSizeBytes(): Long = withContext(Dispatchers.IO) {
        fun folderSize(f: File?): Long {
            if (f == null || !f.exists()) return 0L
            return if (f.isDirectory) {
                f.listFiles()?.sumOf { folderSize(it) } ?: 0L
            } else f.length()
        }
        folderSize(getChatAttachmentsDir())
    }

    /**
     * 一键清空：会话占用存储
     * 返回释放的总字节数（尽量估算，可能受 ROM/权限影响）
     */
    suspend fun clearAllConversationStorage(): Long = withContext(Dispatchers.IO) {
        // 估算清理前大小
        val before = runCatching { getChatAttachmentsSizeBytes() }.getOrElse { 0L }

        // 执行清理
        runCatching { clearAllChatAttachments() }

        // 估算清理后大小
        val after = runCatching { getChatAttachmentsSizeBytes() }.getOrElse { 0L }

        val freed = before - after
        if (freed > 0) freed else 0L
    }

    // ===================== 原图字节级读写（不重新编码） =====================

    /**
     * 从多种来源加载原始字节与 MIME：
     * - data:image/...;base64,XXXX
     * - http(s)://
     * - content://
     * - file:// 或 绝对路径
     */
    suspend fun loadBytesFromFlexibleSource(source: String): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        try {
            if (source.startsWith("data:image", ignoreCase = true)) {
                val headerEnd = source.indexOf(";base64,")
                if (headerEnd > 5) {
                    val mime = source.substring(5, headerEnd)
                    val base64Part = source.substringAfter(",", "")
                    if (base64Part.isNotBlank()) {
                        val bytes = Base64.decode(base64Part, Base64.DEFAULT)
                        return@withContext bytes to mime
                    }
                }
                return@withContext null
            }

            val uri = runCatching { Uri.parse(source) }.getOrNull()
            val scheme = uri?.scheme?.lowercase()

            fun readAllBytesFromContent(u: Uri): Pair<ByteArray, String>? {
                val cr = context.contentResolver
                val mime = cr.getType(u) ?: "application/octet-stream"
                val bytes = cr.openInputStream(u)?.use { it.readBytes() } ?: return null
                return bytes to mime
            }

            fun readAllBytesFromFile(path: String): Pair<ByteArray, String>? {
                val f = File(path)
                if (!f.exists()) return null
                val bytes = f.readBytes()
                val mime = when {
                    path.endsWith(".png", true) -> "image/png"
                    path.endsWith(".jpg", true) || path.endsWith(".jpeg", true) -> "image/jpeg"
                    path.endsWith(".webp", true) -> "image/webp"
                    path.endsWith(".heic", true) -> "image/heic"
                    path.endsWith(".heif", true) -> "image/heif"
                    else -> "application/octet-stream"
                }
                return bytes to mime
            }

            if (scheme == "http" || scheme == "https") {
                val conn = (URL(source).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                }
                conn.connect()
                if (conn.responseCode !in 200..299) return@withContext null
                val mime = conn.contentType ?: "application/octet-stream"
                val bytes = conn.inputStream.use { it.readBytes() }
                return@withContext bytes to mime
            } else if (scheme == "content") {
                return@withContext readAllBytesFromContent(uri!!)
            } else if (scheme == "file") {
                return@withContext readAllBytesFromFile(uri?.path ?: return@withContext null)
            } else if (scheme.isNullOrBlank()) {
                // 绝对路径
                return@withContext readAllBytesFromFile(source)
            }

            null
        } catch (e: Exception) {
            logger.error("Failed to load original bytes from source: $source", e)
            null
        }
    }

    /**
     * 将原始字节保存到应用内部存储（不重新编码），返回绝对路径
     */
    suspend fun saveBytesToInternalImages(
        bytes: ByteArray,
        mime: String,
        baseName: String,
        messageIdHint: String,
        index: Int
    ): String? = withContext(Dispatchers.IO) {
        try {
            val ext = guessExtensionFromMime(mime)
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val safeBase = baseName.filter { it.isLetterOrDigit() || it in "._-" }.take(30)
            val uniqueName = "${safeBase}_${messageIdHint}_${index}_${timeStamp}_${UUID.randomUUID().toString().take(4)}.$ext"

            val dir = getChatAttachmentsDir()
            val file = File(dir, uniqueName)
            FileOutputStream(file).use { it.write(bytes) }
            if (file.exists() && file.length() > 0) file.absolutePath else null
        } catch (e: Exception) {
            logger.error("Failed to save original bytes to internal storage", e)
            null
        }
    }

    /**
     * 将原始字节保存到媒体库（相册/下载），保留原 MIME 与扩展名
     */
    suspend fun saveBytesToMediaStore(
        bytes: ByteArray,
        mime: String,
        displayNameBase: String
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val normalizedMime = mime.substringBefore(';').trim().ifBlank { "application/octet-stream" }
            val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val ext = guessExtensionFromMime(normalizedMime)
            val name = "${displayNameBase}_${System.currentTimeMillis()}.$ext"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, normalizedMime)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/EveryTalk")
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(collection, values) ?: return@withContext null
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@withContext null
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                uri
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        } catch (e: Exception) {
            logger.error("Failed to save original bytes to MediaStore", e)
            null
        }
    }
}