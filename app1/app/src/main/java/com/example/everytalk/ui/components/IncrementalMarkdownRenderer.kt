package com.example.everytalk.ui.components

import android.webkit.ValueCallback
import android.webkit.WebView

/**
 * 轻量增量渲染器：
 * - 封装 WebView.evaluateJavascript 调用
 * - 提供统一入口以支持后续优化（如节流/合并/回退）
 * - 与 MarkdownHtmlView 内的 JS API 对接（updateMarkdown/appendDelta）
 */
class IncrementalMarkdownRenderer(
    private val webView: WebView
) {

    /**
     * 执行 JS 调用（带最小封装）
     */
    fun evalJs(script: String, cb: ValueCallback<String>? = null) {
        try {
            webView.evaluateJavascript(script, cb)
        } catch (_: Throwable) {
            // 静默失败，避免崩溃
        }
    }

    /**
     * 使用全量更新（调用 updateMarkdown）
     */
    fun updateMarkdown(fullEscapedContent: String, isFinal: Boolean, isStreaming: Boolean, cb: ValueCallback<String>? = null) {
        val js = "updateMarkdown('$fullEscapedContent', ${if (isFinal) "true" else "false"}, ${if (isStreaming) "true" else "false"})"
        evalJs(js, cb)
    }

    /**
     * 仅追加增量（调用 appendDelta）
     */
    fun appendDelta(escapedDelta: String, isFinal: Boolean, isStreaming: Boolean, cb: ValueCallback<String>? = null) {
        val js = "appendDelta('$escapedDelta', ${if (isFinal) "true" else "false"}, ${if (isStreaming) "true" else "false"})"
        evalJs(js, cb)
    }
}