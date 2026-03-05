package com.android.everytalk.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test

class ContentParserAccidentalCodeBlockTest {

    @Test
    fun `indented math prose should not be parsed as code block`() {
        val input = """
            1. 利用 Cauchy-Schwarz 不等式：

                由于 ${'$'}${'$'}a+b+c=1${'$'}${'$'}，则 ${'$'}${'$'}\sum a = 1${'$'}${'$'}，于是：
        """.trimIndent()

        val parts = ContentParser.parseCompleteContent(input)

        assertTrue(parts.none { it is ContentPart.Code })
        assertTrue(parts.any { it is ContentPart.Text && it.content.contains("\$\$a+b+c=1\$\$") })
    }
}

