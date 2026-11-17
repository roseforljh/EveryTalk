package com.android.everytalk.ui.screens.BubbleMain.Main

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.R
import com.android.everytalk.data.DataClass.ExecutionStep
import com.android.everytalk.data.DataClass.ExecutionStepType
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.ui.components.math.MathJaxSvgRenderer
import org.junit.After
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
@Config(sdk = [34], application = Application::class)
class ContextCompressionStatusComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var mathRenderer: MathJaxSvgRenderer

    @Before
    fun setUp() {
        stopKoin()
        mathRenderer = MathJaxSvgRenderer(ApplicationProvider.getApplicationContext())
        startKoin { modules(module { single { mathRenderer } }) }
    }

    @After
    fun tearDown() {
        stopKoin()
        mathRenderer.close()
    }

    private fun openReasoningSheet() {
        composeRule.onNodeWithTag("reasoning-inline-status").performClick()
        composeRule.mainClock.advanceTimeBy(250L)
        composeRule.waitForIdle()
        if (composeRule.onAllNodesWithTag("reasoning-sheet-surface").fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithTag("reasoning-chain-summary-0").performClick()
        }
    }

    @Test
    fun `压缩期间显示扫描高光状态且不提供空展开入口`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val localizedStatus = context.getString(R.string.thinking_context_compressing)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "compressing",
                    displayedReasoningText = "",
                    activityStatusText = "正在压缩上下文",
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
        composeRule.onNodeWithTag("reasoning-inline-status").assertIsDisplayed()
        composeRule.onNodeWithText(localizedStatus).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.thinking_expand_execution)).assertDoesNotExist()
        composeRule.onNodeWithTag("reasoning-chain-summaries").assertDoesNotExist()
    }

    @Test
    fun `自动压缩状态显示本次模型真实预算且保留扫描动画`() {
        val status = "正在压缩上下文 · gpt-test · 估算 312000 / 272000 · 触发线 217600"
        val context = ApplicationProvider.getApplicationContext<Application>()
        val localized = context.getString(R.string.thinking_context_compressing_budget,
            "gpt-test", "312000", "272000", "217600")
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "compression-budget",
                    displayedReasoningText = "",
                    activityStatusText = status,
                    replyIsStreaming = true,
                    isReasoningStreaming = false,
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
        composeRule.onNodeWithText(localized).assertIsDisplayed()
        composeRule.onAllNodesWithTag("reasoning-inline-status").assertCountEquals(1)
        composeRule.onNodeWithTag("reasoning-sheet-surface").assertDoesNotExist()
        composeRule.mainClock.advanceTimeBy(1_700L)
        composeRule.onNodeWithText(localized).assertIsDisplayed()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `有工具记录时压缩预算仍完整换行而不是省略数字`() {
        val tool = ExecutionStep(
            id = "done-tool", type = ExecutionStepType.Tool, title = "调用工具", completed = true,
        )
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "compression-with-tools",
                    displayedReasoningText = "",
                    activityStatusText = "正在压缩上下文 · gpt-test · 估算 312000 / 272000 · 触发线 217600",
                    executionTrace = listOf(ExecutionTraceEvent.Tool(tool)),
                    replyIsStreaming = true,
                    isReasoningStreaming = false,
                    isReasoningComplete = false,
                    messageIsError = false,
                    mainContentHasStarted = false,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    modifier = Modifier.width(280.dp),
                    onVisibilityChanged = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(500L)
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithTag("reasoning-chain-summary-scanning", useUnmergedTree = true)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        org.junit.Assert.assertTrue("压缩预算至少显示三行", layout.lineCount >= 3)
        // 标签按内容收缩，段落预留宽度可能大于实际文字宽度；应检查每行文字而不是段落空白。
        repeat(layout.lineCount) { line ->
            org.junit.Assert.assertFalse("预算第 ${line + 1} 行不能省略", layout.isLineEllipsized(line))
            org.junit.Assert.assertTrue("预算文字不能超出标签", layout.getLineRight(line) <= layout.size.width + 1f)
        }
        org.junit.Assert.assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        org.junit.Assert.assertFalse("预算行不能被纵向裁切", layout.didOverflowHeight)
    }

    @Test
    fun `正文后等待指示也完整显示压缩预算`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                com.android.everytalk.ui.screens.MainScreen.chat.text.ui.LoadingStageIndicator(
                    text = checkNotNull(localizedExecutionStatusText(
                        "正在压缩上下文 · gpt-test · 估算 312000 / 272000 · 触发线 217600",
                    )),
                    modifier = Modifier.width(280.dp),
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(500L)
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(
            ApplicationProvider.getApplicationContext<Application>().getString(
                R.string.thinking_context_compressing_budget, "gpt-test", "312000", "272000", "217600",
            ),
        ).assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        org.junit.Assert.assertTrue("等待指示不能裁掉预算行", layout.lineCount >= 3)
        org.junit.Assert.assertFalse(layout.hasVisualOverflow)
    }

    @Test
    @Config(qualifiers = "en")
    fun `英文压缩状态保留本次模型与全部预算数字`() {
        composeRule.setContent {
            androidx.compose.material3.Text(checkNotNull(localizedExecutionStatusText(
                "正在压缩上下文 · gpt-test · 估算 312000 / 272000 · 触发线 217600",
            )))
        }
        composeRule.onNodeWithText("Compressing context · gpt-test\nEstimated 312000 / 272000\nTrigger 217600")
            .assertIsDisplayed()
    }

    @Test
    fun `压缩失败后圆点可回看具体原因`() {
        val failure = "上下文压缩失败：API 返回 429"
        val context = ApplicationProvider.getApplicationContext<Application>()
        val localizedFailure = context.getString(
            R.string.thinking_context_compression_failed,
            "API 返回 429",
        )
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                ReasoningToggleAndContent(
                    currentMessageId = "compression-failed",
                    displayedReasoningText = "",
                    activityStatusText = failure,
                    isReasoningStreaming = false,
                    isReasoningComplete = true,
                    messageIsError = true,
                    mainContentHasStarted = false,
                    reasoningTextColor = Color.Black,
                    reasoningToggleDotColor = Color.Black,
                    onVisibilityChanged = {},
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
        openReasoningSheet()
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("reasoning-execution-finish-step").fetchSemanticsNode("")
        org.junit.Assert.assertTrue(
            composeRule.onAllNodesWithText(localizedFailure).fetchSemanticsNodes().isNotEmpty()
        )
    }
}
