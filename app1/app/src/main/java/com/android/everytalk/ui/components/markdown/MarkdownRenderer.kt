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
private val WINDOWS_PATH_REGEX = Regex("^[A-Za-z]:\\\\")

// 预编译 preprocessAiMarkdown 中的正则，避免每帧重复编译
private val BASE64_IMAGE_PATTERN = Regex(
    "(\\![\\[^\\]]*\\]\\()\\s*(<?)(:?data:image\\/[^)>]+)(>?)\\s*(\\))",
    setOf(RegexOption.DOT_MATCHES_ALL)
)
private val FULL_WIDTH_PAREN_BOLD_REGEX = Regex("）\\*\\*")
private val QUOTED_BOLD_PATTERN = Regex("""\*\*[""\u201C\u201D''\u2018\u2019「」『』«»](.+?)[""\u201C\u201D''\u2018\u2019「」『』«»]\*\*""")
private val HEADER_SPACE_REGEX = Regex("(?<=^|\\n)(#{1,6})(?=[^#\\s])")
private val LONG_HEADER_REGEX = Regex("^(#{1,6})(?=\\s.{50,})", RegexOption.MULTILINE)
private val MULTILINE_BLOCK_DOLLAR_PATTERN = Regex(
    "\\[double dollar]\\s*\\n([\\s\\S]*?)\\n\\s*\\[double dollar]",
    RegexOption.MULTILINE
)
private val BLOCK_PLACEHOLDER_PATTERN = Regex("(?m)^[ \\t]*\\[double dollar][ \\t]*$")
private val INLINE_MATH_PATTERN = Regex("(?<!\\\\)(?<!\\$)\\$([^$\\n]+?)(?<!\\\\)(?<!\\$)\\$")
private val TRIPLE_DOLLAR_CURRENCY_PATTERN = Regex("(?<=^|\\s)\\$\\$\\$(?=\\d)")
private val SINGLE_CURRENCY_PATTERN = Regex("(?<=^|\\s)(?<!\\\\)\\$(?=\\d)")
private val DOUBLE_CURRENCY_PATTERN = Regex("(?<=^|\\s)\\$\\$(?=\\d)")

private fun isSupportedImageSource(raw: String?): Boolean {
    if (raw.isNullOrBlank()) return false
    val s = raw.trim()
    if (s.startsWith("http://") || s.startsWith("https://")) return true
    if (s.startsWith("data:image", ignoreCase = true)) return true
    if (s.startsWith("content://") || s.startsWith("file://")) return true
    if (s.startsWith("/")) return true
    if (WINDOWS_PATH_REGEX.containsMatchIn(s)) return true
    return false
}

private data class MarkdownRenderSignature(
    val processed: String, // 预处理后的文本，包含转义逻辑的结果
    val isDark: Boolean,
    val textSizeSp: Float
)

/**
 * 流式输出时：仅转义未闭合的数学公式标记。
 * 已闭合的公式（如 $$E=mc^2$$）保留原样，允许 JLatexMath 立即渲染。
 * 未闭合的公式（如 $$\int_0^1 f(x)）的起始标记被转义，
 * 防止 JLatexMath 尝试解析残缺公式导致布局跳变。
 */
