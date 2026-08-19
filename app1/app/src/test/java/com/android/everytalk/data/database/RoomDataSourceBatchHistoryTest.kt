package com.android.everytalk.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.database.entities.HISTORY_PREVIEW_OUTPUT_TYPE
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class RoomDataSourceBatchHistoryTest {

    private lateinit var database: AppDatabase
    private lateinit var dataSource: RoomDataSource
    private val queries = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        stopKoin()
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback({ sql, _ -> queries += sql }, Executor { command -> command.run() })
            .build()
        mockkObject(AppDatabase.Companion)
        every { AppDatabase.getDatabase(any()) } returns database
        dataSource = RoomDataSource(context)
    }

    @After
    fun tearDown() {
        unmockkObject(AppDatabase.Companion)
        database.close()
        stopKoin()
    }

    @Test
    fun `加载多会话只执行一次消息查询`() = runBlocking {
        dataSource.saveChatHistory((0 until 6).map(::conversation))
        queries.clear()

        val result = dataSource.loadChatHistoryResult()

        assertEquals(6, result.sessions.size)
        val messageQueries = queries.count { sql ->
            val normalized = sql.replace("`", "").trimStart().uppercase()
            normalized.startsWith("SELECT") && Regex("\\bFROM\\s+MESSAGES\\b").containsMatchIn(normalized)
        }
        assertEquals("实际 SQL：$queries", 1, messageQueries)
    }

    @Test
    fun `重复同步未变化历史不重写会话和消息`() = runBlocking {
        val history = (0 until 3).map(::conversation)
        dataSource.saveChatHistory(history)
        queries.clear()

        dataSource.saveChatHistory(history)

        val writeQueries = synchronized(queries) {
            queries.filter { sql ->
                val normalized = sql.trimStart().uppercase()
                normalized.startsWith("INSERT") || normalized.startsWith("UPDATE") || normalized.startsWith("DELETE")
            }
        }
        assertTrue("未变化历史不应产生写语句：$writeQueries", writeQueries.isEmpty())
    }

    @Test
    fun `启动预览只查询标量字段且按需加载恢复完整消息`() = runBlocking {
        val original = Message(
            id = "preview-session",
            text = "可搜索正文",
            sender = Sender.AI,
            reasoning = "大型思考内容",
            executionTrace = listOf(ExecutionTraceEvent.Reasoning("完整执行轨迹")),
            enabledToolIds = listOf("computer"),
        )
        dataSource.saveChatHistory(listOf(listOf(original)))
        queries.clear()

        val preview = dataSource.loadChatHistoryPreviewResult().sessions.single().messages.single()
        val previewQuery = synchronized(queries) {
            queries.single { sql ->
                val normalized = sql.replace("`", "").lines().joinToString(" ") { it.trim() }.trimStart().uppercase()
                normalized.startsWith("SELECT") && normalized.contains("FROM MESSAGES")
            }
        }.uppercase()

        assertEquals(HISTORY_PREVIEW_OUTPUT_TYPE, preview.outputType)
        assertEquals("可搜索正文", preview.text)
        assertEquals(null, preview.reasoning)
        assertTrue("预览查询不应读取附件 JSON：$previewQuery", "ATTACHMENTS" !in previewQuery)
        assertTrue("预览查询不应读取执行轨迹 JSON：$previewQuery", "EXECUTIONTRACE" !in previewQuery)
        assertTrue("预览查询不应读取 Markdown JSON：$previewQuery", "PARTS" !in previewQuery)

        val restored = dataSource.loadHistorySession("preview-session")!!.single()
        assertEquals(original.reasoning, restored.reasoning)
        assertEquals(original.executionTrace, restored.executionTrace)
        assertEquals(original.enabledToolIds, restored.enabledToolIds)
    }

    @Test
    fun `轻量预览参与批量保存不会覆盖完整消息`() = runBlocking {
        val original = Message(
            id = "protected-preview",
            text = "正文",
            sender = Sender.AI,
            reasoning = "必须保留",
        )
        dataSource.saveChatHistory(listOf(listOf(original)))
        val previewHistory = dataSource.loadChatHistoryPreviewResult().sessions.map { it.messages }

        dataSource.saveChatHistory(previewHistory)

        assertEquals("必须保留", dataSource.loadHistorySession("protected-preview")!!.single().reasoning)
    }

    @Test
    fun `重命名只更新会话元数据且后续保存保留标题`() = runBlocking {
        val original = Message(id = "renamed-session", text = "问题", sender = Sender.User)
        dataSource.saveChatHistory(listOf(listOf(original)))
        queries.clear()

        dataSource.renameHistorySession("renamed-session", "新标题")

        val renameWrites = synchronized(queries) {
            queries.filter { sql ->
                val normalized = sql.replace("`", "").trimStart().uppercase()
                normalized.startsWith("INSERT") || normalized.startsWith("UPDATE") || normalized.startsWith("DELETE")
            }
        }
        assertEquals(1, renameWrites.size)
        assertTrue(renameWrites.single().uppercase().contains("CHAT_SESSIONS"))
        assertTrue(renameWrites.single().uppercase().contains("TITLE"))

        dataSource.saveChatHistory(listOf(listOf(original.copy(text = "修改后的问题"))))

        val loaded = dataSource.loadHistorySession("renamed-session")!!
        assertEquals("新标题", loaded.first().text)
        assertTrue(loaded.first().isPlaceholderName)
        assertEquals("修改后的问题", loaded.last().text)
        assertEquals(1, database.chatDao().getMessagesForSession("renamed-session").size)
    }

    private fun conversation(index: Int): List<Message> = listOf(
        Message(id = "conversation-$index", text = "消息 $index", sender = Sender.User)
    )
}
