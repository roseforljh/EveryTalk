package com.android.everytalk.ui.screens.MainScreen.chat.text.ui

import android.app.Application
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.WebSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PageSourcesButtonLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `来源胶囊在全宽父容器中仍按内容宽度包裹`() {
        val pageSources = listOf(
            WebSearchResult(1, "知乎", "https://zhihu.com", ""),
            WebSearchResult(2, "百度", "https://baidu.com", ""),
            WebSearchResult(3, "示例", "https://example.com", ""),
        )

        composeRule.setContent {
            MaterialTheme {
                Row(modifier = Modifier.width(320.dp)) {
                    Surface(
                        modifier = Modifier
                            .width(320.dp)
                            .fillMaxWidth(),
                        color = Color.Transparent,
                    ) {
                        PageSourcesButton(
                            pageSources = pageSources,
                            onClick = {},
                            modifier = Modifier.testTag("page-sources-button"),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val bounds = composeRule
            .onNodeWithTag("page-sources-button")
            .fetchSemanticsNode("")
            .boundsInRoot

        assertEquals(0f, bounds.left, 0.1f)
        assertTrue(bounds.width < 320f)
    }
}
