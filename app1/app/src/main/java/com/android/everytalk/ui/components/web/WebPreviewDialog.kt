package com.android.everytalk.ui.components
import com.android.everytalk.statecontroller.*

import android.annotation.SuppressLint
import android.content.ClipData
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.android.everytalk.R
import com.android.everytalk.ui.components.syntax.SyntaxHighlightTheme
import com.android.everytalk.ui.components.syntax.HighlightCache
import com.android.everytalk.ui.components.content.isPreviewSupported
import com.android.everytalk.ui.theme.chatColors
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun android.view.Window.setTransparentSystemBars() {
    statusBarColor = android.graphics.Color.TRANSPARENT
    navigationBarColor = android.graphics.Color.TRANSPARENT
}

@Suppress("DEPRECATION")
internal fun WebSettings.disableFileUrlCrossOriginAccess() {
    allowFileAccessFromFileURLs = false
    allowUniversalAccessFromFileURLs = false
}

internal data class CodeViewerDialogAnimationTarget(
    val scale: Float,
    val alpha: Float,
)

internal data class PreparedWebPreviewContent(
    val templateFileName: String?,
    val content: String,
    val requiresExplicitCompletionSignal: Boolean = false,
)

internal class WebPreviewLoadSession(
    val token: String,
    initialError: String?,
) {
    var error by mutableStateOf(initialError)
    var completionReported: Boolean = initialError != null
}

enum class WebPreviewLoadState {
    LOADING,
    READY,
    ERROR,
}

internal sealed interface WebPreviewCompletionSignal {
    data object Ready : WebPreviewCompletionSignal
    data class Error(val message: String) : WebPreviewCompletionSignal
}

internal const val WEB_PREVIEW_READY_SIGNAL = "__EVERYTALK_PREVIEW_READY__"
internal const val WEB_PREVIEW_ERROR_SIGNAL_PREFIX = "__EVERYTALK_PREVIEW_ERROR__:"
internal const val WEB_PREVIEW_COMPLETION_TOKEN_PLACEHOLDER = "__EVERYTALK_COMPLETION_TOKEN__"
internal const val WEB_PREVIEW_COMPLETION_TIMEOUT_MILLIS = 15_000L
internal const val WEB_PREVIEW_DIAGNOSTICS_SCRIPT_TAG =
    "<script id=\"everytalk-preview-diagnostics\">"

internal fun parseWebPreviewCompletionSignal(
    message: String?,
    expectedToken: String,
): WebPreviewCompletionSignal? {
    val readySignal = "$WEB_PREVIEW_READY_SIGNAL:$expectedToken"
    val errorSignalPrefix = "$WEB_PREVIEW_ERROR_SIGNAL_PREFIX$expectedToken:"
    return when {
        message == readySignal -> WebPreviewCompletionSignal.Ready
        message?.startsWith(errorSignalPrefix) == true -> WebPreviewCompletionSignal.Error(
            message.removePrefix(errorSignalPrefix).ifBlank { "Unknown preview error" }
        )
        else -> null
    }
}

internal fun buildWebPreviewBaseUrl(token: String): String =
    "file:///android_asset/templates/preview-$token.html"

internal fun shouldMarkWebPreviewLoadError(
    isForMainFrame: Boolean,
    requestUrl: String?,
    expectedToken: String,
): Boolean = isForMainFrame && requestUrl == buildWebPreviewBaseUrl(expectedToken)

internal fun shouldMarkWebPreviewConsoleError(
    sourceId: String?,
    expectedToken: String,
): Boolean = sourceId == buildWebPreviewBaseUrl(expectedToken)

internal fun shouldHandleWebPreviewPageFinished(
    url: String?,
    expectedToken: String,
): Boolean = url == buildWebPreviewBaseUrl(expectedToken)

internal fun shouldMarkWebPreviewReadyOnPageFinished(
    requiresExplicitCompletionSignal: Boolean,
    hasError: Boolean,
): Boolean = !requiresExplicitCompletionSignal && !hasError

internal fun resolveInitialWebPreviewLoadState(buildError: String?): WebPreviewLoadState =
    if (buildError == null) WebPreviewLoadState.LOADING else WebPreviewLoadState.ERROR

internal fun shouldMarkWebPreviewCompletionTimeoutError(
    requiresExplicitCompletionSignal: Boolean,
    completionReported: Boolean,
    hasError: Boolean,
): Boolean = requiresExplicitCompletionSignal && !completionReported && !hasError

