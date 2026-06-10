package com.android.everytalk.util.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSanitizerTest {
    @Test
    fun `removes unicode replacement characters without touching normal chinese`() {
        val text = "为什么���今天���没有工资"

        val result = TextSanitizer.removeUnicodeReplacementCharacters(text)

        assertEquals("为什么今天没有工资", result)
    }
}
