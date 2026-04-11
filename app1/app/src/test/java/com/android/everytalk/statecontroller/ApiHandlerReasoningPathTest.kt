package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiHandlerReasoningPathTest {

    @Test
    fun `apply reasoning chunk seeds reasoning only for blank message`() {
        val updated = applyReasoningChunk(
            currentMessage = Message(
                id = "ai-1",
                text = "",
                sender = Sender.AI,
                reasoning = null,
                contentStarted = false,
            ),
            reasoningChunk = "第一段推理"
        )

        assertEquals("第一段推理", updated.reasoning)
    }

    @Test
    fun `apply reasoning chunk keeps existing reasoning snapshot`() {
        val updated = applyReasoningChunk(
            currentMessage = Message(
                id = "ai-2",
                text = "",
                sender = Sender.AI,
                reasoning = "已有推理",
                contentStarted = false,
            ),
            reasoningChunk = "新增推理"
        )

        assertEquals("已有推理", updated.reasoning)
    }

    @Test
    fun `apply reasoning chunk ignores blank chunk`() {
        val updated = applyReasoningChunk(
            currentMessage = Message(
                id = "ai-3",
                text = "",
                sender = Sender.AI,
                reasoning = null,
                contentStarted = false,
            ),
            reasoningChunk = "   "
        )

        assertNull(updated.reasoning)
    }
}