private fun escapeUnclosedMathForStreaming(input: String): String {
    if (!input.contains('$') && !input.contains("\\[")) return input
    
    var inInlineCode = false
    var inMathBlock = false
    var mathBlockStart = -1
    var mathBlockMarker = ""
    var inInlineMath = false
    var inlineMathStart = -1
    
    var i = 0
    while (i < input.length) {
        val ch = input[i]
        
        // 跟踪行内代码
        if (ch == '`') {
            inInlineCode = !inInlineCode
            i++
            continue
        }
        if (inInlineCode) { i++; continue }
        
        // 如果在数学块内，寻找闭合标记
        if (inMathBlock) {
            if (input.startsWith(mathBlockMarker, i)) {
                inMathBlock = false
                mathBlockStart = -1
                i += mathBlockMarker.length
                continue
            }
            i++
            continue
        }
        
        // 检测 $$ 开启
        if (input.startsWith("$$", i)) {
            inMathBlock = true
            mathBlockMarker = "$$"
            mathBlockStart = i
            i += 2
            continue
        }
        
        // 检测 \[ 开启
        if (ch == '\\' && i + 1 < input.length && input[i + 1] == '[') {
            inMathBlock = true
            mathBlockMarker = "\\]"
            mathBlockStart = i
            i += 2
            continue
        }
        
        // 检测 $ （行内数学开关）
        if (ch == '$') {
            val isCurrency = i + 1 < input.length && input[i + 1].isDigit()
            if (!isCurrency) {
                inInlineMath = !inInlineMath
                if (inInlineMath) inlineMathStart = i else inlineMathStart = -1
            }
            i++
            continue
        }
        
        i++
    }
    
    // 所有标签都已闭合，直接返回，允许完整渲染
    if (!inMathBlock && !inInlineMath) return input
    
    // 仅转义未闭合的开启标记
    val sb = StringBuilder(input)
    data class Edit(val pos: Int, val origLen: Int, val replacement: String)
    val edits = mutableListOf<Edit>()
    
    if (inMathBlock && mathBlockStart >= 0) {
        if (input.startsWith("$$", mathBlockStart)) {
            edits.add(Edit(mathBlockStart, 2, "\\$\\$"))
        } else if (input.startsWith("\\[", mathBlockStart)) {
            edits.add(Edit(mathBlockStart, 2, "\\\\["))
        }
    }
    if (inInlineMath && inlineMathStart >= 0) {
        edits.add(Edit(inlineMathStart, 1, "\\$"))
    }
    
    edits.sortByDescending { it.pos }
    for (edit in edits) {
        sb.replace(edit.pos, edit.pos + edit.origLen, edit.replacement)
    }
    
    return sb.toString()
}

/**
 * 预处理 Markdown 文本（简化版）
 *
 * 由于代码块和表格已由 ContentParser 提取，此函数只需处理纯文本内容。
 */
