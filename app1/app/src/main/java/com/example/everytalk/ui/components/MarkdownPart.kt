package com.example.everytalk.ui.components

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * 🚀 增强的MarkdownPart - 支持专业数学公式渲染
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

    /**
     * 🎯 专业数学块 - 支持完整LaTeX渲染
     */
    @Serializable
    data class MathBlock(
        override val id: String,
        val content: String,
        val latex: String = "",  // 保持向后兼容
        val displayMode: Boolean = true,  // 保持向后兼容
        val renderMode: String = "professional"  // 新增：渲染模式标识
    ) : MarkdownPart() {
        // 兼容性构造函数
        constructor(id: String, latex: String, displayMode: Boolean = true) : this(
            id = id,
            content = if (displayMode) "$$\n$latex\n$$" else "$$latex$",
            latex = latex,
            displayMode = displayMode,
            renderMode = "professional"
        )
    }

    /**
     * 🎯 表格块 - 专用表格渲染
     */
    @Serializable
    data class Table(
        override val id: String,
        val content: String,
        val renderMode: String = "webview"
    ) : MarkdownPart()

    /**
     * 🎯 混合内容块 - 包含数学公式的复杂内容
     */
    @Serializable
    data class MixedContent(
        override val id: String,
        val content: String,
        val hasMath: Boolean = true,
        val renderMode: String = "hybrid"
    ) : MarkdownPart()

    @Serializable
    data class HtmlContent(override val id: String, val html: String) : MarkdownPart()
    
    /**
     * 获取渲染优先级，用于智能渲染策略选择
     */
    fun getRenderPriority(): Int = when (this) {
        is MathBlock -> if (renderMode == "professional") 100 else 80
        is Table -> 90
        is MixedContent -> 70
        is CodeBlock -> 60
        is HtmlContent -> 50
        is Text -> 10
    }
    
    /**
     * 检查是否需要WebView渲染
     */
    fun requiresWebView(): Boolean = when (this) {
        is MathBlock -> renderMode == "professional"
        is Table -> renderMode == "webview"
        is MixedContent -> hasMath
        is HtmlContent -> true
        else -> false
    }
    
    /**
     * 获取内容摘要，用于调试和日志
     */
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