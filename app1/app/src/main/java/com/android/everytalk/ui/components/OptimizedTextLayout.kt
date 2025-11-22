package com.android.everytalk.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import android.widget.Toast

/**
 * 代码块组件（自定义样式）
 * - 自适应滚动策略（短代码换行，长代码水平滚动）
 * - 支持水平/垂直双向滚动
 * - 顶部右侧"复制"按钮
 * - 适配明暗主题
 * - 手势冲突解决
 */
@Composable
fun CodeBlock(
    code: String,
    language: String?,
    textColor: Color,
    modifier: Modifier = Modifier,
    maxHeight: Int = 300,
    cornerRadius: Int = 10,
    enableHorizontalScroll: Boolean = true, // 🎯 新增：是否启用水平滚动
    onScrollingStateChanged: (Boolean) -> Unit = {}, // 🎯 新增：滚动状态回调
    onPreviewClick: (() -> Unit)? = null, // 🎯 新增：预览回调
    onLongPress: (() -> Unit)? = null // 🎯 新增：长按回调
) {
    val isDark = isSystemInDarkTheme()
    val codeColor = if (isDark) Color(0xFFD4D4D4) else Color(0xFF24292F)
    val topBarColor = if (isDark) Color.White else Color(0xFF24292F)

    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    
    // 🎯 手势冲突解决：检测水平滚动状态
    var isScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(hScroll.isScrollInProgress) {
        isScrolling = hScroll.isScrollInProgress
        onScrollingStateChanged(isScrolling)
    }

    val codeBgColor = if (isDark) Color(0xFF1E1E1E) else Color(0xFFF7F7F7)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp, max = maxHeight.dp)
            // 恢复原先圆角（使用组件参数 cornerRadius）
            .clip(RoundedCornerShape(cornerRadius.dp))
            .background(codeBgColor)
            .pointerInput(enableHorizontalScroll, onLongPress) {
                // 🎯 如果启用水平滚动，捕获水平拖动手势
                // 注意：detectHorizontalDragGestures 可能会拦截长按，所以我们需要组合使用
                // 或者使用 awaitPointerEventScope 手动处理
                
                // 简化策略：优先处理长按，只有在确实发生拖动时才消费事件
                
                if (onLongPress != null) {
                     detectTapGestures(
                        onLongPress = {
                            if (!isScrolling) {
                                onLongPress()
                            }
                        },
                         onTap = { /* no-op */ }
                    )
                }
            }
            .pointerInput(enableHorizontalScroll) {
                if (enableHorizontalScroll) {
                    detectHorizontalDragGestures(
                        onDragStart = { onScrollingStateChanged(true) },
                        onDragEnd = { onScrollingStateChanged(false) },
                        onDragCancel = { onScrollingStateChanged(false) },
                        onHorizontalDrag = { change, _ ->
                            change.consume() // 消费事件，防止抽屉响应
                        }
                    )
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(vScroll)
                .padding(start = 4.dp, end = 4.dp, top = 28.dp, bottom = 4.dp)
        ) {
            // 🎯 根据是否需要长按来决定是否启用文本选择
            // 如果启用了长按，禁用SelectionContainer以避免手势冲突
            val contentModifier = if (enableHorizontalScroll) {
                Modifier.horizontalScroll(hScroll)
            } else {
                Modifier.fillMaxWidth()
            }
            
            if (onLongPress == null) {
                SelectionContainer {
                    Row(modifier = contentModifier) {
                        Text(
                            text = code,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                fontWeight = FontWeight.Normal,
                                color = codeColor,
                                letterSpacing = 0.sp
                            ),
                            softWrap = !enableHorizontalScroll
                        )
                    }
                }
            } else {
                Row(modifier = contentModifier) {
                    Text(
                        text = code,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontWeight = FontWeight.Normal,
                            color = codeColor,
                            letterSpacing = 0.sp
                        ),
                        softWrap = !enableHorizontalScroll
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val langText = language?.takeIf { it.isNotBlank() } ?: "code"
            Text(
                text = langText.lowercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = topBarColor,
                    letterSpacing = 0.3.sp
                )
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                // 🎯 预览按钮 (仅当 onPreviewClick 不为空时显示)
                if (onPreviewClick != null) {
                    IconButton(
                        onClick = onPreviewClick,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Visibility,
                            contentDescription = "预览",
                            tint = topBarColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(code))
                        Toast.makeText(ctx, "代码已复制", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "复制代码",
                        tint = topBarColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
