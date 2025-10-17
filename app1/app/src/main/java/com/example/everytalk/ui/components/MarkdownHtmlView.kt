
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
    // 记录“JS侧 last 的长度”，用它来计算真实应发送的增量，彻底消除重复内容
    val lastJsLen = remember { mutableStateOf(0) }
    val isFinalState = remember { mutableStateOf(false) }
    val isLoading = remember(markdown, isFinal) { mutableStateOf(true) }
    val isVisible = remember(markdown) { mutableStateOf(true) }
    // 新增：小增量批处理，避免因过滤小增量导致“非前缀重置”
    val pendingDelta = remember { mutableStateOf("") }
    val pendingFlushJob = remember { mutableStateOf<Job?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 引入增量渲染器
    val incRenderer = remember { mutableStateOf<IncrementalMarkdownRenderer?>(null) }

    LaunchedEffect(markdown, isFinal) {
        isFinalState.value = isFinal
        if (!isFinal) return@LaunchedEffect
        delay(3000)
        if (isLoading.value) {
            isLoading.value = false
            onRendered?.invoke(false)
        }
    }

    // 防止双通道重复派发：把实际派发集中在 AndroidView.update 内
    LaunchedEffect(isPageLoaded.value, markdown, isFinal, isStreaming) {
        if (!isPageLoaded.value) return@LaunchedEffect
        // 仅用于触发 Compose 重组，不在此处调用 evaluateJavascript
        android.util.Log.d("MdHtmlView", "LE: no-op (dispatch happens in AndroidView.update)")
    }
 
    val density = LocalDensity.current
    val webViewHeight = remember { mutableStateOf(50.dp) }
    val lastHeightPxState = remember { mutableStateOf(0) }
    // 回滚：恢复旧限高（与历史行为一致）
    val maxCapDp = 8000.dp

    LaunchedEffect(markdown) {
        // 保留高度复位，避免初始闪烁；不再清空 lastSentContent，保证前缀连续
        lastHeightPxState.value = 0
        webViewHeight.value = 50.dp
        // 不重置 lastJsLen，这样可确保严格“只追加”，防止重复
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
                .height(webViewHeight.value)
                .alpha(if (isVisible.value) 1f else 0f),
            factory = { ctx ->
                rememberedWebView.apply {
                    android.util.Log.i("MdHtmlView", "WebView factory created (MarkdownHtmlView) — using WebView for markdown")
                    webViewRef.value = this
                    
                    // 🔥 添加触摸事件监听器来处理水平滚动
                    var startX = 0f
                    var startY = 0f
                    setOnTouchListener { view, event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                startX = event.x
                                startY = event.y
                                // 初始不拦截，让 WebView 有机会处理
                                view.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val deltaX = kotlin.math.abs(event.x - startX)
                                val deltaY = kotlin.math.abs(event.y - startY)
                                
                                // 🔥 关键：检测水平滚动意图
                                // 如果水平移动明显大于垂直移动，请求父视图不拦截
                                if (deltaX > deltaY * 1.5f && deltaX > 15f) {
                                    android.util.Log.d("MdHtmlView", "Horizontal scroll detected, requesting parent not to intercept")
                                    view.parent?.requestDisallowInterceptTouchEvent(true)
                                }
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                view.parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                        false // 返回 false 让 WebView 自己处理事件
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
                        }

                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                if (request?.isForMainFrame == true) {
                                    Log.e("WebViewError", "Error: ${error?.description} on URL: ${request?.url}")
                                    isLoading.value = false
                                    onRendered?.invoke(false)
                                }
                            }
                            super.onReceivedError(view, request, error)
                        }

                        @Suppress("OverridingDeprecatedMember")
                        override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                                Log.e("WebViewError", "Error: $description on URL: $failingUrl")
                                isLoading.value = false
                                onRendered?.invoke(false)
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
                                    val minDeltaStream = 200
                                    val minDeltaFinalPx = 24
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
                          
                          table { display:block; max-width:100%; overflow-x:auto; -webkit-overflow-scrolling:touch; border-collapse:collapse; touch-action: pan-x pan-y; }
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
                          function notifyHeightThrottled() {
                            if (_nhTimer) return;
                            let streaming = !!window._isStreaming;
                            const throttle = streaming ? 700 : 250; // 调高节流以减少回传频率
                            _nhTimer = setTimeout(function(){
                              _nhTimer = null;
                              try {
                                var rectH = (container.getBoundingClientRect && container.getBoundingClientRect().height) || 0;
                                var cssPx = rectH || container.scrollHeight || 0;
                                var h = Math.ceil(cssPx);
                                if (h < 0) h = 0;
                                if (h > 120000) h = 120000;
                                window._lastNotifiedH = window._lastNotifiedH || 0;
                                const minDelta = streaming ? 240 : 24; // 提升最小高度变更阈值
                                if (Math.abs(h - window._lastNotifiedH) >= minDelta) {
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
                         // Find safe commit point: paragraph break outside code blocks
                         function findSafeIndex(text) {
                           let i = 0;
                           let safe = -1;
                           let localOpen = codeOpen;
                           
                           // 🔥 修复：在流式模式下，如果当前在代码块内，不要提交任何内容
                           // 直到代码块完全闭合，避免代码块被分割
                           if (window._isStreaming && localOpen) {
                             return -1; // 强制等待代码块完成
                           }
                           
                           while (i < text.length) {
                             // Check for fence toggle
                             const fenceInfo = localOpen
                               ? findClosingFence(text.slice(i), currentFenceChar, currentFenceLen)
                               : findOpeningFence(text.slice(i));
                             const paraIdx = text.indexOf('\n\n', i);
                             
                             if (fenceInfo && (paraIdx === -1 || fenceInfo.idx + i < paraIdx)) {
                               localOpen = !localOpen;
                               i += fenceInfo.idx + (fenceInfo.len || 3) + 1;
                               
                               // 🔥 修复：如果刚刚闭合了一个代码块，这是一个安全的提交点
                               if (!localOpen && window._isStreaming) {
                                 safe = i;
                               }
                             } else if (paraIdx !== -1) {
                               if (!localOpen) safe = paraIdx + 2;
                               i = paraIdx + 2;
                             } else {
                               break;
                             }
                           }
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
                               let j = open.idx + open.len;
                               while (j < buffer.length && buffer[j] === ' ') j++;
                               const nextNl = buffer.indexOf('\n', j);
                               buffer = nextNl === -1 ? '' : buffer.slice(nextNl + 1);
                               codeOpen = true;
                               if (liveCodePre) liveCodePre.style.display = 'block';
                               
                               // 🔥 修复：进入代码块时，清空实时预览区域
                               if (liveCode) liveCode.textContent = '';
                             }
                           }
                           
                           if (codeOpen) {
                             const close = findClosingFence(buffer, currentFenceChar, currentFenceLen);
                             if (close) {
                               // Complete code block found - render it
                               const codeText = (liveCode ? liveCode.textContent : '') + buffer.slice(0, close.idx);
                               try {
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
                               } catch(_e){}
                               
                               // 🔥 修复：代码块完成后，清理状态并隐藏实时预览
                               if (liveCode) liveCode.textContent = '';
                               if (liveCodePre) liveCodePre.style.display = 'none';
                               
                               let j = close.idx + close.len;
                               while (j < buffer.length && buffer[j] === ' ') j++;
                               const nextNl = buffer.indexOf('\n', j);
                               buffer = nextNl === -1 ? '' : buffer.slice(nextNl + 1);
                               codeOpen = false;
                               currentFenceChar = '';
                               currentFenceLen = 0;
                               currentLang = '';
                               prevCodeOpen = false;
                             } else if (window._isStreaming) {
                               // 🔥 修复：流式模式下，将代码内容累积到实时预览区域
                               // 但不要清空buffer，保持代码块的连续性
                               const newCodeContent = buffer;
                               if (liveCode) {
                                 // 只更新实时预览，不清空buffer
                                 liveCode.textContent = newCodeContent;
                                 if (liveCodePre) liveCodePre.style.display = 'block';
                               }
                               // 🔥 关键修复：不要清空buffer！保持代码块内容的连续性
                               // buffer = ''; // 移除这行，避免代码块内容丢失
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
                            if (newContent && newContent.indexOf(last) === 0) {
                              // 前缀正常增长
                              delta = newContent.slice(last.length);
                              buffer += delta;
                              last = newContent || '';
                            } else {
                              // 非前缀：在流式模式尝试“最长公共前缀(LCP)”缓解，尽量避免从头重建
                              let handled = false;
                              try {
                                const oldStr = last || '';
                                const newStr = newContent || '';
                                if (window._isStreaming && oldStr && newStr) {
                                  let i = 0;
                                  const maxLcp = Math.min(oldStr.length, newStr.length);
                                  while (i < maxLcp && oldStr.charCodeAt(i) === newStr.charCodeAt(i)) i++;
                                  const keepRatio = oldStr.length ? (i / oldStr.length) : 0;
                                  // 当 LCP 覆盖原文本 ≥70%，保留已提交内容，仅把差异部分作为增量追加
                                  if (keepRatio >= 0.7) {
                                    const appendPart = newStr.slice(i);
                                    try { console.warn('non-prefix-lcp-append', { prevLen: oldStr.length, newLen: newStr.length, lcp: i, keepRatio }); } catch(_e){}
                                    buffer += appendPart;
                                    last = newStr;
                                    handled = true;
                                  }
                                }
                              } catch(_e) {}
                              if (!handled) {
                                // 回退：仍执行从头重建（完成态或 LCP 不足）
                                try { console.warn('non-prefix-reset', { prevLen: (last || '').length, newLen: (newContent || '').length }); } catch(_e){}
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
                            } else if (codeOpen && liveCode && !isFinal) {
                              // 🔥 修复：流式模式下，保持代码内容的连续性
                              // 直接更新实时预览，不要分割buffer
                              if (buffer.length > 0) {
                                liveCode.textContent = buffer;
                                if (liveCodePre) liveCodePre.style.display = 'block';
                                // 🔥 关键修复：不要修改buffer，保持完整的代码块内容
                                // 让buffer保持完整，直到代码块闭合
                              }
                            }
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
                            } else if (codeOpen && liveCode && !isFinal) {
                              // 围栏未闭合：实时预览代码内容，但不清空 buffer，保持连续性
                              if (buffer.length > 0) {
                                liveCode.textContent = buffer;
                                if (liveCodePre) liveCodePre.style.display = 'block';
                              }
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
                          // 🔥 关键修复：为所有可滚动元素添加触摸事件处理
                          document.addEventListener('DOMContentLoaded', function() {
                            function makeScrollable(element) {
                              if (!element) return;
                              
                              let startX = 0;
                              let startY = 0;
                              let scrollLeft = 0;
                              let isHorizontalScroll = false;
                              
                              element.addEventListener('touchstart', function(e) {
                                if (element.scrollWidth <= element.clientWidth) return;
                                startX = e.touches[0].pageX;
                                startY = e.touches[0].pageY;
                                scrollLeft = element.scrollLeft;
                                isHorizontalScroll = false;
                              }, { passive: true });
                              
                              element.addEventListener('touchmove', function(e) {
                                if (element.scrollWidth <= element.clientWidth) return;
                                
                                const x = e.touches[0].pageX;
                                const y = e.touches[0].pageY;
                                const deltaX = Math.abs(x - startX);
                                const deltaY = Math.abs(y - startY);
                                
                                // 🔥 只有在明确的水平滑动时才处理
                                if (!isHorizontalScroll && deltaX > 10 && deltaX > deltaY * 1.5) {
                                  isHorizontalScroll = true;
                                }
                                
                                if (isHorizontalScroll) {
                                  const walk = (startX - x);
                                  element.scrollLeft = scrollLeft + walk;
                                  // 🔥 阻止默认行为和事件冒泡
                                  e.preventDefault();
                                  e.stopPropagation();
                                }
                              }, { passive: false });
                              
                              element.addEventListener('touchend', function() {
                                isHorizontalScroll = false;
                              }, { passive: true });
                            }
                            
                            // 为所有pre元素添加滚动支持
                            document.querySelectorAll('pre').forEach(makeScrollable);
                            makeScrollable(document.getElementById('liveCodePre'));
                            
                            // 监听DOM变化，为新添加的pre元素添加滚动支持
                            const observer = new MutationObserver(function(mutations) {
                              mutations.forEach(function(mutation) {
                                mutation.addedNodes.forEach(function(node) {
                                  if (node.nodeType === 1) {
                                    if (node.tagName === 'PRE') {
                                      makeScrollable(node);
                                    }
                                    node.querySelectorAll && node.querySelectorAll('pre').forEach(makeScrollable);
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
                if (isPageLoaded.value) {
                    // 回滚：恢复旧的转义方案（包含反引号转义）
                    val escapedContent = markdown
                        .replace("\\", "\\\\")
                        .replace("`", "\\`")
                        .replace("'", "\\'")
                        .replace("\n", "\\n")

                    val shouldSend = lastSentContent.value != escapedContent || isFinal
                    if (shouldSend) {
                        val last = lastSentContent.value ?: ""
                        var skipRest = false
                        if (isStreaming && escapedContent.startsWith(last) && !isFinal) {
                            val deltaLen = escapedContent.length - last.length
                            val deltaStr = escapedContent.substring(last.length)
                            if (deltaLen in 1..2) {
                                // 合并到 pending，由上面的 LaunchedEffect 定时器冲刷
                                pendingDelta.value = pendingDelta.value + deltaStr
                                isLoading.value = false
                                skipRest = true
                            } else {
                                // 冲刷 pending
                                if (pendingDelta.value.isNotEmpty()) {
                                    val batched = pendingDelta.value
                                    webView.evaluateJavascript(
                                        "appendDelta('${batched.replace("\\", "\\\\").replace("`", "\\`").replace("'", "\\'").replace("\n", "\\n")}', false, true)"
                                    ) { }
                                    lastSentContent.value = (last + batched)
                                    pendingDelta.value = ""
                                    pendingFlushJob.value?.cancel()
                                    pendingFlushJob.value = null
                                }
                            }
                        }
                        if (!skipRest) {
                            val newLast = lastSentContent.value ?: ""
                            if (escapedContent.startsWith(newLast)) {
                                val delta = escapedContent.substring(newLast.length)
                                incRenderer.value?.appendDelta(
                                    escapedDelta = delta,
                                    isFinal = isFinal,
                                    isStreaming = isStreaming
                                )
                            } else {
                                incRenderer.value?.updateMarkdown(
                                    fullEscapedContent = escapedContent,
                                    isFinal = isFinal,
                                    isStreaming = isStreaming
                                )
                            }
                            // 直接更新发送记录与加载状态（回调为可选）
                            lastSentContent.value = escapedContent
                            if (isFinal) {
                                isLoading.value = false
                                onRendered?.invoke(true)
                            } else {
                                isLoading.value = false
                            }
                        }
                    }
                }
            }
        )

        if (!isLoading.value && isFinalState.value && lastHeightPxState.value <= 0) {
            androidx.compose.material3.Text(
                text = markdown,
                modifier = androidx.compose.ui.Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(8.dp),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        if (isLoading.value) {
            CircularProgressIndicator(
                modifier = androidx.compose.ui.Modifier
                    .align(Alignment.Center)
                    .zIndex(1f)
                    .size(24.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
