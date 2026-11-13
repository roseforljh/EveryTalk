package com.android.everytalk.data.sync

import com.android.everytalk.data.DataClass.ExecutionStep
import com.android.everytalk.data.DataClass.ExecutionStepType
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LiveSyncTest {
    @Test
    fun `实时快照往返保留正文思考和步骤，去掉工具结果`() {
        val step = ExecutionStep("s1", ExecutionStepType.Tool, "执行命令", detail = "ls", started = true, resultPreview = "secret output")
        val message = Message(
            id = "local-1", text = "答案", sender = Sender.AI, reasoning = "思考", executionStatus = "正在执行工具",
            executionTrace = listOf(ExecutionTraceEvent.Reasoning("思考"), ExecutionTraceEvent.Tool(step), ExecutionTraceEvent.Content("答案")),
        )
        val decoded = LiveSync.decode(LiveSync.encode(LiveSnapshot("uuid-1", message)))
        assertEquals("uuid-1", decoded.messageUuid)
        assertEquals("答案", decoded.message.text)
        assertEquals("思考", decoded.message.reasoning)
        assertEquals("正在执行工具", decoded.message.executionStatus)
        assertEquals(3, decoded.message.executionTrace.size)
        val tool = decoded.message.executionTrace[1] as ExecutionTraceEvent.Tool
        assertEquals("ls", tool.step.detail)
        assertNull(tool.step.resultPreview)
    }

    @Test
    fun `能解码网页端上报的快照`() {
        val web = """{"messageUuid":"m1","message":{"id":"m1","text":"网页正文","sender":"AI","contentStarted":true,
            "reasoning":"想","executionStatus":"正在搜索","executionSteps":[{"id":"s","type":"Search","title":"搜索",
            "labels":["q"],"completed":false,"started":true,"isError":false}]}}"""
        val gz = java.io.ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write(web.toByteArray()) } }
        val decoded = LiveSync.decode(android.util.Base64.encodeToString(gz.toByteArray(), android.util.Base64.NO_WRAP))
        assertEquals("网页正文", decoded.message.text)
        assertEquals(ExecutionStepType.Search, decoded.message.executionSteps.single().type)
        assertEquals("正在搜索", decoded.message.executionStatus)
    }
}
