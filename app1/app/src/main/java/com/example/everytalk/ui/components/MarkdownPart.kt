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

    @Serializable
    data class MathBlock(
        override val id: String,
        val latex: String,
        val isDisplay: Boolean = true
    ) : MarkdownPart()

    @Serializable
    data class InlineMath(override val id: String, val latex: String) : MarkdownPart()

    @Serializable
    data class HtmlContent(override val id: String, val html: String) : MarkdownPart()

    @Serializable
    data class Table(
        override val id: String,
        val tableData: TableData
    ) : MarkdownPart()
}