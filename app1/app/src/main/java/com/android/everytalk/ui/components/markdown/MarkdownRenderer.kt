package com.android.everytalk.ui.components.markdown

import android.util.TypedValue
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.GestureDetector
import android.view.Gravity
import android.widget.TextView
import android.graphics.text.LineBreaker
import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
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
import android.text.SpannableStringBuilder
import android.text.Selection
import android.text.Spanned
import android.text.Layout
import android.text.TextPaint
import android.text.method.ArrowKeyMovementMethod
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import io.noties.markwon.image.AsyncDrawable 
import io.noties.markwon.image.AsyncDrawableSpan
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.ui.components.ChatMarkdownTextStyle
import com.android.everytalk.ui.components.MathStreamingPolicy
import com.android.everytalk.ui.components.markdown.MarkdownSpansCache
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import kotlin.math.max
import kotlin.math.min

private val MULTIPLE_SPACES_REGEX = Regex(" {2,}")
private val ENUM_ITEM_REGEX = Regex("(?<!\\n)\\s+([A-D])[\\.)]\\s")
private val WINDOWS_PATH_REGEX = Regex("^[A-Za-z]:\\\\")
private const val EXTERNAL_LINK_SUFFIX = " ↗"
private const val LINK_DOTTED_UNDERLINE_LIGHT = 0xCC000000.toInt()
private const val LINK_DOTTED_UNDERLINE_DARK = 0xFFFFFFFF.toInt()

// 预编译 preprocessAiMarkdown 中的正则，避免每帧重复编译
private val BASE64_IMAGE_PATTERN = Regex(
    "(\\![\\[^\\]]*\\]\\()\\s*(<?)(:?data:image\\/[^)>]+)(>?)\\s*(\\))",
    setOf(RegexOption.DOT_MATCHES_ALL)
)
private val FULL_WIDTH_PAREN_BOLD_REGEX = Regex("""（\*\*""")
private val QUOTED_BOLD_PATTERN =
    Regex("""\*\*["“”'‘’「」『』](.+?)["“”'‘’「」『』]\*\*""")
private val HEADER_SPACE_REGEX = Regex("(?<=^|\\n)(#{1,6})(?=[^#\\s])")
private val LIST_ITEM_TO_LIST_ITEM_BLANK_LINE_REGEX =
    Regex("""(?m)^([ \t]{0,12}(?:[-*+]|\d+[.)])\s+.*\S)[ \t]*\n(?:[ \t]*\n)+([ \t]{0,12}(?:[-*+]|\d+[.)])\s+)""")
private val MULTILINE_BLOCK_DOLLAR_PATTERN = Regex(
    "\\[double dollar]\\s*\\n([\\s\\S]*?)\\n\\s*\\[double dollar]",
    RegexOption.MULTILINE
)
private val BLOCK_PLACEHOLDER_PATTERN = Regex("(?m)^[ \\t]*\\[double dollar][ \\t]*$")
private val SPORTS_SCORE_PATTERN = Regex("^\\d{1,3}\\s*[:：]\\s*\\d{1,3}$")
private val PURE_BLOCK_DOLLAR_MATH_REGEX = Regex("^\\s*\\$\\$[\\s\\S]*\\$\\$\\s*$")
private val PURE_BLOCK_BRACKET_MATH_REGEX = Regex("^\\s*\\\\\\[[\\s\\S]*\\\\\\]\\s*$")
private val INLINE_DOUBLE_DOLLAR_MATH_REGEX = Regex("\\$\\$(?!\\$)([^\\n]+?)\\$\\$(?!\\$)")
private val FIRST_BLOCK_MATH_TOKEN_REGEX = Regex("\\$\\$(?!\\$)[\\s\\S]+?\\$\\$(?!\\$)")
private val plainLatexReplacements = listOf(
    "\\Longleftrightarrow" to "⇔",
    "\\Leftrightarrow" to "⇔",
    "\\Longrightarrow" to "⟹",
    "\\Rightarrow" to "⇒",
    "\\rightarrow" to "→",
    "\\leftarrow" to "←",
    "\\implies" to "⇒",
    "\\iff" to "⇔",
    "\\geq" to "≥",
    "\\ge" to "≥",
    "\\leq" to "≤",
    "\\le" to "≤",
    "\\neq" to "≠",
    "\\times" to "×",
    "\\cdot" to "·",
)

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
    val textSizeSp: Float,
    val contentKey: String,
    val isStreaming: Boolean,
    val sender: Sender,
    val colorArgb: Int,
    val pureMathBlockMessage: Boolean,
    val allowSystemTextSelection: Boolean,
    val disableVerticalPadding: Boolean,
    val enablePureMathHorizontalScroll: Boolean
)

private data class MarkdownRenderViewState(
    val signature: MarkdownRenderSignature,
    val processed: String,
    val pureMathBlockMessage: Boolean
)

private class ChatLinkUrlSpan(
    url: String,
    private val colorArgb: Int,
) : URLSpan(url) {
    override fun updateDrawState(ds: TextPaint) {
        ds.color = colorArgb
        ds.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        ds.isUnderlineText = false
    }
}

private class DottedLinkUnderlineSpan(
    private val linkStart: Int,
    private val linkEnd: Int,
    private val colorArgb: Int,
) : LineBackgroundSpan {
    override fun drawBackground(
        canvas: Canvas,
        paint: Paint,
        left: Int,
        right: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        lineNumber: Int,
    ) {
        val drawStart = max(linkStart, start)
        val drawEnd = min(linkEnd, end)
        if (drawStart >= drawEnd) return

        val originalColor = paint.color
        val originalStyle = paint.style
        val originalStrokeWidth = paint.strokeWidth
        val originalPathEffect = paint.pathEffect
        val originalStrokeCap = paint.strokeCap

        val strokeWidth = max(1.8f, paint.textSize / 15f)
        val lineLeft = left + continuationLeadingMargin(text, start, end)
        val xStart = lineLeft + paint.measureText(text, start, drawStart)
        val xEnd = xStart + paint.measureText(text, drawStart, drawEnd)
        val y = min(bottom - strokeWidth, baseline + max(5f, paint.textSize / 5f))

        paint.color = colorArgb
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.strokeCap = Paint.Cap.ROUND
        paint.pathEffect = DashPathEffect(floatArrayOf(0.1f, strokeWidth * 2.25f), 0f)
        canvas.drawLine(xStart, y, xEnd, y, paint)

        paint.color = originalColor
        paint.style = originalStyle
        paint.strokeWidth = originalStrokeWidth
        paint.pathEffect = originalPathEffect
        paint.strokeCap = originalStrokeCap
    }

    private fun continuationLeadingMargin(text: CharSequence, start: Int, end: Int): Int {
        if (text !is Spanned) return 0
        val paragraphStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)) + 1
        if (start <= paragraphStart) return 0
        return text.getSpans(start, end, LeadingMarginSpan::class.java)
            .sumOf { it.getLeadingMargin(false) }
    }
}

