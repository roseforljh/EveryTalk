package com.example.everytalk.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import dev.jeziellago.compose.markdowntext.MarkdownText
import java.util.regex.Pattern

/**
 * 基于Compose的数学公式渲染器
 * 🎯 支持真正的分数上下结构显示
 */
@Composable
fun ComposeMathRenderer(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    modifier: Modifier = Modifier
) {
    val processedText = remember(text) {
        processLatexFormulas(text)
    }
    
    // 🎯 简化：直接使用MarkdownText处理所有内容，包括数学公式
    MarkdownText(
        markdown = processedText,
        style = style.copy(color = color),
        modifier = modifier
    )
}


/**
 * 处理LaTeX公式，转换为可显示的Unicode文本
 */
private fun processLatexFormulas(text: String): String {
    var result = text
    
    // 🎯 关键修复：首先处理所有LaTeX符号，不管是否在$符号内
    result = processComplexLatexStructures(result)
    
    // 🎯 新增：预处理，修复常见的AI输出格式问题
    result = fixCommonFormattingIssues(result)
    
    // 🎯 修复：先处理块级公式 $$...$$ 避免与行内公式冲突
    val blockPattern = Pattern.compile("\\$\\$([^$]+?)\\$\\$")
    val blockMatcher = blockPattern.matcher(result)
    val blockBuffer = StringBuffer()
    
    while (blockMatcher.find()) {
        val latexContent = blockMatcher.group(1) ?: ""
        val converted = convertLatexToUnicode(latexContent)
        // 🎯 修复：块级公式不添加$符号，只加粗显示
        blockMatcher.appendReplacement(blockBuffer, java.util.regex.Matcher.quoteReplacement("\n\n**$converted**\n\n"))
    }
    blockMatcher.appendTail(blockBuffer)
    result = blockBuffer.toString()
    
    // 然后处理行内公式 $...$
    val inlinePattern = Pattern.compile("\\$([^$\n]+?)\\$")
    val inlineMatcher = inlinePattern.matcher(result)
    val inlineBuffer = StringBuffer()
    
    while (inlineMatcher.find()) {
        val latexContent = inlineMatcher.group(1) ?: ""
        val converted = convertLatexToUnicode(latexContent)
        // 🎯 修复：行内公式也不显示$符号
        inlineMatcher.appendReplacement(inlineBuffer, java.util.regex.Matcher.quoteReplacement(converted))
    }
    inlineMatcher.appendTail(inlineBuffer)
    result = inlineBuffer.toString()
    
    return result
}

/**
 * 修复常见的AI输出格式问题
 */
private fun fixCommonFormattingIssues(text: String): String {
    var result = text
    
    // 🎯 修复：开头的*应该是列表项，但AI错误地包含在公式中
    // 将 "$*expression$" 转换为 "• $expression$"
    // 使用Matcher.quoteReplacement来避免$符号被误解为组引用
    val dollarStarPattern = Regex("\\$\\s*\\*\\s*([^$]+)\\$")
    result = dollarStarPattern.replace(result) { matchResult ->
        "• " + java.util.regex.Matcher.quoteReplacement("$${matchResult.groupValues[1]}$")
    }
    
    // 🎯 修复：多行数学表达式中的列表项
    // 将独立行的 "*expression" 转换为 "• expression"
    result = result.replace(Regex("(?m)^\\s*\\*\\s*([^*\n]+)$")) { matchResult ->
        "• ${matchResult.groupValues[1]}"
    }
    
    // 🎯 修复：错误的cdot使用，应该转换为·
    result = result.replace("cdot", "⋅")
    
    // 🎯 修复：常见的LaTeX间距问题
    result = result.replace(Regex("\\$\\s+"), java.util.regex.Matcher.quoteReplacement("\$"))  // 去除$后的多余空格
                    .replace(Regex("\\s+\\$"), java.util.regex.Matcher.quoteReplacement("\$"))  // 去除$前的多余空格
    
    return result
}

/**
 * 将LaTeX符号转换为Unicode字符
 */
