package com.android.everytalk.ui.components.markdown

import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownMathRegressionTest {

    @Test
    fun `matrix block should preserve begin bmatrix and partial markers`() {
        val input = """
            $$ M = \begin{bmatrix} \frac{\partial^2 f}{\partial x^2} & \frac{\partial^2 f}{\partial x \partial y} \\ \frac{\partial^2 f}{\partial y \partial x} & \frac{\partial^2 f}{\partial y^2} \end{bmatrix} $$
        """.trimIndent()

        val output = preprocessAiMarkdown(input, isStreaming = false)

        assertTrue(output.contains("\\begin{bmatrix}"))
        assertTrue(output.contains("\\partial"))
        assertTrue(output.contains("\\end{bmatrix}"))
    }

    @Test
    fun `schrodinger block should preserve hbar partial and nabla markers`() {
        val input = """
            $$ i\hbar \frac{\partial}{\partial t} \Psi(\mathbf{r}, t) = \left[ -\frac{\hbar^2}{2m} \nabla^2 + V(\mathbf{r}, t) \right] \Psi(\mathbf{r}, t) $$
        """.trimIndent()

        val output = preprocessAiMarkdown(input, isStreaming = false)

        assertTrue(output.contains("\\hbar"))
        assertTrue(output.contains("\\partial"))
        assertTrue(output.contains("\\nabla^2"))
        assertTrue(output.contains("\\left["))
        assertTrue(output.contains("\\right]"))
    }

    @Test
    fun `partition function block should preserve Xi sum and epsilon markers`() {
        val input = """
            $$ \Xi(T, V, \mu) = \sum_{N=0}^{\infty} \exp\left( \frac{\mu N}{k_B T} \right) \left[ \sum_{\{n_i\}} \exp\left( -\frac{\sum_i n_i \epsilon_i}{k_B T} \right) \right] $$
        """.trimIndent()

        val output = preprocessAiMarkdown(input, isStreaming = false)

        assertTrue(output.contains("\\Xi"))
        assertTrue(output.contains("\\sum_{N=0}^{\\infty}"))
        assertTrue(output.contains("\\epsilon_i"))
        assertTrue(output.contains("\\exp\\left("))
    }

    @Test
    fun `streaming closed complex block math should remain renderable`() {
        val input = """
            $$ \det \begin{pmatrix} A & B \\ C & D \end{pmatrix} = \det(A) \det(D - CA^{-1}B) $$
        """.trimIndent()

        val output = preprocessAiMarkdown(input, isStreaming = true)

        assertTrue(output.contains("$$"))
        assertTrue(output.contains("\\begin{pmatrix}"))
        assertTrue(output.contains("\\det"))
    }
}
