package com.android.everytalk.ui.components.markdown

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.TextPaint
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.widget.TextView
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.utils.LeadingMarginUtils
import kotlin.math.max

class CustomOrderedListItemSpan(
    private val theme: MarkwonTheme,
    val number: String,
    private val customBlockMargin: Int,
    val level: Int,
    private val topLevelItemSpacing: Int,
    private val nestedTopSpacing: Int,
    private val listItemLineHeight: Int
) : LeadingMarginSpan, LineHeightSpan {

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
        applyCompactLineHeight(fm)

        val next = findNextItem(spanned, spanStart)
        val lineEndsBeforeNextItem = next != null && start < next.start && end >= next.start - 1
        val extraBottom = when {
            next == null -> 0
            !lineEndsBeforeNextItem -> 0
            next.level > level -> nestedTopSpacing
            else -> topLevelItemSpacing
        }
        if (extraBottom > 0) {
            fm.descent += extraBottom
            fm.bottom += extraBottom
        }
    }

    private fun applyCompactLineHeight(fm: Paint.FontMetricsInt) {
        if (listItemLineHeight <= 0) return
        val currentHeight = fm.descent - fm.ascent
        if (currentHeight <= 0 || currentHeight == listItemLineHeight) return

        val center = fm.ascent + currentHeight / 2
        fm.ascent = center - listItemLineHeight / 2
        fm.descent = fm.ascent + listItemLineHeight
        fm.top = fm.ascent
        fm.bottom = fm.descent
    }

    private data class ListItemPosition(
        val start: Int,
        val level: Int,
    )

    private fun findNextItem(spanned: Spanned, spanStart: Int): ListItemPosition? {
        val bulletSpans = spanned.getSpans(spanStart, spanned.length, CustomBulletListItemSpan::class.java)
        val orderedSpans = spanned.getSpans(spanStart, spanned.length, CustomOrderedListItemSpan::class.java)
        return (bulletSpans.toList() + orderedSpans.toList())
            .filter { it !== this }
            .mapNotNull { span ->
                val start = spanned.getSpanStart(span)
                if (start <= spanStart) return@mapNotNull null
                val level = when (span) {
                    is CustomBulletListItemSpan -> span.level
                    is CustomOrderedListItemSpan -> span.level
                    else -> return@mapNotNull null
                }
                ListItemPosition(start = start, level = level)
            }
            .minByOrNull { it.start }
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
