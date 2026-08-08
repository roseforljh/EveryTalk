package com.android.everytalk.util.storage

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.core.net.toUri
import com.android.everytalk.data.network.SafeHttpDownloader
import com.android.everytalk.util.image.GENERATED_IMAGE_PERSISTENCE_POLICY
import com.android.everytalk.util.image.ImagePersistenceFailure
import com.android.everytalk.util.image.ImagePersistencePolicy
import com.android.everytalk.util.image.ImagePersistenceResult
import com.android.everytalk.util.image.ImageSizeCheckResult
import com.android.everytalk.util.image.USER_IMAGE_PERSISTENCE_POLICY
import com.android.everytalk.util.image.decodedBase64SizeOrNull
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片来源统一持久化入口。所有路径均保留原始字节，只执行边界、格式和像素校验。
 */
internal class ImagePersistenceService(
    private val context: Context,
) {
    suspend fun checkUserImageSize(
        sourceUri: Uri,
        policy: ImagePersistencePolicy = USER_IMAGE_PERSISTENCE_POLICY,
    ): ImageSizeCheckResult = withContext(Dispatchers.IO) {
        declaredSize(sourceUri)?.let { size ->
            return@withContext if (size > policy.maxBytes) {
                ImageSizeCheckResult.Rejected(
                    ImagePersistenceFailure.TooLarge(size, policy.maxBytes, actualSizeIsExact = true),
                )
            } else {
                ImageSizeCheckResult.Accepted(size)
            }
        }

        try {
            val input = context.contentResolver.openInputStream(sourceUri)
                ?: return@withContext ImageSizeCheckResult.Rejected(ImagePersistenceFailure.ReadFailed)
            input.use {
                val observed = countAtMost(it, policy.maxBytes)
                if (observed > policy.maxBytes) {
                    ImageSizeCheckResult.Rejected(
                        ImagePersistenceFailure.TooLarge(observed, policy.maxBytes, actualSizeIsExact = false),
                    )
                } else {
                    ImageSizeCheckResult.Accepted(observed)
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Log.w(TAG, "读取用户图片大小失败", exception)
            ImageSizeCheckResult.Rejected(ImagePersistenceFailure.ReadFailed)
        }
    }

    suspend fun persistUserImage(
        sourceUri: Uri,
        fileName: String?,
        messageIdHint: String,
        attachmentIndex: Int,
        policy: ImagePersistencePolicy = USER_IMAGE_PERSISTENCE_POLICY,
    ): ImagePersistenceResult = withContext(Dispatchers.IO) {
        declaredSize(sourceUri)?.takeIf { it > policy.maxBytes }?.let { size ->
            return@withContext ImagePersistenceResult.Failure(
                ImagePersistenceFailure.TooLarge(size, policy.maxBytes, actualSizeIsExact = true),
            )
        }
        val input = try {
            context.contentResolver.openInputStream(sourceUri)
        } catch (exception: Exception) {
            Log.w(TAG, "打开用户图片失败", exception)
            null
        } ?: return@withContext ImagePersistenceResult.Failure(ImagePersistenceFailure.ReadFailed)

        input.use {
            persistStream(
                input = it,
                baseName = fileName?.substringBeforeLast('.', fileName),
                messageIdHint = messageIdHint,
                index = attachmentIndex,
                policy = policy,
            )
        }
    }

    suspend fun persistEncodedUserImage(
        base64Data: String,
        declaredMimeType: String,
        messageIdHint: String,
        attachmentIndex: Int,
        policy: ImagePersistencePolicy = USER_IMAGE_PERSISTENCE_POLICY,
    ): ImagePersistenceResult = persistGeneratedImage(
        source = "data:${declaredMimeType.substringBefore(';')};base64,$base64Data",
        messageIdHint = messageIdHint,
        index = attachmentIndex,
        policy = policy,
    )

    suspend fun persistGeneratedImage(
        source: String,
        messageIdHint: String,
        index: Int,
        policy: ImagePersistencePolicy = GENERATED_IMAGE_PERSISTENCE_POLICY,
        remoteHeaders: Map<String, String> = emptyMap(),
        trustedOrigin: String? = null,
    ): ImagePersistenceResult = withContext(Dispatchers.IO) {
        val normalizedSource = source.trim()
        if (normalizedSource.isEmpty()) {
            return@withContext ImagePersistenceResult.Failure(ImagePersistenceFailure.EmptyImage)
        }

        when {
            normalizedSource.startsWith("data:", ignoreCase = true) ->
                persistDataImage(normalizedSource, messageIdHint, index, policy)

            normalizedSource.startsWith("http://", ignoreCase = true) ||
                normalizedSource.startsWith("https://", ignoreCase = true) ->
                persistRemoteImage(
                    source = normalizedSource,
                    messageIdHint = messageIdHint,
                    index = index,
                    policy = policy,
                    remoteHeaders = remoteHeaders,
                    trustedOrigin = trustedOrigin,
                )

            else -> persistLocalSource(normalizedSource, messageIdHint, index, policy)
        }
    }

    suspend fun validatePersistedUserImage(
        filePath: String,
        policy: ImagePersistencePolicy = USER_IMAGE_PERSISTENCE_POLICY,
    ): ImagePersistenceResult = withContext(Dispatchers.IO) {
        val attachmentRoot = attachmentsDirectory().canonicalFile
        val file = runCatching { File(filePath).canonicalFile }.getOrNull()
            ?: return@withContext ImagePersistenceResult.Failure(ImagePersistenceFailure.ReadFailed)
        if (file.parentFile != attachmentRoot || !file.isFile) {
            return@withContext ImagePersistenceResult.Failure(ImagePersistenceFailure.UnsupportedSource)
        }
        when (val validation = validateImageFile(file, policy)) {
            is FileValidation.Valid -> ImagePersistenceResult.Success(
                filePath = file.absolutePath,
                mimeType = validation.mimeType,
                sizeBytes = validation.sizeBytes,
                width = validation.width,
                height = validation.height,
            )
            is FileValidation.Invalid -> ImagePersistenceResult.Failure(validation.reason)
        }
    }

    private fun persistDataImage(
        source: String,
        messageIdHint: String,
        index: Int,
        policy: ImagePersistencePolicy,
    ): ImagePersistenceResult {
        val markerIndex = source.indexOf(";base64,", ignoreCase = true)
        if (markerIndex !in 5..256) {
            return ImagePersistenceResult.Failure(ImagePersistenceFailure.InvalidImage)
        }
        val encoded = source.substring(markerIndex + BASE64_MARKER.length)
        val decodedSize = decodedBase64SizeOrNull(encoded)
            ?: return ImagePersistenceResult.Failure(ImagePersistenceFailure.InvalidImage)
        if (decodedSize > policy.maxBytes) {
            return ImagePersistenceResult.Failure(
                ImagePersistenceFailure.TooLarge(decodedSize, policy.maxBytes, actualSizeIsExact = true),
            )
        }
        val bytes = try {
            Base64.decode(encoded, Base64.DEFAULT)
        } catch (exception: IllegalArgumentException) {
            return ImagePersistenceResult.Failure(ImagePersistenceFailure.InvalidImage)
        }
        return ByteArrayInputStream(bytes).use {
            persistStream(it, "generated", messageIdHint, index, policy)
        }
    }

    private suspend fun persistRemoteImage(
        source: String,
        messageIdHint: String,
        index: Int,
        policy: ImagePersistencePolicy,
        remoteHeaders: Map<String, String>,
        trustedOrigin: String?,
    ): ImagePersistenceResult {
        val urlBytes = source.toByteArray(Charsets.UTF_8).size
        if (urlBytes > policy.maxRemoteUrlBytes) {
            return ImagePersistenceResult.Failure(
                ImagePersistenceFailure.UrlTooLong(urlBytes, policy.maxRemoteUrlBytes),
            )
        }

        val temporaryFile = createTemporaryFile()
            ?: return ImagePersistenceResult.Failure(ImagePersistenceFailure.StorageFailed)
        return try {
            FileOutputStream(temporaryFile).use { output ->
                SafeHttpDownloader.downloadTo(
                    url = source,
                    output = output,
                    maxBytes = policy.maxBytes,
                    connectTimeoutMillis = policy.remoteConnectTimeoutMillis,
                    totalTimeoutMillis = policy.remoteDownloadTimeoutMillis,
                    accept = "image/*",
                    headers = remoteHeaders,
                    trustedOrigin = trustedOrigin,
                )
                output.fd.sync()
            }
            validateAndFinalize(temporaryFile, "generated", messageIdHint, index, policy)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SafeHttpDownloader.ResponseTooLargeException) {
            ImagePersistenceResult.Failure(
                ImagePersistenceFailure.TooLarge(
                    actualBytes = exception.actualBytes,
                    limitBytes = policy.maxBytes,
                    actualSizeIsExact = exception.actualSizeIsExact,
                ),
            )
        } catch (exception: SocketTimeoutException) {
            ImagePersistenceResult.Failure(ImagePersistenceFailure.DownloadTimedOut)
        } catch (exception: java.io.InterruptedIOException) {
            ImagePersistenceResult.Failure(ImagePersistenceFailure.DownloadTimedOut)
        } catch (exception: Exception) {
            Log.w(TAG, "远程图片下载失败", exception)
            ImagePersistenceResult.Failure(ImagePersistenceFailure.DownloadFailed)
        } finally {
            temporaryFile.delete()
        }
    }

    private fun persistLocalSource(
        source: String,
        messageIdHint: String,
        index: Int,
        policy: ImagePersistencePolicy,
    ): ImagePersistenceResult {
        val uri = runCatching { source.toUri() }.getOrNull()
            ?: return ImagePersistenceResult.Failure(ImagePersistenceFailure.UnsupportedSource)
        val input = try {
            when (uri.scheme?.lowercase(Locale.ROOT)) {
                "content", "file" -> context.contentResolver.openInputStream(uri)
                null, "" -> File(source).takeIf(File::isFile)?.inputStream()
                else -> null
            }
        } catch (exception: Exception) {
            Log.w(TAG, "打开本地图片来源失败", exception)
            null
        } ?: return ImagePersistenceResult.Failure(ImagePersistenceFailure.UnsupportedSource)

        return input.use { persistStream(it, "generated", messageIdHint, index, policy) }
    }

    private fun persistStream(
        input: InputStream,
        baseName: String?,
        messageIdHint: String,
        index: Int,
        policy: ImagePersistencePolicy,
    ): ImagePersistenceResult {
        val temporaryFile = createTemporaryFile()
            ?: return ImagePersistenceResult.Failure(ImagePersistenceFailure.StorageFailed)
        return try {
            val copiedBytes = FileOutputStream(temporaryFile).use { output ->
                val result = copyAtMost(input, output, policy.maxBytes)
                output.fd.sync()
                result
            }
            if (copiedBytes > policy.maxBytes) {
                ImagePersistenceResult.Failure(
                    ImagePersistenceFailure.TooLarge(copiedBytes, policy.maxBytes, actualSizeIsExact = false),
                )
            } else {
                validateAndFinalize(temporaryFile, baseName, messageIdHint, index, policy)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Log.w(TAG, "图片原始字节持久化失败", exception)
            ImagePersistenceResult.Failure(ImagePersistenceFailure.StorageFailed)
        } finally {
            temporaryFile.delete()
        }
    }

    private fun validateAndFinalize(
        temporaryFile: File,
        baseName: String?,
        messageIdHint: String,
        index: Int,
        policy: ImagePersistencePolicy,
    ): ImagePersistenceResult {
        val validation = when (val result = validateImageFile(temporaryFile, policy)) {
            is FileValidation.Valid -> result
            is FileValidation.Invalid -> return ImagePersistenceResult.Failure(result.reason)
        }

        val target = createTargetFile(baseName, messageIdHint, index, validation.mimeType)
        if (!temporaryFile.renameTo(target)) {
            return ImagePersistenceResult.Failure(ImagePersistenceFailure.StorageFailed)
        }
        return ImagePersistenceResult.Success(
            filePath = target.absolutePath,
            mimeType = validation.mimeType,
            sizeBytes = validation.sizeBytes,
            width = validation.width,
            height = validation.height,
        )
    }

    private fun validateImageFile(
        file: File,
        policy: ImagePersistencePolicy,
    ): FileValidation {
        val sizeBytes = file.length()
        if (sizeBytes == 0L) return FileValidation.Invalid(ImagePersistenceFailure.EmptyImage)
        if (sizeBytes > policy.maxBytes) {
            return FileValidation.Invalid(
                ImagePersistenceFailure.TooLarge(sizeBytes, policy.maxBytes, actualSizeIsExact = true),
            )
        }
        val mimeType = detectImageMime(file)
            ?: return FileValidation.Invalid(ImagePersistenceFailure.InvalidImage)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            return FileValidation.Invalid(ImagePersistenceFailure.InvalidImage)
        }
        val pixels = width.toLong() * height.toLong()
        if (pixels > policy.maxPixels) {
            return FileValidation.Invalid(
                ImagePersistenceFailure.TooManyPixels(pixels, policy.maxPixels),
            )
        }
        return FileValidation.Valid(mimeType, sizeBytes, width, height)
    }

    private fun createTemporaryFile(): File? = try {
        File.createTempFile(".image_", ".part", attachmentsDirectory())
    } catch (exception: IOException) {
        Log.e(TAG, "创建图片临时文件失败", exception)
        null
    }

    private fun createTargetFile(
        baseName: String?,
        messageIdHint: String,
        index: Int,
        mimeType: String,
    ): File {
        val safeBaseName = baseName.orEmpty()
            .filter { it.isLetterOrDigit() || it in "._-" }
            .take(30)
            .ifBlank { "image" }
        val safeMessageId = messageIdHint
            .filter { it.isLetterOrDigit() || it in "_-" }
            .take(48)
            .ifBlank { "message" }
        val extension = guessExtensionFromMime(mimeType)
        val fileName = "${safeBaseName}_${safeMessageId}_${index.coerceAtLeast(0)}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(4)}.$extension"
        return File(attachmentsDirectory(), fileName)
    }

    private fun attachmentsDirectory(): File =
        File(context.filesDir, FileManager.CHAT_ATTACHMENTS_DIR).apply { mkdirs() }

    private fun declaredSize(uri: Uri): Long? {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            uri.path?.let(::File)?.takeIf(File::isFile)?.length()?.takeIf { it >= 0L }?.let { return it }
        }
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index != -1 && !cursor.isNull(index)) {
                        cursor.getLong(index).takeIf { it >= 0L }?.let { return it }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.statSize.takeIf { it >= 0L }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun detectImageMime(file: File): String? {
        val header = ByteArray(64)
        val size = file.inputStream().use { it.read(header) }
        if (size >= 8 && header.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) return "image/png"
        if (size >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        if (size >= 12 && header.ascii(0, 4) == "RIFF" && header.ascii(8, 12) == "WEBP") return "image/webp"
        if (size >= 6 && header.ascii(0, 6) in setOf("GIF87a", "GIF89a")) return "image/gif"
        if (size >= 2 && header[0] == 'B'.code.toByte() && header[1] == 'M'.code.toByte()) return "image/bmp"
        if (size >= 12 && header.ascii(4, 8) == "ftyp") {
            val brands = (8 until size - 3 step 4).map { header.ascii(it, it + 4) }.toSet()
            if (brands.any { it in AVIF_BRANDS }) return "image/avif"
            if (brands.any { it in HEIC_BRANDS }) return "image/heic"
            if (brands.any { it in HEIF_BRANDS }) return "image/heif"
        }
        return null
    }

    private fun ByteArray.ascii(start: Int, end: Int): String =
        String(this, start, end - start, Charsets.US_ASCII)

    private fun countAtMost(input: InputStream, maxBytes: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            if (total > maxBytes) return total
        }
    }

    private fun copyAtMost(input: InputStream, output: FileOutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            if (total > maxBytes) return total
            output.write(buffer, 0, read)
        }
    }

    private companion object {
        const val TAG = "ImagePersistence"
        const val BASE64_MARKER = ";base64,"
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val AVIF_BRANDS = setOf("avif", "avis")
        val HEIC_BRANDS = setOf("heic", "heix", "hevc", "hevx")
        val HEIF_BRANDS = setOf("mif1", "msf1")
    }

    private sealed interface FileValidation {
        data class Valid(
            val mimeType: String,
            val sizeBytes: Long,
            val width: Int,
            val height: Int,
        ) : FileValidation

        data class Invalid(val reason: ImagePersistenceFailure) : FileValidation
    }
}
