package com.android.everytalk.ui.components.markdown

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLinkLogoTest {

    @Test
    fun `普通链接和自动链接按 host 去重`() {
        val requests = markdownLinkLogoRequests(
            "[官网](https://www.example.com/a) https://example.com/b"
        )

        assertEquals(1, requests.size)
        assertEquals("example.com", requests.single().host)
    }

    @Test
    fun `引用链接解析定义并忽略脚注内部链接`() {
        val requests = markdownLinkLogoRequests(
            "[官网][official]\n\n" +
                "[official]: https://example.com/policy\n\n" +
                "[¹](everytalk-footnote-reference:1)"
        )

        assertEquals(1, requests.size)
        assertEquals("example.com", requests.single().host)
    }

    @Test
    fun `图片和代码块中的 URL 不创建 Logo`() {
        val requests = markdownLinkLogoRequests(
            "![图片](https://image.example.com/a.png)\n\n" +
                "```text\nhttps://code.example.com\n```"
        )

        assertTrue(requests.isEmpty())
    }

    @Test
    fun `自动链接只插入一个 Logo 占位符`() {
        val content = "https://example.com/policy"
        val rendered = content.buildMarkdownAnnotatedString(
            style = TextStyle.Default,
            annotatorSettings = DefaultAnnotatorSettings(
                linkTextSpanStyle = TextLinkStyles(style = SpanStyle()),
                codeSpanStyle = SpanStyle(),
                annotator = createPreparedMessageMarkdownAnnotator(
                    preparedMessage = com.android.everytalk.ui.components.streaming.PreparedMessage(
                        markdown = content,
                        formulas = emptyMap(),
                        hasPendingFormula = false,
                        contentVersion = 1L,
                    ),
                    linkLogoHosts = setOf("example.com"),
                ),
            ),
        )

        assertTrue(rendered.text.startsWith("example.com"))
        assertEquals(2, rendered.text.windowed("example.com".length).count { it == "example.com" })
    }
}
