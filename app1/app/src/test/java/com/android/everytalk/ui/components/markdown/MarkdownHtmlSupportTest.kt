package com.android.everytalk.ui.components.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.parseMarkdown
import com.android.everytalk.ui.components.streaming.FormulaDisplayMode
import com.android.everytalk.ui.components.streaming.FormulaRequest
import com.android.everytalk.ui.components.streaming.INLINE_FORMULA_SCHEME
import com.android.everytalk.ui.components.streaming.PreparedMessage
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownHtmlSupportTest {

    @Test
    fun `named numeric and non bmp html entities decode after parsing`() {
        val input = "&copy; 2026 &amp; &lt; &gt; &#169; &#x1F600; &unknown;"

        assertEquals(
            "© 2026 & < > © 😀 &unknown;",
            decodeMarkdownHtmlEntities(input),
        )
    }

    @Test
    fun `entity encoded markdown marker never becomes emphasis syntax`() {
        val input = "&ast;这不是斜体&ast;"
        val root = (parseMarkdown(input) as State.Success).node

        assertFalse(root.containsType(MarkdownElementTypes.EMPH))
        assertEquals("*这不是斜体*", decodeMarkdownHtmlEntities(input))
    }

    @Test
    fun `safe span style accepts bounded color and bold properties`() {
        val style = requireNotNull(parseSafeSpanStyle("color: red; font-weight: bold"))

        assertEquals(Color.Red, style.color)
        assertEquals(FontWeight.Bold, style.fontWeight)
    }

    @Test
    fun `safe span style rejects executable or unknown css`() {
        assertNull(parseSafeSpanStyle("color: url(https://example.com/a.png)"))
        assertNull(parseSafeSpanStyle("position: fixed"))
        assertNull(parseSafeSpanStyle("color: var(--danger)"))
    }

    @Test
    fun `balanced safe span applies style while invalid attributes stay plain text`() {
        val safe = renderInline(
            "<span style=\"color:red;font-weight:bold\">红色粗体</span>"
        )
        val invalid = renderInline(
            "<span onclick=\"alert(1)\">普通正文</span>"
        )

        assertEquals("红色粗体", safe.text)
        assertTrue(
            safe.spanStyles.any { range ->
                range.item.color == Color.Red && range.item.fontWeight == FontWeight.Bold
            }
        )
        assertEquals("普通正文", invalid.text)
        assertFalse(invalid.spanStyles.any { it.item.color == Color.Red })
    }

    @Test
    fun `details summary annotator shares entity span and inline math behavior`() {
        val id = "a".repeat(64)
        val formula = FormulaRequest(
            id = id,
            latex = "x^2",
            displayMode = FormulaDisplayMode.INLINE,
            contentVersion = 7L,
        )
        val prepared = PreparedMessage(
            markdown = "",
            formulas = mapOf(id to formula),
            hasPendingFormula = false,
            contentVersion = 7L,
        )
        val link = INLINE_FORMULA_SCHEME + id
        val input = "&copy; <span style=\"color:red\">红色</span> ![math]($link)"
        val rendered = input.buildMarkdownAnnotatedString(
            style = TextStyle.Default,
            annotatorSettings = DefaultAnnotatorSettings(
                linkTextSpanStyle = TextLinkStyles(style = SpanStyle()),
                codeSpanStyle = SpanStyle(),
                annotator = createPreparedMessageMarkdownAnnotator(prepared),
            ),
        )

        assertEquals("© 红色 ${'$'}x^2${'$'}", rendered.text)
        assertTrue(rendered.spanStyles.any { it.item.color == Color.Red })
        assertTrue(
            rendered.getStringAnnotations(start = 0, end = rendered.length)
                .any { it.item == link }
        )
    }

    @Test
    fun `streaming tail node outside current snapshot is consumed without crashing`() {
        val builder = AnnotatedString.Builder()
        val staleTailNode = TestAstNode(
            type = MarkdownTokenTypes.TEXT,
            startOffset = 3,
            endOffset = 5,
        )

        assertTrue(
            builder.annotateMarkdownHtmlCompatibility(
                content = "abc",
                child = staleTailNode,
            )
        )
        assertEquals("", builder.toAnnotatedString().text)
    }

    @Test
    fun `inline image destination outside current snapshot is ignored`() {
        val staleDestination = TestAstNode(
            type = MarkdownElementTypes.LINK_DESTINATION,
            startOffset = 3,
            endOffset = 5,
        )
        val image = TestAstNode(
            type = MarkdownElementTypes.IMAGE,
            startOffset = 0,
            endOffset = 5,
            children = listOf(staleDestination),
        )

        assertNull(image.inlineImageDestination("abc"))
    }

    @Test
    fun `image paragraph outside current snapshot keeps base style`() {
        val image = TestAstNode(
            type = MarkdownElementTypes.IMAGE,
            startOffset = 0,
            endOffset = 5,
        )
        val paragraph = TestAstNode(
            type = MarkdownElementTypes.PARAGRAPH,
            startOffset = 0,
            endOffset = 5,
            children = listOf(image),
        )
        val baseStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)

        assertEquals(baseStyle, markdownParagraphStyle("abc", paragraph, baseStyle))
    }

    @Test
    fun `inline link outside current snapshot is ignored`() {
        val destination = TestAstNode(
            type = MarkdownElementTypes.LINK_DESTINATION,
            startOffset = 3,
            endOffset = 5,
        )
        val link = TestAstNode(
            type = MarkdownElementTypes.INLINE_LINK,
            startOffset = 0,
            endOffset = 5,
            children = listOf(destination),
        )
        destination.parent = link
        val root = TestAstNode(
            type = MarkdownElementTypes.MARKDOWN_FILE,
            startOffset = 0,
            endOffset = 5,
            children = listOf(link),
        )

        assertTrue(footnoteTargets(content = "abc", node = root).isEmpty())
    }

    private fun renderInline(input: String) = input.buildMarkdownAnnotatedString(
        style = TextStyle.Default,
        annotatorSettings = DefaultAnnotatorSettings(
            linkTextSpanStyle = TextLinkStyles(style = SpanStyle()),
            codeSpanStyle = SpanStyle(),
            annotator = markdownAnnotator { content, child ->
                annotateMarkdownHtmlCompatibility(content, child)
            },
        ),
    )

    private fun org.intellij.markdown.ast.ASTNode.containsType(
        type: org.intellij.markdown.IElementType,
    ): Boolean = this.type == type || children.any { it.containsType(type) }

    private class TestAstNode(
        override val type: org.intellij.markdown.IElementType,
        override val startOffset: Int,
        override val endOffset: Int,
        override var parent: ASTNode? = null,
        override val children: List<ASTNode> = emptyList(),
    ) : ASTNode
}
