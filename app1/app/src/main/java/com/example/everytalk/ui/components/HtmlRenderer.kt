package com.example.everytalk.ui.components

import android.annotation.SuppressLint
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.viewinterop.AndroidView
import com.example.everytalk.data.DataClass.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 🎯 优化的HtmlRenderer - 使用统一架构解决表格卡死和公式乱闪
 * 
 * 替代原有的双重渲染系统：
 * - 集成UnifiedWebViewManager统一管理
 * - 使用KaTeXOptimizer避免双重JavaScript执行  
 * - 集成MemoryLeakGuard内存防护
 * - Compose重组优化避免不必要更新
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlRenderer(
    message: Message,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    textColor: Color,
    stableKey: String? = null
) {
    // 🚀 直接使用优化的统一渲染器
    OptimizedUnifiedRenderer(
        message = message,
        modifier = modifier,
        style = style,
        textColor = textColor,
        stableKey = stableKey ?: message.id
    )
}