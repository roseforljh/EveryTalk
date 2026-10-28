package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.*
import com.android.everytalk.data.agent.AgentRunStatus
import com.android.everytalk.data.agent.AgentTaskTarget
import com.android.everytalk.data.agent.AgentApprovalDecision
import com.android.everytalk.data.computer.ComputerHostCommandConfirmationRequest
import com.android.everytalk.data.computer.ComputerRequestContext
import com.android.everytalk.data.computer.ComputerToolApprovalRequest
import com.android.everytalk.data.computer.PendingComputerToolApproval
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

    @Test fun `新审批自动展开所属服务器且收起后不重复弹出下一条仍可审批`() {
        val trace = (1..20).map { index -> ExecutionTraceEvent.Tool(
            ExecutionStep("approval-step-$index", ExecutionStepType.Tool, "检查 $index", computerId = "a", completed = true)) }
        val tasks = listOf(AgentTaskItem("a", "服务器 A", AgentTaskPhase.RUNNING, trace),
            AgentTaskItem("b", "服务器 B", AgentTaskPhase.APPROVAL, emptyList()))
        val approvals = mutableStateOf(emptyList<PendingComputerToolApproval>())
        val decisions = mutableListOf<Pair<String, AgentApprovalDecision>>()
        rule.setContent { MaterialTheme {
            AgentTaskOverlay("approval-session", tasks, approvals.value, 60.dp, 100.dp) { pending, decision, handled ->
                decisions += pending.approvalRequestId to decision
                approvals.value = approvals.value.filterNot { it.approvalRequestId == pending.approvalRequestId }
                handled()
            }
        } }
        rule.onNodeWithTag("agent-task-drawer").assertDoesNotExist()
        rule.runOnIdle { approvals.value = listOf(approval("first", "b")) }
        rule.onNodeWithTag("agent-task-drawer").assertIsDisplayed()
        rule.onNodeWithTag("agent-task-tab-b").assertIsSelected()
        rule.onNodeWithText("command-first").assertIsDisplayed()
        assertTrue("只展示申请，不能自动批准", decisions.isEmpty())

        rule.onNodeWithTag("agent-task-drawer").performTouchInput { swipeRight() }
        rule.onNodeWithTag("agent-task-drawer").assertDoesNotExist()
        rule.runOnIdle { approvals.value = listOf(approval("first", "b", "刷新后的同一审批")) }
        rule.onNodeWithTag("agent-task-drawer").assertDoesNotExist()
        rule.runOnIdle { approvals.value = listOf(approval("second", "a")) }
        rule.onNodeWithTag("agent-task-tab-a").assertIsSelected()
        rule.onNodeWithText("command-second").assertIsDisplayed()
        rule.onNodeWithText("允许本次").performClick()
        assertEquals(listOf("second" to AgentApprovalDecision.APPROVED), decisions)
        rule.onNodeWithTag("agent-task-process-a").performScrollToIndex(18)
        rule.onNodeWithText("检查 18").assertIsDisplayed()
        rule.runOnIdle { approvals.value = listOf(approval("third", "a")) }
        rule.onNodeWithText("command-third").assertIsDisplayed()
        rule.onNodeWithText("拒绝").performClick()
        assertEquals(listOf("second" to AgentApprovalDecision.APPROVED, "third" to AgentApprovalDecision.REJECTED), decisions)
    }

    @Test fun `单服务器和目标不匹配的审批不能交给任务抽屉`() {
        val tasks = listOf(AgentTaskItem("a", "A", AgentTaskPhase.RUNNING, emptyList()),
            AgentTaskItem("b", "B", AgentTaskPhase.RUNNING, emptyList()))
        val matching = approval("matching", "b")
        val unmatched = approval("unmatched", "c")
        assertEquals(listOf(matching), taskApprovalsForDisplay(tasks, listOf(unmatched, matching)))
        assertTrue(taskApprovalsForDisplay(tasks.take(1), listOf(matching)).isEmpty())
    }

    private fun approval(id: String, computerId: String, command: String = "command-$id"): PendingComputerToolApproval = PendingComputerToolApproval(
        runId = "run", approvalRequestId = id,
        request = ComputerToolApprovalRequest.HostCommand(id, ComputerHostCommandConfirmationRequest(
            requestId = id, context = ComputerRequestContext("session", computerId, "workspace", runId = "run"),
            computerName = "服务器 $computerId", command = command, cwd = "", requestsPrivilege = false,
            reason = "需要确认", risks = emptySet(),
        )),
    )

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
