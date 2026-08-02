package com.android.everytalk.ui.screens.BubbleMain.Main

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    @Test
    fun `压缩期间在原执行抽屉显示扫描高光文字`() {
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
        composeRule.onNodeWithText("正在压缩上下文").performClick()
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("reasoning-execution-live-step").fetchSemanticsNode("")
        composeRule.onNodeWithTag("reasoning-active-step-title").fetchSemanticsNode("")
    }

    @Test
    fun `压缩失败后圆点可回看具体原因`() {
        val failure = "上下文压缩失败：API 返回 429"
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
        composeRule.onNodeWithTag("reasoning-sheet-review-toggle").performClick()
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("reasoning-execution-finish-step").fetchSemanticsNode("")
        composeRule.onNodeWithText(failure).fetchSemanticsNode("")
    }
}
