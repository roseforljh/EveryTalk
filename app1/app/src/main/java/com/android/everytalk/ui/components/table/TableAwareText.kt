package com.android.everytalk.ui.components.table

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.android.everytalk.ui.components.ContentParser
import com.android.everytalk.ui.components.ContentPart
import com.android.everytalk.ui.components.WebPreviewDialog
import com.android.everytalk.ui.components.content.CodeBlockCard
import com.android.everytalk.ui.components.markdown.MarkdownRenderer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.android.everytalk.util.cache.ContentParseCache
import com.android.everytalk.ui.components.table.TableUtils
import android.widget.ImageView
import android.view.ViewGroup
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.toArgb
import ru.noties.jlatexmath.JLatexMathDrawable
import com.android.everytalk.data.DataClass.Sender
import android.util.TypedValue
import androidx.compose.ui.platform.LocalContext

/**
 * 表格感知文本渲染器（优化版 + 跳动修复）
 *
 * 核心策略：
 * - 统一渲染流水线：
 *   - 全程（流式 + 结束）都使用分段解析和渲染
 *   - ContentParser 负责准确解析文本、代码块和表格
 *   - UI 层只负责渲染，不做内容过滤
 *   - 彻底消除流式结束时的组件替换，从而根除跳动
 *
 * 缓存机制：通过 contentKey 持久化解析结果，避免 LazyColumn 回收导致重复解析
 *
 * 修复历史：
 * - 2024-11: 移除 filteredParts 补丁逻辑，由 ContentParser 保证解析准确性
 */
