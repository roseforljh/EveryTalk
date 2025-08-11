package com.example.everytalk.ui.components.math

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.*

/**
 * 高性能数学公式渲染器 - 基于Canvas，无WebView依赖
 * 支持常见的数学表达式渲染
 */
class MathRenderer {
    private val textPaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("serif", Typeface.NORMAL)
    }
    
    private val symbolPaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("serif", Typeface.NORMAL)
    }

    /**
     * 渲染数学表达式
     */
    fun renderMath(
        canvas: Canvas,
        latex: String,
        x: Float,
        y: Float,
        textSize: Float,
        color: Color,
        isDisplay: Boolean = false
    ): RenderResult {
        textPaint.textSize = if (isDisplay) textSize * 1.2f else textSize
        textPaint.color = color.toArgb()
        symbolPaint.textSize = textPaint.textSize
        symbolPaint.color = color.toArgb()
        
        return when {
            latex.contains("\\frac") -> renderFraction(canvas, latex, x, y)
            latex.contains("^") -> renderSuperscript(canvas, latex, x, y)
            latex.contains("_") -> renderSubscript(canvas, latex, x, y)
            latex.contains("\\sqrt") -> renderSquareRoot(canvas, latex, x, y)
            latex.contains("\\sum") -> renderSum(canvas, latex, x, y)
            latex.contains("\\int") -> renderIntegral(canvas, latex, x, y)
            else -> renderSimple(canvas, latex, x, y)
        }
    }

    private fun renderFraction(canvas: Canvas, latex: String, x: Float, y: Float): RenderResult {
        // 解析 \frac{numerator}{denominator}
        val fracRegex = """\\frac\{([^}]+)\}\{([^}]+)\}""".toRegex()
        val match = fracRegex.find(latex)
        
        if (match != null) {
            val numerator = match.groupValues[1]
            val denominator = match.groupValues[2]
            
            val numBounds = Rect()
            val denBounds = Rect()
            textPaint.getTextBounds(numerator, 0, numerator.length, numBounds)
            textPaint.getTextBounds(denominator, 0, denominator.length, denBounds)
            
            val maxWidth = maxOf(numBounds.width(), denBounds.width()).toFloat()
            val lineY = y
            val numY = lineY - textPaint.textSize * 0.3f
            val denY = lineY + textPaint.textSize * 0.8f
            
            // 绘制分子
            val numX = x + (maxWidth - numBounds.width()) / 2
            canvas.drawText(numerator, numX, numY, textPaint)
            
            // 绘制分母
            val denX = x + (maxWidth - denBounds.width()) / 2
            canvas.drawText(denominator, denX, denY, textPaint)
            
            // 绘制分数线
            canvas.drawLine(x, lineY, x + maxWidth, lineY, textPaint)
            
            return RenderResult(
                width = maxWidth,
                height = textPaint.textSize * 1.5f,
                baseline = textPaint.textSize * 0.3f
            )
        }
        
        return renderSimple(canvas, latex, x, y)
    }

    private fun renderSuperscript(canvas: Canvas, latex: String, x: Float, y: Float): RenderResult {
        // 解析上标 base^{superscript}
        val supRegex = """([^_^]+)\^\{([^}]+)\}""".toRegex()
        val match = supRegex.find(latex)
        
        if (match != null) {
            val base = match.groupValues[1]
            val superscript = match.groupValues[2]
            
            val baseBounds = Rect()
            textPaint.getTextBounds(base, 0, base.length, baseBounds)
            
            // 绘制底数
            canvas.drawText(base, x, y, textPaint)
            
            // 绘制上标（较小字体）
            val supPaint = Paint(textPaint).apply {
                textSize = textPaint.textSize * 0.7f
            }
            val supX = x + baseBounds.width()
            val supY = y - textPaint.textSize * 0.4f
            canvas.drawText(superscript, supX, supY, supPaint)
            
            val supBounds = Rect()
            supPaint.getTextBounds(superscript, 0, superscript.length, supBounds)
            
            return RenderResult(
                width = baseBounds.width() + supBounds.width().toFloat(),
                height = textPaint.textSize,
                baseline = 0f
            )
        }
        
        return renderSimple(canvas, latex, x, y)
    }

    private fun renderSubscript(canvas: Canvas, latex: String, x: Float, y: Float): RenderResult {
        // 解析下标 base_{subscript}
        val subRegex = """([^_^]+)_\{([^}]+)\}""".toRegex()
        val match = subRegex.find(latex)
        
        if (match != null) {
            val base = match.groupValues[1]
            val subscript = match.groupValues[2]
            
            val baseBounds = Rect()
            textPaint.getTextBounds(base, 0, base.length, baseBounds)
            
            // 绘制底数
            canvas.drawText(base, x, y, textPaint)
            
            // 绘制下标（较小字体）
            val subPaint = Paint(textPaint).apply {
                textSize = textPaint.textSize * 0.7f
            }
            val subX = x + baseBounds.width()
            val subY = y + textPaint.textSize * 0.3f
            canvas.drawText(subscript, subX, subY, subPaint)
            
            val subBounds = Rect()
            subPaint.getTextBounds(subscript, 0, subscript.length, subBounds)
            
            return RenderResult(
                width = baseBounds.width() + subBounds.width().toFloat(),
                height = textPaint.textSize,
                baseline = 0f
            )
        }
        
        return renderSimple(canvas, latex, x, y)
    }

    private fun renderSquareRoot(canvas: Canvas, latex: String, x: Float, y: Float): RenderResult {
        // 解析 \sqrt{content}
        val sqrtRegex = """\\sqrt\{([^}]+)\}""".toRegex()
        val match = sqrtRegex.find(latex)
        
        if (match != null) {
            val content = match.groupValues[1]
            val contentBounds = Rect()
            textPaint.getTextBounds(content, 0, content.length, contentBounds)
            
            val rootWidth = textPaint.textSize * 0.4f
            val contentX = x + rootWidth
            
            // 绘制根号内容
            canvas.drawText(content, contentX, y, textPaint)
            
            // 绘制根号符号
            val rootHeight = textPaint.textSize
            canvas.drawLine(x, y, x + rootWidth * 0.3f, y + rootHeight * 0.3f, textPaint)
            canvas.drawLine(x + rootWidth * 0.3f, y + rootHeight * 0.3f, x + rootWidth * 0.7f, y - rootHeight * 0.2f, textPaint)
            canvas.drawLine(x + rootWidth * 0.7f, y - rootHeight * 0.2f, contentX + contentBounds.width(), y - rootHeight * 0.2f, textPaint)
            
            return RenderResult(
                width = rootWidth + contentBounds.width(),
                height = rootHeight,
                baseline = 0f
            )
        }
        
        return renderSimple(canvas, latex, x, y)
    }

    private fun renderSum(canvas: Canvas, latex: String, x: Float, y: Float): RenderResult {
        // 简化的求和符号渲染
        val sumSymbol = "Σ"
        val sumPaint = Paint(textPaint).apply {
            textSize = textPaint.textSize * 1.5f
        }
        canvas.drawText(sumSymbol, x, y, sumPaint)
        
        val bounds = Rect()
        sumPaint.getTextBounds(sumSymbol, 0, sumSymbol.length, bounds)
        
        return RenderResult(
            width = bounds.width().toFloat(),
            height = bounds.height().toFloat(),
            baseline = 0f
        )
    }

    private fun renderIntegral(canvas: Canvas, latex: String, x: Float, y: Float): RenderResult {
        // 简化的积分符号渲染
        val integralSymbol = "∫"
        val integralPaint = Paint(textPaint).apply {
            textSize = textPaint.textSize * 1.5f
        }
        canvas.drawText(integralSymbol, x, y, integralPaint)
        
        val bounds = Rect()
        integralPaint.getTextBounds(integralSymbol, 0, integralSymbol.length, bounds)
        
        return RenderResult(
            width = bounds.width().toFloat(),
            height = bounds.height().toFloat(),
            baseline = 0f
        )
    }

    private fun renderSimple(canvas: Canvas, latex: String, x: Float, y: Float): RenderResult {
        // 简单的文本渲染，处理一些基本的LaTeX符号
        val text = latex
            .replace("\\alpha", "α")
            .replace("\\beta", "β")
            .replace("\\gamma", "γ")
            .replace("\\delta", "δ")
            .replace("\\epsilon", "ε")
            .replace("\\theta", "θ")
            .replace("\\lambda", "λ")
            .replace("\\mu", "μ")
            .replace("\\pi", "π")
            .replace("\\sigma", "σ")
            .replace("\\phi", "φ")
            .replace("\\omega", "ω")
            .replace("\\infty", "∞")
            .replace("\\pm", "±")
            .replace("\\mp", "∓")
            .replace("\\times", "×")
            .replace("\\div", "÷")
            .replace("\\leq", "≤")
            .replace("\\geq", "≥")
            .replace("\\neq", "≠")
            .replace("\\approx", "≈")
            .replace("\\equiv", "≡")
            .replace("\\in", "∈")
            .replace("\\subset", "⊂")
            .replace("\\supset", "⊃")
            .replace("\\cup", "∪")
            .replace("\\cap", "∩")
            .replace("\\rightarrow", "→")
            .replace("\\leftarrow", "←")
            .replace("\\leftrightarrow", "↔")
            .replace("\\Rightarrow", "⇒")
            .replace("\\Leftarrow", "⇐")
            .replace("\\Leftrightarrow", "⇔")
        
        canvas.drawText(text, x, y, textPaint)
        
        val bounds = Rect()
        textPaint.getTextBounds(text, 0, text.length, bounds)
        
        return RenderResult(
            width = bounds.width().toFloat(),
            height = bounds.height().toFloat(),
            baseline = 0f
        )
    }

    data class RenderResult(
        val width: Float,
        val height: Float,
        val baseline: Float
    )
}