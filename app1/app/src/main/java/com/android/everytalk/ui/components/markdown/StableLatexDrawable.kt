package com.android.everytalk.ui.components.markdown

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import org.scilab.forge.jlatexmath.TeXIcon
import ru.noties.jlatexmath.awt.AndroidGraphics2D

/**
 * 不换行的稳定 LaTeX Drawable。
 * 用于矩阵、偏导等需要完整保真显示的块级公式。
 */
class StableLatexDrawable(
    private val icon: TeXIcon
) : Drawable() {

    private val graphics2D = AndroidGraphics2D()
    private val iconWidth = icon.iconWidth
    private val iconHeight = icon.iconHeight

    init {
        setBounds(0, 0, iconWidth, iconHeight)
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val save = canvas.save()
        try {
            if (bounds.left != 0 || bounds.top != 0) {
                canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            }
            graphics2D.setCanvas(canvas)
            icon.paintIcon(null, graphics2D, 0, 0)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = iconWidth
    override fun getIntrinsicHeight(): Int = iconHeight
}
