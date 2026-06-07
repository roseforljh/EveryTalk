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
    fun `extracts footnote urls as sources`() {
        val input = """
            这是回答正文。[^1]

            [^1]: https://example.com/source
        """.trimIndent()

        val result = WebMarkdownSourcesExtractor.extract(input)

        assertEquals(1, result.sources.size)
        assertFalse(result.displayText.contains("[^1]"))
    }
}
