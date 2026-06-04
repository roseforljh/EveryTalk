package com.android.everytalk.ui.components.markdown

import android.content.Context
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LineBackgroundSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.ui.components.ChatMarkdownTextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MarkwonCacheTest {

    @Before
    fun setUp() {
        stopKoin()
        MarkwonCache.clear()
    }

    @Test
    fun `heading relative sizes follow chatgpt title scale`() {
        assertEquals(1.5f, chatGptHeadingRelativeSizeMultiplier(1), 0.001f)
        assertEquals(1.375f, chatGptHeadingRelativeSizeMultiplier(2), 0.001f)
        assertEquals(1.0f, chatGptHeadingRelativeSizeMultiplier(3), 0.001f)
        assertEquals(0.875f, chatGptHeadingRelativeSizeMultiplier(6), 0.001f)
    }

    @Test
    fun `inline code uses gray bold compact style without background`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val markwon = MarkwonCache.getOrCreate(context, isDark = false, textSize = 16f)

        val rendered = markwon.render(markwon.parse("Use `tony2233` reply")) as Spanned
        val start = rendered.toString().indexOf("tony2233")
        val end = start + "tony2233".length

        val inlineCodeBackgrounds = rendered.getSpans(start, end, Any::class.java)
            .filter { it.javaClass.simpleName == "ChatInlineCodeBackgroundSpan" }

        assertTrue(inlineCodeBackgrounds.isEmpty())
        assertTrue(rendered.getSpans(start, end, LineBackgroundSpan::class.java).isEmpty())
        assertTrue(rendered.getSpans(start, end, BackgroundColorSpan::class.java).isEmpty())
        assertTrue(rendered.getSpans(start, end, TypefaceSpan::class.java).isNotEmpty())
        assertTrue(rendered.getSpans(start, end, StyleSpan::class.java).any { it.style == Typeface.BOLD })
        assertTrue(rendered.getSpans(start, end, ForegroundColorSpan::class.java).any {
            it.foregroundColor == chatInlineCodeTextColorArgb(isDark = false)
        })
        assertTrue(rendered.getSpans(start, end, RelativeSizeSpan::class.java).any {
            it.sizeChange == ChatMarkdownTextStyle.INLINE_CODE_RELATIVE_SIZE
        })
    }

    @Test
    fun `markwon renders strikethrough and keeps task markers as text`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val markwon = MarkwonCache.getOrCreate(context, isDark = false, textSize = 16f)

        val strike = markwon.render(markwon.parse("Strike: ~~old API~~.")) as Spanned
        val checked = markwon.render(markwon.parse("* [x] **Task 1**: done")) as Spanned
        val unchecked = markwon.render(markwon.parse("* [ ] **Task 2**: todo")) as Spanned

        assertTrue(strike.getSpans(0, strike.length, StrikethroughSpan::class.java).isNotEmpty())
        assertTrue(checked.toString().contains("[x]"))
        assertTrue(unchecked.toString().contains("[ ]"))
    }
}
