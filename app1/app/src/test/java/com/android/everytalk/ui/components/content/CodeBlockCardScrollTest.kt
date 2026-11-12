package com.android.everytalk.ui.components.content

import com.android.everytalk.ui.components.WebPreviewLoadState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeBlockCardScrollTest {

    @Test
    fun `长代码只排版可见窗口`() {
        val code = (1..500).joinToString("\n") { "line $it" }
        val head = codeBlockVisibleWindow(code, isStreaming = false)
        assertEquals((1..CODE_BLOCK_VISIBLE_LINE_LIMIT).joinToString("\n") { "line $it" }, head)
        val tail = codeBlockVisibleWindow(code, isStreaming = true)
        assertEquals((441..500).joinToString("\n") { "line $it" }, tail)
        assertEquals("a\nb", codeBlockVisibleWindow("a\nb", isStreaming = false))
        assertEquals("a\nb", codeBlockVisibleWindow("a\nb", isStreaming = true))
        assertEquals(CODE_BLOCK_VISIBLE_CHAR_LIMIT, codeBlockVisibleWindow("x".repeat(50_000), false).length)
    }

    @Test
    fun `streaming code block scrolls to bottom`() {
        assertEquals(320, resolveCodeBlockScrollTarget(isStreaming = true, maxValue = 320))
    }

    @Test
    fun `non streaming code block resets to top`() {
        assertEquals(0, resolveCodeBlockScrollTarget(isStreaming = false, maxValue = 320))
    }

    @Test
    fun `历史完成态代码块仅跳过首次异步高度动画`() {
        assertFalse(
            shouldAnimateCodeBlockContentSize(
                isStreaming = false,
                suppressInitialAsyncResizeAnimation = true,
                initialAsyncResizeSettled = false,
            )
        )
        assertTrue(
            shouldAnimateCodeBlockContentSize(
                isStreaming = false,
                suppressInitialAsyncResizeAnimation = true,
                initialAsyncResizeSettled = true,
            )
        )
    }

    @Test
    fun `流式与普通代码块继续保留高度动画`() {
        assertTrue(
            shouldAnimateCodeBlockContentSize(
                isStreaming = true,
                suppressInitialAsyncResizeAnimation = true,
                initialAsyncResizeSettled = false,
            )
        )
        assertTrue(
            shouldAnimateCodeBlockContentSize(
                isStreaming = false,
                suppressInitialAsyncResizeAnimation = false,
                initialAsyncResizeSettled = false,
            )
        )
    }

    @Test
    fun `inline web preview height follows loading result`() {
        val loadingHeight = resolveInlineWebPreviewHeightDp(WebPreviewLoadState.LOADING)
        val errorHeight = resolveInlineWebPreviewHeightDp(WebPreviewLoadState.ERROR)
        val readyHeight = resolveInlineWebPreviewHeightDp(WebPreviewLoadState.READY)

        assertEquals(160f, loadingHeight)
        assertEquals(220f, errorHeight)
        assertEquals(450f, readyHeight)
        assertTrue(loadingHeight < readyHeight)
        assertTrue(errorHeight < readyHeight)
        assertEquals(
            300f,
            resolveInlineWebPreviewHeightDp(WebPreviewLoadState.READY, contentHeightDp = 300f),
        )
        assertEquals(
            450f,
            resolveInlineWebPreviewHeightDp(WebPreviewLoadState.READY, contentHeightDp = 800f),
        )
    }

    @Test
    fun `code content padding follows chatgpt code block style evidence`() {
        val source = codeBlockCardSource()

        assertTrue(source.contains("internal const val GPT_CODE_BLOCK_CONTENT_PADDING_DP = 16f"))
        assertTrue(source.contains(".padding(GPT_CODE_BLOCK_CONTENT_PADDING_DP.dp)"))
        assertFalse(source.contains(".padding(start = 12.dp, end = 12.dp, top = 8.dp)"))
        assertFalse(source.contains(".padding(bottom = if (isAtMaxHeight) 0.dp else 24.dp)"))
    }

    @Test
    fun `代码块吸顶与内部滚动时计算顶部透明渐隐强度`() {
        assertEquals(
            0f,
            resolveCodeBlockStickyOffsetPx(
                stickyTopPx = Float.NaN,
                cardTopPx = 100f,
                cardHeightPx = 400,
                headerHeightPx = 56,
            ),
            0.001f,
        )
        assertEquals(
            80f,
            resolveCodeBlockStickyOffsetPx(
                stickyTopPx = 180f,
                cardTopPx = 100f,
                cardHeightPx = 400,
                headerHeightPx = 56,
            ),
            0.001f,
        )
        assertEquals(
            0f,
            resolveCodeBlockTopFadeStrength(
                stickyOffsetPx = 0f,
                scrollOffsetPx = 0,
                rampPx = 48f,
            ),
            0.001f,
        )
        assertEquals(
            0.5f,
            resolveCodeBlockTopFadeStrength(
                stickyOffsetPx = 24f,
                scrollOffsetPx = 0,
                rampPx = 48f,
            ),
            0.001f,
        )
        assertEquals(
            1f,
            resolveCodeBlockTopFadeStrength(
                stickyOffsetPx = 0f,
                scrollOffsetPx = 96,
                rampPx = 48f,
            ),
            0.001f,
        )
    }

    private fun codeBlockCardSource(): String {
        val sourceFile = listOf(
            File("src/main/java/com/android/everytalk/ui/components/content/CodeBlockCard.kt"),
            File("app/src/main/java/com/android/everytalk/ui/components/content/CodeBlockCard.kt"),
            File("app1/app/src/main/java/com/android/everytalk/ui/components/content/CodeBlockCard.kt"),
        ).first { it.exists() }
        return sourceFile.readText(Charsets.UTF_8)
    }
}
