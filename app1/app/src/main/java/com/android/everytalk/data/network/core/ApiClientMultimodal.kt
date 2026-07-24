package com.android.everytalk.data.network

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.ImageGenerationResponse
import com.android.everytalk.data.DataClass.GitHubRelease
import com.android.everytalk.models.SelectedMediaItem
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import io.ktor.utils.io.streams.asInput
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import android.util.Base64
import kotlinx.coroutines.CancellationException as CoroutineCancellationException

private const val MAX_INLINE_ATTACHMENT_BYTES = 10L * 1024L * 1024L
private const val MAX_MODELS_RESPONSE_BYTES = 4L * 1024L * 1024L
internal suspend fun buildDirectMultimodalRequest(
    request: ChatRequest,
    attachments: List<com.android.everytalk.models.SelectedMediaItem>,
    context: Context
): ChatRequest {
    val inlineParts = mutableListOf<com.android.everytalk.data.DataClass.ApiContentPart.InlineData>()
    val documentTexts = mutableListOf<String>()

    attachments.forEach { item ->
        when (item) {
            is com.android.everytalk.models.SelectedMediaItem.ImageFromUri -> {
                val mime = context.contentResolver.getType(item.uri) ?: "image/jpeg"
                val bytes = readInlineAttachmentBytes(context, item.uri, "图片")
                if (bytes != null && isImageMime(mime)) {
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    inlineParts.add(
                        com.android.everytalk.data.DataClass.ApiContentPart.InlineData(
                            base64Data = b64,
                            mimeType = mime
                        )
                    )
                }
            }
            is com.android.everytalk.models.SelectedMediaItem.ImageFromBitmap -> {
                if (item.bitmapData.isNotBlank() && isImageMime(item.mimeType)) {
                    val encodedLength = item.bitmapData.count { !it.isWhitespace() }.toLong()
                    ensureInlineAttachmentSize("图片", ((encodedLength + 3L) / 4L) * 3L)
                    inlineParts.add(
                        com.android.everytalk.data.DataClass.ApiContentPart.InlineData(
                            base64Data = item.bitmapData,
                            mimeType = item.mimeType
                        )
                    )
                }
            }
            is com.android.everytalk.models.SelectedMediaItem.Audio -> {
                // Audio item already contains base64 data
                val mime = item.mimeType
                ensureInlineAttachmentSize("音频", item.data.length * 3L / 4L)
                inlineParts.add(
                    com.android.everytalk.data.DataClass.ApiContentPart.InlineData(
                        base64Data = item.data,
                        mimeType = mime
                    )
                )
            }
            is com.android.everytalk.models.SelectedMediaItem.GenericFile -> {
                val mime = item.mimeType
                if (isImageMime(mime) || isAudioMime(mime) || isVideoMime(mime)) {
                    val bytes = readInlineAttachmentBytes(context, item.uri, item.displayName)
                    if (bytes != null) {
                        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        inlineParts.add(
                            com.android.everytalk.data.DataClass.ApiContentPart.InlineData(
                                base64Data = b64,
                                mimeType = mime
                            )
                        )
                    }
                } else {
                    // 尝试提取文档文本
                    // Qwen 和 Gemini 模型支持原生文档上传，跳过文本提取，直接传递文件
                    val isQwen = request.model.contains("qwen", ignoreCase = true)
                    val isGemini = request.model.contains("gemini", ignoreCase = true)
                    val isPdf = mime == "application/pdf"

                    if (isQwen) {
                        val fileName = item.displayName
                        // 读取文件字节并转为 Base64，以便 OpenAIDirectClient 上传
                        val bytes = readInlineAttachmentBytes(context, item.uri, fileName)

                        if (bytes != null) {
                            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            inlineParts.add(
                                com.android.everytalk.data.DataClass.ApiContentPart.InlineData(
                                    base64Data = b64,
                                    mimeType = "file_upload_marker|$mime|$fileName" // 使用特殊 mimeType 标记，携带文件名
                                )
                            )
                        }
                    } else if (isGemini && isPdf) {
                        // Gemini 原生支持 PDF，直接通过 inlineData 传递
                        val bytes = readInlineAttachmentBytes(context, item.uri, item.displayName)

                        if (bytes != null) {
                            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            inlineParts.add(
                                com.android.everytalk.data.DataClass.ApiContentPart.InlineData(
                                    base64Data = b64,
                                    mimeType = mime
                                )
                            )
                        }
                    } else {
                        val text = DocumentProcessor.extractText(context, item.uri, mime)
                        if (!text.isNullOrBlank()) {
                            val fileName = item.displayName
                            documentTexts.add("--- Begin of document: $fileName ---\n$text\n--- End of document ---")
                        }
                    }
                }
            }
        }
    }

    if (inlineParts.isEmpty() && documentTexts.isEmpty()) return request

    val msgs = request.messages.toMutableList()
    val lastUserIdx = msgs.indexOfLast { it.role == "user" }
    if (lastUserIdx < 0) return request

    val lastMsg = msgs[lastUserIdx]
    
    // 构造文档文本部分
    val documentContentParts = documentTexts.map { 
        com.android.everytalk.data.DataClass.ApiContentPart.Text(it) 
    }

    val newParts = when (lastMsg) {
        is com.android.everytalk.data.DataClass.PartsApiMessage -> {
            val existing = lastMsg.parts.toMutableList()
            // 先放文档，再放原消息，最后放多媒体
            existing.addAll(0, documentContentParts)
            existing.addAll(inlineParts)
            existing.toList()
        }
        is com.android.everytalk.data.DataClass.SimpleTextApiMessage -> {
            val list = mutableListOf<com.android.everytalk.data.DataClass.ApiContentPart>()
            list.addAll(documentContentParts)
            if (lastMsg.content.isNotBlank()) {
                list.add(com.android.everytalk.data.DataClass.ApiContentPart.Text(lastMsg.content))
            }
            list.addAll(inlineParts)
            list.toList()
        }
    }

    val upgraded = com.android.everytalk.data.DataClass.PartsApiMessage(
        role = "user",
        parts = newParts
    )
    msgs[lastUserIdx] = upgraded
    return request.copy(messages = msgs)
}

private fun isImageMime(mime: String?): Boolean {
    if (mime == null) return false
    val m = mime.lowercase()
    return m.startsWith("image/")
}

private fun isAudioMime(mime: String?): Boolean {
    if (mime == null) return false
    val m = mime.lowercase()
    return m.startsWith("audio/")
}

private fun isVideoMime(mime: String?): Boolean {
    if (mime == null) return false
    val m = mime.lowercase()
    return m.startsWith("video/")
}
