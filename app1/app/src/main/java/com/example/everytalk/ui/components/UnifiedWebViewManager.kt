package com.example.everytalk.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * 统一WebView管理器 - 解决表格卡死和公式乱闪问题
 * 
 * 核心解决策略：
 * 1. 统一实例管理，避免双重WebView系统
 * 2. 生命周期自动管理，防止内存泄漏
 * 3. 智能复用机制，减少创建开销
 * 4. 状态同步机制，解决Compose重组冲突
 */
object UnifiedWebViewManager {
    
    // 使用WeakReference避免内存泄漏
    internal val webViewPool = ConcurrentHashMap<String, WeakReference<WebView>>()
    internal val activeViews = mutableSetOf<WeakReference<WebView>>()
    private var isInitialized = false
    
    /**
     * 安全清理WebView的parent引用 - 公共接口
     */
    fun safelyRemoveFromParent(webView: WebView): Boolean {
        return try {
            val currentParent = webView.parent as? android.view.ViewGroup
            if (currentParent != null) {
                currentParent.removeView(webView)
                android.util.Log.d("UnifiedWebViewManager", "成功从parent移除WebView")
                true
            } else {
                android.util.Log.d("UnifiedWebViewManager", "WebView没有parent，无需移除")
                false
            }
        } catch (e: Exception) {
            android.util.Log.w("UnifiedWebViewManager", "清理WebView parent时出错: ${e.message}", e)
            false
        }
    }
    
    /**
     * 获取WebView实例 - 统一入口
     */
    suspend fun getWebView(context: Context, type: String = "unified"): WebView {
        return withContext(Dispatchers.Main) {
            // 尝试从池中获取
            val existingRef = webViewPool[type]
            val existing = existingRef?.get()
            
            if (existing != null) {
                // 🎯 关键修复：WebView复用前必须先移除旧的parent
                safelyRemoveFromParent(existing)
                existing
            } else {
                // 创建新实例
                createOptimizedWebView(context).also { newWebView ->
                    webViewPool[type] = WeakReference(newWebView)
                    activeViews.add(WeakReference(newWebView))
                }
            }
        }
    }
    
    /**
     * 创建优化的WebView实例
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createOptimizedWebView(context: Context): WebView {
        return WebView(context.applicationContext).apply {
            webViewClient = OptimizedWebViewClient()
            
            settings.apply {
                // 基础设置
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                textZoom = 100
                
                // 性能优化设置
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                setRenderPriority(android.webkit.WebSettings.RenderPriority.HIGH)
                cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                
                // 内存优化 - 移除已废弃的setAppCacheEnabled
                databaseEnabled = false
                setGeolocationEnabled(false)
            }
            
            // 避免长按菜单
            setOnLongClickListener { false }
            isLongClickable = false
            
            // 初始透明状态
            alpha = 0f
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }
    
    /**
     * 优化的WebViewClient
     */
    private class OptimizedWebViewClient : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            // 渲染完成后显示
            view?.alpha = 1f
        }
        
        override fun onReceivedError(
            view: WebView?,
            errorCode: Int,
            description: String?,
            failingUrl: String?
        ) {
            super.onReceivedError(view, errorCode, description, failingUrl)
            // 错误时也显示，避免一直透明
            view?.alpha = 1f
        }
    }
    
    /**
     * 清理无效引用
     */
    fun cleanupDeadReferences() {
        val deadKeys = mutableListOf<String>()
        webViewPool.forEach { (key, ref) ->
            if (ref.get() == null) {
                deadKeys.add(key)
            }
        }
        deadKeys.forEach { webViewPool.remove(it) }
        
        activeViews.removeAll { it.get() == null }
    }
    
    /**
     * 强制清理所有WebView
     */
    fun clearAll() {
        // 🎯 修复：在主线程上安全地清理所有WebView实例
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.post {
            activeViews.forEach { ref ->
                ref.get()?.let { webView ->
                    try {
                        // 确保先从父视图中移除
                        safelyRemoveFromParent(webView)
                        webView.loadUrl("about:blank")
                        webView.clearHistory()
                        webView.clearCache(true)
                        webView.destroy()
                        android.util.Log.d("UnifiedWebViewManager", "成功销毁了一个WebView实例: $webView")
                    } catch (e: Exception) {
                        android.util.Log.w("UnifiedWebViewManager", "清理WebView失败", e)
                    }
                }
            }
            webViewPool.clear()
            activeViews.clear()
            isInitialized = false
            android.util.Log.d("UnifiedWebViewManager", "所有WebView实例和池已清理")
        }
    }

    /**
     * 从池和活跃视图中移除指定的WebView引用
     */
    fun removeWebViewReference(webView: WebView, type: String) {
        activeViews.removeAll { it.get() == webView }
        webViewPool.remove(type)
    }
    
    /**
     * 获取当前活跃WebView数量（调试用）
     */
    fun getActiveViewCount(): Int {
        cleanupDeadReferences()
        return activeViews.count { it.get() != null }
    }
}

/**
 * Compose生命周期集成 - 增强版WebView管理
 */
@Composable
fun rememberManagedWebView(
    type: String = "unified"
): WebView? {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    
    // 生命周期观察
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> webView?.onPause()
                Lifecycle.Event.ON_RESUME -> webView?.onResume()
                Lifecycle.Event.ON_DESTROY -> {
                    // 🎯 关键修复：在组件销毁时彻底销毁WebView实例，防止内存泄漏
                    webView?.let { wv ->
                        UnifiedWebViewManager.safelyRemoveFromParent(wv)
                        wv.destroy()
                        UnifiedWebViewManager.removeWebViewReference(wv, type)
                        android.util.Log.d("UnifiedWebViewManager", "WebView for type '$type' destroyed on ON_DESTROY event.")
                    }
                    webView = null
                }
                else -> { /* No-op */ }
            }
        }
        
        lifecycleOwner.lifecycle.addObserver(observer)
        
        onDispose {
            // DisposableEffect销毁时，确保移除观察者并清理parent
            lifecycleOwner.lifecycle.removeObserver(observer)
            webView?.let { wv ->
                UnifiedWebViewManager.safelyRemoveFromParent(wv)
                android.util.Log.d("UnifiedWebViewManager", "DisposableEffect onDispose cleaned up WebView parent for type '$type'.")
            }
        }
    }
    
    // 异步获取WebView
    LaunchedEffect(context, type) {
        webView = UnifiedWebViewManager.getWebView(context, type)
    }
    
    return webView
}