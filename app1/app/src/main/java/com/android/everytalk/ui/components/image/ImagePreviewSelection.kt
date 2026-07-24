package com.android.everytalk.ui.components.image
import com.android.everytalk.statecontroller.*

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.models.SelectedMediaItem
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

internal data class ImagePreviewSelection(
    val clickedSource: String,
    val candidates: List<String>,
    val initialIndex: Int,
)

internal fun buildImagePreviewSelection(
    clickedSource: String,
    messages: List<Message>,
): ImagePreviewSelection {
    val normalizedClickedSource = normalizeImageSourceForComparison(clickedSource)
    val candidatesByNormalizedSource = linkedMapOf<String, String>()

    messages.forEach { message ->
        message.attachments.forEach { attachment ->
            val source = when (attachment) {
                is SelectedMediaItem.ImageFromUri -> attachment.filePath?.takeIf { it.isNotBlank() }
                    ?: attachment.uri.toString()
                is SelectedMediaItem.ImageFromBitmap -> attachment.model
                else -> null
            }
            if (!source.isNullOrBlank()) {
                candidatesByNormalizedSource.putIfAbsent(
                    normalizeImageSourceForComparison(source),
                    source,
                )
            }
        }
        message.imageUrls.orEmpty().forEach { source ->
            if (source.isNotBlank()) {
                candidatesByNormalizedSource.putIfAbsent(
                    normalizeImageSourceForComparison(source),
                    source,
                )
            }
        }
    }

    val candidates = candidatesByNormalizedSource.values.toList()
    val initialIndex = candidatesByNormalizedSource.keys.indexOf(normalizedClickedSource)
    return if (initialIndex >= 0) {
        ImagePreviewSelection(clickedSource, candidates, initialIndex)
    } else {
        ImagePreviewSelection(clickedSource, listOf(clickedSource), 0)
    }
}

internal fun normalizeImageSourceForComparison(source: String): String {
    val trimmed = source.trim()
    if (trimmed.startsWith("data:", ignoreCase = true)) return normalizeDataUri(trimmed)
    if (trimmed.startsWith("file:", ignoreCase = true)) {
        val path = runCatching { URI(trimmed).path }.getOrNull().orEmpty()
        return normalizeAbsolutePath(path)
    }
    if (trimmed.startsWith("/", ignoreCase = false) || WINDOWS_ABSOLUTE_PATH.matches(trimmed)) {
        return normalizeAbsolutePath(trimmed)
    }

    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed.replace('\\', '/')
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return trimmed.replace('\\', '/')
    return when (scheme) {
        "http", "https" -> buildString {
            append(scheme)
            append("://")
            append((uri.rawAuthority ?: "").lowercase(Locale.ROOT))
            append(uri.rawPath.orEmpty())
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
        "content" -> buildString {
            append("content://")
            append(uri.rawAuthority.orEmpty())
            append(uri.rawPath.orEmpty())
            uri.rawQuery?.let { append('?').append(it) }
        }
        else -> trimmed.replace('\\', '/')
    }
}

private fun normalizeAbsolutePath(path: String): String =
    File(path).absoluteFile.normalize().path.replace('\\', '/')

private fun normalizeDataUri(source: String): String {
    val commaIndex = source.indexOf(',')
    if (commaIndex < 0) return source.replace(WHITESPACE_REGEX, "").lowercase(Locale.ROOT)

    val digest = MessageDigest.getInstance("SHA-256")
    val normalizedHeader = source.substring(0, commaIndex)
        .replace(WHITESPACE_REGEX, "")
        .lowercase(Locale.ROOT)
    digest.update(normalizedHeader.toByteArray(Charsets.UTF_8))
    for (index in commaIndex + 1 until source.length) {
        val character = source[index]
        if (!character.isWhitespace()) digest.update(character.code.toByte())
    }
    return "data-sha256:" + digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[\\\\/].*")
private val WHITESPACE_REGEX = Regex("\\s+")
