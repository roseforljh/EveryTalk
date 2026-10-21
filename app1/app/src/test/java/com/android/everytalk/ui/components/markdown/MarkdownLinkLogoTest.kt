package com.android.everytalk.ui.components.markdown

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import com.android.everytalk.ui.components.streaming.PreparedMarkdownDocument
import com.android.everytalk.ui.components.streaming.PreparedMessage
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLinkLogoTest {

    @Test
    fun `中文紧邻的多个网址都保留链接样式和独立图标`() {
        val urls = listOf(
            "https://github.com/CYBERVERSE-Research/skyline-speeder",
            "https://github.com/Kylin010/tcpfit",
            "http://example.com/path",
        )
        val content = "${urls[0]} 帮我看看这个项目和${urls[1]} 谁更牛逼，另见${urls[2]}"
        val index = markdownLinkLogoIndex(content)
        val rendered = content.buildMarkdownAnnotatedString(
            style = TextStyle.Default,
            flavour = EveryTalkMarkdownFlavourDescriptor,
            annotatorSettings = DefaultAnnotatorSettings(
                linkTextSpanStyle = TextLinkStyles(style = SpanStyle()),
                codeSpanStyle = SpanStyle(),
                annotator = createPreparedMessageMarkdownAnnotator(
                    PreparedMessage(content, emptyMap(), false, 1L),
                    linkLogoHosts = index.requests.map { it.host }.toSet(),
                ),
            ).withRegularMarkdownStrongWeight(),
        )

        assertEquals(listOf("github.com", "example.com"), index.requests.map { it.host })
        assertEquals(3, rendered.getStringAnnotations("androidx.compose.foundation.text.inlineContent", 0, rendered.length).size)
        assertEquals(3, rendered.text.count { it == '↗' })
        assertEquals(urls, rendered.getLinkAnnotations(0, rendered.length)
            .map { (it.item as androidx.compose.ui.text.LinkAnnotation.Url).url }.distinct())
        assertTrue(rendered.text.contains("帮我看看这个项目和"))
    }


    @Test
    fun `静态分块渲染复用消息级链接索引`() {
        val content = "[官网](https://example.com/policy)"
        val state = parseMarkdown(content, lookupLinks = true) as State.Success
        val index = markdownLinkLogoIndex(content)
        val document = PreparedMarkdownDocument(
            state = state,
            nodes = state.node.children,
            linkLogoIndex = index,
        )
        val message = PreparedMessage(
            markdown = content,
            formulas = emptyMap(),
            hasPendingFormula = false,
            contentVersion = 1L,
        )

        val resolved = resolveMarkdownLinkLogoIndex(
            isStreaming = false,
            preparedMessage = message,
            preparedMarkdownDocument = document,
            calculate = { error("静态分块渲染不应重新解析整条消息") },
        )

        assertSame(index, resolved)
    }

    @Test
    fun `已解析文档直接构建链接索引`() {
        val content = "[官网](https://www.example.com/a) https://example.com/b"
        val state = parseMarkdown(content, lookupLinks = true) as State.Success

        assertEquals(
            markdownLinkLogoIndex(content),
            markdownLinkLogoIndex(content, state),
        )
    }

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

        assertTrue(rendered.text.startsWith("\uFFFC\u2060"))
        assertEquals(1, rendered.text.windowed("example.com".length).count { it == "example.com" })
        assertEquals(1, rendered.getStringAnnotations("androidx.compose.foundation.text.inlineContent", 0, rendered.length).size)
    }

    @Test
    fun `未启用图标的渲染不修改链接文本`() {
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