private fun convertLatexToUnicode(latex: String): String {
    var result = latex.trim()
    
    // 🎯 首先处理复杂的LaTeX结构
    result = processComplexLatexStructures(result)
    
    // 基础数学符号替换
    val symbolMap = mapOf(
        // 希腊字母 (小写)
        "\\alpha" to "α",
        "\\beta" to "β", 
        "\\gamma" to "γ",
        "\\delta" to "δ",
        "\\epsilon" to "ε",
        "\\varepsilon" to "ε",
        "\\zeta" to "ζ",
        "\\eta" to "η",
        "\\theta" to "θ",
        "\\vartheta" to "ϑ",
        "\\iota" to "ι",
        "\\kappa" to "κ",
        "\\lambda" to "λ",
        "\\mu" to "μ",
        "\\nu" to "ν",
        "\\xi" to "ξ",
        "\\pi" to "π",
        "\\varpi" to "ϖ",
        "\\rho" to "ρ",
        "\\varrho" to "ϱ",
        "\\sigma" to "σ",
        "\\varsigma" to "ς",
        "\\tau" to "τ",
        "\\upsilon" to "υ",
        "\\phi" to "φ",
        "\\varphi" to "φ",
        "\\chi" to "χ",
        "\\psi" to "ψ",
        "\\omega" to "ω",
        
        // 希腊字母 (大写)
        "\\Alpha" to "Α",
        "\\Beta" to "Β",
        "\\Gamma" to "Γ",
        "\\Delta" to "Δ",
        "\\Epsilon" to "Ε",
        "\\Zeta" to "Ζ",
        "\\Eta" to "Η",
        "\\Theta" to "Θ",
        "\\Iota" to "Ι",
        "\\Kappa" to "Κ",
        "\\Lambda" to "Λ",
        "\\Mu" to "Μ",
        "\\Nu" to "Ν",
        "\\Xi" to "Ξ",
        "\\Pi" to "Π",
        "\\Rho" to "Ρ",
        "\\Sigma" to "Σ",
        "\\Tau" to "Τ",
        "\\Upsilon" to "Υ",
        "\\Phi" to "Φ",
        "\\Chi" to "Χ",
        "\\Psi" to "Ψ",
        "\\Omega" to "Ω",
        
        // 三角函数 (带反斜杠和不带反斜杠的版本)
        "\\sin" to "sin",
        "\\cos" to "cos",
        "\\tan" to "tan",
        "\\cot" to "cot",
        "\\sec" to "sec",
        "\\csc" to "csc",
        "\\arcsin" to "arcsin",
        "\\arccos" to "arccos",
        "\\arctan" to "arctan",
        "\\sinh" to "sinh",
        "\\cosh" to "cosh",
        "\\tanh" to "tanh",
        // 不带反斜杠的版本 - 使用更严格的匹配模式
        "(?<![a-zA-Z])sin(?![a-zA-Z])" to "sin",
        "(?<![a-zA-Z])cos(?![a-zA-Z])" to "cos", 
        "(?<![a-zA-Z])tan(?![a-zA-Z])" to "tan",
        "(?<![a-zA-Z])cot(?![a-zA-Z])" to "cot",
        "(?<![a-zA-Z])sec(?![a-zA-Z])" to "sec",
        "(?<![a-zA-Z])csc(?![a-zA-Z])" to "csc",
        "(?<![a-zA-Z])sinh(?![a-zA-Z])" to "sinh",
        "(?<![a-zA-Z])cosh(?![a-zA-Z])" to "cosh",
        "(?<![a-zA-Z])tanh(?![a-zA-Z])" to "tanh",
        "(?<![a-zA-Z])arcsin(?![a-zA-Z])" to "arcsin",
        "(?<![a-zA-Z])arccos(?![a-zA-Z])" to "arccos",
        "(?<![a-zA-Z])arctan(?![a-zA-Z])" to "arctan",
        
        // 对数和指数函数 (带反斜杠和不带反斜杠的版本)
        "\\log" to "log",
        "\\ln" to "ln",
        "\\lg" to "lg",
        "\\exp" to "exp",
        // 不带反斜杠的版本 - 使用更严格的匹配模式
        "(?<![a-zA-Z])log(?![a-zA-Z])" to "log",
        "(?<![a-zA-Z])ln(?![a-zA-Z])" to "ln",
        "(?<![a-zA-Z])lg(?![a-zA-Z])" to "lg",
        "(?<![a-zA-Z])exp(?![a-zA-Z])" to "exp",
        
        // 数学运算符
        "\\pm" to "±",
        "\\mp" to "∓",
        "\\times" to "×",
        "\\div" to "÷",
        "\\cdot" to "⋅",
        "\\ast" to "∗",
        "\\star" to "⋆",
        "\\circ" to "∘",
        "\\bullet" to "•",
        "\\oplus" to "⊕",
        "\\ominus" to "⊖",
        "\\otimes" to "⊗",
        "\\oslash" to "⊘",
        "\\odot" to "⊙",
        
        // 关系符号
        "\\le" to "≤",
        "\\leq" to "≤",
        "\\ge" to "≥",
        "\\geq" to "≥",
        "\\ne" to "≠",
        "\\neq" to "≠",
        "\\equiv" to "≡",
        "\\approx" to "≈",
        "\\sim" to "∼",
        "\\simeq" to "≃",
        "\\cong" to "≅",
        "\\propto" to "∝",
        "\\ll" to "≪",
        "\\gg" to "≫",
        "\\prec" to "≺",
        "\\succ" to "≻",
        "\\preceq" to "⪯",
        "\\succeq" to "⪰",
        
        // 集合符号
        "\\in" to "∈",
        "\\notin" to "∉",
        "\\ni" to "∋",
        "\\subset" to "⊂",
        "\\supset" to "⊃",
        "\\subseteq" to "⊆",
        "\\supseteq" to "⊇",
        "\\cup" to "∪",
        "\\cap" to "∩",
        "\\setminus" to "∖",
        "\\emptyset" to "∅",
        "\\varnothing" to "∅",
        
        // 逻辑符号
        "\\land" to "∧",
        "\\lor" to "∨",
        "\\lnot" to "¬",
        "\\neg" to "¬",
        "\\forall" to "∀",
        "\\exists" to "∃",
        "\\nexists" to "∄",
        "\\therefore" to "∴",
        "\\because" to "∵",
        
        // 箭头符号
        "\\rightarrow" to "→",
        "\\to" to "→",
        "\\leftarrow" to "←",
        "\\leftrightarrow" to "↔",
        "\\Rightarrow" to "⇒",
        "\\Leftarrow" to "⇐",
        "\\Leftrightarrow" to "⇔",
        "\\iff" to "⇔",
        "\\implies" to "⇒",
        "\\uparrow" to "↑",
        "\\downarrow" to "↓",
        "\\updownarrow" to "↕",
        "\\nearrow" to "↗",
        "\\searrow" to "↘",
        "\\swarrow" to "↙",
        "\\nwarrow" to "↖",
        
        // 微积分符号
        "\\int" to "∫",
        "\\iint" to "∬",
        "\\iiint" to "∭",
        "\\oint" to "∮",
        "\\partial" to "∂",
        "\\nabla" to "∇",
        "\\infty" to "∞",
        "\\lim" to "lim",
        "\\limsup" to "lim sup",
        "\\liminf" to "lim inf",
        
        // 🎯 新增：常见数学函数和常数
        "(?<![a-zA-Z])max(?![a-zA-Z])" to "max",
        "(?<![a-zA-Z])min(?![a-zA-Z])" to "min",
        "(?<![a-zA-Z])sup(?![a-zA-Z])" to "sup",
        "(?<![a-zA-Z])inf(?![a-zA-Z])" to "inf",
        "(?<![a-zA-Z])det(?![a-zA-Z])" to "det",
        "(?<![a-zA-Z])arg(?![a-zA-Z])" to "arg",
        "(?<![a-zA-Z])gcd(?![a-zA-Z])" to "gcd",
        "(?<![a-zA-Z])lcm(?![a-zA-Z])" to "lcm",
        "(?<![a-zA-Z])dim(?![a-zA-Z])" to "dim",
        "(?<![a-zA-Z])ker(?![a-zA-Z])" to "ker",
        "(?<![a-zA-Z])deg(?![a-zA-Z])" to "deg",
        
        // 求和与乘积
        "\\sum" to "Σ",
        "\\prod" to "Π",
        "\\coprod" to "∐",
        "\\bigcup" to "⋃",
        "\\bigcap" to "⋂",
        "\\bigoplus" to "⨁",
        "\\bigotimes" to "⨂",
        
        // 其他数学符号
        "\\sqrt" to "√",
        "\\angle" to "∠",
        "\\measuredangle" to "∡",
        "\\sphericalangle" to "∢",
        "\\degree" to "°",
        "\\triangle" to "△",
        "\\square" to "□",
        "\\blacksquare" to "■",
        "\\diamond" to "◊",
        "\\blacklozenge" to "⧫",
        "\\bigstar" to "★",
        "\\blacktriangle" to "▲",
        "\\vartriangle" to "△",
        "\\triangledown" to "▽",
        "\\blacktriangledown" to "▼",
        
        // 特殊常数
        "\\e" to "e",
        "\\i" to "i",
        "\\mathbf\\{i\\}" to "𝐢",
        "\\mathbf\\{j\\}" to "𝐣",
        "\\mathbf\\{k\\}" to "𝐤",
        
        // 分隔符
        "\\mid" to "|",
        "\\parallel" to "∥",
        "\\perp" to "⊥",
        "\\top" to "⊤",
        "\\bot" to "⊥",
        
        // 省略号
        "\\ldots" to "…",
        "\\cdots" to "⋯",
        "\\vdots" to "⋮",
        "\\ddots" to "⋱",
        
        // 数学字体命令（简化处理）
        "\\mathbb\\{R\\}" to "ℝ",
        "\\mathbb\\{C\\}" to "ℂ",
        "\\mathbb\\{N\\}" to "ℕ",
        "\\mathbb\\{Z\\}" to "ℤ",
        "\\mathbb\\{Q\\}" to "ℚ",
        "\\mathbb\\{P\\}" to "ℙ",
        "\\mathcal\\{L\\}" to "ℒ",
        "\\mathcal\\{F\\}" to "ℱ",
        "\\mathcal\\{O\\}" to "𝒪",
        
        // 单位和常数
        "\\hbar" to "ℏ",
        "\\ell" to "ℓ",
        "\\wp" to "℘",
        "\\Re" to "ℜ",
        "\\Im" to "ℑ",
        "\\aleph" to "ℵ",
        "\\beth" to "ℶ",
        "\\gimel" to "ℷ",
        "\\daleth" to "ℸ"
    )
    
    // 应用符号替换
    for ((latex, unicode) in symbolMap) {
        if (latex.startsWith("(?<") || latex.startsWith("\\b") && latex.endsWith("\\b")) {
            // 这是一个正则表达式模式，需要特殊处理
            result = result.replace(Regex(latex), unicode)
        } else {
            // 普通字符串替换
            result = result.replace(latex, unicode)
        }
    }
    
    // 🎯 修复：处理单独的*符号（在LaTeX中通常是乘法）
    // 但要小心不影响Markdown的**粗体**语法
    result = result.replace(Regex("(?<!\\*)\\*(?!\\*)"), "⋅")
    
    // 🎯 新增：处理常见的数学表达式模式
    // 处理 "f(x)" 类型的函数表示
    result = result.replace(Regex("\\b([a-zA-Z])\\s*\\(([^)]+)\\)")) { matchResult ->
        "${matchResult.groupValues[1]}(${matchResult.groupValues[2]})"
    }
    
    // 处理复数单位 "i" 前面的系数乘法（如 "2i" -> "2×i"）
    result = result.replace(Regex("\\b(\\d+)\\s*([a-zA-Z])\\b")) { matchResult ->
        val number = matchResult.groupValues[1]
        val variable = matchResult.groupValues[2]
        // 只对单个字母变量进行处理，避免影响函数名
        if (variable.length == 1) {
            "$number×$variable"
        } else {
            matchResult.value
        }
    }
    
    // 处理平方、立方等常见指数形式（如 x^2, x^3）
    result = result.replace(Regex("\\^(\\d)")) { matchResult ->
        val digit = matchResult.groupValues[1]
        when (digit) {
            "2" -> "²"
            "3" -> "³"
            else -> "^$digit"
        }
    }
    
    // 处理上标和下标
    result = processSupAndSub(result)
    
    // 处理分数
    result = processFractions(result)
    
    // 处理根号
    result = processSqrt(result)
    
    // 处理花括号
    result = result.replace("\\{", "{").replace("\\}", "}")
    
    // 处理其他LaTeX命令
    result = processOtherLatexCommands(result)
    
    return result
}

