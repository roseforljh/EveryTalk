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
    fun `streaming render state keeps only tail mutable while streaming`() {
        val state = buildStreamingRenderState(
            messageId = "msg-5",
            content = "第一段\n```kotlin\nval x = 1\n```\n正在增长的尾段",
            isStreaming = true,
            isComplete = false,
        )

        assertTrue(state.blocks.size > 1)
        assertEquals(state.blocks.dropLast(1), state.committedBlocks)
        assertEquals(state.blocks.takeLast(1), state.tailBlocks)
    }

    @Test
    fun `complete render state commits all blocks and has no mutable tail`() {
        val state = buildStreamingRenderState(
            messageId = "msg-6",
            content = "第一段\n```kotlin\nval x = 1\n```\n最终尾段",
            isStreaming = false,
            isComplete = true,
        )

        assertEquals(state.blocks, state.committedBlocks)
        assertTrue(state.tailBlocks.isEmpty())
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
        assertTrue(state.mathRanges.isEmpty())
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
