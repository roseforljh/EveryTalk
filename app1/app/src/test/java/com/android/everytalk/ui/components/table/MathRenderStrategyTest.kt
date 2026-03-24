package com.android.everytalk.ui.components.table

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MathRenderStrategyTest {

    @Test
    fun `pmatrix block should force markdown renderer`() {
        val input = "$$ \\det \\begin{pmatrix} A & B \\\\ C & D \\end{pmatrix} = \\det(A) \\det(D - CA^{-1}B) $$"

        assertTrue(MathRenderStrategy.shouldForceMarkdownRendererForMathPart(input))
        assertFalse(MathRenderStrategy.shouldEnableHorizontalScrollForMathPart(input))
        assertFalse(MathRenderStrategy.shouldPreferStableNativeMathRenderer(input))
    }

    @Test
    fun `bmatrix block should force markdown renderer`() {
        val input = "$$ M = \\begin{bmatrix} \\frac{\\partial^2 f}{\\partial x^2} & \\frac{\\partial^2 f}{\\partial x \\partial y} \\\\ \\frac{\\partial^2 f}{\\partial y \\partial x} & \\frac{\\partial^2 f}{\\partial y^2} \\end{bmatrix} $$"

        assertTrue(MathRenderStrategy.shouldForceMarkdownRendererForMathPart(input))
        assertFalse(MathRenderStrategy.shouldEnableHorizontalScrollForMathPart(input))
        assertFalse(MathRenderStrategy.shouldPreferStableNativeMathRenderer(input))
    }

    @Test
    fun `long non structural formula should prefer breakable renderer`() {
        val input = "$$ \\sum_{N=0}^{\\infty} \\exp\\left( \\frac{\\mu N}{k_B T} \\right) + \\frac{1}{1 + \\frac{e^{-2\\pi}}{1 + \\frac{e^{-4\\pi}}{1 + \\frac{e^{-6\\pi}}{1 + \\dots}}}} $$"

        assertFalse(MathRenderStrategy.shouldForceMarkdownRendererForMathPart(input))
        assertTrue(MathRenderStrategy.shouldEnableHorizontalScrollForMathPart(input))
    }
}
