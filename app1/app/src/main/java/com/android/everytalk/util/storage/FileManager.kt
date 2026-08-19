package com.android.everytalk.util.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.android.everytalk.data.network.SafeHttpDownloader
import com.android.everytalk.util.AppLogger
import com.android.everytalk.util.image.ImageHandlingLimits
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
class FileManager(internal val context: Context) {
    internal val logger = AppLogger.forComponent("FileManager")
    
    companion object {
        internal const val CHAT_ATTACHMENTS_DIR = "chat_attachments"
        private const val MAX_FILE_SIZE_BYTES = 100 * 1024 * 1024 // 100MB 最大文件大小
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Error checking file size", e)
            Pair(false, 0L)
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            logger.error("Out of memory while copying file", e)
            null
        } catch (e: Exception) {
            logger.error("Failed to copy URI to internal storage", e)
            null
        }
    }

    /**
     * 把发送阶段持有的 Base64 音频写入应用私有附件目录。
     * 返回值只包含本地路径，聊天消息落库时无需再保存整段 Base64。
     */
    suspend fun persistBase64Attachment(
        base64Data: String,
        mimeType: String,
        messageIdHint: String,
        attachmentIndex: Int,
    ): String? = withContext(Dispatchers.IO) {
        var temporaryFile: File? = null
        try {
            val encodedLength = base64Data.count { !it.isWhitespace() }.toLong()
            val estimatedBytes = ((encodedLength + 3L) / 4L) * 3L
            if (estimatedBytes <= 0L || estimatedBytes > MAX_FILE_SIZE_BYTES) return@withContext null

            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            if (bytes.isEmpty() || bytes.size.toLong() > MAX_FILE_SIZE_BYTES) return@withContext null

            val extension = android.webkit.MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mimeType)
                ?.takeIf { it.all(Char::isLetterOrDigit) }
                ?: "audio"
            val attachmentDir = getChatAttachmentsDir()
            temporaryFile = File.createTempFile(".audio_", ".part", attachmentDir)
            temporaryFile.outputStream().buffered().use { it.write(bytes) }
            val destination = File(
                attachmentDir,
                "audio_${messageIdHint}_${attachmentIndex}_${UUID.randomUUID().toString().take(8)}.$extension",
            )
            if (!temporaryFile.renameTo(destination)) return@withContext null
            destination.absolutePath
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error("Failed to persist Base64 attachment", exception)
            null
        } finally {
            temporaryFile?.takeIf(File::exists)?.delete()
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
    suspend fun loadBytesFromFlexibleSource(
        source: String,
        maxBytes: Long = ImageHandlingLimits.GENERATED_IMAGE_MAX_BYTES,
        networkTimeoutMillis: Int = ImageHandlingLimits.REMOTE_DOWNLOAD_TIMEOUT_MILLIS,
    ): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        try {
            if (source.startsWith("data:image", ignoreCase = true)) {
                val headerEnd = source.indexOf(";base64,", ignoreCase = true)
                if (headerEnd in 6..256) {
                    val mime = source.substring(5, headerEnd).substringBefore(';').trim().lowercase(Locale.ROOT)
                    val base64Part = source.substringAfter(",", "")
                    if (base64Part.isNotBlank()) {
                        val encodedLength = base64Part.count { !it.isWhitespace() }.toLong()
                        val estimatedBytes = ((encodedLength + 3L) / 4L) * 3L
                        if (estimatedBytes > maxBytes + 2L) return@withContext null
                        val bytes = Base64.decode(base64Part, Base64.DEFAULT)
                        if (bytes.size.toLong() > maxBytes) return@withContext null
                        return@withContext bytes to mime
                    }
                }
                return@withContext null
            }

            val uri = runCatching { source.toUri() }.getOrNull()
            val scheme = uri?.scheme?.lowercase()

            fun readAllBytesFromContent(u: Uri): Pair<ByteArray, String>? {
                val cr = context.contentResolver
                val mime = cr.getType(u) ?: "application/octet-stream"
                val bytes = cr.openInputStream(u)?.use { readAtMost(it, maxBytes) } ?: return null
                return bytes to mime
            }

            fun readAllBytesFromFile(path: String): Pair<ByteArray, String>? {
                val f = File(path)
                if (!f.exists()) return null
                val bytes = f.readAtMost(maxBytes)
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
                val downloaded = SafeHttpDownloader.download(
                    url = source,
                    maxBytes = maxBytes,
                    timeoutMillis = networkTimeoutMillis,
                    connectTimeoutMillis = minOf(
                        ImageHandlingLimits.REMOTE_CONNECT_TIMEOUT_MILLIS,
                        networkTimeoutMillis,
                    ),
                    accept = "image/*",
                )
                return@withContext downloaded.bytes to downloaded.contentType
            } else if (scheme == "content") {
                return@withContext readAllBytesFromContent(uri)
            } else if (scheme == "file") {
                return@withContext readAllBytesFromFile(uri.path ?: return@withContext null)
            } else if (scheme.isNullOrBlank()) {
                // 绝对路径
                return@withContext readAllBytesFromFile(source)
            }

            null
        } catch (exception: CancellationException) {
            throw exception
        } catch (e: Exception) {
            val scheme = runCatching { source.toUri().scheme?.lowercase(Locale.ROOT) }.getOrNull() ?: "path"
            logger.error("Failed to load original bytes: scheme=$scheme, chars=${source.length}", e)
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
        var temporaryFile: File? = null
        try {
            val ext = guessExtensionFromMime(mime)
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val safeBase = baseName.filter { it.isLetterOrDigit() || it in "._-" }.take(30).ifBlank { "img" }
            val safeMessageId = messageIdHint.filter { it.isLetterOrDigit() || it in "_-" }
                .take(48)
                .ifBlank { "message" }
            val uniqueName = "${safeBase}_${safeMessageId}_${index.coerceAtLeast(0)}_${timeStamp}_${UUID.randomUUID().toString().take(4)}.$ext"

            val dir = getChatAttachmentsDir()
            val file = File(dir, uniqueName)
            temporaryFile = File(dir, ".$uniqueName.tmp")
            FileOutputStream(temporaryFile).use {
                it.write(bytes)
                it.fd.sync()
            }
            if (!temporaryFile.renameTo(file)) return@withContext null
            if (file.exists() && file.length() > 0) file.absolutePath else null
        } catch (exception: CancellationException) {
            throw exception
        } catch (e: Exception) {
            logger.error("Failed to save original bytes to internal storage", e)
            null
        } finally {
            temporaryFile?.takeIf(File::exists)?.delete()
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
            var completed = false
            try {
                val output = resolver.openOutputStream(uri)
                    ?: throw IOException("无法打开媒体库输出流")
                output.use { it.write(bytes) }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                completed = true
                uri
            } finally {
                if (!completed) {
                    runCatching { resolver.delete(uri, null, null) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to save original bytes to MediaStore", e)
            null
        }
    }
}
