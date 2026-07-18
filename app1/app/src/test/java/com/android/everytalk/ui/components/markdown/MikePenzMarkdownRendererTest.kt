package com.android.everytalk.ui.components.markdown

import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MikePenzMarkdownRendererTest {

    @Test
    fun `行内公式会转换为MikePenz可承载的行内组件`() {
        val rendered = prepareMarkdownForMikePenz(
            markdown = "勾股定理为 ${'$'}a^2+b^2=c^2${'$'}。",
            contentKey = "inline",
        )

        val link = Regex("everytalk-math-inline:[A-Za-z0-9_-]+").find(rendered)?.value
        requireNotNull(link)
        assertEquals("a^2+b^2=c^2", decodeInlineMathLink(link))
        assertFalse(rendered.contains("${'$'}a^2+b^2=c^2${'$'}"))
    }

    @Test
    fun `块级公式会转换为内部公式组件而不进入代码块样式`() {
        val rendered = prepareMarkdownForMikePenz(
            markdown = "积分：\n${'$'}${'$'}\\int_0^1 x^2 dx${'$'}${'$'}\n结束",
            contentKey = "block",
        )

        assertTrue(rendered.contains("```everytalk-internal-math-v1"))
        val payload = rendered
            .substringAfter("```everytalk-internal-math-v1\n")
            .substringBefore("\n```")
        assertEquals("\\int_0^1 x^2 dx", decodeMathPayload(payload))
    }

    @Test
    fun `表格中的公式保持原始内容并交给定制表格组件`() {
        val markdown = """
            | 公式名称 | 数学表达式 |
            |:---|:---:|
            | 欧拉恒等式 | ${'$'}e^{i\\pi} + 1 = 0${'$'} |
        """.trimIndent()

        val rendered = prepareMarkdownForMikePenz(markdown, "table")

        assertEquals(markdown, rendered)
        assertFalse(rendered.contains("everytalk-math-inline:"))
    }

    @Test
    fun `代码块中的美元符号和LaTeX不会被公式预处理改写`() {
        val markdown = """
            ```kotlin
            val price = "${'$'}30"
            val latex = "${'$'}\\frac{a}{b}${'$'}"
            ```
        """.trimIndent()

        assertEquals(markdown, prepareMarkdownForMikePenz(markdown, "code"))
    }

    @Test
    fun `货币美元符号不会被识别为数学公式`() {
        val markdown = "Free ${'$'}0, Pro ${'$'}20, credit ${'$'}30"

        assertEquals(markdown, prepareMarkdownForMikePenz(markdown, "currency"))
    }

    @Test
    fun `公式载荷按UTF8往返编码`() {
        val latex = "\\begin{cases} 中文 & x > 0 \\\\ 0 & x = 0 \\end{cases}"

        assertEquals(latex, decodeMathPayload(encodeMathPayload(latex)))
    }

    @Test
    fun `完整表格代码块和公式样例可由MikePenz解析`() {
        val markdown = """
            # 渲染测试

            | 名称 | 表达式 |
            |:---|:---:|
            | 欧拉公式 | ${'$'}e^{i\\pi}+1=0${'$'} |

            ```kotlin
            val answer = 42
            ```

            ${'$'}${'$'}\\int_0^1 x^2 dx${'$'}${'$'}
        """.trimIndent()
        val prepared = prepareMarkdownForMikePenz(markdown, "full-sample")

        val state = parseMarkdown(prepared)

        assertTrue(state is State.Success)
        assertEquals(prepared, (state as State.Success).content)
    }
}
