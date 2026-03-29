package com.android.everytalk.ui.components.markdown

import android.content.Context
import android.util.Log
import org.scilab.forge.jlatexmath.TeXConstants
import org.scilab.forge.jlatexmath.TeXFormula
import org.scilab.forge.jlatexmath.TeXIcon
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.awt.Color as JColor

internal object NativeLatexSupport {

    private const val TAG = "NativeLatexSupport"

    @Volatile
    private var initialized = false

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                JLatexMathAndroid.init(context.applicationContext)
                initialized = true
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to initialize native latex support", error)
                throw error
            }
        }
    }

    fun extractPureMathContent(latex: String): String {
        val trimmed = latex.trim()
        return when {
            trimmed.startsWith("$$") && trimmed.endsWith("$$") ->
                trimmed.removePrefix("$$").removeSuffix("$$").trim()
            trimmed.startsWith("\\[") && trimmed.endsWith("\\]") ->
                trimmed.removePrefix("\\[").removeSuffix("\\]").trim()
            else -> trimmed
        }
    }

    fun normalizeForNativeBlockRenderer(latex: String): String {
        var s = extractPureMathContent(latex)

        // pmatrix / bmatrix 原生可被 JLatexMath 正确渲染。
        // 之前的 rewrite 会把合法矩阵改坏，并可能产出 0 像素 icon，
        // 这里保留原始环境，仅继续处理 vmatrix / Vmatrix。
        s = rewriteMatrixEnvironment(s, "vmatrix", "\\left|", "\\right|")
        s = rewriteMatrixEnvironment(s, "Vmatrix", "\\left\\|", "\\right\\|")

        return s
    }

    fun canRenderNatively(latex: String, textSizePx: Float, colorArgb: Int): Boolean {
        return runCatching {
            buildDisplayIcon(latex, textSizePx, colorArgb)
        }.isSuccess
    }

    fun buildDisplayIcon(latex: String, textSizePx: Float, colorArgb: Int): TeXIcon {
        val normalized = normalizeForNativeBlockRenderer(latex)
        return TeXFormula(normalized)
            .TeXIconBuilder()
            .setStyle(TeXConstants.STYLE_DISPLAY)
            .setSize(textSizePx)
            .setFGColor(JColor(colorArgb))
            .build()
    }

    fun shouldFallbackToMarkdownBlockRenderer(latex: String): Boolean {
        val normalized = normalizeForNativeBlockRenderer(latex)
        val beginToken = "\\begin{array}{"
        val startIndex = normalized.indexOf(beginToken)
        val columnSpec = if (startIndex >= 0) {
            val specStart = startIndex + beginToken.length
            val specEnd = normalized.indexOf('}', specStart)
            if (specEnd > specStart) normalized.substring(specStart, specEnd) else ""
        } else {
            ""
        }
        val columnCount = columnSpec.count { it == 'c' || it == 'l' || it == 'r' }
        val hasPartialDerivative = normalized.contains("\\partial") || normalized.contains("\\nabla")
        return columnCount >= 3 && hasPartialDerivative
    }

    private fun rewriteMatrixEnvironment(
        input: String,
        envName: String,
        leftDelimiter: String,
        rightDelimiter: String
    ): String {
        val beginToken = "\\begin{$envName}"
        val endToken = "\\end{$envName}"
        if (!input.contains(beginToken) || !input.contains(endToken)) return input

        val startIndex = input.indexOf(beginToken)
        val endIndex = input.indexOf(endToken, startIndex + beginToken.length)
        if (startIndex < 0 || endIndex < 0 || endIndex <= startIndex) return input

        val prefix = input.substring(0, startIndex).trim()
        val body = input.substring(startIndex + beginToken.length, endIndex).trim()
        val suffix = input.substring(endIndex + endToken.length).trim()

        val rows = body.split("\\\\").map { it.trim() }.filter { it.isNotEmpty() }
        val maxColumns = rows.maxOfOrNull { row -> row.split("&").size } ?: 0
        if (maxColumns <= 0) return input

        val alignment = buildString(maxColumns) { repeat(maxColumns) { append('c') } }
        val matrixCore = "$leftDelimiter \\begin{array}{$alignment} ${rows.joinToString(" \\\\ ")} \\end{array} $rightDelimiter"
        return listOf(prefix, matrixCore, suffix)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }
}