/**
 * 处理上标和下标
 */
private fun processSupAndSub(text: String): String {
    var result = text
    
    // 处理上标 ^{...} 或 ^x
    val supPattern = Pattern.compile("\\^\\{([^}]+)\\}|\\^(.)")
    val supMatcher = supPattern.matcher(result)
    val supBuffer = StringBuffer()
    
    while (supMatcher.find()) {
        val content = supMatcher.group(1) ?: supMatcher.group(2)
        val superscript = convertToSuperscript(content)
        supMatcher.appendReplacement(supBuffer, java.util.regex.Matcher.quoteReplacement(superscript))
    }
    supMatcher.appendTail(supBuffer)
    result = supBuffer.toString()
    
    // 处理下标 _{...} 或 _x  
    val subPattern = Pattern.compile("_\\{([^}]+)\\}|_(.)")
    val subMatcher = subPattern.matcher(result)
    val subBuffer = StringBuffer()
    
    while (subMatcher.find()) {
        val content = subMatcher.group(1) ?: subMatcher.group(2)
        val subscript = convertToSubscript(content)
        subMatcher.appendReplacement(subBuffer, java.util.regex.Matcher.quoteReplacement(subscript))
    }
    subMatcher.appendTail(subBuffer)
    result = subBuffer.toString()
    
    return result
}

