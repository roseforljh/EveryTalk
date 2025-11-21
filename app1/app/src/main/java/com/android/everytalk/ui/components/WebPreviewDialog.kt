package com.android.everytalk.ui.components

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import java.io.BufferedReader
import java.io.InputStreamReader

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPreviewDialog(
    code: String,
    language: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    // 1. 根据语言选择模板并预处理代码
    val (templateFileName, processedCode) = remember(code, language) {
        val normalizedLang = language.trim().lowercase()
        when (normalizedLang) {
            "mermaid" -> "templates/mermaid.html" to code
            "echarts" -> "templates/echarts.html" to code
            "chartjs" -> "templates/chartjs.html" to code
            // flowchart 模板里使用 JS 模板字符串，需要转义反引号
            "flowchart", "flow" -> "templates/flowchart.html" to code.replace("`", "\\`")
            "vega", "vega-lite" -> "templates/vega.html" to code
            // html / svg / xml：剥掉外层 html/body，只保留 body 内容，交给居中模板
            "html", "svg", "xml" -> "templates/html.html" to extractHtmlBodyOrSelf(code)
            else -> "templates/html.html" to code // 其它语言也走通用 html 模板
        }
    }

    // 2. 从 assets 读取模板并注入内容
    val htmlContent = remember(templateFileName, processedCode) {
        try {
            val assetManager = context.assets
            val inputStream = assetManager.open(templateFileName)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val template = reader.readText()
            reader.close()

            // 模板使用 <!-- CONTENT_PLACEHOLDER -->
            template.replace("<!-- CONTENT_PLACEHOLDER -->", processedCode)
        } catch (e: Exception) {
            e.printStackTrace()
            "<html><body>Error loading template: ${e.message}</body></html>"
        }
    }

    // 3. 全屏对话框 + 沉浸式窗口
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            decorFitsSystemWindows = false
        )
    ) {
        val dialogWindowProvider = LocalView.current.parent as? DialogWindowProvider
        SideEffect {
            dialogWindowProvider?.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
        ) {
            // 4. 真正承载模板的 WebView
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        setBackgroundColor(0)
                        webViewClient = WebViewClient()
                    }
                },
                update = { webView ->
                    webView.loadDataWithBaseURL(
                        "file:///android_asset/templates/",
                        htmlContent,
                        "text/html",
                        "UTF-8",
                        null
                    )
                },
                modifier = Modifier.fillMaxSize()
            )

            // 5. 右上角关闭按钮
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .size(48.dp)
                    .zIndex(2f)
                    .background(
                        color = Color.Black.copy(alpha = 0.1f),
                        shape = CircleShape
                    )
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.Black
                    )
                }
            }
        }
    }
}

/**
 * 提取原始 HTML/XML 字符串中 <body>...</body> 的内容。
 * - 若存在 body 标签，则返回 body 内部内容；
 * - 若不存在 body 标签，则直接返回原始字符串。
 *
 * 这样可以避免用户提供的整页 HTML 自己的布局把图片固定在顶部，
 * 让真正的布局交给我们的 html.html 模板（flex 居中）。
 */
private fun extractHtmlBodyOrSelf(raw: String): String {
    val lower = raw.lowercase()

    // 优先匹配未转义的 <body>
    val bodyIndex = lower.indexOf("<body")
    if (bodyIndex == -1) {
        // 再尝试匹配已经 HTML 转义后的 &lt;body> 形式
        val escapedIndex = lower.indexOf("<body")
        if (escapedIndex == -1) return raw

        val startTagEndEscaped = lower.indexOf(">", escapedIndex).takeIf { it != -1 } ?: return raw
        val endIndexEscaped = lower.indexOf("</body>", startTagEndEscaped).takeIf { it != -1 } ?: raw.length
        return raw.substring(startTagEndEscaped + 4, endIndexEscaped)
    }

    val startTagEnd = lower.indexOf(">", bodyIndex).takeIf { it != -1 } ?: return raw
    val endIndex = lower.indexOf("</body>", startTagEnd).takeIf { it != -1 } ?: raw.length

    return raw.substring(startTagEnd + 1, endIndex)
}