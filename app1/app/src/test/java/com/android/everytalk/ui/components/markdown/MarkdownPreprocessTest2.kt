package com.android.everytalk.ui.components.markdown

import org.junit.Test

class MarkdownPreprocessTest2 {

    @Test
    fun `debug output`() {
        val input = """
            - 第一部分：${'$'}1 - \frac{x^2}{2!} + \dots = \cos x${'$'}
            售价 ${'$'}12 或者 ${'$'}${'$'}24
        """.trimIndent()

        val output = preprocessAiMarkdown(input, isStreaming = false)
        
        println("=== OUTPUT START ===")
        println(output)
        println("=== OUTPUT END ===")
    }
}
