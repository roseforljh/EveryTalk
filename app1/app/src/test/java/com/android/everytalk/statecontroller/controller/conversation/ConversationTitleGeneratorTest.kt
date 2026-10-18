package com.android.everytalk.statecontroller.controller.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 验证标题清洗结果，确保模型失败或返回空文本时可以走首条问题兜底。 */
class ConversationTitleGeneratorTest {
    @Test
    fun `normalize removes wrappers and truncates title`() {
        assertEquals("旅行计划", ConversationTitleGenerator.normalize("```\n“旅行计划”\n```") )
        assertNull(ConversationTitleGenerator.normalize("   "))
    }
}
