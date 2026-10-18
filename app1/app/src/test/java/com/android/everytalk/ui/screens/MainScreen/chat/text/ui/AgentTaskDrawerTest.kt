package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.*
import com.android.everytalk.data.agent.AgentRunStatus
import com.android.everytalk.data.computer.ComputerTargetBinding
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 用真实 Compose 布局验证悬浮入口、切换、内部滚动及退出动画。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentTaskDrawerTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `切换任务保留独立滚动位置且关闭后入口仍存在`() {
        val trace = (1..20).map { index -> ExecutionTraceEvent.Tool(
            ExecutionStep("step-$index", ExecutionStepType.Tool, "检查 $index", computerId = "a", completed = true)) }
        val tasks = listOf(AgentTaskItem(null, "", AgentTaskPhase.PLANNING, emptyList()),
            AgentTaskItem("a", "服务器 A", AgentTaskPhase.REVIEWING, trace),
            AgentTaskItem("b", "服务器 B", AgentTaskPhase.RUNNING, emptyList()))
        rule.setContent { MaterialTheme {
            AgentTaskOverlay("s", tasks, emptyList(), 60.dp, 100.dp) { _, _, _ -> }
        } }
        rule.onNodeWithTag("agent-task-entry").assertIsDisplayed().performClick()
        rule.onNodeWithTag("agent-task-tab-a").performClick()
        rule.onNodeWithTag("agent-task-process-a").performScrollToIndex(18)
        rule.onNodeWithText("检查 18").assertIsDisplayed()
        rule.onNodeWithTag("agent-task-tabs").assertIsDisplayed()
        rule.onNodeWithTag("agent-task-tab-b").performClick()
        rule.onNodeWithTag("agent-task-process-b").assertIsDisplayed()
        rule.onNodeWithTag("agent-task-tab-a").performClick()
        rule.onNodeWithText("检查 18").assertIsDisplayed()
        rule.onNodeWithContentDescription("关闭任务").performClick()
        rule.onNodeWithTag("agent-task-drawer").assertDoesNotExist()
        rule.onNodeWithTag("agent-task-entry").assertIsDisplayed()
    }

    @Test fun `工具结束但主任务未结束时不能提前显示服务器已完成`() {
        val targets = listOf(ComputerTargetBinding("a", "wa", "A"), ComputerTargetBinding("b", "wb", "B"))
        val message = Message(text = "", sender = Sender.AI, executionSteps = listOf(
            ExecutionStep("one", ExecutionStepType.Tool, "检查", completed = true, started = true, computerId = "a")))
        val active = buildAgentTaskItems(AgentRunStatus.STREAMING_MODEL.name, message, targets, emptyList())
        assertEquals(AgentTaskPhase.REVIEWING, active[1].phase)
        assertEquals(AgentTaskPhase.WAITING, active[2].phase)
        val completed = buildAgentTaskItems(AgentRunStatus.COMPLETED.name, message, targets, emptyList())
        assertEquals(AgentTaskPhase.COMPLETED, completed[1].phase)
        assertEquals(AgentTaskPhase.UNUSED, completed[2].phase)
    }
}
