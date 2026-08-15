package com.android.everytalk.ui.screens.BubbleMain.Main

import android.app.Application
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.android.everytalk.data.DataClass.ExecutionStep
import com.android.everytalk.data.DataClass.ExecutionStepType
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.WebSearchResult
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
        composeRule.onNodeWithTag("reasoning-chain-live-status").fetchSemanticsNode("")
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
        composeRule.onNodeWithTag("reasoning-chain-live-status").fetchSemanticsNode("")
        assertTrue(
            composeRule.onAllNodesWithTag("reasoning-sheet-surface").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `前导正文出现后工具运行状态仍持续显示`() {
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
        composeRule.onNodeWithText("运行 Agent · exec").fetchSemanticsNode("")
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
        composeRule.onNodeWithText("搜索了 1 次网页").fetchSemanticsNode("")

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
    fun `短思考内容使用固定加高抽屉且内部不可滚动`() {
        composeRule.mainClock.autoAdvance = false
        lateinit var scrollState: ScrollState
        var windowHeightPx = 1f
        var pixelsPerDp = 1f

        composeRule.setContent {
            scrollState = rememberScrollState()
            windowHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
            pixelsPerDp = LocalDensity.current.density
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

        val sheetHeight = composeRule
            .onNodeWithTag("reasoning-sheet-surface")
            .fetchSemanticsNode("")
            .boundsInRoot
            .height
        assertEquals(
            windowHeightPx * reasoningSheetTallHeightFraction(),
            sheetHeight,
            2f * pixelsPerDp,
        )
        assertTrue("抽屉触发了全屏高度", sheetHeight < windowHeightPx)
        composeRule.onNodeWithTag("reasoning-sheet-header").fetchSemanticsNode("")
        composeRule.onNodeWithTag("reasoning-sheet-bottom-indicator").fetchSemanticsNode("")
        assertEquals(0, scrollState.maxValue)
        val lockedTop = composeRule
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
        val topAfterLockedSwipe = composeRule
            .onNodeWithTag("reasoning-sheet-content")
            .fetchSemanticsNode("")
            .boundsInRoot
            .top
        assertEquals(lockedTop, topAfterLockedSwipe, 1f)
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
    fun `圆点打开已有长内容时默认展开抽屉`() {
        composeRule.mainClock.autoAdvance = false
        var windowHeightPx = 1f

        composeRule.setContent {
            windowHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
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
        val expandedStateNodes = composeRule
            .onAllNodesWithTag("reasoning-sheet-state-Expanded-overflow-true")
            .fetchSemanticsNodes()
        assertTrue(
            "长内容未进入展开状态：partialOverflow=" +
                composeRule.onAllNodesWithTag(
                    "reasoning-sheet-state-PartiallyExpanded-overflow-true",
                ).fetchSemanticsNodes().size +
                ", partialNoOverflow=" +
                composeRule.onAllNodesWithTag(
                    "reasoning-sheet-state-PartiallyExpanded-overflow-false",
                ).fetchSemanticsNodes().size +
                ", expandedNoOverflow=" +
                composeRule.onAllNodesWithTag(
                    "reasoning-sheet-state-Expanded-overflow-false",
                ).fetchSemanticsNodes().size +
                ", hiddenOverflow=" +
                composeRule.onAllNodesWithTag(
                    "reasoning-sheet-state-Hidden-overflow-true",
                ).fetchSemanticsNodes().size +
                ", hiddenNoOverflow=" +
                composeRule.onAllNodesWithTag(
                    "reasoning-sheet-state-Hidden-overflow-false",
                ).fetchSemanticsNodes().size,
            expandedStateNodes.size == 1,
        )

        val expandedTop = composeRule
            .onNodeWithTag("reasoning-sheet-content")
            .fetchSemanticsNode("")
            .boundsInRoot
            .top
        assertTrue(
            "圆点打开长内容后抽屉未默认展开：top=$expandedTop, window=$windowHeightPx",
            expandedTop < windowHeightPx * 0.35f,
        )
    }

    @Test
    fun `执行中打开已有长内容时默认展开抽屉`() {
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
        composeRule.onNodeWithTag("reasoning-sheet-content").performTouchInput {
            click(center)
        }
        var passedThroughPartialHeight = false
        repeat(30) {
            composeRule.mainClock.advanceTimeBy(100L)
            composeRule.waitForIdle()
            passedThroughPartialHeight = passedThroughPartialHeight ||
                composeRule.onAllNodesWithTag(
                    "reasoning-sheet-state-PartiallyExpanded-overflow-true",
                ).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag(
                    "reasoning-sheet-state-PartiallyExpanded-overflow-false",
                ).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag(
            "reasoning-sheet-state-Expanded-overflow-true",
        ).fetchSemanticsNode("")
        assertTrue("长执行抽屉曾先停在默认高度", !passedThroughPartialHeight)
    }

    @Test
    fun `圆点打开短内容时保持默认高度且可下拉关闭`() {
        composeRule.mainClock.autoAdvance = false
        var windowHeightPx = 1f

        composeRule.setContent {
            windowHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
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

        val defaultTop = composeRule
            .onNodeWithTag("reasoning-sheet-content")
            .fetchSemanticsNode("")
            .boundsInRoot
            .top
        val timelineHeight = composeRule
            .onNodeWithTag("reasoning-execution-timeline")
            .fetchSemanticsNode("")
            .boundsInRoot
            .height
        assertTrue(
            "短内容错误触发展开：top=$defaultTop, window=$windowHeightPx, timeline=$timelineHeight",
            defaultTop > windowHeightPx * 0.35f,
        )

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
    fun `抽屉内容由短变长并溢出默认高度时自动展开`() {
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
        val automaticallyExpandedTop = composeRule
            .onNodeWithTag("reasoning-sheet-content")
            .fetchSemanticsNode("")
            .boundsInRoot
            .top
        assertTrue(
            "内容溢出后未自动展开抽屉：before=${initialSheetBounds.top}, " +
                "after=$automaticallyExpandedTop",
            automaticallyExpandedTop < initialSheetBounds.top,
        )
        composeRule.onNodeWithTag(
            "reasoning-sheet-drag-handle-enabled",
            useUnmergedTree = true,
        ).fetchSemanticsNode("")

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
        assertEquals(automaticallyExpandedTop, topAfterContentScroll, 1f)
        assertTrue("加高抽屉展开后正文未接管滚动", scrollState.value > 0)
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
        assertEquals(automaticallyExpandedTop, topWhileContentNotAtBoundary, 1f)
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
        assertTrue("正文位于顶部时再次下拉未收起抽屉", collapsedTop > automaticallyExpandedTop)
    }
}
