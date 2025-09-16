package com.example.everytalk.ui.components

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

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

    // 新增：数学块（LaTeX 公式），displayMode 用于区分行内/块级
    @Serializable
    data class MathBlock(
        override val id: String,
        val latex: String,
        val displayMode: Boolean = true
    ) : MarkdownPart()

    @Serializable
    data class HtmlContent(override val id: String, val html: String) : MarkdownPart()
}