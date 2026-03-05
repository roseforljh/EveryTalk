package com.android.everytalk.ui.components.markdown

import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPreprocessLongMathContentTest {

    @Test
    fun `long math article should convert inline dollar formulas to double dollar render form`() {
        val input = """
            ## 数论与不等式：${'$'}a^3 + b^3 + c^3 = 33(a + b + c)^2${'$'}
            根据三维幂平均不等式，我们有：
            ${'$'}\frac{a^3 + b^3 + c^3}{3} \ge (\frac{a + b + c}{3})^3${'$'}

            - ${'$'}C_{10}^3 C_7^1 = 120 \times 7 = 840${'$'}

            ```infographic
            infographic
            data
            title 数学问题核心结论汇总
            ```
        """.trimIndent()

        val output = preprocessAiMarkdown(input, isStreaming = false)

        assertTrue(output.contains("## 数论与不等式：\$\$a^3 + b^3 + c^3 = 33(a + b + c)^2\$\$"))
        assertTrue(output.contains("\$\$\\frac{a^3 + b^3 + c^3}{3} \\ge (\\frac{a + b + c}{3})^3\$\$"))
        assertTrue(output.contains("\$\$C_{10}^3 C_7^1 = 120 \\times 7 = 840\$\$"))

        assertTrue(output.contains("```infographic"))
    }
}
