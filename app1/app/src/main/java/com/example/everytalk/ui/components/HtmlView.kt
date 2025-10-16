package com.example.everytalk.ui.components

import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.unit.dp

/**
 * 轻量 HTML 渲染器（用于渲染非数学的普通 HTML 片段）
 *
 * - 使用 Android WebView 离线渲染，默认 baseUrl 指向 assets，便于本地图片/样式引用
 * - 仅用于显示，默认关闭 JavaScript
 * - 提供最小高度以避免高度为 0 的“细条”
 *
 * 用法：
 * [Kotlin.HtmlView()](KunTalkwithAi/app1/app/src/main/java/com/example/everytalk/ui/components/HtmlView.kt:19)
 */
@Composable
fun HtmlView(
    html: String,
    modifier: Modifier = Modifier,
    baseUrl: String? = "file:///android_asset/",
    minHeightDp: Int = 0,
    enableJavaScript: Boolean = false
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val dark = isSystemInDarkTheme()

    // 构造简单的页面骨架，注入暗色样式（注意：这里是标准 HTML，不要进行实体转义）
    val page = remember(html, dark) {
        buildString {
            append("<!DOCTYPE html>")
            append("<html lang=\"zh-CN\">")
            append("<head>")
            append("<meta charset=\"UTF-8\" />")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />")
            append("<style>")
            append(":root{color-scheme: light dark;} body{margin:0;padding:0;background:transparent;}")
            if (dark) {
                append("body{color: rgba(255,255,255,0.92);} a{color:#61dafb;}")
            } else {
                append("body{color:#111;} a{color:#0a66c2;}")
            }
            append("img,svg,canvas,video{max-width:100%;height:auto;}")
            append("pre{white-space:pre-wrap;word-break:break-word;}")
            append("</style>")
            append("</head>")
            append("<body>")
            append(html)
            append("</body>")
            append("</html>")
        }
    }

    val webView = remember(context) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS

            settings.javaScriptEnabled = enableJavaScript
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.domStorageEnabled = false

            // 关闭宽视口，避免整体缩放
            settings.useWideViewPort = false
            settings.loadWithOverviewMode = false

            // 允许从 file:// 访问相对资源（assets）
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true

            // 禁止缩放控件
            settings.displayZoomControls = false
            settings.builtInZoomControls = false
            settings.setSupportZoom(false)

            // 提升渲染优先级
            settings.setRenderPriority(WebSettings.RenderPriority.HIGH)

            // 使用硬件加速以减少重绘卡顿
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView
        },
        update = { wv ->
            if (minHeightDp > 0) {
                val px = with(density) { minHeightDp.dp.toPx().toInt() }
                if (wv.minimumHeight != px) wv.minimumHeight = px
            }
            // 以 baseUrl 方式加载，允许相对路径（如 assets 内资源）
            val safeBase = baseUrl ?: "file:///android_asset/"
            wv.loadDataWithBaseURL(
                safeBase,
                page,
                "text/html",
                "utf-8",
                null
            )
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            // 不在此销毁，由上层生命周期统一管理；需要彻底释放可在合适位置调用 destroy()
        }
    }
}