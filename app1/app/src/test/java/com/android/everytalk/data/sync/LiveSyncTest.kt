package com.android.everytalk.data.sync

import com.android.everytalk.data.DataClass.ExecutionStep
import com.android.everytalk.data.DataClass.ExecutionStepType
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.database.entities.toEntity
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
    fun `实时快照保留变更卡片工具结果搜索来源和用量`() {
        val step = ExecutionStep("s1", ExecutionStepType.Tool, "写入文件", detail = "write_file", completed = true, started = true,
            resultPreview = "写入成功", fileChange = com.android.everytalk.data.DataClass.ExecutionFileChange(
                "/workspace/main.kt", true, listOf(com.android.everytalk.data.DataClass.ExecutionFileEdit("", "正文"))))
        val message = Message(
            id = "local-1", text = "答案", sender = Sender.AI, reasoning = "思考", executionStatus = "正在执行工具",
            executionSteps = listOf(step),
            webSearchResults = listOf(com.android.everytalk.data.DataClass.WebSearchResult(1, "来源", "https://example.com", "摘要")),
            contextUsageSnapshot = com.android.everytalk.data.DataClass.ContextUsageSnapshot("local-1", systemPromptTokens = 1,
                conversationTextTokens = 2, mediaTokens = 0, toolSchemaTokens = 3, protocolOverheadTokens = 4,
                reservedOutputTokens = 100, contextWindowTokens = 1000),
            executionTrace = listOf(ExecutionTraceEvent.Reasoning("思考"), ExecutionTraceEvent.Tool(step), ExecutionTraceEvent.Content("答案")),
        )
        val decoded = LiveSync.decode(LiveSync.encode(LiveSnapshot("uuid-1", message)))
        assertEquals("uuid-1", decoded.messageUuid)
        assertEquals("答案", decoded.message.text)
        assertEquals("思考", decoded.message.reasoning)
        assertEquals("正在执行工具", decoded.message.executionStatus)
        assertEquals(3, decoded.message.executionTrace.size)
        val tool = decoded.message.executionTrace[1] as ExecutionTraceEvent.Tool
        assertEquals(step, tool.step)
        assertEquals(message.executionSteps, decoded.message.executionSteps)
        assertEquals(message.webSearchResults, decoded.message.webSearchResults)
        assertEquals(message.contextUsageSnapshot, decoded.message.contextUsageSnapshot)
    }

    @Test
    fun `追加消息跨两份Room映射后保持边界身份且兼容旧本地ID`() = kotlinx.coroutines.runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val a = androidx.room.Room.inMemoryDatabaseBuilder(context, com.android.everytalk.data.database.AppDatabase::class.java).build()
        val b = androidx.room.Room.inMemoryDatabaseBuilder(context, com.android.everytalk.data.database.AppDatabase::class.java).build()
        try {
            a.chatDao().writeSession(com.android.everytalk.data.database.entities.ChatSessionEntity("a", 1, 1, false))
            b.chatDao().writeSession(com.android.everytalk.data.database.entities.ChatSessionEntity("b", 1, 1, false))
            val question = Message(id = "original-user", text = "调整", sender = Sender.User, timestamp = 20)
                .toEntity("a")
            a.chatDao().writeMessages(listOf(question))
            b.chatDao().writeMessages(listOf(question.copy(id = "cloud:${question.uuid}", sessionId = "b")))
            val source = Message(id = "original-ai", text = "之前之后", sender = Sender.AI, executionTrace = listOf(
                ExecutionTraceEvent.Content("之前"), ExecutionTraceEvent.UserMessageBoundary(question.id), ExecutionTraceEvent.Content("之后")))
            val wire = mapLiveMessageReferences(source, a.chatDao()::resolveMessageSyncUuid)
            assertEquals(question.uuid, (wire.executionTrace[1] as ExecutionTraceEvent.UserMessageBoundary).messageId)
            for (incoming in listOf(wire, source)) {
                val restored = mapLiveMessageReferences(incoming, b.chatDao()::resolveLocalMessageReference)
                assertEquals("cloud:${question.uuid}", (restored.executionTrace[1] as ExecutionTraceEvent.UserMessageBoundary).messageId)
            }
        } finally { a.close(); b.close() }
    }

    @Test
    fun `终态快照保留最后正文和结束标记`() {
        val message = Message(id = "final", text = "最后一句", sender = Sender.AI, executionFinishedAt = 123L)
        val decoded = LiveSync.decode(LiveSync.encode(LiveSnapshot("uuid-final", message, running = false)))
        assertEquals(false, decoded.running)
        assertEquals("最后一句", decoded.message.text)
        assertEquals(123L, decoded.message.executionFinishedAt)
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
