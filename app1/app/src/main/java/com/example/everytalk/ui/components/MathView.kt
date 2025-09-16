package com.example.everytalk.ui.components

// 🎯 修复版本 - 恢复所有必要的数学渲染功能
// 
// 解决问题：
// - SmartMathView调用不存在的MathView函数
// - 保持向后兼容性
// - 集成优化的渲染系统

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.example.everytalk.data.DataClass.Message
import com.example.everytalk.data.DataClass.Sender

/**
 * 简化版数学公式组件，用于简单的数学表达式
 * 当KaTeX不可用时的后备方案
 */
@Composable
fun SimpleMathView(
    expression: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    textSize: TextUnit = 14.sp
) {
    Text(
        text = formatMathExpression(expression),
        modifier = modifier,
        color = textColor,
        fontSize = textSize
    )
}

/**
 * 🎯 修复的MathView函数 - 使用统一渲染系统
 */
@Composable
fun MathView(
    latex: String,
    isDisplay: Boolean,
    textColor: Color,
    modifier: Modifier = Modifier,
    textSize: TextUnit = 16.sp,
    delayMs: Long = 0L
) {
    // 创建临时Message对象用于渲染
    val mathMessage = remember(latex, isDisplay) {
        Message(
            id = "math_temp",
            text = if (isDisplay) "$$${latex}$$" else "$${latex}$",
            sender = Sender.AI,
            parts = listOf(
                MarkdownPart.MathBlock(id = "math_${latex.hashCode()}", latex = latex)
            ),
            timestamp = System.currentTimeMillis()
        )
    }
    
    // 延迟渲染支持
    var shouldRender by remember(delayMs) { mutableStateOf(delayMs == 0L) }
    
    LaunchedEffect(latex, delayMs) {
        if (delayMs > 0L) {
            shouldRender = false
            kotlinx.coroutines.delay(delayMs)
            shouldRender = true
        }
    }
    
    if (shouldRender) {
        OptimizedUnifiedRenderer(
            message = mathMessage,
            modifier = modifier,
            style = androidx.compose.ui.text.TextStyle(
                fontSize = textSize,
                color = textColor
            ),
            textColor = textColor
        )
    }
}

/**
 * 智能数学公式组件 - 根据表达式复杂度自动选择渲染方式
 */
@Composable
fun SmartMathView(
    expression: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    textSize: TextUnit = 16.sp,
    isDisplay: Boolean = false,
    delayMs: Long = 0L
) {
    val trimmed = expression.trim()

    // 明确的 LaTeX 指示：包含 $ 或 $$ 或 \ 开头的命令，或常见环境/命令
    val looksLikeLatex = remember(trimmed) {
        // 支持 $, $$, \\(...\\), \\[...\\] 分隔符；以及常见命令/花括号判断
        val hasDollarDelimiters = Regex("(?<!\\\\)\\$\\$|(?<!\\\\)\\$").containsMatchIn(trimmed)
        val hasBracketDelimiters = Regex("\\\\\\[|\\\\\\]").containsMatchIn(trimmed)
        val hasParenDelimiters = Regex("\\\\\\(|\\\\\\)").containsMatchIn(trimmed)
        val hasCommands = Regex("\\\\(frac|sqrt|sum|int|lim|prod|binom|begin|end|over|underline|overline|text|mathbb|mathrm|mathbf|vec|hat|bar|dot|ddot|left|right|pm|times|div|leq|geq|neq|approx|to|rightarrow|leftarrow)").containsMatchIn(trimmed)
        val hasBraces = trimmed.contains('{') && trimmed.contains('}')
        hasDollarDelimiters || hasBracketDelimiters || hasParenDelimiters || hasCommands || hasBraces
    }

    if (looksLikeLatex) {
        MathView(
            latex = trimmed,
            isDisplay = isDisplay,
            textColor = textColor,
            modifier = modifier,
            textSize = textSize,
            delayMs = delayMs
        )
    } else {
        SimpleMathView(
            expression = trimmed,
            modifier = modifier,
            textColor = textColor,
            textSize = textSize
        )
    }
}

/**
 * 向后兼容的旧版本API别名
 */
@Composable
fun WebMathView(
    latex: String,
    isDisplay: Boolean,
    textColor: Color,
    modifier: Modifier = Modifier,
    delayMs: Long = 0L
) {
    MathView(latex, isDisplay, textColor, modifier, delayMs = delayMs)
}

/**
 * 非破坏性的符号友好替换，仅用于纯文本展示。
 * 不再删除花括号/美元符号，避免破坏合法的 LaTeX。
 */
private fun formatMathExpression(latex: String): String {
    // 若包含任何可能的 LaTeX 控制符或分隔符，则直接返回原文，让 SmartMathView 选择 KaTeX
    if (latex.contains('\\') || latex.contains('{') || latex.contains('}') || latex.contains('$')) {
        return latex
    }
    return latex
        .replace("\\u03B1", "α") // 容错：万一传入的是转义形式
        .replace("alpha", "α")
        .replace("beta", "β")
        .replace("gamma", "γ")
        .replace("delta", "δ")
        .replace("epsilon", "ε")
        .replace("theta", "θ")
        .replace("lambda", "λ")
        .replace("mu", "μ")
        .replace("pi", "π")
        .replace("sigma", "σ")
        .replace("phi", "φ")
        .replace("omega", "ω")
        .replace("infty", "∞")
        .replace("pm", "±")
        .replace("times", "×")
        .replace("div", "÷")
        .replace("leq", "≤")
        .replace("geq", "≥")
        .replace("neq", "≠")
        .replace("approx", "≈")
        .replace("->", "→")
        .replace("<-", "←")
        .replace("<->", "↔")
}