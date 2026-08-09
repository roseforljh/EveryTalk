package com.android.everytalk.util.image

import android.content.Context
import com.android.everytalk.R
import java.util.Locale

/** 图片输入、生成结果和远程下载共用的安全边界。 */
internal object ImageHandlingLimits {
    const val USER_UPLOAD_MAX_BYTES = 16L * 1024L * 1024L
    const val GENERATED_IMAGE_MAX_BYTES = 32L * 1024L * 1024L
    const val MAX_IMAGE_PIXELS = 40_000_000L
    const val MAX_REMOTE_URL_BYTES = 16 * 1024
    const val REMOTE_CONNECT_TIMEOUT_MILLIS = 10_000
    const val REMOTE_DOWNLOAD_TIMEOUT_MILLIS = 60_000
}

internal data class ImagePersistencePolicy(
    val maxBytes: Long,
    val maxPixels: Long,
    val maxRemoteUrlBytes: Int = ImageHandlingLimits.MAX_REMOTE_URL_BYTES,
    val remoteConnectTimeoutMillis: Int = ImageHandlingLimits.REMOTE_CONNECT_TIMEOUT_MILLIS,
    val remoteDownloadTimeoutMillis: Int = ImageHandlingLimits.REMOTE_DOWNLOAD_TIMEOUT_MILLIS,
) {
    init {
        require(maxBytes > 0L) { "图片字节上限必须大于 0" }
        require(maxPixels > 0L) { "图片像素上限必须大于 0" }
        require(maxRemoteUrlBytes > 0) { "远程 URL 上限必须大于 0" }
        require(remoteConnectTimeoutMillis > 0) { "连接超时必须大于 0" }
        require(remoteDownloadTimeoutMillis >= remoteConnectTimeoutMillis) { "总下载超时不得小于连接超时" }
    }
}

internal val USER_IMAGE_PERSISTENCE_POLICY = ImagePersistencePolicy(
    maxBytes = ImageHandlingLimits.USER_UPLOAD_MAX_BYTES,
    maxPixels = ImageHandlingLimits.MAX_IMAGE_PIXELS,
)

internal val GENERATED_IMAGE_PERSISTENCE_POLICY = ImagePersistencePolicy(
    maxBytes = ImageHandlingLimits.GENERATED_IMAGE_MAX_BYTES,
    maxPixels = ImageHandlingLimits.MAX_IMAGE_PIXELS,
)

internal sealed interface ImagePersistenceFailure {
    data class TooLarge(
        val actualBytes: Long?,
        val limitBytes: Long,
        val actualSizeIsExact: Boolean,
    ) : ImagePersistenceFailure

    data class TooManyPixels(
        val actualPixels: Long,
        val limitPixels: Long,
    ) : ImagePersistenceFailure

    data class UrlTooLong(
        val actualBytes: Int,
        val limitBytes: Int,
    ) : ImagePersistenceFailure

    data object EmptyImage : ImagePersistenceFailure
    data object InvalidImage : ImagePersistenceFailure
    data object UnsupportedSource : ImagePersistenceFailure
    data object ReadFailed : ImagePersistenceFailure
    data object DownloadTimedOut : ImagePersistenceFailure
    data object DownloadFailed : ImagePersistenceFailure
    data object StorageFailed : ImagePersistenceFailure
}

internal sealed interface ImagePersistenceResult {
    data class Success(
        val filePath: String,
        val mimeType: String,
        val sizeBytes: Long,
        val width: Int,
        val height: Int,
    ) : ImagePersistenceResult

    data class Failure(val reason: ImagePersistenceFailure) : ImagePersistenceResult
}

internal sealed interface ImageSizeCheckResult {
    data class Accepted(val sizeBytes: Long?) : ImageSizeCheckResult
    data class Rejected(val reason: ImagePersistenceFailure) : ImageSizeCheckResult
}

internal fun ImagePersistenceResult.pathOrNull(): String? =
    (this as? ImagePersistenceResult.Success)?.filePath

internal fun decodedBase64SizeOrNull(
    encoded: CharSequence,
    startIndex: Int = 0,
): Long? {
    if (startIndex !in 0..encoded.length) return null
    var characters = 0L
    var padding = 0
    var sawPadding = false
    for (index in startIndex until encoded.length) {
        val character = encoded[index]
        if (character.isWhitespace()) continue
        when {
            character == '=' -> {
                sawPadding = true
                padding++
                if (padding > 2) return null
            }

            character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' ||
                character == '+' || character == '/' -> if (sawPadding) return null

            else -> return null
        }
        characters++
    }
    if (characters == 0L || characters % 4L == 1L) return null
    if (padding > 0 && characters % 4L != 0L) return null
    return if (padding > 0) characters / 4L * 3L - padding else characters * 3L / 4L
}

