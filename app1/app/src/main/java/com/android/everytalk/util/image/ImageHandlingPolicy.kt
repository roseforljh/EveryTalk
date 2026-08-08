package com.android.everytalk.util.image

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

internal fun ImagePersistenceFailure.toUserImageMessage(fileName: String?): String {
    val imageLabel = fileName?.takeIf(String::isNotBlank)?.let { "图片“$it”" } ?: "所选图片"
    return when (this) {
        is ImagePersistenceFailure.TooLarge -> {
            val limit = formatBinarySize(limitBytes, keepFraction = false)
            if (actualBytes != null && actualSizeIsExact) {
                "${imageLabel}大小为 ${formatBinarySize(actualBytes, keepFraction = true)}，超过最大 $limit 限制，请选择更小的图片。"
            } else {
                "${imageLabel}大小已超过最大 $limit 限制，请选择更小的图片。"
            }
        }

        is ImagePersistenceFailure.TooManyPixels ->
            "${imageLabel}像素数为 ${formatPixelCount(actualPixels)}，超过最大 ${formatPixelCount(limitPixels)} 限制，请选择尺寸更小的图片。"

        is ImagePersistenceFailure.UrlTooLong -> "远程图片地址超过最大 ${formatBinarySize(limitBytes.toLong(), false)} 限制。"
        ImagePersistenceFailure.EmptyImage -> "$imageLabel 内容为空，请重新选择。"
        ImagePersistenceFailure.InvalidImage -> "$imageLabel 不是可识别的有效图片，请重新选择。"
        ImagePersistenceFailure.UnsupportedSource -> "$imageLabel 来源不受支持，请重新选择。"
        ImagePersistenceFailure.ReadFailed -> "无法读取$imageLabel，请重新选择。"
        ImagePersistenceFailure.DownloadTimedOut -> "下载${imageLabel}超时，请稍后重试。"
        ImagePersistenceFailure.DownloadFailed -> "下载${imageLabel}失败，请稍后重试。"
        ImagePersistenceFailure.StorageFailed -> "保存${imageLabel}失败，请重试。"
    }
}

internal fun ImagePersistenceFailure.toGeneratedImageMessage(): String = when (this) {
    is ImagePersistenceFailure.TooLarge ->
        "图片已生成，但结果超过最大 ${formatBinarySize(limitBytes, false)} 限制，未能保存。"

    is ImagePersistenceFailure.TooManyPixels ->
        "图片已生成，但像素数超过最大 ${formatPixelCount(limitPixels)} 限制，未能保存。"

    is ImagePersistenceFailure.UrlTooLong ->
        "图片已生成，但远程地址超过最大 ${formatBinarySize(limitBytes.toLong(), false)} 限制，未能下载。"

    ImagePersistenceFailure.DownloadTimedOut -> "图片已生成，但远程下载超过 60 秒，未能保存。"
    ImagePersistenceFailure.DownloadFailed -> "图片已生成，但远程下载失败，未能保存。"
    ImagePersistenceFailure.EmptyImage -> "图片生成接口返回了空图片。"
    ImagePersistenceFailure.InvalidImage -> "图片生成接口返回了无法识别的图片数据。"
    ImagePersistenceFailure.UnsupportedSource -> "图片生成接口返回了不受支持的图片来源。"
    ImagePersistenceFailure.ReadFailed -> "图片已生成，但读取图片数据失败。"
    ImagePersistenceFailure.StorageFailed -> "图片已生成，但写入本地存储失败。"
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
