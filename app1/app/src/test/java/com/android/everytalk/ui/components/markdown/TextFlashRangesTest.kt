package com.android.everytalk.ui.components.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class TextFlashRangesTest {
    @Test fun `关键词每处都标且忽略大小写`() {
        assertEquals(listOf(0..5, 9..14), textFlashRanges("Kotlin 和 kotlin", listOf("kotlin")))
    }

    @Test fun `跨段引用按行匹配，整段落在引用里就标整段`() {
        val phrases = textFlashPhrases("第一段结尾\n\n1. 列表项内容\n")
        assertEquals(listOf("第一段结尾", "1. 列表项内容"), phrases)
        assertEquals(listOf(3..7), textFlashRanges("前面的第一段结尾", phrases))
        assertEquals(listOf(0..4), textFlashRanges("列表项内容", phrases))
        assertEquals(emptyList<IntRange>(), textFlashRanges("无关段落", phrases))
    }
}