internal fun ImagePersistenceFailure.toUserImageMessage(
    context: Context,
    fileName: String?,
): String {
    val imageLabel = fileName?.takeIf(String::isNotBlank)?.let {
        context.getString(R.string.image_input_label_named, it)
    } ?: context.getString(R.string.image_input_label_selected)
    return when (this) {
        is ImagePersistenceFailure.TooLarge -> {
            val limit = formatBinarySize(limitBytes, keepFraction = false)
            if (actualBytes != null && actualSizeIsExact) {
                context.getString(
                    R.string.image_input_too_large_exact,
                    imageLabel,
                    formatBinarySize(actualBytes, keepFraction = true),
                    limit,
                )
            } else {
                context.getString(R.string.image_input_too_large, imageLabel, limit)
            }
        }

        is ImagePersistenceFailure.TooManyPixels ->
            context.getString(
                R.string.image_input_too_many_pixels,
                imageLabel,
                formatPixelCount(actualPixels),
                formatPixelCount(limitPixels),
            )

        is ImagePersistenceFailure.UrlTooLong -> context.getString(
            R.string.image_input_url_too_long,
            formatBinarySize(limitBytes.toLong(), false),
        )
        ImagePersistenceFailure.EmptyImage ->
            context.getString(R.string.image_input_empty, imageLabel)
        ImagePersistenceFailure.InvalidImage ->
            context.getString(R.string.image_input_invalid, imageLabel)
        ImagePersistenceFailure.UnsupportedSource ->
            context.getString(R.string.image_input_unsupported, imageLabel)
        ImagePersistenceFailure.ReadFailed ->
            context.getString(R.string.image_input_read_failed, imageLabel)
        ImagePersistenceFailure.DownloadTimedOut ->
            context.getString(R.string.image_input_download_timeout, imageLabel)
        ImagePersistenceFailure.DownloadFailed ->
            context.getString(R.string.image_input_download_failed, imageLabel)
        ImagePersistenceFailure.StorageFailed ->
            context.getString(R.string.image_input_storage_failed, imageLabel)
    }
}

internal fun ImagePersistenceFailure.toGeneratedImageMessage(context: Context): String = when (this) {
    is ImagePersistenceFailure.TooLarge ->
        context.getString(R.string.generated_image_too_large, formatBinarySize(limitBytes, false))

    is ImagePersistenceFailure.TooManyPixels ->
        context.getString(R.string.generated_image_too_many_pixels, formatPixelCount(limitPixels))

    is ImagePersistenceFailure.UrlTooLong ->
        context.getString(
            R.string.generated_image_url_too_long,
            formatBinarySize(limitBytes.toLong(), false),
        )

    ImagePersistenceFailure.DownloadTimedOut ->
        context.getString(R.string.generated_image_download_timeout)
    ImagePersistenceFailure.DownloadFailed ->
        context.getString(R.string.generated_image_download_failed)
    ImagePersistenceFailure.EmptyImage -> context.getString(R.string.generated_image_empty)
    ImagePersistenceFailure.InvalidImage -> context.getString(R.string.generated_image_invalid)
    ImagePersistenceFailure.UnsupportedSource -> context.getString(R.string.generated_image_unsupported)
    ImagePersistenceFailure.ReadFailed -> context.getString(R.string.generated_image_read_failed)
    ImagePersistenceFailure.StorageFailed -> context.getString(R.string.generated_image_storage_failed)
}

private fun formatBinarySize(bytes: Long, keepFraction: Boolean): String {
    val mib = bytes.toDouble() / (1024.0 * 1024.0)
    if (mib >= 1.0) {
        val format = if (!keepFraction && mib % 1.0 == 0.0) "%.0f MiB" else "%.1f MiB"
        return String.format(Locale.ROOT, format, mib)
    }
    val kib = bytes.toDouble() / 1024.0
    if (kib >= 1.0) {
        val format = if (!keepFraction && kib % 1.0 == 0.0) "%.0f KiB" else "%.1f KiB"
        return String.format(Locale.ROOT, format, kib)
    }
    return "$bytes B"
}

private fun formatPixelCount(pixels: Long): String =
    String.format(Locale.ROOT, "%.1f MP", pixels.toDouble() / 1_000_000.0)
