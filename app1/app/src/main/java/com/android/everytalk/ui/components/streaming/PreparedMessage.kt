package com.android.everytalk.ui.components.streaming

import com.android.everytalk.ui.components.markdown.MarkdownLinkLogoIndex
import com.mikepenz.markdown.model.State
import org.intellij.markdown.ast.ASTNode

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

data class DetailsRequest(
    val id: String,
    val summary: String,
    val markdown: String,
    val contentVersion: Long,
)

data class PreparedMessage(
    val markdown: String,
    val formulas: Map<String, FormulaRequest>,
    val hasPendingFormula: Boolean,
    val contentVersion: Long,
    val details: Map<String, DetailsRequest> = emptyMap(),
)

data class PreparedMarkdownDocument(
    val state: State.Success,
    val nodes: List<ASTNode>,
    val targetNodeIndexByUri: Map<String, Int> = emptyMap(),
    val linkLogoIndex: MarkdownLinkLogoIndex? = null,
)

internal const val INLINE_FORMULA_SCHEME = "everytalk-math-inline:"
internal const val BLOCK_FORMULA_FENCE_LANGUAGE = "everytalk-internal-math-v1"
internal const val DETAILS_FENCE_LANGUAGE = "everytalk-internal-details-v1"
