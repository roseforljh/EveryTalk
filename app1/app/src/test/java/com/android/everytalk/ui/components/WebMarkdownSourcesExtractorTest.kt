package com.android.everytalk.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebMarkdownSourcesExtractorTest {

    @Test
    fun `extracts grok sources section into page sources and removes it from display text`() {
        val input = """
            **是的，Cloudflare 刚刚收购了 VoidZero。** [[1]](https://example.com/news)

            这在前端圈引发了不小的讨论。

            ## Sources
            [grok2api-sources]: #
            - [Cloudflare Acquires VoidZero](https://www.morningstar.com/news/business-wire/20260604108073/cloudflare-acquires-voidzero-to-build-the-future-of-the-ai-native-web)
            - [Cloudflare Acquires Outerbase](https://www.cloudflare.com/press/press-releases/2025/cloudflare-acquires-outerbase-to-expand-developer-experience/)
            - [The Astro Technology Company joins Cloudflare | Astro](https://www.reddit.com/r/programming/comments/1qeilrk/the_astro_technology_company_joins_cloudflare/)
        """.trimIndent()

        val result = WebMarkdownSourcesExtractor.extract(input)

        assertEquals(3, result.sources.size)
        assertEquals("Cloudflare Acquires VoidZero", result.sources[0].title)
        assertEquals(
            "https://www.morningstar.com/news/business-wire/20260604108073/cloudflare-acquires-voidzero-to-build-the-future-of-the-ai-native-web",
            result.sources[0].href
        )
        assertEquals("Cloudflare Acquires Outerbase", result.sources[1].title)
        assertEquals("The Astro Technology Company joins Cloudflare | Astro", result.sources[2].title)
        assertTrue(result.displayText.contains("这在前端圈引发了不小的讨论。"))
        assertFalse(result.displayText.contains("## Sources"))
        assertFalse(result.displayText.contains("grok2api-sources"))
        assertFalse(result.displayText.contains("morningstar.com/news/business-wire"))
    }

    @Test
    fun `does not treat ordinary inline links as page sources`() {
        val input = "官网是 https://example.com，也可以看 [文档](https://docs.example.com)。"

        val result = WebMarkdownSourcesExtractor.extract(input)

        assertEquals(input, result.displayText)
        assertTrue(result.sources.isEmpty())
    }

    @Test
    fun `does not extract numbered citation urls from middle of answer`() {
        val input = """
            我先说明背景。

            [1] https://example.com/inline

            然后继续给出结论，这一段仍然是正文。
        """.trimIndent()

        val result = WebMarkdownSourcesExtractor.extract(input)

        assertEquals(input, result.displayText)
        assertTrue(result.sources.isEmpty())
    }

    @Test
    fun `extracts numbered citation urls without sources header`() {
        val input = """
            这是回答正文。

            [1] https://example.com/news
            [2] Example https://example.com/other
        """.trimIndent()

        val result = WebMarkdownSourcesExtractor.extract(input)

        assertEquals(2, result.sources.size)
        assertEquals("https://example.com/news", result.sources[0].href)
        assertFalse(result.displayText.contains("https://example.com/news"))
        assertEquals("这是回答正文。", result.displayText.trim())
    }

    @Test
    fun `trims chinese punctuation from citation urls`() {
        val input = """
            这是回答正文。

            [1] https://example.com/news。
            [2] https://example.com/other，
        """.trimIndent()

        val result = WebMarkdownSourcesExtractor.extract(input)

        assertEquals("https://example.com/news", result.sources[0].href)
        assertEquals("https://example.com/other", result.sources[1].href)
    }

    @Test
    fun `keeps ordinary markdown footnotes in display text`() {
        val input = """
            这是回答正文。[^note]

            [^note]: 这是普通脚注说明。
        """.trimIndent()

        val result = WebMarkdownSourcesExtractor.extract(input)

        assertEquals(input, result.displayText)
        assertTrue(result.sources.isEmpty())
    }

    @Test
    fun `keeps markdown link footnotes in display text`() {
        val input = """
            这是回答正文。[^2]

            [^2]: 参见 [完整文档](https://example.com/docs)。
        """.trimIndent()

        val result = WebMarkdownSourcesExtractor.extract(input)

        assertEquals(input, result.displayText)
        assertTrue(result.sources.isEmpty())
    }

    @Test
    fun `keeps numeric pure url footnotes in display text`() {
        val input = """
            这是回答正文。[^1]

            [^1]: https://example.com/source
        """.trimIndent()

        val result = WebMarkdownSourcesExtractor.extract(input)

        assertEquals(input, result.displayText)
        assertTrue(result.sources.isEmpty())
    }

    @Test
    fun `extracts footnote shaped urls inside explicit sources section`() {
        val input = """
            这是回答正文。

            ## Sources
            [^1]: https://example.com/source
        """.trimIndent()

        val result = WebMarkdownSourcesExtractor.extract(input)

        assertEquals("这是回答正文。", result.displayText)
        assertEquals(1, result.sources.size)
        assertEquals("https://example.com/source", result.sources.single().href)
    }

    @Test
    fun `keeps sources shaped text inside fenced code`() {
        val input = """
            正文。

            ```markdown
            ## Sources
            [1] https://example.com/code
            ```
        """.trimIndent()

        val result = WebMarkdownSourcesExtractor.extract(input)

        assertEquals(input, result.displayText)
        assertTrue(result.sources.isEmpty())
    }

    @Test
    fun `keeps sources shaped text inside indented code`() {
        val input = "正文。\n\n    ## Sources\n    [1] https://example.com/code"

        val result = WebMarkdownSourcesExtractor.extract(input)

        assertEquals(input, result.displayText)
        assertTrue(result.sources.isEmpty())
    }

    @Test
    fun `keeps sources shaped text inside details`() {
        val input = """
            正文。

            <details>
            <summary>引用示例</summary>
            ## Sources
            [1] https://example.com/details
            </details>
        """.trimIndent()

        val result = WebMarkdownSourcesExtractor.extract(input)

        assertEquals(input, result.displayText)
        assertTrue(result.sources.isEmpty())
    }

    @Test
    fun `extracts real sources section after fenced code`() {
        val input = """
            ```text
            ## Sources
            [1] https://example.com/code
            ```

            ## Sources
            [真实来源](https://example.com/real)
        """.trimIndent()

        val result = WebMarkdownSourcesExtractor.extract(input)

        assertEquals(1, result.sources.size)
        assertEquals("https://example.com/real", result.sources.single().href)
        assertTrue(result.displayText.contains("https://example.com/code"))
        assertFalse(result.displayText.contains("https://example.com/real"))
    }

    @Test
    fun `extracts real sources section after details`() {
        val input = """
            <details>
            <summary>引用示例</summary>
            [1] https://example.com/details
            </details>

            ## Sources
            [真实来源](https://example.com/real)
        """.trimIndent()

        val result = WebMarkdownSourcesExtractor.extract(input)

        assertEquals(1, result.sources.size)
        assertEquals("https://example.com/real", result.sources.single().href)
        assertTrue(result.displayText.contains("https://example.com/details"))
        assertFalse(result.displayText.contains("https://example.com/real"))
    }

    @Test
    fun `inline code details literal does not hide later sources section`() {
        val input = """
            字面标签 `<details>` 只是示例。

            ## Sources
            [真实来源](https://example.com/real)
        """.trimIndent()

        val result = WebMarkdownSourcesExtractor.extract(input)

        assertEquals("https://example.com/real", result.sources.single().href)
        assertEquals("字面标签 `<details>` 只是示例。", result.displayText)
    }

    @Test
    fun `escaped details literal does not hide later sources section`() {
        val input = """
            字面标签 \<details> 只是示例。

            ## Sources
            [真实来源](https://example.com/real)
        """.trimIndent()

        val result = WebMarkdownSourcesExtractor.extract(input)

        assertEquals("https://example.com/real", result.sources.single().href)
        assertEquals("字面标签 \\<details> 只是示例。", result.displayText)
    }

    @Test
    fun `keeps explicit sources section when ordinary body follows it`() {
        val input = """
            ## Sources
            [来源](https://example.com/source)

            这是来源之后仍需保留的正文。
        """.trimIndent()

        val result = WebMarkdownSourcesExtractor.extract(input)

        assertEquals(input, result.displayText)
        assertTrue(result.sources.isEmpty())
    }

    @Test
    fun `keeps explicit sources section when fenced code follows it`() {
        val input = """
            ## Sources
            [来源](https://example.com/source)

            ```text
            这是仍需保留的代码。
            ```
        """.trimIndent()

        val result = WebMarkdownSourcesExtractor.extract(input)

        assertEquals(input, result.displayText)
        assertTrue(result.sources.isEmpty())
    }

    @Test
    fun `keeps sources shaped text inside mixed space tab indented code`() {
        val input = "正文。\n\n \t## Sources\n \t[1] https://example.com/code"

        val result = WebMarkdownSourcesExtractor.extract(input)

        assertEquals(input, result.displayText)
        assertTrue(result.sources.isEmpty())
    }

    @Test
    fun `keeps all content after unclosed fence`() {
        val input = """
            正文。

            ```markdown
            ## Sources
            [1] https://example.com/code
        """.trimIndent()

        val result = WebMarkdownSourcesExtractor.extract(input)

        assertEquals(input, result.displayText)
        assertTrue(result.sources.isEmpty())
    }

    @Test
    fun `keeps all content after unclosed details`() {
        val input = """
            正文。

            <details>
            ## Sources
            [1] https://example.com/details
        """.trimIndent()

        val result = WebMarkdownSourcesExtractor.extract(input)

        assertEquals(input, result.displayText)
        assertTrue(result.sources.isEmpty())
    }

    @Test
    fun `keeps crlf content unchanged after unclosed fence`() {
        val input = "正文第一行。\r\n正文第二行。\r\n\r\n```markdown\r\n[1] https://example.com/code"

        val result = WebMarkdownSourcesExtractor.extract(input)

        assertEquals(input, result.displayText)
        assertTrue(result.sources.isEmpty())
    }
}
