package com.example.everytalk.ui.components

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.example.everytalk.ui.theme.DarkCodeBackground
import com.example.everytalk.ui.theme.chatColors
import androidx.compose.foundation.isSystemInDarkTheme

/**
 * 代码预览组件
 * 支持HTML、SVG、CSS等可视化代码的预览
 */

/**
 * 简单的代码预览组件
 * 提供代码块显示和可选的预览功能
 */
@Composable
fun CodePreview(
    code: String,
    language: String?,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.chatColors.userBubble
) {
    // 为每个代码块实例创建稳定的唯一标识符
    // 使用UUID确保每个组件实例都有唯一的ID，避免状态冲突
    val stableInstanceId = remember {
        java.util.UUID.randomUUID().toString()
    }
    val clipboardManager = LocalClipboardManager.current
    
    Column(modifier = modifier) {
        // 显示代码语言标签（如果有）
        if (!language.isNullOrBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = backgroundColor,
                shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
            ) {
                Text(
                    text = language,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        
        // 代码内容区域
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = backgroundColor,
            shape = RoundedCornerShape(
                topStart = if (language.isNullOrBlank()) 8.dp else 0.dp,
                topEnd = if (language.isNullOrBlank()) 8.dp else 0.dp,
                bottomStart = 8.dp,
                bottomEnd = 8.dp
            )
        ) {
            Column {
                // 代码文本 - 支持水平滚动，带有视觉指示器
                val scrollState = rememberScrollState()
                val verticalScrollState = rememberScrollState()
                val isDarkTheme = isSystemInDarkTheme()
                val scrollIndicatorColor = if (isDarkTheme) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.2f)
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    // 代码内容区域
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(verticalScrollState)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                            .background(
                                color = if (isDarkTheme) Color.Black.copy(alpha = 0.2f) else Color.Gray.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = if (isDarkTheme) Color.White.copy(alpha = 0.1f) else Color.Gray.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .horizontalScroll(scrollState)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = code,
                            modifier = Modifier.widthIn(min = 0.dp), // 允许文本超出容器宽度
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.3f, // 增加行高提升可读性
                                fontSize = MaterialTheme.typography.bodySmall.fontSize * 0.95f // 稍微减小字体以适应更多内容
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            softWrap = false, // 禁用自动换行，保持代码格式
                            maxLines = Int.MAX_VALUE // 允许多行显示
                        )
                    }
                    }
                    
                    // 右侧滚动指示器（当内容可滚动时显示）
                    if (scrollState.maxValue > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(20.dp)
                                .height(40.dp)
                                .background(
                                    brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            backgroundColor.copy(alpha = 0.8f)
                                        )
                                    )
                                )
                        ) {
                            Text(
                                text = "→",
                                modifier = Modifier.align(Alignment.Center),
                                color = scrollIndicatorColor,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                
                // 预览对话框状态 - 使用稳定的键确保状态独立
                var showPreview by remember(stableInstanceId) { mutableStateOf(false) }

                // 添加调试日志
                LaunchedEffect(stableInstanceId) {
                    android.util.Log.d("CodePreview", "CodePreview created with ID: $stableInstanceId")
                }
                
                // 按钮行
                Row(
                    modifier = Modifier
                        .zIndex(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    // 如果支持预览，显示预览按钮（左边）
                    if (isCodePreviewable(language, code)) {
                        Surface(
                            onClick = {
                                android.util.Log.d("CodePreview", "Preview button clicked for ID: $stableInstanceId")
                                showPreview = true
                            },
                            modifier = Modifier
                                .weight(1f),
                            shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp, topEnd = 0.dp, bottomEnd = 0.dp),
                            color = backgroundColor,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Visibility,
                                    contentDescription = "预览代码",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "预览代码",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                                )
                            }
                        }
                        
                        // 复制代码按钮（右边）
                        Surface(
                            onClick = {
                                android.util.Log.d("CodePreview", "Copy button clicked for ID: $stableInstanceId")
                                clipboardManager.setText(AnnotatedString(code))
                            },
                            modifier = Modifier
                                .weight(1f),
                            shape = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 16.dp, bottomEnd = 16.dp),
                            color = backgroundColor,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = "复制代码",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "复制代码",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                                )
                            }
                        }
                    } else {
                        // 没有预览时，复制按钮占满宽度
                        Surface(
                            onClick = {
                                android.util.Log.d("CodePreview", "Full-width copy button clicked for ID: $stableInstanceId")
                                clipboardManager.setText(AnnotatedString(code))
                            },
                            modifier = Modifier
                                .fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = backgroundColor,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = "复制代码",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "复制代码",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                                )
                            }
                        }
                    }
                }
                
                // 预览对话框
                if (showPreview && isCodePreviewable(language, code)) {
                    CodePreviewDialog(
                        code = code,
                        language = language,
                        instanceId = stableInstanceId,
                        onDismiss = { showPreview = false }
                    )
                }
            }
        }
    }
}

