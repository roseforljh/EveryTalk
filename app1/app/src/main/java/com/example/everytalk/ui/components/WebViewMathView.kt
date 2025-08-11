package com.example.everytalk.ui.components

import android.annotation.SuppressLint
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.ConcurrentHashMap

/**
 * 原始WebView版本的MathView - 仅作为备份
 * ⚠️ 警告: 此版本存在严重性能问题，仅供紧急回滚使用
 * 
 * 已知问题:
 * - 高CPU使用率 (可达200%+)
 * - 内存泄漏风险
 * - 频繁ANR
 * - 渲染缓慢
 * - JavaScript执行开销巨大
 */

// Cache for pre-rendered KaTeX HTML to avoid re-rendering
private val htmlCache = ConcurrentHashMap<String, String>()

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewMathView(
    latex: String,
    isDisplay: Boolean,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val cacheKey = "$latex-$isDisplay-${textColor.value}"

    // Generate the HTML content for KaTeX
    val htmlContent = remember(cacheKey) {
        htmlCache.getOrPut(cacheKey) {
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
                <meta name="viewport" content="${if (isDisplay) "width=device-width, " else ""}initial-scale=1.0">
                <link rel="stylesheet" href="file:///android_asset/katex.min.css">
                <script src="file:///android_asset/katex.min.js"></script>
                <style>
                    * {
                        margin: 0;
                        padding: 0;
                        box-sizing: border-box;
                        -webkit-user-select: none;
                        -moz-user-select: none;
                        -ms-user-select: none;
                        user-select: none;
                        -webkit-touch-callout: none;
                        -webkit-tap-highlight-color: transparent;
                    }
                    html, body {
                        height: 100%;
                        overflow-x: auto;
                        overflow-y: hidden;
                        background-color: transparent;
                        -webkit-user-select: none;
                        -moz-user-select: none;
                        -ms-user-select: none;
                        user-select: none;
                    }
                    body {
                        padding: 4px 8px;
                        font-size: 16px;
                        color: $hexColor;
                        display: flex;
                        align-items: center;
                        justify-content: ${if (isDisplay) "center" else "flex-start"};
                        width: max-content;
                        min-width: ${if (isDisplay) "100%" else "auto"};
                        min-height: 30px;
                        line-height: 1.5;
                    }
                    #math {
                        display: inline-block;
                        text-align: ${if (isDisplay) "center" else "left"};
                        width: ${if (isDisplay) "100%" else "auto"};
                    }
                    .katex {
                        font-size: ${if (isDisplay) "1.1em" else "1em"};
                        display: inline-block;
                        line-height: 1.5;
                        vertical-align: middle;
                        max-width: none;
                        width: auto;
                        min-height: 20px;
                        padding: 0;
                        margin: 0;
                        color: $hexColor !important;
                    }
                    .katex-display {
                        margin: 4px 0;
                        display: block;
                        font-size: 1.2em;
                        line-height: 1.6;
                        text-align: center;
                        max-width: none;
                        width: 100%;
                        min-height: 28px;
                        padding: 2px 0;
                    }
                    .katex * {
                        color: $hexColor !important;
                    }
                    .error-message {
                        color: #ff6b6b;
                        font-size: 0.9em;
                        font-family: monospace;
                        background: rgba(255, 107, 107, 0.1);
                        padding: 4px 8px;
                        border-radius: 4px;
                        border-left: 3px solid #ff6b6b;
                    }
                </style>
            </head>
            <body>
                <div id="math"></div>
                <script>
                    function renderMath() {
                        const mathElement = document.getElementById('math');
                        const latexInput = `$latex`.replace(/\\\\/g, "\\\\");
                        
                        try {
                            let processedLatex = latexInput.trim();
                            if (!processedLatex) {
                                throw new Error('空的数学表达式');
                            }
                            
                            katex.render(processedLatex, mathElement, {
                                throwOnError: false,
                                displayMode: $isDisplay,
                                strict: false,
                                trust: false
                            });
                            
                            const katexElements = mathElement.querySelectorAll('.katex, .katex *');
                            katexElements.forEach(el => {
                                el.style.color = '$hexColor';
                            });
                            
                        } catch (e) {
                            console.error('KaTeX render error:', e);
                            mathElement.innerHTML = `<div class="error-message">数学公式渲染错误<br><small>${'$'}{e.message || '未知错误'}</small></div>`;
                        }
                    }
                    renderMath();
                </script>
            </body>
            </html>
            """.trimIndent()
        }
    }

    AndroidView(
        factory = { context ->
            object : WebView(context) {
                override fun onTouchEvent(event: MotionEvent?): Boolean {
                    when (event?.action) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                            return super.onTouchEvent(event)
                        }
                        else -> {
                            return false
                        }
                    }
                }
                
                override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
                    if (t != 0) {
                        scrollTo(l, 0)
                    } else {
                        super.onScrollChanged(l, t, oldl, oldt)
                    }
                }
            }.apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )

                settings.javaScriptEnabled = true
                settings.loadWithOverviewMode = isDisplay
                settings.useWideViewPort = isDisplay
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.layoutAlgorithm = android.webkit.WebSettings.LayoutAlgorithm.NORMAL
                setBackgroundColor(0) // Transparent background

                setOnLongClickListener { false }
                isLongClickable = false

                isHorizontalScrollBarEnabled = true
                isVerticalScrollBarEnabled = false
                scrollBarStyle = android.view.View.SCROLLBARS_INSIDE_OVERLAY

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        Log.d("WebViewMathView", "${consoleMessage.message()} -- From line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}")
                        return super.onConsoleMessage(consoleMessage)
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.requestLayout()
                    }
                }
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
        },
        modifier = modifier
            .wrapContentHeight()
            .then(
                if (isDisplay) Modifier.fillMaxWidth() else Modifier.wrapContentWidth()
            )
    )
}