internal fun preprocessAiMarkdown(input: String, isStreaming: Boolean = false): String {
    if (input.isBlank()) return input

    var s = input

    // 1. HTML Escape
    s = s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    // 2. Base64 Image: 移除 Base64 中的空白
    if (s.contains("data:image/")) {
        val base64ImagePattern = Regex("(\\!\\[[^\\]]*\\]\\()\\s*(<?)(data:image\\/[^)>]+)(>?)\\s*(\\))", setOf(RegexOption.DOT_MATCHES_ALL))
        s = s.replace(base64ImagePattern) { mr ->
            val prefix = mr.groupValues[1]
            val openAngle = mr.groupValues[2]
            val data = mr.groupValues[3].filter { !it.isWhitespace() }
            val closeAngle = mr.groupValues[4]
            val suffix = mr.groupValues[5]
            prefix + openAngle + data + closeAngle + suffix
        }
    }

    // 4. Special Spaces
    s = s.replace("&nbsp;", " ")
        .replace("\u00A0", " ")
        .replace("\u3000", " ")

    // 5. Full-width Paren Bold Fix
    s = s.replace(FULL_WIDTH_PAREN_BOLD_REGEX, "**）")

    // 5.1 Fix: **"xxx"** 等引号包裹加粗无法渲染的问题
    s = s.replace(QUOTED_BOLD_PATTERN) { mr ->
        val inner = mr.groupValues[1]
        "\"**${inner}**\""
    }

    // 6. Headers: 确保 # 后有空格
    s = s.replace(HEADER_SPACE_REGEX, "$1 ")
    s = s.replace(LONG_HEADER_REGEX) { mr ->
        "\\" + mr.groupValues[1]
    }

    // 7. Hard Break Enforcement
    val lines = s.lines()
    val lastIndex = lines.size - 1
    s = lines.mapIndexed { index, line ->
        val trimmedLine = line.trimStart()
        val isHeadingLine = trimmedLine.startsWith("# ") ||
                trimmedLine.startsWith("## ") ||
                trimmedLine.startsWith("### ") ||
                trimmedLine.startsWith("#### ") ||
                trimmedLine.startsWith("##### ") ||
                trimmedLine.startsWith("###### ")

        val isEmptyLine = line.isBlank()
        val isTableLine = line.contains("|")
        val hasUnbalancedBold = line.split("**").size % 2 == 0
        val trimmedEnd = line.trimEnd()
        val endsWithMathDelimiter = trimmedEnd.endsWith("$$") || trimmedEnd.endsWith("$")
        // 无论是否流式渲染，有未闭合的 ** 都跳过硬换行，避免破坏加粗语法
        val shouldSkipHardBreak = isTableLine || hasUnbalancedBold || endsWithMathDelimiter

        when {
            index == lastIndex -> line
            isEmptyLine -> "$line\n"
            isHeadingLine -> "$line\n"
            shouldSkipHardBreak -> "$line\n"
            line.endsWith("  ") -> "$line\n"
            else -> "$line  \n"
        }
    }.joinToString("")

    // 8. 块级 [double dollar] 占位符转换（仅处理独立行/跨行块）
    if (s.contains("[double dollar]")) {
        s = s.replace(MULTILINE_BLOCK_DOLLAR_PATTERN) { matchResult ->
            val inner = matchResult.groupValues[1].trim()
            if (inner.isEmpty()) "" else "\$\$${inner}\$\$"
        }

        s = BLOCK_PLACEHOLDER_PATTERN.replace(s) { "\$\$" }
    }

    // 8.5 行内数学分隔符单向规范化（跳过代码围栏/行内代码）
    s = MathDelimiterNormalizer.normalize(s)

    // 9. Inline Math: 将单个 $ 转换为 $$（统一交给 JLatexMath 的 $$ 分隔符渲染）
    if (s.contains("$")) {
        s = s.replace(INLINE_MATH_PATTERN) { matchResult ->
            "$$" + matchResult.groupValues[1] + "$$"
        }
    }

    // 9.5 修复由单 $货币 转义导致的 $$$数字 形态
    s = s.replace(TRIPLE_DOLLAR_CURRENCY_PATTERN) { "\\\\$" }

    // 10. Currency: 只有在确认没有配对成公式之后，才对独立的 $数字 或 $$数字 做货币转义
    s = s.replace(SINGLE_CURRENCY_PATTERN) { "\\$" }
    s = s.replace(DOUBLE_CURRENCY_PATTERN) { "\\\\$" }

    // 最后一步：流式期间，仅转义残留的未闭合数学标记
    // 已闭合的公式保留原样，允许立即渲染
    if (isStreaming) {
        s = escapeUnclosedMathForStreaming(s)
    }

    return s.trimStart('\n')
}

