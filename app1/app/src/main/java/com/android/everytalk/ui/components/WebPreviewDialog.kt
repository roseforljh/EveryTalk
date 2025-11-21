package com.android.everytalk.ui.components

import android.annotation.SuppressLint
import android.webkit.WebSettings
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
    
    // Determine template and process code based on language
    val (templateFileName, processedCode) = remember(code, language) {
        when (language.lowercase()) {
            "mermaid" -> "templates/mermaid.html" to code
            "echarts" -> "templates/echarts.html" to code
            "chartjs" -> "templates/chartjs.html" to code
            // Escape backticks for flowchart as it uses template literals in JS
            "flowchart", "flow" -> "templates/flowchart.html" to code.replace("`", "\\`")
            "vega", "vega-lite" -> "templates/vega.html" to code
            "html", "svg" -> "templates/html.html" to code
            else -> "templates/html.html" to code // Fallback
        }
    }

    // Load template content
    val htmlContent = remember(templateFileName, processedCode) {
        try {
            val assetManager = context.assets
            val inputStream = assetManager.open(templateFileName)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val template = reader.readText()
            reader.close()
            
            // Inject code into template
            // For HTML/SVG, we inject directly. For JS-based, we might need to escape or handle differently if needed.
            // The templates use <!-- CONTENT_PLACEHOLDER -->
            template.replace("<!-- CONTENT_PLACEHOLDER -->", processedCode)
        } catch (e: Exception) {
            e.printStackTrace()
            "<html><body>Error loading template: ${e.message}</body></html>"
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            decorFitsSystemWindows = false // 🎯 允许内容延伸到状态栏/导航栏后面
        )
    ) {
        // 🎯 获取 Dialog 的 Window 并设置沉浸式
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
                .background(Color.White) // WebView usually has white bg, match it or let WebView handle it
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        
                        // Transparent background to let template handle colors
                        setBackgroundColor(0)
                        
                        webViewClient = WebViewClient()
                    }
                },
                update = { webView ->
                    // Load data with base URL pointing to assets so relative paths work
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

            // Close button
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding() // 🎯 避开状态栏
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