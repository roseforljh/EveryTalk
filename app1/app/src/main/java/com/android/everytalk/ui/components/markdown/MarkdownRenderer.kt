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
import io.noties.markwon.image.AsyncDrawable // 使用 AsyncDrawable
import io.noties.markwon.image.AsyncDrawableSpan
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.components.markdown.MarkdownSpansCache


private val MULTIPLE_SPACES_REGEX = Regex(" {2,}")
private val ENUM_ITEM_REGEX = Regex("(?<!\n)\\s+([A-DＡ-Ｄ][\\.．、])\\s")

private fun preprocessAiMarkdown(input: String, isStreaming: Boolean = false): String {
    var s = input

    // ============================================================================================
    // 最小化预处理策略 (Minimal Preprocessing Strategy)
    // ============================================================================================
    //
    // 之前的激进结构重写规则（如自动换行、自动缩进、列表拆分等）导致了很多副作用：
    // - 错误触发代码块（4空格缩进）
    // - 破坏粗体语法（** 被拆开）
    // - 渲染结果不可预测
    //
    // 现在只保留绝对安全的、不改变文本结构的清理操作。
    // 要获得干净的 Markdown 排版，应在生成端（Prompt / System Prompt）约束模型输出规范格式。
    // ============================================================================================

    // 1. 货币符号修复: $$30 -> \$30
    // Markwon 会将 $$ 解析为数学公式块，导致显示错误。需转义。
    if (s.contains("$$")) {
        val currencyPattern = Regex("\\$\\$(?=\\d)")
        s = s.replace(currencyPattern) { "\\$" }
    }

    // 2. Base64 图片链接清理
    // 修复 Base64 字符串中可能包含的换行符，防止 Markdown 图片解析失败。
    if (s.contains("data:image/")) {
        val base64ImagePattern = Regex("(\\!\\[[^\\]]*\\]\\()\\s*(data:image\\/[^)]+)\\s*(\\))", setOf(RegexOption.DOT_MATCHES_ALL))
        s = s.replace(base64ImagePattern) { mr ->
            val prefix = mr.groupValues[1]
            val data = mr.groupValues[2].filter { !it.isWhitespace() }
            val suffix = mr.groupValues[3]
            prefix + data + suffix
        }
    }

    // 3. 特殊空格归一化
    s = s.replace("&nbsp;", " ")
        .replace("\u00A0", " ")
        .replace("\u3000", " ")

    // ============================================================================================
    // 格式化增强 (Formatting Enhancements)
    // 只保留用户明确需要的功能，移除会导致问题的列表处理逻辑
    // ============================================================================================

    // 4.1 修复紧凑标题 (Compact Headers Fix) - 用户明确要求保留
    // 某些模型输出标题时没有换行，导致Markwon无法正确识别
    // 例如: "前文## 标题" -> "前文\n\n## 标题"
    // 只处理 H2-H6 (##-######)，避免误伤 H1 (#) 和 hashtag (#tag)
    s = s.replace(Regex("(?<!^)(?<!\\n)(#{2,6})(?=[^#])"), "\n\n$1")
    s = s.replace(Regex("(?<!^)(?<!\\n)(#{1}\\s)"), "\n\n$1") // H1 必须后跟空格才处理
    
    // 修复标题后缺少空格: "##Title" -> "## Title"
    s = s.replace(Regex("(?<=^|\\n)(#{1,6})(?=[^#\\s])"), "$1 ")

    // 4.2 紧凑列表（仅限标题行内的第一个 "- "）拆行
    // 典型模式: "## 政策层面- **经济政策**" -> "## 政策层面\n- **经济政策**"
    // 只在标题行内部寻找第一个 "- "，避免影响普通段落和子列表。
    s = s.replace(
        Regex("^(#{1,6}[^\\n]*?)(?:\\s*)-\\s", RegexOption.MULTILINE)
    ) { mr ->
        val headingPart = mr.groupValues[1].trimEnd()
        "$headingPart\n- "
    }

    // 5. 强制换行处理 (Hard Break Enforcement) - 用户明确要求保留
    // Markwon/CommonMark 默认将单个换行符视为空格 (Soft Break)。
    // 将单换行转换为 Markdown 硬换行 (两个空格 + \n)。
    val isIsolatedCodeBlock = s.trimStart().startsWith("```")
    if (!isIsolatedCodeBlock) {
        s = s.replace(Regex("(?<!\\n)(?<!  )\\n(?!\\n)"), "  \n")
    }

    // 6. 内联数学公式转换 - 用户明确要求保留
    // Convert inline math ($...$) to block math ($$...$$)
    val inlineMathPattern = Regex("(?<!\\\\)(?<!\\$)\\$([^$\\n]+?)(?<!\\\\)(?<!\\$)\\$")
    if (s.contains("$")) {
        s = s.replace(inlineMathPattern) { matchResult ->
            "$$" + matchResult.groupValues[1] + "$$"
        }
    }

    // ============================================================================================
    // 已移除的功能（会导致代码块和粗体破坏问题）：
    // - 紧凑列表修复 (Compact Lists Fix) - 已移除，会触发代码块
    // - 列表项缩进处理 - 已移除，会触发代码块
    // - 列表符号后自动加空格 - 已移除，会破坏 ** 粗体语法
    // ============================================================================================

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
    onImageClick: ((String) -> Unit)? = null,
    sender: Sender = Sender.AI,
    contentKey: String = "",
    disableVerticalPadding: Boolean = false // 新增参数：允许禁用垂直padding
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    
    val baseTextSizeSp = if (style.fontSize.value > 0f) style.fontSize.value else 16f
    // 统一气泡内文本字号：用户与 AI 一样，整体略小于之前的 AI 放大效果
    val textSizeSp = baseTextSizeSp * 1.05f
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

    // 
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
                val baseSp = if (style.fontSize.value > 0f) style.fontSize.value else 16f
                // 用户与 AI 使用相同字号，整体略小于之前的 AI 放大效果
                val sp = baseSp * 1.05f
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                setTextColor(finalColor.toArgb())
                // 稳定基线，减少跳动
                // setIncludeFontPadding(false) // 导致数学公式垂直被截断，必须开启
                setIncludeFontPadding(true)
                
                // TextView内部padding - 用户气泡使用相等的上下padding实现垂直居中
                if (sender == Sender.User) {
                    // 用户气泡：使用相等的上下padding，减小水平padding
                    val horizontalPaddingPx = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        1f,  // 减小水平padding
                        resources.displayMetrics
                    ).toInt()
                    val verticalPaddingPx = if (disableVerticalPadding) 0 else TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        4f,  // 增加垂直padding以实现视觉居中
                        resources.displayMetrics
                    ).toInt()
                    setPadding(horizontalPaddingPx, verticalPaddingPx, horizontalPaddingPx, verticalPaddingPx)
                } else {
                    // AI气泡
                    // 增加 padding 以防止数学公式（特别是斜体/积分符号）在边缘被截断
                    // 16dp 应该足够容纳大部分溢出的字形
                    val paddingPx = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        1f,
                        resources.displayMetrics
                    ).toInt()
                    
                    val verticalPaddingPx = if (disableVerticalPadding) 0 else paddingPx
                    
                    setPadding(paddingPx, verticalPaddingPx, paddingPx, verticalPaddingPx)
                }
                
                // 行间距 - 用户与 AI 区分设置
                // 用户保持略紧凑，AI 适度加大上下行距离
                // 根据反馈“稍微减文本之间的距离”，将 AI 行间距从 6f 调整为 5f
                val lineSpacingDp = if (sender == Sender.User) 2f else 5f
                setLineSpacing(
                    TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        lineSpacingDp,
                        resources.displayMetrics
                    ),
                    1.0f
                )
                
                // 字符间距 - 统一减小左右间距，使 AI 气泡内文本更紧凑，同时保持用户与 AI 一致
                letterSpacing = 0.02f
                
                // 设置居中对齐 - 对多行文本有效
                // gravity = Gravity.CENTER_VERTICAL // 移除垂直居中，避免长文/图片显示异常
                
                // 禁用文本选择但保留长按功能
                setTextIsSelectable(false)
                highlightColor = android.graphics.Color.TRANSPARENT
                // 启用 LinkMovementMethod 以支持 ClickableSpan
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
                
                // 关键：如果设置了 movementMethod，TextView 会在 onTouchEvent 中处理点击。
                // 为了让外层 Compose 的长按生效，我们需要 TextView 返回 false (未消费)，
                // 除非点击中了 ClickableSpan。
                // 但 LinkMovementMethod 的实现通常会消费事件。
                //
                // 替代方案：不使用 LinkMovementMethod，而是在 onTouchEvent 中手动检测 ClickableSpan。
                // 这样我们可以精确控制事件消费。
                
                if (onImageClick != null) {
                    // 自定义触摸监听：优先检测是否命中 ClickableSpan（图片），
                    // 如果命中则执行 onClick 并消费事件；
                    // 否则返回 false 交由父层处理（如长按等）。
                    movementMethod = null // 禁用 LinkMovementMethod，完全手动接管
                    linksClickable = false
                    isClickable = true
                    isLongClickable = true

                    setOnTouchListener { v, event ->
                        // 仅在 ACTION_UP 时检测点击
                        if (event.action == MotionEvent.ACTION_UP) {
                            val tvLocal = v as TextView
                            val text = tvLocal.text
                            if (text is android.text.Spannable) {
                                var x = event.x.toInt()
                                var y = event.y.toInt()

                                x -= tvLocal.totalPaddingLeft
                                y -= tvLocal.totalPaddingTop
                                x += tvLocal.scrollX
                                y += tvLocal.scrollY

                                val layout = tvLocal.layout
                                if (layout != null) {
                                    val line = layout.getLineForVertical(y)
                                    val off = layout.getOffsetForHorizontal(line, x.toFloat())

                                    // 几何命中测试：直接检查触摸点是否在 ImageSpan 的 bounds 内
                                    // 这种方式不依赖 getOffsetForHorizontal 的光标位置计算，对图片更准确
                                    val lineStart = layout.getLineStart(line)
                                    val lineEnd = layout.getLineEnd(line)
                                    
                                    // 1. 查找该行内的所有图片 Span (AsyncDrawableSpan)
                                    val imageSpans = text.getSpans(lineStart, lineEnd, AsyncDrawableSpan::class.java)
                                    
                                    for (imageSpan in imageSpans) {
                                        val spanStart = text.getSpanStart(imageSpan)
                                        // 获取图片在该行的水平位置
                                        val xStart = layout.getPrimaryHorizontal(spanStart)
                                        val drawable = imageSpan.drawable
                                        val bounds = drawable.bounds
                                        val width = bounds.width()
                                        
                                        // 检查 x 坐标是否在图片范围内 (允许一定的触摸误差)
                                        val touchSlop = 20
                                        if (x >= (xStart - touchSlop) && x <= (xStart + width + touchSlop)) {
                                            // 命中！查找对应的 source 并触发点击
                                            val source = drawable.destination
                                            if (!source.isNullOrEmpty()) {
                                                android.util.Log.d("MarkdownRenderer", "Geometric Hit: x=$x, imgX=$xStart, w=$width, src=$source")
                                                onImageClick(source)
                                                return@setOnTouchListener true
                                            }
                                        }
                                    }

                                    // 2. 兜底：查找 ImageSpan (非 AsyncDrawableSpan)
                                    val standardImageSpans = text.getSpans(lineStart, lineEnd, android.text.style.ImageSpan::class.java)
                                    for (imageSpan in standardImageSpans) {
                                        val spanStart = text.getSpanStart(imageSpan)
                                        val xStart = layout.getPrimaryHorizontal(spanStart)
                                        val drawable = imageSpan.drawable
                                        val width = drawable.bounds.width()
                                        
                                        if (x >= xStart && x <= (xStart + width)) {
                                            val source = imageSpan.source
                                            if (!source.isNullOrEmpty()) {
                                                android.util.Log.d("MarkdownRenderer", "Geometric Hit (Standard): src=$source")
                                                onImageClick(source)
                                                return@setOnTouchListener true
                                            }
                                        }
                                    }

                                    android.util.Log.d("MarkdownRenderer", "No geometric image hit at line $line, x=$x")
                                }
                            }
                        }
                        // 返回 false，让 View 继续处理长按等其他事件
                        false
                    }

                    // 明确提供长按回调
                    setOnLongClickListener {
                        onLongPress?.invoke()
                        true
                    }
                } else {
                    movementMethod = null
                    linksClickable = false
                    setOnTouchListener(null)
                    isClickable = false
                    setOnLongClickListener(null)
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
            // ⚡️ 性能优化：如果内容未变更，直接跳过更新，防止流式结束时的闪烁/跳动
            if (tv.tag == markdown) {
                return@AndroidView
            }
            tv.tag = markdown

            // 缓存优化：尝试从缓存获取 Spanned 对象
            val sp = if (style.fontSize.value > 0f) style.fontSize.value else 16f
            val cacheKey = if (contentKey.isNotBlank() && !isStreaming) {
                // Append version suffix to invalidate old cache entries after regex fixes
                // v11: Fixed duplicate variables and switched to Theme multipliers for headings
                MarkdownSpansCache.generateKey(contentKey + "_v11", isDark, sp)
            } else ""

            val cachedSpanned = if (cacheKey.isNotBlank()) MarkdownSpansCache.get(cacheKey) else null

            if (cachedSpanned != null) {
                // 命中缓存：直接设置文本，跳过解析
                tv.text = cachedSpanned
                if (com.android.everytalk.config.PerformanceConfig.ENABLE_PERFORMANCE_LOGGING) {
                    android.util.Log.d("MarkdownRenderer", "Spans Cache HIT: $cacheKey")
                }
            } else {
                // 未命中缓存：执行完整解析
                // 即使完全移除了差异化逻辑，保留参数接口以便后续扩展，但目前逻辑已统一
                val processed = preprocessAiMarkdown(markdown, isStreaming)

                // 调试：检查是否包含数学公式
                if (processed.contains("$")) {
                    android.util.Log.d("MarkdownRenderer", "检测到数学公式标记: ${processed.take(100)}")
                }

                // 分步解析以支持缓存
                // markwon.setMarkdown(tv, processed) // 原逻辑
                
                // 新逻辑：Parse -> Render -> Cache -> Set
                val node = markwon.parse(processed)
                val spanned = markwon.render(node)
                
                if (cacheKey.isNotBlank()) {
                    MarkdownSpansCache.put(cacheKey, spanned)
                    if (com.android.everytalk.config.PerformanceConfig.ENABLE_PERFORMANCE_LOGGING) {
                        android.util.Log.d("MarkdownRenderer", "Spans Cache MISS, cached: $cacheKey")
                    }
                }
                
                markwon.setParsedMarkdown(tv, spanned)
            }

            // 处理图片点击事件（兼容 AsyncDrawableSpan 与 ImageSpan）
            if (onImageClick != null) {
                val text = tv.text
                if (text is Spannable) {
                    // 1) 先处理 Markwon 的 AsyncDrawableSpan
                    val asyncSpans = text.getSpans(0, text.length, AsyncDrawableSpan::class.java)
                    asyncSpans.forEach { span ->
                        val start = text.getSpanStart(span)
                        val end = text.getSpanEnd(span)
                        val drawable = span.drawable
                        val source: String? = drawable.destination

                        // 清理该范围内的历史 ClickableSpan，防止叠加
                        text.getSpans(start, end, ClickableSpan::class.java).forEach { text.removeSpan(it) }

                        val finalSource = source ?: ""
                        android.util.Log.d("MarkdownRenderer", "Attach ClickableSpan on AsyncDrawableSpan: range=[$start,$end), src.len=${finalSource.length}")
                        text.setSpan(object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                android.util.Log.d("MarkdownRenderer", "onImageClick triggered (AsyncDrawableSpan)")
                                onImageClick(finalSource)
                            }
                            override fun updateDrawState(ds: android.text.TextPaint) {
                                ds.isUnderlineText = false
                            }
                        }, start, end, Spannable.SPAN_EXCLUSIVE_INCLUSIVE)
                    }
                    // 若 asyncSpans 为空，打印一次日志帮助定位
                    if (asyncSpans.isEmpty()) {
                        android.util.Log.d("MarkdownRenderer", "No AsyncDrawableSpan found; will fallback to ImageSpan")
                    }

                    // 2) 再兜底处理系统的 ImageSpan（某些设备/版本可能使用它）
                    val imageSpans = text.getSpans(0, text.length, android.text.style.ImageSpan::class.java)
                    imageSpans.forEach { imageSpan ->
                        val start = text.getSpanStart(imageSpan)
                        val end = text.getSpanEnd(imageSpan)
                        val source: String = imageSpan.source ?: ""

                        // 清理该范围内的历史 ClickableSpan，防止叠加
                        text.getSpans(start, end, ClickableSpan::class.java).forEach { text.removeSpan(it) }

                        val finalSource = source
                        android.util.Log.d("MarkdownRenderer", "Attach ClickableSpan on ImageSpan: range=[$start,$end), src.len=${finalSource.length}")
                        text.setSpan(object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                android.util.Log.d("MarkdownRenderer", "onImageClick triggered (ImageSpan)")
                                onImageClick(finalSource)
                            }
                            override fun updateDrawState(ds: android.text.TextPaint) {
                                ds.isUnderlineText = false
                            }
                        }, start, end, Spannable.SPAN_EXCLUSIVE_INCLUSIVE)
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

