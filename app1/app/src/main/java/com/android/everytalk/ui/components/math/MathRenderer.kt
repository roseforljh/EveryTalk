package com.android.everytalk.ui.components.math

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.everytalk.config.PerformanceConfig

/**
 * MathRenderer
 *
 * - 仅使用 WebView 加载本地 assets 下的 KaTeX 页面（file:///android_asset/katex/index.html）
 * - 正常情况调用页面中的 renderLatex(latex, inline, theme) 进行排版
 * - 失败/超时/过长时：回退显示原始 LaTeX 文本（保留原始 $ 或 $$ 包裹逻辑在上层决定）
 *
 * 注意：本文件不引入外链；assets/katex/index.html 将实现 renderLatex 方法。
 */

@Composable
fun MathInline(
    latex: String,
    modifier: Modifier = Modifier,
    timeoutMs: Long = PerformanceConfig.MATH_RENDER_TIMEOUT_MS
) {
    MathRenderContainer(
        latex = latex,
        inline = true,
        modifier = modifier,
        timeoutMs = timeoutMs
    )
}

@Composable
fun MathBlock(
    latex: String,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 300.dp,
    timeoutMs: Long = PerformanceConfig.MATH_RENDER_TIMEOUT_MS
) {
    val density = LocalDensity.current
    val maxHeightPx = with(density) { maxHeight.toPx() }
    
    // 使用状态记录测量的内容宽度
    var measuredWidth by remember { mutableStateOf<Int?>(null) }
    
    // 🎯 WebView 内部滚动方案：移除外层滚动，让 WebView 自己处理
    Box(
        modifier = modifier
            .fillMaxWidth()  // 外层占满父容器
            .heightIn(max = maxHeight)
    ) {
        MathRenderContainer(
            latex = latex,
            inline = false,
            modifier = measuredWidth?.let { 
                Modifier.width(it.dp)  // 应用测量宽度
            } ?: Modifier.fillMaxWidth(),  // 初始占满
            maxHeightPx = maxHeightPx,
            timeoutMs = timeoutMs,
            onWidthMeasured = { width ->
                measuredWidth = width  // 回调设置宽度
            }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MathRenderContainer(
    latex: String,
    inline: Boolean,
    modifier: Modifier = Modifier,
    maxHeightPx: Float? = null,
    timeoutMs: Long = PerformanceConfig.MATH_RENDER_TIMEOUT_MS,
    onWidthMeasured: ((Int) -> Unit)? = null  // 新增：宽度测量回调
) {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current
    val isDark = isSystemInDarkTheme()
    val themeStr = if (isDark) "dark" else "light"

    // 避免重复无意义渲染：记忆上一次成功渲染的 key
    val lastRendered = remember { mutableStateOf<Triple<String, Boolean, String>?>(null) }

    // 生命周期与回调控制
    var webViewReady by remember { mutableStateOf(false) }
    var pageReady by remember { mutableStateOf(false) } // 页面一次可用后，不再重复延时
    var loadFailed by remember { mutableStateOf(false) }
    var alive by remember { mutableStateOf(true) }
    var webViewRef: WebView? = null
    var pendingReadyRunnable: Runnable? = null
    var token by remember { mutableStateOf(0) } // 使已有回调失效的递增令牌

    // 快速失败条件：过长
    if (latex.length > PerformanceConfig.MATH_MAX_FORMULA_LEN) {
        FallbackRaw(latex = latex, inline = inline, modifier = modifier)
        return
    }

    if (isPreview) {
        // 预览模式下直接回退
        FallbackRaw(latex = latex, inline = inline, modifier = modifier)
        return
    }

    AndroidView(
        modifier = modifier,
        factory = {
            WebView(context).apply {
                webViewRef = this
                setBackgroundColor(Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                // 🎯 块级数学：启用 WebView 自身水平滚动
                isHorizontalScrollBarEnabled = !inline

                settings.javaScriptEnabled = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    settings.safeBrowsingEnabled = true
                }
                // 禁止网络请求（仅允许 file）
                settings.setBlockNetworkLoads(true)
                // 缩放禁用
                settings.displayZoomControls = false
                settings.builtInZoomControls = false
                settings.setSupportZoom(false)
                
                // 🎯 允许内容超出视口宽度（块级数学需要）
                settings.useWideViewPort = !inline
                settings.loadWithOverviewMode = false

                // 载入本地 KaTeX 容器页面
                loadUrl("file:///android_asset/katex/index.html")
            }
        },
        update = { view ->
            webViewRef = view
            if (!alive) return@AndroidView

            // 若 key 未变化且已 ready，避免重复 evaluate
            val key = Triple(latex, inline, themeStr)
            if (lastRendered.value == key && webViewReady) {
                return@AndroidView
            }

            // 页面可用后仅在 key 变化时渲染
            if (webViewReady && pageReady && view.isSafeForJs()) {
                val currentToken = token
                tryRender(view, latex, inline, themeStr) { success ->
                    if (!alive || token != currentToken) return@tryRender
                    if (success) {
                        lastRendered.value = key
                        
                        // ✅ 渲染成功后测量宽度（仅块级数学）
                        if (!inline && onWidthMeasured != null) {
                            measureContentWidth(view) { width ->
                                if (alive && token == currentToken) {
                                    onWidthMeasured(width)
                                }
                            }
                        }
                    } else {
                        loadFailed = true
                    }
                }
                return@AndroidView
            }

            // 若尚未 ready，则尝试一次“探测可用性”，失败时只投递一次延时
            if (!webViewReady) {
                webViewReady = true
            }

            if (!pageReady) {
                // 先直接探测一次（不依赖延时）
                if (view.isSafeForJs()) {
                    val currentToken = token
                    probeReady(view) { ready ->
                        if (!alive || token != currentToken) return@probeReady
                        pageReady = ready
                        if (pageReady) {
                            // 立即尝试渲染
                            tryRender(view, latex, inline, themeStr) { success ->
                                if (!alive || token != currentToken) return@tryRender
                                if (success) {
                                    lastRendered.value = key
                                    
                                    // ✅ 渲染成功后测量宽度（仅块级数学）
                                    if (!inline && onWidthMeasured != null) {
                                        measureContentWidth(view) { width ->
                                            if (alive && token == currentToken) {
                                                onWidthMeasured(width)
                                            }
                                        }
                                    }
                                } else {
                                    loadFailed = true
                                }
                            }
                        } else {
                            // 仅投递一次延时，避免多次 postDelayed
                            pendingReadyRunnable?.let { view.removeCallbacks(it) }
                            val r = Runnable {
                                if (!alive || token != currentToken) return@Runnable
                                pageReady = true
                                if (view.isSafeForJs()) {
                                    tryRender(view, latex, inline, themeStr) { success ->
                                        if (!alive || token != currentToken) return@tryRender
                                        if (success) {
                                            lastRendered.value = key
                                            
                                            // ✅ 渲染成功后测量宽度（仅块级数学）
                                            if (!inline && onWidthMeasured != null) {
                                                measureContentWidth(view) { width ->
                                                    if (alive && token == currentToken) {
                                                        onWidthMeasured(width)
                                                    }
                                                }
                                            }
                                        } else {
                                            loadFailed = true
                                        }
                                    }
                                }
                            }
                            pendingReadyRunnable = r
                            view.postDelayed(r, 150L)
                        }
                    }
                }
            }
        }
    )

    // 超时保护：仅在 key 变化且仍未成功时再尝试一次
    LaunchedEffect(latex, inline, themeStr) {
        loadFailed = false
        kotlinx.coroutines.delay(timeoutMs)
        val view = webViewRef
        val key = Triple(latex, inline, themeStr)
        if (alive && pageReady && view != null && view.isSafeForJs() && lastRendered.value != key) {
            val currentToken = token
            tryRender(view, latex, inline, themeStr) { success ->
                if (!alive || token != currentToken) return@tryRender
                if (success) {
                    lastRendered.value = key
                    
                    // ✅ 渲染成功后测量宽度（仅块级数学）
                    if (!inline && onWidthMeasured != null) {
                        measureContentWidth(view) { width ->
                            if (alive && token == currentToken) {
                                onWidthMeasured(width)
                            }
                        }
                    }
                } else {
                    loadFailed = true
                }
            }
        }
    }

    // 销毁
    DisposableEffect(Unit) {
        onDispose {
            alive = false
            token += 1 // 使之前的回调全部失效
            val view = webViewRef
            pendingReadyRunnable?.let { view?.removeCallbacks(it) }
            pendingReadyRunnable = null
            view?.stopLoading()

            // 延后一帧再 destroy，降低“attached 时 destroy”的概率
            view?.post {
                try {
                    if (view.isSafeForJs()) {
                        // 仍附着则不立即 destroy，由 AndroidView 生命周期回收
                    } else {
                        view.destroy()
                    }
                } catch (_: Throwable) { /* ignore */ }
            }
            webViewRef = null
        }
    }

    if (loadFailed) {
        FallbackRaw(latex = latex, inline = inline, modifier = modifier)
    }
}

/**
 * 轻量探测 renderLatex 是否已注入可用，避免无谓的延时等待。
 * 返回 true 表示页面端已提供 renderLatex，可立即尝试渲染。
 */
private fun probeReady(
    webView: WebView,
    onResult: (Boolean) -> Unit
) {
    if (!webView.isSafeForJs()) {
        onResult(false)
        return
    }
    val js = "javascript:(function(){return (typeof renderLatex==='function')?'ok':'no';})();"
    try {
        webView.evaluateJavascript(js) { ret ->
            onResult(ret?.contains("ok") == true)
        }
    } catch (_: Throwable) {
        onResult(false)
    }
}

private fun tryRender(
    webView: WebView,
    latex: String,
    inline: Boolean,
    theme: String,
    onResult: (Boolean) -> Unit
) {
    // WebView 已销毁/未附着时直接失败，避免异常
    if (!webView.isSafeForJs()) {
        onResult(false)
        return
    }
    val safeLatex = latex
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", "\\n")
        .replace("\r", "")
    val js = "javascript:(function(){ if (typeof renderLatex==='function'){try{renderLatex('$safeLatex', $inline, '$theme');return 'ok';}catch(e){return 'err';}}else{return 'no';}})();"
    try {
        webView.evaluateJavascript(js, ValueCallback { ret ->
            val ok = ret?.contains("ok") == true
            onResult(ok)
        })
    } catch (_: Throwable) {
        onResult(false)
    }
}

private fun WebView.isSafeForJs(): Boolean {
    // 附着到窗口且 handler 存在时认为安全
    return this.handler != null && this.isAttachedToWindow
}

/**
 * 通过 JavaScript 测量 WebView 内容实际宽度
 */
private fun measureContentWidth(
    webView: WebView,
    onResult: (Int) -> Unit
) {
    if (!webView.isSafeForJs()) {
        onResult(0)
        return
    }
    
    // 获取内容滚动宽度（实际内容宽度）
    val js = """
        javascript:(function(){
            var width = Math.max(
                document.body.scrollWidth,
                document.documentElement.scrollWidth,
                document.getElementById('root').scrollWidth
            );
            return width;
        })();
    """.trimIndent()
    
    try {
        webView.evaluateJavascript(js) { result ->
            val width = result?.toIntOrNull() ?: 0
            onResult(if (width > 0) width else 0)
        }
    } catch (_: Throwable) {
        onResult(0)
    }
}

@Composable
private fun FallbackRaw(
    latex: String,
    inline: Boolean,
    modifier: Modifier = Modifier
) {
    val textStyle = if (inline) {
        MaterialTheme.typography.bodyMedium
    } else {
        MaterialTheme.typography.bodyLarge
    }
    // 回退原文（不做任何替换），保持与输入一致
    Box(
        modifier = modifier
            // 跟随主页面背景（明暗主题自动适配）；如需完全透明可改为 Color.Transparent
            .background(MaterialTheme.colorScheme.surface)
            .padding(6.dp)
    ) {
        Text(
            text = latex,
            style = textStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}