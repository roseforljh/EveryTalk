package com.android.everytalk.ui.components.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamBlockParserTest {

    @Test
    fun `supports escaped inline math delimiters`() {
        val result = StreamBlockParser.parse("前缀 \\(x+1\\) 后缀", "msg-1")

        assertEquals(3, result.blocks.size)
        assertTrue(result.blocks[1] is StreamBlock.MathInline)
        assertEquals(MathBlockState.RENDERED, (result.blocks[1] as StreamBlock.MathInline).state)
        assertEquals("\\(x+1\\)", result.blocks[1].text)
        assertFalse(result.hasPendingMath)
    }

    @Test
    fun `supports escaped block math delimiters`() {
        val result = StreamBlockParser.parse("\\[x^2 + y^2\\]", "msg-2")

        assertEquals(1, result.blocks.size)
        assertTrue(result.blocks.first() is StreamBlock.MathBlock)
        assertEquals(MathBlockState.RENDERED, (result.blocks.first() as StreamBlock.MathBlock).state)
        assertEquals("\\[x^2 + y^2\\]", result.blocks.first().text)
    }

    @Test
    fun `closed escaped block math with explicit math content may appear inside prose`() {
        val result = StreamBlockParser.parse("公式：\\[x^2+y^2\\]。", "msg-inline-block-math")

        assertEquals(3, result.blocks.size)
        assertTrue(result.blocks[1] is StreamBlock.MathBlock)
        assertEquals("\\[x^2+y^2\\]", result.blocks[1].text)
        assertFalse(result.hasPendingMath)
    }

    @Test
    fun `escaped brackets inside prose stay plain text`() {
        val content = "普通文本 \\[这不是链接\\]，继续说明。"
        val result = StreamBlockParser.parse(content, "msg-escaped-brackets-prose")

        assertEquals(1, result.blocks.size)
        assertTrue(result.blocks.single() is StreamBlock.PlainText)
        assertEquals(content, result.blocks.single().text)
        assertFalse(result.hasPendingMath)
    }

    @Test
    fun `escaped prose brackets with weak separators stay plain text`() {
        listOf(
            "\\[not-a-link\\]",
            "\\[read/write\\]",
            "\\[draft_v2\\]",
        ).forEachIndexed { index, content ->
            val result = StreamBlockParser.parse(content, "msg-escaped-weak-$index")
            val prepared = StreamBlockParser.prepareMessage(
                content = content,
                messageId = "msg-escaped-weak-$index",
                contentVersion = index.toLong(),
            )

            assertEquals(1, result.blocks.size)
            assertTrue(result.blocks.single() is StreamBlock.PlainText)
            assertEquals(content, result.blocks.single().text)
            assertFalse(result.hasPendingMath)
            assertEquals(content, prepared.markdown)
            assertTrue(prepared.formulas.isEmpty())
        }
    }

    @Test
    fun `escaped block math keeps strong and numeric expressions`() {
        val content = "\\[x^2+y^2\\] \\[E=mc^2\\] \\[\\frac{1}{2}\\] \\[1+2\\]"
        val prepared = StreamBlockParser.prepareMessage(
            content = content,
            messageId = "msg-escaped-strong",
            contentVersion = 20L,
        )

        assertEquals(
            setOf("x^2+y^2", "E=mc^2", "\\frac{1}{2}", "1+2"),
            prepared.formulas.values.mapTo(mutableSetOf()) { it.latex },
        )
        assertFalse(prepared.hasPendingFormula)
    }

    @Test
    fun `escaped block math syntax inside code and link destinations stays non math`() {
        val content = """
            `\[x^2\]`
            [链接](https://example.com/\[x^2\])
            ```text
            \[x^2\]
            ```
        """.trimIndent()
        val result = StreamBlockParser.parse(content, "msg-escaped-block-code-link")

        assertTrue(result.blocks.none { it is StreamBlock.MathBlock })
        assertFalse(result.hasPendingMath)
    }

    @Test
    fun `unclosed container fences protect their code without swallowing outside prose`() {
        val contents = listOf(
            "> ```text\n> ${'$'}x${'$'}\n正文 ${'$'}y${'$'}",
            "- ```text\n  ${'$'}x${'$'}\n正文 ${'$'}y${'$'}",
            "- 项目\n    ```text\n    ${'$'}x${'$'}\n正文 ${'$'}y${'$'}",
        )

        contents.forEachIndexed { index, content ->
            val result = StreamBlockParser.parse(content, "msg-unclosed-container-fence-$index")
            val codeBlock = result.blocks.filterIsInstance<StreamBlock.CodeBlock>().single()
            val mathBlocks = result.blocks.filterIsInstance<StreamBlock.MathInline>()
            val prepared = StreamBlockParser.prepareMessage(
                content = content,
                messageId = "msg-unclosed-container-fence-$index",
                contentVersion = 25L + index,
            )

            assertTrue(codeBlock.text.contains("${'$'}x${'$'}"))
            assertFalse(codeBlock.text.contains("${'$'}y${'$'}"))
            assertEquals(listOf("${'$'}y${'$'}"), mathBlocks.map { it.text })
            assertEquals(listOf("y"), prepared.formulas.values.map { it.latex })
            assertTrue(prepared.markdown.contains("${'$'}x${'$'}"))
            assertFalse(prepared.hasPendingFormula)
        }
    }

    @Test
    fun `top level indented code protects every math delimiter across blank lines`() {
        val contents = listOf(
            listOf(
                "    ${'$'}x${'$'}",
                "    ${'$'}${'$'}y+1${'$'}${'$'}",
                "",
                "    \\(z+1\\)",
                "    \\[x^2+y^2\\]",
            ).joinToString("\n"),
            listOf(
                "\t${'$'}x${'$'}",
                "\t${'$'}${'$'}y+1${'$'}${'$'}",
                "",
                "\t\\(z+1\\)",
                "\t\\[x^2+y^2\\]",
            ).joinToString("\n"),
        )

        contents.forEachIndexed { index, content ->
            val result = StreamBlockParser.parse(content, "msg-indented-code-$index")
            val prepared = StreamBlockParser.prepareMessage(
                content = content,
                messageId = "msg-indented-code-$index",
                contentVersion = 30L + index,
            )

            assertTrue(result.blocks.none { it is StreamBlock.MathInline || it is StreamBlock.MathBlock })
            assertFalse(result.hasPendingMath)
            assertEquals(content, prepared.markdown)
            assertTrue(prepared.formulas.isEmpty())
            assertFalse(prepared.hasPendingFormula)
        }
    }

    @Test
    fun `indented list continuation remains markdown and parses its real formula`() {
        val content = "- 项目\n\n    续行公式 ${'$'}x+1${'$'}"
        val prepared = StreamBlockParser.prepareMessage(
            content = content,
            messageId = "msg-list-continuation",
            contentVersion = 32L,
        )

        assertEquals(listOf("x+1"), prepared.formulas.values.map { it.latex })
        assertTrue(prepared.markdown.startsWith("- 项目\n\n    续行公式 "))
        assertFalse(prepared.hasPendingFormula)
    }

    @Test
    fun `list and block quote indented code protect formulas at their container baseline`() {
        val content = listOf(
            "- 项目",
            "",
            "    续行公式 ${'$'}a+1${'$'}",
            "",
            "      ${'$'}b+1${'$'}",
            "",
            ">     ${'$'}c+1${'$'}",
        ).joinToString("\n")
        val prepared = StreamBlockParser.prepareMessage(
            content = content,
            messageId = "msg-container-indented-code",
            contentVersion = 35L,
        )

        assertEquals(listOf("a+1"), prepared.formulas.values.map { it.latex })
        assertTrue(prepared.markdown.contains("      ${'$'}b+1${'$'}"))
        assertTrue(prepared.markdown.contains(">     ${'$'}c+1${'$'}"))
        assertFalse(prepared.hasPendingFormula)
    }

    @Test
    fun `html tags and attributes protect delimiters while body math still parses`() {
        val content =
            "<span style=\"color:${'$'}red${'$'}\" data-inline=\"\\(attr\\)\" data-block=\"\\[x^2\\]\">正文 ${'$'}x+1${'$'}</span>"
        val prepared = StreamBlockParser.prepareMessage(
            content = content,
            messageId = "msg-html-attributes",
            contentVersion = 33L,
        )

        assertEquals(listOf("x+1"), prepared.formulas.values.map { it.latex })
        assertTrue(prepared.markdown.contains("color:${'$'}red${'$'}"))
        assertTrue(prepared.markdown.contains("data-inline=\"\\(attr\\)\""))
        assertTrue(prepared.markdown.contains("data-block=\"\\[x^2\\]\""))
        assertFalse(prepared.hasPendingFormula)
    }

    @Test
    fun `raw html code elements never create pending math`() {
        val content = """
            <script>const value = '${'$'}unfinished';</script>
            <style>.price::after { content: '${'$'}raw'; }</style>
            正文 ${'$'}x+1${'$'}
        """.trimIndent()
        val prepared = StreamBlockParser.prepareMessage(
            content = content,
            messageId = "msg-raw-html-code",
            contentVersion = 36L,
        )

        assertEquals(listOf("x+1"), prepared.formulas.values.map { it.latex })
        assertFalse(prepared.hasPendingFormula)
        assertFalse(prepared.markdown.contains("<script>"))
        assertTrue(prepared.markdown.contains("${'$'}raw"))
    }

    @Test
    fun `bare urls and angle autolinks protect delimiters while prose math still parses`() {
        val bareUrl = "https://example.com/${'$'}value${'$'}/\\(slug\\)/\\[x^2\\]"
        val angleAutolink = "<https://example.com/${'$'}id${'$'}/\\(node\\)/\\[a+b\\]>"
        val emailAutolink = "<user+${'$'}x${'$'}@example.com>"
        val content = "$bareUrl $angleAutolink $emailAutolink 正文 ${'$'}z+1${'$'}"
        val prepared = StreamBlockParser.prepareMessage(
            content = content,
            messageId = "msg-url-delimiters",
            contentVersion = 34L,
        )

        assertEquals(listOf("z+1"), prepared.formulas.values.map { it.latex })
        assertTrue(prepared.markdown.contains(bareUrl))
        assertTrue(prepared.markdown.contains(angleAutolink))
        assertTrue(
            prepared.markdown.contains(
                "[user+${'$'}x${'$'}@example.com](mailto:user+${'$'}x${'$'}@example.com)"
            )
        )
        assertFalse(prepared.hasPendingFormula)
    }

    @Test
    fun `unclosed explicit escaped block math inside prose remains pending`() {
        val result = StreamBlockParser.parse("公式：\\[x^2+y^2", "msg-pending-inline-block-math")

        assertEquals(2, result.blocks.size)
        assertTrue(result.blocks[1] is StreamBlock.MathBlock)
        assertEquals(MathBlockState.RAW, (result.blocks[1] as StreamBlock.MathBlock).state)
        assertTrue(result.hasPendingMath)
    }

    @Test
    fun `keeps unclosed escaped inline math as raw pending block`() {
        val result = StreamBlockParser.parse("结果是 \\(x+1", "msg-3")

        assertEquals(2, result.blocks.size)
        assertTrue(result.blocks[1] is StreamBlock.MathInline)
        assertEquals(MathBlockState.RAW, (result.blocks[1] as StreamBlock.MathInline).state)
        assertTrue(result.hasPendingMath)
    }

    @Test
    fun `stable ids remain unchanged for unchanged prefix blocks`() {
        val first = StreamBlockParser.parse("前缀 \\(x+1\\)", "msg-4")
        val second = StreamBlockParser.parse("前缀 \\(x+1\\) 后缀继续", "msg-4")

        assertEquals(first.blocks[0].stableId, second.blocks[0].stableId)
        assertEquals(first.blocks[1].stableId, second.blocks[1].stableId)
        assertEquals(first.blocks[1].text, second.blocks[1].text)
    }

    @Test
    fun `closed single dollar math remains inline math while streaming`() {
        val result = StreamBlockParser.parse(
            "formula ${'$'}x+1${'$'} and ${'$'}1+2=3${'$'} stay math",
            "msg-single-dollar",
        )

        assertEquals(5, result.blocks.size)
        assertTrue(result.blocks[1] is StreamBlock.MathInline)
        assertEquals("${'$'}x+1${'$'}", result.blocks[1].text)
        assertTrue(result.blocks[3] is StreamBlock.MathInline)
        assertEquals("${'$'}1+2=3${'$'}", result.blocks[3].text)
        assertFalse(result.hasPendingMath)
    }

    @Test
    fun `double dollar math remains block math while streaming`() {
        val result = StreamBlockParser.parse(
            "formula\n${'$'}${'$'}\\frac{a}{b}${'$'}${'$'}\ndone",
            "msg-double-dollar",
        )

        assertEquals(3, result.blocks.size)
        assertTrue(result.blocks[1] is StreamBlock.MathBlock)
        assertEquals("${'$'}${'$'}\\frac{a}{b}${'$'}${'$'}", result.blocks[1].text)
        assertFalse(result.hasPendingMath)
    }

    @Test
    fun `currency before single dollar math does not consume the formula opener`() {
        val result = StreamBlockParser.parse(
            "price ${'$'}12 then formula ${'$'}x+1${'$'}",
            "msg-currency-before-math",
        )

        assertEquals(2, result.blocks.size)
        assertTrue(result.blocks[0] is StreamBlock.PlainText)
        assertEquals("price ${'$'}12 then formula ", result.blocks[0].text)
        assertTrue(result.blocks[1] is StreamBlock.MathInline)
        assertEquals("${'$'}x+1${'$'}", result.blocks[1].text)
        assertFalse(result.hasPendingMath)
    }

    @Test
    fun `digit started latex command remains inline math while streaming`() {
        val result = StreamBlockParser.parse(
            "formula ${'$'}2\\pi${'$'}",
            "msg-digit-latex",
        )

        assertEquals(2, result.blocks.size)
        assertTrue(result.blocks[1] is StreamBlock.MathInline)
        assertEquals("${'$'}2\\pi${'$'}", result.blocks[1].text)
        assertFalse(result.hasPendingMath)
    }

    @Test
    fun `complex single dollar math remains inline token while streaming`() {
        val result = StreamBlockParser.parse(
            "formula ${'$'}\\frac{a}{b}${'$'} stays inline",
            "msg-complex-inline",
        )

        assertEquals(3, result.blocks.size)
        assertTrue(result.blocks[1] is StreamBlock.MathInline)
        assertEquals("${'$'}\\frac{a}{b}${'$'}", result.blocks[1].text)
        assertFalse(result.hasPendingMath)
    }

    @Test
    fun `code block with longer outer fence keeps inner triple fences inside code`() {
        val result = StreamBlockParser.parse(
            "````markdown\n```kotlin\nval x = 1\n```\n````",
            "msg-9",
        )

        assertEquals(1, result.blocks.size)
        assertTrue(result.blocks.first() is StreamBlock.CodeBlock)
        assertEquals(
            "```kotlin\nval x = 1\n```",
            extractFencedCodeBlockContent(result.blocks.first().text).code,
        )
    }

    @Test
    fun `indented markdown fences stay code and are not unwrapped`() {
        val cases = listOf(
            "    ```markdown\n    ${'$'}x+1${'$'}\n    ```" to "markdown",
            "\t~~~md\n\t\\[x^2\\]\n\t~~~" to "md",
        )

        cases.forEachIndexed { index, (content, language) ->
            val prepared = StreamBlockParser.prepareMessage(
                content = content,
                messageId = "msg-indented-markdown-fence-$index",
                contentVersion = 40L + index,
            )
            val codeBlocks = StreamBlockParser.parse(prepared.markdown, "msg-indented-markdown-result-$index")
                .blocks
                .filterIsInstance<StreamBlock.CodeBlock>()

            assertEquals(content, prepared.markdown)
            assertTrue(prepared.formulas.isEmpty())
            assertEquals(1, codeBlocks.size)
            assertEquals(language, extractFencedCodeBlockContent(codeBlocks.single().text).language)
        }
    }

    @Test
    fun `zero to three space markdown fences still unwrap as renderable markdown`() {
        val cases = listOf(
            "```markdown\n公式 ${'$'}x+1${'$'}\n```" to "x+1",
            "   ~~~md\n公式 ${'$'}y+1${'$'}\n   ~~~" to "y+1",
        )

        cases.forEachIndexed { index, (content, latex) ->
            val prepared = StreamBlockParser.prepareMessage(
                content = content,
                messageId = "msg-renderable-markdown-fence-$index",
                contentVersion = 50L + index,
            )

            assertEquals(listOf(latex), prepared.formulas.values.map { it.latex })
            assertFalse(prepared.markdown.contains("```markdown"))
            assertFalse(prepared.markdown.contains("~~~md"))
            assertFalse(prepared.hasPendingFormula)
        }
    }

    @Test
    fun `top level code block closes with up to three spaces indentation`() {
        val result = StreamBlockParser.parse(
            "```bash\nopencode web\n   ```",
            "msg-10",
        )

        assertEquals(1, result.blocks.size)
        assertTrue(result.blocks.first() is StreamBlock.CodeBlock)
        assertEquals("opencode web", extractFencedCodeBlockContent(result.blocks.first().text).code.trim())
    }

    @Test
    fun `closing fence can be longer than opening fence`() {
        val result = StreamBlockParser.parse(
            "```bash\nopencode web\n``````",
            "msg-11",
        )

        assertEquals(1, result.blocks.size)
        assertTrue(result.blocks.first() is StreamBlock.CodeBlock)
        assertEquals("opencode web", extractFencedCodeBlockContent(result.blocks.first().text).code.trim())
    }

    @Test
    fun `pricing table currency should not split streaming blocks as math`() {
        val result = StreamBlockParser.parse(pioneerPricingMarkdown, "msg-pricing")

        assertFalse(result.hasPendingMath)
        assertEquals(1, result.blocks.size)
        assertTrue(result.blocks.single() is StreamBlock.PlainText)
        assertEquals(pioneerPricingMarkdown, result.blocks.single().text)
    }

    @Test
    fun `pricing table streaming state does not split currency as math`() {
        val state = buildStreamingRenderState(
            messageId = "msg-pricing-state",
            content = pioneerPricingMarkdown,
            isStreaming = false,
            isComplete = true,
        )

        assertFalse(state.hasPendingMath)
        assertEquals(1, state.blocks.size)
        assertTrue(state.blocks.single() is StreamBlock.PlainText)
        assertTrue(state.preparedMessage.formulas.isEmpty())
    }

    @Test
    fun `detailed pricing table currency should not split streaming blocks as math`() {
        val result = StreamBlockParser.parse(pioneerDetailedPricingMarkdown, "msg-detailed-pricing")

        assertFalse(result.hasPendingMath)
        assertEquals(1, result.blocks.size)
        assertTrue(result.blocks.single() is StreamBlock.PlainText)
        assertEquals(pioneerDetailedPricingMarkdown, result.blocks.single().text)
    }

    private companion object {
        private val pioneerDetailedPricingMarkdown = """
            | 套餐名称 | 价格 | 适用人群 | 核心功能与权益 |
            |:---|:---:|:---|:---|
            | **Free (免费版)** | **${'$'}0** / 月 | 个人开发者 | • 赠送 **${'$'}30** 的推理额度<br>• 基础推理 API |
            | **Pro (专业版)** | **${'$'}20** / 用户/月 | 团队 | 包含 Free 版所有功能<br>• **限时免费推理** |
            | **Enterprise (企业版)** | **自定义** | 企业 | BYO cloud / private VPC |
        """.trimIndent()

        private val pioneerPricingMarkdown = """
            我查了 pioneer.ai 官网 Pricing 页和文档页，目前它有 **Free、Pro、Enterprise** 三档：

            | 套餐 | 价格 | 适合对象 | 包含内容 / 特点 |
            |:---|:---:|:---|
            | **Free** | **${'$'}0/月** | 试用、个人探索 | 含 **${'$'}30 inference credit**；Inference API；Continuous model optimization；Agent mode；Adaptive Inference |
            | **Pro** | **${'$'}20/用户/月** | 扩展中的团队 | 包含 Free 全部功能；**2026 年 8 月 1 日前免费 inference**（受 rate limits 限制）；更高 rate limits；可下载模型权重；Deep Research mode；可邀请团队成员 |
            | **Enterprise** | **定制报价** | 大团队、复杂工作流、企业部署 | BYO cloud / private VPC；Dedicated H100 fleet；SOC2 / HIPAA 合规；24/7 SLA；专属 SE/解决方案工程师；定制价格 |
        """.trimIndent()
    }
}
