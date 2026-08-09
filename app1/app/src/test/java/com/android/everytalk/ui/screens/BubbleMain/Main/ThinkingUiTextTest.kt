package com.android.everytalk.ui.screens.BubbleMain.Main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class ThinkingUiTextTest {

    @Test
    fun `reasoning sheet keeps content empty before first token`() {
        assertEquals(
            "",
            reasoningSheetText(
                displayedReasoningText = "",
                isReasoningActive = true,
                messageIsError = false,
                errorText = "思考过程中发生错误",
                emptyText = "暂无详细思考内容",
            )
        )
    }

    @Test
    fun `reasoning sheet prefers actual reasoning text`() {
        assertEquals(
            "分析中",
            reasoningSheetText(
                displayedReasoningText = "分析中",
                isReasoningActive = true,
                messageIsError = false,
                errorText = "思考过程中发生错误",
                emptyText = "暂无详细思考内容",
            )
        )
    }

    @Test
    fun `reasoning sheet uses empty state after reasoning ends`() {
        assertEquals(
            "暂无详细思考内容",
            reasoningSheetText(
                displayedReasoningText = "",
                isReasoningActive = false,
                messageIsError = false,
                errorText = "思考过程中发生错误",
                emptyText = "暂无详细思考内容",
            )
        )
    }

    @Test
    fun `reasoning sheet does not render horizontal dividers`() {
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/ui/screens/BubbleMain/Main/ThinkingUI.kt"),
            File("app/src/main/java/com/android/everytalk/ui/screens/BubbleMain/Main/ThinkingUI.kt"),
            File("app1/app/src/main/java/com/android/everytalk/ui/screens/BubbleMain/Main/ThinkingUI.kt"),
        )
        val sourceFile = candidates.firstOrNull { it.isFile }
        requireNotNull(sourceFile) { "找不到 ThinkingUI.kt" }

        assertFalse(sourceFile.readText(Charsets.UTF_8).contains("HorizontalDivider("))
    }
}
