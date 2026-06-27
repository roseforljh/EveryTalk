package com.android.everytalk.ui.components.table

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TableAwareTextMathRoutingTest {

    @Test
    fun `table cell single dollar math should use native inline math path`() {
        assertTrue(shouldRenderTableCellNativeInlineMath("公式 ${'$'}x+1${'$'} 成立", usePlainText = false))
    }

    @Test
    fun `table cell escaped inline math should use native inline math path`() {
        assertTrue(shouldRenderTableCellNativeInlineMath("""公式 \(x+1\) 成立""", usePlainText = false))
    }

    @Test
    fun `table cell block math should use native block math path`() {
        assertTrue(shouldRenderTableCellNativeBlockMath("""$$ x+1 $$""", usePlainText = false))
        assertTrue(shouldRenderTableCellNativeBlockMath("""\[x+1\]""", usePlainText = false))
    }

    @Test
    fun `mixed table cell block math should keep markdown fallback path`() {
        assertTrue(shouldRenderTableCellNativeMixedMath("""公式 $$ x+1 $$ 成立""", usePlainText = false))
        assertTrue(shouldRenderTableCellNativeMixedMath("""公式 \[x+1\] 成立""", usePlainText = false))
    }

    @Test
    fun `raw table cell block math should use native mixed math path`() {
        assertTrue(shouldRenderTableCellNativeMixedMath("""公式 $$ x+1""", usePlainText = false))
        assertTrue(shouldRenderTableCellNativeMixedMath("""公式 \[x+1""", usePlainText = false))
    }

    @Test
    fun `standalone raw table cell block math should use native mixed math path`() {
        assertTrue(shouldRenderTableCellNativeMixedMath("""$$ x+1""", usePlainText = false))
        assertTrue(shouldRenderTableCellNativeMixedMath("""\[x+1""", usePlainText = false))
    }

    @Test
    fun `table cell code and math should use native mixed math path`() {
        assertTrue(
            shouldRenderTableCellNativeMixedMath(
                "```kotlin\nval x = 1\n```\n公式 ${'$'}x+1${'$'} 成立",
                usePlainText = false,
            )
        )
    }

    @Test
    fun `plain text table cell should not use native mixed math path`() {
        assertFalse(shouldRenderTableCellNativeMixedMath("""公式 $$ x+1 $$ 成立""", usePlainText = true))
    }

    @Test
    fun `plain text table cell should not use native block math path`() {
        assertFalse(shouldRenderTableCellNativeBlockMath("""$$ x+1 $$""", usePlainText = true))
    }

    @Test
    fun `plain text table cell should not use native inline math path`() {
        assertFalse(shouldRenderTableCellNativeInlineMath("公式 ${'$'}x+1${'$'} 成立", usePlainText = true))
    }

    @Test
    fun `trailing streaming text with escaped inline math should not use markdown renderer`() {
        assertFalse(shouldRenderTrailingStreamingTextWithMarkdown("""calc: \(x+1\)"""))
    }

    @Test
    fun `trailing streaming text with block math should not use markdown renderer`() {
        assertFalse(shouldRenderTrailingStreamingTextWithMarkdown("$$ x+1 $$"))
        assertFalse(shouldRenderTrailingStreamingTextWithMarkdown("""\[x+1\]"""))
    }

    @Test
    fun `force native block math should stay on stable path during streaming`() {
        assertTrue(
            shouldRenderStableNativeMathPart(
                isBlockMath = true,
                isStreaming = true,
                forceNativeRenderer = true,
                preferStableNativeRenderer = false,
                canRenderNatively = true,
            )
        )
    }

    @Test
    fun `prefer stable block math should stay on stable path during streaming`() {
        assertTrue(
            shouldRenderStableNativeMathPart(
                isBlockMath = true,
                isStreaming = true,
                forceNativeRenderer = false,
                preferStableNativeRenderer = true,
                canRenderNatively = true,
            )
        )
    }

    @Test
    fun `ordinary native block math should still wait for non streaming before stable path`() {
        assertFalse(
            shouldRenderStableNativeMathPart(
                isBlockMath = true,
                isStreaming = true,
                forceNativeRenderer = false,
                preferStableNativeRenderer = false,
                canRenderNatively = true,
            )
        )
        assertTrue(
            shouldRenderStableNativeMathPart(
                isBlockMath = true,
                isStreaming = false,
                forceNativeRenderer = false,
                preferStableNativeRenderer = false,
                canRenderNatively = true,
            )
        )
    }
}
