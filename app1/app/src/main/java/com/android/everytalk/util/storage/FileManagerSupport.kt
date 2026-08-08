package com.android.everytalk.util.storage

import java.io.File

internal fun guessExtensionFromMime(mime: String?): String {
    return when ((mime ?: "").substringBefore(';').trim().lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/bmp" -> "bmp"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "image/avif" -> "avif"
        else -> "bin"
    }
}

internal fun FileManager.getChatAttachmentsDir(): File {
    val directory = File(context.filesDir, FileManager.CHAT_ATTACHMENTS_DIR)
    if (!directory.exists() && !directory.mkdirs()) {
        logger.error("Failed to create chat attachments directory")
    }
    return directory
}
