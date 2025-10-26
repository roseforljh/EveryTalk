package com.android.everytalk.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 自定义代码块组件
 *
 * 特性：
 * - 完美自适应宽度：代码多宽容器就多宽
 * - 丝滑水平滚动：超出屏幕时自动启用滚动
 * - 左上角：语言类型标签
 * - 右上角：复制按钮 + 预览按钮（当检测为图表/SVG时）
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
    maxHeight: Int = Int.MAX_VALUE  // 移除高度限制，让代码完整显示
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

    // ✅ 预览可用性检测：language 或 内容启发式
    val langLower = language?.lowercase()?.trim()
    val canPreview by remember(code, langLower) {
        val hasSvg = Regex("(?is)\\Q<svg\\E").containsMatchIn(code)
        val isSvgLang = langLower == "svg"
        val isMermaidLang = langLower == "mermaid"
        // 常见 mermaid 关键字启发式（graph/sequenceDiagram/classDiagram/stateDiagram 等）
        val looksLikeMermaid = Regex("(?is)\\b(graph\\s+\\w+|sequenceDiagram|classDiagram|stateDiagram|erDiagram|journey)\\b")
            .containsMatchIn(code)
        // 新增图表类型识别（包括 HTML）
        val isChartType = langLower in listOf("echarts", "chart", "chartjs", "vega", "vega-lite", "flowchart", "html")
        
        mutableStateOf(isSvgLang || hasSvg || isMermaidLang || looksLikeMermaid || isChartType)
    }

    var showPreview by remember { mutableStateOf(false) }
    var showFullscreenHtmlDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = codeBlockBg,
                shape = RoundedCornerShape(12.dp)  // 🎯 圆角
            )
            .padding(2.dp)  // 外边距
    ) {
        // 🎯 顶部栏：左上角语言类型 + 右上角操作按钮（复制/预览）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左上角：语言类型
            Text(
                text = langLower?.uppercase() ?: "CODE",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                ),
                color = languageLabelColor
            )

            // 右上角：按钮组（复制 + 条件预览）
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canPreview) {
                    IconButton(
                        onClick = {
                            // HTML 类型使用全屏 Dialog，其他类型内联展开
                            if (langLower == "html") {
                                showFullscreenHtmlDialog = true
                            } else {
                                showPreview = !showPreview
                            }
                        },
                        modifier = Modifier.padding(0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Visibility,
                            contentDescription = if (showPreview) "隐藏预览" else "预览",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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
        
            // 全屏 HTML 预览对话框（不滚动，填满屏幕）
            if (showFullscreenHtmlDialog) {
                Dialog(
                    onDismissRequest = { showFullscreenHtmlDialog = false },
                    properties = DialogProperties(
                        dismissOnClickOutside = true,
                        dismissOnBackPress = true,
                        usePlatformDefaultWidth = false
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        ChartPreviewWebView(
                            code = code,
                            language = "html",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        // 🎯 完美自适应宽度 + 丝滑水平滚动（与数学公式/表格一致）
        // 移除高度限制，让代码完整显示
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
                // 不再限制最大高度，代码可以完整显示
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

        // 🔎 预览区域（内联展开，非全屏/底部抽屉）- 带平滑过渡动画
        // HTML 使用全屏对话框预览，内联区域仅用于非 HTML
        AnimatedVisibility(
            visible = canPreview && showPreview && langLower != "html",
            enter = expandVertically(
                animationSpec = tween(durationMillis = 300),
                expandFrom = Alignment.Top
            ) + fadeIn(animationSpec = tween(durationMillis = 300)),
            exit = shrinkVertically(
                animationSpec = tween(durationMillis = 250),
                shrinkTowards = Alignment.Top
            ) + fadeOut(animationSpec = tween(durationMillis = 250))
        ) {
            // 轻量留白，与代码区分
            // 预览区域自适应高度，完整显示内容
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, start = 6.dp, end = 6.dp, bottom = 8.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .padding(6.dp)
                    .heightIn(min = 200.dp)  // 只设置最小高度，移除最大高度限制，让内容完整显示
            ) {
                // 采用单独的 WebView 组件承载渲染（通过 CDN）
                ChartPreviewWebView(
                    code = code,
                    language = langLower
                )
            }
        }
    }
}