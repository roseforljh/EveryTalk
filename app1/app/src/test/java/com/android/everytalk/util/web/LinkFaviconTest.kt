package com.android.everytalk.util.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkFaviconTest {

    @Test
    fun `只为 http 和 https 生成 favicon`() {
        assertEquals(
            "https://www.google.com/s2/favicons?domain=example.com&sz=64",
            linkFaviconUrl("https://www.example.com/path?q=1"),
        )
        assertEquals(
            "https://www.google.com/s2/favicons?domain=example.com&sz=64",
            linkFaviconUrl("http://example.com"),
        )
        assertEquals("", linkFaviconUrl("mailto:test@example.com"))
        assertEquals("", linkFaviconUrl("everytalk-footnote-reference:1"))
    }

    @Test
    fun `host 规范化并去掉尾部点`() {
        assertEquals("example.com", linkHost("HTTPS://WWW.Example.COM./a"))
        assertEquals("", linkHost("not-a-url"))
    }

    @Test
    fun `无 favicon 时首字母占位`() {
        assertEquals("E", linkFaviconInitial("https://example.com/a"))
        assertEquals("X", linkFaviconInitial("mailto:test@example.com", fallback = "X"))
        assertTrue(linkFaviconInitial("", fallback = "?").isNotBlank())
    }
}
