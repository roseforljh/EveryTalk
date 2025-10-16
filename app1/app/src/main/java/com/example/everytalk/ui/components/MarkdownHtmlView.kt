package com.example.everytalk.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.Log
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
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.alpha
import kotlin.math.abs

/**
 * 使用 CDN 的 Markdown + KaTeX 自动渲染（无需本地资源）。
 * - Markdown: marked
 * - Math: KaTeX auto-render（支持 $...$ 与 $$...$$）
 *
 * 注意：依赖网络；离线将回退为原文 pre 文本显示。
 */
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
    // 稳定 WebView 实例，避免重组期间重建导致的闪烁
    val rememberedWebView = remember { WebView(context) }
    // 发送到页面的最后一次内容，避免无效 evaluateJavascript 触发布局抖动
    val lastSentContent = remember { mutableStateOf<String?>(null) }
    // 跟踪是否处于最终态，用于允许高度收缩
    val isFinalState = remember { mutableStateOf(false) }

    // 历史页进入时给出加载指示，待 WebView 完成后关闭
    val isLoading = remember(markdown, isFinal) { mutableStateOf(true) }
    val isVisible = remember(markdown) { mutableStateOf(true) } // 默认可见

    // 添加超时机制，确保加载指示器不会一直显示
    LaunchedEffect(markdown, isFinal) {
        // 同步最终态标记
        isFinalState.value = isFinal
        if (!isFinal) return@LaunchedEffect
        delay(3000) // 3秒超时
        if (isLoading.value) {
            isLoading.value = false
            onRendered?.invoke(false) // 超时回调，表示可能加载失败
        }
    }

    // 确保在页面加载完成的那一刻也能立刻推送内容（避免只剩“空气泡”）
    LaunchedEffect(isPageLoaded.value, markdown, isFinal, isStreaming) {
        if (!isPageLoaded.value) return@LaunchedEffect
        val webView = webViewRef.value ?: return@LaunchedEffect
        val escapedContent = markdown
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("'", "\\'")
            .replace("\n", "\\n")
        // 与 update 保持一致的去重逻辑
        val shouldSend = lastSentContent.value != escapedContent || isFinal
        if (shouldSend) {
            webView.evaluateJavascript(
                "updateMarkdown('$escapedContent', ${if (isFinal) "true" else "false"}, ${if (isStreaming) "true" else "false"})",
                null
            )
            lastSentContent.value = escapedContent
            isLoading.value = false
            if (isFinal) onRendered?.invoke(true)
        }
    }
 
    // WebView 高度由 JS 回传内容高度驱动；只增长不回退，减少重排抖动
    val density = LocalDensity.current
    val webViewHeight = remember { mutableStateOf(50.dp) }
    val lastHeightPxState = remember { mutableStateOf(0) }
    // 统一安全上限，避免异常高度导致 Compose 约束崩溃
    val maxCapDp = 8000.dp

    // 每次 markdown 源内容变化时重置高度与缓存，避免沿用上一条消息的最大高度造成“尾部大空白”
    LaunchedEffect(markdown) {
        lastHeightPxState.value = 0
        webViewHeight.value = 50.dp
        lastSentContent.value = null
    }
 
    Box(
        modifier = modifier
            .fillMaxWidth()
            // 统一用 heightIn，并设置 max，上限兜底，彻底避免高度爆炸
            .then(
                if (!isPageLoaded.value) {
                    Modifier.heightIn(min = 50.dp, max = maxCapDp)
                } else {
                    // 在最终态也使用测得内容高度，且设置下限 24dp，避免 0 高度“空气泡”
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
                // 使用稳定实例，防止每次重组创建新的 WebView
                rememberedWebView.apply {
                    webViewRef.value = this
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.domStorageEnabled = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.setSupportZoom(false)
                    isHorizontalScrollBarEnabled = true
                    isVerticalScrollBarEnabled = true
                    settings.textZoom = 100
                    setBackgroundColor(Color.TRANSPARENT)

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

                        // For APIs >= 23
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

                        // For APIs < 23
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
 
                    // Bridge to receive content height from JS and update Compose height (monotonic growth)
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onHeight(px: Int) {
                            try {
                                val raw = if (px < 0) 0 else px
                                // 保护：忽略异常高度，并进行钳制，避免Compose约束溢出
                                val p = raw.coerceIn(0, 120_000)
                                // Ensure updates on UI thread
                                this@apply.post {
                                    val lastPx = lastHeightPxState.value
                                    val finalMode = isFinalState.value
                                    // 流式：仅在足够增长时更新；最终：允许增减（收缩）
                                    val minDeltaStream = 120
                                    val minDeltaFinal = 0
                                    if (!finalMode) {
                                        if (p >= lastPx + minDeltaStream) {
                                            lastHeightPxState.value = p
                                            // p 为 WebView CSS 像素（≈dp），不要再按 px→dp 转换，直接用 dp
                                            webViewHeight.value = p.dp
                                        }
                                    } else {
                                        // Final 模式：无阈值收缩；保证最小高度>=24dp，防止“空气泡”
                                        val minFinal = 24
                                        val accepted = (lastPx == 0 && p > 0) || (p != lastPx)
                                        if (accepted) {
                                            val finalPx = if (p < minFinal) minFinal else p
                                            lastHeightPxState.value = finalPx
                                            webViewHeight.value = finalPx.dp
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
                          /* 流式尾部使用透明容器，不再使用带背景的 pre */
                          #tail { white-space: pre-wrap; word-break: break-word; font-family: inherit; background: transparent; padding: 0; margin: 0; }
                          .katex-display { overflow-x: auto; -webkit-overflow-scrolling: touch; }
                          /* 保留最终渲染代码块的深色样式（不影响 #tail） */
                          pre { margin: 1em 0; padding: 16px; overflow-x: auto; -webkit-overflow-scrolling: touch; white-space: pre; background-color: #282c34; border-radius: 8px; }
                          pre > code { font-family: monospace; background-color: transparent; padding: 0; }
                          .katex { color: inherit; }
                          /* 围栏内行级流式容器样式（与最终 pre 风格一致） */
                          #liveCodePre { display:none; margin: 1em 0; padding:16px; overflow-x:auto; background-color:#282c34; border-radius:8px; }
                          #live-code { white-space: pre-wrap; word-break: break-word; font-family: monospace; }
                          /* Responsive tables and media to prevent horizontal overflow */
                          table { display:block; max-width:100%; overflow-x:auto; -webkit-overflow-scrolling:touch; border-collapse:collapse; }
                          thead, tbody, tr, th, td { box-sizing:border-box; }
                          th, td { word-break:break-word; white-space:normal; padding:8px; border:1px solid rgba(255,255,255,0.12); }
                          td pre, td code { white-space:pre-wrap; word-break:break-word; }
                          img, video, canvas, svg { max-width:100%; height:auto; }
                          a, code, kbd, samp { word-break:break-word; overflow-wrap:anywhere; }
                          /* fine tune math block alignment */
                          .katex-display { text-align:left; }
                        </style>
                      </head>
                      <body>
                        <div id="container">
                          <div id="static"></div>
                          <!-- 围栏内行级流式代码容器（未闭合前行级追加） -->
                          <pre id="liveCodePre"><code id="live-code"></code></pre>
                          <!-- 改为 div，避免继承 pre 的灰色背景 -->
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
                          
                          // DOM变更/图片加载监听：任何内容变化都触发高度更新，解决终态尾部留白
                          try {
                            const obs = new MutationObserver(function() {
                              notifyHeightThrottled();
                            });
                            obs.observe(container, { childList: true, subtree: true, characterData: true });
                            window.addEventListener('resize', notifyHeightThrottled);
                            window.addEventListener('load', notifyHeightThrottled);
                          } catch(_e) {}
                          
                          // Throttled height notifier to Android (measure content height in physical px)
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
                            const throttle = streaming ? 360 : 150; // 流式期更强节流
                            _nhTimer = setTimeout(function(){
                              _nhTimer = null;
                              try {
                                var rectH = (container.getBoundingClientRect && container.getBoundingClientRect().height) || 0;
                                var cssPx = rectH || container.scrollHeight || 0;
                                var h = Math.ceil(cssPx);
                                if (h < 0) h = 0;
                                if (h > 120000) h = 120000;
                                // 仅在达到更大增量时上报（流式更高阈值；终态不设阈值）
                                window._lastNotifiedH = window._lastNotifiedH || 0;
                                const minDelta = streaming ? 160 : 0;
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
                            tmp = tmp.replace(/\${'$'}\${'$'}([\s\S]*?)\${'$'}\${'$'}/g, function(_m, f) { mathStore.push({ formula: f, left: '${'$'}${'$'}', right: '${'$'}${'$'}' }); return placeholder(mathStore.length - 1); });
                            tmp = tmp.replace(/\${'$'}([^\${'$'}\n]+?)\${'$'}/g, function(_m, f) { mathStore.push({ formula: f, left: '${'$'}', right: '${'$'}' }); return placeholder(mathStore.length - 1); });
                            return { tmp, mathStore };
                          }
                          function restoreMath(html, mathStore) {
                            return html.replace(/\[\[MATH_PLACEHOLDER_(\d+)\]\]/g, function(_m, i) {
                              const item = mathStore[parseInt(i, 10)];
                              if (!item) return _m;
                              const left = item.left || (item.display ? '${'$'}${'$'}' : '${'$'}');
                              const right = item.right || (item.display ? '${'$'}${'$'}' : '${'$'}');
                              return left + item.formula + right;
                            });
                          }

                          let last = '';
                          let buffer = '';
                          let codeOpen = false;
                          let prevCodeOpen = false;

                          function findSafeIndex(text) {
                            let i = 0;
                            let safe = -1;
                            while (i < text.length) {
                              const nextFence = text.indexOf('```', i);
                              const nextPara = text.indexOf('\n\n', i);
                              const hasFence = nextFence !== -1;
                              const hasPara = nextPara !== -1;
                              if (!hasFence && !hasPara) break;
                              if (hasFence && (!hasPara || nextFence < nextPara)) {
                                codeOpen = !codeOpen;
                                i = nextFence + 3;
                              } else {
                                if (!codeOpen) safe = nextPara + 2;
                                i = nextPara + 2;
                              }
                            }
                            return safe;
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
                                  { left: '${'$'}${'$'}', right: '${'$'}${'$'}', display: true },
                                  { left: '${'$'}', right: '${'$'}', display: false }
                                ],
                                throwOnError: false,
                                ignoredTags: ['pre','code'],
                                ignoredClasses: ['katex']
                              });
                            } catch(_e){}
                            
                            // 在追加前给图片绑定 load/error 事件，渲染完成后再次上报高度
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
                            // 将流式状态保存为全局，供高度上报节流策略使用
                            window._isStreaming = !!isStreaming;
                            try { configureMarked(); } catch(_e){}
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
                              delta = newContent.slice(last.length);
                            } else {
                              staticC.innerHTML = '';
                              tail.textContent = '';
                              // 清空并隐藏实时代码容器
                              if (liveCode) { liveCode.textContent = ''; }
                              if (liveCodePre) { liveCodePre.style.display = 'none'; }
                              buffer = '';
                              codeOpen = false;
                              last = '';
                              delta = newContent || '';
                            }
                            buffer += delta;
                            last = newContent || '';

                            let safeIdx = findSafeIndex(buffer);
                            if (isFinal) safeIdx = buffer.length;

                            // 围栏开闭状态切换检测（用于在闭合时合并 live-code 到静态区）
                            if (!codeOpen && prevCodeOpen) {
                              // 围栏刚刚闭合：把 liveCode 中的行与 buffer 一起固化到静态区域
                              try {
                                if (liveCode && liveCode.textContent.length > 0) {
                                  const pre = document.createElement('pre');
                                  const code = document.createElement('code');
                                  code.textContent = liveCode.textContent + buffer;
                                  pre.appendChild(code);
                                  staticC.appendChild(pre);
                                  liveCode.textContent = '';
                                  if (liveCodePre) liveCodePre.style.display = 'none';
                                  buffer = '';
                                }
                              } catch(_e){}
                            }
                            prevCodeOpen = codeOpen;

                            const wasPinned = true;

                            if (safeIdx > 0) {
                              const safe = buffer.slice(0, safeIdx);
                              commitMarkdown(safe);
                              buffer = buffer.slice(safeIdx);
                              tail.textContent = buffer;
                            } else {
                              // 不存在安全块：若处于代码围栏内，按“行级”把完整行追加到 live-code
                              if (codeOpen && liveCode) {
                                const lastNl = buffer.lastIndexOf('\\n');
                                if (lastNl >= 0) {
                                  const complete = buffer.slice(0, lastNl + 1);
                                  liveCode.textContent += complete;
                                  if (liveCodePre) liveCodePre.style.display = 'block';
                                  buffer = buffer.slice(lastNl + 1);
                                }
                              }
                              tail.textContent = buffer;
                            }
                            notifyHeightThrottled();
                            if (isFinal) {
                              // 终态多次确认高度，避免图片/公式异步渲染后仍保留旧高度造成“气泡下方大空白”
                              setTimeout(notifyHeight, 0);
                              setTimeout(notifyHeight, 120);
                              setTimeout(notifyHeight, 260);
                              // 终态强制一次非节流、非阈值的容器实高上报
                              try {
                                window._lastNotifiedH = 0;
                                var _rectH = (container.getBoundingClientRect && container.getBoundingClientRect().height) || 0;
                                var _cssPx = _rectH || container.scrollHeight || 0;
                                var _h = Math.ceil(_cssPx);
                                if (_h < 0) _h = 0;
                                if (_h > 120000) _h = 120000;
                                AndroidBridge.onHeight(_h);
                              } catch(_e){}
                              // 终态清空尾部缓冲，避免 #tail 继续占位
                              buffer = '';
                              tail.textContent = '';
                            }
                          };
                        </script>
                      </body>
                    </html>
                    """.trimIndent()
                    loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
            },
            update = { webView ->
                if (isPageLoaded.value) {
                    val escapedContent = markdown
                        .replace("\\", "\\\\")
                        .replace("`", "\\`")
                        .replace("'", "\\'")
                        .replace("\n", "\\n")

                    // 内容未变化且非最终态则跳过，避免无效 JS 调用触发布局抖动
                    val shouldSend = lastSentContent.value != escapedContent || isFinal
                    if (shouldSend) {
                        webView.evaluateJavascript(
                            "updateMarkdown('$escapedContent', ${if (isFinal) "true" else "false"}, ${if (isStreaming) "true" else "false"})"
                        ) {
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

        // 渲染兜底：若已结束加载但未收到有效高度，直接用纯文本回退，避免“气泡空白”
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

// No longer needed