/**
 * 代码预览对话框
 */
@Composable
private fun CodePreviewDialog(
    code: String,
    language: String?,
    instanceId: String,
    onDismiss: () -> Unit
) {
    // 动画状态 - 使用稳定的实例ID作为键确保每个对话框独立
    var visible by remember(instanceId) { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        visible = true
    }
    
    Dialog(
        onDismissRequest = {
            visible = false
            onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(
                animationSpec = tween(300, easing = EaseOutCubic)
            ) + scaleIn(
                initialScale = 0.8f,
                animationSpec = tween(300, easing = EaseOutCubic)
            ),
            exit = fadeOut(
                animationSpec = tween(200, easing = EaseInCubic)
            ) + scaleOut(
                targetScale = 0.8f,
                animationSpec = tween(200, easing = EaseInCubic)
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp), // 极窄的边距
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(24.dp)), // 更大的外圆角
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface, // 使用主题表面色
                    tonalElevation = 16.dp
                ) {
                    // 预览内容
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp) // 极窄的内边距
                            .clip(RoundedCornerShape(20.dp)), // 内容区域圆角
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.background, // 使用主题背景色
                        tonalElevation = 0.dp
                    ) {
                        CodePreviewWebView(
                            code = code,
                            language = language,
                            isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                        )
                    }
                }
            }
        }
    }
}

/**
 * WebView组件用于渲染代码预览
 */
@Composable
private fun CodePreviewWebView(
    code: String,
    language: String?,
    isDarkTheme: Boolean
) {
    val context = LocalContext.current
    
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                webViewClient = WebViewClient()
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    // 禁用文本选择相关功能
                    textZoom = 100 // 禁用文本缩放
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                }

                // 禁用文本选择，但不消费长按事件，让其传递到父级
                setOnLongClickListener { false } // 不消费长按事件，让父级处理
                isLongClickable = false

                // 重写触摸事件处理，禁用长按
                setOnTouchListener { _, event ->
                    when (event.action) {
                        android.view.MotionEvent.ACTION_DOWN,
                        android.view.MotionEvent.ACTION_MOVE,
                        android.view.MotionEvent.ACTION_UP -> {
                            // 允许基本的触摸事件用于滚动
                            false // 不消费事件，让WebView处理
                        }
                        else -> {
                            // 对于其他事件（如长按），不处理
                            false
                        }
                    }
                }
            }
        },
        update = { webView ->
            val htmlContent = generatePreviewHtml(code, language, isDarkTheme)
            webView.loadDataWithBaseURL(
                null,
                htmlContent,
                "text/html",
                "UTF-8",
                null
            )
        }
    )
}

/**
 * 生成预览用的HTML内容
 */
private fun generatePreviewHtml(code: String, language: String?, isDarkTheme: Boolean): String {
    val processedCode = preprocessCodeForRendering(code, language)
    val colors = getThemeColors(isDarkTheme)

    return when (language?.lowercase()) {
        "html" -> generateHtmlPreview(processedCode, colors)
        "svg", "xml" -> generateSvgPreview(processedCode, language, code, colors)
        "markdown", "md", "mdpreview", "markdown_preview" -> generateMarkdownPreview(processedCode, colors)
        "mermaid" -> generateMermaidPreview(code, colors, isDarkTheme)
        "css" -> generateCssPreview(code, colors)
        "javascript", "js" -> generateJavaScriptPreview(code, colors)
        else -> generateGenericPreview(code, language, colors)
    }
}

/**
 * 获取主题颜色配置
 */
private data class ThemeColors(
    val backgroundColor: String,
    val textColor: String,
    val surfaceColor: String,
    val borderColor: String,
    val codeBackgroundColor: String,
    val disableSelectionCSS: String
)

