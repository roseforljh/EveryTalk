package com.example.everytalk.util

object LatexToUnicode {
    private val replacements = mapOf(
        "\\triangle" to "△",
        "\\angle" to "∠",
        "\\circ" to "°",
        "\\sim" to "∼",
        "\\sum" to "∑",
        "\\in" to "∈",
        "\\infty" to "∞",
        "\\alpha" to "α",
        "\\beta" to "β",
        "\\gamma" to "γ",
        "\\delta" to "δ",
        "\\epsilon" to "ε",
        "\\zeta" to "ζ",
        "\\eta" to "η",
        "\\theta" to "θ",
        "\\iota" to "ι",
        "\\kappa" to "κ",
        "\\lambda" to "λ",
        "\\mu" to "μ",
        "\\nu" to "ν",
        "\\xi" to "ξ",
        "\\pi" to "π",
        "\\rho" to "ρ",
        "\\sigma" to "σ",
        "\\tau" to "τ",
        "\\upsilon" to "υ",
        "\\phi" to "φ",
        "\\chi" to "χ",
        "\\psi" to "ψ",
        "\\omega" to "ω",
        "\\Gamma" to "Γ",
        "\\Delta" to "Δ",
        "\\Theta" to "Θ",
        "\\Lambda" to "Λ",
        "\\Xi" to "Ξ",
        "\\Pi" to "Π",
        "\\Sigma" to "Σ",
        "\\Upsilon" to "Υ",
        "\\Phi" to "Φ",
        "\\Psi" to "Ψ",
        "\\Omega" to "Ω",
        "\\cdot" to "·",
        "\\times" to "×",
        "\\div" to "÷",
        "\\pm" to "±",
        "\\mp" to "∓",
        "\\leq" to "≤",
        "\\geq" to "≥",
        "\\neq" to "≠",
        "\\approx" to "≈",
        "\\equiv" to "≡",
        "\\forall" to "∀",
        "\\exists" to "∃",
        "\\nabla" to "∇",
        "\\partial" to "∂",
        "\\sqrt" to "√",
        "\\{ " to "{",
        "\\} " to "}",
        "\\cdots" to "⋯",
        "\\ldots" to "…",
        "\\dots" to "…",
        "\\cdot" to "⋅",
        "\\rightarrow" to "→",
        "\\leftarrow" to "←",
        "\\Rightarrow" to "⇒",
        "\\Leftarrow" to "⇐",
        "\\leftrightarrow" to "↔",
        "\\Leftrightarrow" to "⇔",
        "\\sin" to "sin",
        "\\cos" to "cos",
        "\\tan" to "tan",
        "\\log" to "log",
        "\\ln" to "ln",
        "\\ " to " ",
        "\\left" to "",
        "\\right" to "",
        "\\backslash" to "＼",
    )

    private val superscriptMap = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'n' to 'ⁿ'
    )

    private val subscriptMap = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'n' to 'ₙ'
    )

    fun convert(latex: String): String {
        // Final cleanup happens here, once, at the end.
        return _convert(latex)
            .replace("{", "")
            .replace("}", "")
    }

    private fun _convert(latex: String): String {
        var result = latex
        replacements.forEach { (key, value) ->
            result = result.replace(key, value)
        }

        // Handle \text{...}
        val textRegex = Regex("\\\\text\\{([^}]+)\\}")
        result = result.replace(textRegex) { matchResult ->
            matchResult.groupValues[1]
        }

        // Handle superscripts ^{...}
        val supRegex = Regex("\\^\\{([^}]+)\\}")
        result = result.replace(supRegex) { matchResult ->
            val content = _convert(matchResult.groupValues[1])
            content.map { superscriptMap[it] ?: it }.joinToString("")
        }

        // Handle subscripts _{...}
        val subRegex = Regex("_\\{([^}]+)\\}")
        result = result.replace(subRegex) { matchResult ->
            val content = _convert(matchResult.groupValues[1])
            content.map { subscriptMap[it] ?: it }.joinToString("")
        }

        // Handle single char superscript ^a
        val singleSupRegex = Regex("\\^((?!\\{)\\S)")
        result = result.replace(singleSupRegex) { matchResult ->
            val char = matchResult.groupValues[1].first()
            (superscriptMap[char] ?: char).toString()
        }

        // Handle single char subscript _a
        val singleSubRegex = Regex("_((?!\\{)\\S)")
        result = result.replace(singleSubRegex) { matchResult ->
            val char = matchResult.groupValues[1].first()
            (subscriptMap[char] ?: char).toString()
        }

        return result
    }
}