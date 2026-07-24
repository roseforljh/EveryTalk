package com.android.everytalk.ui.components.markdown

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import org.intellij.markdown.MarkdownElementTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `单独自动链接段落使用外置 Logo 布局`() {
        val content = "https://x.com/i/status/2080141498658762797"
        val state = parseMarkdown(content, lookupLinks = true) as State.Success
        val paragraph = state.node.children.single { it.type == MarkdownElementTypes.PARAGRAPH }
        val index = markdownLinkLogoIndex(content)

        assertEquals(
            "x.com",
            markdownSingleAutolinkLogoRequest(
                content = content,
                node = paragraph,
                definitions = index.definitions,
                requests = index.requests,
            )?.host,
        )
    }

    @Test
    fun `带前后文字的自动链接继续使用原行内布局`() {
        val content = "来源：https://x.com/i/status/2080141498658762797"
        val state = parseMarkdown(content, lookupLinks = true) as State.Success
        val paragraph = state.node.children.single { it.type == MarkdownElementTypes.PARAGRAPH }
        val index = markdownLinkLogoIndex(content)

        assertNull(
            markdownSingleAutolinkLogoRequest(
                content = content,
                node = paragraph,
                definitions = index.definitions,
                requests = index.requests,
            ),
        )
    }

    @Test
    fun `外置 Logo 布局不修改链接文本`() {
        val content = "https://x.com/i/status/2080141498658762797"
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
                ),
            ),
        )

        assertEquals(content, rendered.text)
    }
}
