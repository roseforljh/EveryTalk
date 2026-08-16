package com.android.everytalk.data.DataClass
import android.content.Context
import android.net.Uri
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.models.toAttachmentContextParts
import com.android.everytalk.ui.components.MarkdownPart
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID
import com.android.everytalk.ui.components.MarkdownPartSerializer
import com.android.everytalk.data.network.TokenUsage
import com.android.everytalk.data.skill.MessageSkillReference

private fun SelectedMediaItem.ImageFromBitmap.encodedDataOrNull(
    uriEncoder: (Uri) -> String?,
): String? = bitmapData.takeIf { it.isNotBlank() }
    ?: filePath
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?.takeIf { it.isFile && it.length() > 0L }
        ?.let { uriEncoder(Uri.fromFile(it)) }
        ?.takeIf { it.isNotBlank() }

@Serializable
enum class Sender {
    User,
    AI,
    System,
    Tool
}

object MessageToolIds {
    const val WEB_SEARCH = "web_search"
    const val MCP = "mcp"
    const val AGENT = "agent"
}

@Serializable
enum class ExecutionStepType {
    Search,
    Web,
    Tool,
    Agent,
}

@Serializable
data class ExecutionStep(
    val id: String,
    val type: ExecutionStepType,
    val title: String,
    val labels: List<String> = emptyList(),
    val completed: Boolean = false,
    val executionId: String? = null,
    /**
     * 这次工具调用前，模型刚产生且尚未归入前序步骤的思考文本。
     * null 表示旧版消息没有顺序信息，空字符串表示新版消息在调用前没有文本。
     */
    val reasoningBefore: String? = null,
)

/** 用户消息正文中的有序内容。Skill 引用保持原位置并携带冻结版本。 */
@Serializable
sealed class MessageContentPart {
    @Serializable
    data class Text(val text: String) : MessageContentPart()

    @Serializable
    data class SkillReference(val reference: MessageSkillReference) : MessageContentPart()
}

fun List<MessageContentPart>.toApiText(fallback: String): String {
    if (isEmpty()) return fallback
    return joinToString("") { part ->
        when (part) {
            is MessageContentPart.Text -> part.text
            is MessageContentPart.SkillReference -> {
                val ref = part.reference
                "<skill_ref id=\"${ref.skillId}\" content_hash=\"${ref.contentHash}\">${ref.displayName}</skill_ref>"
            }
        }
    }
}

/**
 * AI 单条回复的有序输出事件。
 *
 * Content、Reasoning 只和相邻同类增量合并，Tool 一到达就形成边界，
 * 因此正文、思考和工具不会在渲染时被重新排序。
 */
@Serializable
sealed class ExecutionTraceEvent {
    /** 模型对用户可见的正式正文，相邻增量会合并为一段。 */
    @Serializable
    data class Content(val text: String) : ExecutionTraceEvent()

    @Serializable
    data class Reasoning(val text: String) : ExecutionTraceEvent()

    @Serializable
    data class Tool(val step: ExecutionStep) : ExecutionTraceEvent()
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
    val contentParts: List<MessageContentPart> = emptyList(),
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
    val parts: List<MarkdownPart> = emptyList(),
    val executionStatus: String? = null,
    val executionSteps: List<ExecutionStep> = emptyList(),
    val executionTrace: List<ExecutionTraceEvent> = emptyList(),
    /** 执行过程结束时间。用于历史消息准确显示耗时；null 表示仍在执行或旧消息没有记录。 */
    val executionFinishedAt: Long? = null,
    val enabledToolIds: List<String> = emptyList(),
    /** 记录本条消息发送时实际使用的服务器，只用于历史展示与本地审计。 */
    val computerIdSnapshot: String? = null,
    /** 记录本条消息发送时实际使用的持久 Workspace。 */
    val workspaceIdSnapshot: String? = null,
    // 新增：记录发送消息时使用的模型名称
    val modelName: String? = null,
    // 新增：记录发送消息时使用的提供商名称
    val providerName: String? = null,
    val tokenUsage: TokenUsage? = null,
    val contextUsageSnapshot: ContextUsageSnapshot? = null,
    val contextCompressionState: ContextCompressionState? = null,
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
        val apiText = contentParts.toApiText(text)
        return if (attachments.isNotEmpty()) {
            val parts = mutableListOf<ApiContentPart>()
            if (apiText.isNotBlank()) {
                parts.add(ApiContentPart.Text(apiText))
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
                        mediaItem.encodedDataOrNull(uriEncoder)?.let { bitmapData ->
                            parts.add(ApiContentPart.InlineData(base64Data = bitmapData, mimeType = mediaItem.mimeType))
                        }
                    }
                    is SelectedMediaItem.GenericFile -> {
                        parts.addAll(mediaItem.toAttachmentContextParts().map { ApiContentPart.Text(it) })
                    }
                    is SelectedMediaItem.Audio -> {
                        // 音频数据已经是base64格式
                        parts.add(ApiContentPart.InlineData(base64Data = mediaItem.data, mimeType = mediaItem.mimeType))
                    }
                }
            }
            PartsApiMessage(id = id, role = role, parts = parts, name = name)
        } else {
            SimpleTextApiMessage(id = id, role = role, content = contentParts.toApiText(text), name = name)
        }
    }

    // 🔥 新增：接受Context的方法，用于获取真实MIME类型
    fun toApiMessage(uriEncoder: (Uri) -> String?, context: Context): AbstractApiMessage {
        val apiText = contentParts.toApiText(text)
        return if (attachments.isNotEmpty()) {
            val parts = mutableListOf<ApiContentPart>()
            if (apiText.isNotBlank()) {
                parts.add(ApiContentPart.Text(apiText))
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
                        mediaItem.encodedDataOrNull(uriEncoder)?.let { bitmapData ->
                            parts.add(ApiContentPart.InlineData(base64Data = bitmapData, mimeType = mediaItem.mimeType))
                        }
                    }
                    is SelectedMediaItem.GenericFile -> {
                        parts.addAll(mediaItem.toAttachmentContextParts().map { ApiContentPart.Text(it) })
                    }
                    is SelectedMediaItem.Audio -> {
                        // 音频数据已经是base64格式
                        parts.add(ApiContentPart.InlineData(base64Data = mediaItem.data, mimeType = mediaItem.mimeType))
                    }
                }
            }
            PartsApiMessage(id = id, role = role, parts = parts, name = name)
        } else {
            SimpleTextApiMessage(id = id, role = role, content = contentParts.toApiText(text), name = name)
        }
    }
}

fun hasReviewableExecutionProcess(
    reasoningText: String?,
    executionSteps: List<ExecutionStep>,
    executionTrace: List<ExecutionTraceEvent> = emptyList(),
    webSearchResults: List<WebSearchResult>?,
    executionStatus: String? = null,
): Boolean = !reasoningText.isNullOrBlank() ||
    executionSteps.isNotEmpty() ||
    executionTrace.any { it !is ExecutionTraceEvent.Content } ||
    !webSearchResults.isNullOrEmpty() ||
    !executionStatus.isNullOrBlank()

fun Message.hasReviewableExecutionProcess(): Boolean = hasReviewableExecutionProcess(
    reasoningText = reasoning,
    executionSteps = executionSteps,
    executionTrace = executionTrace,
    webSearchResults = webSearchResults,
    executionStatus = executionStatus,
)
