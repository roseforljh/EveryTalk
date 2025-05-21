// com/example/everytalk/webviewpool/WebViewPool.kt

package com.example.everytalk.webviewpool

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import com.anyaitalked.everytalk.R
import java.util.LinkedHashMap // LRU用
import java.util.UUID

data class WebViewConfig(val htmlTemplate: String, val latexInput: String)

@SuppressLint("SetJavaScriptEnabled")
class WebViewPool(
    private val applicationContext: Context,
    private val maxSize: Int = 8
) {
    private val poolTag = "WebViewPool[${UUID.randomUUID().toString().take(4)}]"

    // LRU池/可管理的WebView：key是contentId
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
                "https://cdn.jsdelivr.net/",
                config.htmlTemplate,
                "text/html",
                "UTF-8",
                null
            )
        }
        inUse[contentId] = webView
        webView.tag = contentId
        // 每次替换WebViewClient
        webView.webViewClient = object : WebViewClient() {
            private var hadError = false
            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                hadError = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (view == webView) {
                    val success = !hadError
                    onPageFinishedCallback(view, success)
                }
            }
        }

        if (!isNewlyCreated) {
            // 已在池中，直接回调为“已就绪”，无需二次load
            webView.post { onPageFinishedCallback(webView, true) }
        }
        return webView
    }

    @Synchronized
    fun release(webView: WebView) {
        val contentId = webView.tag as? String ?: return
        if (inUse.remove(contentId) != null) {
            if (!available.containsKey(contentId)) {
                webView.stopLoading()
                webView.webViewClient = WebViewClient()
                (webView.parent as? ViewGroup)?.removeView(webView)
                available[contentId] = webView
            }
            if (available.size > maxSize) {
                val eldestKey = available.entries.firstOrNull()?.key
                eldestKey?.let {
                    val eldestView = available.remove(it)
                    (eldestView?.parent as? ViewGroup)?.removeView(eldestView)
                    eldestView?.destroy()
                }
            }
        } else {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    @Synchronized
    fun destroyAll() {
        Log.d(poolTag, "Destroying ALL WebView: Avail=${available.size}, InUse=${inUse.size}")
        available.values.forEach { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        inUse.values.forEach { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        available.clear(); inUse.clear()
    }
}
