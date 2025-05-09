package com.example.app1.ui.chat.views // 替换为你的实际包名基础 + .ui.chat.views

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 自定义代码块可组合项。
 * @param language 代码语言，可为null。
 * @param code 代码内容。
 * @param backgroundColor 背景颜色。
 * @param contentColor 内容颜色（文本、图标）。
 * @param cornerRadius 圆角大小。
 * @param fixedWidth 代码块的固定宽度。
 * @param showTopBar 是否显示顶部栏（包含语言名称和复制按钮）。
 * @param modifier Modifier。
 */
@Composable
fun MyCodeBlockComposable(
    language: String?,
    code: String,
    backgroundColor: Color,
    contentColor: Color,
    cornerRadius: Dp,
    fixedWidth: Dp,
    showTopBar: Boolean,
    modifier: Modifier = Modifier
) {
    Log.d(
        "MyCodeBlockComposable",
        "Rendering - Lang: '$language', CodePreview: '${
            code.take(30).replace("\n", "\\n")
        }...', ShowTopBar: $showTopBar, BG: $backgroundColor"
    )

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Column(
        modifier = modifier
            .width(fixedWidth) // 应用固定宽度
            .clip(RoundedCornerShape(cornerRadius))
            .background(backgroundColor)
    ) {
        if (showTopBar) {
            // 顶部栏：显示语言和复制按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp), // 调整内边距以减少顶部栏高度
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    // 语言名称，首字母大写
                    text = language?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                        ?: "Code", // 如果没有语言，默认为 "Code"
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = contentColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        lineHeight = 16.sp // 调整行高
                    ),
                    modifier = Modifier.padding(start = 4.dp) // 语言标签的左边距
                )
                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(code))
                        Toast.makeText(context, "${language ?: "代码"}已复制", Toast.LENGTH_SHORT)
                            .show()
                    },
                    modifier = Modifier.heightIn(min = 28.dp), // 限制按钮的最小高度
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp) // 按钮内边距
                ) {
                    Icon(
                        Icons.Outlined.ContentPaste, // Material图标库中的复制图标
                        contentDescription = "Copy code", // 无障碍文本
                        modifier = Modifier.size(16.dp),
                        tint = contentColor
                    )
                    Spacer(Modifier.width(4.dp)) // 图标和文字之间的间距
                    Text(
                        "Copy", style = MaterialTheme.typography.labelSmall.copy(
                            color = contentColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            lineHeight = 16.sp, // 调整行高
                        )
                    )
                }
            }
        }
        // 代码文本本身
        // 注意：这里直接使用Text显示代码。如果需要语法高亮，则需要更复杂的解决方案。
        Text(
            text = code,
            fontFamily = FontFamily.Monospace, // 等宽字体
            color = contentColor,
            fontSize = 13.sp, // 调整字体大小
            lineHeight = 18.sp, // 调整行高
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    bottom = 10.dp,
                    top = if (showTopBar) 6.dp else 10.dp // 如果有顶部栏，顶部内边距小一些
                )
        )
    }
}