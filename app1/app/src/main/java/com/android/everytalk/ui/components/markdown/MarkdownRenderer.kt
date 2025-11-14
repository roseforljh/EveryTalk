package com.android.everytalk.ui.components.markdown

import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.toArgb
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.AbstractMarkwonPlugin
import org.commonmark.node.Code
import android.graphics.Typeface
import android.text.style.StyleSpan
import android.text.style.ForegroundColorSpan

/**
 * 使用 Markwon 渲染 Markdown（TextView + Spannable）
 *
 * 设计要点
 * - 无 WebView、无 HTML 中间态，直接 Spannable，稳定且高性能
 * - 通过 AndroidView 包裹 TextView，Compose 层保持单一组件，避免流式结束的组件类型切换
 * - isStreaming 期间多次 setMarkdown 仅更新同一 TextView，减少重排
 */
@Composable
fun MarkdownRenderer(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    isStreaming: Boolean = false,
    onLongPress: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val markwon = remember {
        Markwon.builder(context)
            // 表格支持
            .usePlugin(TablePlugin.create(context))
            // 启用核心插件
            .usePlugin(CorePlugin.create())
            // 主题与 span 定制（内联 `code` + 围栏代码块样式）
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    // 围栏代码块样式（外部库样式，非语法高亮）：
                    // - 等宽外观由 Markwon 默认处理；这里设定背景、边距与文字色
                    builder
                        .codeBlockTextColor(android.graphics.Color.parseColor("#D0D0D0"))
                        .codeBlockBackgroundColor(android.graphics.Color.parseColor("#1E1E1E")) // 深色背景
                        .codeBlockMargin(0)     // 去额外外边距，避免气泡内跳动
                    // 注意：不在主题里设置 inline code 的背景/颜色，完全交由自定义 SpanFactory 控制，避免任何残留底色
                }
                override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                    // 完全替换内联 `code` 的 Span，确保无背景，仅灰色+加粗
                    builder.setFactory(Code::class.java) { _, _ ->
                        arrayOf(
                            StyleSpan(Typeface.BOLD),
                            ForegroundColorSpan(android.graphics.Color.parseColor("#9E9E9E"))
                        )
                    }
                }
            })
            .build()
    }

    val finalColor = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> MaterialTheme.colorScheme.onSurface
    }

    AndroidView(
        modifier = modifier,
        factory = {
            TextView(it).apply {
                // 统一文本样式（字号）
                val sp = if (style.fontSize.value > 0f) style.fontSize.value else 16f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                setTextColor(finalColor.toArgb())
                // 稳定基线，减少跳动
                setIncludeFontPadding(false)
                // 链接点击仍可用，但确保长按不被吞掉
                movementMethod = LinkMovementMethod.getInstance()
                linksClickable = true
                isClickable = true
                isLongClickable = true

                // 设置长按监听器（返回 true 明确消费，避免下传）
                onLongPress?.let { callback ->
                    setOnLongClickListener {
                        callback()
                        true
                    }
                } ?: run {
                    setOnLongClickListener(null)
                }
            }
        },
        update = { tv ->
            markwon.setMarkdown(tv, markdown)
            // 禁用文本选择 & 点击高亮，避免出现系统高亮底色
            tv.setTextIsSelectable(false)
            tv.highlightColor = android.graphics.Color.TRANSPARENT

            // 确保点击/长按能力开启
            tv.linksClickable = true
            tv.isClickable = true
            tv.isLongClickable = true

            // 更新长按监听器
            if (onLongPress != null) {
                tv.setOnLongClickListener {
                    onLongPress.invoke()
                    true
                }
            } else {
                tv.setOnLongClickListener(null)
            }
        }
    )
}

