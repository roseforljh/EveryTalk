package com.android.everytalk.ui.components.markdown

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.text.Layout
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import com.android.everytalk.ui.components.ChatMarkdownTextStyle
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.utils.LeadingMarginUtils

/**
 * 自定义的无序列表小圆点 Span，用于替换 Markwon 默认的 BulletListItemSpan。
 * 目的是独立控制列表项的缩进（blockMargin），而不影响其他元素（如 Blockquote、Table 等）。
 */
class CustomBulletListItemSpan(
    private val theme: MarkwonTheme,
    private val level: Int,
    private val customBlockMargin: Int,
    private val bulletWidth: Int,
    private val topLevelItemSpacing: Int,
    private val nestedTopSpacing: Int
) : LeadingMarginSpan, LineHeightSpan {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val circle = RectF()

    override fun getLeadingMargin(first: Boolean): Int {
        // 使用自定义的缩小间距，而不是主题中全局的 blockMargin
        return customBlockMargin
    }

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int,
        top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int,
        first: Boolean, layout: Layout
    ) {
        if (!first || !LeadingMarginUtils.selfStart(start, text, this)) {
            return
        }

        paint.set(p)
        theme.applyListItemStyle(paint)

        val save = c.save()
        try {
            val width = customBlockMargin
            val textLineHeight = (paint.descent() - paint.ascent() + .5f).toInt()
            val side = bulletWidth.takeIf { it > 0 } ?: theme.getBulletWidth(textLineHeight)
            val marginLeft = (width - side) / 2

            val l: Int
            val r: Int

            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.N || Build.VERSION.SDK_INT == Build.VERSION_CODES.N_MR1) {
                val diff: Int = if (dir < 0) {
                    x - (layout.width - (width * level))
                } else {
                    (width * level) - x
                }

                val left = x + (dir * marginLeft)
                val right = left + (dir * side)
                l = Math.min(left, right) + (dir * diff)
                r = Math.max(left, right) + (dir * diff)
            } else {
                if (dir > 0) {
                    l = x + marginLeft
                } else {
                    l = x - width + marginLeft
                }
                r = l + side
            }

            val t = baseline + ((paint.descent() + paint.ascent()) / 2f + .5f).toInt() - (side / 2)
            val b = t + side

            circle.set(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
            val filled = ChatMarkdownTextStyle.listBulletFilled(level)
            paint.style = if (filled) {
                Paint.Style.FILL
            } else {
                Paint.Style.STROKE
            }
            if (!filled) {
                paint.strokeWidth = maxOf(1.5f, side * 0.28f)
            }
            c.drawOval(circle, paint)
        } finally {
            c.restoreToCount(save)
        }
    }

    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        fm: Paint.FontMetricsInt,
    ) {
        val spanned = text as? Spanned ?: return
        val spanStart = spanned.getSpanStart(this)
        if (start > spanStart) return

        val extraTop = when {
            level == 0 && hasPreviousSibling(spanned, spanStart) -> topLevelItemSpacing
            level > 0 -> nestedTopSpacing
            else -> 0
        }
        if (extraTop > 0) {
            fm.ascent -= extraTop
            fm.top -= extraTop
        }
    }

    private fun hasPreviousSibling(spanned: Spanned, spanStart: Int): Boolean {
        val previous = spanned.getSpans(0, spanStart, CustomBulletListItemSpan::class.java)
            .filter { span -> span !== this && spanned.getSpanEnd(span) <= spanStart }
            .maxByOrNull { span -> spanned.getSpanEnd(span) }

        return previous?.level == level
    }
}
