package com.android.everytalk.ui.components

import android.app.Application
import android.text.Editable
import android.text.Selection
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.ui.components.content.CodeBlockCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class CodeBlockStreamingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `长代码预览始终显示最新原文并在布局后滚到底部`() {
        val code = mutableStateOf((1..80).joinToString("\n") { "第 $it 行：流式代码内容" })
        composeRule.setContent {
            MaterialTheme {
                CodeBlockCard(
                    language = "text",
                    code = code.value,
                    isStreaming = true,
                    modifier = Modifier.testTag("streaming-code"),
                )
            }
        }
        fun assertAtBottom() {
            composeRule.waitForIdle()
            val range = composeRule.onNodeWithTag("streaming-code")
                .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue(range.maxValue() > 0f)
            assertEquals(range.maxValue(), range.value(), 1f)
        }

        assertAtBottom()
        val latest = code.value + "\n最后一行：必须实时显示"
        composeRule.runOnIdle { code.value = latest }
        composeRule.onNodeWithText(latest).assertExists()
        assertAtBottom()
    }

    @Test
    fun `详情页只追加增量且最终高亮不会替换文本缓冲区或选区`() {
        val view = TextView(ApplicationProvider.getApplicationContext<Application>()).apply {
            setTextIsSelectable(true)
            setText("first\n", TextView.BufferType.EDITABLE)
        }
        val buffer = view.editableText
        val changes = mutableListOf<Triple<Int, Int, Int>>()
        view.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                changes += Triple(start, before, count)
            }
        })
        Selection.setSelection(buffer, 0, 5)

        assertTrue(updateCodeText(view, "first\nsecond"))
        assertSame(buffer, view.editableText)
        assertEquals(listOf(Triple(6, 0, 6)), changes)
        assertEquals(0, view.selectionStart)
        assertEquals(5, view.selectionEnd)
        assertFalse(updateCodeText(view, "first\nsecond"))

        val highlighted = SpannableString("first\nsecond").apply {
            setSpan(ForegroundColorSpan(android.graphics.Color.RED), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        assertFalse(updateCodeText(view, highlighted))
        assertSame(buffer, view.editableText)
        assertEquals(1, changes.size)
        assertEquals(0, view.selectionStart)
        assertEquals(5, view.selectionEnd)
        assertEquals(1, buffer.getSpans(0, buffer.length, ForegroundColorSpan::class.java).size)

        assertTrue(updateCodeText(view, "rewritten"))
        assertEquals("rewritten", view.text.toString())
        assertTrue(updateCodeText(view, ""))
        assertEquals("", view.text.toString())
    }

    @Test
    fun `详情页跟随新文本且上滑阅读时保留位置`() {
        val code = mutableStateOf((1..120).joinToString("\n") { "line $it" })
        val hostView = java.util.concurrent.atomic.AtomicReference<android.view.View>()
        composeRule.setContent {
            val view = LocalView.current
            SideEffect { hostView.set(view.rootView) }
            SelectableCodeTextView(
                code = code.value,
                language = "text",
                isDarkTheme = false,
                headerColor = Color.Black,
                codeTextColor = Color.Black,
                isStreaming = true,
            )
        }
        // 从真实 AndroidView 找到 ScrollView，验证追加后跟随和用户阅读位置，而非只检查源码。
        fun findScroll(view: android.view.View): ScrollView? {
            if (view is ScrollView) return view
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) {
                    findScroll(view.getChildAt(index))?.let { return it }
                }
            }
            return null
        }
        composeRule.waitForIdle()
        val scroll = requireNotNull(findScroll(hostView.get()))
        composeRule.runOnIdle {
            assertFalse(scroll.isVerticalScrollBarEnabled)
            assertFalse(scroll.isHorizontalScrollBarEnabled)
            assertTrue(scroll.scrollY > 0)
            code.value += "\nnew tail"
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(scroll.getChildAt(0).height - scroll.height, scroll.scrollY)
            scroll.scrollTo(0, 100)
            code.value += "\nkeep reading position"
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(100, scroll.scrollY) }
    }
}
