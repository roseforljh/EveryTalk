package com.android.everytalk.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentParserMathRegressionTest {

    @Test
    fun `multiline matrix block should be parsed as one math part`() {
        val input = """
            在矩阵展开前：

            $$
            \begin{bmatrix}
            a & b \\
            c & d
            \end{bmatrix}
            $$

            在矩阵展开后。
        """.trimIndent()

        val parts = ContentParser.parseCompleteContent(input)
        val mathParts = parts.filterIsInstance<ContentPart.Math>()

        assertEquals(1, mathParts.size)
        assertTrue(mathParts.first().content.startsWith("$$"))
        assertTrue(mathParts.first().content.contains("\\begin{bmatrix}"))
        assertTrue(mathParts.first().content.contains("\na & b"))
        assertTrue(mathParts.first().content.contains("\\end{bmatrix}"))
    }

    @Test
    fun `multiline bracket math block should be parsed as one math part`() {
        val input = """
            先看定义：

            \[
            f(x) =
            \begin{cases}
            x^2, & x > 0 \\
            0, & x \le 0
            \end{cases}
            \]
        """.trimIndent()

        val parts = ContentParser.parseCompleteContent(input)
        val mathParts = parts.filterIsInstance<ContentPart.Math>()

        assertEquals(1, mathParts.size)
        assertTrue(mathParts.first().content.startsWith("\\["))
        assertTrue(mathParts.first().content.contains("\\begin{cases}"))
        assertTrue(mathParts.first().content.contains("x \\le 0"))
        assertTrue(mathParts.first().content.endsWith("\\]"))
    }

    @Test
    fun `matrix block from bug sample should be parsed as one math part`() {
        val input = """
            $$ M = \begin{bmatrix} \frac{\partial^2 f}{\partial x^2} & \frac{\partial^2 f}{\partial x \partial y} & \frac{\partial^2 f}{\partial x \partial z} \\ \frac{\partial^2 f}{\partial y \partial x} & \frac{\partial^2 f}{\partial y^2} & \frac{\partial^2 f}{\partial y \partial z} \\ \frac{\partial^2 f}{\partial z \partial x} & \frac{\partial^2 f}{\partial z \partial y} & \frac{\partial^2 f}{\partial z^2} \end{bmatrix} $$
        """.trimIndent()

        val parts = ContentParser.parseCompleteContent(input)
        val mathParts = parts.filterIsInstance<ContentPart.Math>()

        assertEquals(1, mathParts.size)
        assertTrue(mathParts.first().content.contains("\\begin{bmatrix}"))
        assertTrue(mathParts.first().content.contains("\\partial"))
        assertTrue(mathParts.first().content.contains("\\end{bmatrix}"))
    }

    @Test
    fun `schrodinger equation block from bug sample should be parsed as one math part`() {
        val input = """
            $$ i\hbar \frac{\partial}{\partial t} \Psi(\mathbf{r}, t) = \left[ -\frac{\hbar^2}{2m} \nabla^2 + V(\mathbf{r}, t) \right] \Psi(\mathbf{r}, t) $$
        """.trimIndent()

        val parts = ContentParser.parseCompleteContent(input)
        val mathParts = parts.filterIsInstance<ContentPart.Math>()

        assertEquals(1, mathParts.size)
        assertTrue(mathParts.first().content.contains("\\hbar"))
        assertTrue(mathParts.first().content.contains("\\partial"))
        assertTrue(mathParts.first().content.contains("\\nabla^2"))
    }

    @Test
    fun `partition function block from bug sample should be parsed as one math part`() {
        val input = """
            $$ \Xi(T, V, \mu) = \sum_{N=0}^{\infty} \exp\left( \frac{\mu N}{k_B T} \right) \left[ \sum_{\{n_i\}} \exp\left( -\frac{\sum_i n_i \epsilon_i}{k_B T} \right) \right] $$
        """.trimIndent()

        val parts = ContentParser.parseCompleteContent(input)
        val mathParts = parts.filterIsInstance<ContentPart.Math>()

        assertEquals(1, mathParts.size)
        assertTrue(mathParts.first().content.contains("\\Xi"))
        assertTrue(mathParts.first().content.contains("\\sum_{N=0}^{\\infty}"))
        assertTrue(mathParts.first().content.contains("\\epsilon_i"))
    }

    @Test
    fun `bug sample mixed text and formulas should keep three math blocks`() {
        val input = """
            这个公式测试的是希腊字母与伽马函数。

            $$ \zeta(s) = 2^s \pi^{s-1} \sin\left(\frac{\pi s}{2}\right) \Gamma(1-s) \zeta(1-s) $$

            然后是矩阵：

            $$ \det \begin{pmatrix} A & B \\ C & D \end{pmatrix} = \det(A) \det(D - CA^{-1}B) $$

            最后是量子力学：

            $$ i\hbar \frac{\partial}{\partial t} \Psi(\mathbf{r}, t) = \left[ -\frac{\hbar^2}{2m} \nabla^2 + V(\mathbf{r}, t) \right] \Psi(\mathbf{r}, t) $$
        """.trimIndent()

        val parts = ContentParser.parseCompleteContent(input)
        val mathParts = parts.filterIsInstance<ContentPart.Math>()

        assertEquals(3, mathParts.size)
        assertTrue(mathParts.any { it.content.contains("\\Gamma(1-s)") })
        assertTrue(mathParts.any { it.content.contains("\\begin{pmatrix}") })
        assertTrue(mathParts.any { it.content.contains("\\nabla^2") })
    }
}
