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

private data class MarkdownRenderSignature(
    val markdown: String,
    val isStreaming: Boolean,
    val isDark: Boolean,
    val textSizeSp: Float
)

private data class MarkdownSegment(
    val content: String,
    val isCode: Boolean,
    val isOpen: Boolean = false
)

private fun preprocessAiMarkdown(input: String, isStreaming: Boolean = false): String {
    if (input.isBlank()) return input

    // 1. Split into segments
    val segments = mutableListOf<MarkdownSegment>()
    val fenceRegex = Regex("```")
    val matches = fenceRegex.findAll(input)

    var lastIndex = 0
    var inCodeBlock = false
    var codeBlockStart = 0

    for (match in matches) {
        if (!inCodeBlock) {
            // Found start of code block
            if (match.range.first > lastIndex) {
                segments.add(MarkdownSegment(input.substring(lastIndex, match.range.first), isCode = false))
            }
            inCodeBlock = true
            codeBlockStart = match.range.first
        } else {
            // Found end of code block
            val end = match.range.last + 1
            segments.add(MarkdownSegment(input.substring(codeBlockStart, end), isCode = true))
            inCodeBlock = false
            lastIndex = end
        }
    }

    // Handle remaining content
    if (inCodeBlock) {
        // We are inside an open code block
        segments.add(MarkdownSegment(input.substring(codeBlockStart), isCode = true, isOpen = true))
    } else if (lastIndex < input.length) {
        // Remaining text
        segments.add(MarkdownSegment(input.substring(lastIndex), isCode = false))
    }

    // 2. Process and Join
    val s = segments.joinToString("") { seg ->
        if (seg.isCode) {
            if (seg.isOpen && isStreaming) {
                // Auto-close open code blocks during streaming to prevent flickering
                // Ensure there is a newline before closing fence if content doesn't end with one
                val content = seg.content
                if (content.endsWith("\n")) "$content```" else "$content\n```"
            } else {
                seg.content
            }
        } else {
            processTextSegment(seg.content, isStreaming)
        }
    }

    // 移除文本开头的多余换行符
    return s.trimStart('\n')
}

private fun processTextSegment(input: String, isStreaming: Boolean): String {
    var s = input

    // 0. HTML Escape
    s = s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    // 1. Currency
    if (s.contains("$$")) {
        val currencyPattern = Regex("\\$\\$(?=\\d)")
        s = s.replace(currencyPattern) { "\\$" }
    }

    // 2. Base64
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

    // 3. Special Spaces
    s = s.replace("&nbsp;", " ")
        .replace("\u00A0", " ")
        .replace("\u3000", " ")

    val fullWidthParenBoldFix = Regex("）\\*\\*")
    s = s.replace(fullWidthParenBoldFix, "**）")

    // 4. Formatting (Headers)
    s = s.replace(Regex("(?<=^|\\n)(#{1,6})(?=[^#\\s])"), "$1 ")
    s = s.replace(Regex("^(#{1,6})(?=\\s.{50,})", RegexOption.MULTILINE)) { mr ->
        "\\" + mr.groupValues[1]
    }

    // 5. Hard Break Enforcement
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
        val shouldSkipHardBreak = isTableLine || (isStreaming && hasUnbalancedBold)

        when {
            index == lastIndex -> line
            isEmptyLine -> "$line\n"
            isHeadingLine -> "$line\n"
            shouldSkipHardBreak -> "$line\n"
            line.endsWith("  ") -> "$line\n"
            else -> "$line  \n"
        }
    }.joinToString("")

    // 6. Inline Math
    val inlineMathPattern = Regex("(?<!\\\\)(?<!\\$)\\$([^$\\n]+?)(?<!\\\\)(?<!\\$)\\$")
    if (s.contains("$")) {
        s = s.replace(inlineMathPattern) { matchResult ->
            "$$" + matchResult.groupValues[1] + "$$"
        }
    }

    return s
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
                                
                                    // 1. 查找该行内的所有图片 Span (AsyncDrawableSpan)
                                    val imageSpans = text.getSpans(lineStart, lineEnd, AsyncDrawableSpan::class.java)
                                
                                    for (imageSpan in imageSpans) {
                                        val spanStart = text.getSpanStart(imageSpan)
                                        val xStart = layout.getPrimaryHorizontal(spanStart)
                                        val drawable = imageSpan.drawable
                                        val bounds = drawable.bounds
                                        val width = bounds.width()
                                
                                        val touchSlop = 20
                                        if (x >= (xStart - touchSlop) && x <= (xStart + width + touchSlop)) {
                                            val source = drawable.destination
                                            if (!source.isNullOrEmpty()) {
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
            val signature = MarkdownRenderSignature(
                markdown = markdown,
                isStreaming = isStreaming,
                isDark = isDark,
                textSizeSp = textSizeSp
            )

            if (tv.tag == signature) {
                return@AndroidView
            }
            tv.tag = signature

            // 缓存优化：尝试从缓存获取 Spanned 对象
            val sp = if (style.fontSize.value > 0f) style.fontSize.value else 16f
            val cacheKey = if (contentKey.isNotBlank() && !isStreaming) {
                // Append version suffix to invalidate old cache entries after heading size fixes
                // v27: Disabled heading preprocessing that caused top spacing issues
                MarkdownSpansCache.generateKey(contentKey + "_v27", isDark, sp)
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

            tv.requestLayout()
            tv.invalidate()

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