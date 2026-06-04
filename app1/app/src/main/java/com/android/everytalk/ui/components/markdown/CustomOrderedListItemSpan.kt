package com.android.everytalk.ui.components.markdown

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.widget.TextView
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.utils.LeadingMarginUtils
import kotlin.math.max

class CustomOrderedListItemSpan(
    private val theme: MarkwonTheme,
    private val number: String,
    private val customBlockMargin: Int
) : LeadingMarginSpan {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var measuredMargin: Int = 0

    override fun getLeadingMargin(first: Boolean): Int {
        return max(measuredMargin, customBlockMargin)
    }

    override fun drawLeadingMargin(
        c: Canvas,
        p: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: Layout
    ) {
        if (!first || !LeadingMarginUtils.selfStart(start, text, this)) {
            return
        }

        paint.set(p)
        theme.applyListItemStyle(paint)

        val numberWidth = (paint.measureText(number) + .5f).toInt()
        val width = max(numberWidth, customBlockMargin)
        measuredMargin = numberWidth.takeIf { it > customBlockMargin } ?: 0

        val left = if (dir > 0) {
            x + (width * dir) - numberWidth
        } else {
            x + (width * dir) + (width - numberWidth)
        }
        c.drawText(number, left.toFloat(), baseline.toFloat(), paint)
    }

    companion object {
        fun measure(textView: TextView, text: CharSequence) {
            val spanned = text as? android.text.Spanned ?: return
            val spans = spanned.getSpans(0, text.length, CustomOrderedListItemSpan::class.java)
            val textPaint: TextPaint = textView.paint
            spans.forEach { span ->
                span.measuredMargin = (textPaint.measureText(span.number) + .5f).toInt()
            }
        }
    }
}
