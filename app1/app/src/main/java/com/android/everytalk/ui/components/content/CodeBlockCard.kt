package com.android.everytalk.ui.components.content

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.everytalk.ui.theme.chatColors

/**
 * 代码块卡片组件
 *
 * 特性：
 * - 圆角边框
 * - 顶部操作栏（语言标签 + 复制/预览按钮）
 * - 代码内容区域（等宽字体）
 */
@Composable
fun CodeBlockCard(
    language: String?,
    code: String,
    modifier: Modifier = Modifier,
    onCopy: (() -> Unit)? = null,
    onPreviewRequested: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(8.dp)
    val clipboard = LocalClipboardManager.current
    val bg = MaterialTheme.chatColors.codeBlockBackground
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    
    // 规范化语言标签
    val displayLanguage = language?.trim()?.ifBlank { "CODE" }?.uppercase() ?: "CODE"
    
    // 判断是否支持预览
    val canPreview = onPreviewRequested != null && isPreviewSupported(language)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, outline, shape),
        shape = shape,
        color = Color.Transparent
    ) {
        Column {
            // 顶部操作栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2d2d2d)) // 使用深色背景以衬托白色图标
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：语言标签
                Text(
                    text = displayLanguage,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.9f), // 白色文字
                    fontFamily = FontFamily.Monospace
                )
                
                // 右侧：按钮组
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 预览按钮
                    if (canPreview) {
                        IconButton(
                            onClick = { onPreviewRequested?.invoke() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = "预览",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    
                    // 复制按钮
                    IconButton(
                        onClick = {
                            if (onCopy != null) {
                                onCopy()
                            } else {
                                clipboard.setText(AnnotatedString(code))
                            }
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "复制",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            
            // 代码内容区域
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .padding(12.dp)
            ) {
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * 检查语言是否支持 Web 预览
 */
private fun isPreviewSupported(language: String?): Boolean {
    val lang = language?.trim()?.lowercase() ?: return false
    return when (lang) {
        "html", "svg", "xml", 
        "mermaid", 
        "echarts", 
        "chartjs", 
        "flowchart", "flow", 
        "vega", "vega-lite" -> true
        else -> false
    }
}