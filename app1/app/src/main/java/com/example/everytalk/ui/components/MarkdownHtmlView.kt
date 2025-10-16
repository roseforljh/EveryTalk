package com.example.everytalk.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.alpha

/**
 * 使用 CDN 的 Markdown + 代码高亮 + KaTeX 自动渲染（无需本地资源）。
 * - Markdown: marked
 * - Code highlight: highlight.js (github 风格)
 * - Math: KaTeX auto-render（支持 $...$ 与 $$...$$）
 *
 * 注意：依赖网络；离线将回退为原文 pre 文本显示。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MarkdownHtmlView(
    markdown: String,
    modifier: Modifier = Modifier,
    onRendered: ((ok: Boolean) -> Unit)? = null
) {
    val context = LocalContext.current

    // 变化时重载
    val contentKey = remember(markdown) { markdown }
    // 历史页进入时给出加载指示，待 WebView 完成后关闭
    val isLoading = remember(contentKey) { mutableStateOf(false) }
    val isVisible = remember(contentKey) { mutableStateOf(true) } // 默认可见
    
    // 添加超时机制，确保加载指示器不会一直显示
    LaunchedEffect(contentKey) {
        delay(3000) // 3秒超时
        if (isLoading.value) {
            isLoading.value = false
            onRendered?.invoke(false) // 超时回调，表示可能加载失败
        }
    }
 
    val webViewHeight = remember { mutableStateOf(50.dp) } // Default minimum height
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(webViewHeight.value)
    ) {
        AndroidView(
            modifier = androidx.compose.ui.Modifier
                .matchParentSize()
                .alpha(if (isVisible.value) 1f else 0f),
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT // Height fills the Box
                    )
                    settings.javaScriptEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.domStorageEnabled = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.setSupportZoom(false)
                    isHorizontalScrollBarEnabled = true
                    isVerticalScrollBarEnabled = true // Allow internal vertical scrolling
                    settings.textZoom = 100
                    setBackgroundColor(Color.TRANSPARENT)

                    // Enable debugging and capture console logs
                    WebView.setWebContentsDebuggingEnabled(true)
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                            Log.e( // Use Log.e to ensure it's not filtered out
                                "WebViewConsole",
                                "[${consoleMessage.messageLevel()}] ${consoleMessage.message()} -- From line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}"
                            )
                            return true
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            super.onPageFinished(view, url)
                            view.post {
                                view.evaluateJavascript("document.body.scrollHeight") { height ->
                                    val contentHeight = (height.toFloat() * view.resources.displayMetrics.density)
                                    if (contentHeight > 0) {
                                        webViewHeight.value = with(density) { contentHeight.toDp() }
                                    }
                                    isLoading.value = false
                                    onRendered?.invoke(true)
                                }
                            }
                        }
                        
                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?
                        ) {
                            super.onReceivedError(view, errorCode, description, failingUrl)
                            isLoading.value = false
                            onRendered?.invoke(false)
                        }
                    }
                }
            },
            update = { webView ->
                fun jsEscape(s: String): String {
                    return s
                        .replace("\\", "\\\\")
                        .replace("`", "\\`")
                        .replace("$", "\\$")
                }

                val escapedMd = jsEscape(contentKey)
                isLoading.value = true

                val html = """
                <!doctype html>
                <html lang="zh-CN">
                  <head>
                    <meta charset="utf-8"/>
                    <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1"/>
                    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.25/dist/katex.min.css">
                    <style>
                      html,body{ margin:0; padding:0; background:transparent; width:100%; box-sizing:border-box; }
                      body{ color:#E6E6E6; font-size: 16px; line-height: 1.6; }
                      #container { padding: 8px; }
                      .katex-display {
                        overflow-x: auto;
                        -webkit-overflow-scrolling: touch;
                      }
                      /* Default code style */
                      code {
                        font-family: monospace;
                      }
                      /* Code block style */
                      pre {
                        background-color: #282c34; /* Dark background for the block */
                        padding: 16px;
                        border-radius: 8px;
                        overflow-x: auto;
                        -webkit-overflow-scrolling: touch;
                        white-space: pre;
                      }
                      /* Reset inline style for code inside a block */
                      pre > code {
                        background-color: transparent;
                        padding: 0;
                      }
                    </style>
                  </head>
                  <body>
                    <div id="container"></div>
                    <script src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script>
                    <script src="https://cdn.jsdelivr.net/npm/katex@0.16.25/dist/katex.min.js"></script>
                    <script src="https://cdn.jsdelivr.net/npm/katex@0.16.25/dist/contrib/auto-render.min.js"></script>
                    <script>
                      document.addEventListener('DOMContentLoaded', function () {
                          const md = `${escapedMd}`;
                          const container = document.getElementById('container');
                          
                          // Pre-render block-level math ($$ ... $$) before Marked touches it
                          // This prevents Marked from corrupting complex LaTeX syntax
                          const processedMd = md.replace(/\$\$([\s\S]*?)\$\$/g, (match, math) => {
                              try {
                                  return katex.renderToString(math, {
                                      displayMode: true,
                                      throwOnError: false
                                  });
                              } catch (e) {
                                  console.error("KaTeX block rendering error:", e);
                                  return match; // Fallback to original on error
                              }
                          });

                          // Parse the Markdown (with block math already rendered as HTML)
                          container.innerHTML = marked.parse(processedMd);

                          // Render inline math ($...$) and any block math that failed pre-rendering
                          renderMathInElement(container, {
                              delimiters: [
                                  { left: '$$', right: '$$', display: true },
                                  { left: '$', right: '$', display: false }
                              ],
                              throwOnError: false,
                              ignoredClasses: ['katex'] // Don't re-render our pre-rendered math
                          });
                      });
                    </script>
                  </body>
                </html>
                """.trimIndent()

                // 使用loadData而不是loadDataWithBaseURL，避免一些潜在的问题
                webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        )

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