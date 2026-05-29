package com.android.everytalk.ui.components.table

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
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
    fun `bold can contain link annotation`() {
        val parsed = InlineMarkdownParser.parse("**粗体里的 [链接](https://example.com)**")

        assertEquals("粗体里的 链接", parsed.text)
        assertTrue(parsed.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertEquals(
            "https://example.com",
            parsed.getStringAnnotations("URL", 0, parsed.length).single().item,
        )
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
}
