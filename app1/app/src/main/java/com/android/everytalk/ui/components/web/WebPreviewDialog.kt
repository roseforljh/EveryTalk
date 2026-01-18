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
            "mermaid" -> "templates/mermaid.html" to escapeHtml(code)
            "echarts" -> "templates/echarts.html" to code.replace("`", "\\`")
            "chartjs" -> "templates/chartjs.html" to code.replace("`", "\\`")
            // flowchart 模板里使用 JS 模板字符串，需要转义反引号
            "flowchart", "flow" -> "templates/flowchart.html" to code.replace("`", "\\`")
            "vega", "vega-lite" -> "templates/vega.html" to code
            "infographic" -> "templates/html.html" to renderInfographic(code)
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
 * HTML 转义，防止 mermaid 代码中的 < > 等字符破坏 HTML 结构
 */
private fun escapeHtml(text: String): String {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#039;")
}

private fun renderInfographic(raw: String): String {
    val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) {
        return ""
    }

    var index = 0
    while (index < lines.size && lines[index].startsWith("infographic", ignoreCase = true)) {
        index++
    }
    if (index < lines.size && lines[index].equals("data", ignoreCase = true)) {
        index++
    }

    var title = ""
    val items = mutableListOf<Triple<String, String, String?>>()

    while (index < lines.size) {
        val line = lines[index]
        if (line.startsWith("title ", ignoreCase = true)) {
            title = line.removePrefix("title").trim()
            index++
            continue
        }
        if (line.startsWith("items", ignoreCase = true)) {
            index++
            while (index < lines.size) {
                val current = lines[index]
                if (!current.startsWith("- label ", ignoreCase = true)) {
                    index++
                    continue
                }
                val label = current.removePrefix("- label").trim()
                var desc = ""
                var icon: String? = null

                var next = index + 1
                if (next < lines.size && lines[next].startsWith("desc ", ignoreCase = true)) {
                    desc = lines[next].removePrefix("desc").trim()
                    next++
                }
                if (next < lines.size && lines[next].startsWith("icon ", ignoreCase = true)) {
                    icon = lines[next].removePrefix("icon").trim()
                    next++
                }

                items.add(Triple(label, desc, icon))
                index = next
            }
            continue
        }
        index++
    }

    if (title.isBlank() && items.isEmpty()) {
        return "<pre style=\"white-space:pre-wrap;font-family:monospace;font-size:14px;\">${escapeHtml(raw)}</pre>"
    }

    val builder = StringBuilder()
    builder.append("<div style=\"width:100%;display:flex;flex-direction:column;gap:16px;\">")
    if (title.isNotBlank()) {
        builder.append("<div style=\"font-size:20px;font-weight:600;margin-bottom:4px;\">")
        builder.append(escapeHtml(title))
        builder.append("</div>")
    }
    builder.append("<div style=\"display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px;\">")
    for ((label, desc, icon) in items) {
        builder.append("<div style=\"border-radius:12px;padding:12px 14px;border:1px solid rgba(0,0,0,0.08);background:rgba(0,0,0,0.02);\">")
        if (!icon.isNullOrBlank()) {
            builder.append("<div style=\"font-size:11px;color:rgba(0,0,0,0.5);margin-bottom:4px;\">")
            builder.append(escapeHtml(icon))
            builder.append("</div>")
        }
        builder.append("<div style=\"font-size:14px;font-weight:600;margin-bottom:4px;\">")
        builder.append(escapeHtml(label))
        builder.append("</div>")
        if (desc.isNotBlank()) {
            builder.append("<div style=\"font-size:13px;color:rgba(0,0,0,0.75);\">")
            builder.append(escapeHtml(desc))
            builder.append("</div>")
        }
        builder.append("</div>")
    }
    builder.append("</div></div>")
    return builder.toString()
}

/**
 * 提取原始 HTML/XML 字符串中的 <style> 标签和 <body>...</body> 的内容。
 * - 若存在 body 标签，则返回所有 <style> 标签 + body 内部内容；
 * - 若不存在 body 标签，则直接返回原始字符串。
 *
 * 这样可以保留用户定义的 CSS 样式，同时避免用户提供的整页 HTML
 * 自己的布局把图片固定在顶部，让真正的布局交给我们的 html.html 模板（flex 居中）。
 */
private fun extractHtmlBodyOrSelf(raw: String): String {
    val lower = raw.lowercase()

    // 优先匹配未转义的 <body>
    val bodyIndex = lower.indexOf("<body")
    if (bodyIndex == -1) {
        // 没有 body 标签，直接返回原始字符串
        return raw
    }

    // 提取所有 <style>...</style> 标签内容
    val styleBlocks = extractAllStyleBlocks(raw)
    
    // 提取 body 内容
    val startTagEnd = lower.indexOf(">", bodyIndex).takeIf { it != -1 } ?: return raw
    val endIndex = lower.indexOf("</body>", startTagEnd).takeIf { it != -1 } ?: raw.length
    val bodyContent = raw.substring(startTagEnd + 1, endIndex)

    // 合并 style 标签和 body 内容
    return if (styleBlocks.isNotEmpty()) {
        styleBlocks + "\n" + bodyContent
    } else {
        bodyContent
    }
}

/**
 * 从 HTML 字符串中提取所有 <style>...</style> 标签（包括标签本身）
 */
private fun extractAllStyleBlocks(html: String): String {
    val lower = html.lowercase()
    val result = StringBuilder()
    var searchStart = 0
    
    while (true) {
        // 查找 <style 开始
        val styleStart = lower.indexOf("<style", searchStart)
        if (styleStart == -1) break
        
        // 查找 </style> 结束
        val styleEnd = lower.indexOf("</style>", styleStart)
        if (styleEnd == -1) break
        
        // 提取完整的 style 块（包括标签）
        val styleBlock = html.substring(styleStart, styleEnd + 8) // 8 = "</style>".length
        if (result.isNotEmpty()) {
            result.append("\n")
        }
        result.append(styleBlock)
        
        // 继续搜索下一个 style 块
        searchStart = styleEnd + 8
    }
    
    return result.toString()
}
