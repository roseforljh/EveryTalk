package com.android.everytalk.ui.components.markdown

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.ui.components.streaming.PreparedMessage
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
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
class MarkdownLinkLogoLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `粗体美元区间在Compose文本节点中完整显示`() {
        val content = "1. **${'$'}22 ~ ${'$'}25**"

        composeRule.setContent {
            MaterialTheme {
                val bodyStyle = MaterialTheme.typography.bodyLarge
                Markdown(
                    content = content,
                    colors = markdownColor(
                        inlineCodeBackground = androidx.compose.ui.graphics.Color.Transparent,
                        tableBackground = androidx.compose.ui.graphics.Color.Transparent,
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
                        textLink = androidx.compose.ui.text.TextLinkStyles(),
                    ),
                    flavour = EveryTalkMarkdownFlavourDescriptor,
                    immediate = true,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule
            .onNodeWithText("${'$'}22 ~ ${'$'}25", substring = true)
            .fetchSemanticsNode("")
    }

    @Test
    fun `单独自动链接的 Logo 与链接处于同一行`() {
        val content = "https://x.com"
        val request = MarkdownLinkLogoRequest(
            host = "x.com",
            faviconUrl = "https://www.google.com/s2/favicons?domain=x.com&sz=64",
        )
        val logoFreeAnnotator = createPreparedMessageMarkdownAnnotator(
            preparedMessage = PreparedMessage(
                markdown = content,
                formulas = emptyMap(),
                hasPendingFormula = false,
                contentVersion = 1L,
            ),
        )
        val components = markdownComponents(
            paragraph = { model ->
                MarkdownSingleAutolinkLogoParagraph(
                    content = model.content,
                    node = model.node,
                    style = model.typography.paragraph,
                    request = request,
                    logoFreeAnnotator = logoFreeAnnotator,
                    modifier = Modifier.testTag("single-autolink-row"),
                )
            },
        )

        composeRule.setContent {
            MaterialTheme {
                val bodyStyle = MaterialTheme.typography.bodyLarge
                val typography = markdownTypography(
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
                    textLink = androidx.compose.ui.text.TextLinkStyles(),
                )
                Markdown(
                    content,
                    colors = markdownColor(
                        inlineCodeBackground = androidx.compose.ui.graphics.Color.Transparent,
                        tableBackground = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                    typography = typography,
                    modifier = Modifier.width(320.dp),
                    padding = markdownPadding(
                        block = 0.dp,
                        list = 0.dp,
                        listItemTop = 0.dp,
                        listItemBottom = 0.dp,
                        listIndent = 0.dp,
                    ),
                    annotator = logoFreeAnnotator,
                    components = components,
                    lookupLinks = true,
                    immediate = true,
                )
            }
        }
        composeRule.waitForIdle()

        val logoNode = composeRule
            .onNodeWithContentDescription("链接 Logo：x.com")
            .fetchSemanticsNode("")
        val linkNode = composeRule
            .onNodeWithText(content, substring = true)
            .fetchSemanticsNode("")
        val rowBounds = composeRule
            .onNodeWithTag("single-autolink-row")
            .fetchSemanticsNode("")
            .boundsInRoot
        val logoBounds = logoNode.boundsInRoot
        val linkBounds = linkNode.boundsInRoot

        assertTrue(rowBounds.width < 320f)
        assertTrue(logoBounds.top < linkBounds.bottom)
        assertTrue(kotlin.math.abs(logoBounds.top - linkBounds.top) < 32f)
        assertTrue(logoBounds.left < linkBounds.left)
    }
}
