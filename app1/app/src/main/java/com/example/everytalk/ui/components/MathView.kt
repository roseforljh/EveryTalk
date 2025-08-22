package com.example.everytalk.ui.components

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * 使用KaTeX的数学公式渲染组件
 * 支持完整的LaTeX数学公式渲染
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MathView(
    latex: String,
    isDisplay: Boolean,
    textColor: Color,
    modifier: Modifier = Modifier,
    textSize: TextUnit = 16.sp,
    delayMs: Long = 0L
) {
    val context = LocalContext.current
    val isDarkTheme = isSystemInDarkTheme()
    val colorHex = String.format("#%06X", 0xFFFFFF and textColor.toArgb())
    
    // 设置完全透明的背景色，避免大白块问题
    val backgroundColor = "transparent"
    // 使用传入的textColor参数，而不是硬编码
    val mathTextColor = colorHex
    
    // 延迟渲染状态
    var shouldRender by remember { mutableStateOf(delayMs == 0L) }
    
    // 延迟渲染逻辑
    LaunchedEffect(latex, delayMs) {
        if (delayMs > 0L) {
            shouldRender = false
            delay(delayMs)
            shouldRender = true
        } else {
            // 当delayMs为0时，立即显示
            shouldRender = true
        }
    }
    
    // 使用记忆化来避免重复创建相同的HTML内容
    val htmlContent = remember(latex, isDisplay, mathTextColor, backgroundColor, textSize.value) {
        createMathHtmlContent(latex, isDisplay, mathTextColor, backgroundColor, textSize.value)
    }
    
    if (shouldRender) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            // 页面开始加载时保持透明，避免闪白
                            view?.alpha = 0f
                        }
                        
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // 页面加载完成后立即显示，无过渡动画
                            view?.alpha = 1f
                        }
                    }
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.setSupportZoom(false)
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    
                    // 启用水平滚动
                    isHorizontalScrollBarEnabled = true
                    isVerticalScrollBarEnabled = false
                    
                    // 禁用长按和文本选择
                    setOnLongClickListener { true }
                    isLongClickable = false
                    
                    // 确保触摸事件不被拦截
                    requestDisallowInterceptTouchEvent(false)
                    
                    // 设置完全透明的背景色，避免大白块问题
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    
                    // 初始设置透明度为0，减少闪白
                    alpha = 0f
                    
                    loadDataWithBaseURL("file:///android_asset/", htmlContent, "text/html", "UTF-8", null)
                }
            },
            update = { webView ->
                // 只有当内容真正改变时才重新加载
                if (webView.tag != htmlContent.hashCode()) {
                    webView.tag = htmlContent.hashCode()
                    webView.alpha = 0f
                    webView.loadDataWithBaseURL("file:///android_asset/", htmlContent, "text/html", "UTF-8", null)
                }
            }
        )
    }
}

