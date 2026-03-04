package com.android.everytalk.ui.components.markdown

import org.junit.Test

class MarkdownPreprocessTest4 {

    @Test
    fun `debug output`() {
        val input = """
            - 第一部分：${'$'}1 - \frac{x^2}{2!} + \frac{x^4}{4!} - \dots = \cos x${'$'}
            - 第二部分：${'$'}x - \frac{x^3}{3!} + \frac{x^5}{5!} - \dots = \sin x${'$'}
        """.trimIndent()

        val output = preprocessAiMarkdown(input, isStreaming = false)
        
        println("=== OUTPUT START ===")
        println(output)
        println("=== OUTPUT END ===")
    }
}
