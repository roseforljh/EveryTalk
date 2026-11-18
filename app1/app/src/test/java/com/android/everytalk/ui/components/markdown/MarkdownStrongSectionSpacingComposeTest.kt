package com.android.everytalk.ui.components.markdown

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.ui.components.ChatMarkdownTextStyle
import com.android.everytalk.ui.components.streaming.StreamBlockParser
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class MarkdownStrongSectionSpacingComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `连续加粗小标题上方保留二十四dp且下方维持十六dp`() {
        val prepared = StreamBlockParser.prepareMessage(
            content = """
                **1. 红卫兵运动**
                第一段正文。
                **2. 破“四旧”**
                第二段正文。
            """.trimIndent(),
            messageId = "strong-section-spacing-layout",
            contentVersion = 47L,
        )
        lateinit var density: Density
        val compact = mutableStateOf(false)

        composeRule.setContent {
            density = LocalDensity.current
            MaterialTheme {
                val bodyStyle = MaterialTheme.typography.bodyLarge
                Markdown(
                    content = prepared.markdown,
                    colors = markdownColor(
                        inlineCodeBackground = Color.Transparent,
                        tableBackground = Color.Transparent,
                    ),
                    typography = markdownTypography(
                        h1 = bodyStyle,
                        h2 = bodyStyle,
                        h3 = bodyStyle,
                        h4 = bodyStyle,
                        h5 = bodyStyle,
                        h6 = bodyStyle,
                        text = bodyStyle,
                        quote = bodyStyle,
                        paragraph = bodyStyle,
                        ordered = bodyStyle,
                        bullet = bodyStyle,
                        list = bodyStyle,
                        table = bodyStyle,
                        inlineCode = bodyStyle,
                        textLink = TextLinkStyles(),
                    ),
                    flavour = EveryTalkMarkdownFlavourDescriptor,
                    padding = markdownPadding(
                        block = ChatMarkdownTextStyle.SPACING_PARAGRAPH_DP.dp,
                    ),
                    success = { state, components, modifier ->
                        Box(Modifier.testTag("markdown-document")) {
                            CompositionLocalProvider(LocalAiExecutionSegmentSpacing provides compact.value) {
                                MarkdownNodesSuccess(
                                    state = state,
                                    components = components,
                                    modifier = modifier,
                                    nodes = state.node.children,
                                )
                            }
                        }
                    },
                    immediate = true,
                )
            }
        }
        composeRule.waitForIdle()

        val previousBodyBottom = composeRule
            .onNodeWithText("第一段正文。")
            .fetchSemanticsNode("")
            .boundsInRoot
            .bottom
        val nextHeadingBounds = composeRule
            .onNodeWithText("2. 破“四旧”")
            .fetchSemanticsNode("")
            .boundsInRoot
        val nextBodyTop = composeRule
            .onNodeWithText("第二段正文。")
            .fetchSemanticsNode("")
            .boundsInRoot
            .top
        val topGapPx = nextHeadingBounds.top - previousBodyBottom
        val bottomGapPx = nextBodyTop - nextHeadingBounds.bottom
        val expectedTopGapPx = with(density) { 24.dp.toPx() }
        val expectedBottomGapPx = with(density) { 16.dp.toPx() }

        assertTrue(
            "编号标题顶部间距不足：实际 ${topGapPx}px，至少需要 ${expectedTopGapPx}px",
            topGapPx >= expectedTopGapPx - 0.5f,
        )
        assertTrue(
            "编号标题下方间距发生变化：实际 ${bottomGapPx}px，预期 ${expectedBottomGapPx}px",
            kotlin.math.abs(bottomGapPx - expectedBottomGapPx) <= 0.5f,
        )

        fun documentTop() = composeRule.onNodeWithTag("markdown-document").fetchSemanticsNode("").boundsInRoot.top
        fun firstParagraphTop() = composeRule.onNodeWithText("1. 红卫兵运动").fetchSemanticsNode("").boundsInRoot.top
        fun lastParagraphTop() = composeRule.onNodeWithText("第二段正文。").fetchSemanticsNode("").boundsInRoot.top
        val normalLeadingGap = firstParagraphTop() - documentTop()
        val normalTextSpan = lastParagraphTop() - firstParagraphTop()
        composeRule.runOnIdle { compact.value = true }
        val compactLeadingGap = firstParagraphTop() - documentTop()
        val compactTextSpan = lastParagraphTop() - firstParagraphTop()
        val removedSpace = with(density) { 8.dp.toPx() }
        // 有序切片只少掉首段的 8dp；其余标题/段落保持同一套间距。
        assertTrue("首段从 ${normalLeadingGap}px 收紧到 ${compactLeadingGap}px",
            kotlin.math.abs(normalLeadingGap - compactLeadingGap - removedSpace) <= 0.5f)
        assertTrue("段间距不变：${normalTextSpan}px 对 ${compactTextSpan}px",
            kotlin.math.abs(normalTextSpan - compactTextSpan) <= 0.5f)
    }
}
