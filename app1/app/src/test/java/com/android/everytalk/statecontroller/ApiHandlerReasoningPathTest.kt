package com.android.everytalk.statecontroller

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `新一轮思考会清除上一轮工具等待状态`() {
        val updated = applyActiveReasoningChunk(
            currentMessage = Message(
                id = "ai-agent-loop",
                text = "我先检查服务器",
                sender = Sender.AI,
                reasoning = "第一轮思考",
                contentStarted = true,
                currentWebSearchStage = "运行 Agent · exec",
                executionStatus = AGENT_LOOP_CONTINUING_STATUS,
            ),
            reasoningChunk = "第二轮思考",
        )

        assertEquals("第一轮思考", updated.reasoning)
        assertNull(updated.currentWebSearchStage)
        assertNull(updated.executionStatus)
    }

    @Test
    fun `新一轮思考会重新打开运行态`() {
        val completionMap = mutableMapOf("ai-agent-loop" to true)

        markReasoningRoundActive(completionMap, "ai-agent-loop")

        assertFalse(completionMap.getValue("ai-agent-loop"))
    }
}
