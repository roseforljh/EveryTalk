package com.example.everytalk.data.DataClass
import android.net.Uri
import com.example.everytalk.models.SelectedMediaItem
import kotlinx.serialization.Serializable
import java.util.UUID

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
    val outputType: String = "general"
) : IMessage {
    // 实现IMessage接口的role属性
    override val role: String
        get() = sender.toRole()
    
    // 转换为API消息
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
                            parts.add(ApiContentPart.InlineData(base64Data = base64, mimeType = mediaItem.mimeType))
                        }
                    }
                    else -> {} // Handle other attachment types if necessary
                }
            }
            PartsApiMessage(id = id, role = role, parts = parts, name = name)
        } else {
            SimpleTextApiMessage(id = id, role = role, content = text, name = name)
        }
    }
}