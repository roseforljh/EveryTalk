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