package com.android.everytalk.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichText
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor

/**
 * Compose Rich Editor 使用示例
 * 
 * 这个文件展示了如何使用 Compose Rich Editor 来替代之前的 compose-markdown 和 Markwon 库
 */

/**
 * 示例 1: 显示 Markdown 内容（只读模式）
 * 
 * 用于显示聊天消息中的 Markdown 格式文本
 */
@Composable
fun MarkdownDisplay(
    markdownContent: String,
    modifier: Modifier = Modifier
) {
    // 创建 RichTextState
    val richTextState = rememberRichTextState()
    
    // 将 Markdown 转换为富文本
    LaunchedEffect(markdownContent) {
        richTextState.setMarkdown(markdownContent)
    }
    
    // 显示富文本（只读）
    RichText(
        state = richTextState,
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
    )
}

/**
 * 示例 2: 显示 HTML 内容（只读模式）
 * 
 * 如果后端返回的是 HTML 格式，可以直接使用
 */
@Composable
fun HtmlDisplay(
    htmlContent: String,
    modifier: Modifier = Modifier
) {
    val richTextState = rememberRichTextState()
    
    LaunchedEffect(htmlContent) {
        richTextState.setHtml(htmlContent)
    }
    
    RichText(
        state = richTextState,
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
    )
}

/**
 * 示例 3: 可编辑的富文本编辑器
 * 
 * 如果需要用户输入富文本内容
 */
@Composable
fun RichTextEditorExample(
    modifier: Modifier = Modifier
) {
    val richTextState = rememberRichTextState()
    
    Column(modifier = modifier.fillMaxWidth()) {
        // 工具栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 粗体按钮
            Button(
                onClick = {
                    richTextState.toggleSpanStyle(
                        SpanStyle(fontWeight = FontWeight.Bold)
                    )
                }
            ) {
                Text("B")
            }
            
            // 斜体按钮
            Button(
                onClick = {
                    richTextState.toggleSpanStyle(
                        SpanStyle(fontWeight = FontWeight.Normal)
                    )
                }
            ) {
                Text("I")
            }
            
            // 添加链接
            Button(
                onClick = {
                    richTextState.addLink(
                        text = "链接文本",
                        url = "https://example.com"
                    )
                }
            ) {
                Text("Link")
            }
        }
        
        // 编辑器
        RichTextEditor(
            state = richTextState,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(8.dp)
        )
        
        // 显示当前内容（Markdown 格式）
        Text(
            text = "Markdown 输出:",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(8.dp)
        )
        Text(
            text = richTextState.toMarkdown(),
            modifier = Modifier.padding(8.dp)
        )
    }
}

/**
 * 示例 4: 自定义样式配置
 * 
 * 配置链接颜色、代码块样式等
 */
@Composable
fun CustomStyledMarkdownDisplay(
    markdownContent: String,
    modifier: Modifier = Modifier
) {
    val richTextState = rememberRichTextState()
    
    // 自定义配置
    LaunchedEffect(Unit) {
        richTextState.config.linkColor = Color.Blue
        richTextState.config.codeSpanColor = Color(0xFFE91E63)
        richTextState.config.codeSpanBackgroundColor = Color(0xFFF5F5F5)
        richTextState.config.codeSpanStrokeColor = Color.LightGray
    }
    
    LaunchedEffect(markdownContent) {
        richTextState.setMarkdown(markdownContent)
    }
    
    RichText(
        state = richTextState,
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
    )
}

/**
 * 示例 5: 流式更新 Markdown 内容
 * 
 * 适用于 AI 流式响应的场景
 */
@Composable
fun StreamingMarkdownDisplay(
    streamingContent: String,
    modifier: Modifier = Modifier
) {
    val richTextState = rememberRichTextState()
    
    // 每次内容更新时重新设置
    LaunchedEffect(streamingContent) {
        richTextState.setMarkdown(streamingContent)
    }
    
    RichText(
        state = richTextState,
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
    )
}

/**
 * 示例 6: 支持的 Markdown 特性
 */
@Composable
fun MarkdownFeaturesDemo() {
    val markdownContent = """
        # 标题 1
        ## 标题 2
        ### 标题 3
        
        **粗体文本**
        *斜体文本*
        
        [链接文本](https://example.com)
        
        `行内代码`
        
        ```kotlin
        // 代码块
        fun example() {
            println("Hello World")
        }
        ```
        
        - 无序列表项 1
        - 无序列表项 2
        
        1. 有序列表项 1
        2. 有序列表项 2
        
        > 引用文本
    """.trimIndent()
    
    MarkdownDisplay(markdownContent = markdownContent)
}