package com.example.everytalk.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 自定义代码块组件
 *
 * 特性：
 * - 完美自适应宽度：代码多宽容器就多宽
 * - 丝滑水平滚动：超出屏幕时自动启用滚动
 * - 左上角：语言类型标签
 * - 右上角：复制按钮
 * - 圆角设计，适配黑白天模式
 *
 * 与表格/数学公式使用相同的滚动策略
 */
@Composable
fun CodeBlock(
    code: String,
    modifier: Modifier = Modifier,
    language: String? = null,
    textColor: Color = Color.Unspecified,
    enableHorizontalScroll: Boolean = true,
    maxHeight: Int = 600
) {
    val clipboard = LocalClipboardManager.current
    val isDark = isSystemInDarkTheme()
    
    // 🎨 背景色适配黑白天模式
    val codeBlockBg = if (isDark) {
        Color(0xFF1E1E1E)  // 深色模式：深灰色背景
    } else {
        Color(0xFFF5F5F5)  // 浅色模式：浅灰色背景
    }
    
    // 🎨 文本颜色
    val resolvedTextColor = when {
        textColor != Color.Unspecified -> textColor
        isDark -> Color(0xFFD4D4D4)  // 深色模式：浅灰文本
        else -> Color(0xFF1E1E1E)     // 浅色模式：深灰文本
    }
    
    // 🎨 语言标签颜色（绿色系）
    val languageLabelColor = if (isDark) {
        Color(0xFF4EC9B0)  // 深色模式：青绿色
    } else {
        Color(0xFF22863A)  // 浅色模式：深绿色
    }
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = codeBlockBg,
                shape = RoundedCornerShape(12.dp)  // 🎯 圆角
            )
            .padding(2.dp)  // 外边距
    ) {
        // 🎯 顶部栏：左上角语言类型 + 右上角复制按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左上角：语言类型
            Text(
                text = language?.uppercase() ?: "CODE",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                ),
                color = languageLabelColor
            )
            
            // 右上角：复制按钮
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(code))
                },
                modifier = Modifier.padding(0.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "复制代码",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        // 🎯 完美自适应宽度 + 丝滑水平滚动（与数学公式/表格一致）
        Box(
            modifier = Modifier
                .fillMaxWidth()  // 外层占满父容器，提供滚动边界
                .then(
                    if (enableHorizontalScroll) {
                        Modifier.horizontalScroll(rememberScrollState())  // 超出时启用滚动
                    } else {
                        Modifier
                    }
                )
                .heightIn(max = maxHeight.dp)  // 限制最大高度
        ) {
            Text(
                text = code,
                modifier = Modifier
                    .wrapContentWidth()  // 🎯 完全自适应代码实际宽度
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                ),
                color = resolvedTextColor
            )
        }
    }
}