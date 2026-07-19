package com.android.everytalk.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPartRecoveryTest {

    @Test
    fun `historical code block parts recover as fenced markdown`() {
        val parts = listOf(
            MarkdownPart.Text(id = "text_0", content = "前文"),
            MarkdownPart.CodeBlock(
                id = "code_1",
                language = "kotlin",
                content = "val sample = \"```\"",
            ),
            MarkdownPart.Text(id = "text_2", content = "\n后文"),
        )

        val recovered = parts.toRecoveredMarkdown()

        assertEquals(
            "前文\n````kotlin\nval sample = \"```\"\n````\n后文",
            recovered,
        )
    }

    @Test
    fun `inline image parts do not inject base64 into recovered markdown`() {
        val parts = listOf(
            MarkdownPart.Text(id = "text_0", content = "图片说明"),
            MarkdownPart.InlineImage(
                id = "image_1",
                mimeType = "image/png",
                base64Data = "sensitive-payload",
            ),
        )

        val recovered = parts.toRecoveredMarkdown()

        assertEquals("图片说明", recovered)
        assertTrue(!recovered.contains("sensitive-payload"))
    }
}
