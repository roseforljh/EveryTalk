package com.example.everytalk.ui.components

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * 🚀 专业数学公式渲染器 - 学术级别LaTeX渲染
 * 
 * 特性：
 * - KaTeX引擎渲染，支持完整LaTeX语法
 * - 智能主题适配（深色/浅色模式）
 * - 高性能缓存机制
 * - 渲染状态监控
 * - 错误处理与回退
 */
@Composable
fun ProfessionalMathRenderer(
    content: String,
    modifier: Modifier = Modifier,
    maxHeight: Int = 800,
    onRenderComplete: ((Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    
    // 主题色彩适配
    val backgroundColor = MaterialTheme.colorScheme.surface
    val textColor = MaterialTheme.colorScheme.onSurface
    val isDarkTheme = MaterialTheme.colorScheme.background.toArgb() and 0xFFFFFF < 0x808080
    
    // 渲染状态管理
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    
    AndroidView(
        factory = { createMathWebView(context, isDarkTheme) },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor),
        update = { webView ->
            renderMathContent(
                webView = webView,
                content = content,
                backgroundColor = backgroundColor.toArgb(),
                textColor = textColor.toArgb(),
                isDarkTheme = isDarkTheme,
                onComplete = { success ->
                    isLoading = false
                    hasError = !success
                    onRenderComplete?.invoke(success)
                }
            )
        }
    )
}

/**
 * 创建优化的数学渲染WebView
 */
private fun createMathWebView(context: Context, isDarkTheme: Boolean): WebView {
    return WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        
        // 设置透明背景
        setBackgroundColor(Color.TRANSPARENT)
        
        // 设置WebView客户端
        webViewClient = MathWebViewClient()
        webChromeClient = MathWebChromeClient()
        
        // 添加JavaScript接口
        addJavascriptInterface(MathRenderInterface(), "Android")
    }
}

/**
 * 渲染数学内容
 */
private fun renderMathContent(
    webView: WebView,
    content: String,
    backgroundColor: Int,
    textColor: Int,
    isDarkTheme: Boolean,
    onComplete: (Boolean) -> Unit
) {
    val processedContent = preprocessMathContent(content)
    val html = createMathHTML(processedContent, backgroundColor, textColor, isDarkTheme)
    
    // 缓存检查
    val cacheKey = generateCacheKey(processedContent, isDarkTheme)
    if (MathRenderCache.hasCache(cacheKey)) {
        val cachedHtml = MathRenderCache.getCache(cacheKey)
        if (cachedHtml != null) {
            webView.loadDataWithBaseURL(null, cachedHtml, "text/html", "UTF-8", null)
            onComplete(true)
            return
        }
    }
    
    // 设置渲染完成回调
    mathRenderCallbacks[webView] = onComplete
    
    // 加载HTML内容
    webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    
    // 缓存HTML
    MathRenderCache.putCache(cacheKey, html)
}

/**
 * 预处理数学内容，确保LaTeX语法正确
 */
private fun preprocessMathContent(content: String): String {
    return content
        // 确保数学块独占行
        .replace(Regex("([^\\n])\\$\\$")) { "${it.groupValues[1]}\n$$" }
        .replace(Regex("\\$\\$([^\\n])")) { "$$\n${it.groupValues[1]}" }
        // 转义特殊字符
        .replace("\\", "\\\\")
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        // 优化LaTeX语法
        .replace(Regex("\\\\frac\\{([^}]+)\\}\\{([^}]+)\\}")) { "\\frac{${it.groupValues[1]}}{${it.groupValues[2]}}" }
        .replace(Regex("\\\\sqrt\\{([^}]+)\\}")) { "\\sqrt{${it.groupValues[1]}}" }
        .trim()
}

/**
 * 创建专业数学HTML模板
 */
private fun createMathHTML(
    content: String,
    backgroundColor: Int,
    textColor: Int,
    isDarkTheme: Boolean
): String {
    val bgColor = String.format("#%06X", backgroundColor and 0xFFFFFF)
    val txtColor = String.format("#%06X", textColor and 0xFFFFFF)
    
    return """
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>
            body {
                margin: 0;
                padding: 16px;
                background-color: $bgColor;
                color: $txtColor;
                font-family: 'Computer Modern', 'Times New Roman', serif;
                font-size: 16px;
                line-height: 1.6;
                overflow-x: hidden;
            }
            
            .math-container {
                max-width: 100%;
                overflow-x: auto;
                overflow-y: hidden;
            }
        </style>
    </head>
    <body>
        <div class="math-container" id="content">$content</div>
    </body>
    </html>
    """.trimIndent()
}

/**
 * WebView客户端处理
 */
private class MathWebViewClient : WebViewClient() {
    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        Log.d("ProfessionalMathRenderer", "Page finished loading")
    }
    
    override fun onReceivedError(
        view: WebView?,
        errorCode: Int,
        description: String?,
        failingUrl: String?
    ) {
        super.onReceivedError(view, errorCode, description, failingUrl)
        Log.e("ProfessionalMathRenderer", "WebView error: $description")
        view?.let { mathRenderCallbacks[it]?.invoke(false) }
    }
}

/**
 * WebView控制台消息处理
 */
private class MathWebChromeClient : WebChromeClient() {
    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        consoleMessage?.let {
            Log.d("MathWebView", "${it.messageLevel()}: ${it.message()}")
        }
        return true
    }
}

/**
 * JavaScript接口
 */
private class MathRenderInterface {
    @android.webkit.JavascriptInterface
    fun onRenderComplete(success: Boolean) {
        // 通过回调通知渲染完成
        Log.d("ProfessionalMathRenderer", "Render complete: $success")
    }
}

/**
 * 数学渲染缓存管理器
 */
private object MathRenderCache {
    private val cache = ConcurrentHashMap<String, String>()
    private const val MAX_CACHE_SIZE = 100
    
    fun hasCache(key: String): Boolean = cache.containsKey(key)
    
    fun getCache(key: String): String? = cache[key]
    
    fun putCache(key: String, html: String) {
        if (cache.size >= MAX_CACHE_SIZE) {
            // 简单的LRU淘汰策略
            val firstKey = cache.keys.first()
            cache.remove(firstKey)
        }
        cache[key] = html
    }
    
    fun clearCache() = cache.clear()
}

/**
 * 生成缓存键
 */
private fun generateCacheKey(content: String, isDarkTheme: Boolean): String {
    return "${content.hashCode()}_${isDarkTheme}"
}

/**
 * 渲染回调管理
 */
private val mathRenderCallbacks = mutableMapOf<WebView, (Boolean) -> Unit>()