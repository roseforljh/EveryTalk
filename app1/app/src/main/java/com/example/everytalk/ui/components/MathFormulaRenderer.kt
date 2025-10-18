package com.example.everytalk.ui.components

import android.webkit.WebView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.jeziellago.compose.markdowntext.MarkdownText

/**
 * 🎯 数学公式渲染器
 * 使用 WebView + KaTeX 渲染 LaTeX 数学公式
 */

/**
 * 数学公式数据类
 */
data class MathFormula(
    val latex: String,
    val isBlock: Boolean  // true=块级公式 $$...$$，false=行内公式 $...$
)

/**
 * 解析文本中的数学公式
 * 返回：(处理后的文本, 公式列表)
 */
fun extractMathFormulas(text: String): Pair<String, List<MathFormula>> {
    val formulas = mutableListOf<MathFormula>()
    var processedText = text
    
    // 1. 提取块级公式 $$...$$
    val blockMathRegex = Regex("""\$\$(.*?)\$\$""", RegexOption.DOT_MATCHES_ALL)
    blockMathRegex.findAll(text).forEach { match ->
        val latex = match.groupValues[1].trim()
        formulas.add(MathFormula(latex, isBlock = true))
        processedText = processedText.replaceFirst(
            match.value,
            "\n{{MATH_FORMULA_${formulas.size - 1}}}\n"
        )
    }
    
    // 2. 提取行内公式 $...$
    val inlineMathRegex = Regex("""\$([^\$\n]+)\$""")
    inlineMathRegex.findAll(processedText).forEach { match ->
        val latex = match.groupValues[1].trim()
        formulas.add(MathFormula(latex, isBlock = false))
        processedText = processedText.replaceFirst(
            match.value,
            "{{MATH_FORMULA_${formulas.size - 1}}}"
        )
    }
    
    return Pair(processedText, formulas)
}

/**
 * 检查文本是否包含数学公式
 * 排除内联代码中的 $ 符号（` $var$ `）
 */
fun hasMathFormulas(text: String): Boolean {
    // 先移除内联代码，避免误判
    val withoutInlineCode = text.replace(Regex("`[^`]+`"), "")
    
    // 检查是否包含数学公式 $...$ 或 $$...$$
    return withoutInlineCode.contains(Regex("""\$\$[\s\S]+?\$\$""")) || 
           withoutInlineCode.contains(Regex("""\$[^\$\n]+\$"""))
}

/**
 * 渲染包含数学公式的内容
 */
@Composable
fun ContentWithMathFormulas(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified
) {
    val isDark = isSystemInDarkTheme()
    val textColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> MaterialTheme.colorScheme.onSurface
    }
    
    // 提取数学公式
    val (processedText, formulas) = remember(text) {
        extractMathFormulas(text)
    }
    
    Column(modifier = modifier.fillMaxWidth()) {
        // 按占位符分割并渲染
        val parts = processedText.split(Regex("""(\{\{MATH_FORMULA_\d+\}\})"""))
        
        // 🎯 自定义内联代码样式
        val codeBackgroundColor = if (isDark) {
            Color(0xFF2D2D2D)
        } else {
            Color(0xFFF5F5F5)
        }
        
        val codeTextColor = if (isDark) {
            Color(0xFFE06C75)
        } else {
            Color(0xFFD73A49)
        }
        
        parts.forEach { part ->
            when {
                part.startsWith("{{MATH_FORMULA_") -> {
                    // 渲染数学公式
                    val index = part.removeSurrounding("{{MATH_FORMULA_", "}}").toIntOrNull()
                    if (index != null && index < formulas.size) {
                        val formula = formulas[index]
                        MathFormulaView(
                            latex = formula.latex,
                            isBlock = formula.isBlock,
                            isDark = isDark,
                            textColor = textColor
                        )
                    }
                }
                part.isNotBlank() -> {
                    // 🎯 直接使用 MarkdownText 渲染，避免递归调用
                    dev.jeziellago.compose.markdowntext.MarkdownText(
                        markdown = part,
                        style = style,
                        modifier = Modifier.fillMaxWidth(),
                        syntaxHighlightColor = codeBackgroundColor,
                        syntaxHighlightTextColor = codeTextColor
                    )
                }
            }
        }
    }
}

/**
 * 使用 WebView 渲染单个数学公式
 */
@Composable
fun MathFormulaView(
    latex: String,
    isBlock: Boolean,
    isDark: Boolean,
    textColor: Color
) {
    val bgColor = if (isDark) "#1E1E1E" else "#FFFFFF"
    val fgColor = String.format("#%06X", 0xFFFFFF and textColor.toArgb())
    
    val html = remember(latex, isBlock, isDark) {
        """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.8/dist/katex.min.css">
            <script src="https://cdn.jsdelivr.net/npm/katex@0.16.8/dist/katex.min.js"></script>
            <style>
                body {
                    margin: 0;
                    padding: 8px;
                    background-color: $bgColor;
                    color: $fgColor;
                    font-size: 16px;
                    ${if (isBlock) "text-align: center;" else ""}
                }
                .katex { font-size: 1.1em; }
                .katex-display { margin: 0; }
            </style>
        </head>
        <body>
            <div id="math"></div>
            <script>
                try {
                    katex.render(${latex.replace("\"", "\\\"").let { "\"$it\"" }}, document.getElementById('math'), {
                        displayMode: $isBlock,
                        throwOnError: false,
                        trust: true
                    });
                } catch(e) {
                    document.getElementById('math').textContent = 'Error: ' + e.message;
                }
            </script>
        </body>
        </html>
        """.trimIndent()
    }
    
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(
                null,
                html,
                "text/html",
                "UTF-8",
                null
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isBlock) 80.dp else 40.dp)
    )
}

