package com.android.everytalk.data.memory

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.ChatRequest
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.DataClass.SimpleTextApiMessage
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.daos.MemoryTranscriptLine
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.database.entities.MemoryDocumentEntity
import com.android.everytalk.data.database.entities.toEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Codex 风格两阶段记忆：闲置会话摘要 → 总览整合 → 注入与按需读取。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class MemoryConsolidatorTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: MemoryRepository
    private val now = System.currentTimeMillis()
    private val idle = now - 60 * 60_000L
    private val base = ChatRequest(messages = emptyList(), provider = "OpenAI", channel = "OpenAI兼容",
        apiAddress = "https://example.invalid", apiKey = "test-key", model = "test")
    private val calls = mutableListOf<ChatRequest>()

    /** 假模型：第一阶段按会话标题返回摘要，第二阶段返回带指针的总览。 */
    private val model: suspend (ChatRequest) -> String = { request ->
        calls += request
        val system = (request.messages.first() as SimpleTextApiMessage).content
        val user = (request.messages.last() as SimpleTextApiMessage).content
        when {
            system == com.android.everytalk.data.network.SystemPromptModules.conversationMemory && "闲聊" in user -> """{"title":"","summary":""}"""
            system == com.android.everytalk.data.network.SystemPromptModules.conversationMemory ->
                "```json\n" + """{"title":"节点迁移","summary":"用户要求把节点迁到 sing-box。token: sk-abcdefghijklmnop 已用完。"}""" + "\n```"
            else -> "## User profile\nNone\n## User preferences\nNone\n## General tips\nNone\n## What's in memory\n### 代理\n- [conv:work] 1970-01-10 — 节点迁移到 sing-box\n" +
                (if ("用户笔记" in user) "笔记已整合" else "")
        }
    }

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        repository = MemoryRepository(database)
    }
    @After fun close() { database.close() }

    private suspend fun conversation(id: String, title: String, vararg texts: String, at: Long = idle) {
        database.chatDao().insertSession(ChatSessionEntity(id, 1, 1, false, title))
        database.chatDao().writeMessages(texts.mapIndexed { index, text ->
            Message(id = "$id-$index", text = text, sender = if (index % 2 == 0) Sender.User else Sender.AI, timestamp = at + index).toEntity(id)
        })
    }

    @Test fun `闲置会话生成摘要并整合成总览，输入不变不重复请求`() = runBlocking {
        repository.setSettings(true)
        conversation("work", "迁移", "把节点迁到 sing-box", "好的，已改")
        conversation("chat", "闲聊", "今天天气怎样", "晴")
        conversation("fresh", "刚聊", "还在进行", "嗯", at = now - 60_000)
        conversation("current", "当前", "当前会话", "回复")
        conversation("single", "单条", "只有一句")
        val consolidator = MemoryConsolidator(database, generate = { MemoryModelReply(model(it), 100) })
        consolidator.runOnce(base, "current", repository.settings().version)

        val summaries = database.memoryDao().getDocuments(MemoryDocumentEntity.KIND_CONVERSATION).associateBy { it.id }
        assertEquals(setOf("work", "chat"), summaries.keys)
        assertEquals("", summaries.getValue("chat").content)
        val work = summaries.getValue("work")
        assertEquals("节点迁移", work.title)
        assertTrue("[REDACTED]" in work.content && "sk-abcdefghijklmnop" !in work.content)
        // 第一阶段请求不带工具和附加上下文；第二阶段的输入带会话 ID，供总览写指针。
        assertTrue(calls.all { it.tools == null && it.localMemorySnapshot == null })
        assertTrue((calls.last().messages.last() as SimpleTextApiMessage).content.contains("### conv:work"))
        assertTrue(database.memoryDao().getDocument(MemoryDocumentEntity.OVERVIEW_ID)!!.content.contains("[conv:work]"))

        val count = calls.size
        val stats = repository.observeStats().first()
        assertEquals(count.toLong(), stats.modelCalls)
        assertEquals(100L * count, stats.tokens)
        assertEquals(1, stats.conversations)
        assertTrue(stats.lastUpdatedAt > 0)
        consolidator.runOnce(base, "current", repository.settings().version)
        assertEquals(count, calls.size)

        // 有新消息才重写该会话的摘要。
        database.chatDao().writeMessages(listOf(Message(id = "work-new", text = "再加一个节点", sender = Sender.User, timestamp = idle + 10).toEntity("work")))
        consolidator.runOnce(base, "current", repository.settings().version)
        assertEquals(count + 2, calls.size)
    }

    @Test fun `总览注入后可按会话读取摘要，笔记参与整合，删除会话和清空记忆会清掉派生内容`() = runBlocking {
        repository.setSettings(true)
        conversation("work", "迁移", "把节点迁到 sing-box", "好的，已改")
        val note = repository.save("用户笔记：回复要简短", MemoryCategory.USER_PREFERENCE)
        val consolidator = MemoryConsolidator(database, generate = { MemoryModelReply(model(it), 100) })
        consolidator.runOnce(base, "current", repository.settings().version)
        val overview = database.memoryDao().getDocument(MemoryDocumentEntity.OVERVIEW_ID)!!
        assertTrue(overview.content.contains("笔记已整合"))

        val prompt = MemoryPromptRenderer.render(repository.state().entries, "节点", conversationId = "current", overview = overview)
        assertTrue(prompt.contains("[conv:work]") && prompt.contains("read_summary"))
        assertFalse("整合前的旧笔记不重复注入", prompt.contains(note.id))
        val later = repository.save("新笔记", MemoryCategory.OTHER)
        assertTrue(MemoryPromptRenderer.render(repository.state().entries, "", overview = overview).contains(later.id))

        val tool = MemoryTool(repository, ConversationHistorySearch(database))
        val grant = MemoryRequestSnapshot(MemoryWriteContext(repository.settings().version, "current", "m", MemoryOrigin.CHAT_EXPLICIT), "节点", "")
        val read = tool.execute(buildJsonObject { put("action", "read_summary"); put("conversation_id", "work") }, grant).jsonObject
        assertTrue(read.getValue("summary").jsonPrimitive.content.contains("sing-box"))
        val search = tool.execute(buildJsonObject { put("action", "search"); put("query", "sing-box") }, grant).jsonObject
        assertTrue(search.toString().contains("\"conversation_id\":\"work\""))

        database.chatDao().deleteSession("work")
        consolidator.runOnce(base, "current", repository.settings().version)
        assertNull(database.memoryDao().getDocument("work"))
        repository.clear()
        assertNull(database.memoryDao().getDocument(MemoryDocumentEntity.OVERVIEW_ID))
    }

    @Test fun `关闭记忆或授权变化后不再请求也不写入`() = runBlocking {
        conversation("work", "迁移", "把节点迁到 sing-box", "好的，已改")
        val consolidator = MemoryConsolidator(database, generate = { MemoryModelReply(model(it), 100) })
        consolidator.runOnce(base, "current", repository.settings().version)
        repository.setSettings(true)
        consolidator.runOnce(base, "current", repository.settings().version - 1)
        assertTrue(calls.isEmpty())
        assertTrue(database.memoryDao().getDocuments(MemoryDocumentEntity.KIND_CONVERSATION).isEmpty())
    }

    @Test fun `长会话保留开头和结尾`() {
        val lines = (1..200).map { MemoryTranscriptLine(if (it % 2 == 0) "AI" else "User", "第 $it 条 " + "内容".repeat(60), it.toLong()) }
        val text = MemoryConsolidator.renderTranscript(lines)
        assertTrue(text.startsWith("User: 第 1 条") && text.contains("第 200 条") && text.contains("omitted"))
        assertTrue(text.memoryCharacters() < 16_200)
    }
}