private fun getThemeColors(isDarkTheme: Boolean): ThemeColors {
    val backgroundColor = if (isDarkTheme) "#0D1117" else "#FFFFFF"
    val textColor = if (isDarkTheme) "#E6EDF3" else "#24292F"
    val surfaceColor = if (isDarkTheme) "#161B22" else "#F6F8FA"
    val borderColor = if (isDarkTheme) "#30363D" else "#D0D7DE"
    val codeBackgroundColor = if (isDarkTheme) "#0D1117" else "#F6F8FA"

    val disableSelectionCSS = """
        * {
            -webkit-user-select: none;
            -moz-user-select: none;
            -ms-user-select: none;
            user-select: none;
            -webkit-touch-callout: none;
            -webkit-tap-highlight-color: transparent;
        }
    """.trimIndent()

    return ThemeColors(backgroundColor, textColor, surfaceColor, borderColor, codeBackgroundColor, disableSelectionCSS)
}
/**
 * 生成基础HTML模板
 */
private fun generateBaseHtml(
    title: String,
    content: String,
    colors: ThemeColors,
    additionalHead: String = "",
    additionalStyles: String = ""
): String {
    return """
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>$title</title>
        $additionalHead
        <style>
            ${colors.disableSelectionCSS}
            body {
                margin: 16px;
                font-family: system-ui, -apple-system, sans-serif;
                background: ${colors.backgroundColor};
                color: ${colors.textColor};
            }
            $additionalStyles
        </style>
    </head>
    <body>
        $content
    </body>
    </html>
    """.trimIndent()
}

private fun generateHtmlPreview(code: String, colors: ThemeColors): String {
    return if (code.contains("<html", ignoreCase = true) || code.contains("<!doctype", ignoreCase = true)) {
        code
    } else {
        generateBaseHtml("HTML Preview", code, colors)
    }
}

private fun generateSvgPreview(processedCode: String, language: String?, code: String, colors: ThemeColors): String {
    return if (language?.lowercase() == "xml" && !code.contains("<svg", ignoreCase = true)) {
        generateBaseHtml(
            "Code Preview",
            """
            <h3>代码内容 (${language ?: "未知语言"})</h3>
            <pre style="background: ${colors.surfaceColor}; color: ${colors.textColor}; padding: 16px; border-radius: 8px; overflow: auto; border: 1px solid ${colors.borderColor};"><code>${code.replace("<", "&lt;").replace(">", "&gt;")}</code></pre>
            """.trimIndent(),
            colors
        )
    } else {
        generateBaseHtml(
            "SVG Preview",
            processedCode,
            colors,
            additionalStyles = """
            body {
                display: flex;
                justify-content: center;
                align-items: center;
                min-height: 100vh;
                margin: 0;
                padding: 16px;
            }
            svg {
                max-width: 100%;
                max-height: 80vh;
                background: ${colors.surfaceColor};
                border-radius: 8px;
                border: 1px solid ${colors.borderColor};
            }
            """.trimIndent()
        )
    }
}

private fun generateMarkdownPreview(code: String, colors: ThemeColors): String {
    return generateBaseHtml(
        "Markdown Preview",
        """<div id="content"></div>""",
        colors,
        additionalHead = """<script src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script>""",
        additionalStyles = """
        body {
            margin: 0;
            padding: 20px;
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            line-height: 1.6;
        }
        #content {
            max-width: 800px;
            margin: 0 auto;
            background: ${colors.surfaceColor};
            padding: 30px;
            border-radius: 8px;
            border: 1px solid ${colors.borderColor};
        }
        h1, h2, h3 { color: ${colors.textColor}; }
        code { background: ${colors.backgroundColor}; color: #000000; font-weight: 700; padding: 2px 4px; border-radius: 3px; }
        pre { background: ${colors.codeBackgroundColor}; color: ${colors.textColor}; padding: 15px; border-radius: 5px; overflow-x: auto; border: 1px solid ${colors.borderColor}; }
        blockquote { border-left: 4px solid ${colors.borderColor}; margin: 0; padding-left: 20px; color: ${colors.textColor}; opacity: 0.8; }
        """.trimIndent()
    ) + """
    <script>
        const markdown = `${code.replace("`", "\\`")}`;
        document.getElementById('content').innerHTML = marked.parse(markdown);
    </script>
    """.trimIndent()
}

