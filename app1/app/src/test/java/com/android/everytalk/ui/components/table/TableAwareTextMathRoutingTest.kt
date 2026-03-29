package com.android.everytalk.ui.components.table

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TableAwareTextMathRoutingTest {

    @Test
    fun `trailing streaming text with escaped inline math should use markdown renderer`() {
        assertTrue(shouldRenderTrailingStreamingTextWithMarkdown("""calc: \(x+1\)"""))
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
