package com.android.everytalk.ui.components

import android.annotation.SuppressLint
import android.content.ClipData
import android.os.Build
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.android.everytalk.R
import com.android.everytalk.ui.components.syntax.HighlightCache
import com.android.everytalk.ui.components.syntax.SyntaxHighlightTheme
import com.android.everytalk.ui.components.syntax.SyntaxHighlighter
import com.android.everytalk.ui.components.content.isPreviewSupported
import com.android.everytalk.ui.theme.chatColors
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val CODE_VIEWER_DIALOG_EDGE_SCALE = 0.72f
internal const val CODE_VIEWER_DIALOG_TRANSITION_MILLIS = 260
internal const val CODE_VIEWER_DIALOG_ALPHA_MILLIS = CODE_VIEWER_DIALOG_TRANSITION_MILLIS
internal const val CODE_VIEWER_DIALOG_WINDOW_DIM_AMOUNT = 0f

@Suppress("DEPRECATION")
private fun android.view.Window.setTransparentSystemBars() {
    statusBarColor = android.graphics.Color.TRANSPARENT
    navigationBarColor = android.graphics.Color.TRANSPARENT
}

@Suppress("DEPRECATION")
private fun WebSettings.disableFileUrlCrossOriginAccess() {
    allowFileAccessFromFileURLs = false
    allowUniversalAccessFromFileURLs = false
}

internal data class CodeViewerDialogAnimationTarget(
    val scale: Float,
    val alpha: Float,
)

internal fun resolveCodeViewerDialogAnimationTarget(
    hasEntered: Boolean,
    isClosing: Boolean,
): CodeViewerDialogAnimationTarget {
    return when {
        isClosing -> CodeViewerDialogAnimationTarget(
            scale = CODE_VIEWER_DIALOG_EDGE_SCALE,
            alpha = 0f,
        )
        hasEntered -> CodeViewerDialogAnimationTarget(
            scale = 1f,
            alpha = 1f,
        )
        else -> CodeViewerDialogAnimationTarget(
            scale = CODE_VIEWER_DIALOG_EDGE_SCALE,
            alpha = 0f,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPreviewContent(
    code: String,
    language: String,
    previewBackgroundColor: Color = Color.White,
    previewTextColor: Color = Color.Black,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val (templateFileName, processedCode) = remember(code, language) {
        val normalizedLang = language.trim().lowercase()
        when (normalizedLang) {
            "mermaid" -> "templates/mermaid.html" to escapeHtml(code)
            "echarts" -> "templates/echarts.html" to code.replace("`", "\\`")
            "chartjs" -> "templates/chartjs.html" to code.replace("`", "\\`")
            "flowchart", "flow" -> "templates/flowchart.html" to code.replace("`", "\\`")
            "vega", "vega-lite" -> "templates/vega.html" to code
            "infographic" -> "templates/html.html" to renderInfographic(code)
            "html", "svg", "xml" -> "templates/html.html" to extractHtmlBodyOrSelf(code)
            else -> "templates/html.html" to code
        }
    }

    val isDarkPreview = previewBackgroundColor.luminance() < 0.5f
    val previewSurfaceColor = if (isDarkPreview) Color(0xFF1E1E1E) else Color.White
    val previewTextCssColor = previewTextColor.toCssHex()
    val previewSurfaceCssColor = previewSurfaceColor.toCssHex()
    val darkModeOverrides = if (isDarkPreview) {
        """
        body.everytalk-dark .preview-container table {
            background-color: $previewSurfaceCssColor;
        }
        body.everytalk-dark .preview-container :is(table, tr, td, th) {
            -webkit-tap-highlight-color: transparent;
        }
        body.everytalk-dark .preview-container :is(td, th):not([style*="color"]) {
            color: $previewTextCssColor;
        }
        body.everytalk-dark .preview-container :is(tr, td, th):is(:hover, :active, :focus, :focus-within) {
            background-color: rgba(255, 255, 255, 0.08) !important;
            color: $previewTextCssColor !important;
        }
        body.everytalk-dark .preview-container :is(tr, td, th):is(:hover, :active, :focus, :focus-within) *:not([style*="color"]) {
            color: $previewTextCssColor !important;
        }
        body.everytalk-dark .preview-container :is(table, tr, td, th):not([style*="border"]) {
            border-color: rgba(255, 255, 255, 0.18);
        }
        """.trimIndent()
    } else {
        ""
    }

    val htmlContent = remember(templateFileName, processedCode, previewBackgroundColor, previewTextColor) {
        try {
            val assetManager = context.assets
            val inputStream = assetManager.open(templateFileName)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val template = reader.readText()
            reader.close()
            template
                .replace("ET_COLOR_SCHEME", if (isDarkPreview) "dark" else "light")
                .replace("ET_THEME_CLASS", if (isDarkPreview) "everytalk-dark" else "everytalk-light")
                .replace("ET_BACKGROUND_COLOR", previewBackgroundColor.toCssHex())
                .replace("ET_TEXT_COLOR", previewTextCssColor)
                .replace("ET_DARK_MODE_OVERRIDES", darkModeOverrides)
                .replace("<!-- CONTENT_PLACEHOLDER -->", processedCode)
        } catch (e: Exception) {
            e.printStackTrace()
            "<html><body>Error loading template: ${e.message}</body></html>"
        }
    }

    var previewWebView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            previewWebView?.apply {
                stopLoading()
                loadUrl("about:blank")
                removeAllViews()
                destroy()
            }
            previewWebView = null
        }
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                previewWebView = this
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                settings.disableFileUrlCrossOriginAccess()
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    settings.isAlgorithmicDarkeningAllowed = false
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    @Suppress("DEPRECATION")
                    settings.forceDark = WebSettings.FORCE_DARK_OFF
                }
                setBackgroundColor(previewBackgroundColor.toArgb())
                webViewClient = WebViewClient()
                // 禁用 WebView 自身的滚动和点击拦截，让外层能够捕获事件（针对非全屏预览模式）
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setOnTouchListener { v, event ->
                    // 返回 false 允许事件冒泡到外层 Compose 点击监听器
                    false
                }
            }
        },
        update = { webView ->
            if (webView.tag != htmlContent) {
                webView.tag = htmlContent
                webView.loadDataWithBaseURL(
                    "file:///android_asset/templates/",
                    htmlContent,
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        },
        modifier = modifier
    )
}

