package com.example.everytalk.ui.components

import android.annotation.SuppressLint
import android.util.Log
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.ConcurrentHashMap

// Cache for pre-rendered KaTeX HTML to avoid re-rendering
private val htmlCache = ConcurrentHashMap<String, String>()

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MathView(
    latex: String,
    isDisplay: Boolean,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var webViewHeight by remember { mutableStateOf(if (isDisplay) 80.dp else 60.dp) }
    val cacheKey = "$latex-$isDisplay-${textColor.value}"

    // Generate the HTML content for KaTeX
    val htmlContent = remember(cacheKey) {
        htmlCache.getOrPut(cacheKey) {
            val displayStyle = if (isDisplay) "block" else "inline"
            val hexColor = String.format(
                "#%02x%02x%02x",
                (textColor.red * 255).toInt(),
                (textColor.green * 255).toInt(),
                (textColor.blue * 255).toInt()
            )

            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <link rel="stylesheet" href="file:///android_asset/katex.min.css">
                <script src="file:///android_asset/katex.min.js"></script>
                <style>
                    * {
                        margin: 0;
                        padding: 0;
                        box-sizing: border-box;
                        /* 禁用文本选择 */
                        -webkit-user-select: none;
                        -moz-user-select: none;
                        -ms-user-select: none;
                        user-select: none;
                        /* 禁用长按选择 */
                        -webkit-touch-callout: none;
                        -webkit-tap-highlight-color: transparent;
                    }
                    html, body {
                        width: 100%;
                        height: 100%;
                        overflow-x: auto;
                        overflow-y: hidden;
                        background-color: transparent;
                        /* 禁用文本选择 */
                        -webkit-user-select: none;
                        -moz-user-select: none;
                        -ms-user-select: none;
                        user-select: none;
                    }
                    body {
                        padding: 2px 8px;
                        font-size: 16px;
                        color: $hexColor;
                        display: flex;
                        align-items: center;
                        width: max-content; /* Let body be as wide as its content */
                        min-width: 100%; /* But at least as wide as the screen */
                        min-height: 30px;
                    }
                    #math {
                        display: inline-block; /* Keep it simple */
                    }
                    .katex {
                        font-size: 1em;
                        display: inline-block;
                        line-height: 1.4;
                        vertical-align: middle;
                        max-width: none;
                        width: auto;
                        min-height: 20px;
                        padding: 0;
                        margin: 0;
                    }
                    .katex-display {
                        margin: 2px 0;
                        display: inline-block;
                        font-size: 1.1em;
                        line-height: 1.6;
                        vertical-align: middle;
                        max-width: none;
                        width: auto;
                        min-height: 24px;
                        padding: 0;
                    }
                </style>
            </head>
            <body>
                <div id="math"></div>
                <script>
                    try {
                        katex.render(`$latex`.replace(/\\\\/g, "\\\\"), document.getElementById('math'), {
                            throwOnError: false,
                            displayMode: $isDisplay
                        });
                        
                        // Wait for rendering to complete, then adjust dimensions
                        // Use multiple measurement attempts for accurate height calculation
                        setTimeout(() => {
                            const mathElement = document.getElementById('math');

                            // Force layout recalculation
                            mathElement.style.display = 'none';
                            mathElement.offsetHeight; // trigger reflow
                            mathElement.style.display = 'inline-block';

                            // Multiple measurement methods for accuracy
                            const bodyHeight = document.body.scrollHeight;
                            const mathHeight = mathElement.offsetHeight;
                            const mathBoundingHeight = mathElement.getBoundingClientRect().height;
                            const documentHeight = document.documentElement.scrollHeight;

                            // Use the maximum of all measurements with minimal padding
                            const maxMeasuredHeight = Math.max(bodyHeight, mathHeight, mathBoundingHeight, documentHeight);
                            const totalHeight = Math.max(maxMeasuredHeight + 4, 30);

                            if (window.android) {
                                window.android.updateHeight(totalHeight);
                            }
                            console.log('Math rendered - Body:', bodyHeight, 'Math:', mathHeight, 'Bounding:', mathBoundingHeight, 'Doc:', documentHeight, 'Final:', totalHeight);
                        }, 200);
                    } catch (e) {
                        console.error('KaTeX render error:', e);
                        document.getElementById('math').innerText = `Render Error: ${'$'}{e.message || e.toString()}`;
                        if (window.android) {
                            window.android.updateHeight(200);
                        }
                    }
                </script>
            </body>
            </html>
            """.trimIndent()
        }
    }

    BoxWithConstraints(modifier = modifier) {
        AndroidView(
            factory = {
                val webView = object : WebView(it) {
                    override fun onTouchEvent(event: MotionEvent?): Boolean {
                        // 禁用长按事件，让其传递到父级组件
                        when (event?.action) {
                            MotionEvent.ACTION_DOWN -> {
                                // 允许按下事件，但不消费长按
                                return super.onTouchEvent(event)
                            }
                            MotionEvent.ACTION_MOVE -> {
                                // 允许移动事件用于水平滚动
                                return super.onTouchEvent(event)
                            }
                            MotionEvent.ACTION_UP -> {
                                // 允许抬起事件
                                return super.onTouchEvent(event)
                            }
                            else -> {
                                // 对于长按等其他事件，不消费，让父级处理
                                return false
                            }
                        }
                    }
                    
                    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
                        // Allow horizontal scrolling but prevent vertical scrolling
                        if (t != 0) {
                            scrollTo(l, 0)
                        } else {
                            super.onScrollChanged(l, t, oldl, oldt)
                        }
                    }
                }.apply {
                    settings.javaScriptEnabled = true
                    settings.loadWithOverviewMode = false // Disable overview mode for better scrolling
                    settings.useWideViewPort = true
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    settings.layoutAlgorithm = android.webkit.WebSettings.LayoutAlgorithm.NORMAL
                    setBackgroundColor(0) // Transparent background

                    // 禁用文本选择相关功能
                    settings.textZoom = 100 // 禁用文本缩放

                    // 禁用文本选择，但不消费长按事件，让其传递到父级
                    setOnLongClickListener { false } // 不消费长按事件，让父级处理
                    isLongClickable = false

                    // Enable horizontal scrolling
                    isHorizontalScrollBarEnabled = true
                    isVerticalScrollBarEnabled = false
                    scrollBarStyle = android.view.View.SCROLLBARS_INSIDE_OVERLAY

                    // Add logging for debugging
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                            Log.d("MathView", "${consoleMessage.message()} -- From line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}")
                            return super.onConsoleMessage(consoleMessage)
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            Log.d("MathView", "Page finished loading: $url")
                        }
                    }
                    
                    addJavascriptInterface(object {
                        @android.webkit.JavascriptInterface
                        fun updateHeight(newHeight: Int) {
                            // Update height based on content, add minimal padding for math formulas
                            val density = resources.displayMetrics.density
                            val calculatedHeight = (newHeight / density).dp + 2.dp
                            // Always update height to ensure proper display, with compact minimums
                            webViewHeight = maxOf(calculatedHeight, if (isDisplay) 40.dp else 32.dp)
                            Log.d("MathView", "New height from JS: $newHeight -> $webViewHeight")
                        }
                    }, "android")
                }
                webView
            },
            update = { webView ->
                webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
            },
            modifier = Modifier.height(webViewHeight).fillMaxWidth()
        )
    }
}