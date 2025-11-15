package com.android.everytalk.ui.components.markdown

import android.util.TypedValue
import android.view.MotionEvent
import android.view.Gravity
import android.widget.TextView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.wrapContentWidth
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
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.AbstractMarkwonPlugin
import org.commonmark.node.Code
import android.graphics.Typeface
import android.text.style.StyleSpan
import android.text.style.ForegroundColorSpan
import com.android.everytalk.data.DataClass.Sender

/**
 * 使用 Markwon 渲染 Markdown（TextView + Spannable）
 *
 * 设计要点
 * - 无 WebView、无 HTML 中间态，直接 Spannable，稳定且高性能
 * - 通过 AndroidView 包裹 TextView，Compose 层保持单一组件，避免流式结束的组件类型切换
 * - isStreaming 期间多次 setMarkdown 仅更新同一 TextView，减少重排
 */
// 预编译的正则表达式，避免重复编译
private val MULTIPLE_SPACES_REGEX = Regex(" {2,}")
private val ENUM_ITEM_REGEX = Regex("(?<!\n)\\s+([A-DＡ-Ｄ][\\.．、])\\s")

private fun preprocessAiMarkdown(input: String): String {
    var s = input
    // 1) 规范空白：将 HTML 不换行空格与全角空格替换为普通空格
    s = s.replace("&nbsp;", " ")
        .replace("\u00A0", " ")
        .replace("\u3000", " ")
    // 2) 合并连续空格，避免在同一段中过宽
    s = s.replace(MULTIPLE_SPACES_REGEX, " ")
    // 3) 把 " A. / B. / C. / D. " 这类枚举项从同一行拆为多行列表
    //    例如："... 四大益处  A. xxx  B. yyy  C. zzz  D. www"
    //    变为每项单独一行，交给 Markdown 渲染为列表
    s = s.replace(ENUM_ITEM_REGEX, "\n- $1 ")
    return s
}
@Composable
fun MarkdownRenderer(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    isStreaming: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    sender: Sender = Sender.AI
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    
    val markwon = remember(isDark) {
        android.util.Log.d("MarkdownRenderer", "🔧 初始化 Markwon with JLatexMathPlugin")
        
        // 根据 TextView 的字号动态计算公式大小
        val textSizeSp = if (style.fontSize.value > 0f) style.fontSize.value else 16f
        val mathTextSize = textSizeSp * 5f  // 公式放大 5 倍
        
        Markwon.builder(context)
            // 启用核心插件
            .usePlugin(CorePlugin.create())
            // 数学公式支持 - 必须在 InlineParser 之前注册
            .usePlugin(JLatexMathPlugin.create(mathTextSize) { builder ->
                builder.inlinesEnabled(true)  // 启用内联公式 $...$
                android.util.Log.d("MarkdownRenderer", "✅ JLatexMathPlugin 已配置，字号: $mathTextSize sp")
            })
            // InlineParser 必须在 JLatexMathPlugin 之后
            .usePlugin(MarkwonInlineParserPlugin.create())
            // 表格支持
            .usePlugin(TablePlugin.create(context))
            // 主题与 span 定制（内联 `code` 样式）
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    // 代码块背景和边距
                    builder.codeBlockMargin(0)  // 去额外外边距，避免气泡内跳动
                    // 注意：不在主题里设置 inline code 的背景/颜色，完全交由自定义 SpanFactory 控制
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

    // 🎯 根据发送者决定AndroidView的宽度策略
    val viewModifier = if (sender == Sender.User) {
        modifier.wrapContentWidth()
    } else {
        modifier
    }
    
    AndroidView(
        modifier = viewModifier,
        factory = {
            TextView(it).apply {
                // 统一文本样式（字号）
                val sp = if (style.fontSize.value > 0f) style.fontSize.value else 16f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                setTextColor(finalColor.toArgb())
                // 稳定基线，减少跳动
                setIncludeFontPadding(false)
                
                // 🎯 TextView内部padding - 用户气泡使用相等的上下padding实现垂直居中
                if (sender == Sender.User) {
                    // 用户气泡：使用相等的上下padding，减小水平padding
                    val horizontalPaddingPx = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        1f,  // 减小水平padding
                        resources.displayMetrics
                    ).toInt()
                    val verticalPaddingPx = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        4f,  // 增加垂直padding以实现视觉居中
                        resources.displayMetrics
                    ).toInt()
                    setPadding(horizontalPaddingPx, verticalPaddingPx, horizontalPaddingPx, verticalPaddingPx)
                } else {
                    // AI气泡
                    val paddingPx = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        3f,
                        resources.displayMetrics
                    ).toInt()
                    setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
                }
                
                // 🎯 行间距 - 更小的行间距
                val lineSpacingDp = if (sender == Sender.User) 2f else 3f
                setLineSpacing(
                    TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        lineSpacingDp,
                        resources.displayMetrics
                    ),
                    1.0f
                )
                
                // 🎯 字符间距 - 更小的字符间距
                letterSpacing = if (sender == Sender.User) 0.02f else 0.03f
                
                // 🎯 设置居中对齐 - 对多行文本有效
                gravity = Gravity.CENTER_VERTICAL
                
                // 🔒 禁用文本选择但保留长按功能
                setTextIsSelectable(false)
                highlightColor = android.graphics.Color.TRANSPARENT
                movementMethod = null
                linksClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
                
                // ✅ 仅在需要时启用 TextView 自身的长按处理
                // 对于外层已经有 Compose pointerInput 处理长按（如用户气泡）的场景，
                // 如果这里始终 isLongClickable = true，会拦截长按事件，导致外层拿不到回调。
                isLongClickable = onLongPress != null
                
                // 设置长按监听器（仅当调用方显式传入 onLongPress 时生效）
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
            val processed = preprocessAiMarkdown(markdown)
            
            // 调试：检查是否包含数学公式
            if (processed.contains("$")) {
                android.util.Log.d("MarkdownRenderer", "📐 检测到数学公式标记: ${processed.take(100)}")
            }
            
            markwon.setMarkdown(tv, processed)

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

