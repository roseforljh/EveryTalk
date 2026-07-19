package com.android.everytalk.ui.components

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 简化的 MarkdownPart 数据结构（仅用于数据存储和兼容性）
 */
@Serializable
sealed class MarkdownPart {
    abstract val id: String

    @Serializable
    data class Text(override val id: String, val content: String) : MarkdownPart()

    @Serializable
    data class CodeBlock(
        override val id: String,
        val content: String,
        val language: String = ""
    ) : MarkdownPart()

    @Serializable
    data class InlineImage(
        override val id: String,
        val mimeType: String,
        val base64Data: String
    ) : MarkdownPart()
}

/**
 * 仅用于恢复旧数据库中 text 为空、内容仍保存在 parts 的消息。
 * 新消息始终将完整原始 Markdown 保存为单个 Text part。
 */
fun List<MarkdownPart>.toRecoveredMarkdown(): String = buildString {
    this@toRecoveredMarkdown.forEachIndexed { index, part ->
        when (part) {
            is MarkdownPart.Text -> append(part.content)

            is MarkdownPart.CodeBlock -> {
                if (isNotEmpty() && last() != '\n') append('\n')
                val fence = safeCodeFence(part.content)
                val language = part.language
                    .lineSequence()
                    .firstOrNull()
                    .orEmpty()
                    .trim()
                    .replace("`", "")
                append(fence)
                append(language)
                append('\n')
                append(part.content)
                if (part.content.isNotEmpty() && !part.content.endsWith('\n')) append('\n')
                append(fence)

                val next = this@toRecoveredMarkdown.getOrNull(index + 1)
                if (next != null && (next !is MarkdownPart.Text || !next.content.startsWith('\n'))) {
                    append('\n')
                }
            }

            is MarkdownPart.InlineImage -> Unit
        }
    }
}

private fun safeCodeFence(content: String): String {
    var longestRun = 0
    var currentRun = 0
    content.forEach { character ->
        if (character == '`') {
            currentRun++
            longestRun = maxOf(longestRun, currentRun)
        } else {
            currentRun = 0
        }
    }
    return "`".repeat((longestRun + 1).coerceAtLeast(3))
}

/**
 * MarkdownPart 序列化器
 */
object MarkdownPartSerializer : KSerializer<List<MarkdownPart>> {
    private val delegateSerializer = ListSerializer(MarkdownPart.serializer())
    override val descriptor: SerialDescriptor = delegateSerializer.descriptor

    override fun serialize(encoder: Encoder, value: List<MarkdownPart>) {
        delegateSerializer.serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): List<MarkdownPart> {
        return delegateSerializer.deserialize(decoder)
    }
}
