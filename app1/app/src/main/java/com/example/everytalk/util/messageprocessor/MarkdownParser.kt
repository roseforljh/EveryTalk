package com.example.everytalk.util.messageprocessor

import com.example.everytalk.ui.components.MarkdownPart
import java.util.UUID

/**
 * 🚀 增强的Markdown解析器 - 支持专业数学公式渲染
 */
internal fun parseMarkdownParts(markdown: String, inTableContext: Boolean = false): List<MarkdownPart> {
    android.util.Log.d("MarkdownParser", "=== Enhanced parseMarkdownParts START ===")
    android.util.Log.d("MarkdownParser", "Input markdown length: ${markdown.length}")
    android.util.Log.d("MarkdownParser", "Input preview: ${markdown.take(200)}...")
    
    if (markdown.isBlank()) {
        android.util.Log.d("MarkdownParser", "Markdown为空，返回空文本part")
        return listOf(MarkdownPart.Text(id = "text_${UUID.randomUUID()}", content = ""))
    }

    // 🎯 首先进行数学内容智能预处理
    val preprocessed = preprocessMarkdownForMath(markdown)
    android.util.Log.d("MarkdownParser", "Math preprocessed preview: ${preprocessed.take(200)}...")
    
    // 🎯 智能内容分类 - 检测是否包含复杂数学内容
    val contentType = detectContentType(preprocessed)
    android.util.Log.d("MarkdownParser", "Detected content type: $contentType")
    
    return when (contentType) {
        ContentType.MATH_HEAVY -> parseMathHeavyContent(preprocessed)
        ContentType.MIXED_MATH -> parseMixedMathContent(preprocessed, inTableContext)
        ContentType.SIMPLE_TEXT -> parseSimpleMarkdown(preprocessed, inTableContext)
        ContentType.TABLE -> parseTableContent(preprocessed)
    }
}

/**
 * 内容类型枚举
 */
private enum class ContentType {
    MATH_HEAVY,     // 数学公式为主，使用专业渲染器
    MIXED_MATH,     // 包含数学公式的混合内容
    SIMPLE_TEXT,    // 简单文本，使用原生渲染
    TABLE          // 表格内容
}

/**
 * 🎯 数学内容智能预处理 - 彻底清理LaTeX语法
 */
private fun preprocessMarkdownForMath(markdown: String): String {
    var content = markdown
    
    // 1. 彻底清理LaTeX语法，转换为Unicode
    // 处理分数 \frac{a}{b} -> (a)/(b)
    content = content.replace(Regex("\\\\frac\\{([^}]+)\\}\\{([^}]+)\\}"), "($1)/($2)")
    
    // 处理根号 \sqrt{x} -> √(x)
    content = content.replace(Regex("\\\\sqrt\\{([^}]+)\\}"), "√($1)")
    content = content.replace(Regex("\\\\sqrt\\s+(\\d+\\.?\\d*)"), "√$1")
    
    // 处理上标 ^{n} -> ⁿ
    val superscriptMap = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'n' to 'ⁿ', 'i' to 'ⁱ', 'x' to 'ˣ', 'y' to 'ʸ'
    )
    
    content = content.replace(Regex("\\^(\\d)")) { match ->
        superscriptMap[match.groupValues[1][0]]?.toString() ?: match.value
    }
    
    content = content.replace(Regex("\\^\\{([^}]+)\\}")) { match ->
        match.groupValues[1].map { char -> superscriptMap[char]?.toString() ?: char.toString() }.joinToString("")
    }
    
    // 处理下标 _{n} -> ₙ  
    val subscriptMap = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ',
        'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ',
        'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ',
        'v' to 'ᵥ', 'x' to 'ₓ'
    )
    
    content = content.replace(Regex("_(\\d)")) { match ->
        subscriptMap[match.groupValues[1][0]]?.toString() ?: match.value
    }
    
    content = content.replace(Regex("_\\{([^}]+)\\}")) { match ->
        match.groupValues[1].map { char -> subscriptMap[char]?.toString() ?: char.toString() }.joinToString("")
    }
    
    // 2. 处理常见数学运算符
    content = content.replace("\\pm", "±")
    content = content.replace("\\mp", "∓")  
    content = content.replace("\\times", "×")
    content = content.replace("\\div", "÷")
    content = content.replace("\\cdot", "·")
    
    // 3. 处理比较运算符
    content = content.replace("\\leq", "≤")
    content = content.replace("\\geq", "≥")
    content = content.replace("\\neq", "≠")
    content = content.replace("\\approx", "≈")
    content = content.replace("\\equiv", "≡")
    
    // 4. 处理希腊字母
    val greekLetters = mapOf(
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
        "\\epsilon" to "ε", "\\zeta" to "ζ", "\\eta" to "η", "\\theta" to "θ",
        "\\iota" to "ι", "\\kappa" to "κ", "\\lambda" to "λ", "\\mu" to "μ",
        "\\nu" to "ν", "\\xi" to "ξ", "\\pi" to "π", "\\rho" to "ρ",
        "\\sigma" to "σ", "\\tau" to "τ", "\\upsilon" to "υ", "\\phi" to "φ",
        "\\chi" to "χ", "\\psi" to "ψ", "\\omega" to "ω",
        "\\Alpha" to "Α", "\\Beta" to "Β", "\\Gamma" to "Γ", "\\Delta" to "Δ",
        "\\Epsilon" to "Ε", "\\Zeta" to "Ζ", "\\Eta" to "Η", "\\Theta" to "Θ",
        "\\Iota" to "Ι", "\\Kappa" to "Κ", "\\Lambda" to "Λ", "\\Mu" to "Μ",
        "\\Nu" to "Ν", "\\Xi" to "Ξ", "\\Pi" to "Π", "\\Rho" to "Ρ",
        "\\Sigma" to "Σ", "\\Tau" to "Τ", "\\Upsilon" to "Υ", "\\Phi" to "Φ",
        "\\Chi" to "Χ", "\\Psi" to "Ψ", "\\Omega" to "Ω"
    )
    
    greekLetters.forEach { (latex, unicode) ->
        content = content.replace(latex, unicode)
    }
    
    // 5. 处理特殊符号
    content = content.replace("\\partial", "∂")
    content = content.replace("\\nabla", "∇")
    content = content.replace("\\sum", "∑")
    content = content.replace("\\prod", "∏")
    content = content.replace("\\int", "∫")
    content = content.replace("\\oint", "∮")
    content = content.replace("\\infty", "∞")
    content = content.replace("\\forall", "∀")
    content = content.replace("\\exists", "∃")
    content = content.replace("\\in", "∈")
    content = content.replace("\\notin", "∉")
    content = content.replace("\\subset", "⊂")
    content = content.replace("\\supset", "⊃")
    content = content.replace("\\cup", "∪")
    content = content.replace("\\cap", "∩")
    content = content.replace("\\emptyset", "∅")
    
    // 6. 处理省略号
    content = content.replace("\\ldots", "…")
    content = content.replace("\\cdots", "⋯")
    content = content.replace("\\vdots", "⋮")
    content = content.replace("\\ddots", "⋱")
    
    // 7. 清理所有剩余的LaTeX语法
    content = content.replace(Regex("\\\\[a-zA-Z]+\\{[^}]*\\}"), "") // 清理 \command{content}
    content = content.replace(Regex("\\\\[a-zA-Z]+"), "") // 清理 \command
    content = content.replace(Regex("\\$+"), "") // 清理 $ 符号
    
    // 8. 清理多余空格
    content = content.replace(Regex("\\s+"), " ").trim()
    
    android.util.Log.d("MarkdownParser", "LaTeX清理前: ${markdown.take(100)}...")
    android.util.Log.d("MarkdownParser", "LaTeX清理后: ${content.take(100)}...")
    
    return content
}