/**
 * 处理分数 \frac{a}{b} - 改进为真正的分数显示
 */
private fun processFractions(text: String): String {
    var result = text
    
    // 🎯 关键改进：处理LaTeX分数为真正的分数结构
    val fracPattern = Pattern.compile("\\\\frac\\{([^}]+)\\}\\{([^}]+)\\}")
    val matcher = fracPattern.matcher(result)
    val buffer = StringBuffer()
    
    while (matcher.find()) {
        val numerator = matcher.group(1)
        val denominator = matcher.group(2)
        
        // 🎯 新方法：创建真正的分数显示效果
        val fractionDisplay = createFractionDisplay(numerator, denominator)
        matcher.appendReplacement(buffer, java.util.regex.Matcher.quoteReplacement(fractionDisplay))
    }
    matcher.appendTail(buffer)
    result = buffer.toString()
    
    // 🎯 增强：处理更多类型的分数格式，包括复杂表达式
    // 匹配模式：数字/数字、变量/变量、简单表达式/简单表达式
    val enhancedFracPattern = Pattern.compile("\\b(\\d+(?:\\.\\d+)?|[a-zA-Z]+(?:[_^][a-zA-Z0-9]+)?|\\([^)]+\\))\\s*/\\s*(\\d+(?:\\.\\d+)?|[a-zA-Z]+(?:[_^][a-zA-Z0-9]+)?|\\([^)]+\\))\\b")
    val enhancedMatcher = enhancedFracPattern.matcher(result)
    val enhancedBuffer = StringBuffer()
    
    while (enhancedMatcher.find()) {
        val numerator = enhancedMatcher.group(1)
        val denominator = enhancedMatcher.group(2)
        
        // 🎯 对简单分数也使用改进的显示方式
        val fractionDisplay = createSimpleFractionDisplay(numerator, denominator)
        enhancedMatcher.appendReplacement(enhancedBuffer, java.util.regex.Matcher.quoteReplacement(fractionDisplay))
    }
    enhancedMatcher.appendTail(enhancedBuffer)
    result = enhancedBuffer.toString()
    
    // 🎯 新增：处理残留的普通斜杠，在数学环境中转换为Unicode分数斜杠
    // 扩展处理：数字/数字、字母/字母、数字/字母等情况
    result = result.replace(Regex("([\\d\\w+\\-()]+)\\s*/\\s*([\\d\\w+\\-()]+)")) { matchResult ->
        val num = matchResult.groupValues[1]
        val den = matchResult.groupValues[2]
        // 如果是网址等特殊情况，不转换
        if (num.contains("http") || den.contains("http") || num.contains("www") || den.contains("www")) {
            matchResult.value
        } else {
            createSimpleFractionDisplay(num, den)
        }
    }
    
    return result
}

