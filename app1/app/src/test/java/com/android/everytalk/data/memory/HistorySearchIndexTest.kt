package com.android.everytalk.data.memory

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.database.entities.toEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HistorySearchIndexTest {
    private lateinit var database: AppDatabase
    private lateinit var index: HistorySearchIndex

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        index = HistorySearchIndex(database)
    }
    @After fun close() { database.close() }

    private suspend fun conversation(id: String, title: String, vararg texts: Pair<String, Long>) {
        database.chatDao().insertSession(ChatSessionEntity(id, 1, 1, false, title))
        database.chatDao().writeMessages(texts.mapIndexed { i, (text, time) ->
            Message(id = "$id-$i", text = text, sender = if (i % 2 == 0) Sender.User else Sender.AI, timestamp = time).toEntity(id)
        })
    }

    private suspend fun ids(query: String, current: String = "current", conversation: String? = null, start: Long? = null, end: Long? = null) =
        index.search(query, current, conversation, start, end)?.map { it.candidate.messageId }

    @Test fun `分词：中日韩按双字、其他按单词，单字查询交给逐条扫描`() {
        assertEquals(listOf("迁移", "移方", "方案", "sing", "box", "v2"), HistorySearchIndex.tokens("迁移方案 sing-box V2"))
        assertEquals(listOf("猫"), HistorySearchIndex.tokens("猫"))
        assertNull(HistorySearchIndex.queryTerms("猫"))
        assertNull(HistorySearchIndex.queryTerms("!!"))
        assertEquals(listOf("节点", "sing", "box"), HistorySearchIndex.queryTerms("节点 sing-box"))
    }

    @Test fun `按相关度排序，关键词顺序无关，过滤条件与逐条扫描一致`() = runBlocking {
        conversation("a", "代理", "把节点迁到 sing-box 内核" to 100, "好的，节点已经迁移到 sing-box，sing-box 配置如下" to 200)
        conversation("b", "闲聊", "今天的节点很多" to 300, "随便聊聊" to 400)
        conversation("current", "当前", "当前会话也提到 sing-box 节点" to 500)
        // 两个词都命中的排前面；同时出现次数更多的那条（a-1）排第一；当前会话默认排除。
        val hits = ids("sing-box 节点")!!
        assertEquals(listOf("a-1", "a-0"), hits.take(2))
        assertTrue("current-0" !in hits)
        assertEquals(hits.take(2), ids("节点 sing-box")!!.take(2))
        // 指定会话和日期范围。
        assertEquals(listOf("current-0"), ids("sing-box", conversation = "current"))
        assertEquals(listOf("a-1"), ids("sing-box", start = 150, end = 250))
    }

    @Test fun `空格分开的多个关键词全部命中为空时退回部分命中，单个词组必须整体命中`() = runBlocking {
        conversation("a", "代理", "迁移方案选了 sing-box" to 100)
        conversation("b", "其他", "迁移数据库" to 200)
        val hits = index.search("迁移方案 xray", "current", null, null, null)!!
        assertEquals("a-0", hits.first().candidate.messageId)
        assertTrue(hits.any { it.candidate.messageId == "b-0" })
        assertTrue(hits.none { it.complete })
        assertEquals(emptyList<String>(), ids("迁移方案xray"))
    }

    @Test fun `索引随消息修改和删除增量更新`() = runBlocking {
        conversation("a", "代理", "原来的内容 alpha" to 100)
        assertEquals(listOf("a-0"), ids("alpha"))
        database.chatDao().writeMessages(listOf(Message(id = "a-0", text = "改写后的内容 beta", sender = Sender.User, timestamp = 100).toEntity("a")))
        assertEquals(emptyList<String>(), ids("alpha"))
        assertEquals(listOf("a-0"), ids("beta"))
        database.chatDao().deleteSession("a")
        assertEquals(emptyList<String>(), ids("beta"))
    }

    @Test fun `用户说之前给过时自动回忆能用原话找回另一段会话里的内容`() = runBlocking {
        conversation("old", "服务器配置", "412 那台服务器的 GitHub token 是 ghp_example123" to 10L, "收到" to 11L)
        conversation("now", "新会话", "随便聊聊" to 20L)
        val repository = MemoryRepository(database)
        repository.setSettings(true)
        val context = MemoryWriteContext(repository.settings().version, "now", "now-0", MemoryOrigin.CHAT_EXPLICIT)
        val user = "我记得之前有个服务器里我给过了，你去找找，肯定有的好像是412还是哪个，我忘记了"

        assertTrue(PastConversationRecall.triggered(user))
        val keywords = PastConversationRecall.keywords(user)
        val arguments = kotlinx.serialization.json.buildJsonObject {
            put("action", kotlinx.serialization.json.JsonPrimitive("search_conversations"))
            put("query", kotlinx.serialization.json.JsonPrimitive(keywords))
        }
        val recall = PastConversationRecall.render(keywords, ConversationHistorySearch(database).search(arguments, context))
        assertTrue(recall, recall.contains("ghp_example123"))
        assertTrue(!PastConversationRecall.triggered("今天天气怎么样"))
    }
}
