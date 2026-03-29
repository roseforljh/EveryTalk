package com.android.everytalk.ui.components.markdown

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class NativeLatexSupportTest {

    @Test
    fun `pmatrix should keep native environment unchanged`() {
        val input = "$$ \\det \\begin{pmatrix} A & B \\\\ C & D \\end{pmatrix} = \\det(A) $$"
        val normalized = NativeLatexSupport.normalizeForNativeBlockRenderer(input)

        assertTrue(normalized.contains("\\begin{pmatrix}"))
        assertTrue(normalized.contains("A & B"))
        assertTrue(normalized.contains("\\\\"))
        assertTrue(normalized.contains("C & D"))
        assertFalse(normalized.contains("\\begin{array}{"))
    }

    @Test
    fun `bmatrix should keep native environment unchanged`() {
        val input = "$$ M = \\begin{bmatrix} a & b \\\\ c & d \\end{bmatrix} $$"
        val normalized = NativeLatexSupport.normalizeForNativeBlockRenderer(input)

        assertTrue(normalized.contains("\\begin{bmatrix}"))
        assertTrue(normalized.contains("a & b"))
        assertTrue(normalized.contains("\\\\"))
        assertTrue(normalized.contains("c & d"))
        assertFalse(normalized.contains("\\begin{array}{"))
    }

    @Test
    fun `complex bmatrix should keep native environment unchanged`() {
        val input = """
            $$ M = \begin{bmatrix} \frac{\partial^2 f}{\partial x^2} & \frac{\partial^2 f}{\partial x \partial y} &
            \frac{\partial^2 f}{\partial x \partial z} \\ \frac{\partial^2 f}{\partial y \partial x} & \frac{\partial^2
            f}{\partial y^2} & \frac{\partial^2 f}{\partial y \partial z} \\ \frac{\partial^2 f}{\partial z \partial x} &
            \frac{\partial^2 f}{\partial z \partial y} & \frac{\partial^2 f}{\partial z^2} \end{bmatrix} $$
        """.trimIndent()

        val normalized = NativeLatexSupport.normalizeForNativeBlockRenderer(input)

        assertTrue(normalized.contains("\\begin{bmatrix}"))
        assertTrue(normalized.contains("\\partial"))
        assertFalse(normalized.contains("\\begin{array}{"))
    }

    @Test
    fun `simple matrix should not fallback to markdown`() {
        val input = "$$ M = \\begin{bmatrix} a & b \\\\ c & d \\end{bmatrix} $$"

        assertFalse(NativeLatexSupport.shouldFallbackToMarkdownBlockRenderer(input))
    }
}
