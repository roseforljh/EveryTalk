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
    backgroundColor: Color = MaterialTheme.chatColors.aiBubble
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
                                color = MaterialTheme.chatColors.codeBlockBackground, // ✅ 使用主题颜色
                                shape = RoundedCornerShape(8.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), // ✅ 使用主题轮廓颜色
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

                // 关闭 WebView 深色策略（无需依赖 androidx.webkit；通过反射调用 Android Q+ 的原生 API）
                try {
                    val webSettingsClass = android.webkit.WebSettings::class.java
                    val forceDarkField = webSettingsClass.getField("FORCE_DARK_OFF")
                    val forceDarkOff = forceDarkField.getInt(null)
                    val setForceDark = webSettingsClass.getMethod("setForceDark", Int::class.javaPrimitiveType)
                    setForceDark.invoke(settings, forceDarkOff)
                } catch (_: Throwable) {
                    // 设备不支持或低版本，无需处理
                }

                // WebView 背景透明，避免深色主题下容器发黑
                setBackgroundColor(android.graphics.Color.TRANSPARENT)

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

    return when (resolvePreviewKind(language, code)) {
        PreviewKind.HTML -> generateHtmlPreview(processedCode, colors)
        PreviewKind.SVG -> generateSvgPreview(processedCode, language, code, colors)
        PreviewKind.MARKDOWN -> generateMarkdownPreview(processedCode, colors)
        PreviewKind.MERMAID -> generateMermaidPreview(code, colors, isDarkTheme)
        PreviewKind.CSS -> generateCssPreview(code, colors)
        PreviewKind.JAVASCRIPT -> generateJavaScriptPreview(code, colors)
        PreviewKind.GENERIC -> generateGenericPreview(code, language, colors)
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

private fun getThemeColors(@Suppress("UNUSED_PARAMETER") isDarkTheme: Boolean): ThemeColors {
    // 统一采用“固定浅色”方案，确保夜间模式下也可读
    val backgroundColor = "#FFFFFF"
    val textColor = "#111111"
    val surfaceColor = "#F6F8FA"
    val borderColor = "#D0D7DE"
    val codeBackgroundColor = "#F6F8FA"

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
 * 渲染类型统一枚举
 */
private enum class PreviewKind {
    SVG, HTML, MARKDOWN, MERMAID, CSS, JAVASCRIPT, GENERIC
}

/**
 * 语言标识归一化（大小写/空白/MIME/别名）
 */
private fun normalizeLanguage(lang: String?): String? {
    val raw = lang?.trim() ?: return null
    val l = raw.lowercase()
        .replace(Regex("\\s+"), " ")
        .replace(Regex(";.*$"), "") // 去掉分号后面的mime参数
        .trim()

    // 优先处理常见MIME或组合写法
    if (l.contains("svg")) return "svg"
    if (l == "htm") return "html"

    return when (l) {
        "html" -> "html"
        "xml" -> "xml"
        "css" -> "css"
        "js", "javascript" -> "javascript"
        "markdown", "md", "mdown", "mdpreview", "markdown_preview" -> "markdown"
        else -> l
    }
}

/**
 * 内容检测：是否为内联SVG
 */
private fun detectSvgByContent(code: String): Boolean {
    if (code.isBlank()) return false
    // 匹配 <svg ...>，忽略大小写与空白
    return Regex("(?is)<\\s*svg(\\s|>)").containsMatchIn(code)
}

/**
 * 内容检测：是否为 SVG 片段（只有子元素，没有根 <svg>）
 */
private fun detectSvgFragmentByContent(code: String): Boolean {
    if (code.isBlank()) return false
    // 已经包含根 <svg> 则不是片段
    if (Regex("(?is)<\\s*svg(\\s|>)").containsMatchIn(code)) return false
    // 常见 SVG 子标签集合
    val svgChildTags = "g|path|circle|rect|line|polyline|polygon|ellipse|text|defs|use|clipPath|mask|linearGradient|radialGradient|pattern|filter|animate|animateTransform|animateMotion|foreignObject"
    // 只要命中任意 SVG 子元素，即认定为片段
    return Regex("(?is)<\\s*(?:$svgChildTags)\\b").containsMatchIn(code)
}

/**
 * 内容检测：是否为HTML文档/片段
 */
private fun detectHtmlByContent(code: String): Boolean {
    if (code.isBlank()) return false
    return code.contains("<html", ignoreCase = true) ||
           code.contains("<!doctype", ignoreCase = true)
}

/**
 * 统一的“语言+内容”决策器，供按钮判定与渲染分派共用
 * 优先级策略：
 * - 明确语言优先；但对于 svg/xml 需判定“完整 vs 片段”
 * - 无语言时，先判定 SVG（含根或片段），再判定 HTML
 */
private fun resolvePreviewKind(language: String?, code: String): PreviewKind {
    val lang = normalizeLanguage(language)

    when (lang) {
        "html" -> return PreviewKind.HTML
        "svg" -> {
            // 仅当确为 SVG（根或片段）才进入 SVG 预览，否则回落
            return if (detectSvgByContent(code) || detectSvgFragmentByContent(code)) {
                PreviewKind.SVG
            } else {
                PreviewKind.GENERIC
            }
        }
        "xml" -> {
            // xml 中若包含 SVG 根或片段，则按 SVG 渲染，否则走通用
            return if (detectSvgByContent(code) || detectSvgFragmentByContent(code)) {
                PreviewKind.SVG
            } else {
                PreviewKind.GENERIC
            }
        }
        "markdown" -> return PreviewKind.MARKDOWN
        "mermaid" -> return PreviewKind.MERMAID
        "css" -> return PreviewKind.CSS
        "javascript" -> return PreviewKind.JAVASCRIPT
    }

    // 语言未知/不标准时，依据内容兜底
    if (detectSvgByContent(code) || detectSvgFragmentByContent(code)) return PreviewKind.SVG
    if (detectHtmlByContent(code)) return PreviewKind.HTML

    // 其它保持通用
    return PreviewKind.GENERIC
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
        <!-- 防止 WebView 夜间反色；声明仅用浅色方案 -->
        <meta name="color-scheme" content="light only">
        <title>$title</title>
        $additionalHead
        <style>
            ${colors.disableSelectionCSS}
            html { color-scheme: light; }
            body {
                margin: 16px;
                font-family: system-ui, -apple-system, sans-serif;
                background: ${colors.backgroundColor};
                color: ${colors.textColor};
                -webkit-text-size-adjust: 100%;
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

/**
 * 向完整 HTML 文档中注入“浅色可读”样式与 meta，防止系统夜间反色导致对比度过低
 * - 不改变用户内容结构，仅在 head/body 关键位置插入最小覆盖样式
 * - 若无 head 则尝试插到 <html> 或 <body> 后；都没有则前置注入
 */
private fun injectLightModeShim(html: String): String {
    val meta = """<meta name="color-scheme" content="light only">"""
    val style = """
        <style id="__preview_light_shim__">
            html { color-scheme: light; }
            body {
                background: #FFFFFF !important;
                color: #111111 !important;
                -webkit-text-size-adjust: 100%;
            }
            h1,h2,h3,h4,h5,h6 { color: #0F172A !important; }
        </style>
    """.trimIndent()

    val hasHeadClose = Regex("(?is)</\\s*head\\s*>").containsMatchIn(html)
    return when {
        hasHeadClose -> html.replace(Regex("(?is)</\\s*head\\s*>"), "$meta$style</head>")
        Regex("(?is)<\\s*head\\b[^>]*>").containsMatchIn(html) ->
            html.replace(Regex("(?is)<\\s*head\\b[^>]*>"), "$0$meta$style")
        Regex("(?is)<\\s*html\\b[^>]*>").containsMatchIn(html) ->
            html.replace(Regex("(?is)<\\s*html\\b[^>]*>"), "$0$meta$style")
        Regex("(?is)<\\s*body\\b[^>]*>").containsMatchIn(html) ->
            html.replace(Regex("(?is)<\\s*body\\b[^>]*>"), "$0$meta$style")
        else -> "$meta$style$html"
    }
}


private fun generateHtmlPreview(code: String, colors: ThemeColors): String {
    // 对“完整 HTML 文档”注入浅色可读的强制样式与元信息；片段则包裹到我们统一模板
    return if (code.contains("<html", ignoreCase = true) || code.contains("<!doctype", ignoreCase = true)) {
        injectLightModeShim(code)
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
                /* 强制白底，避免深色主题或 WebView 深色策略导致发黑 */
                background: #FFFFFF;
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
    // 固定为浅色阅读方案，避免夜间模式下黑字叠深底导致对比度过低
    return """
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <meta name="color-scheme" content="light only">
        <title>CSS Preview</title>
        <style>
            html { color-scheme: light; }
            body {
                margin: 0;
                padding: 20px;
                background: #FFFFFF;         /* 固定白底 */
                color: #111111;              /* 固定深色文字 */
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, system-ui, sans-serif;
                -webkit-text-size-adjust: 100%;
                line-height: 1.6;
            }
            .preview-card {
                max-width: 860px;
                margin: 0 auto;
                background: #FFFFFF;
                border: 1px solid #E2E8F0;
                border-radius: 12px;
                padding: 24px;
                box-shadow: 0 1px 2px rgba(0,0,0,.06);
            }
            h1,h2,h3,h4,h5,h6 { color: #0F172A; }
            p,li,button { color: #111111; }
            .demo-box {
                width: 200px; height: 100px;
                background: #E3F2FD;
                border: 1px solid #2196F3;
                margin: 16px 0; padding: 16px;
                color: #0F172A;             /* 盒内文字保持深色 */
            }
            .btn {
                padding: 8px 16px; margin: 4px;
                background: #F1F5F9;
                border: 1px solid #CBD5E1;
                color: #0F172A;
                border-radius: 8px;
            }
            /* 用户CSS放在最后，依然可以覆盖上面的固定浅色方案 */
            $code
        </style>
    </head>
    <body>
        <div class="preview-card">
            <h1>CSS样式预览</h1>
            <p>这是一个段落文本，用于展示CSS样式效果。</p>
            <div class="demo-box">演示容器</div>
            <button class="btn">按钮示例</button>
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
    return resolvePreviewKind(language, code) != PreviewKind.GENERIC
}

/**
 * 预处理代码内容
 * 目标：对常见的“AI 生成但不完全合法的 SVG”做温和修复，以提升 Android WebView 的容错性
 *
 * 注意：仅在判定为 SVG（语言为 svg，或内容中包含 <svg）时执行；其它语言原样返回
 */
private fun preprocessCodeForRendering(code: String, language: String?): String {
    val lang = normalizeLanguage(language)
    val looksLikeSvg = (lang == "svg") || detectSvgByContent(code)

    if (!looksLikeSvg) return code

    var fixed = code

    // 1) 修复把命名空间片段误拼在 viewBox 后的常见错误：
    //    形如：viewBox="0 0 300 300".org/2000/svg">
    //    处理：把 .org/2000/svg 挪正为 xmlns="http://www.w3.org/2000/svg"
    fixed = Regex("(?is)(<\\s*svg\\b[^>]*viewBox=\"[^\"]*\")\\s*\\.org/2000/svg\"?")
        .replace(fixed) { mr ->
            mr.groupValues[1] + "\" xmlns=\"http://www.w3.org/2000/svg\""
        }

    // 2) 若 <svg> 上缺少 xmlns，则自动补上
    if (!Regex("(?is)<\\s*svg\\b[^>]*\\sxmlns=").containsMatchIn(fixed)) {
        fixed = fixed.replace(Regex("(?is)<\\s*svg\\b"), "<svg xmlns=\"http://www.w3.org/2000/svg\"")
    }

    // 3) 修复 <stop> 把颜色写进 offset 的情况：offset="[#hex]" -> offset="0%" stop-color="#hex"
    fixed = Regex("(?is)<\\s*stop\\b([^>]*?)offset\\s*=\\s*\"(#[0-9a-fA-F]{3,8})\"([^>]*)/?>")
        .replace(fixed) { mr ->
            val before = mr.groupValues[1]
            val color = mr.groupValues[2]
            val after = mr.groupValues[3]
            val hasStopColor = Regex("(?is)stop-color\\s*=").containsMatchIn(before + after)
            val rebuilt = StringBuilder()
                .append(before)
                .append("offset=\"0%\"")
                .append(after)
            if (!hasStopColor) rebuilt.append(" stop-color=\"").append(color).append("\"")
            "<stop$rebuilt />"
        }

    // 4) 明显被截断的 <circle ... 行（没有 >），直接丢弃该行，避免破坏后续解析
    run {
        val repaired = fixed.lines().filter { line ->
            val t = line.trimStart()
            !(t.startsWith("<circle") && !t.contains(">"))
        }
        fixed = repaired.joinToString("\n")
    }

    // 5) 清理被注释片段误插入到属性值后的中文/文本："...xxx" 任意文本 -->  => 只保留到引号
    fixed = Regex("(?s)\"\\s*[^<\"]*?-->").replace(fixed, "\"")

    // 6) 移除明显未闭合的属性（引号未闭合直到 > 或 /> 之前），以免整段失败
    fixed = Regex("(?is)\\s+[a-zA-Z_:][-a-zA-Z0-9_:.]*=\"[^\"]*(?=\\s*[>/])").replace(fixed, "")

    return fixed
}