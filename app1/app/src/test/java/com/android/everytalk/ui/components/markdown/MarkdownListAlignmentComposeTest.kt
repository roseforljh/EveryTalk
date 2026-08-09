package com.android.everytalk.ui.components.markdown

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.ui.components.ChatMarkdownTextStyle
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class MarkdownListAlignmentComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `三级多行无序列表保留层级且标记与正文首行中心对齐`() {
        val markdown = """
            - 一级内容用于验证多行列表的首行中心对齐
                - 二级内容用于验证多行列表的首行中心对齐
                    - 三级内容用于验证多行列表的首行中心对齐
        """.trimIndent()
        lateinit var density: Density

        composeRule.setContent {
            density = LocalDensity.current
            val bodyStyle = TextStyle(fontSize = 16.sp, lineHeight = 26.sp)
            val components = markdownComponents(
                unorderedList = { EveryTalkMarkdownUnorderedList(it) },
            )
            MaterialTheme {
                Markdown(
                    content = markdown,
                    colors = markdownColor(),
                    modifier = Modifier.width(220.dp),
                    typography = markdownTypography(
                        text = bodyStyle,
                        paragraph = bodyStyle,
                        bullet = bodyStyle,
                        list = bodyStyle,
                    ),
                    padding = markdownPadding(
                        list = 0.dp,
                        listItemTop = 0.dp,
                        listItemBottom = 0.dp,
                        listIndent = 0.dp,
                    ),
                    components = components,
                    immediate = true,
                )
            }
        }

        val markerNodes = (0..2).map { level ->
            composeRule.onAllNodesWithTag(
                "markdown-list-marker-$level",
                useUnmergedTree = true,
            )
                .fetchSemanticsNodes()
                .single()
        }
        val contentNodes = listOf(
            "一级内容用于验证多行列表的首行中心对齐",
            "二级内容用于验证多行列表的首行中心对齐",
            "三级内容用于验证多行列表的首行中心对齐",
        ).map { content ->
            composeRule.onAllNodesWithText(content, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .single()
        }

        val markerCenters = markerNodes.map { it.boundsInRoot.center.x }
        val nestingStepPx = with(density) {
            ChatMarkdownTextStyle.LIST_MARKER_WIDTH_DP.dp.toPx()
        }
        assertEquals(nestingStepPx, markerCenters[1] - markerCenters[0], 1f)
        assertEquals(nestingStepPx, markerCenters[2] - markerCenters[1], 1f)
        assertTrue(markerCenters.zipWithNext().all { (current, next) -> next > current })

        val halfMarkerWidthPx = nestingStepPx / 2f
        val markerOpticalHeightPx = with(density) {
            ChatMarkdownTextStyle.LIST_MARKER_OPTICAL_HEIGHT_SP.sp.toPx()
        }
        markerNodes.zip(contentNodes).forEach { (marker, content) ->
            assertEquals(
                content.boundsInRoot.left - halfMarkerWidthPx,
                marker.boundsInRoot.center.x,
                1f,
            )
            assertEquals(
                "marker=${marker.boundsInRoot}, content=${content.boundsInRoot}",
                content.boundsInRoot.top,
                marker.boundsInRoot.top,
                1f,
            )
            assertEquals(markerOpticalHeightPx, marker.boundsInRoot.height, 1f)
            assertEquals(
                content.boundsInRoot.top + markerOpticalHeightPx / 2f,
                marker.boundsInRoot.center.y,
                1f,
            )
            assertTrue(content.boundsInRoot.height > marker.boundsInRoot.height)
        }
    }
}
