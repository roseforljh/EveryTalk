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
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.AbstractMarkwonPlugin
import org.commonmark.node.Code
import android.graphics.Typeface
import android.text.style.StyleSpan

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
    isStreaming: Boolean = false
) {
    val context = LocalContext.current
    val markwon = remember {
        Markwon.builder(context)
            // 表格支持
            .usePlugin(TablePlugin.create(context))
            // 启用核心插件
            .usePlugin(CorePlugin.create())
            // 主题与 span 定制
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    // 内联代码：灰色文字、透明背景
                    builder
                        .codeTextColor(android.graphics.Color.parseColor("#9E9E9E"))
                        .codeBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
                override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                    // 为 `code` 追加粗体样式
                    builder.appendFactory(Code::class.java) { _, _ ->
                        StyleSpan(Typeface.BOLD)
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
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { tv ->
            markwon.setMarkdown(tv, markdown)
            // TextView 的 isTextSelectable 仅有 getter，需调用 setter 方法
            tv.setTextIsSelectable(false)
        }
    )
}

// Color 转 ARGB
private fun Color.toArgb(): Int =
    android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt()
    )
