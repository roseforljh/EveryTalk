package com.android.everytalk.ui.components.table

internal object MathRenderStrategy {

    fun shouldEnableHorizontalScrollForMathPart(math: String): Boolean {
        val trimmed = math.trim()
        if (trimmed.isEmpty()) return false

        val isBlockMath = (trimmed.startsWith("$$") && trimmed.endsWith("$$")) ||
            (trimmed.startsWith("\\[") && trimmed.endsWith("\\]"))
        if (!isBlockMath) return false

        val normalized = trimmed
            .removePrefix("$$")
            .removeSuffix("$$")
            .removePrefix("\\[")
            .removeSuffix("\\]")
            .trim()

        val longestLineLength = normalized.lines().maxOfOrNull { it.length } ?: 0
        val hasStructuralEnvironment = normalized.contains("\\begin{") ||
            normalized.contains("\\end{") ||
            normalized.contains("\\matrix") ||
            normalized.contains("\\pmatrix") ||
            normalized.contains("\\bmatrix") ||
            normalized.contains("\\vmatrix") ||
            normalized.contains("\\Vmatrix") ||
            normalized.contains("\\cases") ||
            normalized.contains("\\aligned") ||
            normalized.contains("\\array")

        val hasLargeOperators = normalized.contains("\\sum") ||
            normalized.contains("\\int") ||
            normalized.contains("\\prod") ||
            normalized.contains("\\lim") ||
            normalized.contains("\\oint")

        val hasDeepFractions = normalized.contains("\\frac") ||
            normalized.contains("\\dfrac") ||
            normalized.contains("\\tfrac") ||
            normalized.contains("\\sqrt") ||
            normalized.contains("\\left") ||
            normalized.contains("\\right")

        val hasPartialDerivative = normalized.contains("\\partial") ||
            normalized.contains("\\nabla")

        if (hasStructuralEnvironment || hasPartialDerivative) {
            return false
        }

        val isVeryLongSingleLine = longestLineLength >= 96
        val isLongOperatorFormula = longestLineLength >= 72 && (hasLargeOperators || hasDeepFractions)

        return isVeryLongSingleLine || isLongOperatorFormula
    }

    fun shouldForceNativeBlockRendererForMathPart(math: String): Boolean {
        val trimmed = math.trim()
        if (trimmed.isEmpty()) return false

        return trimmed.contains("\\begin{pmatrix}") ||
            trimmed.contains("\\begin{bmatrix}") ||
            trimmed.contains("\\begin{vmatrix}") ||
            trimmed.contains("\\begin{Vmatrix}") ||
            trimmed.contains("\\begin{matrix}") ||
            trimmed.contains("\\end{pmatrix}") ||
            trimmed.contains("\\end{bmatrix}") ||
            trimmed.contains("\\end{vmatrix}") ||
            trimmed.contains("\\end{Vmatrix}") ||
            trimmed.contains("\\end{matrix}")
    }

    fun shouldForceMarkdownRendererForMathPart(math: String): Boolean {
        val trimmed = math.trim()
        if (trimmed.isEmpty()) return false

        if (shouldForceNativeBlockRendererForMathPart(trimmed)) {
            return false
        }

        val normalized = trimmed
            .removePrefix("$$")
            .removeSuffix("$$")
            .removePrefix("\\[")
            .removeSuffix("\\]")
            .trim()

        return normalized.contains("\\begin{") ||
            normalized.contains("\\end{") ||
            normalized.contains("\\matrix") ||
            normalized.contains("\\pmatrix") ||
            normalized.contains("\\bmatrix") ||
            normalized.contains("\\vmatrix") ||
            normalized.contains("\\Vmatrix") ||
            normalized.contains("\\cases") ||
            normalized.contains("\\aligned") ||
            normalized.contains("\\array")
    }

    fun shouldPreferStableNativeMathRenderer(math: String): Boolean {
        val trimmed = math.trim()
        if (trimmed.isEmpty()) return true

        if (shouldForceNativeBlockRendererForMathPart(trimmed)) {
            return false
        }

        val normalized = trimmed
            .removePrefix("$$")
            .removeSuffix("$$")
            .removePrefix("\\[")
            .removeSuffix("\\]")
            .trim()

        if (shouldForceMarkdownRendererForMathPart(trimmed)) {
            return false
        }

        return normalized.contains("\\partial") ||
            normalized.contains("\\nabla") ||
            normalized.contains("\\hbar") ||
            normalized.contains("\\Gamma") ||
            normalized.contains("\\Xi")
    }
}
