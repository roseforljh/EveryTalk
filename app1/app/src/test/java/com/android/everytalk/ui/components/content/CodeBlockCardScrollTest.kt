package com.android.everytalk.ui.components.content

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeBlockCardScrollTest {

    @Test
    fun `streaming code block scrolls to bottom`() {
        assertEquals(320, resolveCodeBlockScrollTarget(isStreaming = true, maxValue = 320))
    }

    @Test
    fun `non streaming code block resets to top`() {
        assertEquals(0, resolveCodeBlockScrollTarget(isStreaming = false, maxValue = 320))
    }

    @Test
    fun `code content padding follows chatgpt code block style evidence`() {
        val source = codeBlockCardSource()

        assertTrue(source.contains("internal const val GPT_CODE_BLOCK_CONTENT_PADDING_DP = 16f"))
        assertTrue(source.contains(".padding(GPT_CODE_BLOCK_CONTENT_PADDING_DP.dp)"))
        assertFalse(source.contains(".padding(start = 12.dp, end = 12.dp, top = 8.dp)"))
        assertFalse(source.contains(".padding(bottom = if (isAtMaxHeight) 0.dp else 24.dp)"))
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