internal fun prepareWebPreviewContent(
    code: String,
    language: String,
): PreparedWebPreviewContent {
    val normalizedLang = language.trim().lowercase()
    return when (normalizedLang) {
        "mermaid" -> PreparedWebPreviewContent(
            "templates/mermaid.html",
            escapeHtml(code),
            requiresExplicitCompletionSignal = true,
        )
        "echarts" -> PreparedWebPreviewContent(
            "templates/echarts.html",
            code.replace("`", "\\`"),
            requiresExplicitCompletionSignal = true,
        )
        "chartjs" -> PreparedWebPreviewContent(
            "templates/chartjs.html",
            code.replace("`", "\\`"),
            requiresExplicitCompletionSignal = true,
        )
        "flowchart", "flow" -> PreparedWebPreviewContent(
            "templates/flowchart.html",
            code.replace("`", "\\`"),
            requiresExplicitCompletionSignal = true,
        )
        "vega", "vega-lite" -> PreparedWebPreviewContent(
            "templates/vega.html",
            code,
            requiresExplicitCompletionSignal = true,
        )
        "infographic" -> PreparedWebPreviewContent("templates/html.html", renderInfographic(code))
        "html" -> {
            if (isCompleteHtmlDocument(code)) {
                PreparedWebPreviewContent(null, code)
            } else {
                PreparedWebPreviewContent("templates/html.html", extractHtmlBodyOrSelf(code))
            }
        }
        "svg", "xml" -> PreparedWebPreviewContent("templates/html.html", extractHtmlBodyOrSelf(code))
        else -> PreparedWebPreviewContent("templates/html.html", code)
    }
}

internal fun isCompleteHtmlDocument(raw: String): Boolean {
    val lower = raw.lowercase()
    return lower.contains("<!doctype html") || lower.contains("<html")
}

internal fun formatWebPreviewConsoleMessage(
    message: String?,
    lineNumber: Int,
    sourceId: String?,
): String {
    val safeMessage = message?.ifBlank { "Unknown preview error" } ?: "Unknown preview error"
    val safeSource = sourceId
        ?.substringAfterLast('/')
        ?.ifBlank { "inline.html" }
        ?: "inline.html"
    return if (lineNumber > 0) {
        "JS: $safeMessage ($safeSource:$lineNumber)"
    } else {
        "JS: $safeMessage"
    }
}

internal const val WEB_PREVIEW_DIAGNOSTICS_SNIPPET = """
<style id="everytalk-preview-error-style">
    #everytalk-preview-error-overlay {
        position: fixed;
        left: 12px;
        right: 12px;
        top: 12px;
        z-index: 2147483647;
        padding: 10px 12px;
        border-radius: 12px;
        background: rgba(176, 0, 32, 0.94);
        color: #fff;
        font: 12px/1.45 system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
        white-space: pre-wrap;
        word-break: break-word;
        box-shadow: 0 6px 24px rgba(0, 0, 0, 0.28);
    }
</style>
$WEB_PREVIEW_DIAGNOSTICS_SCRIPT_TAG
(function() {
    window.__everytalkPreviewDiagnosticsInstalled = true;
    var completionToken = '$WEB_PREVIEW_COMPLETION_TOKEN_PLACEHOLDER';

    function stringify(value) {
        if (value instanceof Error) return value.stack || value.message || String(value);
        if (typeof value === 'object') {
            try { return JSON.stringify(value); } catch (e) { return String(value); }
        }
        return String(value);
    }

    function ensureOverlay() {
        var existing = document.getElementById('everytalk-preview-error-overlay');
        if (existing) return existing;
        if (!document.body) return null;
        var overlay = document.createElement('div');
        overlay.id = 'everytalk-preview-error-overlay';
        document.body.appendChild(overlay);
        return overlay;
    }

    function showPreviewError(kind, message, source, line, column) {
        var overlay = ensureOverlay();
        if (!overlay) {
            document.addEventListener('DOMContentLoaded', function() {
                showPreviewError(kind, message, source, line, column);
            }, { once: true });
            return;
        }
        var location = source ? '\n' + source + (line ? ':' + line : '') + (column ? ':' + column : '') : '';
        overlay.textContent = kind + ': ' + message + location;
    }

    function reportPreviewError(kind, error, source, line, column) {
        var message = stringify(error || 'Unknown preview error');
        var location = source ? '\n' + source + (line ? ':' + line : '') + (column ? ':' + column : '') : '';
        showPreviewError(kind, message, source, line, column);
        console.log('${WEB_PREVIEW_ERROR_SIGNAL_PREFIX}' + completionToken + ':' + kind + ': ' + message + location);
    }

    window.__everytalkPreviewReady = function() {
        console.log('${WEB_PREVIEW_READY_SIGNAL}:' + completionToken);
    };

    window.__everytalkPreviewError = function(error) {
        reportPreviewError('Preview', error);
    };

    window.addEventListener('error', function(event) {
        reportPreviewError('JS', event.message || 'Script error', event.filename, event.lineno, event.colno);
    });

    window.addEventListener('unhandledrejection', function(event) {
        reportPreviewError('Promise', event.reason || 'Unhandled rejection');
    });

    var originalConsoleError = console.error;
    console.error = function() {
        var message = Array.prototype.map.call(arguments, stringify).join(' ');
        reportPreviewError('Console ERROR', message);
        if (originalConsoleError) originalConsoleError.apply(console, arguments);
    };
})();
</script>
"""

