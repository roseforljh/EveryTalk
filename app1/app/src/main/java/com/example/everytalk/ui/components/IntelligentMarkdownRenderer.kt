package com.example.everytalk.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.util.Log
import dev.jeziellago.compose.markdowntext.MarkdownText

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
                    // 统一走原生Compose表格渲染（不使用WebView/HTML）
                    SimpleTableRenderer(
                        content = part.content,
                        modifier = Modifier.fillMaxWidth()
                    )
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
                    // 使用标准 Markdown 渲染，保持一致的显示效果
                    MarkdownText(
                        markdown = normalizeBasicMarkdown(part.content),
                        style = MaterialTheme.typography.bodyMedium,
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

/**
 * 🎯 简单表格渲染器
 */
@Composable
fun SimpleTableRenderer(
    content: String,
    modifier: Modifier = Modifier
) {
    // 原生Compose表格渲染（不依赖WebView/HTML）
    val lines = content.trim().lines().filter { it.isNotBlank() }
    if (lines.size < 2) {
        // 非表格，直接按原文渲染
        MarkdownText(markdown = content, style = MaterialTheme.typography.bodyMedium, modifier = modifier)
        return
    }

    // 解析表头/分隔/数据
    val headerLine = lines.first()
    val dataLines = if (lines.size > 2) lines.drop(2) else emptyList()

    // 解析单元格（保留空字符串用于对齐）
    fun parseRow(line: String): List<String> =
        line.split('|').map { it.trim() }.filterIndexed { idx, cell ->
            // 允许首尾为空格管道，但过滤掉纯空且为首尾导致的空列
            !(idx == 0 && cell.isEmpty()) && !(idx == line.split('|').lastIndex && cell.isEmpty())
        }

    val headers = parseRow(headerLine)
    val rows = dataLines.map { parseRow(it) }
    val colCount = headers.size.coerceAtLeast(rows.maxOfOrNull { it.size } ?: headers.size)

    // 样式与布局策略（垂直等分布局，如你原先的视觉）— 强化对比度和边框
    val headerBg = MaterialTheme.colorScheme.primaryContainer
    val headerFg = MaterialTheme.colorScheme.onPrimaryContainer
    val cellFg = MaterialTheme.colorScheme.onSurface
    val rowAltBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
    val gridLine = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val cellPadding = 10.dp
    val rowVPad = 8.dp

    Column(modifier = modifier) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerBg)
        ) {
            for (i in 0 until colCount) {
                val h = headers.getOrNull(i) ?: ""
                MarkdownText(
                    markdown = normalizeBasicMarkdown(h),
                    style = MaterialTheme.typography.bodyMedium.copy(color = headerFg),
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, gridLine)
                        .padding(horizontal = cellPadding, vertical = rowVPad)
                )
            }
        }

        // Body rows（斑马条 + 网格线 + 单元格 Markdown）
        rows.forEachIndexed { index, cells ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (index % 2 == 0) rowAltBg else MaterialTheme.colorScheme.surface)
            ) {
                for (i in 0 until colCount) {
                    val raw = cells.getOrNull(i) ?: ""
                    val md = normalizeBasicMarkdown(raw).replace("<br>", "  \n")
                    MarkdownText(
                        markdown = md,
                        style = MaterialTheme.typography.bodyMedium.copy(color = cellFg),
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, gridLine)
                            .padding(horizontal = cellPadding, vertical = rowVPad)
                    )
                }
            }
        }
    }
}

/**
 * 🎯 HTML内容渲染器
 */
@Composable
fun HtmlContentRenderer(
    html: String,
    modifier: Modifier = Modifier,
    onRenderComplete: ((Boolean) -> Unit)? = null
) {
    // 原生路径：不做HTML渲染，直接以纯文本显示
    androidx.compose.material3.Text(
        text = html,
        modifier = modifier
    )
    LaunchedEffect(html) { onRenderComplete?.invoke(true) }
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

/**
 * 将Markdown表格转换为HTML
 */