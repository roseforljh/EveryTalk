package com.example.everytalk.ui.components.coordinator
import com.example.everytalk.ui.components.markdown.MarkdownRenderer

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.example.everytalk.ui.components.math.MathAwareText
import com.example.everytalk.ui.components.table.TableAwareText
import com.example.everytalk.ui.components.table.TableUtils

/**
 * 内容协调器（搬迁版）
 * 原文件位置：ui/components/ContentCoordinator.kt
 * 说明：统一调度表格/数学/纯文本渲染；提供递归深度保护。
 */
@Composable
fun ContentCoordinator(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
    recursionDepth: Int = 0
) {
    // 🛡️ 防止无限递归：超过3层直接渲染
    if (recursionDepth > 3) {
        android.util.Log.w(
            "ContentCoordinator",
            "递归深度超限($recursionDepth)，直接渲染以避免ANR"
        )
        MarkdownRenderer(
            markdown = text,
            style = style,
            color = color,
            modifier = modifier.fillMaxWidth(),
            isStreaming = isStreaming
        )
        return
    }
    
    // 🎯 优先级1：检测表格
    val hasTable = text.contains("|") && text.lines().any { line ->
        TableUtils.isTableLine(line)
    }
    
    if (hasTable) {
        TableAwareText(
            text = text,
            style = style,
            color = color,
            isStreaming = isStreaming,
            modifier = modifier,
            recursionDepth = recursionDepth
        )
        return
    }
    
    // 🎯 优先级2：检测数学公式（粗略检测，以 $ 为信号）
    val hasMath = text.contains("$")
    if (hasMath) {
        MathAwareText(
            text = text,
            style = style,
            color = color,
            isStreaming = isStreaming,
            modifier = modifier,
            recursionDepth = recursionDepth
        )
        return
    }
    
    // 🎯 优先级3：纯文本
    MarkdownRenderer(
        markdown = text,
        style = style,
        color = color,
        modifier = modifier.fillMaxWidth(),
        isStreaming = isStreaming
    )
}