private fun styleMarkdownLinks(
    source: Spanned,
    linkTextColorArgb: Int,
    dottedUnderlineColorArgb: Int,
): Spanned {
    val builder = SpannableStringBuilder(source)
    val links = builder.getSpans(0, builder.length, URLSpan::class.java)
        .mapNotNull { span ->
            val start = builder.getSpanStart(span)
            val end = builder.getSpanEnd(span)
            if (start < 0 || end <= start) {
                null
            } else {
                LinkRange(
                    span = span,
                    start = start,
                    end = end,
                    url = span.url,
                )
            }
        }
        .sortedByDescending { it.start }

    links.forEach { link ->
        val suffixAlreadyPresent = builder.subSequence(
            link.end,
            min(builder.length, link.end + EXTERNAL_LINK_SUFFIX.length)
        ).toString() == EXTERNAL_LINK_SUFFIX
        val linkEndWithSuffix = if (suffixAlreadyPresent) {
            link.end + EXTERNAL_LINK_SUFFIX.length
        } else {
            builder.insert(link.end, EXTERNAL_LINK_SUFFIX)
            link.end + EXTERNAL_LINK_SUFFIX.length
        }

        builder.removeSpan(link.span)
        builder.removeContainedSpans(link.start, linkEndWithSuffix, ForegroundColorSpan::class.java)
        builder.removeContainedSpans(link.start, linkEndWithSuffix, UnderlineSpan::class.java)
        builder.setSpan(
            ChatLinkUrlSpan(link.url, linkTextColorArgb),
            link.start,
            linkEndWithSuffix,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        builder.setSpan(
            DottedLinkUnderlineSpan(
                linkStart = link.start,
                linkEnd = link.end,
                colorArgb = dottedUnderlineColorArgb,
            ),
            link.start,
            link.end,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    return builder
}

private data class LinkRange(
    val span: URLSpan,
    val start: Int,
    val end: Int,
    val url: String,
)

private fun <T> SpannableStringBuilder.removeContainedSpans(
    start: Int,
    end: Int,
    spanClass: Class<T>,
) {
    getSpans(start, end, spanClass).forEach { span ->
        val spanStart = getSpanStart(span)
        val spanEnd = getSpanEnd(span)
        if (spanStart >= start && spanEnd <= end) {
            removeSpan(span)
        }
    }
}

private fun TextView.findUrlSpanAt(event: MotionEvent): URLSpan? {
    val currentText = text as? Spanned ?: return null
    val currentLayout = layout ?: return null

    var x = event.x.toInt()
    var y = event.y.toInt()
    x -= totalPaddingLeft
    y -= totalPaddingTop
    x += scrollX
    y += scrollY

    if (y < 0 || y > currentLayout.height) return null

    val line = currentLayout.getLineForVertical(y)
    val lineLeft = currentLayout.getLineLeft(line)
    val lineRight = currentLayout.getLineRight(line)
    if (x < lineLeft || x > lineRight) return null

    val offset = currentLayout.getOffsetForHorizontal(line, x.toFloat())
    return currentText.getSpans(offset, offset, URLSpan::class.java).firstOrNull()
}

private fun openUrlFromTextView(textView: TextView, url: String) {
    try {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        textView.context.startActivity(intent)
    } catch (_: Exception) {
    }
}

private fun TextView.applyMarkdownTextSelectionState(allowSystemTextSelection: Boolean) {
    setTextIsSelectable(allowSystemTextSelection)
    if (allowSystemTextSelection) {
        movementMethod = ArrowKeyMovementMethod.getInstance()
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isLongClickable = true
    } else {
        movementMethod = null
        highlightColor = android.graphics.Color.TRANSPARENT
        isFocusable = false
        isFocusableInTouchMode = false
    }
}

private fun TextView.applyMarkdownLayoutMode(pureMathBlockMessage: Boolean) {
    setHorizontallyScrolling(pureMathBlockMessage)
    isHorizontalScrollBarEnabled = false
    overScrollMode = View.OVER_SCROLL_NEVER
    if (!pureMathBlockMessage) {
        isSingleLine = false
        maxLines = Int.MAX_VALUE
        ellipsize = null
        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            justificationMode = LineBreaker.JUSTIFICATION_MODE_INTER_WORD
        }
    }
}

private fun TextView.applyMarkdownTextMetrics(sender: Sender, textSizeSp: Float) {
    if (sender == Sender.User) {
        setLineSpacing(
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                2f,
                resources.displayMetrics
            ),
            1.0f
        )
    } else {
        val targetLineHeightSp = max(
            textSizeSp + 2f,
            ChatMarkdownTextStyle.BODY_LINE_HEIGHT_SP
        )
        val targetLineHeightPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            targetLineHeightSp,
            resources.displayMetrics
        )
        val naturalLineHeightPx = paint.fontMetricsInt.descent - paint.fontMetricsInt.ascent
        setLineSpacing(max(0f, targetLineHeightPx - naturalLineHeightPx), 1.0f)
    }
    letterSpacing = 0f
}

private fun TextView.setMeasuredMarkdownText(markdown: CharSequence) {
    CustomOrderedListItemSpan.measure(this, markdown)
    text = markdown
}

private fun Markwon.setMeasuredParsedMarkdown(textView: TextView, markdown: Spanned) {
    CustomOrderedListItemSpan.measure(textView, markdown)
    setParsedMarkdown(textView, markdown)
}

