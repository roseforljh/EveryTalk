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
}
