package com.android.everytalk.data.computer

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Base64InputStream
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.util.storage.FileManager
import com.android.everytalk.util.image.ImageHandlingLimits
import com.android.everytalk.util.image.decodedBase64SizeOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class ComputerUploadSource(
    val displayName: String,
    val mimeType: String,
    val size: Long?,
    val openStream: () -> InputStream,
)

data class ComputerDownloadedAttachment(
    val attachment: SelectedMediaItem.GenericFile,
    val transfer: ComputerStreamTransferResult,
)

/** 只解析当前会话已有附件，并把下载结果原子保存到应用内部附件目录。 */
class ComputerAttachmentBridge(
    context: Context,
    private val attachmentsForConversation: (String) -> List<SelectedMediaItem>,
    private val onDownloaded: suspend (String, SelectedMediaItem.GenericFile) -> Unit = { _, _ -> },
) {
    private val applicationContext = context.applicationContext
    private val fileManager = FileManager(applicationContext)

    suspend fun resolveUpload(conversationId: String, attachmentId: String): ComputerUploadSource? =
        withContext(Dispatchers.IO) {
            val attachment = attachmentsForConversation(conversationId).firstOrNull { it.id == attachmentId }
                ?: return@withContext null
            val localFile = attachment.localFilePath()?.let(::File)?.takeIf(File::isFile)
            if (localFile != null) {
                return@withContext ComputerUploadSource(
                    displayName = attachment.uploadDisplayName(),
                    mimeType = attachment.mimeType,
                    size = localFile.length(),
                    openStream = { FileInputStream(localFile) },
                )
            }
            when (attachment) {
                is SelectedMediaItem.GenericFile -> attachment.uri.toUploadSource(attachment.displayName, attachment.mimeType)
                is SelectedMediaItem.ImageFromUri -> attachment.uri.toUploadSource(
                    displayName = queryDisplayName(attachment.uri) ?: attachment.uploadDisplayName(),
                    mimeType = attachment.mimeType,
                )
                is SelectedMediaItem.ImageFromBitmap -> attachment.bitmapData.toBase64UploadSource(
                    displayName = attachment.uploadDisplayName(),
                    mimeType = attachment.mimeType,
                    maxBytes = ImageHandlingLimits.USER_UPLOAD_MAX_BYTES,
                )
                is SelectedMediaItem.Audio -> attachment.data.toBase64UploadSource(
                    displayName = attachment.uploadDisplayName(),
                    mimeType = attachment.mimeType,
                    maxBytes = MAX_AUDIO_UPLOAD_BYTES,
                )
            }
        }

    suspend fun receiveDownload(
        conversationId: String,
        suggestedName: String,
        writer: suspend (OutputStream) -> ComputerStreamTransferResult,
    ): ComputerDownloadedAttachment = withContext(Dispatchers.IO) {
        val directory = File(applicationContext.filesDir, FileManager.CHAT_ATTACHMENTS_DIR).apply { mkdirs() }
        val safeName = sanitizeName(suggestedName)
        val finalFile = File(directory, "computer_${UUID.randomUUID()}_$safeName")
        val temporaryFile = File(directory, ".${finalFile.name}.part")
        try {
            val transfer = FileOutputStream(temporaryFile).use { output ->
                val result = writer(output)
                output.fd.sync()
                result
            }
            if (temporaryFile.length() != transfer.bytes || !temporaryFile.renameTo(finalFile)) {
                throw ComputerException(ComputerErrorCodes.DOWNLOAD_INTERRUPTED, "本地下载文件校验失败", retryable = true)
            }
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(finalFile.extension.lowercase())
                ?: "application/octet-stream"
            val attachment = SelectedMediaItem.GenericFile(
                uri = fileManager.getFileProviderUri(finalFile),
                id = "attachment_${UUID.randomUUID()}",
                displayName = safeName,
                mimeType = mime,
                filePath = finalFile.absolutePath,
            )
            onDownloaded(conversationId, attachment)
            ComputerDownloadedAttachment(attachment, transfer)
        } finally {
            if (temporaryFile.exists()) temporaryFile.delete()
        }
    }

    private fun querySize(uri: Uri): Long? {
        applicationContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index).takeIf { it >= 0 }
            }
        }
        return applicationContext.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.statSize.takeIf { it >= 0 }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        applicationContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && !cursor.isNull(index)) {
                    return cursor.getString(index)?.takeIf(String::isNotBlank)
                }
            }
        }
        return null
    }

    private fun Uri.toUploadSource(displayName: String, mimeType: String): ComputerUploadSource =
        ComputerUploadSource(
            displayName = displayName,
            mimeType = mimeType,
            size = querySize(this),
            openStream = {
                applicationContext.contentResolver.openInputStream(this)
                    ?: throw ComputerException(ComputerErrorCodes.UPLOAD_INTERRUPTED, "无法打开本地附件")
            },
        )

    private fun String.toBase64UploadSource(
        displayName: String,
        mimeType: String,
        maxBytes: Long,
    ): ComputerUploadSource? {
        val decodedSize = decodedBase64SizeOrNull(this)?.takeIf { it in 1..maxBytes } ?: return null
        return ComputerUploadSource(
            displayName = displayName,
            mimeType = mimeType,
            size = decodedSize,
            openStream = {
                Base64InputStream(
                    ByteArrayInputStream(toByteArray(Charsets.US_ASCII)),
                    Base64.DEFAULT,
                )
            },
        )
    }

    private fun SelectedMediaItem.localFilePath(): String? = when (this) {
        is SelectedMediaItem.GenericFile -> filePath
        is SelectedMediaItem.ImageFromUri -> filePath
        is SelectedMediaItem.ImageFromBitmap -> filePath
        is SelectedMediaItem.Audio -> filePath
    }

    private fun SelectedMediaItem.uploadDisplayName(): String = when (this) {
        is SelectedMediaItem.GenericFile -> displayName
        is SelectedMediaItem.ImageFromUri,
        is SelectedMediaItem.ImageFromBitmap -> "image-${id.take(8)}.${mimeType.fileExtension("jpg")}"
        is SelectedMediaItem.Audio -> "audio-${id.take(8)}.${mimeType.fileExtension("bin")}"
    }

    private fun String.fileExtension(fallback: String): String = MimeTypeMap.getSingleton()
        .getExtensionFromMimeType(this)
        ?.takeIf(String::isNotBlank)
        ?: fallback

    private fun sanitizeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .filter { it.isLetterOrDigit() || it in "._- " }
            .trim()
            .take(120)
        return base.ifBlank { "download.bin" }
    }

    private companion object {
        const val MAX_AUDIO_UPLOAD_BYTES = 64L * 1024L * 1024L
    }
}
