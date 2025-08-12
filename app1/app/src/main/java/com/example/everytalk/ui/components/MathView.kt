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
    textSize: TextUnit = 16.sp
) {
    val context = LocalContext.current
    val isDarkTheme = isSystemInDarkTheme()
    val colorHex = String.format("#%06X", 0xFFFFFF and textColor.toArgb())
    
    // 根据主题设置背景色
    val backgroundColor = if (isDarkTheme) "#1a1a1a" else "#ffffff"
    val mathTextColor = if (isDarkTheme) "#ffffff" else "#000000"
    
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                
                // 禁用长按和文本选择
                setOnLongClickListener { true }
                isLongClickable = false
                
                val displayMode = if (isDisplay) "true" else "false"
                val fontSize = textSize.value
                
                val htmlContent = """
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
                                background: $backgroundColor;
                                font-family: 'KaTeX_Main', 'Times New Roman', serif;
                            }
                            .katex {
                                color: $mathTextColor !important;
                            }
                            .katex-display {
                                margin: 0;
                                text-align: left;
                                background: $backgroundColor;
                                padding: 4px 8px;
                                border-radius: 4px;
                            }
                            .katex .base {
                                color: $mathTextColor !important;
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
                                        "\\RR": "\\mathbb{R}",
                                        "\\NN": "\\mathbb{N}",
                                        "\\ZZ": "\\mathbb{Z}",
                                        "\\QQ": "\\mathbb{Q}",
                                        "\\CC": "\\mathbb{C}"
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
                
                loadDataWithBaseURL("file:///android_asset/", htmlContent, "text/html", "UTF-8", null)
            }
        }
    )
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
    isDisplay: Boolean = false
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
            textSize = textSize
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
) {
    MathView(latex, isDisplay, textColor, modifier)
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