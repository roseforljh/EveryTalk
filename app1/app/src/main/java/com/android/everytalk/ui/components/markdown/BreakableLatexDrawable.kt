package com.android.everytalk.ui.components.markdown

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import org.scilab.forge.jlatexmath.TeXIcon
import ru.noties.jlatexmath.awt.AndroidGraphics2D
import ru.noties.jlatexmath.awt.Color
import ru.noties.jlatexmath.awt.Insets

/**
 * 支持自动换行的 LaTeX 公式 Drawable。
 *
 * 与原生 JLatexMathDrawable 的区别：
 * - 构建 TeXIcon 时传入 maxWidth（像素），调用 setWidth() + setInterLineSpacing()
 * - JLatexMath 内部 BreakFormula.split() 会在 Rel/Bin Atom（=, +, - 等）处断行
 * - 结果是一个多行的 VerticalBox，而非单行 HorizontalBox
 */
class BreakableLatexDrawable private constructor(
    private val icon: TeXIcon,
    private val align: Int
) : Drawable() {

    private val graphics2D = AndroidGraphics2D()
    private val iconWidth = icon.iconWidth
    private val iconHeight = icon.iconHeight

    init {
        setBounds(0, 0, iconWidth, iconHeight)
    }

    override fun draw(canvas: Canvas) {
        val bounds = getBounds()
        val save = canvas.save()
        try {
            val w = bounds.width()
            val h = bounds.height()

            // 不做缩放——换行后的公式已经适配宽度
            // 但保留居中/靠左对齐
            val left = when (align) {
                ALIGN_CENTER -> (w - iconWidth) / 2
                ALIGN_RIGHT -> w - iconWidth
                else -> 0
            }
            val top = (h - iconHeight) / 2

            if (top != 0 || left != 0) {
                canvas.translate(left.toFloat(), top.toFloat())
            }

            graphics2D.setCanvas(canvas)
            icon.paintIcon(null, graphics2D, 0, 0)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity(): Int = PixelFormat.OPAQUE
    override fun getIntrinsicWidth(): Int = iconWidth
    override fun getIntrinsicHeight(): Int = iconHeight

    companion object {
        const val ALIGN_LEFT = 0
        const val ALIGN_CENTER = 1
        const val ALIGN_RIGHT = 2

        /**
         * 创建支持换行的 LaTeX drawable。
         *
         * @param latex LaTeX 公式字符串（不含 $$ 分隔符）
         * @param textSize 字号（与 JLatexMathDrawable 相同的单位）
         * @param color 前景色 ARGB
         * @param maxWidthPx 最大宽度（像素）——公式超过此宽度会自动换行
         * @param align 对齐方式
         * @param interlineSpacing 换行间距（em 单位），默认 0.5
         */
        @JvmStatic
        fun create(
            latex: String,
            textSize: Float,
            color: Int,
            maxWidthPx: Float,
            align: Int = ALIGN_LEFT,
            interlineSpacing: Float = 0.5f,
            padding: Insets? = null
        ): BreakableLatexDrawable {
            val icon = TeXFormula(latex)
                .TeXIconBuilder()
                .setStyle(TeXConstants.STYLE_DISPLAY)
                .setSize(textSize)
                .setFGColor(Color(color))
                .setWidth(TeXConstants.UNIT_PIXEL, maxWidthPx, TeXConstants.ALIGN_LEFT)
                .setIsMaxWidth(true)
                .setInterLineSpacing(TeXConstants.UNIT_EM, interlineSpacing)
                .build()

            if (padding != null) {
                icon.setInsets(padding)
            }

            return BreakableLatexDrawable(icon, align)
        }

        /**
         * 判断公式是否需要换行（宽度是否超过 maxWidth）。
         * 先用无宽度约束渲染测量，再决定是否需要换行版本。
         */
        @JvmStatic
        fun needsLineBreaking(
            latex: String,
            textSize: Float,
            maxWidthPx: Float
        ): Boolean {
            return try {
                val icon = TeXFormula(latex)
                    .TeXIconBuilder()
                    .setStyle(TeXConstants.STYLE_DISPLAY)
                    .setSize(textSize)
                    .build()
                icon.iconWidth > maxWidthPx
            } catch (e: Exception) {
                false
            }
        }
    }
}