/**
 * 🎯 智能内容类型检测
 */
private fun detectContentType(content: String): ContentType {
    val mathSymbols = listOf("∫", "∑", "√", "π", "α", "β", "γ", "δ", "Δ", "σ", "μ", "λ")
    val hasMathSymbols = mathSymbols.any { content.contains(it) }
    val hasTable = content.contains("|") && content.contains("---")
    val hasComplexMath = content.contains("²") || content.contains("³") || content.contains("½")
    
    return when {
        hasTable && !hasMathSymbols -> ContentType.TABLE
        hasMathSymbols || hasComplexMath -> ContentType.MATH_HEAVY
        else -> ContentType.SIMPLE_TEXT
    }
}

/**
 * 🎯 解析数学密集型内容 - 使用专业渲染器
 */
private fun parseMathHeavyContent(content: String): List<MarkdownPart> {
    android.util.Log.d("MarkdownParser", "Parsing math-heavy content with professional renderer")
    
    return listOf(
        MarkdownPart.MathBlock(
            id = "math_${UUID.randomUUID()}",
            content = content,
            renderMode = "professional"
        )
    )
}

/**
 * 🎯 解析混合数学内容
 */
private fun parseMixedMathContent(content: String, inTableContext: Boolean): List<MarkdownPart> {
    android.util.Log.d("MarkdownParser", "Parsing mixed math content")
    
    // 简化处理：直接返回文本部分
    return listOf(
        MarkdownPart.Text(
            id = "text_${UUID.randomUUID()}",
            content = content
        )
    )
}

/**
 * 🎯 解析表格内容
 */
private fun parseTableContent(content: String): List<MarkdownPart> {
    android.util.Log.d("MarkdownParser", "Parsing table content")
    
    return listOf(
        MarkdownPart.Table(
            id = "table_${UUID.randomUUID()}",
            content = content,
            renderMode = "webview"
        )
    )
}

/**
 * 🎯 解析简单文本内容 - 使用原生渲染器
 */
private fun parseSimpleMarkdown(content: String, inTableContext: Boolean): List<MarkdownPart> {
    android.util.Log.d("MarkdownParser", "Parsing simple markdown with native renderer")
    
    // 简化处理：直接返回文本部分
    return listOf(
        MarkdownPart.Text(
            id = "text_${UUID.randomUUID()}",
            content = content
        )
    )
}

/**
 * 预处理Markdown以兼容Android前端
 */
private fun preprocessMarkdownForAndroid(markdown: String): String {
    if (markdown.isEmpty()) return markdown
    
    return markdown
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .trim()
}
