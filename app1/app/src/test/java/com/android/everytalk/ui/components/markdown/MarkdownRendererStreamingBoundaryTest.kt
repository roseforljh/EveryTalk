package com.android.everytalk.ui.components.markdown

import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.text.Layout
import android.text.Selection
import android.text.Spanned
import android.text.style.LineBackgroundSpan
import android.text.style.URLSpan
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.android.everytalk.data.DataClass.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
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

    @Test
    fun `ai markdown uses simple non hyphenating line break strategy`() {
        composeRule.setContent {
            MarkdownRenderer(
                markdown = "像 Mike Krieger 这种能把一个 13 人的小项目带成 10 亿用户帝国的 CTO，或者像 OpenAI、Google 里的技术掌舵人。",
                isStreaming = false,
                sender = Sender.AI,
                contentKey = "message-wrap-strategy:complete",
            )
        }

        val textView = composeRule.runOnIdle {
            composeRule.activity.contentView().findDescendantTextView()
        }

        assertEquals(Layout.BREAK_STRATEGY_SIMPLE, textView.breakStrategy)
        assertEquals(Layout.HYPHENATION_FREQUENCY_NONE, textView.hyphenationFrequency)
    }

    @Test
    fun `link markdown uses text colored dotted underline style with external marker`() {
        composeRule.setContent {
            MarkdownRenderer(
                markdown = "* **淘宝/天猫选购入口**: [淘宝网 - 蜜丝婷泰版防晒](https://example.com/item)",
                isStreaming = false,
                sender = Sender.AI,
                contentKey = "message-2:complete",
            )
        }

        val textView = composeRule.runOnIdle {
            composeRule.activity.contentView().findDescendantTextView()
        }
        val spanned = composeRule.runOnIdle { textView.text as Spanned }
        val renderedText = spanned.toString()
        val labelStart = renderedText.indexOf("淘宝/天猫选购入口")
        val labelEnd = labelStart + "淘宝/天猫选购入口".length
        val linkStart = renderedText.indexOf("淘宝网 - 蜜丝婷泰版防晒")
        val linkEnd = linkStart + "淘宝网 - 蜜丝婷泰版防晒".length
        val markerEnd = linkEnd + " ↗".length

        assertTrue(renderedText.contains("淘宝网 - 蜜丝婷泰版防晒 ↗"))
        val urlSpans = spanned.getSpans(linkStart, markerEnd, URLSpan::class.java)
        assertEquals(1, urlSpans.size)
        assertEquals("https://example.com/item", urlSpans.single().url)
        assertEquals(linkStart, spanned.getSpanStart(urlSpans.single()))
        assertEquals(markerEnd, spanned.getSpanEnd(urlSpans.single()))
        val dottedUnderlineSpan = spanned.getSpans(linkStart, linkEnd, Any::class.java)
            .single { it.javaClass.simpleName == "DottedLinkUnderlineSpan" }
        assertTrue(dottedUnderlineSpan is LineBackgroundSpan)
        assertEquals(linkStart, spanned.getSpanStart(dottedUnderlineSpan))
        assertEquals(linkEnd, spanned.getSpanEnd(dottedUnderlineSpan))
        assertTrue(labelStart >= 0)
        assertTrue(labelEnd > labelStart)
        assertTrue(
            spanned.getSpans(labelStart, labelEnd, Any::class.java)
                .none { it.javaClass.simpleName == "DottedLinkUnderlineSpan" }
        )
    }

    @Test
    fun `single tap on link is consumed without text selection`() {
        composeRule.setContent {
            MarkdownRenderer(
                markdown = "[淘宝网 - 蜜丝婷泰版防晒](https://example.com/item)",
                isStreaming = false,
                sender = Sender.AI,
                contentKey = "message-3:complete",
                onImageClick = {},
            )
        }

        val textView = composeRule.runOnIdle {
            composeRule.activity.contentView().findDescendantTextView()
        }
        val consumed = composeRule.runOnIdle {
            val spanned = textView.text as Spanned
            val linkStart = spanned.toString().indexOf("淘宝网")
            val layout = textView.layout
            val line = layout.getLineForOffset(linkStart)
            val x = textView.totalPaddingLeft + layout.getPrimaryHorizontal(linkStart + 1)
            val y = textView.totalPaddingTop +
                (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
            val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(0L, 16L, MotionEvent.ACTION_UP, x, y, 0)
            try {
                textView.dispatchTouchEvent(down) to textView.dispatchTouchEvent(up)
            } finally {
                down.recycle()
                up.recycle()
            }
        }

        val selectionStart = composeRule.runOnIdle {
            Selection.getSelectionStart(textView.text)
        }
        val selectionEnd = composeRule.runOnIdle {
            Selection.getSelectionEnd(textView.text)
        }

        assertTrue(consumed.first)
        assertTrue(consumed.second)
        assertEquals(-1, selectionStart)
        assertEquals(-1, selectionEnd)
    }

    @Test
    fun `pressing link down does not create selected link highlight`() {
        composeRule.setContent {
            MarkdownRenderer(
                markdown = "[淘宝网 - 蜜丝婷泰版防晒](https://example.com/item)",
                isStreaming = false,
                sender = Sender.AI,
                contentKey = "message-6:complete",
                onImageClick = {},
            )
        }

        val textView = composeRule.runOnIdle {
            composeRule.activity.contentView().findDescendantTextView()
        }
        composeRule.runOnIdle {
            val spanned = textView.text as Spanned
            val linkStart = spanned.toString().indexOf("淘宝网")
            val layout = textView.layout
            val line = layout.getLineForOffset(linkStart)
            val x = textView.totalPaddingLeft + layout.getPrimaryHorizontal(linkStart + 1)
            val y = textView.totalPaddingTop +
                (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
            val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, x, y, 0)
            try {
                textView.dispatchTouchEvent(down)
            } finally {
                down.recycle()
            }
        }

        val selectionStart = composeRule.runOnIdle {
            Selection.getSelectionStart(textView.text)
        }
        val selectionEnd = composeRule.runOnIdle {
            Selection.getSelectionEnd(textView.text)
        }

        assertEquals(-1, selectionStart)
        assertEquals(-1, selectionEnd)
    }

    @Test
    fun `long press on link keeps system text selection available`() {
        composeRule.setContent {
            MarkdownRenderer(
                markdown = "[淘宝网 - 蜜丝婷泰版防晒](https://example.com/item)",
                isStreaming = false,
                sender = Sender.AI,
                contentKey = "message-5:complete",
                onImageClick = {},
            )
        }

        val textView = composeRule.runOnIdle {
            composeRule.activity.contentView().findDescendantTextView()
        }
        composeRule.runOnIdle {
            val spanned = textView.text as Spanned
            val linkStart = spanned.toString().indexOf("淘宝网")
            val layout = textView.layout
            val line = layout.getLineForOffset(linkStart)
            val x = textView.totalPaddingLeft + layout.getPrimaryHorizontal(linkStart + 1)
            val y = textView.totalPaddingTop +
                (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
            val longPressUpTime = ViewConfiguration.getLongPressTimeout().toLong() + 80L
            val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(0L, longPressUpTime, MotionEvent.ACTION_UP, x, y, 0)
            try {
                textView.dispatchTouchEvent(down)
                textView.dispatchTouchEvent(up)
            } finally {
                down.recycle()
                up.recycle()
            }
        }

        assertTrue(textView.isTextSelectable)
        assertNotNull(textView.movementMethod)
        assertNull(shadowOf(composeRule.activity).nextStartedActivity)
    }

    @Test
    fun `reused text view clears stale custom long press handler when selection becomes enabled`() {
        var useCustomLongPress by mutableStateOf(true)
        var customLongPressCount = 0

        composeRule.setContent {
            MarkdownRenderer(
                markdown = "这是一段可以长按选择的 AI 文本",
                isStreaming = false,
                sender = Sender.AI,
                contentKey = "message-4:complete",
                onImageClick = {},
                onLongPress = if (useCustomLongPress) {
                    { customLongPressCount++ }
                } else {
                    null
                },
            )
        }

        val firstTextView = composeRule.runOnIdle {
            composeRule.activity.contentView().findDescendantTextView()
        }
        composeRule.runOnIdle {
            firstTextView.performLongClick()
        }
        assertEquals(1, customLongPressCount)

        composeRule.runOnIdle {
            useCustomLongPress = false
        }

        val secondTextView = composeRule.runOnIdle {
            composeRule.activity.contentView().findDescendantTextView()
        }
        composeRule.runOnIdle {
            secondTextView.performLongClick()
        }

        assertSame(firstTextView, secondTextView)
        assertTrue(secondTextView.isTextSelectable)
        assertEquals(1, customLongPressCount)
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
