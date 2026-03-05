package com.android.everytalk.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MathStreamingPolicyTest {

    @Test
    fun `hasMathSyntax should detect dollar and bracket delimiters`() {
        assertTrue(MathStreamingPolicy.hasMathSyntax("price ${'$'}12"))
        assertTrue(MathStreamingPolicy.hasMathSyntax("\\(x+1\\)"))
        assertTrue(MathStreamingPolicy.hasMathSyntax("\\[x+1\\]"))
        assertFalse(MathStreamingPolicy.hasMathSyntax("plain text"))
    }

    @Test
    fun `hasUnclosedMathDelimiter should detect unclosed inline and block math`() {
        assertTrue(MathStreamingPolicy.hasUnclosedMathDelimiter("calc: ${'$'}x+1"))
        assertTrue(MathStreamingPolicy.hasUnclosedMathDelimiter("${'$'}${'$'}x+1"))
        assertFalse(MathStreamingPolicy.hasUnclosedMathDelimiter("calc: ${'$'}x+1${'$'}"))
        assertFalse(MathStreamingPolicy.hasUnclosedMathDelimiter("${'$'}${'$'}x+1${'$'}${'$'}"))
    }

    @Test
    fun `currency should not be treated as unclosed math`() {
        assertFalse(MathStreamingPolicy.hasUnclosedMathDelimiter("price is ${'$'}12"))
    }

    @Test
    fun `shouldForceMathBoundaryRefresh should trigger on boundary and non-prefix updates`() {
        assertTrue(MathStreamingPolicy.shouldForceMathBoundaryRefresh("abc", "ab"))
        assertTrue(MathStreamingPolicy.shouldForceMathBoundaryRefresh("abc", "xyz"))
        assertTrue(MathStreamingPolicy.shouldForceMathBoundaryRefresh("abc", "abc$$"))
        assertFalse(MathStreamingPolicy.shouldForceMathBoundaryRefresh("abc", "abc1"))
    }

    @Test
    fun `escapeUnclosedMathDelimiters should escape unclosed math but keep closed math`() {
        assertEquals("\\${'$'}\\${'$'}E=mc^2", MathStreamingPolicy.escapeUnclosedMathDelimiters("${'$'}${'$'}E=mc^2"))
        assertEquals("${'$'}${'$'}E=mc^2${'$'}${'$'}", MathStreamingPolicy.escapeUnclosedMathDelimiters("${'$'}${'$'}E=mc^2${'$'}${'$'}"))
        assertEquals("calc: \\${'$'}x+1", MathStreamingPolicy.escapeUnclosedMathDelimiters("calc: ${'$'}x+1"))
        assertEquals("price ${'$'}12", MathStreamingPolicy.escapeUnclosedMathDelimiters("price ${'$'}12"))
    }

    @Test
    fun `escapeAllMathDelimiters should escape math markers but keep inline code unchanged`() {
        assertEquals(
            "value \\${'$'}x\\${'$'} and \\\\[x+1\\\\]",
            MathStreamingPolicy.escapeAllMathDelimiters("value ${'$'}x${'$'} and \\[x+1\\]")
        )
        assertEquals(
            "code `${'$'}x${'$'}`",
            MathStreamingPolicy.escapeAllMathDelimiters("code `${'$'}x${'$'}`")
        )
    }
}