@Composable
fun TableAwareText(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
    recursionDepth: Int = 0,
    contentKey: String = "",  // 新增：用于缓存key（通常为消息ID）
    onLongPress: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    onImageClick: ((String) -> Unit)? = null,
    sender: Sender = Sender.AI,
    onCodePreviewRequested: ((String, String) -> Unit)? = null, // 代码预览回调
    onCodeCopied: (() -> Unit)? = null // 代码复制回调
) {
    // 预览状态管理
    var previewState by remember { mutableStateOf<Pair<String, String>?>(null) } // (code, language)

    // 方案二：实时分段解析与统一渲染
    // 无论是否流式，都尝试进行轻量级分段解析（仅分离代码块，表格仍由MarkdownRenderer处理或后续优化）
    
    // 1. 解析状态管理
    // 优化：使用 remember + LaunchedEffect 替代 produceState
    // 目的：当 isStreaming 变化时（true -> false），保持当前的 parsedParts 不变，
    // 直到新的解析完成。避免 produceState 重置导致的回退到 initialValue (纯文本) 造成的闪烁/跳动。
    
    // 缓存版本控制：当解析逻辑更新时，通过修改版本号使旧缓存失效
    val effectiveCacheKey = if (contentKey.isNotBlank()) "${contentKey}_v${ContentParseCache.PARSER_VERSION}" else ""

    val parsedPartsState = remember(contentKey) {
        mutableStateOf(
            if (!isStreaming && effectiveCacheKey.isNotBlank()) {
                ContentParseCache.get(effectiveCacheKey) ?: listOf(ContentPart.Text(text))
            } else {
                listOf(ContentPart.Text(text))
            }
        )
    }

    LaunchedEffect(text, contentKey) {
        val newParts = withContext(Dispatchers.Default) {
            if (isStreaming) {
                // 流式期间不读写全局缓存，直接解析
                ContentParser.parseCompleteContent(text, isStreaming = true)
            } else {
                // 非流式：尝试从全局缓存获取，否则完整解析并缓存
                // 策略：如果文本包含表格特征字符 '|'，为了保险起见，可以考虑强制刷新（可选）
                // 但有了版本号控制，通常不需要强制刷新。
                ContentParseCache.get(effectiveCacheKey) ?: ContentParser.parseCompleteContent(text, isStreaming = false).also {
                    if (effectiveCacheKey.isNotBlank()) ContentParseCache.put(effectiveCacheKey, it)
                }
            }
        }
        parsedPartsState.value = newParts
    }
    
    val parsedParts = parsedPartsState.value

    // 统一渲染逻辑
    // ContentParser 已经确保解析准确性，UI 层直接渲染即可
    // 优化：将垂直 padding 移至外层 Column，内部组件禁用垂直 padding，从而消除组件间的双重间距
    
    val context = LocalContext.current
    // 用户气泡外部已有 padding，内部不再添加垂直 padding，避免顶部空白过大
    // AI 气泡也移除垂直 padding，将空间控制交给具体的渲染组件（如 MarkdownRenderer 的 pre-processing）
    // 防止出现“顶部空白过大”的问题
    val verticalPaddingDp = 0.dp
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = verticalPaddingDp) // 在容器层统一添加垂直 padding
    ) {
        parsedParts.forEach { part ->
            when (part) {
                is ContentPart.Text -> {
                    // 纯文本部分：用MarkdownRenderer渲染
                    MarkdownRenderer(
                        markdown = part.content,
                        style = style,
                        color = color,
                        modifier = Modifier.fillMaxWidth(),
                        isStreaming = isStreaming, // 传递流式状态给MarkdownRenderer（用于内部优化）
                        onLongPress = onLongPress,
                        onImageClick = onImageClick,
                        sender = sender,
                        // 修复缓存冲突：Key 必须包含内容的特征（如长度），因为 index 0 可能从完整文本变为片段
                        contentKey = if (contentKey.isNotBlank()) "${contentKey}_part_${parsedParts.indexOf(part)}_${part.content.length}" else "",
                        disableVerticalPadding = true // 禁用内部垂直 padding，由外层 Column 统一控制
                    )
                }
                is ContentPart.Code -> {
                    // 🔧 修复：使用 CodeBlockCard 渲染代码块，提供复制按钮、预览按钮、语言类型和圆角
                    // 这样无论流式还是非流式，代码块都有完整的 UI 功能
                    val clipboard = LocalClipboardManager.current
                    CodeBlockCard(
                        language = part.language,
                        code = part.content,
                        modifier = Modifier.padding(vertical = 4.dp),
                        onPreviewRequested = if (onCodePreviewRequested != null) {
                            { onCodePreviewRequested(part.language ?: "", part.content) }
                        } else null,
                        onCopy = {
                            clipboard.setText(AnnotatedString(part.content))
                            onCodeCopied?.invoke()
                        }
                    )
                }
                is ContentPart.Table -> {
                    // 表格部分：使用 TableRenderer 渲染
                    TableRenderer(
                        lines = part.lines,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        isStreaming = isStreaming,
                        contentKey = if (contentKey.isNotBlank()) "${contentKey}_table_${parsedParts.indexOf(part)}_${part.lines.size}" else "",
                        onLongPress = onLongPress,
                        // 使用与文本一致的样式
                        headerStyle = style.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                        cellStyle = style
                    )
                }
                is ContentPart.Math -> {
                    // 数学公式块部分：支持横向滚动
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        // 尝试直接使用 JLatexMathDrawable 渲染，绕过 Markwon 解析
                        // 这样可以避免 Markwon 对 $$ 块的解析问题
                        val mathContent = part.content.trim().removePrefix("$$").removeSuffix("$$").trim()
                        val density = androidx.compose.ui.platform.LocalDensity.current.density
                        val textSizePx = style.fontSize.value * density
                        
                        // 修复：避免在非 Composable 上下文中使用 Composable 函数
                        val defaultColor = MaterialTheme.colorScheme.onSurface
                        val finalColor = if (color != Color.Unspecified) color else defaultColor
                        val textColorInt = finalColor.toArgb()

                        AndroidView(
                            factory = { context ->
                                ImageView(context).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                    )
                                    scaleType = ImageView.ScaleType.FIT_START
                                    adjustViewBounds = true
                                }
                            },
                            update = { imageView ->
                                try {
                                    val drawable = JLatexMathDrawable.builder(mathContent)
                                        .textSize(textSizePx)
                                        .padding(0) // 移除多余的 padding
                                        .background(0) // Transparent
                                        .align(JLatexMathDrawable.ALIGN_LEFT)
                                        .color(textColorInt)
                                        .build()
                                    imageView.setImageDrawable(drawable)
                                } catch (e: Throwable) {
                                    // 降级处理：如果直接渲染失败（如依赖缺失），回退到 MarkdownRenderer
                                    android.util.Log.e("TableAwareText", "JLatexMath direct render failed", e)
                                    // 这里无法直接切换回 Composable，只能显示错误或尝试用 TextView 显示源码
                                    // 简单起见，我们不处理回退，因为如果库存在，通常只会因 LaTeX 语法错误失败
                                }
                            },
                            modifier = Modifier
                                .wrapContentWidth()
                                .padding(horizontal = 8.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                    }
                }
            }
        }
    }

    // 显示预览对话框
    previewState?.let { (code, language) ->
        WebPreviewDialog(
            code = code,
            language = language,
            onDismiss = { previewState = null }
        )
    }
}
