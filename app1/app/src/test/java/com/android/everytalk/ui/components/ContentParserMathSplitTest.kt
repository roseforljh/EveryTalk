package com.android.everytalk.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentParserMathSplitTest {

    @Test
    fun `standalone single dollar math line should be split to math part`() {
        val input = """
            Before
            ${'$'}\frac{a^3 + b^3 + c^3}{3} \ge (\frac{a + b + c}{3})^3${'$'}
            After
        """.trimIndent()

        val parts = ContentParser.parseCompleteContent(input)
        val mathParts = parts.filterIsInstance<ContentPart.Math>()

        assertEquals(1, mathParts.size)
        assertTrue(mathParts.first().content.startsWith("$"))
        assertTrue(mathParts.first().content.endsWith("$"))
    }

    @Test
    fun `standalone double dollar math line should be split to math part`() {
        val input = """
            line1
            ${'$'}${'$'}\int_0^1 x^2 dx${'$'}${'$'}
            line2
        """.trimIndent()

        val parts = ContentParser.parseCompleteContent(input)
        val mathParts = parts.filterIsInstance<ContentPart.Math>()

        assertEquals(1, mathParts.size)
        assertTrue(mathParts.first().content.startsWith("$$"))
        assertTrue(mathParts.first().content.endsWith("$$"))
    }

    @Test
    fun `inline math inside sentence should remain text part`() {
        val input = "Eq: ${'$'}a+b${'$'} is inline."
        val parts = ContentParser.parseCompleteContent(input)

        assertTrue(parts.none { it is ContentPart.Math })
        assertTrue(parts.any { it is ContentPart.Text })
    }

    @Test
    fun `trailing long inline math should be promoted to math part`() {
        val input = "Given inequality: ${'$'}\\frac{a^3 + b^3 + c^3}{3} \\ge (\\frac{a + b + c}{3})^3${'$'}"
        val parts = ContentParser.parseCompleteContent(input)

        assertEquals(1, parts.filterIsInstance<ContentPart.Math>().size)
        assertTrue(parts.first() is ContentPart.Text)
        assertTrue(parts[1] is ContentPart.Math)
    }
}
