package com.example.everytalk.ui.components.math

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/**
 * 高性能Canvas数学公式组件
 * 完全替代WebView，提供极高的渲染性能
 */
@Composable
fun CanvasMathView(
    latex: String,
    modifier: Modifier = Modifier,
    textColor: Color = Color.Black,
    textSize: TextUnit = 16.sp,
    isDisplay: Boolean = false,
    backgroundColor: Color = Color.Transparent
) {
    val density = LocalDensity.current
    val mathRenderer = remember { MathRenderer() }
    
    // 预计算尺寸以优化布局
    val (width, height) = remember(latex, textSize, isDisplay) {
        val textSizePx = with(density) { textSize.toPx() }
        // 简单估算，实际渲染时会更精确
        val estimatedWidth = latex.length * textSizePx * 0.6f
        val estimatedHeight = if (isDisplay) textSizePx * 1.5f else textSizePx * 1.2f
        Pair(estimatedWidth, estimatedHeight)
    }
    
    Canvas(
        modifier = modifier
            .then(
                if (isDisplay) {
                    Modifier
                        .fillMaxWidth()
                        .height(with(density) { height.toDp() })
                } else {
                    Modifier
                        .wrapContentWidth()
                        .wrapContentHeight()
                }
            )
    ) {
        // 绘制背景
        if (backgroundColor != Color.Transparent) {
            drawRect(backgroundColor)
        }
        
        val canvas = drawContext.canvas.nativeCanvas
        val textSizePx = textSize.toPx()
        
        // 计算起始位置
        val startX = if (isDisplay) {
            (size.width - width) / 2f
        } else {
            0f
        }
        val startY = size.height / 2f + textSizePx / 3f // 垂直居中
        
        try {
            mathRenderer.renderMath(
                canvas = canvas,
                latex = latex,
                x = startX,
                y = startY,
                textSize = textSizePx,
                color = textColor,
                isDisplay = isDisplay
            )
        } catch (e: Exception) {
            // 渲染失败时显示原始文本
            val paint = android.graphics.Paint().apply {
                this.textSize = textSizePx
                this.color = textColor.toArgb()
                isAntiAlias = true
            }
            canvas.drawText("渲染错误: $latex", startX, startY, paint)
        }
    }
}

/**
 * 轻量级数学文本组件 - 用于简单的数学符号
 */
@Composable
fun SimpleMathText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Black,
    fontSize: TextUnit = 14.sp
) {
    val processedText = remember(text) {
        text.replace("\\alpha", "α")
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
            .replace("\\times", "×")
            .replace("\\div", "÷")
            .replace("\\leq", "≤")
            .replace("\\geq", "≥")
            .replace("\\neq", "≠")
            .replace("\\approx", "≈")
            .replace("\\rightarrow", "→")
            .replace("\\leftarrow", "←")
    }
    
    androidx.compose.material3.Text(
        text = processedText,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontFamily = FontFamily.Serif
    )
}

/**
 * 智能数学公式组件 - 根据复杂度自动选择渲染方式
 */
@Composable
fun SmartMathView(
    latex: String,
    modifier: Modifier = Modifier,
    textColor: Color = Color.Black,
    textSize: TextUnit = 16.sp,
    isDisplay: Boolean = false
) {
    val isComplex = remember(latex) {
        latex.contains("\\frac") || 
        latex.contains("\\sqrt") || 
        latex.contains("\\sum") || 
        latex.contains("\\int") ||
        latex.contains("^{") ||
        latex.contains("_{")
    }
    
    if (isComplex) {
        CanvasMathView(
            latex = latex,
            modifier = modifier,
            textColor = textColor,
            textSize = textSize,
            isDisplay = isDisplay
        )
    } else {
        SimpleMathText(
            text = latex,
            modifier = modifier,
            color = textColor,
            fontSize = textSize
        )
    }
}