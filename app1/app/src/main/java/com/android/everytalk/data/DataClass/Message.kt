package com.android.everytalk.data.DataClass
import android.content.Context
import android.net.Uri
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.components.MarkdownPart
import kotlinx.serialization.Serializable
import java.util.UUID
import com.android.everytalk.ui.components.MarkdownPartSerializer

@Serializable
enum class Sender {
    User,
    AI,
    System,
    Tool
}

// 将Sender枚举值映射到API角色字符串
fun Sender.toRole(): String = when(this) {
    Sender.User -> "user"
    Sender.AI -> "assistant"
    Sender.System -> "system"
    Sender.Tool -> "tool"
}

@Serializable
data class Message(
    override val id: String = UUID.randomUUID().toString(),
    val text: String,
    val sender: Sender,
    val reasoning: String? = null,
    val contentStarted: Boolean = false,
    val isError: Boolean = false,
    override val name: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isPlaceholderName: Boolean = false,
    val webSearchResults: List<WebSearchResult>? = null,
    val currentWebSearchStage: String? = null,
    val imageUrls: List<String>? = null,
    val attachments: List<SelectedMediaItem> = emptyList(),
    val outputType: String = "general",
    @Serializable(with = MarkdownPartSerializer::class)
    val parts: List<MarkdownPart> = emptyList()
) : IMessage {
    // 检查消息是否包含内联图片
    fun hasInlineImages(): Boolean {
        return parts.any { it is MarkdownPart.InlineImage }
    }
    // 实现IMessage接口的role属性
    override val role: String
        get() = sender.toRole()
    
    // 转换为API消息 - 保留原方法兼容性
    fun toApiMessage(uriEncoder: (Uri) -> String?): AbstractApiMessage {
        return if (attachments.isNotEmpty()) {
            val parts = mutableListOf<ApiContentPart>()
            if (text.isNotBlank()) {
                parts.add(ApiContentPart.Text(text))
            }
            attachments.forEach { mediaItem ->
                when (mediaItem) {
                    is SelectedMediaItem.ImageFromUri -> {
                        uriEncoder(mediaItem.uri)?.let { base64 ->
                            // 🔥 修复：使用硬编码值作为后备，但优先使用真实MIME类型
                            parts.add(ApiContentPart.InlineData(base64Data = base64, mimeType = mediaItem.mimeType))
                        }
                    }
                    is SelectedMediaItem.ImageFromBitmap -> {
                        // 处理Bitmap类型的图片
                        mediaItem.bitmap?.let { bitmap ->
                            // 将Bitmap转为base64
                            val baos = java.io.ByteArrayOutputStream()
                            val format = if (mediaItem.mimeType.contains("png")) 
                                android.graphics.Bitmap.CompressFormat.PNG 
                            else 
                                android.graphics.Bitmap.CompressFormat.JPEG
                            bitmap.compress(format, 90, baos)
                            val base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                            parts.add(ApiContentPart.InlineData(base64Data = base64, mimeType = mediaItem.mimeType))
                        }
                    }
                    is SelectedMediaItem.GenericFile -> {
                        // 处理通用文件，但这里不转换为InlineData，由ApiClient处理
                    }
                    is SelectedMediaItem.Audio -> {
                        // 音频数据已经是base64格式
                        parts.add(ApiContentPart.InlineData(base64Data = mediaItem.data, mimeType = mediaItem.mimeType))
                    }
                }
            }
            PartsApiMessage(id = id, role = role, parts = parts, name = name)
        } else {
            SimpleTextApiMessage(id = id, role = role, content = text, name = name)
        }
    }

    // 🔥 新增：接受Context的方法，用于获取真实MIME类型
    fun toApiMessage(uriEncoder: (Uri) -> String?, context: Context): AbstractApiMessage {
        return if (attachments.isNotEmpty()) {
            val parts = mutableListOf<ApiContentPart>()
            if (text.isNotBlank()) {
                parts.add(ApiContentPart.Text(text))
            }
            attachments.forEach { mediaItem ->
                when (mediaItem) {
                    is SelectedMediaItem.ImageFromUri -> {
                        uriEncoder(mediaItem.uri)?.let { base64 ->
                            // 🔥 修复：从ContentResolver获取真实的MIME类型
                            val actualMimeType = try {
                                context.contentResolver.getType(mediaItem.uri) ?: mediaItem.mimeType
                            } catch (e: Exception) {
                                mediaItem.mimeType // 出错时使用默认值
                            }
                            parts.add(ApiContentPart.InlineData(base64Data = base64, mimeType = actualMimeType))
                        }
                    }
                    is SelectedMediaItem.ImageFromBitmap -> {
                        // 处理Bitmap类型的图片
                        mediaItem.bitmap?.let { bitmap ->
                            // 将Bitmap转为base64
                            val baos = java.io.ByteArrayOutputStream()
                            val format = if (mediaItem.mimeType.contains("png")) 
                                android.graphics.Bitmap.CompressFormat.PNG 
                            else 
                                android.graphics.Bitmap.CompressFormat.JPEG
                            bitmap.compress(format, 90, baos)
                            val base64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                            parts.add(ApiContentPart.InlineData(base64Data = base64, mimeType = mediaItem.mimeType))
                        }
                    }
                    is SelectedMediaItem.GenericFile -> {
                        // 处理通用文件，但这里不转换为InlineData，由ApiClient处理
                    }
                    is SelectedMediaItem.Audio -> {
                        // 音频数据已经是base64格式
                        parts.add(ApiContentPart.InlineData(base64Data = mediaItem.data, mimeType = mediaItem.mimeType))
                    }
                }
            }
            PartsApiMessage(id = id, role = role, parts = parts, name = name)
        } else {
            SimpleTextApiMessage(id = id, role = role, content = text, name = name)
        }
    }
}