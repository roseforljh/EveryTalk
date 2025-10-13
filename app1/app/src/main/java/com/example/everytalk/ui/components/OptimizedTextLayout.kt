package com.example.everytalk.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.ui.theme.chatColors
import com.example.everytalk.ui.theme.ChatDimensions

/**
 * 优化的文本布局组件 - 支持Markdown渲染但不处理数学公式
 * 
 * 主要功能:
 * 1. 完整的Markdown格式支持
 * 2. 代码块语法高亮
 * 3. 表格、列表、标题等格式
 * 4. 紧凑的布局设计
 * 5. 响应式字体大小
 */
@Composable
fun OptimizedTextLayout(
    message: Message,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodyLarge
) {
    val isDarkTheme = isSystemInDarkTheme()
    
    // 解析消息内容，分离文本和代码块
    val contentParts = remember(message.text, message.parts) {
        parseMessageContent(
            if (message.parts.isNotEmpty()) {
                message.parts.joinToString("\n") { part ->
                    when (part) {
                        is MarkdownPart.Text -> part.content
                        is MarkdownPart.CodeBlock -> "```" + part.language + "\n" + part.content + "\n```"
                        else -> ""
                    }
                }
            } else {
                message.text
            }
        )
    }
    
    // 优化的文本样式
    val optimizedTextStyle = remember(style) {
        style.copy(
            lineHeight = (style.fontSize.value * 1.3f).sp,
            letterSpacing = (-0.1).sp,
            fontWeight = FontWeight.Normal
        )
    }
    
    SelectionContainer {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            contentParts.forEachIndexed { index, part ->
                when (part.type) {
                    ContentType.TEXT -> {
                        val prevType = if (index > 0) contentParts[index - 1].type else null
                        val nextType = if (index < contentParts.lastIndex) contentParts[index + 1].type else null
                        
                        // 文本块与代码块之间需要更大的间距
                        val topPadding = if (prevType == ContentType.CODE) 12.dp else 6.dp
                        val bottomPadding = if (nextType == ContentType.CODE) 12.dp else 6.dp
                        
                        // 降级：使用 Text 显示规范化后的 Markdown 文本（不做富渲染）
                        Text(
                            text = normalizeBasicMarkdown(part.content),
                            style = optimizedTextStyle.copy(color = textColor),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = topPadding, bottom = bottomPadding),
                            textAlign = TextAlign.Start
                        )
                    }
                    ContentType.CODE -> {
                        val prevType = if (index > 0) contentParts[index - 1].type else null
                        val nextType = if (index < contentParts.lastIndex) contentParts[index + 1].type else null
                        
                        // 代码块之间需要明显的间距
                        val topPadding = when (prevType) {
                            ContentType.CODE -> 16.dp  // 代码块之间
                            ContentType.TEXT -> 12.dp  // 文本后面
                            null -> 0.dp               // 第一个元素
                        }
                        val bottomPadding = when (nextType) {
                            ContentType.CODE -> 0.dp   // 下一个是代码块，由下一个的topPadding处理
                            ContentType.TEXT -> 12.dp  // 下一个是文本
                            null -> 0.dp               // 最后一个元素
                        }
                        
                        CodeBlock(
                            code = part.content,
                            language = part.metadata,
                            textColor = textColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = topPadding, bottom = bottomPadding)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 代码块组件（自定义样式）
 * - 固定高度（可调），大圆角
 * - 支持水平/垂直双向滚动
 * - 顶部右侧“复制”按钮
 * - 适配明暗主题
 */
@Composable
fun CodeBlock(
    code: String,
    language: String?,
    textColor: Color,
    modifier: Modifier = Modifier,
    maxHeight: Int = 300,          // 增加最大高度
    cornerRadius: Int = 10         // 稍微减小圆角使其更精致
) {
    // 优化的代码块样式 - 更现代、更清晰
    val isDark = isSystemInDarkTheme()
    
    // 更柔和的配色方案
    val bg = if (isDark) Color(0xFF1E1E1E) else Color(0xFFF6F8FA)
    val codeColor = if (isDark) Color(0xFFD4D4D4) else Color(0xFF24292F)
    val borderColor = if (isDark) Color(0xFF3E3E42) else Color(0xFFD0D7DE)
    
    // 顶栏元素根据主题自适应颜色
    val topBarColor = if (isDark) Color.White else Color(0xFF24292F)
    val chipBg = if (isDark) Color(0xFF2D2D30).copy(alpha = 0.8f) else Color(0xFFFFFFFF).copy(alpha = 0.9f)

    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp, max = maxHeight.dp)
            .clip(RoundedCornerShape(cornerRadius.dp))
            .border(1.dp, borderColor, RoundedCornerShape(cornerRadius.dp)),
        color = bg,
        contentColor = codeColor,
        shadowElevation = 0.dp,
        tonalElevation = if (isDark) 2.dp else 0.dp
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // 代码内容区域 - 优化内边距
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(vScroll)
                    .padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 16.dp)
            ) {
                SelectionContainer {
                    Row(
                        modifier = Modifier.horizontalScroll(hScroll)
                    ) {
                        Text(
                            text = code,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = codeColor,
                                letterSpacing = 0.sp
                            )
                        )
                    }
                }
            }

            // 顶部栏：语言标签 + 复制按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：语言标签
                val langText = language?.takeIf { it.isNotBlank() } ?: "code"
                Box(
                    modifier = Modifier
                        .background(chipBg, RoundedCornerShape(6.dp))
                        .border(1.dp, borderColor, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = langText.lowercase(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = topBarColor,
                            letterSpacing = 0.3.sp
                        )
                    )
                }

                // 右侧：复制按钮
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

/**
 * 内容类型枚举 - 只保留文本和代码，不包含数学公式
 */
enum class ContentType {
    TEXT,
    CODE
}

/**
 * 内容部分数据类
 */
data class ContentPart(
    val type: ContentType,
    val content: String,
    val metadata: String? = null
)

/**
 * 解析消息内容，分离文本和代码块
 */
fun parseMessageContent(text: String): List<ContentPart> {
    // 防御：对于超大文本（>500KB），直接返回纯文本，避免复杂解析导致OOM
    if (text.length > 500_000) {
        return listOf(ContentPart(ContentType.TEXT, text))
    }
    
    // 更鲁棒的围栏扫描器，修复"代码块大白块"根因：
    // - MarkdownText 对围栏代码使用浅色背景；我们用自定义 CodeBlock 深色样式替代
    // - 之前用单个正则无法识别：缩进围栏、列表内围栏、~~~ 围栏、开行携带内联内容、缺失闭合围栏等
    // - 这里用逐行扫描，支持 ``` 与 ~~~，支持任意 Unicode 语言标签，兼容开行尾部"内联代码"迁移到下一行
    val parts = mutableListOf<ContentPart>()
    val lines = text.split("\n")
    val textBuf = StringBuilder()

    var i = 0
    var inFence = false
    var fenceChar: Char = '`'
    var fenceLen = 0
    var currentLang: String? = null
    val fenceOpen = Regex("""^\s*([`~]{3,})\s*([^\s`~]*)\s*(.*)$""") // group1=fence, group2=lang?, group3=rest

    fun isFenceClose(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return false
        if (fenceChar == '`') {
            // 至少 fenceLen 个 `
            if (t.first() != '`') return false
            var cnt = 0
            for (ch in t) {
                if (ch == '`') cnt++ else break
            }
            return cnt >= fenceLen
        } else {
            if (t.first() != '~') return false
            var cnt = 0
            for (ch in t) {
                if (ch == '~') cnt++ else break
            }
            return cnt >= fenceLen
        }
    }

    fun flushText() {
        if (textBuf.isNotEmpty()) {
            parts.add(ContentPart(ContentType.TEXT, textBuf.toString()))
            textBuf.clear()
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        if (!inFence) {
            val m = fenceOpen.find(line)
            if (m != null) {
                // 命中开围栏：先输出之前累积的文本
                flushText()

                val fenceToken = m.groupValues[1]
                fenceChar = fenceToken.first()
                fenceLen = fenceToken.length
                currentLang = m.groupValues[2].takeIf { it.isNotBlank() }
                val rest = m.groupValues[3].trimStart()

                val codeLines = mutableListOf<String>()
                if (rest.isNotEmpty()) codeLines.add(rest)

                // 收集直到闭围栏或 EOF
                i += 1
                while (i < lines.size) {
                    val cur = lines[i]
                    if (isFenceClose(cur)) {
                        // 完成一个代码块 - 只有当有实际内容时才添加
                        val codeContent = codeLines.joinToString("\n")
                        if (codeContent.isNotBlank()) {
                            parts.add(ContentPart(ContentType.CODE, codeContent, currentLang))
                        }
                        inFence = false
                        currentLang = null
                        break
                    } else {
                        codeLines.add(cur)
                    }
                    i += 1
                }

                // EOF 未闭合：按代码块处理 - 只有当有实际内容时才添加
                if (i >= lines.size) {
                    val codeContent = codeLines.joinToString("\n")
                    if (codeContent.isNotBlank()) {
                        parts.add(ContentPart(ContentType.CODE, codeContent, currentLang))
                    }
                    inFence = false
                    currentLang = null
                }
                // 继续外层 while（此时 i 指向闭围栏行或 EOF；外层循环自增）
            } else {
                if (textBuf.isNotEmpty()) textBuf.append('\n')
                textBuf.append(line)
                i += 1
            }
        } else {
            // 理论上不会到达，inFence 在内部循环已处理
            i += 1
        }
    }

    // 尾部残留文本
    flushText()

    if (parts.isEmpty()) {
        parts.add(ContentPart(ContentType.TEXT, text))
    }
    return parts
}

/**
 * 匹配项数据类
 */
private data class MatchItem(
    val range: IntRange,
    val type: ContentType,
    val content: String,
    val metadata: String? = null
)