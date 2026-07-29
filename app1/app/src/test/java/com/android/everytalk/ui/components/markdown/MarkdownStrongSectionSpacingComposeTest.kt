package com.android.everytalk.ui.components.markdown

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
                        MarkdownNodesSuccess(
                            state = state,
                            components = components,
                            modifier = modifier,
                            nodes = state.node.children,
                        )
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
    }
}