private fun createMathHtmlContent(
    latex: String,
    isDisplay: Boolean,
    mathTextColor: String,
    backgroundColor: String,
    fontSize: Float
): String {
    val displayMode = if (isDisplay) "true" else "false"
    
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <link rel="stylesheet" href="file:///android_asset/katex.min.css">
            <script src="file:///android_asset/katex.min.js"></script>
            <script src="file:///android_asset/auto-render.min.js"></script>
            <style>
                body {
                    margin: 0;
                    padding: 8px;
                    font-size: ${fontSize}px;
                    color: $mathTextColor;
                    background: transparent;
                    font-family: 'KaTeX_Main', 'Times New Roman', serif;
                    width: 100%;
                    max-width: 100%;
                    box-sizing: border-box;
                    overflow-x: visible;
                }
                .katex {
                    color: $mathTextColor !important;
                    background: transparent !important;
                    display: inline-block;
                    max-width: 100%;
                }
                .katex * {
                    background: transparent !important;
                    color: inherit !important;
                }
                .katex-display {
                    margin: 0;
                    text-align: left;
                    background: transparent;
                    padding: 4px 8px;
                    border-radius: 4px;
                    display: block;
                    max-width: 100%;
                    overflow-x: visible;
                    white-space: normal;
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                }
                .katex .base {
                    color: $mathTextColor !important;
                }
                /* 容器宽度限制 */
                html, body {
                    width: 100%;
                    max-width: 100%;
                    overflow-x: visible;
                }
                /* 优化滚动条样式 */
                ::-webkit-scrollbar {
                    height: 4px;
                }
                ::-webkit-scrollbar-track {
                    background: transparent;
                }
                ::-webkit-scrollbar-thumb {
                    background: rgba(128, 128, 128, 0.3);
                    border-radius: 2px;
                }
                ::-webkit-scrollbar-thumb:hover {
                    background: rgba(128, 128, 128, 0.5);
                }
            </style>
        </head>
        <body>
            <div id="math-content"></div>
            <script>
                try {
                    const mathContent = document.getElementById('math-content');
                    const latex = `${latex.replace("`", "\\`").replace("$", "\\$")}`;
                    
                    katex.render(latex, mathContent, {
                        displayMode: $displayMode,
                        throwOnError: false,
                        errorColor: '$mathTextColor',
                        macros: {
                            "\\\\RR": "\\\\mathbb{R}",
                            "\\\\NN": "\\\\mathbb{N}",
                            "\\\\ZZ": "\\\\mathbb{Z}",
                            "\\\\QQ": "\\\\mathbb{Q}",
                            "\\\\CC": "\\\\mathbb{C}"
                        }
                    });
                    
                    // 强制设置所有数学元素的颜色
                    const allMathElements = mathContent.querySelectorAll('*');
                    allMathElements.forEach(el => {
                        el.style.color = '$mathTextColor';
                    });
                } catch (error) {
                    document.getElementById('math-content').innerHTML = 
                        '<span style="color: red;">Math rendering error: ' + error.message + '</span>';
                }
            </script>
        </body>
        </html>
    """.trimIndent()
}

/**
 * 简化版数学公式组件，用于简单的数学表达式
 * 当KaTeX不可用时的后备方案
 */
@Composable
fun SimpleMathView(
    expression: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    textSize: TextUnit = 14.sp
) {
    Text(
        text = formatMathExpression(expression),
        modifier = modifier,
        color = textColor,
        fontSize = textSize
    )
}

/**
 * 智能数学公式组件 - 根据表达式复杂度自动选择渲染方式
 */
@Composable
fun SmartMathView(
    expression: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    textSize: TextUnit = 16.sp,
    isDisplay: Boolean = false,
    delayMs: Long = 0L
) {
    // 检查是否包含复杂的LaTeX语法
    val hasComplexMath = expression.contains(Regex("\\\\(frac|sqrt|sum|int|lim|prod|binom|matrix)"))
    
    if (hasComplexMath) {
        // 使用KaTeX渲染复杂公式
        MathView(
            latex = expression,
            isDisplay = isDisplay,
            textColor = textColor,
            modifier = modifier,
            textSize = textSize,
            delayMs = delayMs
        )
    } else {
        // 使用简单文本替换
        SimpleMathView(
            expression = expression,
            modifier = modifier,
            textColor = textColor,
            textSize = textSize
        )
    }
}

/**
 * 向后兼容的旧版本API别名
 */
@Composable
fun WebMathView(
    latex: String,
    isDisplay: Boolean,
    textColor: Color,
    modifier: Modifier = Modifier,
    delayMs: Long = 0L
) {
    MathView(latex, isDisplay, textColor, modifier, delayMs = delayMs)
}

/**
 * 格式化数学表达式，进行基本的符号替换
 * 用于SimpleMathView的后备渲染
 */
private fun formatMathExpression(latex: String): String {
    return latex
        .replace("\\alpha", "α")
        .replace("\\beta", "β")
        .replace("\\gamma", "γ")
        .replace("\\delta", "δ")
        .replace("\\epsilon", "ε")
        .replace("\\theta", "θ")
        .replace("\\lambda", "λ")
        .replace("\\mu", "μ")
        .replace("\\pi", "π")
        .replace("\\sigma", "σ")
        .replace("\\phi", "φ")
        .replace("\\omega", "ω")
        .replace("\\infty", "∞")
        .replace("\\pm", "±")
        .replace("\\times", "×")
        .replace("\\div", "÷")
        .replace("\\leq", "≤")
        .replace("\\geq", "≥")
        .replace("\\neq", "≠")
        .replace("\\approx", "≈")
        .replace("\\rightarrow", "→")
        .replace("\\leftarrow", "←")
        .replace("\\leftrightarrow", "↔")
        .replace("\\sum", "Σ")
        .replace("\\int", "∫")
        .replace("\\sqrt", "√")
        .replace("\\frac", "/")
        .replace("{", "")
        .replace("}", "")
        .replace("$", "")
}