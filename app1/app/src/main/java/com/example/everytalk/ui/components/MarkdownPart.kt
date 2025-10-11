package com.example.everytalk.ui.components

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 兼容性最小实现：MarkdownPart
 * 说明：
 * - 用于恢复编译，后续你可按新方案替换。
 * - 不包含任何数学渲染逻辑，仅数据结构。
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

    // 保留 MathBlock 仅作为数据占位，便于现有代码编译通过；无渲染逻辑
    @Serializable
    data class MathBlock(
        override val id: String,
        val content: String,
        val latex: String = "",
        val displayMode: Boolean = true,
        val renderMode: String = "professional"
    ) : MarkdownPart() {
        constructor(id: String, latex: String, displayMode: Boolean = true) : this(
            id = id,
            content = if (displayMode) "$$\n$latex\n$$" else "$$latex$",
            latex = latex,
            displayMode = displayMode,
            renderMode = "professional"
        )
    }

    @Serializable
    data class Table(
        override val id: String,
        val content: String,
        val renderMode: String = "webview"
    ) : MarkdownPart()

    @Serializable
    data class MixedContent(
        override val id: String,
        val content: String,
        val hasMath: Boolean = true,
        val renderMode: String = "hybrid"
    ) : MarkdownPart()

    @Serializable
    data class HtmlContent(override val id: String, val html: String) : MarkdownPart()

    fun getRenderPriority(): Int = when (this) {
        is MathBlock -> if (renderMode == "professional") 100 else 80
        is Table -> 90
        is MixedContent -> 70
        is CodeBlock -> 60
        is HtmlContent -> 50
        is Text -> 10
    }

    fun requiresWebView(): Boolean = when (this) {
        is MathBlock -> renderMode == "professional"
        is Table -> renderMode == "webview"
        is MixedContent -> hasMath
        is HtmlContent -> true
        else -> false
    }

    fun getContentSummary(): String {
        val content = when (this) {
            is MathBlock -> this.content
            is Table -> this.content
            is MixedContent -> this.content
            is Text -> this.content
            is CodeBlock -> this.content
            is HtmlContent -> this.html
        }
        return "${this::class.simpleName}(${content.take(50)}${if (content.length > 50) "..." else ""})"
    }
}

/**
 * 兼容性序列化器：MarkdownPartSerializer
 * 说明：
 * - 保持与旧注解 @Serializable(with = MarkdownPartSerializer::class) 一致。
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