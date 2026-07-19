package com.android.everytalk.ui.components.streaming

enum class FormulaDisplayMode {
    INLINE,
    BLOCK,
}

data class FormulaRequest(
    val id: String,
    val latex: String,
    val displayMode: FormulaDisplayMode,
    val contentVersion: Long,
)

data class PreparedMessage(
    val markdown: String,
    val formulas: Map<String, FormulaRequest>,
    val hasPendingFormula: Boolean,
    val contentVersion: Long,
)

internal const val INLINE_FORMULA_SCHEME = "everytalk-math-inline:"
internal const val BLOCK_FORMULA_FENCE_LANGUAGE = "everytalk-internal-math-v1"
