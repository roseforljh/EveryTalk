package com.example.everytalk.ui.components

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.BasicText
import dev.jeziellago.compose.markdowntext.MarkdownText

/**
 * 内联数学公式渲染器
 * 
 * 只对数学公式使用小型WebView，其他内容全部使用MarkdownText
 */
@Composable
fun InlineMathRenderer(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    modifier: Modifier = Modifier
) {
    val mathFormulas = remember(text) { extractMathFormulas(text) }
    
    if (mathFormulas.isEmpty()) {
        // 没有数学公式，直接使用MarkdownText
        MarkdownText(
            markdown = normalizeBasicMarkdown(text),
            style = style.copy(color = color),
            modifier = modifier
        )
    } else {
        // 有数学公式，需要特殊处理
        MathAwareMarkdown(
            text = text,
            mathFormulas = mathFormulas,
            style = style,
            color = color,
            modifier = modifier
        )
    }
}

/**
 * 处理包含数学公式的Markdown文本
 */
@Composable
private fun MathAwareMarkdown(
    text: String,
    mathFormulas: List<MathFormula>,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    // 将文本分割为普通文本和数学公式部分
    val segments = remember(text, mathFormulas) {
        splitTextWithMath(text, mathFormulas)
    }
    
    Column(modifier = modifier) {
        segments.forEach { segment ->
            when (segment) {
                is TextSegment.PlainText -> {
                    if (segment.content.isNotBlank()) {
                        MarkdownText(
                            markdown = normalizeBasicMarkdown(segment.content),
                            style = style.copy(color = color),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                is TextSegment.MathFormula -> {
                    MiniMathWebView(
                        formula = MathFormula(
                            content = segment.content,
                            type = segment.type,
                            startIndex = segment.startIndex,
                            endIndex = segment.endIndex
                        ),
                        style = style,
                        color = color,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * 迷你数学公式WebView
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MiniMathWebView(
    formula: MathFormula,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val textColorHex = remember(color) { 
        String.format("#%06X", 0xFFFFFF and color.toArgb()) 
    }
    
    val htmlContent = remember(formula, textColorHex, style.fontSize) {
        createMathHtml(formula, textColorHex, style.fontSize.value)
    }
    
    AndroidView(
        factory = { ctx ->
            android.webkit.WebView(ctx.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                
                // 禁用缩放和交互
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                isLongClickable = false
                setOnLongClickListener { false }
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(
                "file:///android_asset/",
                htmlContent,
                "text/html",
                "UTF-8",
                null
            )
        },
        modifier = modifier.height(IntrinsicSize.Min)
    )
}

/**
 * 创建数学公式的HTML内容
 */
private fun createMathHtml(formula: MathFormula, textColor: String, fontSize: Float): String {
    val mathContent = when (formula.type) {
        MathType.INLINE -> "\\(${formula.content}\\)"
        MathType.BLOCK -> "\\[${formula.content}\\]"
    }
    
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <script src="file:///android_asset/katex.min.js"></script>
            <link rel="stylesheet" href="file:///android_asset/katex.min.css">
            <style>
                body {
                    margin: 0;
                    padding: 4px;
                    font-size: ${fontSize}px;
                    color: $textColor;
                    background: transparent;
                    line-height: 1.4;
                }
                .katex { color: $textColor !important; }
            </style>
        </head>
        <body>
            <div id="math">$mathContent</div>
            <script>
                try {
                    katex.render("${formula.content}", document.getElementById('math'), {
                        throwOnError: false,
                        displayMode: ${formula.type == MathType.BLOCK}
                    });
                } catch(e) {
                    document.getElementById('math').textContent = '$mathContent';
                }
            </script>
        </body>
        </html>
    """.trimIndent()
}

/**
 * 数学公式类型
 */
enum class MathType {
    INLINE,  // 行内公式 $...$
    BLOCK    // 块级公式 $$...$$
}

/**
 * 数学公式数据类
 */
data class MathFormula(
    val content: String,
    val type: MathType,
    val startIndex: Int,
    val endIndex: Int
)

/**
 * 文本段落类型
 */
sealed class TextSegment {
    data class PlainText(val content: String) : TextSegment()
    data class MathFormula(
        val content: String,
        val type: MathType,
        val startIndex: Int,
        val endIndex: Int
    ) : TextSegment()
}

/**
 * 提取文本中的数学公式
 */
private fun extractMathFormulas(text: String): List<MathFormula> {
    val formulas = mutableListOf<MathFormula>()
    
    // 提取块级公式 $$...$$
    val blockMathRegex = Regex("""\$\$([^$]+?)\$\$""")
    blockMathRegex.findAll(text).forEach { match ->
        formulas.add(
            MathFormula(
                content = match.groupValues[1].trim(),
                type = MathType.BLOCK,
                startIndex = match.range.first,
                endIndex = match.range.last + 1
            )
        )
    }
    
    // 提取行内公式 $...$（但排除已经被$$包含的部分）
    val inlineMathRegex = Regex("""\$([^$\n]+?)\$""")
    inlineMathRegex.findAll(text).forEach { match ->
        val isInsideBlockMath = formulas.any { blockFormula ->
            match.range.first >= blockFormula.startIndex && match.range.last < blockFormula.endIndex
        }
        
        if (!isInsideBlockMath) {
            formulas.add(
                MathFormula(
                    content = match.groupValues[1].trim(),
                    type = MathType.INLINE,
                    startIndex = match.range.first,
                    endIndex = match.range.last + 1
                )
            )
        }
    }
    
    return formulas.sortedBy { it.startIndex }
}

/**
 * 将文本分割为普通文本和数学公式段落
 */
private fun splitTextWithMath(text: String, mathFormulas: List<MathFormula>): List<TextSegment> {
    if (mathFormulas.isEmpty()) {
        return listOf(TextSegment.PlainText(text))
    }
    
    val segments = mutableListOf<TextSegment>()
    var lastIndex = 0
    
    mathFormulas.forEach { formula ->
        // 添加数学公式前的普通文本
        if (lastIndex < formula.startIndex) {
            val plainText = text.substring(lastIndex, formula.startIndex)
            if (plainText.isNotBlank()) {
                segments.add(TextSegment.PlainText(plainText))
            }
        }
        
        // 添加数学公式
        segments.add(TextSegment.MathFormula(
            content = formula.content,
            type = formula.type,
            startIndex = formula.startIndex,
            endIndex = formula.endIndex
        ))
        lastIndex = formula.endIndex
    }
    
    // 添加最后的普通文本
    if (lastIndex < text.length) {
        val plainText = text.substring(lastIndex)
        if (plainText.isNotBlank()) {
            segments.add(TextSegment.PlainText(plainText))
        }
    }
    
    return segments
}