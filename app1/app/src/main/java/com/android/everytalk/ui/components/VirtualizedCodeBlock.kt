package com.android.everytalk.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.everytalk.util.PerformanceMonitor

/**
 * VirtualizedCodeBlock - 针对超大代码块的虚拟化渲染组件
 * 仅渲染可见行，显著降低测量/布局开销，提升滚动流畅度
 *
 * 适用场景：
 * - 行数很大（例如 200+）
 * - 或单行很长导致宽度/测量极慢
 *
 * 注意：
 * - 与 CodeBlock 的外观保持一致（字体/间距），在父组件中控制背景与边距
 */
@Composable
fun VirtualizedCodeBlock(
    code: String,
    lineHeight: Int = 18,            // px 等效，用于视觉统一
    maxHeightDp: Int = 600,          // 容器最大高度
    textColor: Color = Color.Unspecified
) {
    val lines = remember(code) { code.split('\n') }
    PerformanceMonitor.recordVirtualizedRender("CodeBlock", lines.size, 0)

    // 外层允许水平滚动（针对超长行）
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeightDp.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        // 内部使用 LazyColumn 按行渲染（仅渲染可见内容）
        LazyColumn(
            modifier = Modifier
                .wrapContentWidth()
                .background(Color.Transparent)
        ) {
            itemsIndexed(lines) { index, line ->
                Text(
                    text = line,
                    modifier = Modifier
                        .wrapContentWidth()
                        .padding(horizontal = 12.dp)
                        .padding(top = if (index == 0) 8.dp else 0.dp, bottom = if (index == lines.lastIndex) 8.dp else 0.dp),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = lineHeight.sp
                    ),
                    color = textColor.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}