/**
 * 处理根号 \sqrt{x} 或 \sqrt[n]{x}
 */
private fun processSqrt(text: String): String {
    var result = text
    
    // 处理带次数的根号 \sqrt[n]{x}
    val nthRootPattern = Pattern.compile("\\\\sqrt\\[([^]]+)\\]\\{([^}]+)\\}")
    val nthMatcher = nthRootPattern.matcher(result)
    val nthBuffer = StringBuffer()
    
    while (nthMatcher.find()) {
        val index = nthMatcher.group(1)
        val content = nthMatcher.group(2)
        nthMatcher.appendReplacement(nthBuffer, java.util.regex.Matcher.quoteReplacement("$index√($content)"))
    }
    nthMatcher.appendTail(nthBuffer)
    result = nthBuffer.toString()
    
    // 处理普通根号 \sqrt{x}
    val sqrtPattern = Pattern.compile("\\\\sqrt\\{([^}]+)\\}")
    val sqrtMatcher = sqrtPattern.matcher(result)
    val sqrtBuffer = StringBuffer()
    
    while (sqrtMatcher.find()) {
        val content = sqrtMatcher.group(1)
        sqrtMatcher.appendReplacement(sqrtBuffer, java.util.regex.Matcher.quoteReplacement("√($content)"))
    }
    sqrtMatcher.appendTail(sqrtBuffer)
    result = sqrtBuffer.toString()
    
    return result
}