private fun generateMermaidPreview(code: String, colors: ThemeColors, isDarkTheme: Boolean): String {
    return generateBaseHtml(
        "Mermaid Diagram",
        """<div class="mermaid">$code</div>""",
        colors,
        additionalHead = """<script src="https://cdn.jsdelivr.net/npm/mermaid/dist/mermaid.min.js"></script>""",
        additionalStyles = """
        body {
            display: flex;
            justify-content: center;
            align-items: center;
            min-height: 100vh;
            margin: 0;
            padding: 20px;
            font-family: Arial, sans-serif;
        }
        .mermaid {
            background: ${colors.surfaceColor};
            padding: 20px;
            border-radius: 8px;
            border: 1px solid ${colors.borderColor};
        }
        """.trimIndent()
    ) + """
    <script>
        mermaid.initialize({ startOnLoad: true, theme: ${if (isDarkTheme) "'dark'" else "'default'"} });
    </script>
    """.trimIndent()
}

private fun generateCssPreview(code: String, colors: ThemeColors): String {
    return """
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>CSS Preview</title>
        <style>
            $code
        </style>
    </head>
    <body>
        <div style="padding: 16px;">
            <h1>CSS样式预览</h1>
            <p>这是一个段落文本，用于展示CSS样式效果。</p>
            <div class="demo-box" style="width: 200px; height: 100px; background: #e3f2fd; border: 1px solid #2196f3; margin: 16px 0; padding: 16px;">
                演示容器
            </div>
            <button style="padding: 8px 16px; margin: 4px;">按钮示例</button>
            <ul>
                <li>列表项 1</li>
                <li>列表项 2</li>
                <li>列表项 3</li>
            </ul>
        </div>
    </body>
    </html>
    """.trimIndent()
}

private fun generateJavaScriptPreview(code: String, colors: ThemeColors): String {
    return generateBaseHtml(
        "JavaScript Preview",
        """
        <h2>JavaScript 代码执行结果</h2>
        <div id="output" style="background: ${colors.surfaceColor}; border-radius: 8px; padding: 16px; margin-top: 16px; border: 1px solid ${colors.borderColor};"></div>
        """.trimIndent(),
        colors
    ) + """
    <script>
        try {
            const output = document.getElementById('output');
            const originalLog = console.log;
            console.log = function(...args) {
                const div = document.createElement('div');
                div.textContent = args.join(' ');
                div.style.marginBottom = '8px';
                output.appendChild(div);
                originalLog.apply(console, args);
            };

            $code
        } catch (error) {
            document.getElementById('output').innerHTML = '<div style="color: red;">错误: ' + error.message + '</div>';
        }
    </script>
    """.trimIndent()
}

private fun generateGenericPreview(code: String, language: String?, colors: ThemeColors): String {
    return generateBaseHtml(
        "Code Preview",
        """
        <h3>代码内容 (${language ?: "未知语言"})</h3>
        <pre style="background: ${colors.surfaceColor}; color: ${colors.textColor}; padding: 16px; border-radius: 8px; overflow: auto; border: 1px solid ${colors.borderColor};"><code>${code.replace("<", "&lt;").replace(">", "&gt;")}</code></pre>
        """.trimIndent(),
        colors,
        additionalStyles = "body { font-family: monospace; }"
    )
}

/**
 * 判断代码是否支持预览
 */
private fun isCodePreviewable(language: String?, code: String): Boolean {
    if (language == null && code.isBlank()) return false
    
    val lang = language?.lowercase()
    
    // 直接支持的语言
    when (lang) {
        // Web技术
        "html", "svg", "css", "javascript", "js" -> return true

        // 标记语言
        "markdown", "md", "mdpreview", "markdown_preview" -> return true

        // 图表
        "mermaid" -> return true

        "xml" -> {
            // 对于XML，检查是否是SVG
            if (code.contains("<svg", ignoreCase = true)) {
                return true
            }
        }
    }
    
    // 基于内容的检测
    return code.contains("<html", ignoreCase = true) ||
           code.contains("<svg", ignoreCase = true) ||
           code.contains("<!doctype", ignoreCase = true) ||
           code.contains("graph", ignoreCase = true) && code.contains("-->", ignoreCase = true) // Mermaid
}

/**
 * 预处理代码内容
 */
private fun preprocessCodeForRendering(code: String, language: String?): String {
    // 直接返回原始代码，不需要额外处理
    return code
}