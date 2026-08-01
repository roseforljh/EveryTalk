package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.AbstractApiMessage
import com.android.everytalk.data.DataClass.ApiContentPart
import com.android.everytalk.data.DataClass.ContextUsageSnapshot
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.ModelTokenLimits
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.PartsApiMessage
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.network.PromptCachePolicy

internal const val REQUEST_OVERHEAD_TOKENS = 8L
private const val MESSAGE_OVERHEAD_TOKENS = 12L
private const val UNKNOWN_MEDIA_TOKENS = 4_096L

internal data class RequestTokenEstimate(
    val systemPromptTokens: Long,
    val conversationTextTokens: Long,
    val mediaTokens: Long,
    val toolSchemaTokens: Long,
    val protocolOverheadTokens: Long,
) {
    val totalInputTokens: Long = systemPromptTokens +
        conversationTextTokens +
        mediaTokens +
        toolSchemaTokens +
        protocolOverheadTokens
}

internal object RequestTokenEstimator {
    fun estimateMessageTokens(
        message: AbstractApiMessage,
        mediaTokenEstimator: (ApiContentPart) -> Long = { UNKNOWN_MEDIA_TOKENS },
    ): Long = estimate(
        messages = listOf(message),
        tools = null,
        mediaTokenEstimator = mediaTokenEstimator,
    ).totalInputTokens - REQUEST_OVERHEAD_TOKENS

    fun estimate(
        messages: List<AbstractApiMessage>,
        tools: List<Map<String, Any>>?,
        mediaTokenEstimator: (ApiContentPart) -> Long = { UNKNOWN_MEDIA_TOKENS },
    ): RequestTokenEstimate {
        var systemPromptTokens = 0L
        var conversationTextTokens = 0L
        var mediaTokens = 0L
        var protocolOverheadTokens = REQUEST_OVERHEAD_TOKENS
        val normalizedTools = PromptCachePolicy.normalizeTools(tools)
        val toolSchemaTokens = normalizedTools
            ?.let(PromptCachePolicy::normalizedToolSchemaJson)
            ?.let(::estimateText)
            ?.plus(normalizedTools.size * MESSAGE_OVERHEAD_TOKENS)
            ?: 0L

        messages.forEach { message ->
            protocolOverheadTokens += MESSAGE_OVERHEAD_TOKENS
            protocolOverheadTokens += estimateText(message.role)
            protocolOverheadTokens += estimateText(message.name.orEmpty())
            val isSystem = message.role.equals("system", ignoreCase = true)
            fun addText(text: String) {
                if (isSystem) {
                    systemPromptTokens += estimateText(text)
                } else {
                    conversationTextTokens += estimateText(text)
                }
            }
            when (message) {
                is SimpleTextApiMessage -> addText(message.content)
                is PartsApiMessage -> message.parts.forEach { part ->
                    when (part) {
                        is ApiContentPart.Text -> addText(part.text)
                        is ApiContentPart.FileUri,
                        is ApiContentPart.InlineData -> {
                            mediaTokens += mediaTokenEstimator(part).coerceAtLeast(0L)
                        }
                    }
                }
            }
        }

        return RequestTokenEstimate(
            systemPromptTokens = systemPromptTokens,
            conversationTextTokens = conversationTextTokens,
            mediaTokens = mediaTokens,
            toolSchemaTokens = toolSchemaTokens,
            protocolOverheadTokens = protocolOverheadTokens,
        )
    }

    internal fun estimateText(text: String): Long {
        var compactAscii = 0L
        var structuralAscii = 0L
        var cjk = 0L
        var other = 0L
        text.forEach { character ->
            when {
                character.code <= 0x7f && (character.isLetterOrDigit() || character.isWhitespace()) -> compactAscii++
                character.code <= 0x7f -> structuralAscii++
                character.isCjk() -> cjk++
                else -> other++
            }
        }
        return compactAscii.ceilDiv(4L) + structuralAscii.ceilDiv(2L) + cjk + other.ceilDiv(2L)
    }
}

internal fun RequestTokenEstimate.toContextUsageSnapshot(
    messageId: String,
    configId: String? = null,
    reservedOutputTokens: Long,
    contextWindowTokens: Long,
): ContextUsageSnapshot = ContextUsageSnapshot(
    messageId = messageId,
    configId = configId,
    systemPromptTokens = systemPromptTokens,
    conversationTextTokens = conversationTextTokens,
    mediaTokens = mediaTokens,
    toolSchemaTokens = toolSchemaTokens,
    protocolOverheadTokens = protocolOverheadTokens,
    reservedOutputTokens = reservedOutputTokens,
    contextWindowTokens = contextWindowTokens,
)

internal fun estimateConversationDraftContextUsage(
    messages: List<Message>,
    draftText: String,
    systemPrompt: String?,
    tools: List<Map<String, Any>>?,
    limits: ModelTokenLimits,
    configId: String? = null,
): ContextUsageSnapshot {
    val apiMessages = buildList {
        systemPrompt?.trim()?.takeIf(String::isNotEmpty)?.let { prompt ->
            add(SimpleTextApiMessage(id = "draft-system", role = "system", content = prompt))
        }
        messages.filter { it.sender != Sender.System }.forEach { message ->
            if (message.attachments.isEmpty()) {
                add(
                    SimpleTextApiMessage(
                        id = message.id,
                        role = message.role,
                        content = message.text,
                        name = message.name,
                    )
                )
            } else {
                val parts = buildList {
                    if (message.text.isNotBlank()) add(ApiContentPart.Text(message.text))
                    message.attachments.forEach { attachment ->
                        add(ApiContentPart.FileUri("draft://${attachment.id}", attachment.mimeType))
                    }
                }
                add(PartsApiMessage(id = message.id, role = message.role, parts = parts, name = message.name))
            }
        }
        draftText.trim().takeIf(String::isNotEmpty)?.let { draft ->
            add(SimpleTextApiMessage(id = "draft-user", role = "user", content = draft))
        }
    }
    return RequestTokenEstimator.estimate(apiMessages, tools).toContextUsageSnapshot(
        messageId = "draft",
        configId = configId,
        reservedOutputTokens = limits.maxOutputTokens.toLong(),
        contextWindowTokens = limits.maxContextTokens.toLong(),
    )
}

private fun Long.ceilDiv(divisor: Long): Long = if (this == 0L) 0L else (this + divisor - 1L) / divisor

private fun Char.isCjk(): Boolean = code in 0x3400..0x4dbf ||
    code in 0x4e00..0x9fff ||
    code in 0xf900..0xfaff ||
    code in 0x3040..0x30ff ||
    code in 0xac00..0xd7af
