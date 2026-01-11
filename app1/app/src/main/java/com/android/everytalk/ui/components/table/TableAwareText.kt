package com.android.everytalk.ui.components.table

import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.components.ContentParser
import com.android.everytalk.ui.components.ContentPart
import com.android.everytalk.ui.components.WebPreviewDialog
import com.android.everytalk.ui.components.content.CodeBlockCard
import com.android.everytalk.ui.components.markdown.MarkdownRenderer
import com.android.everytalk.util.cache.ContentParseCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * 表格感知文本渲染器（优化版 + 实时渲染）
 *
 * 核心策略：
 * - 流式模式：实时分块渲染，表格/代码块即时显示
 * - 非流式模式：分块渲染，每种 ContentPart 类型使用最优组件
 * - 后台解析：使用 flowOn(Dispatchers.Default) 在后台线程解析 AST
 * - referentialEqualityPolicy：避免不必要的状态更新导致重组
 * - 稳定 Key：使用索引作为 key，避免内容变化导致组件重建
 *
 * 缓存机制：通过 contentKey 持久化解析结果，避免 LazyColumn 回收导致重复解析
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

    // 使用 referentialEqualityPolicy 避免不必要的重组
    var parsedParts by remember {
        mutableStateOf(
            value = emptyList<ContentPart>(),
            policy = referentialEqualityPolicy()
        )
    }

    // 在后台线程解析内容
    val updatedText by rememberUpdatedState(text)
    val updatedIsStreaming by rememberUpdatedState(isStreaming)
    val effectiveCacheKey = if (contentKey.isNotBlank() && !isStreaming) {
        "${contentKey}_${text.hashCode()}_v${ContentParseCache.PARSER_VERSION}"
    } else ""

    LaunchedEffect(Unit) {
        snapshotFlow { updatedText to updatedIsStreaming }
            .distinctUntilChanged()
            .mapLatest { (currentText, streaming) ->
                // 非流式模式：尝试从缓存读取
                if (!streaming && effectiveCacheKey.isNotBlank()) {
                    ContentParseCache.get(effectiveCacheKey)?.let { return@mapLatest it }
                }

                // 解析内容（流式模式会自动闭合未完成的代码块）
                val parts = ContentParser.parseCompleteContent(currentText, isStreaming = streaming)

                // 非流式模式：缓存结果
                if (!streaming && effectiveCacheKey.isNotBlank()) {
                    ContentParseCache.put(effectiveCacheKey, parts)
                }

                parts
            }
            .catch { e ->
                e.printStackTrace()
                emit(listOf(ContentPart.Text(updatedText)))
            }
            .flowOn(Dispatchers.Default)
            .collect { parts ->
                parsedParts = parts
            }
    }

    // 初始化时同步解析（避免首次渲染空白）
    if (parsedParts.isEmpty() && text.isNotEmpty()) {
        val initialParts = remember(text, contentKey, isStreaming) {
            if (!isStreaming && effectiveCacheKey.isNotBlank()) {
                ContentParseCache.get(effectiveCacheKey)
            } else null
        } ?: ContentParser.parseCompleteContent(text, isStreaming = isStreaming).also { parts ->
            if (!isStreaming && effectiveCacheKey.isNotBlank()) {
                ContentParseCache.put(effectiveCacheKey, parts)
            }
        }
        parsedParts = initialParts
    }

    // 渲染逻辑
    val verticalPaddingDp = 0.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = verticalPaddingDp)
    ) {
        // 统一使用 类型+索引 作为 key
        // 这样流式结束时 key 不会变化，避免组件重建导致闪烁
        parsedParts.forEachIndexed { index, part ->
            val stableKey = "${part.javaClass.simpleName}_$index"

            androidx.compose.runtime.key(stableKey) {
                when (part) {
                    is ContentPart.Text -> {
                        MarkdownRenderer(
                            markdown = part.content,
                            style = style,
                            color = color,
                            modifier = Modifier.fillMaxWidth(),
                            isStreaming = isStreaming,
                            onLongPress = onLongPress,
                            onImageClick = onImageClick,
                            sender = sender,
                            contentKey = if (contentKey.isNotBlank() && !isStreaming) {
                                "${contentKey}_part_${index}_${part.content.hashCode()}"
                            } else "",
                            disableVerticalPadding = true
                        )
                    }
                    is ContentPart.Code -> {
                        val clipboard = LocalClipboardManager.current
                        CodeBlockCard(
                            language = part.language,
                            code = part.content,
                            modifier = Modifier.padding(vertical = 4.dp),
                            isStreaming = isStreaming,
                            onPreviewRequested = if (onCodePreviewRequested != null) {
                                { onCodePreviewRequested(part.language ?: "", part.content) }
                            } else null,
                            onCopy = {
                                clipboard.setText(AnnotatedString(part.content))
                                onCodeCopied?.invoke()
                            },
                            onLongPress = onLongPress
                        )
                    }
                    is ContentPart.Table -> {
                        TableRenderer(
                            lines = part.lines,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            isStreaming = isStreaming,
                            contentKey = if (contentKey.isNotBlank() && !isStreaming) {
                                "${contentKey}_table_${index}_${part.lines.size}"
                            } else "",
                            onLongPress = onLongPress,
                            headerStyle = style.copy(fontWeight = FontWeight.Bold),
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
