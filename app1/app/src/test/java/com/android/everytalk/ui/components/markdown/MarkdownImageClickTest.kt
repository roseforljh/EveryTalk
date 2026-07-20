package com.android.everytalk.ui.components.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class MarkdownImageClickTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        stopKoin()
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `Markdown 图片点击回传真实来源并暴露点击语义`() {
        val source = "/data/user/0/com.android.everytalk/files/chat_attachments/image.png"
        var clickedSource: String? = null
        composeRule.setContent {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .testTag("markdown-image")
                    .markdownImageClick(source) { clickedSource = it }
            )
        }

        composeRule.onNodeWithTag("markdown-image")
            .assertHasClickAction()
            .performClick()

        assertEquals(source, clickedSource)
    }
}