internal fun injectWebPreviewDiagnostics(
    raw: String,
    completionToken: String,
): String {
    if (raw.contains(WEB_PREVIEW_DIAGNOSTICS_SCRIPT_TAG)) return raw

    val diagnosticsSnippet = WEB_PREVIEW_DIAGNOSTICS_SNIPPET.replace(
        WEB_PREVIEW_COMPLETION_TOKEN_PLACEHOLDER,
        completionToken,
    )
    val headStartEnd = Regex("""<head(?:\s[^>]*)?>""", RegexOption.IGNORE_CASE)
        .find(raw)
        ?.range
        ?.last
        ?.plus(1)
    if (headStartEnd != null) {
        return raw.substring(0, headStartEnd) +
            "\n$diagnosticsSnippet\n" +
            raw.substring(headStartEnd)
    }

    val lower = raw.lowercase()
    val bodyIndex = lower.indexOf("<body")
    if (bodyIndex >= 0) {
        val bodyStartEnd = lower.indexOf(">", bodyIndex)
        if (bodyStartEnd >= 0) {
            return raw.substring(0, bodyStartEnd + 1) +
                "\n$diagnosticsSnippet\n" +
                raw.substring(bodyStartEnd + 1)
        }
    }

    return "$diagnosticsSnippet\n$raw"
}

internal fun buildWebPreviewDocument(
    content: String,
    template: String?,
    completionToken: String,
): String {
    val completedHtml = template
        ?.replace("<!-- CONTENT_PLACEHOLDER -->", content)
        ?: content
    return injectWebPreviewDiagnostics(completedHtml, completionToken)
}

