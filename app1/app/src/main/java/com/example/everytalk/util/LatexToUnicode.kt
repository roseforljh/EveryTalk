package com.example.everytalk.util

import java.util.*
import java.util.concurrent.ConcurrentHashMap

object LatexToUnicode {
    // LRU Cache with size limit to prevent memory leaks
    private val conversionCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }.let { Collections.synchronizedMap(it) }
    
    private const val MAX_RECURSION_DEPTH = 10
    private const val MAX_CACHE_SIZE = 1000
    private val recursionDepth = ThreadLocal.withInitial { 0 }

    private val replacements by lazy {
        mapOf(
            // 几何符号
            "\\triangle" to "△",
            "\\angle" to "∠",
            "\\circ" to "°",
            "\\sim" to "∼",
            
            // 数学运算
            "\\sum" to "∑",
            "\\prod" to "∏",
            "\\int" to "∫",
            "\\oint" to "∮",
            
            // 集合和逻辑
            "\\in" to "∈",
            "\\notin" to "∉",
            "\\subset" to "⊂",
            "\\supset" to "⊃",
            "\\cup" to "∪",
            "\\cap" to "∩",
            "\\emptyset" to "∅",
            "\\infty" to "∞",
            "\\forall" to "∀",
            "\\exists" to "∃",
            "\\nexists" to "∄",
            
            // 希腊字母（小写）
            "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
            "\\epsilon" to "ε", "\\varepsilon" to "ε", "\\zeta" to "ζ", "\\eta" to "η",
            "\\theta" to "θ", "\\vartheta" to "ϑ", "\\iota" to "ι", "\\kappa" to "κ",
            "\\lambda" to "λ", "\\mu" to "μ", "\\nu" to "ν", "\\xi" to "ξ",
            "\\pi" to "π", "\\varpi" to "ϖ", "\\rho" to "ρ", "\\varrho" to "ϱ",
            "\\sigma" to "σ", "\\varsigma" to "ς", "\\tau" to "τ", "\\upsilon" to "υ",
            "\\phi" to "φ", "\\varphi" to "φ", "\\chi" to "χ", "\\psi" to "ψ", "\\omega" to "ω",
            
            // 希腊字母（大写）
            "\\Gamma" to "Γ", "\\Delta" to "Δ", "\\Theta" to "Θ", "\\Lambda" to "Λ",
            "\\Xi" to "Ξ", "\\Pi" to "Π", "\\Sigma" to "Σ", "\\Upsilon" to "Υ",
            "\\Phi" to "Φ", "\\Psi" to "Ψ", "\\Omega" to "Ω",
            
            // 运算符
            "\\cdot" to "·", "\\times" to "×", "\\div" to "÷", "\\pm" to "±", "\\mp" to "∓",
            "\\ast" to "∗", "\\star" to "⋆", "\\circ" to "∘", "\\bullet" to "•",
            
            // 关系符
            "\\leq" to "≤", "\\le" to "≤", "\\geq" to "≥", "\\ge" to "≥",
            "\\neq" to "≠", "\\ne" to "≠", "\\approx" to "≈", "\\equiv" to "≡",
            "\\cong" to "≅", "\\simeq" to "≃", "\\propto" to "∝",
            "\\ll" to "≪", "\\gg" to "≫",
            
            // 箭头
            "\\rightarrow" to "→", "\\to" to "→", "\\leftarrow" to "←",
            "\\Rightarrow" to "⇒", "\\Leftarrow" to "⇐",
            "\\leftrightarrow" to "↔", "\\Leftrightarrow" to "⇔",
            "\\uparrow" to "↑", "\\downarrow" to "↓",
            "\\nearrow" to "↗", "\\searrow" to "↘",
            "\\nwarrow" to "↖", "\\swarrow" to "↙",
            
            // 向量箭头和修饰符
            "\\overrightarrow" to "→", "\\overleftarrow" to "←",
            "\\overline" to "‾", "\\underline" to "_",
            "\\vec" to "→", "\\hat" to "^",
            
            // 绝对值和范数
            "\\|" to "‖", "|" to "|",
            
            // 微积分
            "\\nabla" to "∇", "\\partial" to "∂",
            
            // 省略号
            "\\cdots" to "⋯", "\\ldots" to "…", "\\dots" to "…", "\\vdots" to "⋮", "\\ddots" to "⋱",
            
            // 函数
            "\\sin" to "sin", "\\cos" to "cos", "\\tan" to "tan",
            "\\sec" to "sec", "\\csc" to "csc", "\\cot" to "cot",
            "\\arcsin" to "arcsin", "\\arccos" to "arccos", "\\arctan" to "arctan",
            "\\sinh" to "sinh", "\\cosh" to "cosh", "\\tanh" to "tanh",
            "\\log" to "log", "\\ln" to "ln", "\\lg" to "lg",
            "\\exp" to "exp", "\\max" to "max", "\\min" to "min",
            "\\sup" to "sup", "\\inf" to "inf", "\\lim" to "lim",
            
            // 空格和格式
            "\\ " to " ", "\\quad" to "    ", "\\qquad" to "        ",
            "\\," to " ", "\\:" to "  ", "\\;" to "   ",
            
            // 分隔符处理
            "\\left" to "", "\\right" to "", "\\big" to "", "\\Big" to "",
            "\\bigg" to "", "\\Bigg" to "",
            
            // 特殊符号
            "\\backslash" to "\\", "\\|" to "‖",
            "\\mathbfi" to "𝐢", "\\mathbfj" to "𝐣", "\\mathbfk" to "𝐤",
            "\\hbar" to "ℏ", "\\ell" to "ℓ",
            
            // 清理标记
            "\\mathrm" to "", "\\mathbf" to "", "\\mathit" to "",
            "\\mathcal" to "", "\\mathfrak" to "", "\\mathbb" to "",
            "\\textrm" to "", "\\textbf" to "", "\\textit" to "",
            "\\boxed" to ""
        )
    }

    private val superscriptMap by lazy {
        mapOf(
            '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
            '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
            'a' to 'ᵃ', 'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ',
            'f' to 'ᶠ', 'g' to 'ᵍ', 'h' to 'ʰ', 'i' to 'ⁱ', 'j' to 'ʲ',
            'k' to 'ᵏ', 'l' to 'ˡ', 'm' to 'ᵐ', 'n' to 'ⁿ', 'o' to 'ᵒ',
            'p' to 'ᵖ', 'r' to 'ʳ', 's' to 'ˢ', 't' to 'ᵗ', 'u' to 'ᵘ',
            'v' to 'ᵛ', 'w' to 'ʷ', 'x' to 'ˣ', 'y' to 'ʸ', 'z' to 'ᶻ',
            'A' to 'ᴬ', 'B' to 'ᴮ', 'D' to 'ᴰ', 'E' to 'ᴱ', 'G' to 'ᴳ',
            'H' to 'ᴴ', 'I' to 'ᴵ', 'J' to 'ᴶ', 'K' to 'ᴷ', 'L' to 'ᴸ',
            'M' to 'ᴹ', 'N' to 'ᴺ', 'O' to 'ᴼ', 'P' to 'ᴾ', 'R' to 'ᴿ',
            'T' to 'ᵀ', 'U' to 'ᵁ', 'V' to 'ⱽ', 'W' to 'ᵂ',
            '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾'
        )
    }

    private val subscriptMap by lazy {
        mapOf(
            '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
            '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
            'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ',
            'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ',
            'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ',
            'v' to 'ᵥ', 'x' to 'ₓ',
            '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎'
        )
    }

    private val matrixRegex by lazy { Regex("\\\\begin\\{([vp]?matrix|array)\\}([\\s\\S]*?)\\\\end\\{\\1\\}") }
    private val fracRegex by lazy { Regex("\\\\frac\\{([^{}]*(?:\\{[^{}]*\\}[^{}]*)*)\\}\\{([^{}]*(?:\\{[^{}]*\\}[^{}]*)*)\\}") }
    private val sqrtRegex by lazy { Regex("\\\\sqrt(?:\\[([^]]+)\\])?\\{([^{}]*(?:\\{[^{}]*\\}[^{}]*)*)\\}") }
    private val textRegex by lazy { Regex("\\\\text\\{([^}]+)\\}") }
    private val supRegex by lazy { Regex("\\^\\{([^}]+)\\}") }
    private val subRegex by lazy { Regex("_\\{([^}]+)\\}") }
    private val singleSupRegex by lazy { Regex("\\^((?!\\{)[^\\s_^])") }
    private val singleSubRegex by lazy { Regex("_((?!\\{)[^\\s_^])") }
    // 向量和修饰符正则
    private val vectorRegex by lazy { Regex("\\\\overrightarrow\\{([^}]+)\\}") }
    private val overlineRegex by lazy { Regex("\\\\overline\\{([^}]+)\\}") }

    fun convert(latex: String): String {
        if (latex.isBlank()) return latex
        return PerformanceMonitor.measure("LatexToUnicode.convert") {
            conversionCache.getOrPut(latex) {
                try {
                    recursionDepth.set(0)
                    processLaTeX(latex.trim())
                } finally {
                    recursionDepth.remove()
                }
            }
        }
    }

    private fun processLaTeX(latex: String): String {
        val currentDepth = recursionDepth.get()
        if (currentDepth >= MAX_RECURSION_DEPTH) {
            return latex // Stop recursion
        }
        recursionDepth.set(currentDepth + 1)

        try {
            val result = StringBuilder(latex)

            // Process complex structures first
            replaceWithRegex(result, matrixRegex) { matchResult ->
                val matrixType = matchResult.groupValues[1]
                val matrixContent = matchResult.groupValues[2]
                    .replace("&", "  ")
                    .replace("\\\\", "\n")
                    .trim()
                when (matrixType) {
                    "vmatrix" -> "|$matrixContent|"
                    "pmatrix" -> "($matrixContent)"
                    else -> matrixContent
                }
            }
            replaceWithRegex(result, sqrtRegex) { matchResult ->
                val index = matchResult.groupValues[1]
                val content = processLaTeX(matchResult.groupValues[2])
                if (index.isNotEmpty()) "$index√($content)" else "√($content)"
            }
            replaceWithRegex(result, fracRegex) { matchResult ->
                val numerator = processLaTeX(matchResult.groupValues[1])
                val denominator = processLaTeX(matchResult.groupValues[2])
                // Create a proper fraction display with superscript numerator and subscript denominator
                createFractionDisplay(numerator, denominator)
            }
            replaceWithRegex(result, textRegex) { it.groupValues[1] }
            replaceWithRegex(result, supRegex) { convertToSuperscript(processLaTeX(it.groupValues[1])) }
            replaceWithRegex(result, subRegex) { convertToSubscript(processLaTeX(it.groupValues[1])) }
            replaceWithRegex(result, singleSupRegex) { (superscriptMap[it.groupValues[1].first()] ?: it.groupValues[1]).toString() }
            replaceWithRegex(result, singleSubRegex) { (subscriptMap[it.groupValues[1].first()] ?: it.groupValues[1]).toString() }
            
            // Process vector arrows and overlines
            replaceWithRegex(result, vectorRegex) { matchResult ->
                val content = processLaTeX(matchResult.groupValues[1])
                "$content→"
            }
            replaceWithRegex(result, overlineRegex) { matchResult ->
                val content = processLaTeX(matchResult.groupValues[1])
                "$content‾"
            }

            // Optimized batch replace using single pass
            performOptimizedReplacements(result)

            return result.toString().trim()
        } finally {
            recursionDepth.set(currentDepth)
        }
    }

    private fun replaceWithRegex(builder: StringBuilder, regex: Regex, transform: (MatchResult) -> String) {
        var match = regex.find(builder)
        while (match != null) {
            val replacement = transform(match)
            builder.replace(match.range.first, match.range.last + 1, replacement)
            match = regex.find(builder, match.range.first + replacement.length)
        }
    }

    private fun convertToSuperscript(text: String): String {
        return text.map { superscriptMap[it] ?: it }.joinToString("")
    }

    private fun convertToSubscript(text: String): String {
        return text.map { subscriptMap[it] ?: it }.joinToString("")
    }
    
    /**
     * Create a proper fraction display using Unicode superscript/subscript
     */
    private fun createFractionDisplay(numerator: String, denominator: String): String {
        // For simple single-digit numbers, use Unicode fraction symbols
        val unicodeFractions = mapOf(
            "1/2" to "½", "1/3" to "⅓", "2/3" to "⅔", "1/4" to "¼", "3/4" to "¾",
            "1/5" to "⅕", "2/5" to "⅖", "3/5" to "⅗", "4/5" to "⅘", "1/6" to "⅙",
            "5/6" to "⅚", "1/7" to "⅐", "1/8" to "⅛", "3/8" to "⅜", "5/8" to "⅝",
            "7/8" to "⅞", "1/9" to "⅑", "1/10" to "⅒"
        )
        
        val fractionKey = "$numerator/$denominator"
        if (unicodeFractions.containsKey(fractionKey)) {
            return unicodeFractions[fractionKey]!!
        }
        
        // For complex fractions, use superscript/subscript format
        val superNumerator = convertToSuperscript(numerator)
        val subDenominator = convertToSubscript(denominator)
        return "$superNumerator⁄$subDenominator"
    }
    
    /**
     * Optimized single-pass replacement for better performance
     */
    private fun performOptimizedReplacements(result: StringBuilder) {
        // Pre-compiled regex patterns for better performance
        val cleanupPatterns = listOf(
            Regex("\\{\\}") to "",
            Regex("\\$+") to "",
            Regex("(sin|cos|tan|sec|csc|cot|log|ln|exp)\\{([a-zA-Z0-9])\\}") to "$1 $2",
            Regex("(sin|cos|tan|sec|csc|cot|log|ln|exp)\\{([a-zA-Z0-9]+)\\}") to "$1($2)",
            Regex("\\s+") to " "
        )
        
        // Apply regex patterns
        cleanupPatterns.forEach { (pattern, replacement) ->
            var match = pattern.find(result)
            while (match != null) {
                result.replace(match.range.first, match.range.last + 1, replacement)
                match = pattern.find(result, match.range.first + replacement.length)
            }
        }
        
        // Process simple fractions before converting division symbols
        val fractionRegex = Regex("([^\\s/÷]+)/([^\\s/÷]+)")
        var match = fractionRegex.find(result)
        while (match != null) {
            val numerator = match.groupValues[1]
            val denominator = match.groupValues[2]
            val fractionDisplay = createFractionDisplay(numerator, denominator)
            result.replace(match.range.first, match.range.last + 1, fractionDisplay)
            match = fractionRegex.find(result, match.range.first + fractionDisplay.length)
        }
        
        // Convert remaining division slashes to division symbols
        var i = 0
        while (i < result.length) {
            when (result[i]) {
                '/' -> {
                    result.setCharAt(i, '÷')
                }
            }
            i++
        }
        
        // Batch replace LaTeX commands using optimized algorithm
        performBatchReplacements(result)
    }
    
    /**
     * Optimized batch replacement using Aho-Corasick-like approach
     */
    private fun performBatchReplacements(result: StringBuilder) {
        // Sort replacements by length (longest first) for better matching
        val sortedReplacements = replacements.entries.sortedByDescending { it.key.length }
        
        var modified = true
        while (modified) {
            modified = false
            for ((key, value) in sortedReplacements) {
                var index = result.indexOf(key)
                while (index != -1) {
                    result.replace(index, index + key.length, value)
                    modified = true
                    index = result.indexOf(key, index + value.length)
                }
            }
        }
    }
}