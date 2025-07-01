package com.example.everytalk.util

import java.util.concurrent.ConcurrentHashMap

object LatexToUnicode {
    private val conversionCache = ConcurrentHashMap<String, String>()
    private const val MAX_RECURSION_DEPTH = 10
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
                "($numerator)/($denominator)"
            }
            replaceWithRegex(result, textRegex) { it.groupValues[1] }
            replaceWithRegex(result, supRegex) { convertToSuperscript(processLaTeX(it.groupValues[1])) }
            replaceWithRegex(result, subRegex) { convertToSubscript(processLaTeX(it.groupValues[1])) }
            replaceWithRegex(result, singleSupRegex) { (superscriptMap[it.groupValues[1].first()] ?: it.groupValues[1]).toString() }
            replaceWithRegex(result, singleSubRegex) { (subscriptMap[it.groupValues[1].first()] ?: it.groupValues[1]).toString() }

            // Batch replace simple commands
            replacements.forEach { (key, value) ->
                var index = result.indexOf(key)
                while (index != -1) {
                    result.replace(index, index + key.length, value)
                    index = result.indexOf(key, index + value.length)
                }
            }

            // Final cleanup
            return result.toString()
                .replace(Regex("\\{\\}"), "")
                .replace(Regex("\\$+"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
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
}