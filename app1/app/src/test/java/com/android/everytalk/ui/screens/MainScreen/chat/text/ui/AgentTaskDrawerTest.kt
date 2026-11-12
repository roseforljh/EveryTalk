package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import androidx.test.core.app.ApplicationProvider
import com.android.everytalk.ui.components.math.MathJaxSvgRenderer
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
    private lateinit var mathRenderer: MathJaxSvgRenderer

    // 抽屉正文走 Markdown 渲染，需要和聊天页一样注入公式渲染器。
    @Before fun setUp() {
        stopKoin()
        mathRenderer = MathJaxSvgRenderer(ApplicationProvider.getApplicationContext())
        startKoin { modules(module { single { mathRenderer } }) }
    }

    @After fun tearDown() {
        stopKoin()
        mathRenderer.close()
    }

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
        openFromEdge()
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
        rule.onNodeWithTag("agent-task-handle", useUnmergedTree = true).assertIsDisplayed()
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
        rule.onNodeWithContentDescription("查看任务，共 2 台服务器").assertExists()
        openFromEdge()
        rule.onNodeWithTag("agent-task-tab-a").assertIsSelected()
        rule.onNodeWithText("已保存的检查").assertIsDisplayed()
    }

    @Test fun `收起时只有竖条把手且横滑竖条直接拉出面板`() {
        val tasks = listOf(AgentTaskItem("a", "服务器 A", AgentTaskPhase.COMPLETED, emptyList()),
            AgentTaskItem("b", "服务器 B", AgentTaskPhase.COMPLETED, emptyList()))
        rule.setContent { MaterialTheme {
            AgentTaskOverlay("edge-session", tasks, emptyList(), 60.dp, 100.dp) { _, _, _ -> }
        } }
        rule.onNodeWithTag("agent-task-entry").assertDoesNotExist()
        rule.onNodeWithTag("agent-task-handle", useUnmergedTree = true).assertIsDisplayed()
            .assertWidthIsEqualTo(4.dp).assertHeightIsEqualTo(44.dp)
        rule.onNodeWithTag("agent-task-edge").performTouchInput { click() }
        rule.onNodeWithTag("agent-task-drawer").assertIsDisplayed()
        rule.onNodeWithTag("agent-task-drawer").performTouchInput { swipeRight() }
        rule.onNodeWithTag("agent-task-edge").performTouchInput { swipeUp() }
        rule.onNodeWithTag("agent-task-drawer").assertDoesNotExist()
        openFromEdge()
        rule.onNodeWithTag("agent-task-drawer").performTouchInput { swipeRight() }
        rule.onNodeWithTag("agent-task-drawer").assertDoesNotExist()
        rule.onNodeWithTag("agent-task-handle", useUnmergedTree = true).assertIsDisplayed()
        openFromEdge()
    }

    @Test fun `把手闲置淡出后仍可从边缘拉出面板`() {
        val tasks = listOf(AgentTaskItem("a", "服务器 A", AgentTaskPhase.COMPLETED, emptyList()),
            AgentTaskItem("b", "服务器 B", AgentTaskPhase.COMPLETED, emptyList()))
        rule.setContent { MaterialTheme {
            AgentTaskOverlay("idle-session", tasks, emptyList(), 60.dp, 100.dp) { _, _, _ -> }
        } }
        rule.mainClock.advanceTimeBy(AgentTaskHandleIntroMillis + 1_500L)
        rule.onNodeWithTag("agent-task-handle", useUnmergedTree = true).assertExists()
        openFromEdge()
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

    @Test fun `面板按住竖条跟手支持反向拖动并在松手后平滑吸附`() {
        val tasks = listOf(AgentTaskItem("a", "服务器 A", AgentTaskPhase.COMPLETED, emptyList()),
            AgentTaskItem("b", "服务器 B", AgentTaskPhase.COMPLETED, emptyList()))
        rule.setContent { MaterialTheme {
            AgentTaskOverlay("motion-session", tasks, emptyList(), 60.dp, 100.dp) { _, _, _ -> }
        } }
        val edge = rule.onNodeWithTag("agent-task-edge")
        val drawer = rule.onNodeWithTag("agent-task-drawer")
        rule.mainClock.autoAdvance = false
        edge.performTouchInput {
            down(Offset(width - 2f, centerY))
            moveBy(Offset(-width * 4f, 0f))
        }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        val pulledLeft = drawer.getUnclippedBoundsInRoot().left
        edge.performTouchInput { moveBy(Offset(width * 2f, 0f)) }
        rule.mainClock.advanceTimeByFrame()
        val reversedLeft = drawer.getUnclippedBoundsInRoot().left
        assertTrue("同一次手势应能反向拖回", reversedLeft > pulledLeft)
        edge.performTouchInput { advanceEventTime(500); up() }
        rule.mainClock.advanceTimeByFrame()
        drawer.assertExists()
        assertTrue("松手后应有中间过渡帧", drawer.getUnclippedBoundsInRoot().left >= reversedLeft)
        rule.mainClock.advanceTimeBy(1_000)
        drawer.assertDoesNotExist()
        rule.onNodeWithTag("agent-task-handle", useUnmergedTree = true).assertIsDisplayed()
        rule.mainClock.autoAdvance = true
    }

    @Test fun `Fork模式自动展开抽屉并在分支页放入输入框且引用可点击跳转`() {
        val quote = MessageQuoteReference(
            id = "q-1",
            sourceConversationId = "conv-1",
            sourceMessageId = "msg-ai-1",
            quotedText = "这是一段被选中的 AI 回复内容",
        )
        val userMsg = Message(
            id = "u-fork-1",
            text = "请解释这段话",
            sender = Sender.User,
            contentParts = listOf(
                MessageContentPart.QuoteReference(quote),
                MessageContentPart.Text("请解释这段话"),
            ),
        )
        val aiMsg = Message(
            id = "a-fork-1",
            text = "这是 Fork 分支的回答",
            sender = Sender.AI,
            contentStarted = true,
        )
        val forkTask = buildForkTaskItem(listOf(userMsg, aiMsg), runningMessageId = null)
        var clickedQuote: MessageQuoteReference? = null
        rule.setContent {
            MaterialTheme {
                AgentTaskOverlay(
                    sessionKey = "fork-session",
                    tasks = listOf(forkTask),
                    approvals = emptyList(),
                    topAvoidance = 20.dp,
                    bottomAvoidance = 20.dp,
                    allowSingleOrForkTask = true,
                    openRequestVersion = 1,
                    preferredTabId = FORK_TASK_ID,
                    modelName = "DeepSeek-V3",
                    onQuoteClick = { clickedQuote = it },
                    forkComposer = { androidx.compose.material3.Text("fork-composer-slot") },
                    onApproval = { _, _, _ -> },
                )
            }
        }
        rule.onNodeWithTag("agent-task-drawer").assertIsDisplayed()
        rule.onNodeWithText("fork-composer-slot").assertIsDisplayed()
        rule.onNodeWithText("请解释这段话").assertIsDisplayed()
        rule.onNodeWithTag("agent-task-process-fork").performScrollToIndex(1)
        rule.onNodeWithText("这是 Fork 分支的回答").assertIsDisplayed()
        rule.onAllNodesWithTag("quote-reference-pill-q-1").onFirst().performClick()
        assertEquals(quote, clickedQuote)
        rule.onNodeWithTag("agent-task-drawer").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(AgentTaskHandleIntroMillis + 1_500L)
        rule.onNodeWithTag("agent-task-handle", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("agent-task-edge").performClick()
        rule.onNodeWithText("fork-composer-slot").assertIsDisplayed()
        rule.onNodeWithTag("agent-task-tabs").assertDoesNotExist()
    }

    @Test fun `分支收起连续输出中间进度以恢复主输入框动画`() {
        val progress = mutableListOf<Float>()
        rule.setContent { MaterialTheme {
            AgentTaskOverlay(
                sessionKey = "fork-progress",
                tasks = listOf(buildForkTaskItem(emptyList(), null)),
                approvals = emptyList(), topAvoidance = 20.dp, bottomAvoidance = 20.dp,
                allowSingleOrForkTask = true, openRequestVersion = 1,
                forkComposer = { androidx.compose.material3.Text("composer") },
                onComposerOpenProgress = { progress += it },
                onApproval = { _, _, _ -> },
            )
        } }
        rule.onNodeWithTag("agent-task-drawer").assertIsDisplayed()
        rule.runOnIdle { progress.clear() }
        rule.onNodeWithTag("agent-task-drawer").performTouchInput { swipeRight() }
        rule.onNodeWithTag("agent-task-drawer").assertDoesNotExist()
        rule.runOnIdle {
            assertTrue("必须包含中间帧，而不是仅在关闭末尾切换", progress.any { it > 0f && it < 1f })
            assertEquals(0f, progress.last(), 0.001f)
        }
    }

    @Test fun `Fork铺满侧栏并只由自己的输入框预留底部高度`() {
        rule.setContent { MaterialTheme {
            AgentTaskOverlay(
                sessionKey = "fork-insets",
                tasks = listOf(buildForkTaskItem(emptyList(), null)),
                approvals = emptyList(), topAvoidance = 20.dp, bottomAvoidance = 100.dp,
                allowSingleOrForkTask = true, openRequestVersion = 1,
                forkComposer = {
                    Box(
                        Modifier.testTag("fork-insets-composer")
                            .windowInsetsPadding(WindowInsets(bottom = 100.dp)),
                    ) {
                        Box(Modifier.height(48.dp))
                    }
                },
                onApproval = { _, _, _ -> },
            )
        } }
        rule.onNodeWithTag("fork-insets-composer").assertHeightIsEqualTo(148.dp)
        rule.onNodeWithTag("agent-task-tabs").assertDoesNotExist()
    }

    @Test fun `Fork时间线不重复插入Agent追问边界且运行中显示RUNNING`() {
        val first = Message(id = "u1", text = "问题", sender = Sender.User)
        val followUp = Message(id = "u2", text = "追问", sender = Sender.User)
        val ai = Message(
            id = "a1", text = "", sender = Sender.AI, reasoning = "想一想",
            executionTrace = listOf(
                ExecutionTraceEvent.Content("第一段"),
                ExecutionTraceEvent.UserMessageBoundary("u2"),
                ExecutionTraceEvent.Content("第二段"),
            ),
        )
        val task = buildForkTaskItem(listOf(first, ai, followUp), runningMessageId = "a1")
        assertEquals(AgentTaskPhase.RUNNING, task.phase)
        assertEquals(
            listOf("u1", "reasoning", "第一段", "u2", "第二段"),
            task.trace.map {
                when (it) {
                    is ExecutionTraceEvent.UserMessageBoundary -> it.messageId
                    is ExecutionTraceEvent.Reasoning -> "reasoning"
                    is ExecutionTraceEvent.Content -> it.text
                    else -> "?"
                }
            },
        )
        assertEquals(setOf("u1", "u2"), task.userMessages.keys)
    }

    private fun openFromEdge() {
        // 手指从竖条区域滑入聊天区，位移超过唤出阈值，面板直接展开。
        rule.onNodeWithTag("agent-task-edge").performTouchInput {
            swipe(start = Offset(width - 2f, centerY), end = Offset(-width * 6f, centerY))
        }
        rule.onNodeWithTag("agent-task-drawer").assertIsDisplayed()
    }
}
