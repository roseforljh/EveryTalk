package com.android.everytalk.ui.components.markdown

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class MarkdownImageFailureTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `图片失败提示在前端可见`() {
        composeRule.setContent {
            MaterialTheme {
                MarkdownImageFailure()
            }
        }

        composeRule.onNodeWithText("图片加载失败").assertIsDisplayed()
    }

    @Test
    fun `图片加载动画在前端可见`() {
        composeRule.setContent {
            MaterialTheme {
                MarkdownImageLoading()
            }
        }

        composeRule.onNodeWithContentDescription("图片加载中").assertIsDisplayed()
    }
}