@Composable
fun MarkdownRenderer(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    isStreaming: Boolean = false,
    onLongPress: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
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
                // 根据反馈"稍微减文本之间的距离"，将 AI 行间距从 6f 调整为 5f
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
                
                isFocusable = false
                isFocusableInTouchMode = false
                
                // 统一处理触摸事件：图片点击 + 长按坐标捕获
                if (onImageClick != null || onLongPress != null) {
                    movementMethod = null // 禁用 LinkMovementMethod，完全手动接管
                    linksClickable = false
                    isClickable = true
                    isLongClickable = true
                    
                    var lastTouchRawX = 0f
                    var lastTouchRawY = 0f

                    setOnTouchListener { v, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            lastTouchRawX = event.rawX
                            lastTouchRawY = event.rawY
                        }
                        
                        // 仅在 ACTION_UP 时检测图片点击
                        if (onImageClick != null && event.action == MotionEvent.ACTION_UP) {
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
                                
                                    // 几何命中测试：直接检查触摸点是否在 ImageSpan 的 bounds 内
                                    val lineStart = layout.getLineStart(line)
                                    val lineEnd = layout.getLineEnd(line)
                                
                                    val imageSpans = text.getSpans(lineStart, lineEnd, AsyncDrawableSpan::class.java)
                                    for (imageSpan in imageSpans) {
                                        val spanStart = text.getSpanStart(imageSpan)
                                        val xStart = layout.getPrimaryHorizontal(spanStart)
                                        val drawable = imageSpan.drawable
                                        val bounds = drawable.bounds
                                        val width = bounds.width()

                                        val touchSlop = 20
                                        if (x >= (xStart - touchSlop) && x <= (xStart + width + touchSlop)) {
                                            val sourceRaw = drawable.destination
                                            val source = sourceRaw?.trim().orEmpty()
                                            if (isSupportedImageSource(source)) {
                                                onImageClick(source)
                                                return@setOnTouchListener true
                                            }
                                        }
                                    }

                                    val standardImageSpans = text.getSpans(lineStart, lineEnd, android.text.style.ImageSpan::class.java)
                                    for (imageSpan in standardImageSpans) {
                                        val spanStart = text.getSpanStart(imageSpan)
                                        val xStart = layout.getPrimaryHorizontal(spanStart)
                                        val drawable = imageSpan.drawable
                                        val width = drawable.bounds.width()

                                        if (x >= xStart && x <= (xStart + width)) {
                                            val sourceRaw = imageSpan.source
                                            val source = sourceRaw?.trim().orEmpty()
                                            if (isSupportedImageSource(source)) {
                                                onImageClick(source)
                                                return@setOnTouchListener true
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // 返回 false，让 View 继续处理长按等其他事件
                        false
                    }

                    if (onLongPress != null) {
                        setOnLongClickListener {
                            onLongPress.invoke(androidx.compose.ui.geometry.Offset(lastTouchRawX, lastTouchRawY))
                            true
                        }
                    } else {
                        setOnLongClickListener(null)
                    }
                } else {
                    movementMethod = null
                    linksClickable = false
                    setOnTouchListener(null)
                    isClickable = false
                    setOnLongClickListener(null)
                }
            }
        },
        update = { tv ->
            val processed = preprocessAiMarkdown(markdown, isStreaming)
            val signature = MarkdownRenderSignature(
                processed = processed,
                isDark = isDark,
                textSizeSp = textSizeSp
            )

            if (tv.tag == signature) {
                return@AndroidView
            }
            tv.tag = signature

            // 缓存优化：尝试从缓存获取 Spanned 对象
            // 流式期间：上游 TableAwareText 只对已稳定的 part 提供 contentKey
            // 最后一个（持续变化的）part 的 contentKey 为空，不缓存
            val sp = if (style.fontSize.value > 0f) style.fontSize.value else 16f
            val cacheKey = if (contentKey.isNotBlank()) {
                MarkdownSpansCache.generateKey(contentKey + "_v40", isDark, sp)
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
                // 注意：这里不再需要调用 preprocessAiMarkdown，因为上面已经调用过了
                
                // 调试：检查是否包含数学公式
                if (processed.contains("$")) {
                    android.util.Log.d("MarkdownRenderer", "检测到数学公式标记: ${processed.take(100)}")
                }

                // 分步解析以支持缓存
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

            tv.requestLayout()
            tv.invalidate()

            // 处理图片点击事件（兼容 AsyncDrawableSpan 与 ImageSpan）
            if (onImageClick != null) {
                val text = tv.text
                if (text is Spannable) {
                    val asyncSpans = text.getSpans(0, text.length, AsyncDrawableSpan::class.java)
                    asyncSpans.forEach { span ->
                        val start = text.getSpanStart(span)
                        val end = text.getSpanEnd(span)
                        val drawable = span.drawable
                        val source: String? = drawable.destination
                        val finalSource = source?.trim().orEmpty()
                        if (!isSupportedImageSource(finalSource)) return@forEach

                        text.getSpans(start, end, ClickableSpan::class.java).forEach { text.removeSpan(it) }

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

                    val imageSpans = text.getSpans(0, text.length, android.text.style.ImageSpan::class.java)
                    imageSpans.forEach { imageSpan ->
                        val start = text.getSpanStart(imageSpan)
                        val end = text.getSpanEnd(imageSpan)
                        val source: String = imageSpan.source ?: ""
                        val finalSource = source.trim()
                        if (!isSupportedImageSource(finalSource)) return@forEach

                        text.getSpans(start, end, ClickableSpan::class.java).forEach { text.removeSpan(it) }

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
