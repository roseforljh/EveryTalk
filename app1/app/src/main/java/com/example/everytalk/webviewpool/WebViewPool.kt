package com.example.everytalk.webviewpool

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent // 【新增】导入 Intent
import android.net.Uri // 【新增】导入 Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebResourceRequest // 【新增】导入 WebResourceRequest (用于较新API级别)
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.LinkedHashMap
import java.util.UUID

data class WebViewConfig(val htmlTemplate: String, val latexInput: String)

@SuppressLint("SetJavaScriptEnabled")
class WebViewPool(
    private val applicationContext: Context,
    private val maxSize: Int = 8 // 你之前用的是4，这里保持你代码中的值
) {
    private val poolTag = "WebViewPool[${UUID.randomUUID().toString().take(4)}]"
    private val available = object : LinkedHashMap<String, WebView>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WebView>?): Boolean {
            if (size > maxSize) {
                eldest?.let { (_, wv) ->
                    Log.i(poolTag, "LRU淘汰WebView: ${System.identityHashCode(wv)}")
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.destroy()
                }
                return true
            }
            return false
        }
    }
    private val inUse: MutableMap<String, WebView> = mutableMapOf()

    private fun createWebView(): WebView {
        Log.d(poolTag, "Creating NEW WebView...")
        return WebView(applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.textZoom = 100
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            // 【可选但推荐】为了更好的用户体验，可以禁用长按WebView选择文本的功能，
            // 因为你的应用有自己的选择文本机制 (通过上下文菜单)。
            // isLongClickable = false
            // setOnLongClickListener { true } // 消耗长按事件
        }
    }

    @Synchronized
    fun acquire(
        contentId: String,
        config: WebViewConfig,
        onPageFinishedCallback: (webView: WebView, success: Boolean) -> Unit
    ): WebView {
        var webView = available.remove(contentId)
        val isNewlyCreated = (webView == null)
        if (webView == null) {
            Log.d(poolTag, "ACQUIRE: $contentId, New or Pool Size=${available.size}/${maxSize}")
            webView = createWebView()
            webView.loadDataWithBaseURL(
                "file:///android_asset/", // 统一使用这个作为基础，以防模板中有相对路径的本地资源
                config.htmlTemplate,
                "text/html",
                "UTF-8",
                null
            )
        }
        inUse[contentId] = webView
        webView.tag = contentId

        // 【修改】自定义 WebViewClient
        webView.webViewClient = object : WebViewClient() {
            private var hadError = false

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                super.onPageStarted(view, url, favicon)
                hadError = false
                Log.d(poolTag, "WebView ($contentId) onPageStarted: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (view == webView) {
                    val success = !hadError
                    Log.d(poolTag, "WebView ($contentId) onPageFinished: $url, Success: $success")
                    onPageFinishedCallback(view, success)
                }
            }

            // 【新增】处理URL加载行为
            @Deprecated("Use shouldOverrideUrlLoading(WebView, WebResourceRequest) for API 24+")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return handleUrlLoading(url, view?.context)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString()
                return handleUrlLoading(url, view?.context)
            }

            private fun handleUrlLoading(url: String?, context: Context?): Boolean {
                url?.let {
                    Log.d(poolTag, "WebView ($contentId) shouldOverrideUrlLoading: $it")
                    // 检查是否是外部链接 (http, https)
                    if (it.startsWith("http://") || it.startsWith("https://")) {
                        // 确保 context 非空
                        context?.let { ctx ->
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(it))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // 如果从非Activity上下文启动，需要此标志
                                ctx.startActivity(intent)
                                Log.i(poolTag, "Opening external link in browser: $it")
                                return true // 表示我们已经处理了这个URL加载
                            } catch (e: Exception) {
                                Log.e(poolTag, "Could not open external link: $it", e)
                                // 可以给用户一个Toast提示无法打开链接
                            }
                        } ?: run {
                            Log.e(poolTag, "Context is null, cannot open external link: $it")
                        }
                        return true // 即使无法打开，也消费掉这个事件，避免WebView自己加载
                    }
                    // 你可以在这里添加对其他scheme的处理，例如：
                    // if (it.startsWith("mailto:")) { ... return true }
                    // if (it.startsWith("tel:")) { ... return true }
                }
                return false // 对于其他URL（例如，data: URL, file: URL, javascript: URL），让WebView默认处理
            }
        }

        if (!isNewlyCreated) {
            Log.d(
                poolTag,
                "WebView ($contentId) acquired from pool. Posting onPageFinished(true) immediately."
            )
            webView.post { onPageFinishedCallback(webView, true) }
        }
        return webView
    }

    // 【新增】warmUp 方法 (如果还没有，从之前的回复中复制过来)
    @Synchronized
    fun warmUp(count: Int, baseHtmlTemplate: String) {
        // ... (确保 warmUp 方法的实现是完整的) ...
        val currentTotal = available.size + inUse.size
        var needed = count - currentTotal
        if (needed < 0) needed = 0
        Log.i(
            poolTag,
            "WarmUp: Requesting $count, current $currentTotal, need $needed, max $maxSize."
        )
        if (needed > 0) {
            Log.i(poolTag, "Warming up $needed WebViews...")
            for (i in 0 until needed) {
                if (available.size + inUse.size >= maxSize) {
                    Log.i(
                        poolTag,
                        "WarmUp: Pool reached maxSize ($maxSize) during warm-up loop. Stopping."
                    )
                    break
                }
                val warmUpId = "warmup_${UUID.randomUUID().toString().take(4)}"
                val wv = createWebView()
                wv.tag = warmUpId
                wv.loadDataWithBaseURL(
                    "file:///android_asset/",
                    baseHtmlTemplate,
                    "text/html",
                    "UTF-8",
                    null
                )
                if (!available.containsKey(warmUpId)) {
                    available[warmUpId] = wv
                } else {
                    wv.destroy()
                }
            }
        } else {
            Log.i(poolTag, "WarmUp: No new WebViews needed or pool is full.")
        }
    }

    @Synchronized
    fun release(webView: WebView) {
        val contentId = webView.tag as? String ?: return
        if (inUse.remove(contentId) != null) {
            if (!available.containsKey(contentId)) {
                webView.stopLoading()
                // It's generally good practice to reset the WebViewClient when returning to pool,
                // especially if the client holds references that might leak or cause issues.
                // A new client is set in acquire anyway.
                webView.webViewClient = WebViewClient()
                (webView.parent as? ViewGroup)?.removeView(webView)
                available[contentId] = webView
            }
        } else {
            // If it was in use but not found (e.g. race condition or error), or never in use but release called.
            Log.w(poolTag, "Released WebView for $contentId was not in 'inUse' map. Destroying.")
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    @Synchronized
    fun destroyAll() {
        Log.d(poolTag, "Destroying ALL WebView: Avail=${available.size}, InUse=${inUse.size}")
        available.values.forEach {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        inUse.values.forEach {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        available.clear()
        inUse.clear()
    }
}