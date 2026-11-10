package com.android.everytalk.statecontroller.controller.conversation

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.util.ConversationNameHelper
import kotlinx.coroutines.flow.MutableStateFlow
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

    @Test
    fun `生成的标题写入内存历史且不覆盖手动标题`() {
        val question = Message(id = "u1", text = "TCP 和 UDP 有什么区别", sender = Sender.User, timestamp = 10, conversationId = "c1")
        val history = MutableStateFlow(listOf(listOf(question)))

        assertEquals("c1", applyGeneratedConversationTitle(history, "c1", "TCP 与 UDP 区别"))
        assertEquals("TCP 与 UDP 区别", ConversationNameHelper.getStoredConversationTitle(history.value.single()))
        assertEquals(question, ConversationNameHelper.withoutStoredConversationTitle(history.value.single()).single())

        // 已有标题（手动改名）或会话不存在时都不写。
        assertNull(applyGeneratedConversationTitle(history, "c1", "另一个标题"))
        assertEquals("TCP 与 UDP 区别", ConversationNameHelper.getStoredConversationTitle(history.value.single()))
        assertNull(applyGeneratedConversationTitle(history, "missing", "标题"))
    }
}
