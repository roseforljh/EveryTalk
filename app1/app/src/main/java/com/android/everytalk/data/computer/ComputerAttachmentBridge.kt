package com.android.everytalk.data.computer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.util.storage.FileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
    private val attachmentsForConversation: (String) -> List<SelectedMediaItem.GenericFile>,
    private val onDownloaded: suspend (String, SelectedMediaItem.GenericFile) -> Unit = { _, _ -> },
) {
    private val applicationContext = context.applicationContext
    private val fileManager = FileManager(applicationContext)

    suspend fun resolveUpload(conversationId: String, attachmentId: String): ComputerUploadSource? =
        withContext(Dispatchers.IO) {
            val attachment = attachmentsForConversation(conversationId).firstOrNull { it.id == attachmentId }
                ?: return@withContext null
            val localFile = attachment.filePath?.let(::File)?.takeIf(File::isFile)
            if (localFile != null) {
                return@withContext ComputerUploadSource(
                    displayName = attachment.displayName,
                    mimeType = attachment.mimeType,
                    size = localFile.length(),
                    openStream = { FileInputStream(localFile) },
                )
            }
            val uri = attachment.uri
            val size = querySize(uri)
            ComputerUploadSource(
                displayName = attachment.displayName,
                mimeType = attachment.mimeType,
                size = size,
                openStream = {
                    applicationContext.contentResolver.openInputStream(uri)
                        ?: throw ComputerException(ComputerErrorCodes.UPLOAD_INTERRUPTED, "无法打开本地附件")
                },
            )
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

    private fun sanitizeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .filter { it.isLetterOrDigit() || it in "._- " }
            .trim()
            .take(120)
        return base.ifBlank { "download.bin" }
    }
}
