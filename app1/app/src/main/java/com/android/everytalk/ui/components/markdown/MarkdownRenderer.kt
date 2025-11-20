package com.android.everytalk.ui.components.markdown

import android.util.TypedValue
import android.view.MotionEvent
import android.view.GestureDetector
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
import android.text.style.ClickableSpan
import android.view.View
import android.text.Spannable
// import io.noties.markwon.image.ImageSpan // 移除，因为 ImageSpan 是 internal 的或者不可直接访问
import io.noties.markwon.image.AsyncDrawable // 使用 AsyncDrawable
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
    onImageClick: ((String) -> Unit)? = null, // 🎯 新增：图片点击回调
    sender: Sender = Sender.AI
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    
    // 🎯 性能优化：使用全局缓存避免重复初始化
    // 修复前：每次重组都初始化Markwon，流式结束后4次初始化耗时200-400ms
    // 修复后：全局缓存，后续命中缓存<1ms
    val textSizeSp = if (style.fontSize.value > 0f) style.fontSize.value else 16f
    val markwon = remember(isDark, textSizeSp) {
        MarkwonCache.getOrCreate(
            context = context,
            isDark = isDark,
            textSize = textSizeSp
        )
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
                // 🎯 启用 LinkMovementMethod 以支持 ClickableSpan
                // ⚠️ 注意：LinkMovementMethod 可能会吞噬触摸事件，导致外层 Compose 的手势（如长按）失效。
                // 解决方案：
                // 1. 使用自定义的 LinkMovementMethod，在未点击到 Link 时返回 false。
                // 2. 或者，在 Compose 层使用 pointerInput 处理所有手势，并手动计算点击位置是否命中 Link。
                //
                // 这里我们尝试方案 1 的变体：如果是 LinkMovementMethod，它会处理 onTouchEvent。
                // 如果点击的是图片，ClickableSpan 会响应。
                // 如果是长按，LinkMovementMethod 默认不处理长按，但 TextView 的 onTouchEvent 会处理长按。
                //
                // 问题在于：如果 movementMethod 不为 null，TextView.onTouchEvent 会调用 movementMethod.onTouchEvent。
                // LinkMovementMethod.onTouchEvent 在 ACTION_UP 时会执行 ClickableSpan.onClick。
                // 如果它返回 true，事件就被消费了。
                //
                // 为了解决冲突，我们可以：
                // 仅当 onImageClick 存在时设置 movementMethod。
                // 并且，我们需要确保长按事件能传递出去。
                //
                // 实际上，Compose 的 pointerInput (detectTapGestures) 是在 View 的 onTouchEvent 之前还是之后？
                // AndroidView 内部是一个 View。Compose 的手势是在 Layout 层面处理的。
                // 如果 View 消费了事件，Compose 可能就收不到了。
                //
                // 让我们尝试一种混合策略：
                // 保持 movementMethod，但确保 TextView 不会因为 movementMethod 而拦截所有事件。
                // 或者，我们自定义一个 MovementMethod，只处理点击，不消费其他事件。
                
                if (onImageClick != null) {
                    movementMethod = android.text.method.LinkMovementMethod.getInstance()
                } else {
                    movementMethod = null
                }
                // linksClickable = false // 这行可能导致 ClickableSpan 不工作？不，这只影响 autoLink。
                
                isFocusable = false
                isFocusableInTouchMode = false
                
                // ✅ 关键：如果设置了 movementMethod，TextView 会在 onTouchEvent 中处理点击。
                // 为了让外层 Compose 的长按生效，我们需要 TextView 返回 false (未消费)，
                // 除非点击中了 ClickableSpan。
                // 但 LinkMovementMethod 的实现通常会消费事件。
                //
                // 替代方案：不使用 LinkMovementMethod，而是在 onTouchEvent 中手动检测 ClickableSpan。
                // 这样我们可以精确控制事件消费。
                
                if (onImageClick != null) {
                    movementMethod = null // 禁用默认的 MovementMethod
                    
                    val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                        override fun onSingleTapUp(e: MotionEvent): Boolean {
                            val textView = it as TextView
                            val text = textView.text
                            if (text is Spannable) {
                                var x = e.x.toInt()
                                var y = e.y.toInt()
                                x -= textView.totalPaddingLeft
                                y -= textView.totalPaddingTop
                                x += textView.scrollX
                                y += textView.scrollY
                                val layout = textView.layout
                                val line = layout.getLineForVertical(y)
                                val off = layout.getOffsetForHorizontal(line, x.toFloat())
                                val link = text.getSpans(off, off, ClickableSpan::class.java)
                                if (link.isNotEmpty()) {
                                    link[0].onClick(textView)
                                    return true
                                }
                            }
                            return false
                        }
                        override fun onLongPress(e: MotionEvent) {
                             onLongPress?.invoke()
                        }
                    })

                    setOnTouchListener { v, event ->
                        gestureDetector.onTouchEvent(event)
                    }
                    isClickable = true
                    isLongClickable = true
                } else {
                    setOnTouchListener(null)
                    isClickable = false
                }

                if (onLongPress != null) {
                   setOnLongClickListener {
                       onLongPress.invoke()
                       true
                   }
                } else {
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

            // 🎯 处理图片点击事件
            if (onImageClick != null) {
                val text = tv.text
                if (text is Spannable) {
                    // Markwon 的 ImagesPlugin 使用 ImageSpan 来渲染图片
                    val imageSpans = text.getSpans(0, text.length, android.text.style.ImageSpan::class.java)
                    imageSpans.forEach { imageSpan ->
                        val start = text.getSpanStart(imageSpan)
                        val end = text.getSpanEnd(imageSpan)
                        
                        // 尝试获取 source
                        var source: String? = null
                        val drawable = imageSpan.drawable
                        if (drawable is AsyncDrawable) {
                            source = drawable.destination
                        } else {
                            source = imageSpan.source
                        }

                        if (source != null) {
                            // 移除已有的 ClickableSpan（如果有）避免重复叠加
                            val existingClickables = text.getSpans(start, end, ClickableSpan::class.java)
                            existingClickables.forEach { text.removeSpan(it) }

                            // 添加新的 ClickableSpan
                            // 注意：这里需要一个 final 的 source 变量供匿名内部类使用
                            val finalSource = source
                            text.setSpan(object : ClickableSpan() {
                                override fun onClick(widget: View) {
                                    onImageClick(finalSource)
                                }
                                
                                // 去除下划线
                                override fun updateDrawState(ds: android.text.TextPaint) {
                                    super.updateDrawState(ds)
                                    ds.isUnderlineText = false
                                }
                            }, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
            }

            // 更新长按监听器 - 移除，改由 Compose 层统一处理
            // if (onLongPress != null) {
            //    tv.setOnLongClickListener {
            //        onLongPress.invoke()
            //        true
            //    }
            // } else {
            //    tv.setOnLongClickListener(null)
            // }
        }
    )
}

