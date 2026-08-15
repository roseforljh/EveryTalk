package com.android.everytalk.ui.screens.BubbleMain.Main

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.ExecutionStep
import com.android.everytalk.data.DataClass.ExecutionStepType
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
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
    fun `思考预览清理Markdown空行和多余空格`() {
        val preview = reasoningPreviewPlainText(
            """
            # **检查配置**

            - 运行   `uname -a`
            ```text
            [查看结果](https://example.com)
            ```
            """.trimIndent(),
        )

        assertEquals("检查配置\n运行 uname -a\n查看结果", preview)
    }

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
                    reasoningContent = { _, _ -> },
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

    @Test
    fun `思考与工具按模型实际执行顺序交错排列`() {
        val items = orderedExecutionItems(
            reasoningText = "兼容字段不参与新消息排序",
            executionSteps = emptyList(),
            executionTrace = listOf(
                ExecutionTraceEvent.Reasoning("先读取配置。"),
                ExecutionTraceEvent.Tool(toolStep("1", "uname -a")),
                ExecutionTraceEvent.Reasoning("再检查负载。"),
                ExecutionTraceEvent.Tool(toolStep("2", "df -h")),
                ExecutionTraceEvent.Reasoning("最后汇总结论。"),
            ),
        )

        assertEquals(
            listOf("先读取配置。", "uname -a", "再检查负载。", "df -h", "最后汇总结论。"),
            items.map { item ->
                when (item) {
                    is OrderedExecutionItem.Reasoning -> item.text
                    is OrderedExecutionItem.Step -> item.entry.step.labels.single()
                }
            },
        )
    }

    @Test
    fun `连续工具合并为一段且过程文字会切开工具段`() {
        val sections = executionProcessSections(
            reasoningText = "",
            executionSteps = emptyList(),
            executionTrace = listOf(
                ExecutionTraceEvent.Reasoning("先检查配置。"),
                ExecutionTraceEvent.Tool(toolStep("1", "read_file")),
                ExecutionTraceEvent.Tool(toolStep("2", "exec")),
                ExecutionTraceEvent.Reasoning("根据结果继续修复。"),
                ExecutionTraceEvent.Tool(toolStep("3", "write_file")),
                ExecutionTraceEvent.Tool(toolStep("4", "exec")),
            ),
        )

        assertEquals(4, sections.size)
        assertEquals("先检查配置。", (sections[0] as ExecutionProcessSection.Narrative).text)
        assertEquals(
            listOf("read_file", "exec"),
            (sections[1] as ExecutionProcessSection.ToolGroup)
                .entries
                .map { it.step.labels.single() },
        )
        assertEquals("根据结果继续修复。", (sections[2] as ExecutionProcessSection.Narrative).text)
        assertEquals(
            listOf("write_file", "exec"),
            (sections[3] as ExecutionProcessSection.ToolGroup)
                .entries
                .map { it.step.labels.single() },
        )
    }

    @Test
    fun `同一工具在同一段内合并次数但不跨过程文字合并`() {
        val sections = executionProcessSections(
            reasoningText = "",
            executionSteps = emptyList(),
            executionTrace = listOf(
                ExecutionTraceEvent.Tool(toolStep("1", "read_file")),
                ExecutionTraceEvent.Tool(toolStep("2", "read_file")),
                ExecutionTraceEvent.Reasoning("换个方向。"),
                ExecutionTraceEvent.Tool(toolStep("3", "read_file")),
            ),
        )

        val first = sections[0] as ExecutionProcessSection.ToolGroup
        val second = sections[2] as ExecutionProcessSection.ToolGroup
        assertEquals(2, first.entries.single().invocationCount)
        assertEquals(1, second.entries.single().invocationCount)
    }

    @Test
    fun `新旧步骤混合时使用旧消息兼容顺序`() {
        val items = orderedExecutionItems(
            reasoningText = "旧消息的完整思考。",
            executionSteps = listOf(
                toolStep("legacy", "clock"),
                toolStep("new", "exec").copy(reasoningBefore = ""),
            ),
        )

        assertTrue(items.first() is OrderedExecutionItem.Reasoning)
        assertEquals(
            listOf("clock", "exec"),
            items.filterIsInstance<OrderedExecutionItem.Step>().map { it.entry.step.labels.single() },
        )
    }

    @Test
    fun `抽屉节点按有序执行链交错渲染`() {
        val trace = listOf(
            ExecutionTraceEvent.Reasoning("第一段思考"),
            ExecutionTraceEvent.Tool(toolStep("1", "uname -a")),
            ExecutionTraceEvent.Reasoning("第二段思考"),
            ExecutionTraceEvent.Tool(toolStep("2", "df -h")),
        )

        composeRule.setContent {
            MaterialTheme {
                ThinkingExecutionTimeline(
                    executionSteps = emptyList(),
                    executionTrace = trace,
                    webSearchResults = emptyList(),
                    activityStatusText = null,
                    reasoningText = "",
                    isReasoningActive = false,
                    messageIsError = false,
                    reasoningContent = { text, index ->
                        androidx.compose.material3.Text(
                            text = text,
                            modifier = androidx.compose.ui.Modifier.testTag("trace-reasoning-$index"),
                        )
                    },
                )
            }
        }

        val verticalPositions = listOf(
            composeRule.onNodeWithTag("trace-reasoning-0").fetchSemanticsNode("").boundsInRoot.top,
            composeRule.onNodeWithTag("reasoning-execution-step-0").fetchSemanticsNode("").boundsInRoot.top,
            composeRule.onNodeWithTag("trace-reasoning-1").fetchSemanticsNode("").boundsInRoot.top,
            composeRule.onNodeWithTag("reasoning-execution-step-1").fetchSemanticsNode("").boundsInRoot.top,
        )
        assertEquals(verticalPositions.sorted(), verticalPositions)
    }

    private fun toolStep(id: String, name: String) = ExecutionStep(
        id = id,
        type = ExecutionStepType.Tool,
        title = "调用工具",
        labels = listOf(name),
        completed = true,
    )
}
