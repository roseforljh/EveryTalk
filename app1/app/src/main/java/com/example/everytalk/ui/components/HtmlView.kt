package com.example.everytalk.ui.components

import android.annotation.SuppressLint
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender

/**
 * 🎯 重定向到统一渲染器 - 废弃旧版本HtmlView
 * 使用OptimizedUnifiedRenderer替代
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlView(
    htmlContent: String,
    modifier: Modifier = Modifier,
    textColor: Color = Color.Unspecified
) {
    // 创建临时Message用于渲染纯HTML内容
    val message = remember(htmlContent) {
        Message(
            id = "html_temp",
            text = htmlContent,
            sender = Sender.AI,
            parts = listOf(MarkdownPart.Text(id = "html_text", content = htmlContent)),
            timestamp = System.currentTimeMillis()
        )
    }
    
    OptimizedUnifiedRenderer(
        message = message,
        modifier = modifier,
        textColor = if (textColor != Color.Unspecified) textColor 
                   else androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    )
}