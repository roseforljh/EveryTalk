package com.android.everytalk.ui.components.markdown

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LineBackgroundSpan
import android.text.style.LineHeightSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
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

    private fun expectedListItemLineHeightPx(context: Context): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            ChatMarkdownTextStyle.LIST_ITEM_LINE_HEIGHT_SP,
            context.resources.displayMetrics
        ).toInt()
    }

    @Test
    fun `heading relative sizes follow chatgpt title scale`() {
        assertEquals(20f / 14f, chatGptHeadingRelativeSizeMultiplier(1), 0.001f)
        assertEquals(18f / 14f, chatGptHeadingRelativeSizeMultiplier(2), 0.001f)
        assertEquals(16f / 14f, chatGptHeadingRelativeSizeMultiplier(3), 0.001f)
        assertEquals(1.0f, chatGptHeadingRelativeSizeMultiplier(6), 0.001f)
    }

    @Test
    fun `level four heading keeps body scale and normal text weight`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val markwon = MarkwonCache.getOrCreate(context, isDark = false, textSize = 16f)

        val rendered = markwon.render(markwon.parse("#### 3. 左图：2026年5/6月合刊")) as Spanned
        val start = rendered.toString().indexOf("3. 左图")
        val end = rendered.length

        assertTrue(rendered.getSpans(start, end, ChatTextWeightSpan::class.java).any { it.weight == 400 })
        assertTrue(rendered.getSpans(start, end, RelativeSizeSpan::class.java).any { it.sizeChange == 1.0f })
    }

    @Test
    fun `body line height stays compact inside wrapped chinese list items`() {
        assertEquals(14f, ChatMarkdownTextStyle.BODY_FONT_SIZE_SP, 0.001f)
        assertEquals(22f, ChatMarkdownTextStyle.BODY_LINE_HEIGHT_SP, 0.001f)
        assertEquals(22f, ChatMarkdownTextStyle.LIST_ITEM_LINE_HEIGHT_SP, 0.001f)
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
    fun `strong emphasis uses chat text weight instead of full bold span`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val markwon = MarkwonCache.getOrCreate(context, isDark = false, textSize = 16f)

        val rendered = markwon.render(markwon.parse("这是一段 **重点内容** 示例")) as Spanned
        val start = rendered.toString().indexOf("重点内容")
        val end = start + "重点内容".length

        assertTrue(rendered.getSpans(start, end, ChatTextWeightSpan::class.java).any { it.weight == 600 })
        assertTrue(rendered.getSpans(start, end, StyleSpan::class.java).none { it.style == Typeface.BOLD })
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

    @Test
    fun `markwon list spans avoid inflating every wrapped line`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val markwon = MarkwonCache.getOrCreate(context, isDark = false, textSize = 16f)

        val rendered = markwon.render(
            markwon.parse("- parent\n  - child one\n  - child two\n1. ordered")
        ) as Spanned

        assertTrue(
            rendered.getSpans(0, rendered.length, CustomBulletListItemSpan::class.java)
                .all { it is LineHeightSpan },
        )
        assertTrue(rendered.getSpans(0, rendered.length, CustomOrderedListItemSpan::class.java).isNotEmpty())

        val firstBulletSpan = rendered.getSpans(0, rendered.length, CustomBulletListItemSpan::class.java)
            .first() as LineHeightSpan
        val spanStart = rendered.getSpanStart(firstBulletSpan)
        val wrappedLineStart = spanStart + 1
        val fm = Paint.FontMetricsInt().apply {
            top = -22
            ascent = -20
            descent = 5
            bottom = 7
        }

        firstBulletSpan.chooseHeight(rendered, wrappedLineStart, wrappedLineStart + 1, 0, 25, fm)

        val expectedLineHeight = expectedListItemLineHeightPx(context)
        assertEquals(expectedLineHeight, fm.descent - fm.ascent)
        assertEquals(expectedLineHeight, fm.bottom - fm.top)
    }

    @Test
    fun `markwon nested bullet spacing stays outside child first line`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val markwon = MarkwonCache.getOrCreate(context, isDark = false, textSize = 16f)
        val rendered = markwon.render(markwon.parse("- parent\n  - child")) as Spanned
        val parentSpan = rendered.getSpans(0, rendered.length, CustomBulletListItemSpan::class.java)
            .first { span ->
                span.level == 0
            } as LineHeightSpan
        val nestedSpan = rendered.getSpans(0, rendered.length, CustomBulletListItemSpan::class.java)
            .first { span ->
                span.level == 1
            } as LineHeightSpan
        val parentStart = rendered.getSpanStart(parentSpan)
        val parentLineEnd = rendered.toString().indexOf('\n', parentStart).let { index ->
            if (index >= 0) index + 1 else rendered.length
        }
        val parentFm = Paint.FontMetricsInt().apply {
            top = -22
            ascent = -20
            descent = 5
            bottom = 7
        }

        parentSpan.chooseHeight(rendered, parentStart, parentLineEnd, 0, 25, parentFm)

        val expectedLineHeight = expectedListItemLineHeightPx(context)
        val expectedSpacing = (ChatMarkdownTextStyle.LIST_NESTED_TOP_SPACING_DP *
            context.resources.displayMetrics.density).toInt()
        assertEquals(expectedLineHeight + expectedSpacing, parentFm.descent - parentFm.ascent)
        assertEquals(expectedLineHeight + expectedSpacing, parentFm.bottom - parentFm.top)

        val childFm = Paint.FontMetricsInt().apply {
            top = -22
            ascent = -20
            descent = 5
            bottom = 7
        }
        val childStart = rendered.getSpanStart(nestedSpan)
        nestedSpan.chooseHeight(rendered, childStart, rendered.length, 0, 25, childFm)

        assertEquals(expectedLineHeight, childFm.descent - childFm.ascent)
        assertEquals(expectedLineHeight, childFm.bottom - childFm.top)
    }

    @Test
    fun `markwon ordered parent and nested bullet render without blank separator line`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val markwon = MarkwonCache.getOrCreate(context, isDark = false, textSize = 16f)
        val processed = preprocessAiMarkdown(
            "2. 改变体位：\n\n   * 找个沙发或地板坐下来\n   * 不要强撑着站立",
            isStreaming = false,
        )
        val rendered = markwon.render(markwon.parse(processed)) as Spanned

        assertTrue(processed, !processed.contains("改变体位：\n\n   *"))
        assertTrue(rendered.toString(), !rendered.toString().contains("改变体位：\n\n找个沙发"))
    }

    @Test
    fun `markwon ordered parent to nested bullet uses nested spacing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val markwon = MarkwonCache.getOrCreate(context, isDark = false, textSize = 16f)
        val rendered = markwon.render(markwon.parse("2. 改变体位：\n   * 找个沙发或地板坐下来")) as Spanned
        val parentSpan = rendered.getSpans(0, rendered.length, CustomOrderedListItemSpan::class.java)
            .first() as LineHeightSpan
        val childSpan = rendered.getSpans(0, rendered.length, CustomBulletListItemSpan::class.java)
            .first()
        val parentStart = rendered.getSpanStart(parentSpan)
        val parentLineEnd = rendered.toString().indexOf('\n', parentStart).let { index ->
            if (index >= 0) index + 1 else rendered.length
        }
        val fm = Paint.FontMetricsInt().apply {
            top = -22
            ascent = -20
            descent = 5
            bottom = 7
        }

        parentSpan.chooseHeight(rendered, parentStart, parentLineEnd, 0, 25, fm)

        val expectedLineHeight = expectedListItemLineHeightPx(context)
        val expectedSpacing = (ChatMarkdownTextStyle.LIST_NESTED_TOP_SPACING_DP *
            context.resources.displayMetrics.density).toInt()
        assertEquals(1, childSpan.level)
        assertEquals(expectedLineHeight + expectedSpacing, fm.descent - fm.ascent)
        assertEquals(expectedLineHeight + expectedSpacing, fm.bottom - fm.top)
    }

    @Test
    fun `markwon nested bullet to next ordered parent keeps top level spacing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val markwon = MarkwonCache.getOrCreate(context, isDark = false, textSize = 16f)
        val processed = preprocessAiMarkdown(
            "1. 调整呼吸：\n   * 缓解浑身瘫软和发麻的症状。\n\n2. 改变体位：",
            isStreaming = false,
        )
        val rendered = markwon.render(
            markwon.parse(processed)
        ) as Spanned
        val childSpan = rendered.getSpans(0, rendered.length, CustomBulletListItemSpan::class.java)
            .first() as LineHeightSpan
        val childStart = rendered.getSpanStart(childSpan)
        val childLineEnd = rendered.toString().indexOf('\n', childStart).let { index ->
            if (index >= 0) index + 1 else rendered.length
        }
        val fm = Paint.FontMetricsInt().apply {
            top = -22
            ascent = -20
            descent = 5
            bottom = 7
        }

        childSpan.chooseHeight(rendered, childStart, childLineEnd, 0, 25, fm)

        val expectedLineHeight = expectedListItemLineHeightPx(context)
        val expectedSpacing = (ChatMarkdownTextStyle.LIST_TOP_LEVEL_ITEM_SPACING_DP *
            context.resources.displayMetrics.density).toInt()
        assertTrue(processed, !processed.contains("症状。\n\n2. 改变体位"))
        assertEquals(expectedLineHeight + expectedSpacing, fm.descent - fm.ascent)
        assertEquals(expectedLineHeight + expectedSpacing, fm.bottom - fm.top)
    }

    @Test
    fun `markwon emergency list is tight internally after preprocessing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val markwon = MarkwonCache.getOrCreate(context, isDark = false, textSize = 16f)
        val processed = preprocessAiMarkdown(
            """
                1. 调整呼吸（最重要）：
                   * 面罩式呼吸：这样可以快速提高体内的二氧化碳浓度，几分钟内就能缓解浑身瘫软和发麻的症状。

                2. 改变体位：
                   * 找个沙发或地板坐下来或平躺，解开衣领和皮带，保证呼吸顺畅。
                   * 不要强撑着站立，防止因瘫软摔倒受伤。

                3. 心理暗示：
                   * 在心里反复对自己说：“这只是惊恐发作。”
            """.trimIndent(),
            isStreaming = false,
        )
        val rendered = markwon.render(markwon.parse(processed)) as Spanned

        assertTrue(processed, !processed.contains("症状。\n\n2. 改变体位"))
        assertTrue(processed, !processed.contains("改变体位：\n\n   *"))
        assertTrue(rendered.toString(), !rendered.toString().contains("改变体位：\n\n找个沙发"))
        assertTrue(rendered.toString(), !rendered.toString().contains("症状。\n\n改变体位"))
    }

    @Test
    fun `markwon top level sibling bullets add breathing room`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val markwon = MarkwonCache.getOrCreate(context, isDark = false, textSize = 16f)
        val rendered = markwon.render(markwon.parse("- first\n- second")) as Spanned
        val topLevelSpans = rendered.getSpans(0, rendered.length, CustomBulletListItemSpan::class.java)
            .filter { span ->
                val levelField = span.javaClass.getDeclaredField("level")
                levelField.isAccessible = true
                levelField.getInt(span) == 0
            }
            .sortedBy { span -> rendered.getSpanStart(span) }
        val firstTopLevelSpan = topLevelSpans[0] as LineHeightSpan
        val start = rendered.getSpanStart(firstTopLevelSpan)
        val lineEnd = rendered.toString().indexOf('\n', start).let { index ->
            if (index >= 0) index + 1 else rendered.length
        }
        val fm = Paint.FontMetricsInt().apply {
            top = -22
            ascent = -20
            descent = 5
            bottom = 7
        }

        firstTopLevelSpan.chooseHeight(rendered, start, lineEnd, 0, 25, fm)

        val expectedLineHeight = expectedListItemLineHeightPx(context)
        val expectedSpacing = (ChatMarkdownTextStyle.LIST_TOP_LEVEL_ITEM_SPACING_DP *
            context.resources.displayMetrics.density).toInt()
        assertEquals(expectedLineHeight + expectedSpacing, fm.descent - fm.ascent)
        assertEquals(expectedLineHeight + expectedSpacing, fm.bottom - fm.top)
    }

    @Test
    fun `markwon nested sibling bullets add breathing room`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val markwon = MarkwonCache.getOrCreate(context, isDark = false, textSize = 16f)
        val rendered = markwon.render(markwon.parse("- parent\n  - child one\n  - child two")) as Spanned
        val nestedSpans = rendered.getSpans(0, rendered.length, CustomBulletListItemSpan::class.java)
            .filter { span ->
                val levelField = span.javaClass.getDeclaredField("level")
                levelField.isAccessible = true
                levelField.getInt(span) == 1
            }
            .sortedBy { span -> rendered.getSpanStart(span) }
        val firstNestedSpan = nestedSpans[0] as LineHeightSpan
        val start = rendered.getSpanStart(firstNestedSpan)
        val lineEnd = rendered.toString().indexOf('\n', start).let { index ->
            if (index >= 0) index + 1 else rendered.length
        }
        val fm = Paint.FontMetricsInt().apply {
            top = -22
            ascent = -20
            descent = 5
            bottom = 7
        }

        firstNestedSpan.chooseHeight(rendered, start, lineEnd, 0, 25, fm)

        val expectedLineHeight = expectedListItemLineHeightPx(context)
        val expectedSpacing = (ChatMarkdownTextStyle.LIST_TOP_LEVEL_ITEM_SPACING_DP *
            context.resources.displayMetrics.density).toInt()
        assertEquals(expectedLineHeight + expectedSpacing, fm.descent - fm.ascent)
        assertEquals(expectedLineHeight + expectedSpacing, fm.bottom - fm.top)
    }
}
