package com.android.everytalk.ui.screens.BubbleMain.Main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `终态执行提示保持静态，取消中和恢复中保持活动`() {
        assertFalse(isExecutionStatusActive("远端任务已取消"))
        assertFalse(isExecutionStatusActive("远端取消失败，等待恢复确认"))
        assertFalse(isExecutionStatusActive("命令执行完成"))
        assertFalse(isExecutionStatusActive("已由新消息取代"))
        assertTrue(isExecutionStatusActive("正在取消远端任务"))
        assertTrue(isExecutionStatusActive("正在恢复远端任务"))
    }

    @Test
    fun `计时只由真实流驱动且结束后不能重启`() {
        assertTrue(
            executionProcessIsActive(
                executionFinishedAtMillis = null,
                messageIsError = false,
                replyIsStreaming = true,
                isReasoningStreaming = false,
            )
        )
        assertFalse(
            executionProcessIsActive(
                executionFinishedAtMillis = null,
                messageIsError = false,
                replyIsStreaming = false,
                isReasoningStreaming = false,
            )
        )
        assertFalse(
            executionProcessIsActive(
                executionFinishedAtMillis = 1234L,
                messageIsError = false,
                replyIsStreaming = true,
                isReasoningStreaming = true,
            )
        )
    }

    @Test
    fun `reasoning sheet does not render horizontal dividers`() {
        assertFalse(thinkingUiSourceFile().readText(Charsets.UTF_8).contains("HorizontalDivider("))
    }

    private fun thinkingUiSourceFile(): File {
        val candidates = listOf(
            File("src/main/java/com/android/everytalk/ui/screens/BubbleMain/Main/ThinkingUI.kt"),
            File("app/src/main/java/com/android/everytalk/ui/screens/BubbleMain/Main/ThinkingUI.kt"),
            File("app1/app/src/main/java/com/android/everytalk/ui/screens/BubbleMain/Main/ThinkingUI.kt"),
        )
        return requireNotNull(candidates.firstOrNull { it.isFile }) { "找不到 ThinkingUI.kt" }
    }
}
