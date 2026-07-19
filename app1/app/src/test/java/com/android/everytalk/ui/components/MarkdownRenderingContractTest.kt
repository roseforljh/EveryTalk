package com.android.everytalk.ui.components

import com.android.everytalk.ui.components.markdown.markdownToPlainText
import com.android.everytalk.ui.components.streaming.BLOCK_FORMULA_FENCE_LANGUAGE
import com.android.everytalk.ui.components.streaming.DETAILS_FENCE_LANGUAGE
import com.android.everytalk.ui.components.streaming.FormulaDisplayMode
import com.android.everytalk.ui.components.streaming.INLINE_FORMULA_SCHEME
import com.android.everytalk.ui.components.streaming.StreamBlock
import com.android.everytalk.ui.components.streaming.StreamBlockParser
import com.android.everytalk.ui.components.streaming.extractFencedCodeBlockContent
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRenderingContractTest {

    @Test
    fun `renderable markdown without outer fence uses markdown code and math owners`() {
        val markdown = """
            # 一级标题

            普通文本包含 **加粗**、*斜体* 和 ~~删除线~~。

            > 这是引用。

            - [x] 已完成
            - [ ] 待完成

            | 名称 | 公式 |
            |:---|:---:|
            | 质能方程 | ${'$'}E = mc^2${'$'} |

            行内公式 ${'$'}a^2 + b^2 = c^2${'$'}。

            ```python
            print("${'$'}HOME")
            ```

            ${'$'}${'$'}
            \int_0^1 x^2 dx
            ${'$'}${'$'}
        """.trimIndent()

        val prepared = StreamBlockParser.prepareMessage(
            content = markdown,
            messageId = "renderable-markdown",
            contentVersion = 23L,
        )

        assertEquals(3, prepared.formulas.size)
        assertEquals(
            2,
            prepared.formulas.values.count { it.displayMode == FormulaDisplayMode.INLINE },
        )
        assertEquals(
            1,
            prepared.formulas.values.count { it.displayMode == FormulaDisplayMode.BLOCK },
        )
        assertFalse(prepared.hasPendingFormula)
        assertTrue(prepared.markdown.contains("# 一级标题"))
        assertTrue(prepared.markdown.contains("> 这是引用。"))
        assertTrue(prepared.markdown.contains("| 名称 | 公式 |"))
        assertTrue(prepared.markdown.contains("```python\nprint(\"${'$'}HOME\")\n```"))
        assertTrue(prepared.markdown.contains(INLINE_FORMULA_SCHEME))
        assertTrue(prepared.markdown.contains("```$BLOCK_FORMULA_FENCE_LANGUAGE"))
        assertTrue(parseMarkdown(prepared.markdown) is State.Success)
    }

    @Test
    fun `markdown source fence renders content while nested code keeps CodeBlockCard`() {
        val markdownSource = """
            ````markdown
            # 一级标题

            | 名称 | 公式 |
            |:---|:---:|
            | 质能方程 | ${'$'}E = mc^2${'$'} |

            ```python
            print("hello")
            ```

            ${'$'}${'$'}
            \int_0^1 x^2 dx
            ${'$'}${'$'}
            ````
        """.trimIndent()

        val prepared = StreamBlockParser.prepareMessage(
            content = markdownSource,
            messageId = "markdown-source",
            contentVersion = 24L,
        )
        val codeBlocks = StreamBlockParser.parse(prepared.markdown, "markdown-source-result")
            .blocks
            .filterIsInstance<StreamBlock.CodeBlock>()
            .map { block -> extractFencedCodeBlockContent(block.text) }

        assertEquals(2, prepared.formulas.size)
        assertFalse(prepared.hasPendingFormula)
        assertTrue(prepared.markdown.contains("# 一级标题"))
        assertTrue(prepared.markdown.contains("| 名称 | 公式 |"))
        assertTrue(prepared.markdown.contains("```python\nprint(\"hello\")\n```"))
        assertTrue(prepared.markdown.contains(INLINE_FORMULA_SCHEME))
        assertTrue(prepared.markdown.contains(BLOCK_FORMULA_FENCE_LANGUAGE))
        assertFalse(prepared.markdown.contains("````markdown"))
        assertTrue(codeBlocks.any { it.language == "python" })
        assertFalse(codeBlocks.any { it.language == "markdown" })
        assertEquals(
            setOf("E = mc^2", "\\int_0^1 x^2 dx"),
            prepared.formulas.values.mapTo(mutableSetOf()) { it.latex },
        )
    }

    @Test
    fun `multiple markdown fences render while surrounding explanation stays markdown`() {
        val response = """
            以下示例需要直接渲染：

            ```markdown
            # 标题
            ${'$'}E = mc^2${'$'}
            ```

            第二个示例：

            ```markdown
            | 名称 | 公式 |
            |:---|:---:|
            | 欧拉公式 | ${'$'}e^{i\pi}+1=0${'$'} |
            ```
        """.trimIndent()

        val prepared = StreamBlockParser.prepareMessage(
            content = response,
            messageId = "multiple-markdown-examples",
            contentVersion = 25L,
        )
        val blocks = StreamBlockParser.parse(prepared.markdown, "multiple-markdown-result").blocks
        val codeBlocks = blocks.filterIsInstance<StreamBlock.CodeBlock>()

        assertEquals(2, prepared.formulas.size)
        assertTrue(codeBlocks.isEmpty())
        assertFalse(prepared.markdown.contains("```markdown"))
        assertTrue(prepared.markdown.contains("# 标题"))
        assertTrue(prepared.markdown.contains("| 名称 | 公式 |"))
        assertTrue(blocks.filterIsInstance<StreamBlock.PlainText>().any { it.text.contains("第二个示例") })
    }

    @Test
    fun `md fence follows the same renderable markdown rule`() {
        val prepared = StreamBlockParser.prepareMessage(
            content = """
                ```md
                ## 标题

                公式 ${'$'}a+b${'$'}。
                ```
            """.trimIndent(),
            messageId = "md-source",
            contentVersion = 29L,
        )

        assertFalse(prepared.markdown.contains("```md"))
        assertTrue(prepared.markdown.contains("## 标题"))
        assertTrue(prepared.markdown.contains(INLINE_FORMULA_SCHEME))
        assertEquals("a+b", prepared.formulas.values.single().latex)
    }

    @Test
    fun `unfinished markdown fence streams as renderable content`() {
        val prepared = StreamBlockParser.prepareMessage(
            content = """
                ```markdown
                ## 流式标题

                公式 ${'$'}x+y${'$'}
            """.trimIndent(),
            messageId = "streaming-markdown-source",
            contentVersion = 30L,
        )

        assertFalse(prepared.markdown.contains("```markdown"))
        assertTrue(prepared.markdown.contains("## 流式标题"))
        assertEquals("x+y", prepared.formulas.values.single().latex)
        assertFalse(prepared.hasPendingFormula)
    }

    @Test
    fun `markdown-looking fence inside real code remains untouched`() {
        val pythonSource = listOf(
            "````python",
            "```markdown",
            "# 这是 Python 字符串中的原文",
            "```",
            "````",
        ).joinToString("\n")

        val prepared = StreamBlockParser.prepareMessage(
            content = pythonSource,
            messageId = "python-with-markdown-source",
            contentVersion = 31L,
        )
        val code = StreamBlockParser.parse(prepared.markdown, "python-result")
            .blocks
            .filterIsInstance<StreamBlock.CodeBlock>()
            .single()
            .let { block -> extractFencedCodeBlockContent(block.text) }

        assertEquals(pythonSource, prepared.markdown)
        assertTrue(prepared.formulas.isEmpty())
        assertEquals("python", code.language)
        assertTrue(code.code.contains("```markdown"))
    }

    @Test
    fun `formulas beside lists quotes emphasis and links use the same parser`() {
        val markdown = """
            - 列表公式 ${'$'}a+1${'$'}

            > 引用公式 ${'$'}b+1${'$'}

            **粗体文本**旁边是 ${'$'}c+1${'$'}。

            [链接](https://example.com)旁边是 ${'$'}d+1${'$'}。
        """.trimIndent()

        val prepared = StreamBlockParser.prepareMessage(
            content = markdown,
            messageId = "markdown-formula-contexts",
            contentVersion = 26L,
        )

        assertEquals(listOf("a+1", "b+1", "c+1", "d+1"), prepared.formulas.values.map { it.latex })
        assertTrue(prepared.formulas.values.all { it.displayMode == FormulaDisplayMode.INLINE })
        assertTrue(prepared.markdown.contains("- 列表公式"))
        assertTrue(prepared.markdown.contains("> 引用公式"))
        assertTrue(prepared.markdown.contains("**粗体文本**"))
        assertTrue(prepared.markdown.contains("[链接](https://example.com)"))
        assertTrue(parseMarkdown(prepared.markdown) is State.Success)
    }

    @Test
    fun `pricing dollars remain ordinary markdown and plain copy keeps their values`() {
        val markdown = """
            **Free** 套餐为 **${'$'}0/月**，每月包含 ${'$'}30 inference credit。

            > No overage charges until August 2026.
        """.trimIndent()
        val prepared = StreamBlockParser.prepareMessage(
            content = markdown,
            messageId = "pricing",
            contentVersion = 27L,
        )
        val plainText = markdownToPlainText(markdown)

        assertEquals(markdown, prepared.markdown)
        assertTrue(prepared.formulas.isEmpty())
        assertTrue(parseMarkdown(prepared.markdown) is State.Success)
        assertTrue(plainText.contains("${'$'}0/月"))
        assertTrue(plainText.contains("${'$'}30 inference credit"))
        assertFalse(plainText.contains("**"))
    }

    @Test
    fun `formula placeholder uses stable lowercase SHA256 id without payload encoding`() {
        val prepared = StreamBlockParser.prepareMessage(
            content = "公式 ${'$'}E = mc^2${'$'}",
            messageId = "stable-id",
            contentVersion = 28L,
        )
        val formula = prepared.formulas.values.single()

        assertTrue(formula.id.matches(Regex("[0-9a-f]{64}")))
        assertEquals("公式 ![math](everytalk-math-inline:${formula.id})", prepared.markdown)
        assertFalse(prepared.markdown.contains("E = mc^2"))
        assertFalse(prepared.markdown.contains("base64", ignoreCase = true))
        assertFalse(prepared.markdown.contains("katex", ignoreCase = true))
    }

    @Test
    fun `edge case extensions keep one markdown math and code ownership chain`() {
        val markdown = """
            <span style="color:red;font-weight:bold">红色粗体</span>

            联系邮箱 <test@example.com>，实体 &copy; &amp; &lt; &gt;，表情 :rocket:。

            转义括号 \[这不是链接\]。

            正文脚注[^note]。

            <details>
            <summary>折叠详情 :smile:</summary>

            公式 ${'$'}E=mc^2${'$'} 与 **粗体**。

            </details>

            ```text
            <code@example.com> :rocket:
            ```

            <script>alert('不执行')</script>

            [^note]: 脚注包含 [链接](https://example.com/docs)。
        """.trimIndent()

        val prepared = StreamBlockParser.prepareMessage(
            content = markdown,
            messageId = "edge-case-extensions",
            contentVersion = 32L,
        )
        val details = prepared.details.values.single()

        assertEquals(1, prepared.formulas.size)
        assertTrue(prepared.markdown.contains("[test@example.com](mailto:test@example.com)"))
        assertTrue(prepared.markdown.contains("实体 &copy; &amp; &lt; &gt;，表情 🚀"))
        assertTrue(prepared.markdown.contains("\\[这不是链接\\]"))
        assertTrue(
            prepared.markdown.contains(
                "正文脚注[¹](everytalk-footnote-definition:1:1)"
            )
        )
        assertTrue(
            prepared.markdown.contains(
                "[¹](everytalk-footnote-reference:1) " +
                    "脚注包含 [链接](https://example.com/docs)"
            )
        )
        assertTrue(prepared.markdown.contains("```$DETAILS_FENCE_LANGUAGE\n${details.id}\n```"))
        assertEquals("折叠详情 😄", details.summary)
        assertTrue(details.markdown.contains(INLINE_FORMULA_SCHEME))
        assertTrue(details.markdown.contains("**粗体**"))
        assertTrue(prepared.markdown.contains("<code@example.com> :rocket:"))
        assertFalse(prepared.markdown.contains("alert('不执行')"))
        assertTrue(parseMarkdown(prepared.markdown) is State.Success)
    }
}
