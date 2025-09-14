package com.example.everytalk.ui.components

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * HTML渲染组件，用于显示HTML内容（特别是表格）
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlView(
    htmlContent: String,
    modifier: Modifier = Modifier,
    textColor: Color = Color.Unspecified
) {
    val context = LocalContext.current
    val isDarkTheme = isSystemInDarkTheme()
    
    // 根据主题设置背景色和文本颜色
    val backgroundColor = if (isDarkTheme) "#1a1a1a" else "#ffffff"
    val defaultTextColor = if (isDarkTheme) "#ffffff" else "#000000"
    val finalTextColor = if (textColor != Color.Unspecified) {
        String.format("#%06X", 0xFFFFFF and textColor.toArgb())
    } else {
        defaultTextColor
    }
    
    // 使用记忆化来避免重复创建相同的HTML内容
    val fullHtmlContent = remember(htmlContent, finalTextColor, backgroundColor) {
        createHtmlContent(htmlContent, finalTextColor, backgroundColor)
    }
    
    // 记忆单例 WebView，防止重组导致实例重建与内容重复加载
    val webView = remember(context.applicationContext) {
        WebView(context.applicationContext).apply {
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // 页面加载完成后设置透明度，减少闪白
                    view?.alpha = 1f
                }
            }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                textZoom = 100 // 禁用文本缩放
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
            }
            // 禁用长按和文本选择，但不消费长按事件，让其传递到父级
            setOnLongClickListener { false }
            isLongClickable = false
            // 初始设置透明度为0，减少闪白
            alpha = 0f
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { webView },
        update = { vw ->
            // 只有当内容真正改变时才重新加载
            val hash = fullHtmlContent.hashCode()
            if (vw.tag != hash) {
                vw.tag = hash
                vw.alpha = 0f
                vw.loadDataWithBaseURL("file:///android_asset/", fullHtmlContent, "text/html", "UTF-8", null)
            }
        }
    )
}

/**
 * 创建完整的HTML内容
 */
private fun createHtmlContent(
    htmlContent: String,
    textColor: String,
    backgroundColor: String
): String {
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
            <style>
                * {
                    -webkit-user-select: none;
                    -moz-user-select: none;
                    -ms-user-select: none;
                    user-select: none;
                    -webkit-touch-callout: none;
                    -webkit-tap-highlight-color: transparent;
                }
                body {
                    margin: 0;
                    padding: 8px;
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                    font-size: 14px;
                    line-height: 1.5;
                    color: $textColor;
                    background-color: $backgroundColor;
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                }
                table {
                    border-collapse: collapse;
                    width: 100%;
                    margin: 8px 0;
                }
                th, td {
                    border: 1px solid ${if (backgroundColor == "#1a1a1a") "#444444" else "#cccccc"};
                    padding: 8px;
                    text-align: left;
                    vertical-align: top;
                }
                th {
                    background-color: ${if (backgroundColor == "#1a1a1a") "#333333" else "#f5f5f5"};
                    font-weight: bold;
                }
                h1, h2, h3, h4, h5, h6 {
                    margin: 8px 0;
                    color: $textColor;
                }
                p {
                    margin: 4px 0;
                }
                code {
                    background-color: $backgroundColor;
                    padding: 2px 4px;
                    border-radius: 3px;
                    font-family: 'Courier New', Courier, monospace;
                    color: #000000 !important;
                    font-weight: 700;
                }
            </style>
        </head>
        <body>
            $htmlContent
        </body>
        </html>
    """.trimIndent()
}