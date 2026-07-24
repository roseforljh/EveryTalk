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
            android.util.Log.e("WebPreview", "加载预览模板失败", e)
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

