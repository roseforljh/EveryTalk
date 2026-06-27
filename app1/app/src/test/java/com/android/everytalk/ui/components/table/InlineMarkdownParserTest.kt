package com.android.everytalk.ui.components.table

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineMarkdownParserTest {

    @Test
    fun `bold can contain inline code`() {
        val parsed = InlineMarkdownParser.parse("**粗体里的 `code`**")

        assertEquals("粗体里的 code", parsed.text)
        assertTrue(parsed.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(parsed.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun `inline code is gray bold compact without background`() {
        val codeColor = Color(0xFF4F5661)
        val codeFontSize = 14.72.sp
        val parsed = InlineMarkdownParser.parse(
            text = "Use `http2` now",
            codeColor = codeColor,
            codeFontSize = codeFontSize,
        )

        val codeStyle = parsed.spanStyles.single {
            it.item.fontFamily == FontFamily.Monospace
        }.item

        assertEquals("Use http2 now", parsed.text)
        assertEquals(Color.Unspecified, codeStyle.background)
        assertEquals(FontWeight.Bold, codeStyle.fontWeight)
        assertEquals(codeColor, codeStyle.color)
        assertEquals(codeFontSize, codeStyle.fontSize)
    }

    @Test
    fun `bold can contain link annotation`() {
        val parsed = InlineMarkdownParser.parse("**粗体里的 [链接](https://example.com)**")

        assertEquals("粗体里的 链接 ↗", parsed.text)
        assertTrue(parsed.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertEquals(
            "https://example.com",
            parsed.getStringAnnotations("URL", 0, parsed.length).single().item,
        )
    }

    @Test
    fun `markdown link with double quoted title uses only url annotation`() {
        val parsed = InlineMarkdownParser.parse("""Read [docs](https://example.com/docs "文档") now""")

        assertEquals("Read docs ↗ now", parsed.text)
        assertEquals(
            "https://example.com/docs",
            parsed.getStringAnnotations("URL", 0, parsed.length).single().item,
        )
    }

    @Test
    fun `markdown link with single quoted title uses only url annotation`() {
        val parsed = InlineMarkdownParser.parse("Read [docs](https://example.com/docs '文档') now")

        assertEquals("Read docs ↗ now", parsed.text)
        assertEquals(
            "https://example.com/docs",
            parsed.getStringAnnotations("URL", 0, parsed.length).single().item,
        )
    }

    @Test
    fun `markdown link with parenthesized title uses only url annotation`() {
        val parsed = InlineMarkdownParser.parse("Read [docs](https://example.com/docs (文档)) now")

        assertEquals("Read docs ↗ now", parsed.text)
        assertEquals(
            "https://example.com/docs",
            parsed.getStringAnnotations("URL", 0, parsed.length).single().item,
        )
    }

    @Test
    fun `markdown link with parenthesized url keeps full url annotation`() {
        val parsed = InlineMarkdownParser.parse("See [page](https://example.com/wiki/A_(B)) now")

        assertEquals("See page ↗ now", parsed.text)
        assertEquals(
            "https://example.com/wiki/A_(B)",
            parsed.getStringAnnotations("URL", 0, parsed.length).single().item,
        )
    }

    @Test
    fun `markdown link with angle bracket destination strips brackets from url annotation`() {
        val parsed = InlineMarkdownParser.parse("See [page](<https://example.com/wiki/A_(B)>) now")

        assertEquals("See page ↗ now", parsed.text)
        assertEquals(
            "https://example.com/wiki/A_(B)",
            parsed.getStringAnnotations("URL", 0, parsed.length).single().item,
        )
    }

    @Test
    fun `autolink becomes url annotation`() {
        val parsed = InlineMarkdownParser.parse("Open <https://example.com> now")

        assertEquals("Open https://example.com ↗ now", parsed.text)
        assertEquals(
            "https://example.com",
            parsed.getStringAnnotations("URL", 0, parsed.length).single().item,
        )
    }

    @Test
    fun `bare url becomes url annotation`() {
        val parsed = InlineMarkdownParser.parse("Open https://example.com/docs now")

        assertEquals("Open https://example.com/docs ↗ now", parsed.text)
        assertEquals(
            "https://example.com/docs",
            parsed.getStringAnnotations("URL", 0, parsed.length).single().item,
        )
    }

    @Test
    fun `html entities decode in inline markdown`() {
        val parsed = InlineMarkdownParser.parse("A &amp; B &#35; &#x21;")

        assertEquals("A & B # !", parsed.text)
    }

    @Test
    fun `common named html entities decode in inline markdown`() {
        val parsed = InlineMarkdownParser.parse("A &ndash; B &hellip; &copy; &reg; &trade;")

        assertEquals("A \u2013 B \u2026 \u00A9 \u00AE \u2122", parsed.text)
    }

    @Test
    fun `list separator html entities decode in inline markdown`() {
        val parsed = InlineMarkdownParser.parse("Alpha &middot; Beta &bull; Gamma")

        assertEquals("Alpha \u00B7 Beta \u2022 Gamma", parsed.text)
    }

    @Test
    fun `typographic and math html entities decode in inline markdown`() {
        val parsed = InlineMarkdownParser.parse("&ldquo;A&rdquo; &minus; &times; &divide; &rarr; &larr;")

        assertEquals("\u201CA\u201D \u2212 \u00D7 \u00F7 \u2192 \u2190", parsed.text)
    }

    @Test
    fun `extended typographic and math html entities decode in inline markdown`() {
        val parsed = InlineMarkdownParser.parse("&lsquo;A&rsquo; &plusmn; &deg; &le; &ge; &ne;")

        assertEquals("\u2018A\u2019 \u00B1 \u00B0 \u2264 \u2265 \u2260", parsed.text)
    }

    @Test
    fun `greek named html entities decode in inline markdown`() {
        val parsed = InlineMarkdownParser.parse("&alpha; &beta; &gamma; &Delta; &Omega;")

        assertEquals("\u03B1 \u03B2 \u03B3 \u0394 \u03A9", parsed.text)
    }

    @Test
    fun `triple emphasis produces bold italic text`() {
        val parsed = InlineMarkdownParser.parse("***粗斜体***")

        assertEquals("粗斜体", parsed.text)
        assertTrue(parsed.spanStyles.any {
            it.item.fontWeight == FontWeight.Bold && it.item.fontStyle == FontStyle.Italic
        })
    }

    @Test
    fun `strike can contain bold text`() {
        val parsed = InlineMarkdownParser.parse("~~删除线里的 **粗体**~~")

        assertEquals("删除线里的 粗体", parsed.text)
        assertTrue(parsed.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
        assertTrue(parsed.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `underline token can contain bold text`() {
        val parsed = InlineMarkdownParser.parse("++下划线里的 **粗体**++")

        assertEquals("下划线里的 粗体", parsed.text)
        assertTrue(parsed.spanStyles.any { it.item.textDecoration == TextDecoration.Underline })
        assertTrue(parsed.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `highlight token can contain bold text`() {
        val parsed = InlineMarkdownParser.parse("==高亮里的 **粗体**==")

        assertEquals("高亮里的 粗体", parsed.text)
        assertTrue(parsed.spanStyles.any { it.item.background != Color.Unspecified && it.item.background != Color.Transparent })
        assertTrue(parsed.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `superscript token can contain bold text`() {
        val parsed = InlineMarkdownParser.parse("x^^2 **n**^^")

        assertEquals("x2 n", parsed.text)
        assertTrue(parsed.spanStyles.any { it.item.baselineShift == BaselineShift.Superscript })
        assertTrue(parsed.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `subscript token can contain bold text`() {
        val parsed = InlineMarkdownParser.parse("H,,2 **n**,,O")

        assertEquals("H2 nO", parsed.text)
        assertTrue(parsed.spanStyles.any { it.item.baselineShift == BaselineShift.Subscript })
        assertTrue(parsed.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `unclosed token stays literal`() {
        val parsed = InlineMarkdownParser.parse("这是 **未闭合")

        assertEquals("这是 **未闭合", parsed.text)
        assertTrue(parsed.spanStyles.none { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `escaped markers stay literal`() {
        val parsed = InlineMarkdownParser.parse("""这是 \*\*不是粗体\*\*""")

        assertEquals("这是 **不是粗体**", parsed.text)
        assertTrue(parsed.spanStyles.none { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `multiple currency dollar values are not treated as math`() {
        assertFalse(InlineMarkdownParser.containsMath("Free ${'$'}0, Pro ${'$'}20, credit ${'$'}30"))
    }
}
