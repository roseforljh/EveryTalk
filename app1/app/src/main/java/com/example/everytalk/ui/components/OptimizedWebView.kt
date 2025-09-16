package com.example.everytalk.ui.components

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 优化的WebView管理器 - 实现异步初始化和实例复用
 */
object OptimizedWebViewManager {
    private val webViewPool = mutableMapOf<String, WebView>()
    private var isInitialized = false
    
    /**
     * 异步预初始化WebView池
     */
    suspend fun preInitializeWebViews(context: android.content.Context) = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext
        
        try {
            // 预创建常用的WebView实例
            val webViewTypes = listOf("html", "math", "code")
            
            webViewTypes.forEach { type ->
                val webView = WebView(context.applicationContext).apply {
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.alpha = 1f
                        }
                    }
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        textZoom = 100
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                    }
                    setOnLongClickListener { false }
                    isLongClickable = false
                    alpha = 0f
                }
                webViewPool[type] = webView
            }
            isInitialized = true
        } catch (e: Exception) {
            // 如果预初始化失败，不影响主流程
            android.util.Log.w("WebViewManager", "WebView预初始化失败", e)
        }
    }
    
    /**
     * 获取或创建WebView实例
     */
    fun getWebView(context: android.content.Context, type: String = "html"): WebView {
        return webViewPool[type] ?: createWebView(context).also {
            webViewPool[type] = it
        }
    }
    
    private fun createWebView(context: android.content.Context): WebView {
        return WebView(context.applicationContext).apply {
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.alpha = 1f
                }
            }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                textZoom = 100
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
            }
            isSaveEnabled = true
            setOnLongClickListener { false }
            isLongClickable = false
            alpha = 0f
        }
    }
    
    /**
     * 清理WebView池
     */
    fun clearPool() {
        webViewPool.values.forEach { webView ->
            try {
                webView.destroy()
            } catch (e: Exception) {
                android.util.Log.w("WebViewManager", "WebView销毁失败", e)
            }
        }
        webViewPool.clear()
        isInitialized = false
    }
}

/**
 * 优化的HTML渲染组件 - 使用异步WebView和缓存
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OptimizedHtmlView(
    htmlContent: String,
    modifier: Modifier = Modifier,
    textColor: Color = Color.Unspecified,
    stableKey: String? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isDarkTheme = isSystemInDarkTheme()
    
    // 根据主题设置背景色和文本颜色
    val backgroundColor = if (isDarkTheme) "#1a1a1a" else "#ffffff"
    val defaultTextColor = if (isDarkTheme) "#ffffff" else "#000000"
    val finalTextColor = if (textColor != Color.Unspecified) {
        String.format("#%06X", 0xFFFFFF and textColor.toArgb())
    } else {
        defaultTextColor
    }
    
    // 使用记忆化生成当前计算的 HTML
    val computedHtml = remember(htmlContent, finalTextColor, backgroundColor) {
        createOptimizedHtmlContent(htmlContent, finalTextColor, backgroundColor)
    }
    // 关键：为每条消息持久保存 HTML，以便退出/重进后直接恢复
    var savedHtml by rememberSaveable(stableKey ?: "global") { mutableStateOf<String?>(null) }
    val fullHtmlContent = savedHtml ?: computedHtml
    
    // 异步初始化WebView
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            OptimizedWebViewManager.preInitializeWebViews(context)
        }
    }
    
    // 使用优化的WebView实例
    var webView by remember { mutableStateOf<WebView?>(null) }
    
    LaunchedEffect(context) {
        withContext(Dispatchers.Main) {
            webView = OptimizedWebViewManager.getWebView(context, "html")
        }
    }

    webView?.let { webViewInstance ->
        AndroidView(
            modifier = modifier,
            factory = { webViewInstance },
            update = { vw ->
                // 如果新计算的 HTML 和保存态不同，先更新保存态
                val newHash = computedHtml.hashCode()
                if (savedHtml?.hashCode() != newHash) {
                    savedHtml = computedHtml
                }
                val finalHtml = savedHtml ?: computedHtml
                val finalHash = finalHtml.hashCode()
                // 只有当内容真正改变时才重新加载，避免闪烁
                val currentTag = vw.tag as? Int
                if (currentTag != finalHash) {
                    vw.tag = finalHash
                    vw.alpha = 0f
                    vw.loadDataWithBaseURL("file:///android_asset/", finalHtml, "text/html", "UTF-8", null)
                }
            }
        )
    }
}

/**
 * 创建优化的HTML内容 - 减少字符串拼接操作
 */
private fun createOptimizedHtmlContent(
    htmlContent: String,
    textColor: String,
    backgroundColor: String
): String = buildString {
    append("<!DOCTYPE html><html><head>")
    append("<meta charset=\"UTF-8\">")
    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, user-scalable=no\">")
    append("<style>")
    append("*{-webkit-user-select:none;-moz-user-select:none;-ms-user-select:none;user-select:none;")
    append("-webkit-touch-callout:none;-webkit-tap-highlight-color:transparent;}")
    append("body{margin:0;padding:12px;background-color:")
    append(backgroundColor)
    append(";color:")
    append(textColor)
    append(";font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;")
    append("font-size:14px;line-height:1.6;}")
    append("table{border-collapse:collapse;width:100%;margin:8px 0;}")
    append("th,td{border:1px solid ")
    append(textColor)
    append(";padding:8px;text-align:left;}")
    append("th{background-color:")
    append(if (backgroundColor == "#1a1a1a") "#333" else "#f5f5f5")
    append(";font-weight:bold;}")
    append("</style></head><body>")
    append(htmlContent)
    append("</body></html>")
}

/**
 * Compose组件销毁时的清理
 */
@Composable
fun WebViewCleanupEffect() {
    DisposableEffect(Unit) {
        onDispose {
            // 应用退出时清理WebView池
            OptimizedWebViewManager.clearPool()
        }
    }
}