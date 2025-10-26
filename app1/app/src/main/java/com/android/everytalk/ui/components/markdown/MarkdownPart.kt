package com.android.everytalk.ui.components.markdown

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Markdown 片段数据结构（迁移版）
 * 原文件位置：ui/components/MarkdownPart.kt
 * 说明：迁移到 markdown/ 目录以便按功能分层管理。
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
}

/**
 * MarkdownPart 列表序列化器
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
