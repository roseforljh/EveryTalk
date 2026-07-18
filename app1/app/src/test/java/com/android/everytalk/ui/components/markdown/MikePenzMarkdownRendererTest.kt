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
    fun `表格由MikePenz原生解析且行内公式继续交给KaTeX`() {
        val markdown = """
            | 公式名称 | 数学表达式 |
            |:---|:---:|
            | 欧拉恒等式 | ${'$'}e^{i\\pi} + 1 = 0${'$'} |
        """.trimIndent()

        val rendered = prepareMarkdownForMikePenz(markdown, "table")

        assertTrue(rendered.contains("everytalk-math-inline:"))
        assertFalse(rendered.contains("${'$'}e^{i\\pi} + 1 = 0${'$'}"))
        assertTrue(parseMarkdown(rendered) is State.Success)
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
    fun `Markdown示例中的嵌套代码围栏不会吞掉后续内容`() {
        val markdown = """
            ```markdown
            ### Python 示例

            ```python
            print("ok")
            ```

            行内代码说明
            ```

            ### 后续标题
            后续正文
        """.trimIndent()

        val normalized = normalizeNestedMarkdownCodeFences(markdown)

        assertTrue(normalized.startsWith("````markdown\n"))
        assertTrue(normalized.contains("\n```python\n"))
        assertTrue(normalized.contains("\n```\n\n行内代码说明\n````\n\n### 后续标题"))
        assertTrue(parseMarkdown(normalized) is State.Success)

        val prepared = prepareMarkdownForMikePenz(markdown, "nested-markdown")
        assertFalse(prepared.contains("````markdown"))
        assertFalse(prepared.contains("```markdown"))
        assertTrue(prepared.contains("```python\nprint(\"ok\")\n```"))
        assertTrue(prepared.contains("### 后续标题\n后续正文"))
        assertTrue(parseMarkdown(prepared) is State.Success)
    }

    @Test
    fun `普通代码围栏保持原样`() {
        val markdown = """
            ```python
            print("ok")
            ```
        """.trimIndent()

        assertEquals(markdown, normalizeNestedMarkdownCodeFences(markdown))
    }

    @Test
    fun `Markdown文档围栏中的表格和公式进入正式渲染`() {
        val markdown = """
            ```markdown
            | 名称 | 公式 |
            |:---|:---:|
            | 质能方程 | ${'$'}E = mc^2${'$'} |

            ${'$'}${'$'}e^{i\\pi} + 1 = 0${'$'}${'$'}
            ```
        """.trimIndent()

        val prepared = prepareMarkdownForMikePenz(markdown, "wrapped-table-math")

        assertFalse(prepared.contains("```markdown"))
        assertTrue(prepared.contains("| 名称 | 公式 |"))
        assertTrue(prepared.contains("everytalk-math-inline:"))
        assertTrue(prepared.contains("```everytalk-internal-math-v1"))
        assertTrue(parseMarkdown(prepared) is State.Success)
    }

    @Test
    fun `进行中任务标记转为标准未完成任务且代码块内容不变`() {
        val markdown = """
            - [/] 核心功能模块开发（进行中）

            ```text
            - [/] 代码示例
            ```
        """.trimIndent()

        val prepared = prepareMarkdownForMikePenz(markdown, "in-progress-task")

        assertTrue(prepared.startsWith("- [ ] 核心功能模块开发（进行中）"))
        assertTrue(prepared.contains("```text\n- [/] 代码示例\n```"))
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
