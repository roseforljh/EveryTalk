package com.android.everytalk.data.network

import android.content.Context
import androidx.core.content.FileProvider
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.util.storage.FileManager
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal const val MAX_ATTACHMENT_PAGE_CHARS = 12_000

object AttachmentToolExecutor {
    suspend fun execute(
        context: Context,
        attachments: List<SelectedMediaItem.GenericFile>,
        arguments: JsonObject,
    ): JsonObject {
        val attachmentId = (arguments["attachment_id"] as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            .orEmpty()
        if (attachmentId.isEmpty()) return error("attachment_id 不能为空")
        val offset = arguments["offset"]?.let { value ->
            (value as? JsonPrimitive)?.longOrNull ?: return error("offset 必须为整数")
        } ?: 0L
        if (offset < 0L) return error("offset 不能为负数")
        val requestedChars = arguments["max_chars"]?.let { value ->
            (value as? JsonPrimitive)?.longOrNull ?: return error("max_chars 必须为整数")
        } ?: MAX_ATTACHMENT_PAGE_CHARS.toLong()
        if (requestedChars <= 0L) return error("max_chars 必须大于 0")
        val maxChars = requestedChars.coerceAtMost(MAX_ATTACHMENT_PAGE_CHARS.toLong()).toInt()
        val attachment = attachments.firstOrNull { it.id == attachmentId }
            ?: return error("当前会话中不存在附件 $attachmentId")
        val file = resolveAttachmentFile(context, attachment)
            ?: return error("附件文件不可访问")
        return try {
            val page = if (DocumentProcessor.isTextMime(attachment.mimeType)) {
                FileInputStream(file).use { input ->
                    DocumentProcessor.extractPlainTextPage(
                        inputStream = input,
                        offsetChars = offset,
                        maxInputBytes = file.length(),
                        maxOutputChars = maxChars,
                    )
                }
            } else {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    file,
                )
                DocumentProcessor.extractTextPage(
                    context = context,
                    uri = uri,
                    mimeType = attachment.mimeType,
                    offsetChars = offset,
                    maxOutputChars = maxChars,
                ) ?: return error("附件未提取到可读文本")
            }
            buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("attachment_id", JsonPrimitive(attachment.id))
                put("name", JsonPrimitive(attachment.displayName))
                put("mime_type", JsonPrimitive(attachment.mimeType))
                put("source_size_bytes", JsonPrimitive(file.length()))
                put("offset", JsonPrimitive(offset))
                put("content", JsonPrimitive(page.content))
                put("truncated", JsonPrimitive(page.truncated))
                put("complete", JsonPrimitive(!page.truncated))
                page.nextOffset?.let { put("next_offset", JsonPrimitive(it)) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            error(error.message ?: "附件读取失败")
        }
    }

    private fun resolveAttachmentFile(
        context: Context,
        attachment: SelectedMediaItem.GenericFile,
    ): File? {
        val path = attachment.filePath?.takeIf(String::isNotBlank) ?: return null
        val root = File(context.filesDir, FileManager.CHAT_ATTACHMENTS_DIR).canonicalFile
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        return file.takeIf { it.isFile && it.parentFile?.canonicalFile == root }
    }

    private fun error(message: String): JsonObject = buildJsonObject {
        put("ok", JsonPrimitive(false))
        put("error", JsonPrimitive(message))
    }
}
