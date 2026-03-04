package com.android.everytalk.ui.components.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

@Suppress("MaxLineLength")
class MathDelimiterNormalizerTest {

    @Test
    fun `normalize inline double dollar to single dollar`() {
        val input = "A \$\$x+1\$\$ B"
        val output = MathDelimiterNormalizer.normalize(input)
        assertEquals("A \$x+1\$ B", output)
    }

    @Test
    fun `normalize placeholders to dollar delimiters`() {
        val input = "[single dollar]x[single dollar] and [double dollar]y[double dollar]"
        val output = MathDelimiterNormalizer.normalize(input)
        assertEquals("\$x\$ and \$y\$", output)
    }

    @Test
    fun `normalize escaped delimiters`() {
        val input = "\\\$SYN=1\\\$"
        val output = MathDelimiterNormalizer.normalize(input)
        assertEquals("\$SYN=1\$", output)
    }

    @Test
    fun `skip fenced code blocks`() {
        val input = """
            ```kotlin
            val s = "${'$'}${'$'}raw${'$'}${'$'}"
            ```
        """.trimIndent()
        val output = MathDelimiterNormalizer.normalize(input)
        assertEquals(input, output)
    }

    @Test
    fun `skip inline code`() {
        val input = "Use `\$\$raw\$\$` but render \$\$x\$\$"
        val output = MathDelimiterNormalizer.normalize(input)
        assertEquals("Use `\$\$raw\$\$` but render \$x\$", output)
    }
}