internal fun resolveCodeViewerDialogAnimationTarget(
    hasEntered: Boolean,
    isClosing: Boolean,
): CodeViewerDialogAnimationTarget {
    return when {
        isClosing -> CodeViewerDialogAnimationTarget(
            scale = CODE_VIEWER_DIALOG_EDGE_SCALE,
            alpha = 0f,
        )
        hasEntered -> CodeViewerDialogAnimationTarget(
            scale = 1f,
            alpha = 1f,
        )
        else -> CodeViewerDialogAnimationTarget(
            scale = CODE_VIEWER_DIALOG_EDGE_SCALE,
            alpha = 0f,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
internal fun AnnotatedString.toSpannableString(defaultColor: Color): SpannableString {
    val spannable = SpannableString(text)
    if (text.isEmpty()) return spannable
    spannable.setSpan(
        ForegroundColorSpan(defaultColor.toArgb()),
        0,
        text.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    )
    spanStyles.forEach { range ->
        val color = range.item.color
        if (color != Color.Unspecified) {
            spannable.setSpan(
                ForegroundColorSpan(color.toArgb()),
                range.start.coerceIn(0, text.length),
                range.end.coerceIn(0, text.length),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
    return spannable
}

/**
 * HTML 转义，防止 mermaid 代码中的 < > 等字符破坏 HTML 结构
 */
internal fun escapeHtml(text: String): String {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#039;")
}

internal fun Color.toCssHex(): String {
    return String.format("#%06X", 0xFFFFFF and toArgb())
}

internal fun renderInfographic(raw: String): String {
    val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) {
        return ""
    }

    var index = 0
    while (index < lines.size && lines[index].startsWith("infographic", ignoreCase = true)) {
        index++
    }
    if (index < lines.size && lines[index].equals("data", ignoreCase = true)) {
        index++
    }

    var title = ""
    val items = mutableListOf<Triple<String, String, String?>>()

    while (index < lines.size) {
        val line = lines[index]
        if (line.startsWith("title ", ignoreCase = true)) {
            title = line.removePrefix("title").trim()
            index++
            continue
        }
        if (line.startsWith("items", ignoreCase = true)) {
            index++
            while (index < lines.size) {
                val current = lines[index]
                if (!current.startsWith("- label ", ignoreCase = true)) {
                    index++
                    continue
                }
                val label = current.removePrefix("- label").trim()
                var desc = ""
                var icon: String? = null

                var next = index + 1
                if (next < lines.size && lines[next].startsWith("desc ", ignoreCase = true)) {
                    desc = lines[next].removePrefix("desc").trim()
                    next++
                }
                if (next < lines.size && lines[next].startsWith("icon ", ignoreCase = true)) {
                    icon = lines[next].removePrefix("icon").trim()
                    next++
                }

                items.add(Triple(label, desc, icon))
                index = next
            }
            continue
        }
        index++
    }

    if (title.isBlank() && items.isEmpty()) {
        return "<pre style=\"white-space:pre-wrap;font-family:monospace;font-size:14px;\">${escapeHtml(raw)}</pre>"
    }

    val builder = StringBuilder()
    builder.append("<div style=\"width:100%;display:flex;flex-direction:column;gap:16px;\">")
    if (title.isNotBlank()) {
        builder.append("<div style=\"font-size:20px;font-weight:600;margin-bottom:4px;\">")
        builder.append(escapeHtml(title))
        builder.append("</div>")
    }
    builder.append("<div style=\"display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px;\">")
    for ((label, desc, icon) in items) {
        builder.append("<div style=\"border-radius:12px;padding:12px 14px;border:1px solid rgba(0,0,0,0.08);background:rgba(0,0,0,0.02);\">")
        if (!icon.isNullOrBlank()) {
            builder.append("<div style=\"font-size:11px;color:rgba(0,0,0,0.5);margin-bottom:4px;\">")
            builder.append(escapeHtml(icon))
            builder.append("</div>")
        }
        builder.append("<div style=\"font-size:14px;font-weight:600;margin-bottom:4px;\">")
        builder.append(escapeHtml(label))
        builder.append("</div>")
        if (desc.isNotBlank()) {
            builder.append("<div style=\"font-size:13px;color:rgba(0,0,0,0.75);\">")
            builder.append(escapeHtml(desc))
            builder.append("</div>")
        }
        builder.append("</div>")
    }
    builder.append("</div></div>")
    return builder.toString()
}

/**
 * 提取原始 HTML/XML 字符串中的 <style> 标签和 <body>...</body> 的内容。
 * - 若存在 body 标签，则返回所有 <style> 标签 + body 内部内容；
 * - 若不存在 body 标签，则直接返回原始字符串。
 *
 * 这样可以保留用户定义的 CSS 样式，同时避免用户提供的整页 HTML
 * 自己的布局把图片固定在顶部，让真正的布局交给我们的 html.html 模板（flex 居中）。
 */
internal fun extractHtmlBodyOrSelf(raw: String): String {
    val lower = raw.lowercase()

    // 优先匹配未转义的 <body>
    val bodyIndex = lower.indexOf("<body")
    if (bodyIndex == -1) {
        // 没有 body 标签，直接返回原始字符串
        return raw
    }

    // 提取所有 <style>...</style> 标签内容
    val styleBlocks = extractAllStyleBlocks(raw)
    
    // 提取 body 内容
    val startTagEnd = lower.indexOf(">", bodyIndex).takeIf { it != -1 } ?: return raw
    val endIndex = lower.indexOf("</body>", startTagEnd).takeIf { it != -1 } ?: raw.length
    val bodyContent = raw.substring(startTagEnd + 1, endIndex)

    // 合并 style 标签和 body 内容
    return if (styleBlocks.isNotEmpty()) {
        styleBlocks + "\n" + bodyContent
    } else {
        bodyContent
    }
}

/**
 * 从 HTML 字符串中提取所有 <style>...</style> 标签（包括标签本身）
 */
internal fun extractAllStyleBlocks(html: String): String {
    val lower = html.lowercase()
    val result = StringBuilder()
    var searchStart = 0
    
    while (true) {
        // 查找 <style 开始
        val styleStart = lower.indexOf("<style", searchStart)
        if (styleStart == -1) break
        
        // 查找 </style> 结束
        val styleEnd = lower.indexOf("</style>", styleStart)
        if (styleEnd == -1) break
        
        // 提取完整的 style 块（包括标签）
        val styleBlock = html.substring(styleStart, styleEnd + 8) // 8 = "</style>".length
        if (result.isNotEmpty()) {
            result.append("\n")
        }
        result.append(styleBlock)
        
        // 继续搜索下一个 style 块
        searchStart = styleEnd + 8
    }
    
    return result.toString()
}
