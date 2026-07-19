package com.android.everytalk.ui.components

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
import com.android.everytalk.ui.components.syntax.SyntaxHighlighter
import com.android.everytalk.ui.components.content.isPreviewSupported
import com.android.everytalk.ui.theme.chatColors
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val CODE_VIEWER_DIALOG_EDGE_SCALE = 0.72f
internal const val CODE_VIEWER_DIALOG_TRANSITION_MILLIS = 260
internal const val CODE_VIEWER_DIALOG_ALPHA_MILLIS = CODE_VIEWER_DIALOG_TRANSITION_MILLIS
internal const val CODE_VIEWER_DIALOG_WINDOW_DIM_AMOUNT = 0f

@Suppress("DEPRECATION")
private fun android.view.Window.setTransparentSystemBars() {
    statusBarColor = android.graphics.Color.TRANSPARENT
    navigationBarColor = android.graphics.Color.TRANSPARENT
}

@Suppress("DEPRECATION")
private fun WebSettings.disableFileUrlCrossOriginAccess() {
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

private class WebPreviewLoadSession(
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

private const val WEB_PREVIEW_READY_SIGNAL = "__EVERYTALK_PREVIEW_READY__"
private const val WEB_PREVIEW_ERROR_SIGNAL_PREFIX = "__EVERYTALK_PREVIEW_ERROR__:"
private const val WEB_PREVIEW_COMPLETION_TOKEN_PLACEHOLDER = "__EVERYTALK_COMPLETION_TOKEN__"
private const val WEB_PREVIEW_COMPLETION_TIMEOUT_MILLIS = 15_000L
private const val WEB_PREVIEW_DIAGNOSTICS_SCRIPT_TAG =
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

private fun isCompleteHtmlDocument(raw: String): Boolean {
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

private const val WEB_PREVIEW_DIAGNOSTICS_SNIPPET = """
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
@Composable
fun WebPreviewContent(
    code: String,
    language: String,
    modifier: Modifier = Modifier,
    previewBackgroundColor: Color = Color.White,
    previewTextColor: Color = Color.Black,
    onLoadStateChanged: (WebPreviewLoadState) -> Unit = {},
) {
    val context = LocalContext.current
    val latestOnLoadStateChanged by rememberUpdatedState(onLoadStateChanged)

    val previewContent = remember(code, language) {
        prepareWebPreviewContent(code, language)
    }
    val previewLoadToken = remember(
        previewContent,
        previewBackgroundColor,
        previewTextColor,
    ) {
        UUID.randomUUID().toString()
    }
    val latestRequiresExplicitCompletionSignal by rememberUpdatedState(
        previewContent.requiresExplicitCompletionSignal
    )

    val isDarkPreview = previewBackgroundColor.luminance() < 0.5f
    val previewSurfaceColor = if (isDarkPreview) Color(0xFF1E1E1E) else Color.White
    val previewTextCssColor = previewTextColor.toCssHex()
    val previewSurfaceCssColor = previewSurfaceColor.toCssHex()
    val darkModeOverrides = if (isDarkPreview) {
        """
        body.everytalk-dark .preview-container table {
            background-color: $previewSurfaceCssColor;
        }
        body.everytalk-dark .preview-container :is(table, tr, td, th) {
            -webkit-tap-highlight-color: transparent;
        }
        body.everytalk-dark .preview-container :is(td, th):not([style*="color"]) {
            color: $previewTextCssColor;
        }
        body.everytalk-dark .preview-container :is(tr, td, th):is(:hover, :active, :focus, :focus-within) {
            background-color: rgba(255, 255, 255, 0.08) !important;
            color: $previewTextCssColor !important;
        }
        body.everytalk-dark .preview-container :is(tr, td, th):is(:hover, :active, :focus, :focus-within) *:not([style*="color"]) {
            color: $previewTextCssColor !important;
        }
        body.everytalk-dark .preview-container :is(table, tr, td, th):not([style*="border"]) {
            border-color: rgba(255, 255, 255, 0.18);
        }
        """.trimIndent()
    } else {
        ""
    }

    val (htmlContent, previewBuildError) = remember(
        previewContent,
        previewBackgroundColor,
        previewTextColor,
        previewLoadToken,
    ) {
        try {
            val document = if (previewContent.templateFileName == null) {
                buildWebPreviewDocument(
                    content = previewContent.content,
                    template = null,
                    completionToken = previewLoadToken,
                )
            } else {
                val assetManager = context.assets
                val template = assetManager.open(previewContent.templateFileName)
                    .bufferedReader(Charsets.UTF_8)
                    .use { reader -> reader.readText() }
                val themedTemplate = template
                    .replace("ET_COLOR_SCHEME", if (isDarkPreview) "dark" else "light")
                    .replace("ET_THEME_CLASS", if (isDarkPreview) "everytalk-dark" else "everytalk-light")
                    .replace("ET_BACKGROUND_COLOR", previewBackgroundColor.toCssHex())
                    .replace("ET_TEXT_COLOR", previewTextCssColor)
                    .replace("ET_DARK_MODE_OVERRIDES", darkModeOverrides)
                buildWebPreviewDocument(
                    content = previewContent.content,
                    template = themedTemplate,
                    completionToken = previewLoadToken,
                )
            }
            document to null
        } catch (e: Exception) {
            e.printStackTrace()
            val errorMessage = e.message?.ifBlank { "Unknown preview error" } ?: "Unknown preview error"
            buildWebPreviewDocument(
                content = "<html><body>Error loading template: ${escapeHtml(errorMessage)}</body></html>",
                template = null,
                completionToken = previewLoadToken,
            ) to "Template: $errorMessage"
        }
    }

    var previewWebView by remember { mutableStateOf<WebView?>(null) }
    val previewLoadSession = remember(previewLoadToken, previewBuildError) {
        WebPreviewLoadSession(
            token = previewLoadToken,
            initialError = previewBuildError,
        )
    }
    val latestPreviewLoadSession by rememberUpdatedState(previewLoadSession)

    LaunchedEffect(
        previewWebView,
        htmlContent,
        previewBuildError,
        previewLoadToken,
        previewContent.requiresExplicitCompletionSignal,
    ) {
        val webView = previewWebView ?: return@LaunchedEffect
        latestOnLoadStateChanged(resolveInitialWebPreviewLoadState(previewBuildError))
        if (webView.tag != htmlContent) {
            val baseUrl = buildWebPreviewBaseUrl(previewLoadToken)
            webView.tag = htmlContent
            webView.loadDataWithBaseURL(
                baseUrl,
                htmlContent,
                "text/html",
                "UTF-8",
                baseUrl,
            )
        }
        if (previewBuildError != null) return@LaunchedEffect

        if (previewContent.requiresExplicitCompletionSignal) {
            delay(WEB_PREVIEW_COMPLETION_TIMEOUT_MILLIS)
            if (
                shouldMarkWebPreviewCompletionTimeoutError(
                    requiresExplicitCompletionSignal = previewContent.requiresExplicitCompletionSignal,
                    completionReported = previewLoadSession.completionReported,
                    hasError = previewLoadSession.error != null,
                )
            ) {
                webView.evaluateJavascript(
                    "window.__everytalkPreviewError && window.__everytalkPreviewError('Render timed out');",
                    null,
                )
                previewLoadSession.error = "Preview: Render timed out"
                previewLoadSession.completionReported = true
                latestOnLoadStateChanged(WebPreviewLoadState.ERROR)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            previewWebView?.apply {
                stopLoading()
                loadUrl("about:blank")
                removeAllViews()
                destroy()
            }
            previewWebView = null
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    previewWebView = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = false
                    settings.disableFileUrlCrossOriginAccess()
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        settings.isAlgorithmicDarkeningAllowed = false
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        @Suppress("DEPRECATION")
                        settings.forceDark = WebSettings.FORCE_DARK_OFF
                    }
                    setBackgroundColor(previewBackgroundColor.toArgb())
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            val loadSession = latestPreviewLoadSession
                            if (!shouldHandleWebPreviewPageFinished(url, loadSession.token)) return
                            if (
                                shouldMarkWebPreviewReadyOnPageFinished(
                                    requiresExplicitCompletionSignal = latestRequiresExplicitCompletionSignal,
                                    hasError = loadSession.error != null,
                                )
                            ) {
                                loadSession.completionReported = true
                                latestOnLoadStateChanged(WebPreviewLoadState.READY)
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            super.onReceivedError(view, request, error)
                            val loadSession = latestPreviewLoadSession
                            if (
                                !shouldMarkWebPreviewLoadError(
                                    isForMainFrame = request?.isForMainFrame == true,
                                    requestUrl = request?.url?.toString(),
                                    expectedToken = loadSession.token,
                                )
                            ) return
                            loadSession.error = "Web: ${error?.description ?: "Unknown preview error"}"
                            loadSession.completionReported = true
                            latestOnLoadStateChanged(WebPreviewLoadState.ERROR)
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            val loadSession = latestPreviewLoadSession
                            val completionSignal = parseWebPreviewCompletionSignal(
                                message = consoleMessage?.message(),
                                expectedToken = loadSession.token,
                            )
                            when (completionSignal) {
                                WebPreviewCompletionSignal.Ready -> {
                                    if (
                                        latestRequiresExplicitCompletionSignal &&
                                        loadSession.error == null
                                    ) {
                                        loadSession.completionReported = true
                                        latestOnLoadStateChanged(WebPreviewLoadState.READY)
                                    }
                                }
                                is WebPreviewCompletionSignal.Error -> {
                                    loadSession.error = "Preview: ${completionSignal.message}"
                                    loadSession.completionReported = true
                                    latestOnLoadStateChanged(WebPreviewLoadState.ERROR)
                                }
                                null -> Unit
                            }

                            if (
                                completionSignal == null &&
                                consoleMessage?.messageLevel() == ConsoleMessage.MessageLevel.ERROR &&
                                shouldMarkWebPreviewConsoleError(
                                    sourceId = consoleMessage.sourceId(),
                                    expectedToken = loadSession.token,
                                )
                            ) {
                                loadSession.error = formatWebPreviewConsoleMessage(
                                    message = consoleMessage.message(),
                                    lineNumber = consoleMessage.lineNumber(),
                                    sourceId = consoleMessage.sourceId(),
                                )
                                loadSession.completionReported = true
                                latestOnLoadStateChanged(WebPreviewLoadState.ERROR)
                            }
                            return true
                        }
                    }
                    // 禁用 WebView 自身的滚动和点击拦截，让外层能够捕获事件（针对非全屏预览模式）
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    setOnTouchListener { v, event ->
                        if (event.action == MotionEvent.ACTION_UP) v.performClick()
                        // 返回 false 允许事件冒泡到外层 Compose 点击监听器
                        false
                    }
                }
            },
            update = { webView ->
                webView.setBackgroundColor(previewBackgroundColor.toArgb())
            },
            modifier = Modifier.fillMaxSize()
        )

        previewLoadSession.error?.let { errorText ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Text(
                    text = errorText,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@Composable
fun FullScreenCodeViewerDialog(
    code: String,
    language: String,
    initialPreviewMode: Boolean = false,
    sourceBounds: androidx.compose.ui.geometry.Rect = androidx.compose.ui.geometry.Rect.Zero,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboard.current
    val windowSize = LocalWindowInfo.current.containerSize
    val scope = rememberCoroutineScope()
    val isDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val bgColor = MaterialTheme.colorScheme.background
    val previewBgColor = bgColor
    val previewTextColor = if (isDarkTheme) Color(0xFFEAEAEA) else Color.Black
    val headerColor = if (isDarkTheme) Color.White else Color.Black
    val capsuleBgColor = if (isDarkTheme) Color(0xFF383838) else Color(0xFFE2E2E2)
    val capsuleSelectedBgColor = if (isDarkTheme) Color(0xFF505050) else Color.White

    val canPreview = isPreviewSupported(language)
    val pagerState = rememberPagerState(
        initialPage = if (canPreview && initialPreviewMode) 1 else 0,
        pageCount = { if (canPreview) 2 else 1 }
    )
    val isPreviewMode = canPreview && pagerState.currentPage == 1
    var hasEntered by remember { mutableStateOf(false) }
    var isClosing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        hasEntered = true
    }
    LaunchedEffect(isClosing) {
        if (isClosing) {
            delay(CODE_VIEWER_DIALOG_TRANSITION_MILLIS.toLong())
            onDismiss()
        }
    }
    val animationTarget = resolveCodeViewerDialogAnimationTarget(
        hasEntered = hasEntered,
        isClosing = isClosing,
    )
    val entryScale by animateFloatAsState(
        targetValue = animationTarget.scale,
        animationSpec = tween(durationMillis = CODE_VIEWER_DIALOG_TRANSITION_MILLIS),
        label = "dialogEntryScale"
    )
    val entryAlpha by animateFloatAsState(
        targetValue = animationTarget.alpha,
        animationSpec = tween(durationMillis = CODE_VIEWER_DIALOG_ALPHA_MILLIS),
        label = "dialogEntryAlpha"
    )
    val transformOrigin = remember(sourceBounds, windowSize) {
        if (
            sourceBounds == androidx.compose.ui.geometry.Rect.Zero ||
            windowSize.width <= 0 ||
            windowSize.height <= 0
        ) {
            TransformOrigin.Center
        } else {
            TransformOrigin(
                pivotFractionX = (
                    (sourceBounds.left + sourceBounds.width / 2f) / windowSize.width
                    ).coerceIn(0f, 1f),
                pivotFractionY = (
                    (sourceBounds.top + sourceBounds.height / 2f) / windowSize.height
                    ).coerceIn(0f, 1f),
            )
        }
    }

    fun requestDismiss() {
        if (!isClosing) {
            isClosing = true
        }
    }

    Dialog(
        onDismissRequest = { requestDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false
        )
    ) {
        val dialogWindowProvider = LocalView.current.parent as? DialogWindowProvider
        SideEffect {
            dialogWindowProvider?.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setDimAmount(CODE_VIEWER_DIALOG_WINDOW_DIM_AMOUNT)
                window.setTransparentSystemBars()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !isDarkTheme
                    isAppearanceLightNavigationBars = !isDarkTheme
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = entryAlpha
                    scaleX = entryScale
                    scaleY = entryScale
                    this.transformOrigin = transformOrigin
                }
                .background(bgColor)
                .statusBarsPadding()
        ) {
                // 顶部导航栏
                DisableSelection {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(1f)
                            .background(bgColor)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // 左侧：关闭按钮
                        IconButton(onClick = { requestDismiss() }, modifier = Modifier.size(48.dp)) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "关闭",
                                tint = headerColor
                            )
                        }

                        // 中间：胶囊按钮 (代码/预览)
                        if (canPreview) {
                            val capsuleWidth = 140.dp
                            val indicatorWidth = 70.dp

                            Surface(
                                shape = RoundedCornerShape(50),
                                color = capsuleBgColor,
                                modifier = Modifier.size(width = capsuleWidth, height = 36.dp)
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    // 滑块指示器
                                    Box(
                                        modifier = Modifier
                                            .padding(2.dp)
                                            .offset {
                                                val progress = (
                                                    pagerState.currentPage +
                                                        pagerState.currentPageOffsetFraction
                                                    ).coerceIn(0f, 1f)
                                                IntOffset(
                                                    x = (indicatorWidth * progress).roundToPx(),
                                                    y = 0,
                                                )
                                            }
                                            .size(width = indicatorWidth - 4.dp, height = 32.dp)
                                            .background(capsuleSelectedBgColor, RoundedCornerShape(50))
                                    )

                                    // 按钮文本层
                                    Row(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(50))
                                                .clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null
                                                ) {
                                                    scope.launch { pagerState.animateScrollToPage(0) }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "代码",
                                                color = headerColor,
                                                style = MaterialTheme.typography.labelLarge
                                            )
                                        }

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(50))
                                                .clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null
                                                ) {
                                                    scope.launch { pagerState.animateScrollToPage(1) }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "预览",
                                                color = headerColor,
                                                style = MaterialTheme.typography.labelLarge
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        // 右侧：预览模式为分享，代码模式为复制
                        val context = LocalContext.current
                        AnimatedContent(
                            targetState = isPreviewMode,
                            transitionSpec = {
                                (fadeIn(tween(220)) + scaleIn(
                                    tween(220),
                                    initialScale = 0.8f
                                )).togetherWith(
                                    fadeOut(tween(150)) + scaleOut(
                                        tween(150),
                                        targetScale = 0.6f
                                    )
                                )
                            },
                            label = "PreviewHeaderActionButton"
                        ) { previewMode ->
                            if (previewMode) {
                                IconButton(
                                    onClick = {
                                        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_TEXT, code)
                                        }
                                        context.startActivity(android.content.Intent.createChooser(shareIntent, "分享代码"))
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_gpt_share),
                                        contentDescription = "分享",
                                        tint = headerColor,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", code)))
                                        }
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_gpt_copy),
                                        contentDescription = "复制",
                                        tint = headerColor,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 主体内容
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = false
                    ) { page ->
                        if (page == 1) {
                            // 全屏预览时，在底部增加圆角和内边距，使其有悬浮感并避免遮挡底部系统导航条
                            Surface(
                                modifier = Modifier
                                    .fillMaxSize(),
                                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                                color = previewBgColor
                            ) {
                                WebPreviewContent(
                                    code = code,
                                    language = language,
                                    previewBackgroundColor = previewBgColor,
                                    previewTextColor = previewTextColor,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        } else {
                            SelectableCodeTextView(
                                code = code,
                                language = language,
                                isDarkTheme = isDarkTheme,
                                headerColor = headerColor,
                                codeTextColor = previewTextColor,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }

@Composable
private fun SelectableCodeTextView(
    code: String,
    language: String,
    isDarkTheme: Boolean,
    headerColor: Color,
    codeTextColor: Color,
    modifier: Modifier = Modifier
) {
    val syntaxTheme = if (isDarkTheme) SyntaxHighlightTheme.Dark else SyntaxHighlightTheme.Light
    val highlightedCode = remember(code, language, isDarkTheme) {
        SyntaxHighlighter.highlight(code, language, syntaxTheme).toSpannableString(codeTextColor)
    }
    val languageLabel = remember(language) { language.trim().ifBlank { "CODE" }.uppercase() }
    val headerArgb = headerColor.toArgb()
    val codeArgb = codeTextColor.toArgb()

    AndroidView(
        modifier = modifier,
        factory = { context ->
            ScrollView(context).apply {
                isFillViewport = true
                overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
                setBackgroundColor(android.graphics.Color.TRANSPARENT)

                val density = resources.displayMetrics.density
                val topPadding = (8f * density).toInt()
                val bottomPadding = (64f * density).toInt()
                val content = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, topPadding, 0, bottomPadding)
                }

                val labelView = TextView(context).apply {
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(headerArgb)
                    text = languageLabel
                    includeFontPadding = true
                    setPadding(0, 0, 0, (16f * density).toInt())
                }

                val codeView = TextView(context).apply {
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(codeArgb)
                    setLineSpacing(0f, 1.0f)
                    includeFontPadding = true
                    setTextIsSelectable(true)
                    setHorizontallyScrolling(false)
                    text = highlightedCode
                }

                content.addView(
                    labelView,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                content.addView(
                    codeView,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                addView(
                    content,
                    android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                tag = Pair(labelView, codeView)
            }
        },
        update = { scrollView ->
            @Suppress("UNCHECKED_CAST")
            val views = scrollView.tag as Pair<TextView, TextView>
            val labelView = views.first
            val codeView = views.second
            labelView.text = languageLabel
            labelView.setTextColor(headerArgb)
            codeView.setTextColor(codeArgb)
            codeView.text = highlightedCode
        }
    )
}

private fun AnnotatedString.toSpannableString(defaultColor: Color): SpannableString {
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
private fun escapeHtml(text: String): String {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#039;")
}

private fun Color.toCssHex(): String {
    return String.format("#%06X", 0xFFFFFF and toArgb())
}

private fun renderInfographic(raw: String): String {
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
private fun extractHtmlBodyOrSelf(raw: String): String {
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
private fun extractAllStyleBlocks(html: String): String {
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