/**
 * 处理其他LaTeX命令
 */
private fun processOtherLatexCommands(text: String): String {
    var result = text
    
    // 处理 \left 和 \right 括号
    result = result.replace("\\left\\(", "(")
                  .replace("\\right\\)", ")")
                  .replace("\\left\\[", "[")
                  .replace("\\right\\]", "]")
                  .replace("\\left\\{", "{")
                  .replace("\\right\\}", "}")
                  .replace("\\left|", "|")
                  .replace("\\right|", "|")
    
    // 处理空格命令
    result = result.replace("\\,", " ")     // 小空格
                  .replace("\\:", " ")      // 中等空格  
                  .replace("\\;", " ")      // 大空格
                  .replace("\\!", "")       // 负空格
                  .replace("\\quad", "  ")  // 四分之一em空格
                  .replace("\\qquad", "    ") // 半em空格
    
    // 处理字体命令（简化）
    result = result.replace("\\\\text\\{([^}]+)\\}".toRegex(), "$1")
                  .replace("\\\\mathrm\\{([^}]+)\\}".toRegex(), "$1")
                  .replace("\\\\mathit\\{([^}]+)\\}".toRegex(), "$1")
                  .replace("\\\\mathbf\\{([^}]+)\\}".toRegex(), "**$1**")
                  .replace("\\\\mathcal\\{([^}]+)\\}".toRegex(), "$1")
                  .replace("\\\\mathbb\\{([^}]+)\\}".toRegex(), "$1")
    
    // 处理其他常见命令
    result = result.replace("\\\\", "\n")  // 换行
                  .replace("\\&", "&")        // 转义的&
                  .replace("\\%", "%")        // 转义的%
                  .replace("\\#", "#")        // 转义的#
                  .replace("\\_", "_")        // 转义的_
                  .replace("\\\\$", java.util.regex.Matcher.quoteReplacement("$"))      // 转义的$
    
    return result
}

/**
 * 转换为上标字符
 */
