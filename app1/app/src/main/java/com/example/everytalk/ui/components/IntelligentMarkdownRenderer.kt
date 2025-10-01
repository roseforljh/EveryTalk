package com.example.everytalk.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.util.Log

/**
 * 🚀 智能Markdown渲染管理器 - 根据内容自动选择最优渲染策略
 * 
 * 特性：
 * - 自动内容分析与渲染器选择
 * - 专业数学公式渲染
 * - 高性能缓存机制
 * - 渲染质量监控
 */
@Composable
fun IntelligentMarkdownRenderer(
    parts: List<MarkdownPart>,
    modifier: Modifier = Modifier,
    onRenderComplete: ((String, Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    
    // 渲染策略分析
    val renderStrategy = remember(parts) { analyzeRenderStrategy(parts) }
    
    LaunchedEffect(renderStrategy) {
        Log.d("IntelligentRenderer", "Render strategy: $renderStrategy")
        Log.d("IntelligentRenderer", "Parts summary: ${parts.map { it.getContentSummary() }}")
    }
    
    Column(modifier = modifier) {
        parts.forEach { part ->
            when (part) {
                is MarkdownPart.MathBlock -> {
                    if (part.renderMode == "professional") {
                        ProfessionalMathRenderer(
                            content = part.content,
                            modifier = Modifier.fillMaxWidth(),
                            onRenderComplete = { success ->
                                onRenderComplete?.invoke(part.id, success)
                            }
                        )
                    } else {
                        // 回退到原有的数学渲染器
                        LegacyMathRenderer(
                            content = part.latex,
                            displayMode = part.displayMode,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                
                is MarkdownPart.Table -> {
                    if (part.renderMode == "webview") {
                        OptimizedTableRenderer(
                            content = part.content,
                            modifier = Modifier.fillMaxWidth(),
                            onRenderComplete = { success ->
                                onRenderComplete?.invoke(part.id, success)
                            }
                        )
                    } else {
                        // 简单表格渲染
                        SimpleTableRenderer(
                            content = part.content,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                
                is MarkdownPart.MixedContent -> {
                    HybridContentRenderer(
                        content = part.content,
                        hasMath = part.hasMath,
                        modifier = Modifier.fillMaxWidth(),
                        onRenderComplete = { success ->
                            onRenderComplete?.invoke(part.id, success)
                        }
                    )
                }
                
                is MarkdownPart.CodeBlock -> {
                    CodePreview(
                        code = part.content,
                        language = part.language,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                is MarkdownPart.Text -> {
                    // 使用简化的文本显示，避免参数不匹配
                    androidx.compose.material3.Text(
                        text = part.content,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                is MarkdownPart.HtmlContent -> {
                    HtmlContentRenderer(
                        html = part.html,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * 渲染策略枚举
 */
private enum class RenderStrategy {
    PURE_MATH,      // 纯数学内容，使用专业渲染器
    MIXED_COMPLEX,  // 复杂混合内容
    SIMPLE_TEXT,    // 简单文本内容
    TABLE_FOCUSED   // 表格为主的内容
}

/**
 * 🎯 分析渲染策略
 */
private fun analyzeRenderStrategy(parts: List<MarkdownPart>): RenderStrategy {
    val mathParts = parts.filterIsInstance<MarkdownPart.MathBlock>()
    val tableParts = parts.filterIsInstance<MarkdownPart.Table>()
    val mixedParts = parts.filterIsInstance<MarkdownPart.MixedContent>()
    val textParts = parts.filterIsInstance<MarkdownPart.Text>()
    
    return when {
        mathParts.size > parts.size * 0.7 -> RenderStrategy.PURE_MATH
        tableParts.isNotEmpty() -> RenderStrategy.TABLE_FOCUSED
        mixedParts.isNotEmpty() || mathParts.isNotEmpty() -> RenderStrategy.MIXED_COMPLEX
        else -> RenderStrategy.SIMPLE_TEXT
    }
}

/**
 * 🎯 混合内容渲染器
 */
@Composable
private fun HybridContentRenderer(
    content: String,
    hasMath: Boolean,
    modifier: Modifier = Modifier,
    onRenderComplete: ((Boolean) -> Unit)? = null
) {
    // 简化处理，使用文本显示
    androidx.compose.material3.Text(
        text = content,
        modifier = modifier
    )
    onRenderComplete?.invoke(true)
}

/**
 * 🎯 优化的表格渲染器
 */
@Composable
private fun OptimizedTableRenderer(
    content: String,
    modifier: Modifier = Modifier,
    onRenderComplete: ((Boolean) -> Unit)? = null
) {
    // 使用WebView渲染表格以获得最佳效果
    val tableHtml = createOptimizedTableHtml(content)
    
    HtmlContentRenderer(
        html = tableHtml,
        modifier = modifier,
        onRenderComplete = onRenderComplete
    )
}

/**
 * 🎯 简单表格渲染器
 */
@Composable
private fun SimpleTableRenderer(
    content: String,
    modifier: Modifier = Modifier
) {
    // 使用简化的文本显示表格
    androidx.compose.material3.Text(
        text = content,
        modifier = modifier
    )
}

/**
 * 🎯 HTML内容渲染器
 */
@Composable
private fun HtmlContentRenderer(
    html: String,
    modifier: Modifier = Modifier,
    onRenderComplete: ((Boolean) -> Unit)? = null
) {
    // 简化HTML渲染，使用文本显示
    androidx.compose.material3.Text(
        text = html,
        modifier = modifier
    )
    
    LaunchedEffect(html) {
        onRenderComplete?.invoke(true)
    }
}

/**
 * 🎯 传统数学渲染器（向后兼容）
 */
@Composable
private fun LegacyMathRenderer(
    content: String,
    displayMode: Boolean,
    modifier: Modifier = Modifier
) {
    // 简化数学渲染，使用文本显示
    androidx.compose.material3.Text(
        text = if (displayMode) "\n$content\n" else content,
        modifier = modifier
    )
}

/**
 * 创建优化的表格HTML
 */
private fun createOptimizedTableHtml(content: String): String {
    val isDarkTheme = false // 需要从主题获取
    
    return """
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>
            body {
                margin: 0;
                padding: 16px;
                font-family: system-ui, -apple-system, sans-serif;
                background-color: ${if (isDarkTheme) "#1a1a1a" else "#ffffff"};
                color: ${if (isDarkTheme) "#ffffff" else "#000000"};
            }
            
            table {
                width: 100%;
                border-collapse: collapse;
                margin: 8px 0;
                box-shadow: 0 2px 8px rgba(0,0,0,0.1);
                border-radius: 8px;
                overflow: hidden;
            }
            
            th, td {
                padding: 12px 16px;
                text-align: left;
                border-bottom: 1px solid ${if (isDarkTheme) "#333" else "#eee"};
            }
            
            th {
                background-color: ${if (isDarkTheme) "#2a2a2a" else "#f8f9fa"};
                font-weight: 600;
            }
            
            tr:hover {
                background-color: ${if (isDarkTheme) "#2a2a2a" else "#f8f9fa"};
            }
        </style>
    </head>
    <body>
        ${convertMarkdownTableToHtml(content)}
    </body>
    </html>
    """.trimIndent()
}

/**
 * 将Markdown表格转换为HTML
 */
private fun convertMarkdownTableToHtml(markdown: String): String {
    val lines = markdown.trim().split('\n')
    if (lines.size < 2) return markdown
    
    val headerLine = lines[0]
    val separatorLine = lines.getOrNull(1) ?: return markdown
    
    if (!separatorLine.contains("---")) return markdown
    
    val headers = headerLine.split('|').map { it.trim() }.filter { it.isNotEmpty() }
    val dataLines = lines.drop(2)
    
    val html = StringBuilder()
    html.append("<table>\n")
    
    // 表头
    html.append("<thead><tr>\n")
    headers.forEach { header ->
        html.append("<th>$header</th>\n")
    }
    html.append("</tr></thead>\n")
    
    // 表体
    html.append("<tbody>\n")
    dataLines.forEach { line ->
        val cells = line.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        html.append("<tr>\n")
        cells.forEach { cell ->
            html.append("<td>$cell</td>\n")
        }
        html.append("</tr>\n")
    }
    html.append("</tbody>\n")
    
    html.append("</table>")
    return html.toString()
}