package com.android.everytalk.ui.components.markdown

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.extendedspans.drawBehind
import com.mikepenz.markdown.model.markdownAnnotator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownLinkStyleTest {
    @get:Rule val composeRule = createComposeRule()

    private fun settings(listener: LinkInteractionListener? = null) = DefaultAnnotatorSettings(
        linkTextSpanStyle = TextLinkStyles(SpanStyle(color = Color.Black)),
        codeSpanStyle = SpanStyle(), highlightSpanStyle = SpanStyle(),
        annotator = markdownAnnotator(),
        linkInteractionListener = listener,
    ).withRegularMarkdownStrongWeight()

    @Test
    fun `链接文字和箭头都保留原点击目标且不会吞掉后续正文`() {
        var opened = ""
        val listener = LinkInteractionListener { opened = (it as LinkAnnotation.Url).url }
        val rendered = "[**查看说明**](https://example.com/path) 后续正文 `code`".buildMarkdownAnnotatedString(
            style = TextStyle.Default, annotatorSettings = settings(listener),
        )
        assertTrue(rendered.text.startsWith("查看说明${EXTERNAL_LINK_SUFFIX} 后续正文"))
        val links = rendered.getLinkAnnotations(0, rendered.length)
        assertEquals(2, links.size)
        links.forEach {
            val link = it.item as LinkAnnotation.Url
            link.linkInteractionListener!!.onClick(link)
            assertEquals("https://example.com/path", opened)
        }
        assertEquals(EXTERNAL_LINK_SUFFIX, rendered.text.substring(links.last().start, links.last().end))
    }

    @Test
    fun `裸网址带箭头而脚注与代码不加箭头`() {
        val rendered = "https://example.com [返回](#note) `https://code.example.com`".buildMarkdownAnnotatedString(
            style = TextStyle.Default, annotatorSettings = settings(),
        )
        assertEquals(1, rendered.text.count { it == '↗' })
        assertTrue(rendered.text.contains("返回"))
        assertTrue(rendered.text.contains("https://code.example.com"))
    }

    @Test
    fun `多行链接逐行绘制点线且不覆盖箭头或普通文字`() {
        val rendered = "[今天的 Codex GitHub 账号级 capacity 异常](https://example.com) 普通文字".buildMarkdownAnnotatedString(
            style = TextStyle.Default, annotatorSettings = settings(),
        )
        val spans = createRegularMarkdownStrongExtendedSpans()
        val extended = spans.extend(rendered)
        lateinit var layout: TextLayoutResult
        composeRule.setContent {
            Text(
                extended,
                modifier = Modifier.width(150.dp).drawBehind(spans),
                style = TextStyle(color = Color.Black, fontSize = 16.sp, lineHeight = 26.sp),
                onTextLayout = { layout = it; spans.onTextLayout(it, Color.Black) },
            )
        }
        composeRule.waitForIdle()
        val lines = markdownLinkUnderlineBounds(layout)
        assertTrue("应有多行点线，实际 $lines，布局 ${layout.size}", lines.size > 1)
        assertTrue("点线不应超出布局：$lines", lines.all { it.width > 0 && it.left >= 0 && it.right <= layout.size.width + 1 })
        val label = rendered.getLinkAnnotations(0, rendered.length).first()
        assertEquals(layout.getLineForOffset(label.end - 1) + 1, lines.size)
        // 按字框核对每行末字，不能仅断言“不超出控件”，那会漏掉整行留白被画线的问题。
        lines.forEachIndexed { index, bounds ->
            var end = minOf(label.end, layout.getLineEnd(index, visibleEnd = true))
            while (end > label.start && rendered[end - 1].isWhitespace()) end--
            assertEquals(layout.getBoundingBox(end - 1).right, bounds.right, 0.1f)
        }
        assertTrue(lines.first().right < layout.size.width)
    }

    @Test
    fun `行内健康检查链接不包含后续JSON和冒号`() {
        val rendered = "主站 https://www.imjos.com/health： `{" + "\"ok\":true}`"
        val annotated = rendered.buildMarkdownAnnotatedString(
            style = TextStyle.Default, annotatorSettings = settings(), flavour = EveryTalkMarkdownFlavourDescriptor,
        )
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertTrue(annotated.text, links.isNotEmpty())
        links.forEach { range ->
            assertFalse(annotated.text.substring(range.start, range.end).contains("ok"))
        }
    }

}
