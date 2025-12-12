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
    // 增强正则：支持尖括号包裹的 URL (<data:image/...>) 和无尖括号的 URL
    if (s.contains("data:image/")) {
        // 匹配 ![alt](data:image/...) 或 ![alt](<data:image/...>)
        // group 1: ![alt](
        // group 2: < (可选)
        // group 3: data:image/.... (内容)
        // group 4: > (可选)
        // group 5: )
        val base64ImagePattern = Regex("(\\!\\[[^\\]]*\\]\\()\\s*(<?)(data:image\\/[^)>]+)(>?)\\s*(\\))", setOf(RegexOption.DOT_MATCHES_ALL))
        s = s.replace(base64ImagePattern) { mr ->
            val prefix = mr.groupValues[1]
            val openAngle = mr.groupValues[2]
            // 清理 data 中的空白
            val data = mr.groupValues[3].filter { !it.isWhitespace() }
            val closeAngle = mr.groupValues[4]
            val suffix = mr.groupValues[5]
            
            // 重新组合，保持原有的尖括号结构（如果存在）
            prefix + openAngle + data + closeAngle + suffix
        }
    }

    // 3. 特殊空格归一化
    s = s.replace("&nbsp;", " ")
        .replace("\u00A0", " ")
        .replace("\u3000", " ")

    val fullWidthParenBoldFix = Regex("）\\*\\*")
    s = s.replace(fullWidthParenBoldFix, "**）")

    // ============================================================================================
    // 格式化增强 (Formatting Enhancements)
    // 只保留用户明确需要的功能，移除会导致问题的列表处理逻辑
    // ============================================================================================

    // 4.1 修复紧凑标题 (Compact Headers Fix) - 已禁用
    // 之前的逻辑会在标题前添加 \n\n，导致顶部空间过大
    // 现在只保留补全标题后空格的逻辑
    // s = s.replace(Regex("(?<!^)(?<!\\n)(#{2,6})"), "\n\n$1")  // 已禁用：导致顶部空间过大
    
    // 2. 补全标题后的空格（保留：不会导致额外空间）
    s = s.replace(Regex("(?<=^|\\n)(#{1,6})(?=[^#\\s])"), "$1 ")
    
    // 3. 针对"标题+正文"粘连的智能处理 - 已禁用
    // 这些逻辑会在标题后添加 \n\n，可能导致额外空间
    // s = s.replace(Regex("^(#{1,6}\\s+)([^\\n]{1,20}[：:。？！\\s])([^\\n]+)$", RegexOption.MULTILINE)) { ... }  // 已禁用

    // 策略B：降级兜底 - 保留（不会添加额外空间，只是转义）
    s = s.replace(Regex("^(#{1,6})(?=\\s.{50,})", RegexOption.MULTILINE)) { mr ->
        "\\" + mr.groupValues[1]
    }

    // 4.2 紧凑列表 - 已禁用
    // 这个逻辑会在标题后添加换行，可能导致额外空间
    // s = s.replace(Regex("^(#{1,6}[^\\n]*?)(?:\\s*)-\\s", RegexOption.MULTILINE)) { ... }  // 已禁用

    // 5. 强制换行处理 (Hard Break Enforcement) - 用户明确要求保留
    // Markwon/CommonMark 默认将单个换行符视为空格 (Soft Break)。
    // 将单换行转换为 Markdown 硬换行 (两个空格 + \n)。
    // 注意：不处理标题行末尾，否则会破坏标题解析（标题行末尾不能有空格）
    val isIsolatedCodeBlock = s.trimStart().startsWith("```")
    if (!isIsolatedCodeBlock) {
        // 按行处理，排除标题行（以 # 开头的行）
        val lines = s.lines()
        val lastIndex = lines.size - 1
        s = lines.mapIndexed { index, line ->
            // 标题行检测：以 # 开头（可以有前导空格），后跟空格
            // 使用 startsWith 检测更可靠
            val trimmedLine = line.trimStart()
            val isHeadingLine = trimmedLine.startsWith("# ") ||
                               trimmedLine.startsWith("## ") ||
                               trimmedLine.startsWith("### ") ||
                               trimmedLine.startsWith("#### ") ||
                               trimmedLine.startsWith("##### ") ||
                               trimmedLine.startsWith("###### ")
            // 空行检测
            val isEmptyLine = line.isBlank()
            
            // 表格行与未闭合粗体检测 (Table & Unbalanced Bold Protection)
            // 在流式传输时，表格行可能被分片传输，此时插入硬换行会破坏表格结构（如 | **容器编 \n 排** |）
            // 同样，未闭合的粗体如果在中间被插入换行，也会导致粗体失效。
            val isTableLine = line.contains("|")
            val hasUnbalancedBold = line.split("**").size % 2 == 0
            
            val shouldSkipHardBreak = isTableLine || (isStreaming && hasUnbalancedBold)
            
            // 调试日志
            if (trimmedLine.startsWith("#") || (isStreaming && (isTableLine || hasUnbalancedBold))) {
                android.util.Log.d("MarkdownPreprocess", "Line ${index}: isHeading=$isHeadingLine, isTable=$isTableLine, unbalancedBold=$hasUnbalancedBold, streaming=$isStreaming, content='${line.take(50)}'")
            }
            
            when {
                index == lastIndex -> line
                isEmptyLine -> "$line\n"
                isHeadingLine -> "$line\n"
                shouldSkipHardBreak -> "$line\n"
                line.endsWith("  ") -> "$line\n"
                else -> "$line  \n"
            }
        }.joinToString("")
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

    // 7. 移除文本开头的多余换行符 (Remove leading newlines)
    // 防止因标题预处理或其他原因导致顶部出现不必要的空白
    s = s.trimStart('\n')

    // 调试：打印预处理后的前200个字符，检查标题格式
    android.util.Log.d("MarkdownPreprocess", "Processed markdown (first 500 chars): ${s.take(500).replace("\n", "\\n")}")
    
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
            // ⚡️ 性能优化：如果内容未变更，直接跳过更新，防止流式结束时的闪烁/跳动
            if (tv.tag == markdown) {
                return@AndroidView
            }
            tv.tag = markdown

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