@Composable
fun FullScreenCodeViewerDialog(
    code: String,
    language: String,
    initialPreviewMode: Boolean = false,
    sourceBounds: androidx.compose.ui.geometry.Rect = androidx.compose.ui.geometry.Rect.Zero,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboard.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    val isDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val bgColor = MaterialTheme.colorScheme.background
    val previewBgColor = bgColor
    val previewTextColor = if (isDarkTheme) Color(0xFFEAEAEA) else Color.Black
    val headerColor = if (isDarkTheme) Color.White else Color.Black
    val capsuleBgColor = if (isDarkTheme) Color(0xFF383838) else Color(0xFFE2E2E2)
    val capsuleSelectedBgColor = if (isDarkTheme) Color(0xFF505050) else Color.White

    val canPreview = isPreviewSupported(language)
    val pagerState = rememberPagerState(
        initialPage = if (canPreview && initialPreviewMode) 1 else 0,
        pageCount = { if (canPreview) 2 else 1 }
    )
    val isPreviewMode = canPreview && pagerState.currentPage == 1
    var hasEntered by remember { mutableStateOf(false) }
    var isClosing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        hasEntered = true
    }
    LaunchedEffect(isClosing) {
        if (isClosing) {
            delay(CODE_VIEWER_DIALOG_TRANSITION_MILLIS.toLong())
            onDismiss()
        }
    }
    val animationTarget = resolveCodeViewerDialogAnimationTarget(
        hasEntered = hasEntered,
        isClosing = isClosing,
    )
    val entryScale by animateFloatAsState(
        targetValue = animationTarget.scale,
        animationSpec = tween(durationMillis = CODE_VIEWER_DIALOG_TRANSITION_MILLIS),
        label = "dialogEntryScale"
    )
    val entryAlpha by animateFloatAsState(
        targetValue = animationTarget.alpha,
        animationSpec = tween(durationMillis = CODE_VIEWER_DIALOG_ALPHA_MILLIS),
        label = "dialogEntryAlpha"
    )
    val transformOrigin = remember(sourceBounds, configuration.screenWidthDp, configuration.screenHeightDp) {
        if (sourceBounds == androidx.compose.ui.geometry.Rect.Zero) {
            TransformOrigin.Center
        } else {
            val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
            val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
            TransformOrigin(
                pivotFractionX = ((sourceBounds.left + sourceBounds.width / 2f) / screenWidthPx).coerceIn(0f, 1f),
                pivotFractionY = ((sourceBounds.top + sourceBounds.height / 2f) / screenHeightPx).coerceIn(0f, 1f)
            )
        }
    }

    fun requestDismiss() {
        if (!isClosing) {
            isClosing = true
        }
    }

    Dialog(
        onDismissRequest = { requestDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false
        )
    ) {
        val dialogWindowProvider = LocalView.current.parent as? DialogWindowProvider
        SideEffect {
            dialogWindowProvider?.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setDimAmount(CODE_VIEWER_DIALOG_WINDOW_DIM_AMOUNT)
                window.setTransparentSystemBars()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !isDarkTheme
                    isAppearanceLightNavigationBars = !isDarkTheme
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = entryAlpha
                    scaleX = entryScale
                    scaleY = entryScale
                    this.transformOrigin = transformOrigin
                }
                .background(bgColor)
                .statusBarsPadding()
        ) {
            // 顶部导航栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 左侧：关闭按钮
                IconButton(onClick = { requestDismiss() }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = headerColor
                    )
                }

                // 中间：胶囊按钮 (代码/预览)
                if (canPreview) {
                    val capsuleWidth = 140.dp
                    val indicatorWidth = 70.dp
                    val indicatorProgress =
                        (pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(0f, 1f)
                    val indicatorOffset = indicatorWidth * indicatorProgress

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = capsuleBgColor,
                        modifier = Modifier.size(width = capsuleWidth, height = 36.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            // 滑块指示器
                            Box(
                                modifier = Modifier
                                    .padding(2.dp)
                                    .offset(x = indicatorOffset)
                                    .size(width = indicatorWidth - 4.dp, height = 32.dp)
                                    .background(capsuleSelectedBgColor, RoundedCornerShape(50))
                            )

                            // 按钮文本层
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(50))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            scope.launch { pagerState.animateScrollToPage(0) }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "代码",
                                        color = headerColor,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(50))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            scope.launch { pagerState.animateScrollToPage(1) }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "预览",
                                        color = headerColor,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                // 右侧：预览模式为分享，代码模式为复制
                val context = LocalContext.current
                AnimatedContent(
                    targetState = isPreviewMode,
                    transitionSpec = {
                        (fadeIn(tween(220)) + scaleIn(
                            tween(220),
                            initialScale = 0.8f
                        )).togetherWith(
                            fadeOut(tween(150)) + scaleOut(
                                tween(150),
                                targetScale = 0.6f
                            )
                        )
                    },
                    label = "PreviewHeaderActionButton"
                ) { previewMode ->
                    if (previewMode) {
                        IconButton(
                            onClick = {
                                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, code)
                                }
                                context.startActivity(android.content.Intent.createChooser(shareIntent, "分享代码"))
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_gpt_share),
                                contentDescription = "分享",
                                tint = headerColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", code)))
                                }
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_gpt_copy),
                                contentDescription = "复制",
                                tint = headerColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            // 主体内容
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = false
                ) { page ->
                    if (page == 1) {
                        // 全屏预览时，在底部增加圆角和内边距，使其有悬浮感并避免遮挡底部系统导航条
                        Surface(
                            modifier = Modifier
                                .fillMaxSize(),
                            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                            color = previewBgColor
                        ) {
                            WebPreviewContent(
                                code = code,
                                language = language,
                                previewBackgroundColor = previewBgColor,
                                previewTextColor = previewTextColor,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        val syntaxTheme = if (isDarkTheme) SyntaxHighlightTheme.Dark else SyntaxHighlightTheme.Light
                        val highlightedCode = remember(code, language, isDarkTheme) {
                            SyntaxHighlighter.highlight(code, language, syntaxTheme)
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp)
                        ) {
                            Text(
                                text = language.trim().ifBlank { "CODE" }.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = headerColor,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            Text(
                                text = highlightedCode,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                ),
                                softWrap = true
                            )
                            Spacer(modifier = Modifier.height(64.dp))
                        }
                    }
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

private fun Color.toCssHex(): String {
    return String.format("#%06X", 0xFFFFFF and toArgb())
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