private fun TextView.configureMarkdownTouchHandling(
    allowSystemTextSelection: Boolean,
    onLongPress: ((androidx.compose.ui.geometry.Offset) -> Unit)?,
    onImageClick: ((String) -> Unit)?,
) {
    linksClickable = false

    if (onImageClick == null && onLongPress == null) {
        if (!allowSystemTextSelection) {
            movementMethod = null
        }
        setOnTouchListener(null)
        isClickable = allowSystemTextSelection
        isLongClickable = allowSystemTextSelection
        setOnLongClickListener(null)
        return
    }

    if (!allowSystemTextSelection) {
        movementMethod = null
    }
    isClickable = true
    isLongClickable = true

    var lastTouchRawX = 0f
    var lastTouchRawY = 0f
    var pressedUrl: String? = null
    var linkTouchActive = false

    setOnTouchListener { v, event ->
        val tvLocal = v as TextView
        val isPureMathBlock = (tvLocal.tag as? MarkdownRenderViewState)?.pureMathBlockMessage == true

        if (isPureMathBlock) {
            if (event.action == MotionEvent.ACTION_DOWN) {
                lastTouchRawX = event.rawX
                lastTouchRawY = event.rawY
            }
            return@setOnTouchListener false
        }

        if (event.action == MotionEvent.ACTION_DOWN) {
            lastTouchRawX = event.rawX
            lastTouchRawY = event.rawY
            pressedUrl = tvLocal.findUrlSpanAt(event)?.url
            if (pressedUrl != null) {
                linkTouchActive = true
                if (!allowSystemTextSelection) {
                    tvLocal.cancelLongPress()
                    return@setOnTouchListener true
                }
                return@setOnTouchListener false
            }
        }

        if (linkTouchActive) {
            val isLongPressGesture =
                event.eventTime - event.downTime >= android.view.ViewConfiguration.getLongPressTimeout()
            when (event.action) {
                MotionEvent.ACTION_UP -> {
                    val url = pressedUrl
                    linkTouchActive = false
                    pressedUrl = null
                    if (isLongPressGesture) {
                        return@setOnTouchListener true
                    }
                    if (url != null && tvLocal.findUrlSpanAt(event)?.url == url) {
                        (tvLocal.text as? Spannable)?.let { Selection.removeSelection(it) }
                        openUrlFromTextView(tvLocal, url)
                        return@setOnTouchListener true
                    }
                    return@setOnTouchListener !allowSystemTextSelection
                }
                MotionEvent.ACTION_CANCEL -> {
                    linkTouchActive = false
                    pressedUrl = null
                    return@setOnTouchListener !allowSystemTextSelection
                }
                MotionEvent.ACTION_MOVE -> {
                    if (tvLocal.findUrlSpanAt(event)?.url != pressedUrl) {
                        pressedUrl = null
                    }
                    return@setOnTouchListener !allowSystemTextSelection
                }
            }
        }

        if (event.action == MotionEvent.ACTION_UP) {
            val spannable = tvLocal.text as? Spannable
            val layout = tvLocal.layout
            if (spannable != null && layout != null) {
                var x = event.x.toInt()
                var y = event.y.toInt()
                x -= tvLocal.totalPaddingLeft
                y -= tvLocal.totalPaddingTop
                x += tvLocal.scrollX
                y += tvLocal.scrollY

                val line = layout.getLineForVertical(y)
                val lineStart = layout.getLineStart(line)
                val lineEnd = layout.getLineEnd(line)

                if (onImageClick != null) {
                    val imageSpans = spannable.getSpans(lineStart, lineEnd, AsyncDrawableSpan::class.java)
                    for (imageSpan in imageSpans) {
                        val spanStart = spannable.getSpanStart(imageSpan)
                        val xStart = layout.getPrimaryHorizontal(spanStart)
                        val drawable = imageSpan.drawable
                        val bounds = drawable.bounds
                        val width = bounds.width()

                        val touchSlop = 20
                        if (x >= (xStart - touchSlop) && x <= (xStart + width + touchSlop)) {
                            val source = drawable.destination?.trim().orEmpty()
                            if (isSupportedImageSource(source)) {
                                onImageClick(source)
                                return@setOnTouchListener true
                            }
                        }
                    }

                    val standardImageSpans =
                        spannable.getSpans(lineStart, lineEnd, android.text.style.ImageSpan::class.java)
                    for (imageSpan in standardImageSpans) {
                        val spanStart = spannable.getSpanStart(imageSpan)
                        val xStart = layout.getPrimaryHorizontal(spanStart)
                        val drawable = imageSpan.drawable
                        val width = drawable.bounds.width()

                        if (x >= xStart && x <= (xStart + width)) {
                            val source = imageSpan.source?.trim().orEmpty()
                            if (isSupportedImageSource(source)) {
                                onImageClick(source)
                                return@setOnTouchListener true
                            }
                        }
                    }
                }

                val offset = layout.getOffsetForHorizontal(
                    line,
                    event.x - tvLocal.totalPaddingLeft + tvLocal.scrollX.toFloat()
                )
                val urlSpans = spannable.getSpans(offset, offset, URLSpan::class.java)
                if (urlSpans.isNotEmpty()) {
                    openUrlFromTextView(tvLocal, urlSpans[0].url)
                    return@setOnTouchListener true
                }
            }
        }

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
}

private data class MarkdownRenderSession(
    val contentKey: String,
    val isStreaming: Boolean,
    val hasMath: Boolean,
    val isDark: Boolean,
    val textSizeSp: Float,
    val preprocessMs: Long,
    val parseMs: Long,
    val renderMs: Long,
    val totalMs: Long,
    val cacheHit: Boolean,
    val errorCode: String?
)

private fun classifyMarkdownRenderError(error: Throwable): String = when (error) {
    is OutOfMemoryError -> "oom"
    is IllegalArgumentException -> "illegal_argument"
    is IllegalStateException -> "illegal_state"
    is IndexOutOfBoundsException -> "index_out_of_bounds"
    else -> "unknown"
}

private fun logMarkdownRenderSession(session: MarkdownRenderSession) {
    if (!com.android.everytalk.config.PerformanceConfig.ENABLE_PERFORMANCE_LOGGING) return
    android.util.Log.d(
        "MarkdownRenderer",
        "session key=${session.contentKey.ifBlank { "<none>" }} stream=${session.isStreaming} " +
            "math=${session.hasMath} dark=${session.isDark} size=${session.textSizeSp} " +
            "pre=${session.preprocessMs}ms parse=${session.parseMs}ms render=${session.renderMs}ms " +
            "total=${session.totalMs}ms cacheHit=${session.cacheHit} err=${session.errorCode ?: "none"}"
    )
}

private fun hasUnclosedFence(text: String): Boolean {
    return countFenceMarkers(text, "```") % 2 == 1 || countFenceMarkers(text, "~~~") % 2 == 1
}

private fun countFenceMarkers(text: String, marker: String): Int {
    var count = 0
    var index = 0
    while (true) {
        val pos = text.indexOf(marker, index)
        if (pos < 0) break
        count++
        index = pos + marker.length
    }
    return count
}

private fun findSafeStreamingSplitIndex(previousProcessed: String, currentProcessed: String): Int {
    if (previousProcessed.isEmpty()) return -1
    if (!currentProcessed.startsWith(previousProcessed)) return -1
    if (currentProcessed.length <= previousProcessed.length) return -1

    val nearestParagraphBoundary = currentProcessed.lastIndexOf("\n\n", previousProcessed.length)
    if (nearestParagraphBoundary <= 0) return -1

    val splitIndex = nearestParagraphBoundary + 2
    val head = currentProcessed.substring(0, splitIndex)
    if (hasUnclosedFence(head)) return -1
    if (MathStreamingPolicy.hasUnclosedMathDelimiter(head)) return -1
    return splitIndex
}

/**
 * 流式输出时：仅转义未闭合的数学公式标记。
 * 已闭合的公式（如 $$E=mc^2$$）保留原样，允许 JLatexMath 立即渲染。
 * 未闭合的公式（如 $$\\int_0^1 f(x)）的起始标记会被转义，
 * 防止 JLatexMath 尝试解析残缺公式导致布局跳变。
 */
private fun escapeUnclosedMathForStreaming(input: String): String {
    return MathStreamingPolicy.escapeUnclosedMathDelimiters(input)
}

private fun buildMathSafeFallbackMarkdown(input: String): String {
    return MathStreamingPolicy.escapeAllMathDelimiters(input)
}

private fun decodeCommonHtmlEntities(input: String): String {
    if (!input.contains('&')) return input
    var s = input
    repeat(3) {
        val decoded = s
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&#160;", " ", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
            .replace("&apos;", "'", ignoreCase = true)
            .replace("&le;", "≤", ignoreCase = true)
            .replace("&ge;", "≥", ignoreCase = true)
        if (decoded == s) return s
        s = decoded
    }
    return s
}

private val SIMPLE_INLINE_MATH_SYMBOL_REPLACEMENTS = mapOf(
    "\\hbar" to "ℏ",
    "\\nabla" to "∇",
    "\\nabla^2" to "∇²",
    "\\partial" to "∂"
)

private fun normalizeSimpleInlineMathToUnicode(input: String): String {
    if (!input.contains('$')) return input

    val out = StringBuilder(input.length)
    var inInlineCode = false
    var i = 0

    fun findClosingSingleDollar(start: Int): Int {
        var j = start
        while (j < input.length) {
            val ch = input[j]
            val escaped = j > 0 && input[j - 1] == '\\'
            if (ch == '`' && !escaped) return -1
            if (ch == '$' && !escaped) return j
            if (ch == '\n') return -1
            j++
        }
        return -1
    }

    while (i < input.length) {
        val ch = input[i]
        val escaped = i > 0 && input[i - 1] == '\\'

        if (ch == '`' && !escaped) {
            inInlineCode = !inInlineCode
            out.append(ch)
            i++
            continue
        }

        if (!inInlineCode && ch == '$' && !escaped) {
            val isDouble = i + 1 < input.length && input[i + 1] == '$'
            if (!isDouble) {
                val close = findClosingSingleDollar(i + 1)
                if (close > i) {
                    val rawContent = input.substring(i + 1, close)
                    val normalized = rawContent.trim()
                    val replacement = SIMPLE_INLINE_MATH_SYMBOL_REPLACEMENTS[normalized]
                    if (replacement != null) {
                        out.append(replacement)
                        i = close + 1
                        continue
                    }
                }
            }
        }

        out.append(ch)
        i++
    }

    return out.toString()
}

private fun normalizePlainLatexCommandsOutsideMath(input: String): String {
    if (!input.contains('\\')) return input

    val out = StringBuilder(input.length + 8)
    var inInlineCode = false
    var inInlineMath = false
    var inBlockMath = false
    var i = 0

    while (i < input.length) {
        val ch = input[i]
        val escaped = i > 0 && input[i - 1] == '\\'

        if (ch == '`' && !escaped) {
            inInlineCode = !inInlineCode
            out.append(ch)
            i++
            continue
        }

        if (!inInlineCode) {
            if (!inInlineMath && i + 1 < input.length && input[i] == '$' && input[i + 1] == '$') {
                inBlockMath = !inBlockMath
                out.append("$$")
                i += 2
                continue
            }

            if (!inBlockMath && ch == '$' && !escaped) {
                inInlineMath = !inInlineMath
                out.append(ch)
                i++
                continue
            }
        }

        if (!inInlineCode && !inInlineMath && !inBlockMath && ch == '\\') {
            val match = plainLatexReplacements.firstOrNull { (key, _) ->
                input.regionMatches(i, key, 0, key.length)
            }
            if (match != null) {
                out.append(match.second)
                i += match.first.length
                continue
            }
        }

        out.append(ch)
        i++
    }

    return out.toString()
}

private fun normalizeComparisonEntities(input: String): String {
    if (!input.contains('&')) return input
    return input
        .replace("&amp;gt;", ">", ignoreCase = true)
        .replace("&amp;lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
}

private fun normalizeAccidentalIndentedNonCode(input: String): String {
    if (!input.contains("    ") && !input.contains('\t')) return input

    val codeLineRegex = Regex(
        "^\\s*(fun\\b|class\\b|val\\b|var\\b|import\\b|public\\b|private\\b|return\\b|if\\b|else\\b|for\\b|while\\b|switch\\b|case\\b|try\\b|catch\\b|def\\b|from\\b|const\\b|let\\b|function\\b|#include\\b|SELECT\\b|INSERT\\b|UPDATE\\b|DELETE\\b|CREATE\\b|ALTER\\b|DROP\\b)"
    )

    return input.lines().joinToString("\n") { line ->
        val hasIndent = line.startsWith("    ") || line.startsWith("\t")
        if (!hasIndent) return@joinToString line

        val body = line.removePrefix("    ").removePrefix("\t")
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return@joinToString body

        val hasMathSignals = trimmed.contains('$') ||
            trimmed.contains("\\frac") ||
            trimmed.contains("\\sum") ||
            trimmed.contains("\\int") ||
            trimmed.contains("\\sqrt") ||
            trimmed.contains("\\begin") ||
            trimmed.contains("\\end")
        val hasCjk = trimmed.any { it.code in 0x4E00..0x9FFF }
        val strongCodeTokens = listOf("{", "}", ";", "=>", "->", "::")
            .sumOf { token -> Regex(Regex.escape(token)).findAll(trimmed).count() }
        val isCodeLike = codeLineRegex.containsMatchIn(trimmed) || strongCodeTokens >= 3

        if ((hasMathSignals || hasCjk) && !isCodeLike) body else line
    }
}

private fun convertSingleDollarMathToDouble(input: String): String {
    if (!input.contains('$')) return input

    fun findClosingSingleDollar(fromIndex: Int): Int {
        var i = fromIndex
        while (i < input.length) {
            val ch = input[i]
            if (ch == '\n') return -1
            val escaped = i > 0 && input[i - 1] == '\\'
            if (ch == '$' && !escaped) {
                val isDouble = i + 1 < input.length && input[i + 1] == '$'
                if (!isDouble) return i
                i += 2
                continue
            }
            i++
        }
        return -1
    }

    val out = StringBuilder(input.length + 16)
    var inInlineCode = false
    var i = 0

    while (i < input.length) {
        val ch = input[i]
        val escaped = i > 0 && input[i - 1] == '\\'
        if (ch == '`' && !escaped) {
            inInlineCode = !inInlineCode
            out.append(ch)
            i++
            continue
        }
        if (inInlineCode) {
            out.append(ch)
            i++
            continue
        }

        if (i + 1 < input.length && input[i] == '$' && input[i + 1] == '$') {
            out.append("$$")
            i += 2
            continue
        }

        if (ch == '$' && !escaped) {
            val close = findClosingSingleDollar(i + 1)
            if (close > i) {
                val rawContent = input.substring(i + 1, close)
                val normalizedContent = rawContent.trim()
                if (SPORTS_SCORE_PATTERN.matches(normalizedContent)) {
                    out.append(rawContent)
                } else {
                    out.append("$$").append(rawContent).append("$$")
                }
                i = close + 1
                continue
            }
        }

        out.append(ch)
        i++
    }

    return out.toString()
}

private fun shouldPromoteInlineMathToBlock(mathBody: String): Boolean {
    val body = mathBody.trim()
    if (body.isEmpty()) return false
    if (SPORTS_SCORE_PATTERN.matches(body)) return false

    val hasComplexToken = body.contains("\\frac") ||
        body.contains("\\sum") ||
        body.contains("\\int") ||
        body.contains("\\prod") ||
        body.contains("\\lim") ||
        body.contains("\\begin") ||
        body.contains("\\left") ||
        body.contains("\\right") ||
        body.contains("\\matrix") ||
        body.contains("\\cases")

    return body.length >= 36 || hasComplexToken
}

private fun promoteLongInlineMathToBlock(input: String): String {
    if (!input.contains("$$")) return input

    val lines = input.lines()
    val rebuilt = mutableListOf<String>()

    lines.forEach { originalLine ->
        var line = originalLine
        val matches = INLINE_DOUBLE_DOLLAR_MATH_REGEX.findAll(line).toList()
        if (matches.isEmpty()) {
            rebuilt.add(line)
            return@forEach
        }

        var promoted = false
        matches.asReversed().forEach { match ->
            val mathToken = match.value
            val mathBody = match.groupValues.getOrElse(1) { "" }
            if (!shouldPromoteInlineMathToBlock(mathBody)) return@forEach

            val prefix = line.substring(0, match.range.first)
            val suffix = line.substring(match.range.last + 1)
            val hasTextAround = prefix.trim().isNotEmpty() || suffix.trim().isNotEmpty()
            if (!hasTextAround) return@forEach

            val replacement = "\n${mathToken}\n"
            line = line.replaceRange(match.range, replacement)
            promoted = true
        }

        if (promoted) {
            rebuilt.addAll(line.split('\n').map { it.trimEnd() })
        } else {
            rebuilt.add(line)
        }
    }

    return rebuilt.joinToString("\n")
}

private fun separateTextAndLongBlockMathLines(input: String): String {
    if (!input.contains("$$")) return input

    val rebuilt = mutableListOf<String>()
    input.lines().forEach { rawLine ->
        val line = rawLine.trimEnd()
        val match = FIRST_BLOCK_MATH_TOKEN_REGEX.find(line)
        if (match == null) {
            rebuilt.add(line)
            return@forEach
        }

        val prefix = line.substring(0, match.range.first).trim()
        val mathToken = match.value.trim()
        val suffix = line.substring(match.range.last + 1).trim()

        val mathBody = mathToken.removePrefix("$$").removeSuffix("$$").trim()
        val hasTextAround = prefix.isNotEmpty() || suffix.isNotEmpty()
        val hasCjkAround = (prefix + suffix).any { it.code in 0x4E00..0x9FFF }
        val shouldSplit = hasTextAround &&
            hasCjkAround &&
            shouldPromoteInlineMathToBlock(mathBody)

        if (!shouldSplit) {
            rebuilt.add(line)
            return@forEach
        }

        if (prefix.isNotEmpty()) rebuilt.add(prefix)
        rebuilt.add(mathToken)
        if (suffix.isNotEmpty()) rebuilt.add(suffix)
    }

    return rebuilt.joinToString("\n")
}

/**
 * 在数学转换前保护货币符号，避免 $5、$10 等金额被误判为内联数学分隔符。
 *
 * 策略：同行内存在两个「$ + 数字」的配对时才转义（金额场景）。
 * 保留 $1+2=3$、$x^2$ 等合法数学公式不动。
 */
private fun escapeCurrencyOutsideMath(input: String): String {
    if (!input.contains('$')) return input

    fun findNextUnescapedDollar(from: Int): Int {
        var j = from
        while (j < input.length) {
            val c = input[j]
            if (c == '\n') return -1
            val escaped = j > 0 && input[j - 1] == '\\'
            if (c == '`') {
                // 跳过行内代码段
                j++
                while (j < input.length && input[j] != '`') {
                    if (input[j] == '\n' || (j > 0 && input[j - 1] == '\\' && input[j] == '`')) break
                    j++
                }
                if (j < input.length) j++
                continue
            }
            if (c == '$' && !escaped) {
                val isDouble = j + 1 < input.length && input[j + 1] == '$'
                if (isDouble) {
                    j += 2
                    continue
                }
                return j
            }
            j++
        }
        return -1
    }

    val out = StringBuilder(input.length + 16)
    var i = 0
    while (i < input.length) {
        val ch = input[i]
        val escaped = i > 0 && input[i - 1] == '\\'

        // $$$ 后跟数字 → 货币，转义第一个 $
        if (i + 2 < input.length && input.startsWith("$$$", i) && input[i + 2].isDigit()) {
            out.append("\\$")
            i += 2
            continue
        }

        // 未闭合的 $$数字 → 货币，折叠为转义后的单个金额符号
        if (!escaped && i + 2 < input.length && input.startsWith("$$", i) && input[i + 2].isDigit()) {
            val closingDoubleIdx = input.indexOf("$$", startIndex = i + 2)
            if (closingDoubleIdx == -1 || input.substring(i + 2, closingDoubleIdx).contains('\n')) {
                out.append("\\$")
                i += 2
                continue
            }
        }

        // $ 后跟数字 → 可能是货币，检查同行是否有配对的另一个 $+数字
        if (!escaped && ch == '$' && i + 1 < input.length && input[i + 1].isDigit()) {
            val closingIdx = findNextUnescapedDollar(i + 1)
            if (closingIdx == -1 ||
                (closingIdx > i && closingIdx + 1 < input.length && input[closingIdx + 1].isDigit())
            ) {
                // 无数学闭合，或同行找到了另一个 $+数字 → 确认是金额，转义
                out.append("\\$")
                i++
                continue
            }
        }

        out.append(ch)
        i++
    }

    return out.toString()
}

/**
 * 预处理 Markdown 文本（简化版）
 *
 * 由于代码块和表格已由 ContentParser 提取，此函数只需处理纯文本内容。
 */
internal fun preprocessAiMarkdown(input: String, isStreaming: Boolean = false): String {
    if (input.isBlank()) return input
    if (PURE_BLOCK_DOLLAR_MATH_REGEX.matches(input) || PURE_BLOCK_BRACKET_MATH_REGEX.matches(input)) {
        return normalizePureMathBlock(input)
    }

    var s = decodeCommonHtmlEntities(input)

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
    s = s.replace(FULL_WIDTH_PAREN_BOLD_REGEX, "**(")

    // 5.1 Fix: 修复 **"xxx"** 这类带引号的加粗无法正确渲染的问题
    s = s.replace(QUOTED_BOLD_PATTERN) { mr ->
        val inner = mr.groupValues[1]
        "\"**${inner}**\""
    }

    // 6. Headers: 确保 # 后有空格
    s = s.replace(HEADER_SPACE_REGEX, "$1 ")

    // 6.5 防误判缩进代码块：把数学/中文说明的 4 空格缩进恢复为普通文本
    s = normalizeAccidentalIndentedNonCode(s)

    // 6.6 收紧列表项之间的原始空行，避免 Markwon 把整段列表提升为 loose list 后产生段落级大留白。
    // 顶级列表项之间的呼吸感由自定义 ListItemSpan 统一负责。
    s = s.replace(LIST_ITEM_TO_LIST_ITEM_BLANK_LINE_REGEX, "$1\n$2")

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

        val isListLine = trimmedLine.startsWith("- ") ||
                trimmedLine.startsWith("* ") ||
                trimmedLine.startsWith("+ ") ||
                trimmedLine.matches(Regex("""^\d+\.\s.*"""))

        val isEmptyLine = line.isBlank()
        val isTableLine = line.contains("|")
        val hasUnbalancedBold = line.split("**").size % 2 == 0
        val trimmedEnd = line.trimEnd()
        val endsWithMathDelimiter = trimmedEnd.endsWith("$$") || trimmedEnd.endsWith("$")
        // 无论是否流式渲染，有未闭合的 ** 都跳过硬换行，避免破坏加粗语法
        val shouldSkipHardBreak = isTableLine || hasUnbalancedBold || endsWithMathDelimiter || isListLine

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

    // 8.6 对极简单的行内数学符号（如 $\\hbar$、$\\partial$、$\\nabla$）做 Unicode 降级，
    // 避免插件链在个别机型/组合场景下将这类极短公式整段吞掉，表现为“公式不显示”。
    s = normalizeSimpleInlineMathToUnicode(s)

    // 非数学上下文中的裸 LaTeX 指令降级为可读符号，避免显示 \implies 这类原始命令
    s = normalizePlainLatexCommandsOutsideMath(s)

    // 8.8 Currency: 先在数学转换前保护货币符号，避免被后续 `$...$` 识别误吞
    s = escapeCurrencyOutsideMath(s)

    // 9. Inline Math:
    // - JLatexMathPlugin(4.6.2) 行内仅识别 `$$...$$`，因此将 `$...$` 统一提升为 `$$...$$`
    // - 使用状态机避免误匹配 `$$...$$`、货币、以及代码片段中的 `$`
    s = convertSingleDollarMathToDouble(s)

    // 9.5/9.6 关闭“行内公式自动升级块级”策略，避免渲染尺寸突变。
    // 按 Gemini 风格保持分隔符语义稳定：行内留在行内，块级仅在模型明确输出块级时渲染为块级。

    // 最后一步仅在流式阶段执行：
    // 1) safe-points 开启时，统一转义全部数学分隔符，避免流式阶段触发数学引擎重排/报错导致闪烁。
    // 2) 否则保留原策略，只转义未闭合数学标记。
    if (isStreaming) {
        if (com.android.everytalk.config.PerformanceConfig.MATH_STREAMING_RENDER_SAFEPOINTS) {
            s = MathStreamingPolicy.escapeAllMathDelimiters(s)
            android.util.Log.d(
                "MathStreamThrottle",
                "strategy=streaming_math_plaintext action=escape-all len=${s.length}"
            )
        } else {
            s = escapeUnclosedMathForStreaming(s)
        }
    }

    // 收口：确保比较符实体不会作为字面量显示
    s = normalizeComparisonEntities(s)

    return s.trimStart('\n')
}

private fun normalizePureMathBlock(input: String): String {
    val trimmed = input.trim()
    return when {
        PURE_BLOCK_DOLLAR_MATH_REGEX.matches(trimmed) -> {
            val inner = trimmed.removePrefix("$$").removeSuffix("$$").trim()
            "\\[$inner\\]"
        }
        PURE_BLOCK_BRACKET_MATH_REGEX.matches(trimmed) -> trimmed
        else -> trimmed
    }
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
    disableVerticalPadding: Boolean = false, // 新增参数：允许禁用垂直 padding
    enablePureMathHorizontalScroll: Boolean = true
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    
    val baseTextSizeSp = if (style.fontSize.value > 0f) style.fontSize.value else 16f
    // 统一气泡内文本字号：用户与 AI 一样，整体略小于之前的 AI 放大效果
    val textSizeSp = baseTextSizeSp
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

    val processedMarkdown = remember(markdown, isStreaming) {
        // 流式结束时不立即切换预处理模式：如果内容未变，保持上次的预处理结果
        // 避免仅因 isStreaming 切换而触发数学公式转义策略变化导致高度突变
        preprocessAiMarkdown(markdown, isStreaming)
    }
    val pureMathBlockLayout = remember(processedMarkdown) {
        val normalized = processedMarkdown.trim()
        PURE_BLOCK_DOLLAR_MATH_REGEX.matches(normalized) ||
            PURE_BLOCK_BRACKET_MATH_REGEX.matches(normalized)
    }

    // 
    val baseViewModifier = if (sender == Sender.User) {
        modifier.wrapContentWidth()
    } else {
        modifier
    }
    val viewModifier = if (pureMathBlockLayout && enablePureMathHorizontalScroll) {
        baseViewModifier
            .horizontalScroll(rememberScrollState())
    } else {
        baseViewModifier
    }
    
    AndroidView(
        modifier = viewModifier,
        factory = {
            TextView(it).apply {
                // 统一文本样式（字号）
                val baseSp = if (style.fontSize.value > 0f) style.fontSize.value else 16f
                // 用户与 AI 使用相同字号，整体略小于之前的 AI 放大效果
                val sp = baseSp
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                setTextColor(finalColor.toArgb())
                // 稳定基线，减少跳动
                // setIncludeFontPadding(false) // 会导致数学公式垂直被裁切，必须开启
                setIncludeFontPadding(true)
                
                // TextView 内部 padding：用户气泡使用相等的上下 padding 实现垂直居中
                if (sender == Sender.User) {
                    // 用户气泡：使用相等的上下 padding，减少水平 padding
                    val horizontalPaddingPx = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        1f,  // 减小水平 padding
                        resources.displayMetrics
                    ).toInt()
                    val verticalPaddingPx = if (disableVerticalPadding) 0 else TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        4f,  // 增加垂直 padding 以实现视觉居中
                        resources.displayMetrics
                    ).toInt()
                    setPadding(horizontalPaddingPx, verticalPaddingPx, horizontalPaddingPx, verticalPaddingPx)
                } else {
                    // AI 气泡
                    // 增加 padding 以防止数学公式（特别是斜体、积分符号）在边缘被裁切
                    // 16dp 应该足够容纳大部分溢出的字形
                    val paddingPx = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        1f,
                        resources.displayMetrics
                    ).toInt()
                    
                    val verticalPaddingPx = if (disableVerticalPadding) 0 else paddingPx
                    
                    setPadding(paddingPx, verticalPaddingPx, paddingPx, verticalPaddingPx)
                }
                
                applyMarkdownTextMetrics(sender, sp)
                
                // 设置居中对齐 - 对多行文本有效
                // gravity = Gravity.CENTER_VERTICAL // 移除垂直居中，避免长文本/图片显示异常
                
                // AI 正文无自定义长按菜单时，交给系统 TextView 处理长按选中文本。
                val allowSystemTextSelection = sender == Sender.AI && onLongPress == null
                applyMarkdownTextSelectionState(allowSystemTextSelection)
                configureMarkdownTouchHandling(
                    allowSystemTextSelection = allowSystemTextSelection,
                    onLongPress = onLongPress,
                    onImageClick = onImageClick,
                )
                applyMarkdownLayoutMode(pureMathBlockLayout)
            }
        },
        update = { tv ->
            val updateStartNs = SystemClock.elapsedRealtimeNanos()
            val preprocessStartNs = updateStartNs
            val processed = processedMarkdown
            val preprocessMs = (SystemClock.elapsedRealtimeNanos() - preprocessStartNs) / 1_000_000L
            val normalizedProcessed = processed.trim()
            val pureMathBlockMessage =
                PURE_BLOCK_DOLLAR_MATH_REGEX.matches(normalizedProcessed) ||
                    PURE_BLOCK_BRACKET_MATH_REGEX.matches(normalizedProcessed)
            val allowSystemTextSelection = sender == Sender.AI && onLongPress == null
            val linkDottedUnderlineColor = if (isDark) {
                LINK_DOTTED_UNDERLINE_DARK
            } else {
                LINK_DOTTED_UNDERLINE_LIGHT
            }
            val signature = MarkdownRenderSignature(
                processed = processed,
                isDark = isDark,
                textSizeSp = textSizeSp,
                contentKey = contentKey,
                isStreaming = isStreaming,
                sender = sender,
                colorArgb = finalColor.toArgb(),
                pureMathBlockMessage = pureMathBlockMessage,
                allowSystemTextSelection = allowSystemTextSelection,
                disableVerticalPadding = disableVerticalPadding,
                enablePureMathHorizontalScroll = enablePureMathHorizontalScroll
            )

            val previousState = tv.tag as? MarkdownRenderViewState
            tv.applyMarkdownTextMetrics(sender, textSizeSp)
            tv.applyMarkdownTextSelectionState(allowSystemTextSelection)
            tv.configureMarkdownTouchHandling(
                allowSystemTextSelection = allowSystemTextSelection,
                onLongPress = onLongPress,
                onImageClick = onImageClick,
            )
            tv.applyMarkdownLayoutMode(pureMathBlockMessage)
            if (previousState?.signature == signature) {
                return@AndroidView
            }
            tv.tag = MarkdownRenderViewState(
                signature = signature,
                processed = processed,
                pureMathBlockMessage = pureMathBlockMessage
            )

            // 纯数学块：内容保持单行（不自动折行），由 Compose 外层承载横向滚动
            tv.applyMarkdownTextSelectionState(allowSystemTextSelection)
            tv.applyMarkdownLayoutMode(pureMathBlockMessage)

            // 缓存优化：尝试从缓存获取 Spanned 对象
            // 流式期间：上游 TableAwareText 只对已稳定的 part 提供 contentKey
            // 最后一个（持续变化的）part 的 contentKey 为空，不缓存
            val sp = if (style.fontSize.value > 0f) style.fontSize.value else 16f
            val cacheKey = if (contentKey.isNotBlank()) {
                // 核心修复：在缓存 Key 中包含处理后文本的哈希值
                // 这样当流式结束（isStreaming=false）导致预处理结果变化时，
                // 或者消息内容被修改时，缓存会自动失效并重新渲染，避免显示旧的转义结果。
                MarkdownSpansCache.generateKey("${contentKey}_${processed.hashCode()}_v53", isDark, sp)
            } else ""

            val cachedSpanned = if (cacheKey.isNotBlank()) MarkdownSpansCache.get(cacheKey) else null
            var parseMs = 0L
            var renderMs = 0L
            var cacheHit = false
            var errorCode: String? = null
            val previousProcessed = previousState?.processed.orEmpty()
            val segmentSplitIndex = if (isStreaming) {
                findSafeStreamingSplitIndex(previousProcessed, processed)
            } else {
                -1
            }

            try {
                if (cachedSpanned != null) {
                    cacheHit = true
                    tv.setMeasuredMarkdownText(cachedSpanned)
                    if (com.android.everytalk.config.PerformanceConfig.ENABLE_PERFORMANCE_LOGGING) {
                        android.util.Log.d("MarkdownRenderer", "Spans Cache HIT: $cacheKey")
                    }
                } else if (segmentSplitIndex > 0) {
                    val headText = processed.substring(0, segmentSplitIndex)
                    val tailText = processed.substring(segmentSplitIndex)

                    val headKey = if (contentKey.isNotBlank()) {
                        MarkdownSpansCache.generateKey(
                            "${contentKey}_seg_head_${headText.hashCode()}_v45",
                            isDark,
                            sp
                        )
                    } else ""

                    val tailKey = if (contentKey.isNotBlank()) {
                        MarkdownSpansCache.generateKey(
                            "${contentKey}_seg_tail_${tailText.hashCode()}_v45",
                            isDark,
                            sp
                        )
                    } else ""

                    val parseStartNs = SystemClock.elapsedRealtimeNanos()
                    val headSpanned = if (headKey.isNotBlank()) {
                        MarkdownSpansCache.get(headKey) ?: run {
                            val node = markwon.parse(headText)
                            val spanned = styleMarkdownLinks(
                                source = markwon.render(node),
                                linkTextColorArgb = finalColor.toArgb(),
                                dottedUnderlineColorArgb = linkDottedUnderlineColor,
                            )
                            MarkdownSpansCache.put(headKey, spanned)
                            spanned
                        }
                    } else {
                        styleMarkdownLinks(
                            source = markwon.render(markwon.parse(headText)),
                            linkTextColorArgb = finalColor.toArgb(),
                            dottedUnderlineColorArgb = linkDottedUnderlineColor,
                        )
                    }

                    val tailSpanned = if (tailKey.isNotBlank()) {
                        MarkdownSpansCache.get(tailKey) ?: run {
                            val node = markwon.parse(tailText)
                            val spanned = styleMarkdownLinks(
                                source = markwon.render(node),
                                linkTextColorArgb = finalColor.toArgb(),
                                dottedUnderlineColorArgb = linkDottedUnderlineColor,
                            )
                            MarkdownSpansCache.put(tailKey, spanned)
                            spanned
                        }
                    } else {
                        styleMarkdownLinks(
                            source = markwon.render(markwon.parse(tailText)),
                            linkTextColorArgb = finalColor.toArgb(),
                            dottedUnderlineColorArgb = linkDottedUnderlineColor,
                        )
                    }

                    parseMs = (SystemClock.elapsedRealtimeNanos() - parseStartNs) / 1_000_000L
                    renderMs = 0L

                    val merged = SpannableStringBuilder().apply {
                        append(headSpanned)
                        append(tailSpanned)
                    }
                    tv.setMeasuredMarkdownText(merged)
                    if (com.android.everytalk.config.PerformanceConfig.ENABLE_PERFORMANCE_LOGGING) {
                        android.util.Log.d(
                            "MarkdownRenderer",
                            "Spans Segment MISS split=$segmentSplitIndex"
                        )
                    }
                } else {
                    if (processed.contains("$")) {
                        android.util.Log.d("MarkdownRenderer", "检测到数学公式标记: chars=${processed.length}")
                    }

                    val parseStartNs = SystemClock.elapsedRealtimeNanos()
                    val node = markwon.parse(processed)
                    parseMs = (SystemClock.elapsedRealtimeNanos() - parseStartNs) / 1_000_000L

                    val renderStartNs = SystemClock.elapsedRealtimeNanos()
                    val spanned = styleMarkdownLinks(
                        source = markwon.render(node),
                        linkTextColorArgb = finalColor.toArgb(),
                        dottedUnderlineColorArgb = linkDottedUnderlineColor,
                    )
                    renderMs = (SystemClock.elapsedRealtimeNanos() - renderStartNs) / 1_000_000L

                    if (cacheKey.isNotBlank()) {
                        MarkdownSpansCache.put(cacheKey, spanned)
                        if (com.android.everytalk.config.PerformanceConfig.ENABLE_PERFORMANCE_LOGGING) {
                            android.util.Log.d("MarkdownRenderer", "Spans Cache MISS, cached: $cacheKey")
                        }
                    }

                    markwon.setMeasuredParsedMarkdown(tv, spanned)
                }
            } catch (error: Throwable) {
                errorCode = classifyMarkdownRenderError(error)
                android.util.Log.e(
                    "MarkdownRenderer",
                    "render failed, try math-safe fallback first, code=$errorCode",
                    error
                )
                val fallbackMarkdown = buildMathSafeFallbackMarkdown(processed)
                val fallbackApplied = fallbackMarkdown != processed
                if (fallbackApplied) {
                    try {
                        val fallbackNode = markwon.parse(fallbackMarkdown)
                        val fallbackSpanned = styleMarkdownLinks(
                            source = markwon.render(fallbackNode),
                            linkTextColorArgb = finalColor.toArgb(),
                            dottedUnderlineColorArgb = linkDottedUnderlineColor,
                        )
                        markwon.setMeasuredParsedMarkdown(tv, fallbackSpanned)
                    } catch (fallbackError: Throwable) {
                        android.util.Log.e(
                            "MarkdownRenderer",
                            "math-safe fallback render failed, fallback to plain text",
                            fallbackError
                        )
                        tv.text = processed
                    }
                } else {
                    tv.text = processed
                }
            }
            tv.applyMarkdownTextSelectionState(allowSystemTextSelection)
            tv.configureMarkdownTouchHandling(
                allowSystemTextSelection = allowSystemTextSelection,
                onLongPress = onLongPress,
                onImageClick = onImageClick,
            )
            tv.applyMarkdownLayoutMode(pureMathBlockMessage)

            val totalMs = (SystemClock.elapsedRealtimeNanos() - updateStartNs) / 1_000_000L
            logMarkdownRenderSession(
                MarkdownRenderSession(
                    contentKey = contentKey,
                    isStreaming = isStreaming,
                    hasMath = processed.contains('$') || processed.contains("\\["),
                    isDark = isDark,
                    textSizeSp = textSizeSp,
                    preprocessMs = preprocessMs,
                    parseMs = parseMs,
                    renderMs = renderMs,
                    totalMs = totalMs,
                    cacheHit = cacheHit,
                    errorCode = errorCode
                )
            )

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
                    // 若 asyncSpans 为空，直接继续回退到 ImageSpan，无需重复打印日志

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
        }
    )
}



