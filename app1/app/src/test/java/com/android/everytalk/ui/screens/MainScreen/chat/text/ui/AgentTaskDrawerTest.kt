package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.*
import com.android.everytalk.data.agent.AgentRunStatus
import com.android.everytalk.data.agent.AgentTaskTarget
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
        val tasks = listOf(AgentTaskItem("a", "服务器 A", AgentTaskPhase.REVIEWING, trace),
            AgentTaskItem("b", "服务器 B", AgentTaskPhase.RUNNING, emptyList()))
        rule.setContent { MaterialTheme {
            AgentTaskOverlay("s", tasks, emptyList(), 60.dp, 100.dp) { _, _, _ -> }
        } }
        revealTaskEntry()
        rule.onNodeWithTag("agent-task-entry").assertIsDisplayed().performClick()
        rule.onNodeWithTag("agent-task-tab-main").assertDoesNotExist()
        rule.onNodeWithText("主任务").assertDoesNotExist()
        rule.onNodeWithTag("agent-task-tab-a").assertIsSelected()
        val headerBounds = rule.onNodeWithTag("agent-task-header").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("agent-task-process-a").performScrollToIndex(18)
        rule.onNodeWithText("检查 18").assertIsDisplayed()
        rule.onNodeWithTag("agent-task-tabs").assertIsDisplayed()
        assertEquals(headerBounds, rule.onNodeWithTag("agent-task-header").fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithTag("agent-task-tab-b").performClick()
        rule.onNodeWithTag("agent-task-process-b").assertIsDisplayed()
        rule.onNodeWithTag("agent-task-tab-a").performClick()
        rule.onNodeWithText("检查 18").assertIsDisplayed()
        rule.onNodeWithTag("agent-task-drawer").performTouchInput { swipeRight() }
        rule.onNodeWithTag("agent-task-drawer").assertDoesNotExist()
        rule.onNodeWithTag("agent-task-entry").assertIsDisplayed()
    }

    @Test fun `工具结束但主任务未结束时不能提前显示服务器已完成`() {
        val targets = listOf(AgentTaskTarget("a", "A"), AgentTaskTarget("b", "B"))
        val message = Message(text = "", sender = Sender.AI, executionSteps = listOf(
            ExecutionStep("one", ExecutionStepType.Tool, "检查", completed = true, started = true, computerId = "a")))
        val active = buildAgentTaskItems(AgentRunStatus.STREAMING_MODEL.name, message, targets, emptyList())
        assertEquals(listOf("a", "b"), active.map { it.computerId })
        assertEquals(AgentTaskPhase.REVIEWING, active[0].phase)
        assertEquals(AgentTaskPhase.WAITING, active[1].phase)
        val completed = buildAgentTaskItems(AgentRunStatus.COMPLETED.name, message, targets, emptyList())
        assertEquals(AgentTaskPhase.COMPLETED, completed[0].phase)
        assertEquals(AgentTaskPhase.UNUSED, completed[1].phase)
    }

    @Test fun `已完成任务在消息未加载时仍显示入口和已保存的服务器过程`() {
        val tasks = buildAgentTaskItems(
            status = AgentRunStatus.COMPLETED.name, message = null,
            targets = listOf(AgentTaskTarget("a", "服务器 A"), AgentTaskTarget("b", "服务器 B")),
            approvals = emptyList(),
            fallbackTrace = listOf(ExecutionTraceEvent.Tool(ExecutionStep(
                "saved-step", ExecutionStepType.Tool, "已保存的检查", computerId = "a", completed = true,
            ))),
        )
        assertEquals(AgentTaskPhase.COMPLETED, tasks[0].phase)
        rule.setContent { MaterialTheme {
            AgentTaskOverlay("restored-session", tasks, emptyList(), 60.dp, 100.dp) { _, _, _ -> }
        } }
        revealTaskEntry()
        rule.onNodeWithContentDescription("查看任务，共 2 台服务器").assertIsDisplayed()
        rule.onNodeWithTag("agent-task-entry").assertIsDisplayed().performClick()
        rule.onNodeWithTag("agent-task-tab-a").assertIsSelected()
        rule.onNodeWithText("已保存的检查").assertIsDisplayed()
    }

    @Test fun `入口收起保留把手且仅横滑唤出并能滑回边缘再次唤出`() {
        val tasks = listOf(AgentTaskItem("a", "服务器 A", AgentTaskPhase.COMPLETED, emptyList()),
            AgentTaskItem("b", "服务器 B", AgentTaskPhase.COMPLETED, emptyList()))
        rule.setContent { MaterialTheme {
            AgentTaskOverlay("edge-session", tasks, emptyList(), 60.dp, 100.dp) { _, _, _ -> }
        } }
        rule.onNodeWithTag("agent-task-entry").assertIsNotDisplayed()
        rule.onNodeWithTag("agent-task-handle").assertIsDisplayed()
            .assertWidthIsEqualTo(6.dp).assertHeightIsEqualTo(40.dp)
        rule.onNodeWithTag("agent-task-edge").performTouchInput { click() }
        rule.onNodeWithTag("agent-task-entry").assertIsNotDisplayed()
        rule.onNodeWithTag("agent-task-edge").performTouchInput { swipeUp() }
        rule.onNodeWithTag("agent-task-entry").assertIsNotDisplayed()
        revealTaskEntry()
        rule.onNodeWithTag("agent-task-handle").assertDoesNotExist()
        rule.onNodeWithTag("agent-task-entry").assertIsDisplayed().performTouchInput { swipeRight() }
        rule.onNodeWithTag("agent-task-entry").assertIsNotDisplayed()
        rule.onNodeWithTag("agent-task-handle").assertIsDisplayed()
        revealTaskEntry()
        rule.onNodeWithTag("agent-task-entry").assertIsDisplayed().performClick()
        rule.onNodeWithTag("agent-task-drawer").assertIsDisplayed()
    }

    @Test fun `无障碍服务无需横滑即可从隐藏边缘打开任务`() {
        val tasks = listOf(AgentTaskItem("a", "服务器 A", AgentTaskPhase.COMPLETED, emptyList()),
            AgentTaskItem("b", "服务器 B", AgentTaskPhase.COMPLETED, emptyList()))
        rule.setContent { MaterialTheme {
            AgentTaskOverlay("accessible-session", tasks, emptyList(), 60.dp, 100.dp) { _, _, _ -> }
        } }
        rule.onNodeWithTag("agent-task-edge").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithTag("agent-task-drawer").assertIsDisplayed()
    }

    @Test fun `入口按住时跟手支持反向拖动并在松手后平滑吸附`() {
        val tasks = listOf(AgentTaskItem("a", "服务器 A", AgentTaskPhase.COMPLETED, emptyList()),
            AgentTaskItem("b", "服务器 B", AgentTaskPhase.COMPLETED, emptyList()))
        rule.setContent { MaterialTheme {
            AgentTaskOverlay("motion-session", tasks, emptyList(), 60.dp, 100.dp) { _, _, _ -> }
        } }
        val entry = rule.onNodeWithTag("agent-task-entry")
        val edge = rule.onNodeWithTag("agent-task-edge")
        val hiddenLeft = entry.getUnclippedBoundsInRoot().left
        rule.mainClock.autoAdvance = false
        edge.performTouchInput {
            down(Offset(width - 2f, centerY))
            moveBy(Offset(-width * 4f, 0f))
        }
        rule.mainClock.advanceTimeByFrame()
        val expandedLeft = entry.getUnclippedBoundsInRoot().left
        assertTrue("未松手就应跟随拖动展开", expandedLeft < hiddenLeft)
        edge.performTouchInput { moveBy(Offset(width * 2f, 0f)) }
        rule.mainClock.advanceTimeByFrame()
        val reversedLeft = entry.getUnclippedBoundsInRoot().left
        assertTrue("同一次手势应能反向拖回", reversedLeft > expandedLeft && reversedLeft < hiddenLeft)
        edge.performTouchInput { advanceEventTime(500); up() }
        rule.mainClock.advanceTimeByFrame()
        val settlingLeft = entry.getUnclippedBoundsInRoot().left
        assertTrue("松手后应有中间过渡帧", settlingLeft < hiddenLeft)
        rule.mainClock.advanceTimeBy(1_000)
        entry.assertIsNotDisplayed()
        rule.onNodeWithTag("agent-task-handle").assertIsDisplayed()
        rule.mainClock.autoAdvance = true
    }

    private fun revealTaskEntry() {
        // 手指从窄边缘区域滑入聊天区，位移超过触摸容差及唤出阈值。
        rule.onNodeWithTag("agent-task-edge").performTouchInput {
            swipe(start = Offset(width - 2f, centerY), end = Offset(-width * 4f, centerY))
        }
    }
}
