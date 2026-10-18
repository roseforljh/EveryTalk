package com.android.everytalk.ui.components

import android.graphics.Typeface
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import com.android.everytalk.ui.components.syntax.HighlightCache
import com.android.everytalk.ui.components.syntax.SyntaxHighlightTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SelectableCodeTextView(
    code: String,
    language: String,
    isDarkTheme: Boolean,
    headerColor: Color,
    codeTextColor: Color,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier
) {
    val syntaxTheme = if (isDarkTheme) SyntaxHighlightTheme.Dark else SyntaxHighlightTheme.Light
    val highlightedCode by produceState<CharSequence>(
        code,
        code,
        language,
        isDarkTheme,
        codeTextColor,
        isStreaming,
    ) {
        value = code
        // 流式期间只追加原文，结束后再高亮一次，避免每个 token 都重新解析全文。
        if (!isStreaming && HighlightCache.shouldHighlight(code, isStreaming = false)) {
            value = withContext(Dispatchers.Default) {
                HighlightCache.highlight(code, language, isDarkTheme, syntaxTheme)
                    .toSpannableString(codeTextColor)
            }
        }
    }
    // 异步结果可能仍属于上一次输入，不能把新文本短暂替换成旧文本。
    val visibleCode = if (isStreaming || !TextUtils.equals(highlightedCode, code)) {
        code
    } else {
        highlightedCode
    }
    val languageLabel = remember(language) { language.trim().ifBlank { "CODE" }.uppercase() }
    val headerArgb = headerColor.toArgb()
    val codeArgb = codeTextColor.toArgb()

    AndroidView(
        modifier = modifier,
        factory = { context ->
            ScrollView(context).apply {
                isFillViewport = true
                // 代码区保留滚动能力，但不绘制系统滚动条，避免右侧占位影响代码宽度。
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
                setBackgroundColor(android.graphics.Color.TRANSPARENT)

                val density = resources.displayMetrics.density
                val topPadding = (8f * density).toInt()
                val bottomPadding = (64f * density).toInt()
                val content = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, topPadding, 0, bottomPadding)
                }

                val labelView = TextView(context).apply {
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(headerArgb)
                    text = languageLabel
                    includeFontPadding = true
                    setPadding(0, 0, 0, (16f * density).toInt())
                }

                val codeView = TextView(context).apply {
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(codeArgb)
                    setLineSpacing(0f, 1.0f)
                    includeFontPadding = true
                    setTextIsSelectable(true)
                    setHorizontallyScrolling(false)
                    setText(visibleCode, TextView.BufferType.EDITABLE)
                    tag = visibleCode
                }

                content.addView(
                    labelView,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                content.addView(
                    codeView,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                addView(
                    content,
                    android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                tag = Pair(labelView, codeView)
            }
        },
        update = { scrollView ->
            @Suppress("UNCHECKED_CAST")
            val views = scrollView.tag as Pair<TextView, TextView>
            val labelView = views.first
            val codeView = views.second
            if (labelView.text != languageLabel) labelView.text = languageLabel
            if (labelView.currentTextColor != headerArgb) labelView.setTextColor(headerArgb)
            if (codeView.currentTextColor != codeArgb) codeView.setTextColor(codeArgb)

            // 使用更新前的范围判断用户是否仍在底部；上滑阅读或选择文本时保留当前位置。
            val content = scrollView.getChildAt(0)
            val bottomGap = content.height - scrollView.height - scrollView.scrollY
            val followTail = isStreaming &&
                bottomGap <= 24f * scrollView.resources.displayMetrics.density &&
                codeView.selectionStart == codeView.selectionEnd
            updateCodeText(codeView, visibleCode)
            if (followTail) {
                // 等本批追加文本完成布局后再滚动，避免使用上一帧高度漏掉最新一行。
                scrollView.doOnLayout {
                    scrollView.scrollTo(0, content.height - scrollView.height)
                }
            }
        }
    )
}

/**
 * 在原生可编辑缓冲区追加新文本，让 TextView 只重新布局受影响的段落。
 * 服务端改写或缩短内容时替换原文；最终高亮只更新颜色 span，不重设全文和选区。
 * 返回值表示文字是否变化，便于验证流式更新没有退化成整块替换。
 */
internal fun updateCodeText(codeView: TextView, content: CharSequence): Boolean {
    val buffer = codeView.editableText
    val hasPrefix = content.length >= buffer.length &&
        TextUtils.regionMatches(buffer, 0, content, 0, buffer.length)
    val changed = !hasPrefix || content.length != buffer.length
    if (changed) {
        if (hasPrefix) buffer.append(content, buffer.length, content.length)
        else buffer.replace(0, buffer.length, content)
    }

    if (codeView.tag !== content) {
        // 只移除语法颜色，保留 TextView 自己维护的选择标记和 TextWatcher。
        if (content is Spanned || codeView.tag is Spanned) {
            buffer.getSpans(0, buffer.length, ForegroundColorSpan::class.java)
                .forEach(buffer::removeSpan)
            if (content is Spanned) {
                content.getSpans(0, content.length, ForegroundColorSpan::class.java).forEach { span ->
                    buffer.setSpan(
                        ForegroundColorSpan(span.foregroundColor),
                        content.getSpanStart(span),
                        content.getSpanEnd(span),
                        content.getSpanFlags(span),
                    )
                }
            }
        }
        codeView.tag = content
    }
    return changed
}