private fun convertToSuperscript(text: String): String {
    val superscriptMap = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'a' to 'ᵃ', 'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ',
        'f' to 'ᶠ', 'g' to 'ᵍ', 'h' to 'ʰ', 'i' to 'ⁱ', 'j' to 'ʲ',
        'k' to 'ᵏ', 'l' to 'ˡ', 'm' to 'ᵐ', 'n' to 'ⁿ', 'o' to 'ᵒ',
        'p' to 'ᵖ', 'r' to 'ʳ', 's' to 'ˢ', 't' to 'ᵗ', 'u' to 'ᵘ',
        'v' to 'ᵛ', 'w' to 'ʷ', 'x' to 'ˣ', 'y' to 'ʸ', 'z' to 'ᶻ',
        'A' to 'ᴬ', 'B' to 'ᴮ', 'D' to 'ᴰ', 'E' to 'ᴱ', 'G' to 'ᴳ',
        'H' to 'ᴴ', 'I' to 'ᴵ', 'J' to 'ᴶ', 'K' to 'ᴷ', 'L' to 'ᴸ',
        'M' to 'ᴹ', 'N' to 'ᴺ', 'O' to 'ᴼ', 'P' to 'ᴾ', 'R' to 'ᴿ',
        'T' to 'ᵀ', 'U' to 'ᵁ', 'V' to 'ⱽ', 'W' to 'ᵂ'
    )
    
    return text.map { char ->
        superscriptMap[char] ?: char
    }.joinToString("")
}

/**
 * 转换为下标字符
 */
private fun convertToSubscript(text: String): String {
    val subscriptMap = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ', 
        'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ', 
        'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ', 
        'v' to 'ᵥ', 'x' to 'ₓ'
    )
    
    return text.map { char ->
        subscriptMap[char] ?: char
    }.joinToString("")
}

/**
 * 🎯 新增：处理复杂的LaTeX数学结构
 */
private fun processComplexLatexStructures(text: String): String {
    var result = text
    
    // 🎯 首先处理所有基本符号 - 修复转义问题！
    val basicSymbols = mapOf(
        "\\infty" to "∞",
        "\\pi" to "π", 
        "\\dots" to "…",
        "\\ldots" to "…",
        "\\cdots" to "⋯",
        "\\vdots" to "⋮",
        "\\ddots" to "⋱",
        "\\cdot" to "⋅",
        "\\times" to "×",
        "\\pm" to "±",
        "\\neq" to "≠",
        "\\leq" to "≤",
        "\\geq" to "≥",
        "\\approx" to "≈",
        "\\in" to "∈",
        "\\subset" to "⊂",
        "\\cup" to "∪",
        "\\cap" to "∩",
        "\\emptyset" to "∅",
        "\\to" to "→",
        "\\rightarrow" to "→",
        "\\leftarrow" to "←"
    )
    
    for ((latex, unicode) in basicSymbols) {
        result = result.replace(latex, unicode)
    }
    
    // 🎯 处理括号 - 修复转义问题！
    result = result.replace("\\left\\(", "(")
                   .replace("\\right\\)", ")")
                   .replace("\\left\\[", "[")
                   .replace("\\right\\]", "]")
                   .replace("\\left\\{", "{")
                   .replace("\\right\\}", "}")
                   .replace("\\left\\|", "|")
                   .replace("\\right\\|", "|")
                   .replace("\\left", "")  // 清除剩余的\left
                   .replace("\\right", "") // 清除剩余的\right
    
    // 🎯 处理希腊字母 - 修复转义问题！
    val greekLetters = mapOf(
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
        "\\epsilon" to "ε", "\\zeta" to "ζ", "\\eta" to "η", "\\theta" to "θ",
        "\\iota" to "ι", "\\kappa" to "κ", "\\lambda" to "λ", "\\mu" to "μ",
        "\\nu" to "ν", "\\xi" to "ξ", "\\rho" to "ρ",
        "\\sigma" to "σ", "\\tau" to "τ", "\\upsilon" to "υ", "\\phi" to "φ",
        "\\chi" to "χ", "\\psi" to "ψ", "\\omega" to "ω",
        "\\Gamma" to "Γ", "\\Delta" to "Δ", "\\Theta" to "Θ", "\\Lambda" to "Λ",
        "\\Xi" to "Ξ", "\\Pi" to "Π", "\\Sigma" to "Σ", "\\Upsilon" to "Υ",
        "\\Phi" to "Φ", "\\Psi" to "Ψ", "\\Omega" to "Ω"
    )
    
    for ((latex, unicode) in greekLetters) {
        result = result.replace(latex, unicode)
    }
    
    // 🎯 处理三角函数和对数 - 移除反斜杠
    val functionPattern = Regex("\\\\(sin|cos|tan|cot|sec|csc|sinh|cosh|tanh|arcsin|arccos|arctan|ln|log|exp)\\b")
    result = functionPattern.replace(result) { matchResult ->
        matchResult.groupValues[1] // 去掉反斜杠
    }
    
    // 🎯 处理求和符号 \sum_{下标}^{上标}
    result = result.replace(Regex("""\\sum_\{([^}]+)\}\^\{([^}]+)\}""")) { matchResult ->
        val lower = matchResult.groupValues[1]
        val upper = matchResult.groupValues[2]
        "Σ($lower to $upper)"
    }
    
    // 🎯 处理求和符号简化版 \sum_{下标}
    result = result.replace(Regex("""\\sum_\{([^}]+)\}""")) { matchResult ->
        val lower = matchResult.groupValues[1]
        "Σ($lower)"
    }
    
    // 🎯 处理简单的 \sum
    result = result.replace("\\sum", "Σ")
    
    // 🎯 处理积分符号 \int_{下标}^{上标}
    result = result.replace(Regex("""\\int_\{([^}]+)\}\^\{([^}]+)\}""")) { matchResult ->
        val lower = matchResult.groupValues[1]
        val upper = matchResult.groupValues[2]
        "∫[$lower to $upper]"
    }
    
    // 🎯 处理简单的 \int
    result = result.replace("\\int", "∫")
    
    // 🎯 处理极限 \lim_{变量}
    result = result.replace(Regex("""\\lim_\{([^}]+)\}""")) { matchResult ->
        val variable = matchResult.groupValues[1]
        "lim($variable)"
    }
    
    // 🎯 处理简单的 \lim
    result = result.replace("\\lim", "lim")
    
    return result
}

