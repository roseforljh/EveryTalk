package com.android.everytalk.ui.components.markdown

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.android.everytalk.data.DataClass.Sender
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.annotation.Config
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MarkdownRendererStreamingBoundaryTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        stopKoin()
    }

    @Test
    fun `streaming boundary refreshes existing text view layout for link markdown`() {
        var isStreaming by mutableStateOf(true)
        var contentKey by mutableStateOf("message-1:plain:tail")
        val markdown = """
            ### 1. 蜜丝婷（Mistine）泰版防晒素颜霜
            * **淘宝/天猫选购入口**：[淘宝网 - 蜜丝婷泰版防晒/素颜霜选购直达](https://www.taobao.com/list/product/%E8%9C%9C%E4%B8%9D%E5%A9%B7%E6%B3%B0%E7%89%88%E9%98%B2%E6%99%92.htm)
            * **淘宝热销店铺推荐**：[淘宝网 - 泰版蜜丝婷防晒霜（多店铺对比）](https://guangtao.taobao.com/product-a2518403d230b349817880896878b32a54c5c47c8b0d91e149867dcb4fee1745.html)
        """.trimIndent()

        composeRule.setContent {
            MarkdownRenderer(
                markdown = markdown,
                isStreaming = isStreaming,
                sender = Sender.AI,
                contentKey = contentKey,
            )
        }

        val firstTextView = composeRule.runOnIdle {
            composeRule.activity.contentView().findDescendantTextView()
        }
        val firstTag = composeRule.runOnIdle { firstTextView.tag }

        composeRule.runOnIdle {
            isStreaming = false
            contentKey = "message-1:plain:complete"
        }

        val secondTextView = composeRule.runOnIdle {
            composeRule.activity.contentView().findDescendantTextView()
        }
        val secondTag = composeRule.runOnIdle { secondTextView.tag }

        assertSame(firstTextView, secondTextView)
        assertNotSame(firstTag, secondTag)
    }

    private fun ComponentActivity.contentView(): View {
        val content = findViewById<ViewGroup>(android.R.id.content)
        return content.getChildAt(0)
    }

    private fun View.findDescendantTextView(): TextView {
        if (this is TextView) return this
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                val found = getChildAt(index).findDescendantTextViewOrNull()
                if (found != null) return found
            }
        }
        error("TextView not found")
    }

    private fun View.findDescendantTextViewOrNull(): TextView? {
        if (this is TextView) return this
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                val found = getChildAt(index).findDescendantTextViewOrNull()
                if (found != null) return found
            }
        }
        return null
    }
}
