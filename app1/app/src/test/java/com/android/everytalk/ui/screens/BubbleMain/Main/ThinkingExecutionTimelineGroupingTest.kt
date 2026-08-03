package com.android.everytalk.ui.screens.BubbleMain.Main

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.ExecutionStep
import com.android.everytalk.data.DataClass.ExecutionStepType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ThinkingExecutionTimelineGroupingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `连续同名工具合并并在胶囊右侧显示次数`() {
        val steps = List(9) { index -> toolStep("tool-$index", "read_attachment") }

        composeRule.setContent {
            MaterialTheme {
                ThinkingExecutionTimeline(
                    executionSteps = steps,
                    webSearchResults = emptyList(),
                    activityStatusText = null,
                    reasoningText = "",
                    isReasoningActive = false,
                    messageIsError = false,
                    reasoningContent = {},
                )
            }
        }

        composeRule.onNodeWithTag("reasoning-execution-step-0").fetchSemanticsNode("")
        assertTrue(
            composeRule.onAllNodesWithTag("reasoning-execution-step-1")
                .fetchSemanticsNodes().isEmpty(),
        )
        composeRule.onNodeWithText("read_attachment").fetchSemanticsNode("")
        composeRule.onNodeWithText("x 9").fetchSemanticsNode("")
    }

    @Test
    fun `不同工具和非连续同名工具保持独立`() {
        val entries = executionTimelineEntries(
            listOf(
                toolStep("1", "read_attachment"),
                toolStep("2", "read_attachment"),
                toolStep("3", "current_time"),
                toolStep("4", "read_attachment"),
            ),
        )

        assertEquals(listOf(2, 1, 1), entries.map { it.invocationCount })
        assertEquals(
            listOf("read_attachment", "current_time", "read_attachment"),
            entries.map { it.step.labels.single() },
        )
    }

    private fun toolStep(id: String, name: String) = ExecutionStep(
        id = id,
        type = ExecutionStepType.Tool,
        title = "调用工具",
        labels = listOf(name),
        completed = true,
    )
}