/**
 * 🎯 创建分数显示效果（复杂分数）- 简化为Unicode分数
 */
private fun createFractionDisplay(numerator: String, denominator: String): String {
    val cleanNumerator = numerator.trim()
    val cleanDenominator = denominator.trim()
    
    // 🎯 常见分数的Unicode符号 - 优先使用
    val commonFractions = mapOf(
        "1/2" to "½", "1/3" to "⅓", "2/3" to "⅔", "1/4" to "¼", "3/4" to "¾",
        "1/5" to "⅕", "2/5" to "⅖", "3/5" to "⅗", "4/5" to "⅘", "1/6" to "⅙",
        "5/6" to "⅚", "1/8" to "⅛", "3/8" to "⅜", "5/8" to "⅝", "7/8" to "⅞",
        "1/7" to "⅐", "1/9" to "⅑", "1/10" to "⅒"
    )
    
    val fractionKey = "$cleanNumerator/$cleanDenominator"
    
    // 如果是常见分数，直接使用Unicode符号
    if (commonFractions.containsKey(fractionKey)) {
        return commonFractions[fractionKey]!!
    }
    
    // 🎯 简化：对于复杂分数，使用Unicode分数斜杠
    return "${cleanNumerator}⁄${cleanDenominator}"
}

/**
 * 🎯 创建简单分数显示效果
 */
private fun createSimpleFractionDisplay(numerator: String, denominator: String): String {
    val cleanNumerator = numerator.trim()
    val cleanDenominator = denominator.trim()
    
    // 🎯 常见分数的Unicode符号
    val commonFractions = mapOf(
        "1/2" to "½", "1/3" to "⅓", "2/3" to "⅔", "1/4" to "¼", "3/4" to "¾",
        "1/5" to "⅕", "2/5" to "⅖", "3/5" to "⅗", "4/5" to "⅘", "1/6" to "⅙",
        "5/6" to "⅚", "1/8" to "⅛", "3/8" to "⅜", "5/8" to "⅝", "7/8" to "⅞",
        "1/7" to "⅐", "1/9" to "⅑", "1/10" to "⅒"
    )
    
    val fractionKey = "$cleanNumerator/$cleanDenominator"
    
    return commonFractions[fractionKey] ?: "${cleanNumerator}⁄${cleanDenominator}"
}