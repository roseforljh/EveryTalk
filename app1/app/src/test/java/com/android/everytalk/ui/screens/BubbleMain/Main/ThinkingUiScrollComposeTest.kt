package com.android.everytalk.ui.screens.BubbleMain.Main

import android.app.Application
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.android.everytalk.data.DataClass.ExecutionStep
import com.android.everytalk.data.DataClass.ExecutionStepType
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.WebSearchResult
import com.android.everytalk.data.network.TOOL_CALL_WRITING_STATUS
import com.android.everytalk.ui.components.math.MathJaxSvgRenderer
import com.android.everytalk.ui.screens.ImageGeneration.ImageGenerationLoadingView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "zh-rCN")
class ThinkingUiScrollComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var mathRenderer: MathJaxSvgRenderer

    @Before
    fun setUp() {
        stopKoin()
        mathRenderer = MathJaxSvgRenderer(ApplicationProvider.getApplicationContext())
        startKoin {
            modules(module { single { mathRenderer } })
        }
    }

    @After
    fun tearDown() {
        stopKoin()
        mathRenderer.close()
    }

    /** 展开总容器后，点击具体过程内容进入完整抽屉。 */
    private fun openReasoningSheet() {
        if (composeRule.onAllNodesWithTag("reasoning-chain-summaries").fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithTag("reasoning-inline-status").performClick()
            composeRule.mainClock.advanceTimeBy(250L)
            composeRule.waitForIdle()
        }
        if (composeRule.onAllNodesWithTag("reasoning-chain-tool-0-0").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("reasoning-chain-tool-0-0").performClick()
            return
        }
        composeRule.onNodeWithTag("reasoning-chain-summary-0").performClick()
        composeRule.mainClock.advanceTimeBy(250L)
        composeRule.waitForIdle()
        if (
            composeRule.onAllNodesWithTag("reasoning-sheet-surface").fetchSemanticsNodes().isEmpty() &&
            composeRule.onAllNodesWithTag("reasoning-chain-tool-0-0").fetchSemanticsNodes().isNotEmpty()
        ) {
            composeRule.onNodeWithTag("reasoning-chain-tool-0-0").performClick()
        }
    }

    @Test
    fun `正文开始流式输出时过程收起且允许手动查看`() {
        val replyStreaming = mutableStateOf(true)
        val contentStarted = mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "reply-collapse-process",
                    displayedReasoningText = "已完成的思考",
                    executionTrace = listOf(ExecutionTraceEvent.Reasoning("已完成的思考")),
                    isReasoningStreaming = false,
                    isReasoningComplete = true,
                    replyIsStreaming = replyStreaming.value,
                    messageIsError = false,
                    mainContentHasStarted = contentStarted.value,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("reasoning-chain-summaries").fetchSemanticsNode("")

        composeRule.runOnIdle { contentStarted.value = true }
        composeRule.waitUntil(timeoutMillis = 2_000L) {
            composeRule.onAllNodesWithTag("reasoning-chain-summaries")
                .fetchSemanticsNodes()
                .isEmpty()
        }

        val remainingNodes = composeRule
            .onAllNodesWithTag("reasoning-chain-summaries")
            .fetchSemanticsNodes()
        assertTrue(
            "收起后仍有节点：count=${remainingNodes.size}, " +
                "heights=${remainingNodes.map { it.boundsInRoot.height }}",
            remainingNodes.isEmpty(),
        )
        assertTrue("折叠必须发生在回复结束之前", replyStreaming.value)
        composeRule.onNodeWithTag("reasoning-inline-status").performClick()
        composeRule.onNodeWithTag("reasoning-chain-summaries").assertIsDisplayed()
        composeRule.onNodeWithTag("reasoning-inline-status").performClick()
        composeRule.waitUntil(2_000L) {
            composeRule.onAllNodesWithTag("reasoning-chain-summaries").fetchSemanticsNodes().isEmpty()
        }
        composeRule.runOnIdle { replyStreaming.value = false }
    }

    @Test
    fun `只有最新思考段扫描并显示最新三行`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "reasoning-preview",
                    displayedReasoningText = "历史思考\n第一行\n第二行\n第三行\n第四行\n第五行\n第六行",
                    executionTrace = listOf(
                        ExecutionTraceEvent.Reasoning("历史思考"),
                        ExecutionTraceEvent.Tool(
                            ExecutionStep(
                                id = "done-tool",
                                type = ExecutionStepType.Agent,
                                title = "执行命令",
                                completed = true,
                            ),
                        ),
                        ExecutionTraceEvent.Reasoning(
                            "第一行\n第二行\n第三行\n第四行\n第五行\n第六行",
                        ),
                    ),
                    isReasoningStreaming = true,
                    isReasoningComplete = false,
                    messageIsError = false,
                    mainContentHasStarted = false,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(600L)
        composeRule.waitForIdle()

        assertEquals(
            1,
            composeRule.onAllNodesWithTag(
                "reasoning-chain-narrative-scanning",
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size,
        )
        assertEquals(
            1,
            composeRule.onAllNodesWithTag(
                "reasoning-chain-narrative-static",
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithTag(
            "reasoning-chain-narrative-scanning",
            useUnmergedTree = true,
        ).assertTextEquals("第四行\n第五行\n第六行")
        composeRule.onNodeWithTag("reasoning-chain-summary-2").assertHasClickAction()
    }

    @Test
    fun `没有真实过程内容时不创建总容器`() {
        composeRule.setContent {
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "plain-answer",
                    displayedReasoningText = "",
                    isReasoningStreaming = false,
                    isReasoningComplete = true,
                    messageIsError = false,
                    mainContentHasStarted = true,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    onVisibilityChanged = {},
                )
            }
        }

        assertTrue(
            composeRule.onAllNodesWithTag("reasoning-process-container").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `完成态使用持久结束时间显示准确耗时`() {
        composeRule.setContent {
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "finished-duration",
                    displayedReasoningText = "已完成检查。",
                    isReasoningStreaming = false,
                    isReasoningComplete = true,
                    messageIsError = false,
                    mainContentHasStarted = true,
                    executionStartedAtMillis = 1_000L,
                    executionFinishedAtMillis = 66_000L,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.onNodeWithText("耗时 1分5秒").fetchSemanticsNode("")
        assertTrue(composeRule.onAllNodesWithTag("reasoning-chain-summaries").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `执行链默认收起并可展开后再次收起`() {
        composeRule.mainClock.autoAdvance = false
        var pixelsPerDp = 1f
        composeRule.setContent {
            pixelsPerDp = LocalDensity.current.density
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "collapsible-chain",
                    displayedReasoningText = "先分析需求",
                    isReasoningStreaming = false,
                    isReasoningComplete = true,
                    messageIsError = false,
                    mainContentHasStarted = true,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag("reasoning-chain-summaries").fetchSemanticsNodes().isEmpty())
        val collapsedHeaderHeight = composeRule
            .onNodeWithTag("reasoning-inline-status")
            .fetchSemanticsNode("")
            .boundsInRoot
            .height
        assertTrue(
            "折叠标题仍有过多上下留白：height=$collapsedHeaderHeight, density=$pixelsPerDp",
            collapsedHeaderHeight <= 48f * pixelsPerDp,
        )

        composeRule.onNodeWithTag("reasoning-inline-status").performClick()
        composeRule.mainClock.advanceTimeBy(250L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("reasoning-chain-summary-0").assertHasClickAction()
        composeRule.onNodeWithText("思考过程").fetchSemanticsNode("")
        composeRule.onNodeWithText("先分析需求").fetchSemanticsNode("")
        assertTrue(
            composeRule.onAllNodesWithTag(
                "reasoning-chain-summary-scanning",
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isEmpty(),
        )

        composeRule.onNodeWithTag("reasoning-inline-status").performClick()
        composeRule.mainClock.advanceTimeBy(120L)
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag("reasoning-chain-summaries").fetchSemanticsNodes().isNotEmpty())
        composeRule.mainClock.advanceTimeBy(200L)
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithTag("reasoning-chain-summaries").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `服务器操作默认收起且展开只显示最新三条`() {
        val trace = List(5) { index ->
            ExecutionTraceEvent.Tool(
                ExecutionStep(
                    id = "server-command-$index",
                    type = ExecutionStepType.Agent,
                    title = "运行 Agent",
                    labels = listOf("command-$index"),
                    completed = index < 4,
                ),
            )
        }
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "latest-server-commands",
                    displayedReasoningText = "",
                    activityStatusText = "正在执行",
                    executionTrace = trace,
                    isReasoningStreaming = true,
                    isReasoningComplete = false,
                    messageIsError = false,
                    mainContentHasStarted = false,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("reasoning-chain-summary-scanning", useUnmergedTree = true)
            .assertTextEquals("正在执行")
        assertTrue(
            composeRule.onAllNodesWithText("运行 Agent · command-4").fetchSemanticsNodes().isEmpty(),
        )

        composeRule.onNodeWithTag("reasoning-chain-summary-0").performClick()
        composeRule.mainClock.advanceTimeBy(250L)
        composeRule.waitForIdle()

        assertTrue(composeRule.onAllNodesWithText("运行 Agent · command-0").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("运行 Agent · command-1").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("运行 Agent · command-2").fetchSemanticsNode("")
        composeRule.onNodeWithText("运行 Agent · command-3").fetchSemanticsNode("")
        composeRule.onNodeWithText("运行 Agent · command-4").fetchSemanticsNode("")
    }

    @Test
    fun `执行链增长时按真实顺序增加多段内容并可打开工具详情`() {
        composeRule.mainClock.autoAdvance = false
        lateinit var appendExecutionTrace: () -> Unit
        var pixelsPerDp = 1f

        composeRule.setContent {
            pixelsPerDp = LocalDensity.current.density
            var executionTrace by remember {
                mutableStateOf<List<ExecutionTraceEvent>>(
                    listOf(ExecutionTraceEvent.Reasoning("先检查系统信息")),
                )
            }
            appendExecutionTrace = {
                executionTrace = executionTrace + listOf(
                    ExecutionTraceEvent.Tool(
                        ExecutionStep(
                            id = "exec-1",
                            type = ExecutionStepType.Agent,
                            title = "执行服务器命令",
                            labels = listOf("uname -a"),
                            completed = true,
                        ),
                    ),
                    ExecutionTraceEvent.Reasoning("继续检查磁盘空间"),
                    ExecutionTraceEvent.Tool(
                        ExecutionStep(
                            id = "exec-2",
                            type = ExecutionStepType.Agent,
                            title = "执行服务器命令",
                            labels = listOf("df -h"),
                            completed = true,
                        ),
                    ),
                )
            }
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "stable-chain-summary",
                    displayedReasoningText = "先检查系统信息",
                    executionTrace = executionTrace,
                    isReasoningStreaming = true,
                    isReasoningComplete = false,
                    messageIsError = false,
                    mainContentHasStarted = false,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
        val initialHeight = composeRule
            .onNodeWithTag("reasoning-chain-summaries")
            .fetchSemanticsNode("")
            .boundsInRoot
            .height
        composeRule.onNodeWithText("先检查系统信息").fetchSemanticsNode("")
        assertEquals(
            1,
            composeRule.onAllNodesWithTag("reasoning-chain-summary-0").fetchSemanticsNodes().size,
        )
        assertTrue(
            composeRule.onAllNodesWithTag("reasoning-chain-summary-1").fetchSemanticsNodes().isEmpty(),
        )

        composeRule.runOnIdle { appendExecutionTrace() }
        composeRule.mainClock.advanceTimeBy(250L)
        composeRule.waitForIdle()

        val grownHeight = composeRule
            .onNodeWithTag("reasoning-chain-summaries")
            .fetchSemanticsNode("")
            .boundsInRoot
            .height
        assertTrue(grownHeight > initialHeight)
        assertEquals(
            1,
            composeRule.onAllNodesWithTag("reasoning-chain-summary-0").fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithTag("reasoning-chain-summary-1").fetchSemanticsNode("")
        composeRule.onNodeWithTag("reasoning-chain-summary-2").fetchSemanticsNode("")
        composeRule.onNodeWithTag("reasoning-chain-summary-3").fetchSemanticsNode("")

        composeRule.onNodeWithTag("reasoning-chain-summary-1").performClick()
        composeRule.mainClock.advanceTimeBy(250L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("reasoning-chain-tool-1-0").performClick()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("reasoning-sheet-surface").fetchSemanticsNode("")
    }

    @Test
    fun `图像生成空列表加载态只显示真实状态不创建空抽屉`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                ImageGenerationLoadingView()
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("等待首个响应").fetchSemanticsNodes().isNotEmpty())

        composeRule.onNodeWithTag("reasoning-process-container").fetchSemanticsNode("")
        // 等待状态复用顶部标签，不再单独占用一行。
        composeRule.onNodeWithTag("reasoning-inline-status-text", useUnmergedTree = true)
            .assertTextEquals("等待首个响应")
            .assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithTag("reasoning-sheet-surface").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `执行期间总容器展开并实时显示过程文字`() {
        composeRule.mainClock.autoAdvance = false
        val reasoning = "第一行推理\n第二行推理"
        var pixelsPerDp = 1f

        composeRule.setContent {
            pixelsPerDp = LocalDensity.current.density
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "inline-status",
                    displayedReasoningText = reasoning,
                    isReasoningStreaming = true,
                    isReasoningComplete = false,
                    messageIsError = false,
                    mainContentHasStarted = false,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    modifier = Modifier.testTag("reasoning-root"),
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("reasoning-process-container").fetchSemanticsNode("")
        composeRule.onNodeWithTag("reasoning-chain-summary-0").fetchSemanticsNode("")
        assertTrue(
            composeRule.onAllNodesWithText("第一行推理", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )

        val rootHeight = composeRule
            .onNodeWithTag("reasoning-root")
            .fetchSemanticsNode("")
            .boundsInRoot
            .height
        assertTrue("展开后的执行过程应高于单行标题", rootHeight > 56f * pixelsPerDp)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `操作标签短文本自适应且用满气泡可用宽度后才省略`() {
        composeRule.mainClock.autoAdvance = false
        val status = mutableStateOf("执行中")
        val streaming = mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                ReasoningToggleAndContent(
                    modifier = Modifier.width(320.dp),
                    currentMessageId = "tool-label-width",
                    displayedReasoningText = "",
                    activityStatusText = status.value,
                    executionSteps = listOf(ExecutionStep(
                        id = "width-tool", type = ExecutionStepType.Agent,
                        title = "执行服务器命令", completed = true,
                    )),
                    isReasoningStreaming = false,
                    isReasoningComplete = true,
                    replyIsStreaming = streaming.value,
                    mainContentHasStarted = true,
                    messageIsError = false,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    onVisibilityChanged = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.onNodeWithTag("reasoning-inline-status").performClick()
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
        val availableBounds = composeRule.onNodeWithTag("reasoning-chain-summaries").fetchSemanticsNode().boundsInRoot
        var shortWidth = 0f
        for ((text, shouldEllipsize) in listOf(
            "执行中" to false,
            "正在运行，已用时 00:25" to false,
            "正在运行，正在检查网络接口、路由和DNS，等待服务器返回完整的检查结果" to true,
        )) {
            composeRule.runOnIdle { status.value = text }
            composeRule.mainClock.advanceTimeBy(100L)
            composeRule.waitForIdle()
            val label = composeRule.onNodeWithTag("reasoning-chain-summary-scanning", useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals("必须保持单行", 1, layout.lineCount)
            assertEquals("只能在达到最大宽度时省略：$text", shouldEllipsize, layout.isLineEllipsized(0))
            val bounds = composeRule.onNodeWithTag("reasoning-chain-summary-0").fetchSemanticsNode().boundsInRoot
            assertTrue("标签不能超出气泡", bounds.left >= availableBounds.left && bounds.right <= availableBounds.right + 1f)
            if (text == "执行中") shortWidth = bounds.width
            if (shouldEllipsize) {
                val textBounds = label.fetchSemanticsNode().boundsInRoot
                assertEquals("超长标签应使用全部可用宽度", availableBounds.right, bounds.right, 1f)
                assertTrue("短标签必须按内容收缩", shortWidth < bounds.width)
                assertTrue("文字不能只得到一半宽度", textBounds.width > availableBounds.width * 0.75f)
            }
        }
        composeRule.runOnIdle { streaming.value = false }
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
        val completedBounds = composeRule.onNodeWithTag("reasoning-chain-summary-0").fetchSemanticsNode().boundsInRoot
        assertTrue("完成后的操作数量标签也应自适应", completedBounds.width < availableBounds.width)
    }

    @Test
    fun `分析编辑执行和完成在最新工具标签原位切换且高度保持不变`() {
        composeRule.mainClock.autoAdvance = false
        val status = mutableStateOf<String?>("正在分析")
        val streaming = mutableStateOf(true)
        val pending = mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                ReasoningToggleAndContent(
                    modifier = Modifier.width(240.dp),
                    currentMessageId = "stable-tool-status",
                    displayedReasoningText = "继续检查配置",
                    activityStatusText = status.value,
                    executionTrace = listOf(
                        ExecutionTraceEvent.Tool(ExecutionStep(
                            id = "old-tool", type = ExecutionStepType.Agent,
                            title = "执行服务器命令", completed = true,
                        )),
                        ExecutionTraceEvent.Reasoning("继续检查配置"),
                        ExecutionTraceEvent.Tool(ExecutionStep(
                            id = "current-tool", type = ExecutionStepType.Agent,
                            title = "编辑配置", labels = listOf("edit_file"), completed = !pending.value,
                        )),
                    ),
                    isReasoningStreaming = false,
                    isReasoningComplete = true,
                    replyIsStreaming = streaming.value,
                    mainContentHasStarted = true,
                    messageIsError = false,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    onVisibilityChanged = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.onNodeWithTag("reasoning-inline-status").performClick()
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()

        val initialBounds = composeRule.onNodeWithTag("reasoning-chain-summary-2").fetchSemanticsNode().boundsInRoot
        val initialHeight = composeRule.onNodeWithTag("reasoning-process-container").fetchSemanticsNode().boundsInRoot.height
        for (nextStatus in listOf("正在编辑", "正在执行", "正在分析", "正在分析一段很长的工具结果并准备下一步操作", null)) {
            composeRule.runOnIdle {
                status.value = nextStatus
                pending.value = nextStatus == "正在执行"
                if (nextStatus == null) streaming.value = false
            }
            composeRule.mainClock.advanceTimeBy(500L)
            composeRule.waitForIdle()
            val tag = if (nextStatus == null) "reasoning-chain-summary-static" else "reasoning-chain-summary-scanning"
            val labels = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals(if (nextStatus == null) 2 else 1, labels.size)
            composeRule.onNodeWithTag("reasoning-chain-summary-2")
                .assertHasClickAction()
            if (nextStatus != null) {
                composeRule.onNodeWithTag("reasoning-chain-summary-scanning", useUnmergedTree = true)
                    .assertTextEquals(nextStatus)
                    .assertIsDisplayed()
            } else {
                assertEquals(2, composeRule.onAllNodesWithText("执行了 1 项服务器操作", useUnmergedTree = true)
                    .fetchSemanticsNodes().size)
            }
            val bounds = composeRule.onNodeWithTag("reasoning-chain-summary-2").fetchSemanticsNode().boundsInRoot
            assertEquals("标签位置不能随状态变化", initialBounds.top, bounds.top, 0.5f)
            assertEquals("标签高度不能随状态变化", initialBounds.height, bounds.height, 0.5f)
            assertEquals("过程区域不能因状态行增删而抖动", initialHeight,
                composeRule.onNodeWithTag("reasoning-process-container").fetchSemanticsNode().boundsInRoot.height, 0.5f)
            composeRule.onNodeWithTag("reasoning-chain-live-status", useUnmergedTree = true).assertDoesNotExist()
        }
        composeRule.onNodeWithTag("reasoning-chain-summary-2").performClick()
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.onNodeWithTag("reasoning-chain-tool-2-0").assertIsDisplayed()
    }

    @Test
    fun `正文后尚无完整工具记录时显示正在编写`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "writing-tool",
                    displayedReasoningText = "",
                    activityStatusText = TOOL_CALL_WRITING_STATUS,
                    isReasoningStreaming = false,
                    isReasoningComplete = true,
                    replyIsStreaming = true,
                    messageIsError = false,
                    mainContentHasStarted = true,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    onVisibilityChanged = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("reasoning-inline-status-text", useUnmergedTree = true)
            .assertTextEquals("正在编写")
        composeRule.onNodeWithTag("reasoning-chain-live-status", useUnmergedTree = true).assertDoesNotExist()
        assertTrue(composeRule.onAllNodesWithText("正在编写").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("执行了 1 项服务器操作").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `没有工具标签时正在编写复用顶部标题且不新增状态行`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "writing-after-commentary",
                    displayedReasoningText = "",
                    executionTrace = listOf(ExecutionTraceEvent.Content("接下来编辑配置文件。")),
                    activityStatusText = TOOL_CALL_WRITING_STATUS,
                    isReasoningStreaming = false,
                    isReasoningComplete = true,
                    replyIsStreaming = true,
                    messageIsError = false,
                    mainContentHasStarted = false,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    onVisibilityChanged = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("reasoning-chain-commentary").assertIsDisplayed()
        composeRule.onNodeWithTag("reasoning-inline-status-text", useUnmergedTree = true)
            .assertTextEquals("正在编写")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("reasoning-chain-live-status", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(1, composeRule.onAllNodesWithText("正在编写", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `没有真实过程事件时只显示实时工具状态`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "sheet-loader",
                    displayedReasoningText = "",
                    activityStatusText = "调用工具 · search_docs",
                    isReasoningStreaming = true,
                    isReasoningComplete = false,
                    messageIsError = false,
                    mainContentHasStarted = false,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()

        assertTrue(
            composeRule.onAllNodesWithContentDescription("执行过程加载中")
                .fetchSemanticsNodes()
                .isEmpty()
        )
        assertTrue(
            composeRule.onAllNodesWithText("调用工具 · search_docs")
                .fetchSemanticsNodes()
                .isNotEmpty()
        )

        composeRule.mainClock.advanceTimeBy(2_100L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("reasoning-inline-status-text", useUnmergedTree = true)
            .assertTextEquals("调用工具 · search_docs")
            .assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithTag("reasoning-sheet-surface").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `回复仍在执行且前导正文出现后工具组默认收起并可手动展开收起`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "agent-after-content",
                    displayedReasoningText = "先检查系统服务",
                    activityStatusText = "执行服务器命令",
                    executionSteps = listOf(
                        ExecutionStep(
                            id = "call-1",
                            type = ExecutionStepType.Agent,
                            title = "运行 Agent",
                            labels = listOf("exec"),
                        )
                    ),
                    isReasoningStreaming = false,
                    isReasoningComplete = true,
                    // 思考已结束但整条回复还在执行；不能让默认 false 把本场景误设为已完成历史消息。
                    replyIsStreaming = true,
                    messageIsError = false,
                    mainContentHasStarted = true,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("reasoning-inline-status").fetchSemanticsNode("")
        composeRule.onNodeWithTag("reasoning-chain-summaries").assertDoesNotExist()
        composeRule.onNodeWithTag("reasoning-inline-status").performClick()
        composeRule.mainClock.advanceTimeBy(300L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("reasoning-chain-summaries").assertIsDisplayed()
        composeRule.onNodeWithTag("reasoning-chain-tool-1-0").assertDoesNotExist()
        composeRule.onNodeWithTag("reasoning-chain-summary-1").assertHasClickAction().performClick()
        composeRule.mainClock.advanceTimeBy(250L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("reasoning-chain-tool-1-0").assertIsDisplayed()
        composeRule.onNodeWithText("运行 Agent · exec").assertIsDisplayed()

        composeRule.onNodeWithTag("reasoning-chain-summary-1").performClick()
        // 关闭自动时钟后，退出动画要等重组及后续帧完成；按节点移除等待，避免卡在动画末帧。
        composeRule.mainClock.advanceTimeUntil(timeoutMillis = 2_000L) {
            composeRule.onAllNodesWithTag("reasoning-chain-tool-1-0").fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("reasoning-chain-tool-1-0").assertDoesNotExist()
        composeRule.onNodeWithTag("reasoning-chain-summary-1").assertIsDisplayed()
    }

    @Test
    fun `外部显示最新过程且抽屉使用工具和网站胶囊时间线`() {
        composeRule.mainClock.autoAdvance = false
        val query = "EveryTalk Android 执行过程抽屉垂直时间线与工具胶囊布局验证长查询"
        var pixelsPerDp = 1f
        composeRule.setContent {
            pixelsPerDp = LocalDensity.current.density
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "timeline-pills",
                    displayedReasoningText = "正在核对搜索结果",
                    activityStatusText = "搜索网页 · $query",
                    executionSteps = listOf(
                        ExecutionStep(
                            id = "search-1",
                            type = ExecutionStepType.Search,
                            title = "搜索网页",
                            labels = listOf(query),
                        )
                    ),
                    webSearchResults = listOf(
                        WebSearchResult(1, "EveryTalk", "https://github.com/example/everytalk", ""),
                        WebSearchResult(2, "文档", "https://developer.android.com/compose", ""),
                    ),
                    isReasoningStreaming = true,
                    isReasoningComplete = false,
                    messageIsError = false,
                    mainContentHasStarted = false,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("正在核对搜索结果").fetchSemanticsNode("")
        composeRule.onNodeWithTag("reasoning-chain-summary-scanning", useUnmergedTree = true)
            .assertTextEquals("搜索网页 · $query")

        openReasoningSheet()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("reasoning-execution-step-0").fetchSemanticsNode("")
        assertEquals(
            1,
            composeRule.onAllNodesWithTag("reasoning-timeline-icon-active")
                .fetchSemanticsNodes().size,
        )
        assertTrue(
            composeRule.onAllNodesWithTag("reasoning-timeline-icon-static")
                .fetchSemanticsNodes().isNotEmpty(),
        )
        composeRule.onNodeWithTag("reasoning-execution-label-0").fetchSemanticsNode("")
        composeRule.onNodeWithTag("reasoning-website-label-0").fetchSemanticsNode("")
        composeRule.onNodeWithTag("reasoning-website-label-1").fetchSemanticsNode("")
        composeRule.onNodeWithText("github.com").fetchSemanticsNode("")
        composeRule.onNodeWithText("developer.android.com").fetchSemanticsNode("")
    }

    @Test
    fun `外部过程优先显示真实工具状态和最新思考文本`() {
        val steps = listOf(
            ExecutionStep(
                id = "tool-1",
                type = ExecutionStepType.Tool,
                title = "调用工具",
                labels = listOf("search_docs"),
            )
        )
        assertEquals(
            "调用工具 · search_docs",
            executionSummaryText("**分析资料**", "调用工具 · search_docs", steps),
        )
        assertEquals(
            "核对第二项",
            executionSummaryText("**分析资料**\n- 核对第二项", "正在接收思考", steps),
        )
    }

    @Test
    fun `网站胶囊与网页执行标签单击打开原始链接`() {
        var openedUri: String? = null
        val testUriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                openedUri = uri
            }
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalUriHandler provides testUriHandler) {
                MaterialTheme {
                    ThinkingExecutionTimeline(
                        executionSteps = listOf(
                            ExecutionStep(
                                id = "web-1",
                                type = ExecutionStepType.Web,
                                title = "访问网页",
                                labels = listOf("https://developer.android.com/compose"),
                            )
                        ),
                        webSearchResults = listOf(
                            WebSearchResult(
                                index = 1,
                                title = "EveryTalk",
                                href = "https://github.com/example/everytalk",
                                snippet = "",
                            )
                        ),
                        activityStatusText = null,
                        reasoningText = "",
                        isReasoningActive = true,
                        messageIsError = false,
                    ) { _, _ -> }
                }
            }
        }

        composeRule.onNodeWithTag("reasoning-website-label-0")
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle {
            assertEquals("https://github.com/example/everytalk", openedUri)
        }
        composeRule.onNodeWithTag("reasoning-execution-label-0")
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle {
            assertEquals("https://developer.android.com/compose", openedUri)
        }
    }

    @Test
    fun `只有工具调用没有思考文本时仍可回看执行时间线`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "tool-only-review",
                    displayedReasoningText = "",
                    executionSteps = listOf(
                        ExecutionStep(
                            id = "tool-1",
                            type = ExecutionStepType.Tool,
                            title = "调用工具",
                            labels = listOf("local_clock"),
                            completed = true,
                        )
                    ),
                    isReasoningStreaming = false,
                    isReasoningComplete = true,
                    messageIsError = false,
                    mainContentHasStarted = true,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        openReasoningSheet()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("reasoning-execution-step-0").fetchSemanticsNode("")
        composeRule.onNodeWithText("local_clock").fetchSemanticsNode("")
        composeRule.onNodeWithTag("reasoning-execution-finish-step").fetchSemanticsNode("")
        assertTrue(composeRule.onAllNodesWithText("暂无详细思考内容").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `执行失败且有工具记录时仍可通过圆点回看`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "failed-tool-review",
                    displayedReasoningText = "",
                    executionSteps = listOf(
                        ExecutionStep(
                            id = "failed-tool-1",
                            type = ExecutionStepType.Tool,
                            title = "调用工具",
                            labels = listOf("local_clock"),
                            completed = true,
                        )
                    ),
                    isReasoningStreaming = false,
                    isReasoningComplete = true,
                    messageIsError = true,
                    mainContentHasStarted = true,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        openReasoningSheet()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("reasoning-execution-step-0").fetchSemanticsNode("")
        composeRule.onNodeWithText("local_clock").fetchSemanticsNode("")
        assertTrue(composeRule.onAllNodesWithText("执行失败").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `抽屉渲染Markdown并分隔连续加粗段落`() {
        composeRule.mainClock.autoAdvance = false
        val reasoning =
            "**Calculating reimbursement amounts in RMB****Verifying currency symbols and exchange context**"
        var pixelsPerDp = 1f

        composeRule.setContent {
            pixelsPerDp = LocalDensity.current.density
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "markdown-reasoning-sheet",
                    displayedReasoningText = reasoning,
                    isReasoningStreaming = true,
                    isReasoningComplete = false,
                    messageIsError = false,
                    mainContentHasStarted = false,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        openReasoningSheet()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()

        assertTrue(composeRule.onAllNodesWithText(reasoning).fetchSemanticsNodes().isEmpty())
        val firstSection = composeRule
            .onAllNodesWithText("Calculating reimbursement amounts in RMB", substring = true)
            .fetchSemanticsNodes()
            .last()
        val secondSection = composeRule
            .onAllNodesWithText("Verifying currency symbols and exchange context", substring = true)
            .fetchSemanticsNodes()
            .last()
        assertTrue("连续加粗段落没有换行", secondSection.boundsInRoot.top > firstSection.boundsInRoot.top)
        val statusBottom = composeRule
            .onNodeWithTag("reasoning-active-step-title")
            .fetchSemanticsNode("")
            .boundsInRoot
            .bottom
        val markdownTop = composeRule
            .onNodeWithTag("reasoning-sheet-markdown")
            .fetchSemanticsNode("")
            .boundsInRoot
            .top
        assertTrue(
            "状态与正文间距异常：${markdownTop - statusBottom}",
            markdownTop - statusBottom in 0f..20f * pixelsPerDp,
        )
    }

    @Test
    fun `连续加粗推理片段转换为Markdown段落`() {
        assertEquals(
            "**第一步**\n\n**第二步**\n\n**第三步**",
            normalizeReasoningMarkdown("**第一步****第二步****第三步**"),
        )
        assertEquals(
            "```text\n**第一步****第二步**\n```",
            normalizeReasoningMarkdown("```text\n**第一步****第二步**\n```"),
        )
        assertEquals(
            "第一行  \n第二行",
            normalizeReasoningMarkdown("第一行\n第二行"),
        )
        assertEquals(
            "第一段\n\n第二段",
            normalizeReasoningMarkdown("第一段\n\n第二段"),
        )
    }

    @Test
    fun `打开抽屉后实时显示新增思考内容`() {
        composeRule.mainClock.autoAdvance = false
        lateinit var appendReasoning: () -> Unit

        composeRule.setContent {
            var reasoning by remember { mutableStateOf("第一段思考") }
            appendReasoning = { reasoning += "\n第二段思考" }
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "live-sheet",
                    displayedReasoningText = reasoning,
                    isReasoningStreaming = true,
                    isReasoningComplete = false,
                    messageIsError = false,
                    mainContentHasStarted = false,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        openReasoningSheet()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        assertTrue(composeRule.onAllNodesWithText("第一段思考").fetchSemanticsNodes().isNotEmpty())

        composeRule.runOnIdle { appendReasoning() }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        assertTrue(
            composeRule.onAllNodesWithText("第一段思考", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        )
        assertTrue(
            composeRule.onAllNodesWithText("第二段思考", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        )
    }

    @Test
    fun `短思考内容默认停在中档且可上拉进入全屏`() {
        composeRule.mainClock.autoAdvance = false
        lateinit var scrollState: ScrollState

        composeRule.setContent {
            scrollState = rememberScrollState()
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "short-sheet",
                    displayedReasoningText = "简短思考内容",
                    isReasoningStreaming = true,
                    isReasoningComplete = false,
                    messageIsError = false,
                    mainContentHasStarted = false,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    streamingScrollState = scrollState,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        openReasoningSheet()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(
            "reasoning-sheet-state-PartiallyExpanded-overflow-false",
        ).fetchSemanticsNode("")
        composeRule.onNodeWithTag("reasoning-sheet-header").fetchSemanticsNode("")
        composeRule.onNodeWithTag("reasoning-sheet-bottom-indicator").fetchSemanticsNode("")
        assertEquals(0, scrollState.maxValue)
        val partialTop = composeRule
            .onNodeWithTag("reasoning-sheet-content")
            .fetchSemanticsNode("")
            .boundsInRoot
            .top
        composeRule
            .onNodeWithTag("reasoning-sheet-drag-handle-enabled", useUnmergedTree = true)
            .performTouchInput {
            val start = center
            swipe(start, start.copy(y = start.y - 600f), durationMillis = 500L)
        }
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        val topAfterExpanded = composeRule
            .onNodeWithTag("reasoning-sheet-content")
            .fetchSemanticsNode("")
            .boundsInRoot
            .top
        assertTrue("抽屉上拉后未进入全屏", topAfterExpanded < partialTop)
        composeRule.onNodeWithTag(
            "reasoning-sheet-state-Expanded-overflow-false",
        ).fetchSemanticsNode("")
    }

    @Test
    fun `长思考内容实时增长并保留用户阅读位置`() {
        composeRule.mainClock.autoAdvance = false
        lateinit var scrollState: ScrollState
        lateinit var appendReasoning: () -> Unit

        composeRule.setContent {
            var reasoning by remember {
                mutableStateOf((1..60).joinToString("\n") { "初始推理第${it}行" })
            }
            scrollState = rememberScrollState()
            appendReasoning = {
                reasoning += (1..20).joinToString(
                    separator = "\n",
                    prefix = "\n",
                ) { "新增推理第${it}行" }
            }
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "long-sheet",
                    displayedReasoningText = reasoning,
                    isReasoningStreaming = true,
                    isReasoningComplete = false,
                    messageIsError = false,
                    mainContentHasStarted = false,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    streamingScrollState = scrollState,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        openReasoningSheet()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        val initialMaxValue = scrollState.maxValue
        val initialMarkdownBounds = composeRule
            .onNodeWithTag("reasoning-sheet-markdown")
            .fetchSemanticsNode("")
            .boundsInRoot
        val initialViewportHeight = composeRule
            .onNodeWithTag("reasoning-sheet-scroll")
            .fetchSemanticsNode("")
            .boundsInRoot
            .height
        assertTrue(
            "长内容不可滚动：max=$initialMaxValue, markdown=${initialMarkdownBounds.size}, " +
                "viewport=$initialViewportHeight",
            initialMaxValue > 0,
        )
        assertEquals(0, scrollState.value)

        composeRule.runOnIdle { appendReasoning() }
        repeat(4) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }

        composeRule.runOnIdle {
            assertTrue(
                "新增内容后滚动范围没有增长：before=$initialMaxValue, after=${scrollState.maxValue}",
                scrollState.maxValue > initialMaxValue,
            )
            assertEquals(0, scrollState.value)
        }
    }

    @Test
    fun `圆点打开已有长内容时默认停在中档`() {
        composeRule.mainClock.autoAdvance = false

        composeRule.setContent {
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "completed-long-sheet",
                    displayedReasoningText = (1..80).joinToString("\n") { "已完成推理第${it}行" },
                    isReasoningStreaming = false,
                    isReasoningComplete = true,
                    messageIsError = false,
                    mainContentHasStarted = true,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        openReasoningSheet()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        val partialStateNodes = composeRule
            .onAllNodesWithTag("reasoning-sheet-state-PartiallyExpanded-overflow-true")
            .fetchSemanticsNodes()
        assertTrue(
            "长内容未停在中档：partialOverflow=${partialStateNodes.size}",
            partialStateNodes.size == 1,
        )

    }

    @Test
    fun `执行中打开已有长内容时默认停在中档`() {
        composeRule.mainClock.autoAdvance = false

        composeRule.setContent {
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "active-long-sheet",
                    displayedReasoningText = (1..80).joinToString("\n") { "执行中推理第${it}行" },
                    isReasoningStreaming = true,
                    isReasoningComplete = false,
                    messageIsError = false,
                    mainContentHasStarted = false,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        openReasoningSheet()
        var openingSurfaceReady = false
        var openingProbeAttempts = 0
        while (!openingSurfaceReady && openingProbeAttempts < 10) {
            composeRule.mainClock.advanceTimeBy(50L)
            composeRule.waitForIdle()
            openingSurfaceReady = composeRule.onAllNodesWithTag("reasoning-sheet-content")
                .fetchSemanticsNodes().isNotEmpty()
            openingProbeAttempts++
        }
        assertTrue("长执行抽屉没有开始打开", openingSurfaceReady)
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(
            "reasoning-sheet-state-PartiallyExpanded-overflow-true",
        ).fetchSemanticsNode("")
    }

    @Test
    fun `圆点打开短内容时保持当前高度且可下拉关闭`() {
        composeRule.mainClock.autoAdvance = false

        composeRule.setContent {
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "completed-short-sheet",
                    displayedReasoningText = "简短的已完成推理",
                    isReasoningStreaming = false,
                    isReasoningComplete = true,
                    messageIsError = false,
                    mainContentHasStarted = true,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        openReasoningSheet()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(
            "reasoning-sheet-state-PartiallyExpanded-overflow-false",
        ).fetchSemanticsNode("")

        composeRule.onNodeWithTag("reasoning-sheet-scroll").performTouchInput {
            val start = center.copy(y = top + 40f)
            swipe(start, start.copy(y = start.y + 520f), durationMillis = 500L)
        }
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()

        assertTrue(
            "默认高度抽屉下拉后仍未关闭",
            composeRule.onAllNodesWithTag("reasoning-sheet-content").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `抽屉内容由短变长后保持中档并保留正文滚动`() {
        composeRule.mainClock.autoAdvance = false
        lateinit var scrollState: ScrollState
        lateinit var appendLongReasoning: () -> Unit

        composeRule.setContent {
            var reasoning by remember { mutableStateOf("第一行思考") }
            scrollState = rememberScrollState()
            appendLongReasoning = {
                reasoning += (1..80).joinToString(
                    separator = "\n",
                    prefix = "\n",
                ) { "持续推理第${it}行" }
            }
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "growing-sheet",
                    displayedReasoningText = reasoning,
                    isReasoningStreaming = true,
                    isReasoningComplete = false,
                    messageIsError = false,
                    mainContentHasStarted = false,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    streamingScrollState = scrollState,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        openReasoningSheet()
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        assertEquals(0, scrollState.maxValue)
        val initialSheetBounds = composeRule
            .onNodeWithTag("reasoning-sheet-content")
            .fetchSemanticsNode("")
            .boundsInRoot

        composeRule.runOnIdle { appendLongReasoning() }
        repeat(3) {
            composeRule.mainClock.advanceTimeBy(1_000L)
            composeRule.waitForIdle()
        }

        composeRule.runOnIdle {
            assertTrue(
                "增长后的 Markdown 仍不可滚动：max=${scrollState.maxValue}",
                scrollState.maxValue > 0,
            )
            assertEquals(0, scrollState.value)
        }
        val topAfterOverflow = composeRule
            .onNodeWithTag("reasoning-sheet-content")
            .fetchSemanticsNode("")
            .boundsInRoot
            .top
        assertTrue(
            "内容溢出后抽屉不应自动进入全屏：before=${initialSheetBounds.top}, " +
                "after=$topAfterOverflow",
            kotlin.math.abs(topAfterOverflow - initialSheetBounds.top) <= 1f,
        )
        composeRule.onNodeWithTag(
            "reasoning-sheet-drag-handle-enabled",
            useUnmergedTree = true,
        ).fetchSemanticsNode("")

        composeRule.onNodeWithTag(
            "reasoning-sheet-drag-handle-enabled",
            useUnmergedTree = true,
        ).performTouchInput {
            val start = center
            swipe(start, start.copy(y = start.y - 600f), durationMillis = 500L)
        }
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(
            "reasoning-sheet-state-Expanded-overflow-true",
        ).fetchSemanticsNode("")
        val expandedTop = composeRule
            .onNodeWithTag("reasoning-sheet-content")
            .fetchSemanticsNode("")
            .boundsInRoot
            .top
        assertTrue("中档抽屉上拉后未进入全屏", expandedTop < topAfterOverflow)

        composeRule.onNodeWithTag("reasoning-sheet-scroll").performTouchInput {
            val start = center
            swipe(start, start.copy(y = start.y - 240f), durationMillis = 500L)
        }
        composeRule.waitForIdle()
        val topAfterContentScroll = composeRule
            .onNodeWithTag("reasoning-sheet-content")
            .fetchSemanticsNode("")
            .boundsInRoot
            .top
        assertEquals(expandedTop, topAfterContentScroll, 1f)
        assertTrue("全屏抽屉正文未接管滚动", scrollState.value > 0)
        composeRule.onNodeWithTag(
            "reasoning-sheet-drag-handle-enabled",
            useUnmergedTree = true,
        ).fetchSemanticsNode("")

        composeRule.onNodeWithTag("reasoning-sheet-scroll").performTouchInput {
            val start = center
            swipe(start, start.copy(y = start.y + 80f), durationMillis = 300L)
        }
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
        val topWhileContentNotAtBoundary = composeRule
            .onNodeWithTag("reasoning-sheet-content")
            .fetchSemanticsNode("")
            .boundsInRoot
            .top
        assertEquals(expandedTop, topWhileContentNotAtBoundary, 1f)
        assertTrue("正文未到边界时被抽屉抢走下拉手势", scrollState.value > 0)

        composeRule.runOnIdle {
            scrollState.dispatchRawDelta(-scrollState.value.toFloat())
            assertEquals(0, scrollState.value)
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(
            "reasoning-sheet-drag-handle-enabled",
            useUnmergedTree = true,
        ).fetchSemanticsNode("")
        composeRule.onNodeWithTag("reasoning-sheet-scroll").performTouchInput {
            val start = center.copy(y = top + 48f)
            swipe(start, start.copy(y = start.y + 180f), durationMillis = 500L)
        }
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
        val collapsedTop = composeRule
            .onNodeWithTag("reasoning-sheet-content")
            .fetchSemanticsNode("")
            .boundsInRoot
            .top
        assertTrue("正文位于顶部时再次下拉未收起抽屉", collapsedTop > expandedTop)
    }
}
