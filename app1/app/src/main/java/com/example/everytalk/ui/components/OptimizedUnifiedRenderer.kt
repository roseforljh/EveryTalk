package com.example.everytalk.ui.components

import android.annotation.SuppressLint
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.MaterialTheme
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.ui.theme.chatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 优化的统一渲染组件 - 解决Compose重组冲突
 * 
 * 优化策略：
 * 1. 智能记忆化：精确控制重组触发条件
 * 2. 状态稳定化：避免不必要的WebView重创建
 * 3. 内容缓存：复用已渲染的HTML内容
 * 4. 异步更新：避免主线程阻塞
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OptimizedUnifiedRenderer(
    message: Message,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    textColor: Color,
    stableKey: String? = null
) {
    val context = LocalContext.current
    val isDarkTheme = isSystemInDarkTheme()
    
    // 🎯 修复：使用AI气泡背景色而不是app背景色
    val aiBubbleColor = MaterialTheme.chatColors.aiBubble
    val backgroundColorInt = remember(aiBubbleColor) {
        aiBubbleColor.toArgb()
    }
    
    // 内存监控集成
    MemoryMonitorEffect()
    
    // 智能内容缓存 - 只有实际内容变化时才重新生成
    val contentHash = remember(message.parts, message.text, isDarkTheme, textColor.toArgb(), style.fontSize, aiBubbleColor.toArgb()) {
        val basis = if (message.parts.isNotEmpty()) message.parts.hashCode() else message.text.hashCode()
        "${basis}_${isDarkTheme}_${textColor.toArgb()}_${style.fontSize.value}_${aiBubbleColor.toArgb()}".hashCode()
    }
    
    // 检测是否包含表格（用于优化渲染）
    val containsTables = remember(message.parts, message.text) {
        // 已显式解析为表格的情况
        if (message.parts.any { it is MarkdownPart.Table }) return@remember true
        
        val candidateLines: List<String> = if (message.parts.isNotEmpty()) {
            message.parts.filterIsInstance<MarkdownPart.Text>()
                .flatMap { it.content.lines() }
        } else {
            message.text.lines()
        }
        val textLines = candidateLines
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains("|") }
        if (textLines.size < 2) return@remember false
        val separatorRegex = Regex("^\\|?\\s*:?[-]{3,}:?\\s*(\\|\\s*:?[-]{3,}:?\\s*)+\\|?$")
        textLines.any { separatorRegex.containsMatchIn(it) }
    }
    
    // 生成优化的HTML内容 - 使用rememberSaveable持久化
    val optimizedHtmlContent = rememberSaveable(contentHash, aiBubbleColor.toArgb()) {
        val fullContent = if (message.parts.isNotEmpty()) {
            val reconstructedContent = message.parts.joinToString("\n") { part ->
                when (part) {
                    is MarkdownPart.Text -> part.content
                    is MarkdownPart.CodeBlock -> "```" + part.language + "\n" + part.content + "\n```"
                    is MarkdownPart.MathBlock -> "$$" + part.latex + "$$"
                    is MarkdownPart.Table -> buildTableMarkdown(part.tableData)
                    else -> ""
                }
            }.trim()
            
            // 🎯 关键修复：验证parts重建的内容是否有效，如果为空则回退到message.text
            if (reconstructedContent.isNotEmpty()) {
                reconstructedContent
            } else {
                android.util.Log.w("OptimizedUnifiedRenderer", "Parts重建内容为空，回退到message.text: messageId=${message.id}")
                message.text
            }
        } else {
            // 🎯 关键回退：当 parts 未持久化（进程被杀或App重启）时，退回使用 message.text
            message.text
        }
        
        KaTeXOptimizer.createOptimizedMathHtml(
            content = fullContent,
            textColor = String.format("#%06X", 0xFFFFFF and textColor.toArgb()),
            backgroundColor = String.format("#%06X", 0xFFFFFF and aiBubbleColor.toArgb()),
            fontSize = style.fontSize.value,
            containsTables = containsTables
        )
    }
    
    // 🎯 永久化：使用message.id保证WebView实例永久稳定
    val webViewKey = remember(stableKey ?: message.id) { "permanent_webview_${stableKey ?: message.id}" }
    val webView = rememberManagedWebView(webViewKey)
    
    // 🎯 永久化：使用rememberSaveable确保跨app重启的内容持久化
    var lastRenderedHash by rememberSaveable(message.id) { mutableIntStateOf(0) }
    var isContentReady by rememberSaveable(message.id) { mutableStateOf(false) }
    var lastRenderedContent by rememberSaveable(message.id) { mutableStateOf("") }
    
    // 🎯 修复：内容持久化逻辑增强 - 跨app重启恢复
    val shouldUpdateContent = remember(contentHash, lastRenderedHash, lastRenderedContent) {
        contentHash != lastRenderedHash || lastRenderedContent.isEmpty() || lastRenderedContent != optimizedHtmlContent
    }
    
    // 内容恢复检查 - app重启后自动恢复内容
    LaunchedEffect(message.id, optimizedHtmlContent) {
        if (lastRenderedContent.isNotEmpty() && lastRenderedContent != optimizedHtmlContent) {
            android.util.Log.d("OptimizedUnifiedRenderer", "检测到内容差异，需要恢复: messageId=${message.id}")
            // 内容已改变，需要更新
            lastRenderedContent = optimizedHtmlContent
            lastRenderedHash = contentHash
        } else if (lastRenderedContent.isEmpty() && optimizedHtmlContent.isNotEmpty()) {
            android.util.Log.d("OptimizedUnifiedRenderer", "初始化内容: messageId=${message.id}")
            // 初次加载
            lastRenderedContent = optimizedHtmlContent
            lastRenderedHash = contentHash
        }
    }
    
    webView?.let { webViewInstance ->
        // 🎯 关键修复：使用LaunchedEffect稳定WebView状态，减少重组
        LaunchedEffect(webViewInstance, contentHash) {
            if (shouldUpdateContent) {
                withContext(Dispatchers.Main) {
                    isContentReady = false
                    lastRenderedHash = contentHash
                    lastRenderedContent = optimizedHtmlContent
                    
                    try {
                        webViewInstance.loadDataWithBaseURL(
                            "file:///android_asset/",
                            optimizedHtmlContent,
                            "text/html",
                            "UTF-8",
                            null
                        )
                        android.util.Log.d("OptimizedUnifiedRenderer", "LaunchedEffect加载内容，hash=$contentHash")
                    } catch (e: Exception) {
                        android.util.Log.e("OptimizedUnifiedRenderer", "LaunchedEffect加载失败", e)
                    }
                }
            }
        }
        
        AndroidView(
            factory = { 
                UnifiedWebViewManager.safelyRemoveFromParent(webViewInstance)
                webViewInstance.setBackgroundColor(backgroundColorInt)
                webViewInstance
            },
            update = { wv ->
                // 🎯 简化update逻辑，避免重复加载
                wv.setBackgroundColor(backgroundColorInt)
            },
            modifier = modifier.fillMaxWidth()
        )
        
        // 监听加载完成
        LaunchedEffect(lastRenderedHash) {
            if (lastRenderedHash != 0) {
                withContext(Dispatchers.Main) {
                    // 模拟等待渲染完成
                    kotlinx.coroutines.delay(300)
                    isContentReady = true
                    webView?.alpha = 1f
                }
            }
        }
    }
}

/**
 * 构建表格Markdown内容
 */
private fun buildTableMarkdown(tableData: TableData): String {
    if (tableData.headers.isEmpty()) return ""

    val result = StringBuilder()

    // 表格头
    result.append("| ${tableData.headers.joinToString(" | ")} |\n")

    // 分隔线 - 修复对齐
    val separatorLine = tableData.alignsString.zip(tableData.headers).joinToString(" | ") { (align, _) ->
        when (align) {
            "Center" -> ":---:"
            "Right" -> "---:"
            else -> "---"
        }
    }
    result.append("| $separatorLine |\n")

    // 表格行
    tableData.rows.forEach { row ->
        val paddedRow = row.toMutableList()
        while (paddedRow.size < tableData.headers.size) {
            paddedRow.add("")
        }
        result.append("| ${paddedRow.joinToString(" | ")} |\n")
    }

    return result.toString()
}