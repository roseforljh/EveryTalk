package com.example.everytalk.util

object LatexToUnicode {
    private val replacements = mapOf(
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

    private val superscriptMap = mapOf(
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

    private val subscriptMap = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', 
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ', 
        'k' to 'ₖ', 'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ',
        'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ', 
        'v' to 'ᵥ', 'x' to 'ₓ',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎'
    )

    // 预编译正则表达式提高性能
    private val matrixRegex = Regex("\\\\begin\\{([vp]?matrix|array)\\}([\\s\\S]*?)\\\\end\\{\\1\\}")
    private val fracRegex = Regex("\\\\frac\\{([^{}]*(?:\\{[^{}]*\\}[^{}]*)*)\\}\\{([^{}]*(?:\\{[^{}]*\\}[^{}]*)*)\\}")
    private val sqrtRegex = Regex("\\\\sqrt(?:\\[([^]]+)\\])?\\{([^{}]*(?:\\{[^{}]*\\}[^{}]*)*)\\}")
    private val textRegex = Regex("\\\\text\\{([^}]+)\\}")
    private val supRegex = Regex("\\^\\{([^}]+)\\}")
    private val subRegex = Regex("_\\{([^}]+)\\}")
    private val singleSupRegex = Regex("\\^((?!\\{)[^\\s_^])")
    private val singleSubRegex = Regex("_((?!\\{)[^\\s_^])")

    fun convert(latex: String): String {
        if (latex.isBlank()) return latex
        return processLaTeX(latex.trim())
    }

    private fun processLaTeX(latex: String): String {
        var result = latex

        // 处理矩阵（支持多种类型）
        result = matrixRegex.replace(result) { matchResult ->
            val matrixType = matchResult.groupValues[1]
            val matrixContent = matchResult.groupValues[2]
                .replace("&", "  ")
                .replace("\\\\", "\n")
                .trim()
            
            when (matrixType) {
                "vmatrix" -> "|$matrixContent|"
                "pmatrix" -> "($matrixContent)"
                "matrix", "array" -> matrixContent
                else -> matrixContent
            }
        }

        // 处理根号
        result = sqrtRegex.replace(result) { matchResult ->
            val index = matchResult.groupValues[1]
            val content = processLaTeX(matchResult.groupValues[2])
            if (index.isNotEmpty()) {
                "$index√($content)"
            } else {
                "√($content)"
            }
        }

        // 处理分数
        result = fracRegex.replace(result) { matchResult ->
            val numerator = processLaTeX(matchResult.groupValues[1])
            val denominator = processLaTeX(matchResult.groupValues[2])
            "($numerator)/($denominator)"
        }

        // 处理文本
        result = textRegex.replace(result) { it.groupValues[1] }

        // 按最长匹配原则替换LaTeX命令
        val sortedReplacements = replacements.toList().sortedByDescending { it.first.length }
        for ((latex, unicode) in sortedReplacements) {
            result = result.replace(latex, unicode)
        }

        // 处理上标
        result = supRegex.replace(result) { matchResult ->
            val content = processLaTeX(matchResult.groupValues[1])
            convertToSuperscript(content)
        }

        // 处理下标
        result = subRegex.replace(result) { matchResult ->
            val content = processLaTeX(matchResult.groupValues[1])
            convertToSubscript(content)
        }

        // 处理单字符上标
        result = singleSupRegex.replace(result) { matchResult ->
            val char = matchResult.groupValues[1].first()
            (superscriptMap[char] ?: char).toString()
        }

        // 处理单字符下标
        result = singleSubRegex.replace(result) { matchResult ->
            val char = matchResult.groupValues[1].first()
            (subscriptMap[char] ?: char).toString()
        }

        // 清理多余的符号
        result = result
            .replace(Regex("\\{\\}"), "")
            .replace(Regex("\\$+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        return result
    }

    private fun convertToSuperscript(text: String): String {
        return text.map { superscriptMap[it] ?: it }.joinToString("")
    }

    private fun convertToSubscript(text: String): String {
        return text.map { subscriptMap[it] ?: it }.joinToString("")
    }
}