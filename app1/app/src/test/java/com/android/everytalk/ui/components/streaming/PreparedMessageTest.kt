package com.android.everytalk.ui.components.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedMessageTest {

    @Test
    fun `closed inline formula becomes a content addressed request`() {
        val prepared = StreamBlockParser.prepareMessage(
            content = "质能方程 ${'$'}E = mc^2${'$'}。",
            messageId = "message-1",
            contentVersion = 7L,
        )

        val formula = prepared.formulas.values.single()
        assertEquals("E = mc^2", formula.latex)
        assertEquals(FormulaDisplayMode.INLINE, formula.displayMode)
        assertEquals(7L, formula.contentVersion)
        assertEquals(formula, prepared.formulas.getValue(formula.id))
        assertTrue(prepared.markdown.contains("everytalk-math-inline:${formula.id}"))
        assertFalse(prepared.markdown.contains("${'$'}E = mc^2${'$'}"))
        assertFalse(prepared.hasPendingFormula)
    }

    @Test
    fun `closed block formula removes escaped delimiters and uses a block placeholder`() {
        val prepared = StreamBlockParser.prepareMessage(
            content = "前文\n\\[\r\n  x^2 + y^2  \r\n\\]\n后文",
            messageId = "message-2",
            contentVersion = 8L,
        )

        val formula = prepared.formulas.values.single()
        assertEquals("x^2 + y^2", formula.latex)
        assertEquals(FormulaDisplayMode.BLOCK, formula.displayMode)
        assertTrue(prepared.markdown.contains("```$BLOCK_FORMULA_FENCE_LANGUAGE\n${formula.id}\n```"))
        assertFalse(prepared.markdown.contains("\\["))
        assertFalse(prepared.hasPendingFormula)
    }

    @Test
    fun `inline code is preserved and dollar signs inside it are not formulas`() {
        val prepared = StreamBlockParser.prepareMessage(
            content = "命令 `echo ${'$'}HOME`，公式 ${'$'}x+1${'$'}。",
            messageId = "message-3",
            contentVersion = 9L,
        )

        val formula = prepared.formulas.values.single()
        assertEquals("x+1", formula.latex)
        assertTrue(prepared.markdown.contains("`echo ${'$'}HOME`"))
        assertFalse(prepared.hasPendingFormula)
    }

    @Test
    fun `unclosed formula stays as raw markdown and is marked pending`() {
        val content = "流式输出到这里 ${'$'}\\frac{a}{b}"
        val prepared = StreamBlockParser.prepareMessage(
            content = content,
            messageId = "message-4",
            contentVersion = 10L,
        )

        assertEquals(content, prepared.markdown)
        assertTrue(prepared.formulas.isEmpty())
        assertTrue(prepared.hasPendingFormula)
        assertEquals(10L, prepared.contentVersion)
    }

    @Test
    fun `formula id depends only on normalized latex and display mode`() {
        val dollarInline = StreamBlockParser.prepareMessage(
            content = "A ${'$'}  E = mc^2  ${'$'} B",
            messageId = "first-message",
            contentVersion = 11L,
        ).formulas.values.single()
        val escapedInline = StreamBlockParser.prepareMessage(
            content = "\\(E = mc^2\\)",
            messageId = "second-message",
            contentVersion = 12L,
        ).formulas.values.single()
        val block = StreamBlockParser.prepareMessage(
            content = "${'$'}${'$'}E = mc^2${'$'}${'$'}",
            messageId = "third-message",
            contentVersion = 13L,
        ).formulas.values.single()

        assertEquals("7a605609553c4693aa5e9516984cd42a95c2a792cf6f51ae7d06f8ad9df504af", dollarInline.id)
        assertEquals(dollarInline.id, escapedInline.id)
        assertEquals(dollarInline.latex, escapedInline.latex)
        assertTrue(dollarInline.id != block.id)
    }

    @Test
    fun `fenced code and currency remain markdown while real formula is extracted`() {
        val content = """
            | 价格 | ${'$'}20 / 月 |

            ```kotlin
            val template = "${'$'}x${'$'}"
            ```

            公式：${'$'}y+1${'$'}
        """.trimIndent()
        val prepared = StreamBlockParser.prepareMessage(
            content = content,
            messageId = "message-5",
            contentVersion = 14L,
        )

        assertEquals(listOf("y+1"), prepared.formulas.values.map { it.latex })
        assertTrue(prepared.markdown.contains("| 价格 | ${'$'}20 / 月 |"))
        assertTrue(prepared.markdown.contains("val template = \"${'$'}x${'$'}\""))
        assertFalse(prepared.hasPendingFormula)
    }

    @Test
    fun `escaped dollar inside formula does not close the formula`() {
        val prepared = StreamBlockParser.prepareMessage(
            content = "公式 ${'$'}a \\${'$'} b${'$'} 完成",
            messageId = "message-6",
            contentVersion = 15L,
        )

        assertEquals("a \\${'$'} b", prepared.formulas.values.single().latex)
        assertFalse(prepared.hasPendingFormula)
    }

    @Test
    fun `currency score time version and markdown link stay ordinary markdown`() {
        val content =
            "价格 ${'$'}12，USD 12，比分 1:0，时间 03:30，版本 v1.2.3，[链接](https://example.com/${'$'}value)"

        val prepared = StreamBlockParser.prepareMessage(
            content = content,
            messageId = "ordinary-symbols",
            contentVersion = 16L,
        )

        assertEquals(content, prepared.markdown)
        assertTrue(prepared.formulas.isEmpty())
        assertFalse(prepared.hasPendingFormula)
    }

    @Test
    fun `table cell inline formula uses the same stable placeholder`() {
        val prepared = StreamBlockParser.prepareMessage(
            content = "| 名称 | 公式 |\n|:---|:---:|\n| 质能方程 | ${'$'}E=mc^2${'$'} |",
            messageId = "table-formula",
            contentVersion = 17L,
        )

        val formula = prepared.formulas.values.single()
        assertEquals("E=mc^2", formula.latex)
        assertTrue(prepared.markdown.contains("| 质能方程 | ![math](everytalk-math-inline:${formula.id}) |"))
        assertFalse(prepared.hasPendingFormula)
    }
}
