package com.android.everytalk.ui.components.table

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TableCellImageMarkdownTest {

    @Test
    fun `standalone image cell parses alt and url`() {
        val image = parseTableCellImageMarkdown("""![预览](https://example.com/a.png)""")

        requireNotNull(image)
        assertEquals("预览", image.alt)
        assertEquals("https://example.com/a.png", image.url)
    }

    @Test
    fun `standalone image cell with title parses alt and url`() {
        val image = parseTableCellImageMarkdown("""![预览](https://example.com/a.png "Preview")""")

        requireNotNull(image)
        assertEquals("预览", image.alt)
        assertEquals("https://example.com/a.png", image.url)
    }

    @Test
    fun `standalone image cell with parenthesized url parses full url`() {
        val image = parseTableCellImageMarkdown("""![预览](https://example.com/wiki/A_(B).png)""")

        requireNotNull(image)
        assertEquals("预览", image.alt)
        assertEquals("https://example.com/wiki/A_(B).png", image.url)
    }

    @Test
    fun `standalone image cell with angle bracket destination strips brackets from url`() {
        val image = parseTableCellImageMarkdown("""![预览](<https://example.com/wiki/A_(B).png>)""")

        requireNotNull(image)
        assertEquals("预览", image.alt)
        assertEquals("https://example.com/wiki/A_(B).png", image.url)
    }

    @Test
    fun `mixed prose and image cell is not treated as image cell`() {
        assertNull(parseTableCellImageMarkdown("""前缀 ![预览](https://example.com/a.png)"""))
    }

    @Test
    fun `mixed prose and image cell parses into native parts`() {
        val parts = parseTableCellMarkdownParts("""前缀 **重点** ![预览](https://example.com/a.png) 后缀""")

        requireNotNull(parts)
        assertEquals(3, parts.size)
        assertEquals(TableCellMarkdownPart.Text("""前缀 **重点**"""), parts[0])
        assertEquals(
            TableCellMarkdownPart.Image(
                alt = "预览",
                url = "https://example.com/a.png",
            ),
            parts[1],
        )
        assertEquals(TableCellMarkdownPart.Text("后缀"), parts[2])
    }

    @Test
    fun `cell without image has no mixed markdown parts`() {
        assertNull(parseTableCellMarkdownParts("""只有 **文字**"""))
    }

    @Test
    fun `mixed prose and image cell parser keeps parenthesized image url`() {
        val parts = parseTableCellMarkdownParts("""前缀 ![预览](https://example.com/wiki/A_(B).png)""")

        requireNotNull(parts)
        assertFalse(parts.isEmpty())
        assertEquals(
            TableCellMarkdownPart.Image(
                alt = "预览",
                url = "https://example.com/wiki/A_(B).png",
            ),
            parts[1],
        )
    }
}
