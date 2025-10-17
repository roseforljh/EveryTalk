
package com.example.everytalk.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.JavascriptInterface
import android.os.Build
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalDensity
import com.example.everytalk.ui.components.IncrementalMarkdownRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.alpha
import android.view.MotionEvent

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MarkdownHtmlView(
    markdown: String,
    isStreaming: Boolean,
    isFinal: Boolean,
    modifier: Modifier = Modifier,
    onRendered: ((ok: Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    val isPageLoaded = remember { mutableStateOf(false) }
    val rememberedWebView = remember { WebView(context) }
    val lastSentContent = remember { mutableStateOf<String?>(null) }
    // 记录"JS侧 last 的长度"，用它来计算真实应发送的增量，彻底消除重复内容
    val lastJsLen = remember { mutableStateOf(0) }
    val isFinalState = remember { mutableStateOf(false) }
    val isLoading = remember { mutableStateOf(false) }  // 🎯 默认不显示loading
    val isVisible = remember { mutableStateOf(true) }  // 🎯 始终可见，避免闪烁
    // 新增：小增量批处理，避免因过滤小增量导致"非前缀重置"
    val pendingDelta = remember { mutableStateOf("") }
    val pendingFlushJob = remember { mutableStateOf<Job?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 引入增量渲染器
    val incRenderer = remember { mutableStateOf<IncrementalMarkdownRenderer?>(null) }
    
    // 🎯 核心修复：流式节流控制（300ms间隔，减少WebView更新频率）
    val lastRenderTime = remember { mutableStateOf(0L) }
    val pendingRenderJob = remember { mutableStateOf<Job?>(null) }
    
    // 🎯 新增：缓存pending的渲染内容，在页面加载完成后立即渲染
    val pendingRenderContent = remember { mutableStateOf<Triple<String, Boolean, Boolean>?>(null) }
    
    // 🎯 执行实际的WebView渲染逻辑（定义在Composable内部以访问remember变量）
    fun executeRender(webView: WebView, markdown: String, isFinal: Boolean, isStreaming: Boolean) {
        val escapedContent = markdown
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("'", "\\'")
            .replace("\n", "\\n")

        val last = lastSentContent.value ?: ""
        val shouldUpdate = (last != escapedContent) || isFinal
        
        if (shouldUpdate) {
            lastRenderTime.value = System.currentTimeMillis()
            val isIncremental = escapedContent.startsWith(last) && last.isNotEmpty()
            
            when {
                // 场景1：流式更新 - 使用全量渲染而不是增量渲染
                // 🔥 修复格式混乱：增量渲染会导致每个小片段被当作独立段落
                // 解决方案：流式期间每次渲染完整内容，确保格式正确
                isIncremental && isStreaming && !isFinal -> {
                    // 取消增量渲染，使用全量渲染
                    incRenderer.value?.updateMarkdown(
                        fullEscapedContent = escapedContent,
                        isFinal = false,
                        isStreaming = true
                    )
                    lastSentContent.value = escapedContent
                    
                    // 🔍 [STREAM_DEBUG_ANDROID]
                    android.util.Log.i("STREAM_DEBUG", "[MarkdownHtmlView] ✅ FULL RENDER (streaming): totalLen=${escapedContent.length}")
                }
                
                // 场景2：最终状态（流式结束）- 使用全量渲染
                isFinal -> {
                    // 取消所有挂起的任务
                    pendingFlushJob.value?.cancel()
                    pendingDelta.value = ""
                    
                    // 全量渲染最终内容
                    incRenderer.value?.updateMarkdown(
                        fullEscapedContent = escapedContent,
                        isFinal = true,
                        isStreaming = false
                    )
                    lastSentContent.value = escapedContent
                    
                    android.util.Log.i("STREAM_DEBUG", "[MarkdownHtmlView] ✅ FULL RENDER (final): totalLen=${escapedContent.length}")
                }
                
                // 场景3：非增量更新（内容完全改变，需要重新渲染）
                else -> {
                    pendingFlushJob.value?.cancel()
                    incRenderer.value?.updateMarkdown(
                        fullEscapedContent = escapedContent,
                        isFinal = isFinal,
                        isStreaming = isStreaming
                    )
                    lastSentContent.value = escapedContent
                    pendingDelta.value = ""
                }
            }
        }
    }

    LaunchedEffect(markdown, isFinal) {
        isFinalState.value = isFinal
        // 🎯 移除loading超时逻辑，避免不必要的状态变化
    }

    // 🎯 优化：只在页面加载完成后执行 WebView 更新
    // 删除不必要的 LaunchedEffect，改为在 AndroidView.update 中统一处理
 
    val density = LocalDensity.current
    val webViewHeight = remember { mutableStateOf(50.dp) }
    val lastHeightPxState = remember { mutableStateOf(0) }
    // 回滚：恢复旧限高（与历史行为一致）
    val maxCapDp = 8000.dp

    // 🎯 修复：移除LaunchedEffect(markdown)，避免流式期间重置高度
    // 问题：每次markdown变化都重置为50dp，导致内容显示不完整
    // 解决：让WebView自动根据内容调整高度，不要手动重置
    
    // 🔥 关键修复：监听 markdown、isFinal 和 isStreaming 的变化
    // 当内容变化 OR 流式状态结束时，都需要触发渲染
    // 这样可以确保流式结束后立即触发最终的Markdown解析
    // 🎯 新增：监听 isStreaming 变化，确保从流式切换到最终状态时强制渲染
    LaunchedEffect(markdown, isFinal, isStreaming) {
        val webView = webViewRef.value
        if (webView != null && isPageLoaded.value) {
            android.util.Log.i("STREAM_DEBUG", "[MarkdownHtmlView] 🔥 LaunchedEffect triggered: len=${markdown.length}, isStreaming=$isStreaming, isFinal=$isFinal, preview='${markdown.take(50)}'")
            executeRender(webView, markdown, isFinal, isStreaming)
        } else {
            android.util.Log.w("STREAM_DEBUG", "[MarkdownHtmlView] ⚠️ Caching render: webView=${webView!=null}, pageLoaded=${isPageLoaded.value}, len=${markdown.length}")
            if (webView != null) {
                // 页面还未加载完成，缓存内容
                pendingRenderContent.value = Triple(markdown, isFinal, isStreaming)
            }
        }
    }
 
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (!isPageLoaded.value) {
                    Modifier.heightIn(min = 50.dp, max = maxCapDp)
                } else {
                    val minDp = webViewHeight.value.coerceAtLeast(24.dp)
                    Modifier.heightIn(min = minDp, max = maxCapDp)
                }
            )
    ) {
        AndroidView(
            modifier = androidx.compose.ui.Modifier
                .fillMaxWidth()
                .height(webViewHeight.value),
                // 🎯 移除alpha控制，避免闪烁
            factory = { ctx ->
                rememberedWebView.apply {
                    android.util.Log.i("MdHtmlView", "WebView factory created (MarkdownHtmlView) — using WebView for markdown")
                    webViewRef.value = this
                    
                    // Task 8: Improved touch event handling for WebView
                    // Detects scroll direction and manages parent touch event interception
                    var startX = 0f
                    var startY = 0f
                    var hasRequestedDisallow = false
                    var scrollDirectionDetermined = false
                    
                    setOnTouchListener { view, event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                // Reset state on new touch
                                startX = event.x
                                startY = event.y
                                hasRequestedDisallow = false
                                scrollDirectionDetermined = false
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val deltaX = kotlin.math.abs(event.x - startX)
                                val deltaY = kotlin.math.abs(event.y - startY)
                                
                                // Task 8: Add 20px movement threshold for direction detection
                                if (!scrollDirectionDetermined && (deltaX >= 20f || deltaY >= 20f)) {
                                    scrollDirectionDetermined = true
                                    
                                    // Determine scroll direction based on which delta is larger
                                    if (deltaX > deltaY) {
                                        // Task 8: Horizontal scrolling detected
                                        // Task 8: Implement requestDisallowInterceptTouchEvent() for horizontal scrolling
                                        view.parent?.requestDisallowInterceptTouchEvent(true)
                                        hasRequestedDisallow = true
                                        android.util.Log.d("MarkdownHtmlView", "Touch: Horizontal scroll detected (deltaX=$deltaX, deltaY=$deltaY)")
                                    } else {
                                        // Task 8: Vertical scrolling detected
                                        // Task 8: Release interception when vertical scrolling is detected
                                        view.parent?.requestDisallowInterceptTouchEvent(false)
                                        hasRequestedDisallow = false
                                        android.util.Log.d("MarkdownHtmlView", "Touch: Vertical scroll detected (deltaX=$deltaX, deltaY=$deltaY)")
                                    }
                                }
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                // Clean up: release interception on touch end
                                if (hasRequestedDisallow) {
                                    view.parent?.requestDisallowInterceptTouchEvent(false)
                                }
                                hasRequestedDisallow = false
                                scrollDirectionDetermined = false
                            }
                        }
                        false // Let WebView handle all events
                    }
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.domStorageEnabled = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.setSupportZoom(false)
                    // 🔥 修复：确保水平和垂直滚动条都启用
                    isHorizontalScrollBarEnabled = true
                    isVerticalScrollBarEnabled = true
                    settings.textZoom = 100
                    setBackgroundColor(Color.TRANSPARENT)
                    
                    // 🔥 修复：启用WebView的触摸事件处理
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    // 🔥 关键修复：确保WebView可以处理触摸事件
                    isClickable = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    // 绑定增量渲染器
                    incRenderer.value = IncrementalMarkdownRenderer(this)
                    // 🔥 强制启用硬件加速以提升滚动与渲染性能
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)

                    WebView.setWebContentsDebuggingEnabled(true)
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                            Log.e("WebViewConsole", "[${consoleMessage.messageLevel()}] ${consoleMessage.message()} -- From line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}")
                            return true
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            super.onPageFinished(view, url)
                            isPageLoaded.value = true
                            
                            // 🎯 关键修复：页面加载完成后，立即渲染pending内容
                            // 这样可以避免reasoning导致item重建时丢失content
                            pendingRenderContent.value?.let { (content, isFinal, isStreaming) ->
                                android.util.Log.i("STREAM_DEBUG", "[MarkdownHtmlView] 🔥 Page loaded, rendering pending content: len=${content.length}")
                                executeRender(view, content, isFinal, isStreaming)
                                pendingRenderContent.value = null
                            }
                        }

                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                if (request?.isForMainFrame == true) {
                                    // 🎯 Graceful degradation for WebView failures (Requirements: 7.4)
                                    Log.e("WebViewError", "Error: ${error?.description} on URL: ${request?.url}")
                                    Log.e("WebViewError", "Graceful degradation: WebView failed to load, content may not render properly")
                                    
                                    // Notify that rendering failed but don't crash
                                    onRendered?.invoke(false)
                                    
                                    // Note: The content is still available in markdown variable
                                    // The UI layer can decide to show a fallback plain text view
                                }
                            }
                            super.onReceivedError(view, request, error)
                        }

                        @Suppress("OverridingDeprecatedMember")
                        override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                                // 🎯 Graceful degradation for WebView failures (Requirements: 7.4)
                                Log.e("WebViewError", "Error: $description on URL: $failingUrl")
                                Log.e("WebViewError", "Graceful degradation: WebView failed to load (legacy), content may not render properly")
                                
                                // Notify that rendering failed but don't crash
                                onRendered?.invoke(false)
                                
                                // Note: The content is still available in markdown variable
                                // The UI layer can decide to show a fallback plain text view
                            }
                            super.onReceivedError(view, errorCode, description, failingUrl)
                        }
                    }
 
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onHeight(px: Int) {
                            try {
                                val raw = if (px < 0) 0 else px
                                val p = raw.coerceIn(0, 120_000)
                            this@apply.post {
                                    val lastPx = lastHeightPxState.value
                                    val finalMode = isFinalState.value
                                    val minDeltaStream = 32
                                    val minDeltaFinalPx = 12
                                    val minDeltaPercent = 0.05f
                                    if (!finalMode) {
                                        val diff = p - lastPx
                                        val threshold = maxOf(minDeltaStream, (lastPx * minDeltaPercent).toInt())
                                        if (diff >= threshold) {
                                            android.util.Log.d("MdHtmlView", "onHeight(stream): p=$p last=$lastPx diff=$diff >= $threshold accept")
                                            lastHeightPxState.value = p
                                            // 回滚：按旧逻辑直接使用 px→dp，无额外上限钳制
                                            webViewHeight.value = p.dp
                                        } else {
                                            android.util.Log.d("MdHtmlView", "onHeight(stream): p=$p last=$lastPx diff=$diff < $threshold skip")
                                        }
                                    } else {
                                        val diff = kotlin.math.abs(p - lastPx)
                                        val threshold = maxOf(minDeltaFinalPx, (lastPx * minDeltaPercent).toInt())
                                        val isFirst = (lastPx == 0 && p > 0)
                                        val accepted = isFirst || diff >= threshold
                                        if (accepted) {
                                            val finalPx = if (p < minDeltaFinalPx) minDeltaFinalPx else p
                                            android.util.Log.d("MdHtmlView", "onHeight(final): p=$p last=$lastPx diff=$diff >= $threshold accept(finalPx=$finalPx)")
                                            lastHeightPxState.value = finalPx
                                            // 回滚：按旧逻辑直接使用 px→dp
                                            webViewHeight.value = finalPx.dp
                                        } else {
                                            android.util.Log.d("MdHtmlView", "onHeight(final): p=$p last=$lastPx diff=$diff < $threshold skip")
                                        }
                                    }
                                }
                            } catch (_: Throwable) { }
                        }
                    }, "AndroidBridge")
 
                    val html = """
                    <!doctype html>
                    <html lang="zh-CN">
                      <head>
                        <meta charset="utf-8"/>
                        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1"/>
                        <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.25/dist/katex.min.css">
                        <style>
                          html,body { margin:0; padding:0; background:transparent; width:100%; box-sizing:border-box; }
                          body{ color:#E6E6E6; font-size: 16px; line-height: 1.6; }
                          #container { padding: 8px; box-sizing:border-box; }
                          #static { }
                          #tail { white-space: pre-wrap; word-break: break-word; font-family: inherit; background: transparent; padding: 0; margin: 0; }
                          .katex-display { overflow-x: auto; -webkit-overflow-scrolling: touch; }
                          
                          /* 代码块容器 - 优化水平滚动支持 */
                          .code-block-wrapper {
                            position: relative;
                            margin: 1em 0;
                            background: #1e1e1e;
                            border-radius: 8px;
                            overflow: hidden;
                            /* 🔥 修复：使用pan-x允许水平滚动，pan-y允许外层垂直滚动 */
                            touch-action: pan-x pan-y;
                          }
                          .code-toolbar { display: flex; justify-content: space-between; align-items: center; padding: 8px 12px; background: #2d2d2d; border-bottom: 1px solid #3a3a3a; }
                          .code-lang { font-size: 12px; color: #888; font-weight: 500; text-transform: uppercase; }
                          .copy-btn { background: #4a4a4a; color: #fff; border: none; padding: 4px 12px; border-radius: 4px; font-size: 12px; cursor: pointer; transition: background 0.2s; }
                          .copy-btn:hover { background: #5a5a5a; }
                          .copy-btn:active { background: #3a3a3a; }
                          .copy-btn.copied { background: #4caf50; }
                          
                          /* 🔥 修复：优化代码块的水平滚动 */
                          pre {
                            margin: 0;
                            padding: 16px;
                            overflow-x: auto;
                            overflow-y: hidden;
                            -webkit-overflow-scrolling: touch;
                            white-space: pre;
                            background-color: transparent;
                            border-radius: 0;
                            /* 🔥 关键修复：允许水平滚动，同时保留垂直滚动给外层 */
                            touch-action: pan-x pan-y pinch-zoom;
                            /* 🔥 添加滚动条样式，确保用户知道可以滚动 */
                            scrollbar-width: thin;
                            scrollbar-color: #666 #2d2d2d;
                            /* 🔥 硬件加速优化：提示浏览器即将滚动 */
                            will-change: scroll-position;
                            transform: translateZ(0);
                            backface-visibility: hidden;
                            perspective: 1000px;
                          }
                          
                          /* 🔥 WebKit滚动条样式 */
                          pre::-webkit-scrollbar {
                            height: 8px;
                          }
                          pre::-webkit-scrollbar-track {
                            background: #2d2d2d;
                            border-radius: 4px;
                          }
                          pre::-webkit-scrollbar-thumb {
                            background: #666;
                            border-radius: 4px;
                          }
                          pre::-webkit-scrollbar-thumb:hover {
                            background: #888;
                          }
                          
                          pre > code { 
                            font-family: 'Consolas', 'Monaco', 'Courier New', monospace; 
                            background-color: transparent; 
                            padding: 0; 
                            font-size: 14px; 
                            line-height: 1.5; 
                            /* 🔥 确保代码不换行，支持水平滚动 */
                            white-space: pre;
                            word-wrap: normal;
                            overflow-wrap: normal;
                            /* 🔥 硬件加速优化 */
                            transform: translateZ(0);
                            will-change: contents;
                          }
                          
                          .katex { color: inherit; }
                          
                          /* 🔥 修复：优化实时代码预览的水平滚动 */
                          #liveCodePre {
                            display:none;
                            margin: 1em 0;
                            padding:16px;
                            overflow-x:auto;
                            overflow-y: hidden;
                            background-color:#1e1e1e;
                            border-radius:8px;
                            touch-action: pan-x pan-y pinch-zoom;
                            -webkit-overflow-scrolling: touch;
                            /* 🔥 硬件加速优化 */
                            will-change: scroll-position;
                            transform: translateZ(0);
                          }
                          #live-code { 
                            white-space: pre; 
                            word-break: normal; 
                            font-family: 'Consolas', 'Monaco', 'Courier New', monospace; 
                          }
                          
                          /* 🔥 修复：优化数学公式的水平滚动 */
                          .katex-display {
                            text-align:left;
                            overflow-x: auto;
                            overflow-y: hidden;
                            -webkit-overflow-scrolling: touch;
                            /* 🔥 关键修复：数学公式支持水平滚动 */
                            touch-action: pan-x pan-y pinch-zoom;
                            padding: 8px 0;
                            margin: 1em 0;
                            /* 🔥 硬件加速优化 */
                            will-change: scroll-position;
                            transform: translateZ(0);
                          }
                          
                          /* 🔥 数学公式滚动条样式 */
                          .katex-display::-webkit-scrollbar {
                            height: 6px;
                          }
                          .katex-display::-webkit-scrollbar-track {
                            background: rgba(255,255,255,0.1);
                            border-radius: 3px;
                          }
                          .katex-display::-webkit-scrollbar-thumb {
                            background: rgba(255,255,255,0.3);
                            border-radius: 3px;
                          }
                          
                          table { display:block; max-width:100%; overflow-x:auto; -webkit-overflow-scrolling:touch; border-collapse:collapse; touch-action: pan-x pan-y; will-change: scroll-position; transform: translateZ(0); }
                          thead, tbody, tr, th, td { box-sizing:border-box; }
                          th, td { word-break:break-word; white-space:normal; padding:8px; border:1px solid rgba(255,255,255,0.12); }
                          td pre, td code { white-space:pre-wrap; word-break:break-word; }
                          img, video, canvas, svg { max-width:100%; height:auto; }
                          a, code, kbd, samp { word-break:break-word; overflow-wrap:anywhere; }
                        </style>
                      </head>
                      <body>
                        <div id="container">
                          <div id="static"></div>
                          <pre id="liveCodePre"><code id="live-code"></code></pre>
                          <div id="tail"></div>
                        </div>
                        <script defer src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script>
                        <script defer src="https://cdn.jsdelivr.net/npm/katex@0.16.25/dist/katex.min.js"></script>
                        <script defer src="https://cdn.jsdelivr.net/npm/katex@0.16.25/dist/contrib/auto-render.min.js"></script>
                        <script>
                          const container = document.getElementById('container');
                          const staticC = document.getElementById('static');
                          const tail = document.getElementById('tail');
                          const liveCode = document.getElementById('live-code');
                          const liveCodePre = document.getElementById('liveCodePre');
                          
                          try {
                            const obs = new MutationObserver(function() {
                              notifyHeightThrottled();
                            });
                            obs.observe(container, { childList: true, subtree: true, characterData: true });
                            window.addEventListener('resize', notifyHeightThrottled);
                            window.addEventListener('load', notifyHeightThrottled);
                          } catch(_e) {}
                          
                          function notifyHeight() {
                            try {
                              var rectH = (container.getBoundingClientRect && container.getBoundingClientRect().height) || 0;
                              var cssPx = rectH || container.scrollHeight || 0;
                              var h = Math.ceil(cssPx);
                              if (h < 0) h = 0;
                              if (h > 120000) h = 120000;
                              AndroidBridge.onHeight(h);
                            } catch (e) {}
                          }
                          let _nhTimer = null;
                          // 🎯 Task 11: Track WebView height notification frequency
                          // Performance monitoring for height notifications
                          // Requirements: 4.2
                          window._heightNotificationCount = 0;
                          window._heightNotificationStartTime = Date.now();
                          window._lastHeightNotificationTime = 0;
                          
                          function notifyHeightThrottled() {
                            // 🔥 基于流式/最终两种模式节流，提升气泡高度变更的平滑度
                            if (_nhTimer) return;
                            const streaming = !!window._isStreaming;
                            // 更快：流式 120ms，最终 160ms
                            const throttle = streaming ? 120 : 160;
                            
                            _nhTimer = setTimeout(function(){
                              _nhTimer = null;
                              try {
                                var rectH = (container.getBoundingClientRect && container.getBoundingClientRect().height) || 0;
                                var cssPx = rectH || container.scrollHeight || 0;
                                var h = Math.ceil(cssPx);
                                if (h < 0) h = 0;
                                if (h > 120000) h = 120000;
                                
                                window._lastNotifiedH = window._lastNotifiedH || 0;
                                
                                // 更细腻的高度更新阈值：流式 24px，最终 12px
                                const minDelta = streaming ? 24 : 12;
                                
                                // 🔥 Task 5.5: Prevent height notifications during code block accumulation
                                // When in streaming mode and inside a code block, skip height notification
                                // to avoid layout shifts while code is being accumulated
                                if (streaming && codeOpen) {
                                  // Skip notification during code block accumulation
                                  return;
                                }
                                
                                if (Math.abs(h - window._lastNotifiedH) >= minDelta) {
                                  // 🎯 Task 11: Track height notification frequency
                                  const now = Date.now();
                                  const timeSinceLastNotification = now - window._lastHeightNotificationTime;
                                  window._heightNotificationCount++;
                                  window._lastHeightNotificationTime = now;
                                  
                                  // Log every 10th notification to track frequency
                                  if (window._heightNotificationCount % 10 === 0) {
                                    const totalTime = now - window._heightNotificationStartTime;
                                    const avgInterval = totalTime / window._heightNotificationCount;
                                    const notificationsPerSecond = (window._heightNotificationCount / (totalTime / 1000)).toFixed(2);
                                    
                                    console.log('[WebView Height] Notification #' + window._heightNotificationCount + ': ' +
                                      'height=' + h + 'px, ' +
                                      'delta=' + Math.abs(h - window._lastNotifiedH) + 'px, ' +
                                      'timeSinceLast=' + timeSinceLastNotification + 'ms, ' +
                                      'avgInterval=' + avgInterval.toFixed(0) + 'ms, ' +
                                      'rate=' + notificationsPerSecond + '/sec, ' +
                                      'streaming=' + streaming);
                                  }
                                  
                                  window._lastNotifiedH = h;
                                  AndroidBridge.onHeight(h);
                                }
                              } catch(e) {}
                            }, throttle);
                          }

                          function configureMarked() {
                            try {
                              if (typeof marked !== 'undefined' && !marked.__configured) {
                                marked.setOptions({ gfm: true, breaks: true });
                                marked.__configured = true;
                              }
                            } catch (e) {}
                          }

                          function libsReady() {
                            return (typeof marked !== 'undefined') && (typeof renderMathInElement === 'function');
                          }
                          function ensureLibs() {
                            try {
                              if (libsReady() || window.__libsLoading) return;
                              window.__libsLoading = true;
                              var cdns = [
                                { marked: "https://cdn.jsdelivr.net/npm/marked/marked.min.js",
                                  katex: "https://cdn.jsdelivr.net/npm/katex@0.16.25/dist/katex.min.js",
                                  autorender: "https://cdn.jsdelivr.net/npm/katex@0.16.25/dist/contrib/auto-render.min.js" },
                                { marked: "https://unpkg.com/marked/marked.min.js",
                                  katex: "https://unpkg.com/katex@0.16.25/dist/katex.min.js",
                                  autorender: "https://unpkg.com/katex@0.16.25/dist/contrib/auto-render.min.js" }
                              ];
                              function loadScript(src, cb) {
                                var s = document.createElement('script');
                                s.src = src; s.onload = function(){ cb && cb(true); }; s.onerror = function(){ cb && cb(false); };
                                document.head.appendChild(s);
                              }
                              function tryIdx(i) {
                                if (i >= cdns.length) { return; }
                                loadScript(cdns[i].marked, function() {
                                  loadScript(cdns[i].katex, function() {
                                    loadScript(cdns[i].autorender, function() {
                                      if (!libsReady()) { tryIdx(i + 1); }
                                    });
                                  });
                                });
                              }
                              tryIdx(0);
                            } catch(_e) {}
                          }

                          function protectMath(src) {
                            const mathStore = [];
                            function placeholder(i) { return '[[MATH_PLACEHOLDER_' + i + ']]'; }
                            let tmp = src;
                            tmp = tmp.replace(/\\\[([\s\S]*?)\\\]/g, function(_m, f) { mathStore.push({ formula: f, left: "\\[", right: "\\]" }); return placeholder(mathStore.length - 1); });
                            tmp = tmp.replace(/\\\(([\s\S]*?)\\\)/g, function(_m, f) { mathStore.push({ formula: f, left: "\\(", right: "\\)" }); return placeholder(mathStore.length - 1); });
                            tmp = tmp.replace(/\$\$[\s\S]*?\$\$/g, function(_m) { var f = _m.slice(2, -2); mathStore.push({ formula: f, left: '$$', right: '$$' }); return placeholder(mathStore.length - 1); });
                            tmp = tmp.replace(/\$([^\$\n]+?)\$/g, function(_m, f) { mathStore.push({ formula: f, left: '$', right: '$' }); return placeholder(mathStore.length - 1); });
                            return { tmp, mathStore };
                          }
                          function restoreMath(html, mathStore) {
                            return html.replace(/\[\[MATH_PLACEHOLDER_(\d+)\]\]/g, function(_m, i) {
                              const item = mathStore[parseInt(i, 10)];
                              if (!item) return _m;
                              const left = item.left || (item.display ? '$$' : '$');
                              const right = item.right || (item.display ? '$$' : '$');
                              return left + item.formula + right;
                            });
                          }

                          let last = '';
                          let buffer = '';
                          let codeOpen = false;
                          let prevCodeOpen = false;
                          let currentFenceToken = '';
                          let currentFenceChar = '';
                          let currentFenceLen = 0;
                          let currentLang = '';

                          /* duplicate findSafeIndex removed */

                         // Detect safe commit boundary without mutating global code state
                         // Find safe commit point: prefer sentence/paragraph boundary outside code blocks
                         function findSafeIndex(text) {
                           // 🔥 业内最佳实践：流式期间实时渲染完整的Markdown行
                           // 策略：检测完整行边界（\n）、段落边界（\n\n）、句子边界（。！？\n）
                           // 这是 ChatGPT、Claude 等主流应用的做法
                           
                           // 代码块特殊处理：等待闭合标记，避免被切断
                           if (window._isStreaming && codeOpen) return -1;
                           
                           let i = 0;
                           let safe = -1;
                           let localOpen = codeOpen;
                           
                           // 🔥 业内最佳实践：流式期间检测完整行
                           // 优先级：段落（\n\n）> 完整行（\n）> 句子（。！？\n）
                           
                           while (i < text.length) {
                             const slice = text.slice(i);
                             const fenceInfo = localOpen
                               ? findClosingFence(slice, currentFenceChar, currentFenceLen)
                               : findOpeningFence(slice);
                             const paraIdx = text.indexOf('\n\n', i);
                             
                             // 🔥 流式期间：检测完整行（用于标题、列表等）
                             let lineIdx = -1;
                             if (!localOpen && window._isStreaming) {
                               const newlinePos = text.indexOf('\n', i);
                               if (newlinePos !== -1) {
                                 lineIdx = newlinePos + 1;
                               }
                             }
                             
                             // 非流式或作为备选：检测句子边界
                             let sentenceIdx = -1;
                             if (!localOpen && !window._isStreaming) {
                               const sentenceRegex = /([。！？!?])\s|\n/g;
                               const m = sentenceRegex.exec(slice);
                               if (m) sentenceIdx = i + m.index + (m[1] ? m[1].length : 0) + 1;
                               sentenceRegex.lastIndex = 0;
                             }
                             
                             // 🔥 决策：取最早出现的安全边界
                             // 优先级：代码块 > 段落 > 完整行（流式）> 句子（非流式）
                             const candidates = [];
                             if (fenceInfo) candidates.push(i + fenceInfo.idx + (fenceInfo.len || 3) + 1);
                             if (paraIdx !== -1) candidates.push(paraIdx + 2);
                             if (lineIdx !== -1) candidates.push(lineIdx);
                             if (sentenceIdx !== -1) candidates.push(sentenceIdx);
                             const nextIdx = candidates.length ? Math.min.apply(null, candidates) : -1;
                             
                             if (nextIdx !== -1) {
                               // 如果命中的是围栏，则翻转 localOpen
                               if (fenceInfo && (i + fenceInfo.idx + (fenceInfo.len || 3) + 1) === nextIdx) {
                                 localOpen = !localOpen;
                                 // 关闭围栏后也可作为一次提交点
                                 if (!localOpen) safe = nextIdx;
                               } else {
                                 // 段落或句子结束：记录为提交点
                                 if (!localOpen) safe = nextIdx;
                               }
                               i = nextIdx;
                             } else {
                               break;
                             }
                           }
                           
                           // 兜底：若是最终态且无代码块，允许提交全部
                           return safe;
                         }

                         // Utilities: scan for opening/closing fenced code at line start (indent <= 3)
                         function findOpeningFence(s) {
                           let pos = 0;
                           while (pos <= s.length - 3) {
                             const lineStart = pos === 0 ? 0 : (s.lastIndexOf('\n', pos - 1) + 1);
                             let i = lineStart;
                             // allow up to 3 leading spaces
                             let spaces = 0;
                             while (i < s.length && spaces < 3 && s[i] === ' ') { i++; spaces++; }
                             if (i + 2 < s.length) {
                               const ch = s[i];
                               if (ch === '`' || ch === '~') {
                                 let j = i;
                                 while (j < s.length && s[j] === ch) j++;
                                 const cnt = j - i;
                                 if (cnt >= 3) {
                                   // parse optional language token after spaces
                                   let k = j;
                                   while (k < s.length && s[k] === ' ') k++;
                                   const langStart = k;
                                   while (k < s.length && s[k] !== ' ' && s[k] !== '\n') k++;
                                   const lang = s.slice(langStart, k);
                                   return { idx: i, ch, len: cnt, lang };
                                 }
                               }
                             }
                             const nextNl = s.indexOf('\n', lineStart);
                             if (nextNl === -1) break;
                             pos = nextNl + 1;
                           }
                           return null;
                         }

                         function findClosingFence(s, ch, minLen) {
                           let pos = 0;
                           while (pos <= s.length - 3) {
                             const lineStart = pos === 0 ? 0 : (s.lastIndexOf('\n', pos - 1) + 1);
                             let i = lineStart;
                             // allow up to 3 leading spaces
                             let spaces = 0;
                             while (i < s.length && spaces < 3 && s[i] === ' ') { i++; spaces++; }
                             if (i + 2 < s.length && s[i] === ch) {
                               let j = i;
                               while (j < s.length && s[j] === ch) j++;
                               const cnt = j - i;
                               if (cnt >= minLen) {
                                 // rest of line must be spaces only
                                 let k = j;
                                 while (k < s.length && s[k] === ' ') k++;
                                 if (k >= s.length || s[k] === '\n') {
                                   return { idx: i, len: cnt };
                                 }
                               }
                             }
                             const nextNl = s.indexOf('\n', lineStart);
                             if (nextNl === -1) break;
                             pos = nextNl + 1;
                           }
                           return null;
                         }

                         // Process complete code blocks only when closing fence is found
                         function processCodeFencesInline() {
                           // 🔥 业内最佳实践：流式期间也处理完整的代码块
                           // ChatGPT/Claude 的做法：当检测到完整代码块（有闭合标记）时立即渲染
                           // 未完成的代码块在 liveCodePre 中实时预览
                           
                           // 🔥 优化：检测代码块开始标记
                           if (!codeOpen) {
                             const open = findOpeningFence(buffer);
                             if (open) {
                               // Commit text before fence
                               const plain = buffer.slice(0, open.idx);
                               if (plain) commitMarkdown(plain);
                               
                               // Enter code mode
                               currentFenceChar = open.ch;
                               currentFenceLen = open.len;
                               currentLang = open.lang || '';
                               
                               // Skip the opening fence line
                               let j = open.idx + open.len;
                               while (j < buffer.length && buffer[j] === ' ') j++;
                               const nextNl = buffer.indexOf('\n', j);
                               buffer = nextNl === -1 ? '' : buffer.slice(nextNl + 1);
                               
                               codeOpen = true;
                               
                               // 🔥 优化：进入代码块时，初始化实时预览区域
                               if (liveCode) liveCode.textContent = '';
                               if (liveCodePre) liveCodePre.style.display = 'block';
                             }
                           }
                           
                           // 🔥 优化：处理代码块内容
                           if (codeOpen) {
                             const close = findClosingFence(buffer, currentFenceChar, currentFenceLen);
                             
                             if (close) {
                               // 🔥 优化：检测到代码块结束标记 - 完整渲染代码块
                               const codeText = buffer.slice(0, close.idx);
                               
                               try {
                                 // Create code block wrapper with toolbar
                                 const wrapper = document.createElement('div');
                                 wrapper.className = 'code-block-wrapper';
                                 
                                 const toolbar = document.createElement('div');
                                 toolbar.className = 'code-toolbar';
                                 
                                 const langLabel = document.createElement('span');
                                 langLabel.className = 'code-lang';
                                 langLabel.textContent = currentLang || 'text';
                                 
                                 const copyBtn = document.createElement('button');
                                 copyBtn.className = 'copy-btn';
                                 copyBtn.textContent = '复制';
                                 copyBtn.onclick = function() {
                                   try {
                                     if (navigator.clipboard && navigator.clipboard.writeText) {
                                       navigator.clipboard.writeText(codeText).then(function() {
                                         copyBtn.textContent = '已复制';
                                         copyBtn.classList.add('copied');
                                         setTimeout(function() {
                                           copyBtn.textContent = '复制';
                                           copyBtn.classList.remove('copied');
                                         }, 2000);
                                       });
                                     }
                                   } catch(e) {}
                                 };
                                 
                                 toolbar.appendChild(langLabel);
                                 toolbar.appendChild(copyBtn);
                                 wrapper.appendChild(toolbar);
                                 
                                 const pre = document.createElement('pre');
                                 const code = document.createElement('code');
                                 if (currentLang) code.className = 'language-' + currentLang;
                                 code.textContent = codeText;
                                 pre.appendChild(code);
                                 wrapper.appendChild(pre);
                                 
                                 // 🔥 优化：提交完整的代码块到静态容器
                                 staticC.appendChild(wrapper);
                               } catch(_e) {
                                 console.error('Error rendering code block:', _e);
                               }
                               
                               // 🔥 优化：清理代码块状态和实时预览
                               if (liveCode) liveCode.textContent = '';
                               if (liveCodePre) liveCodePre.style.display = 'none';
                               
                               // Skip the closing fence line
                               let j = close.idx + close.len;
                               while (j < buffer.length && buffer[j] === ' ') j++;
                               const nextNl = buffer.indexOf('\n', j);
                               buffer = nextNl === -1 ? '' : buffer.slice(nextNl + 1);
                               
                               // Reset code block state
                               codeOpen = false;
                               currentFenceChar = '';
                               currentFenceLen = 0;
                               currentLang = '';
                               prevCodeOpen = false;
                             } else {
                               // 🔥 优化：代码块未完成 - 在实时预览中累积显示，不清空buffer
                               // 这确保了代码块内容的连续性，避免内容丢失
                               if (liveCode && buffer.length > 0) {
                                 liveCode.textContent = buffer;
                                 if (liveCodePre) liveCodePre.style.display = 'block';
                               }
                               // 🔥 关键优化：不清空buffer！保持代码块内容完整
                               // 让buffer继续累积，直到检测到闭合标记
                             }
                           }
                         }

                          function commitMarkdown(md) {
                            const res = protectMath(md);
                            let html = marked.parse(res.tmp);
                            html = restoreMath(html, res.mathStore);
                            const frag = document.createElement('div');
                            frag.innerHTML = html;
                            try {
                              renderMathInElement(frag, {
                                delimiters: [
                                  { left: "\\\\[", right: "\\\\]", display: true },
                                  { left: "\\\\(", right: "\\\\)", display: false },
                                  { left: '$$', right: '$$', display: true },
                                  { left: '$', right: '$', display: false }
                                ],
                                throwOnError: false,
                                ignoredTags: ['pre','code'],
                                ignoredClasses: ['katex']
                              });
                            } catch(_e){}
                            
                            // 为代码块添加工具栏
                            try {
                              const pres = frag.querySelectorAll('pre > code');
                              pres.forEach(function(codeEl) {
                                const preEl = codeEl.parentElement;
                                const lang = (codeEl.className.match(/language-(\w+)/) || ['', 'text'])[1];
                                const codeText = codeEl.textContent;
                                
                                const wrapper = document.createElement('div');
                                wrapper.className = 'code-block-wrapper';
                                
                                const toolbar = document.createElement('div');
                                toolbar.className = 'code-toolbar';
                                
                                const langLabel = document.createElement('span');
                                langLabel.className = 'code-lang';
                                langLabel.textContent = lang;
                                
                                const copyBtn = document.createElement('button');
                                copyBtn.className = 'copy-btn';
                                copyBtn.textContent = '复制';
                                copyBtn.onclick = function() {
                                  try {
                                    if (navigator.clipboard && navigator.clipboard.writeText) {
                                      navigator.clipboard.writeText(codeText).then(function() {
                                        copyBtn.textContent = '已复制';
                                        copyBtn.classList.add('copied');
                                        setTimeout(function() {
                                          copyBtn.textContent = '复制';
                                          copyBtn.classList.remove('copied');
                                        }, 2000);
                                      });
                                    }
                                  } catch(e) {}
                                };
                                
                                toolbar.appendChild(langLabel);
                                toolbar.appendChild(copyBtn);
                                wrapper.appendChild(toolbar);
                                
                                const newPre = preEl.cloneNode(true);
                                wrapper.appendChild(newPre);
                                
                                preEl.parentNode.replaceChild(wrapper, preEl);
                              });
                            } catch(_e) {}
                            
                            try {
                              const imgs = frag.querySelectorAll('img');
                              imgs.forEach(function(img){
                                img.addEventListener('load', notifyHeightThrottled, { once: true });
                                img.addEventListener('error', notifyHeightThrottled, { once: true });
                              });
                            } catch(_e) {}
                            
                            while (frag.firstChild) staticC.appendChild(frag.firstChild);
                          }

                          function autoScrollIfNeeded(force) { /* no-op in auto-height mode */ }

                          // 🔥 Task 6.1: Implement findLongestCommonPrefix() in JavaScript
                          // Finds the longest common prefix between two strings
                          // Returns the length of the common prefix
                          function findLongestCommonPrefix(str1, str2) {
                            if (!str1 || !str2) return 0;
                            
                            const maxLen = Math.min(str1.length, str2.length);
                            let i = 0;
                            
                            // Compare character by character using charCodeAt for performance
                            while (i < maxLen && str1.charCodeAt(i) === str2.charCodeAt(i)) {
                              i++;
                            }
                            
                            return i;
                          }

                          let waiter = null, pending = null;
                          window.updateMarkdown = function(newContent, isFinal, isStreaming) {
                            window._isStreaming = !!isStreaming;
                            try {
                              console.debug('WEBVIEW_ACTIVE:updateMarkdown-call', { newLen: (newContent || '').length, lastLen: (last || '').length, isFinal: !!isFinal, isStreaming: !!isStreaming });
                              configureMarked();
                            } catch(_e){}
                            if (!libsReady()) {
                              try { ensureLibs(); } catch(_e) {}
                              pending = [newContent, !!isFinal, !!isStreaming];
                              if (!waiter) {
                                let tries = 0;
                                waiter = setInterval(function() {
                                  tries++;
                                  if (libsReady()) {
                                    clearInterval(waiter); waiter = null;
                                    const data = pending; pending = null;
                                    if (data) window.updateMarkdown(data[0], data[1], data[2]);
                                  } else if (tries > 100) {
                                    clearInterval(waiter); waiter = null;
                                    tail.textContent = newContent || '';
                                    notifyHeight();
                                  }
                                }, 100);
                              }
                              return;
                            }

                            let delta = '';
                            // 🔥 Task 6.2: Update updateMarkdown() to detect non-prefix updates
                            if (newContent && newContent.indexOf(last) === 0) {
                              // Prefix match - normal incremental growth
                              delta = newContent.slice(last.length);
                              buffer += delta;
                              last = newContent || '';
                            } else {
                              // 🔥 Task 6.2: Non-prefix update detected
                              // 非前缀：在流式模式尝试“最长公共前缀(LCP)”缓解，尽量避免从头重建
                              let handled = false;
                              
                              // 🔥 Task 6.5: Only attempt LCP recovery during streaming (not in final state)
                              if (isStreaming && !isFinal) {
                                try {
                                  const oldStr = last || '';
                                  const newStr = newContent || '';
                                  if (oldStr && newStr) {
                                  // 🔥 Task 6.1: Use findLongestCommonPrefix() function
                                  const lcpLength = findLongestCommonPrefix(oldStr, newStr);
                                  const lcpRatio = oldStr.length > 0 ? (lcpLength / oldStr.length) : 0;
                                  
                                  // 🔥 Task 6.3: Add 70% LCP threshold check for incremental append
                                  if (lcpRatio >= 0.7) {
                                    // LCP covers ≥70% of previous content - use incremental append
                                    const appendPart = newStr.slice(lcpLength);
                                    
                                    // 🔥 Task 6.4: Log non-prefix events for debugging
                                    try {
                                      console.warn('[LCP-RECOVERY] Non-prefix update recovered via LCP', {
                                        prevLen: oldStr.length,
                                        newLen: newStr.length,
                                        lcpLength: lcpLength,
                                        lcpRatio: lcpRatio.toFixed(3),
                                        appendLen: appendPart.length,
                                        strategy: 'incremental-append'
                                      });
                                    } catch(_e) {}
                                    
                                    buffer += appendPart;
                                    last = newStr;
                                    handled = true;
                                  } else {
                                    // 🔥 Task 6.4: Log non-prefix events for debugging
                                    try {
                                      console.warn('[LCP-RECOVERY] Non-prefix update - LCP ratio too low, performing full reset', {
                                        prevLen: oldStr.length,
                                        newLen: newStr.length,
                                        lcpLength: lcpLength,
                                        lcpRatio: lcpRatio.toFixed(3),
                                        threshold: 0.7,
                                        strategy: 'full-reset'
                                      });
                                    } catch(_e) {}
                                  }
                                }
                                } catch(e) {
                                  // 🔥 Task 6.4: Log errors for debugging
                                  try {
                                    console.error('[LCP-RECOVERY] Error during LCP calculation', e);
                                  } catch(_e) {}
                                }
                              } else {
                                // 🔥 Task 6.5: Final state - always perform full reset
                                // 🔥 Task 6.4: Log non-prefix events for debugging
                                try {
                                  console.warn('[LCP-RECOVERY] Non-prefix update in final state, performing full reset', {
                                    prevLen: (last || '').length,
                                    newLen: (newContent || '').length,
                                    isFinal: isFinal,
                                    isStreaming: isStreaming,
                                    strategy: 'full-reset'
                                  });
                                } catch(_e) {}
                              }
                              
                              if (!handled) {
                                // 🔥 Task 6.5: Full reset for final state or low LCP ratio
                                staticC.innerHTML = '';
                                tail.textContent = '';
                                if (liveCode) { liveCode.textContent = ''; }
                                if (liveCodePre) { liveCodePre.style.display = 'none'; }
                                buffer = '';
                                codeOpen = false;
                                last = '';
                                delta = newContent || '';
                                buffer += delta;
                                last = newContent || '';
                              }
                            }

                            // Process complete code blocks first
                            processCodeFencesInline();

                            // Then handle normal text commits
                            let safeIdx = findSafeIndex(buffer);
                            if (isFinal && !codeOpen) safeIdx = buffer.length;

                            if (safeIdx > 0) {
                              const safe = buffer.slice(0, safeIdx);
                              try { console.debug('commit-safe(updateMarkdown)', {safeIdx, safeLen: safe.length, bufferLen: buffer.length}); } catch(_e){}
                              commitMarkdown(safe);
                              buffer = buffer.slice(safeIdx);
                            }
                            
                            // 🔥 优化：在代码块未完成时，更新实时预览但不修改buffer
                            // 这确保了代码块内容的连续性和完整性
                            if (codeOpen && !isFinal) {
                              if (liveCode && buffer.length > 0) {
                                liveCode.textContent = buffer;
                                if (liveCodePre) liveCodePre.style.display = 'block';
                              }
                              // 🔥 关键优化：不清空buffer，保持代码块内容完整
                              // buffer继续累积，直到检测到闭合标记
                            }
                            
                            // 🔥 优化：tail显示未提交的buffer内容（包括未完成的代码块）
                            tail.textContent = buffer;
                            notifyHeightThrottled();
                            if (isFinal) {
                              // Final flush
                              try {
                                if (codeOpen) {
                                  const codeText = (liveCode ? liveCode.textContent : '') + buffer;
                                  if (codeText) {
                                    const wrapper = document.createElement('div');
                                    wrapper.className = 'code-block-wrapper';
                                    const toolbar = document.createElement('div');
                                    toolbar.className = 'code-toolbar';
                                    const langLabel = document.createElement('span');
                                    langLabel.className = 'code-lang';
                                    langLabel.textContent = currentLang || 'text';
                                    const copyBtn = document.createElement('button');
                                    copyBtn.className = 'copy-btn';
                                    copyBtn.textContent = '复制';
                                    copyBtn.onclick = function() {
                                      try {
                                        if (navigator.clipboard && navigator.clipboard.writeText) {
                                          navigator.clipboard.writeText(codeText).then(function() {
                                            copyBtn.textContent = '已复制';
                                            copyBtn.classList.add('copied');
                                            setTimeout(function() {
                                              copyBtn.textContent = '复制';
                                              copyBtn.classList.remove('copied');
                                            }, 2000);
                                          });
                                        }
                                      } catch(e) {}
                                    };
                                    toolbar.appendChild(langLabel);
                                    toolbar.appendChild(copyBtn);
                                    wrapper.appendChild(toolbar);
                                    const pre = document.createElement('pre');
                                    const code = document.createElement('code');
                                    if (currentLang) code.className = 'language-' + currentLang;
                                    code.textContent = codeText;
                                    pre.appendChild(code);
                                    wrapper.appendChild(pre);
                                    staticC.appendChild(wrapper);
                                  }
                                  if (liveCode) liveCode.textContent = '';
                                  if (liveCodePre) liveCodePre.style.display = 'none';
                                  codeOpen = false;
                                  currentFenceChar = '';
                                  currentFenceLen = 0;
                                  currentLang = '';
                                  buffer = '';
                                } else if (buffer) {
                                  commitMarkdown(buffer);
                                  buffer = '';
                                }
                                tail.textContent = '';
                              } catch(_e){}
                              setTimeout(notifyHeight, 0);
                              setTimeout(notifyHeight, 120);
                              setTimeout(notifyHeight, 260);
                              try {
                                window._lastNotifiedH = 0;
                                var _rectH = (container.getBoundingClientRect && container.getBoundingClientRect().height) || 0;
                                var _cssPx = _rectH || container.scrollHeight || 0;
                                var _h = Math.ceil(_cssPx);
                                if (_h < 0) _h = 0;
                                if (_h > 120000) _h = 120000;
                                AndroidBridge.onHeight(_h);
                              } catch(_e){}
                            }
                          };

                          // 仅追加增量，避免走全量重建路径；保持 last/buffer 的严格前缀增长
                          window.appendDelta = function(delta, isFinal, isStreaming) {
                            window._isStreaming = !!isStreaming;
                            try { configureMarked(); } catch(_e){}
                            if (!libsReady()) {
                              // 回退：库未就绪时，拼接后走全量路径，避免丢增量
                              return window.updateMarkdown((last || '') + (delta || ''), isFinal, isStreaming);
                            }

                            if (delta) {
                              buffer += delta;
                              const before = (last || '').length;
                              last = (last || '') + delta;
                              try { console.debug('appendDelta', {deltaLen: delta.length, lastBefore: before, lastAfter: last.length}); } catch(_e){}
                            }

                            // 先处理完整的代码块
                            processCodeFencesInline();

                            // 然后处理正常文本的安全提交
                            let safeIdx = findSafeIndex(buffer);
                            if (isFinal && !codeOpen) safeIdx = buffer.length;

                            if (safeIdx > 0) {
                              const safe = buffer.slice(0, safeIdx);
                              commitMarkdown(safe);
                              buffer = buffer.slice(safeIdx);
                            }
                            
                            // 🔥 优化：在代码块未完成时，更新实时预览但不修改buffer
                            // 这确保了代码块内容的连续性和完整性
                            if (codeOpen && !isFinal) {
                              if (liveCode && buffer.length > 0) {
                                liveCode.textContent = buffer;
                                if (liveCodePre) liveCodePre.style.display = 'block';
                              }
                              // 🔥 关键优化：不清空buffer，保持代码块内容完整
                              // buffer继续累积，直到检测到闭合标记
                            }

                            // 尾部直出：确保“1..100”连续可见
                            tail.textContent = buffer;
                            try { console.debug('tail-update(appendDelta)', {bufferLen: buffer.length, codeOpen}); } catch(_e){}
                            notifyHeightThrottled();

                            if (isFinal) {
                              try {
                                if (codeOpen) {
                                  const codeText = (liveCode ? liveCode.textContent : '') + buffer;
                                  if (codeText) {
                                    const wrapper = document.createElement('div');
                                    wrapper.className = 'code-block-wrapper';
                                    const toolbar = document.createElement('div');
                                    toolbar.className = 'code-toolbar';
                                    const langLabel = document.createElement('span');
                                    langLabel.className = 'code-lang';
                                    langLabel.textContent = currentLang || 'text';
                                    const copyBtn = document.createElement('button');
                                    copyBtn.className = 'copy-btn';
                                    copyBtn.textContent = '复制';
                                    copyBtn.onclick = function() {
                                      try {
                                        if (navigator.clipboard && navigator.clipboard.writeText) {
                                          navigator.clipboard.writeText(codeText).then(function() {
                                            copyBtn.textContent = '已复制';
                                            copyBtn.classList.add('copied');
                                            setTimeout(function() {
                                              copyBtn.textContent = '复制';
                                              copyBtn.classList.remove('copied');
                                            }, 2000);
                                          });
                                        }
                                      } catch(e) {}
                                    };
                                    toolbar.appendChild(langLabel);
                                    toolbar.appendChild(copyBtn);
                                    wrapper.appendChild(toolbar);
                                    const pre = document.createElement('pre');
                                    const code = document.createElement('code');
                                    if (currentLang) code.className = 'language-' + currentLang;
                                    code.textContent = codeText;
                                    pre.appendChild(code);
                                    wrapper.appendChild(pre);
                                    staticC.appendChild(wrapper);
                                  }
                                  if (liveCode) liveCode.textContent = '';
                                  if (liveCodePre) liveCodePre.style.display = 'none';
                                  codeOpen = false;
                                  currentFenceChar = '';
                                  currentFenceLen = 0;
                                  currentLang = '';
                                  buffer = '';
                                } else if (buffer) {
                                  commitMarkdown(buffer);
                                  buffer = '';
                                }
                                tail.textContent = '';
                              } catch(_e){}
                              setTimeout(notifyHeight, 0);
                              setTimeout(notifyHeight, 120);
                              setTimeout(notifyHeight, 260);
                              try {
                                window._lastNotifiedH = 0;
                                var _rectH = (container.getBoundingClientRect && container.getBoundingClientRect().height) || 0;
                                var _cssPx = _rectH || container.scrollHeight || 0;
                                var _h = Math.ceil(_cssPx);
                                if (_h < 0) _h = 0;
                                if (_h > 120000) _h = 120000;
                                AndroidBridge.onHeight(_h);
                              } catch(_e){}
                            }
                          };
                        </script>
                        <script>
                          // 🔥 简化方案：让浏览器处理原生滚动，我们只做方向判断
                          document.addEventListener('DOMContentLoaded', function() {
                            // 为所有可滚动元素添加轻量级触摸处理
                            function setupNativeScroll(element) {
                              if (!element) return;
                              
                              let startX = 0, startY = 0;
                              let isHorizontalIntent = false;
                              
                              element.addEventListener('touchstart', function(e) {
                                if (element.scrollWidth <= element.clientWidth) return;
                                startX = e.touches[0].pageX;
                                startY = e.touches[0].pageY;
                                isHorizontalIntent = false;
                              }, { passive: true });
                              
                              element.addEventListener('touchmove', function(e) {
                                if (element.scrollWidth <= element.clientWidth) return;
                                
                                if (!isHorizontalIntent) {
                                  const deltaX = Math.abs(e.touches[0].pageX - startX);
                                  const deltaY = Math.abs(e.touches[0].pageY - startY);
                                  
                                  // 🔥 更早判断水平意图（与Android层一致）
                                  // 降低阈值，更积极地识别水平滚动
                                  if ((deltaX > 10 && deltaX > deltaY * 1.2) || (deltaX > 5 && deltaY < 3)) {
                                    isHorizontalIntent = true;
                                  }
                                }
                                
                                // 不阻止默认行为，让浏览器处理滚动
                                // 浏览器的原生滚动已经很流畅了
                              }, { passive: true });
                            }
                            
                            // 为所有可滚动元素设置
                            document.querySelectorAll('pre, table, .katex-display').forEach(setupNativeScroll);
                            setupNativeScroll(document.getElementById('liveCodePre'));
                            
                            // 监听新添加的元素
                            const observer = new MutationObserver(function(mutations) {
                              mutations.forEach(function(mutation) {
                                mutation.addedNodes.forEach(function(node) {
                                  if (node.nodeType === 1) {
                                    if (node.tagName === 'PRE' || node.tagName === 'TABLE' || 
                                        (node.classList && node.classList.contains('katex-display'))) {
                                      setupNativeScroll(node);
                                    }
                                    if (node.querySelectorAll) {
                                      node.querySelectorAll('pre, table, .katex-display').forEach(setupNativeScroll);
                                    }
                                  }
                                });
                              });
                            });
                            
                            observer.observe(document.getElementById('container'), {
                              childList: true,
                              subtree: true
                            });
                          });
                        </script>
                      </body>
                    </html>
                    """.trimIndent()
                    loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
            },
            update = { webView ->
                // 🎯 核心修复：移除300ms节流，StreamingBuffer已经做了节流（100ms/10字符）
                // 问题：update block的300ms节流会导致中间内容被跳过，用户看到的是跳跃式更新而不是流畅的流式输出
                // 修复：让WebView立即渲染StreamingBuffer flush的内容，保持流式输出的流畅性
                if (isPageLoaded.value) {
                    // 立即渲染，不做额外节流
                    // 🔍 [STREAM_DEBUG_ANDROID]
                    android.util.Log.i("STREAM_DEBUG", "[MarkdownHtmlView] ✅ Rendering: len=${markdown.length}, isStreaming=$isStreaming, isFinal=$isFinal, preview='${markdown.take(30)}'")
                    executeRender(webView, markdown, isFinal, isStreaming)
                    // 清除pending内容（已渲染）
                    pendingRenderContent.value = null
                } else {
                    // 🎯 关键修复：页面未加载完成时，缓存最新内容，等待加载完成后渲染
                    // 这样可以避免reasoning导致item重建时丢失content
                    android.util.Log.w("STREAM_DEBUG", "[MarkdownHtmlView] ⚠️ WebView not loaded yet, caching content, len=${markdown.length}")
                    pendingRenderContent.value = Triple(markdown, isFinal, isStreaming)
                }
            }
        )

        // 🎯 移除fallback文本和加载动画，避免闪烁
        // WebView 会直接渲染内容，不需要fallback
    }
}
