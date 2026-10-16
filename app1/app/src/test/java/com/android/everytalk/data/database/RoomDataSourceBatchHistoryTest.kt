package com.android.everytalk.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.ExecutionTraceEvent
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.database.entities.HISTORY_PREVIEW_OUTPUT_TYPE
import com.android.everytalk.data.database.entities.toEntity
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

    @Test
    fun `旧账本引用与同步UUID在消息到达前后都指向同一条消息`() = runBlocking {
        val oldId = "user:original-question"
        val uuid = com.android.everytalk.data.database.entities.localSyncUuid("messages", oldId)
        val cloudId = "cloud:$uuid"
        val dao = database.chatDao()
        assertEquals(cloudId, dao.resolveLocalMessageReference(oldId))
        assertEquals(cloudId, dao.resolveLocalMessageReference(uuid))
        assertEquals(cloudId, dao.resolveLocalMessageReference(cloudId))
        val session = com.android.everytalk.data.database.entities.ChatSessionEntity("cloud:session", 1, 1, false)
        dao.writeSession(session)
        dao.writeMessages(listOf(Message(id = cloudId, text = "恢复的问题", sender = Sender.User)
            .toEntity(session.id).copy(uuid = uuid)))
        assertEquals(cloudId, dao.resolveLocalMessageReference(oldId))
        assertEquals(cloudId, dao.resolveLocalMessageReference(uuid))
        assertEquals(uuid, dao.resolveMessageSyncUuid(cloudId))
    }

    @Test
    fun `最近页面保留真实云端会话身份且旧快照能自动恢复`() = runBlocking {
        val id = "cloud:session-uuid"
        val first = Message(id = "cloud:message-uuid", text = "原始消息", sender = Sender.User, conversationId = id)
        dataSource.saveLoadedHistorySession(id, listOf(first), false)
        for (image in listOf(false, true)) {
            val snapshotId = if (image) "last_open_image_generation_v1" else "last_open_chat_v1"
            if (image) dataSource.saveLastOpenImageGenerationChat(listOf(first)) else dataSource.saveLastOpenChat(listOf(first))
            val restored = if (image) dataSource.loadLastOpenImageGenerationChat() else dataSource.loadLastOpenChat()
            assertEquals(id, restored.single().conversationId)
            assertEquals(first.id, restored.single().id)
            database.settingsDao().deleteSetting("${snapshotId}_origin") // 模拟旧版快照。
            val legacy = if (image) dataSource.loadLastOpenImageGenerationChat() else dataSource.loadLastOpenChat()
            assertEquals(id, legacy.single().conversationId)
        }
        val stored = database.chatDao().getStoredSession(id)!!
        database.chatDao().writeSession(stored.copy(deleted = true))
        assertTrue(dataSource.loadLastOpenChat().isEmpty())
    }

    @Test
    fun `最近页面首条草稿尚无历史原件时仍保留会话身份`() = runBlocking {
        val message = Message(id = "new-question", text = "停止后保留", sender = Sender.User,
            conversationId = "local-session", isStreamingDraft = true)
        dataSource.saveLastOpenChat(listOf(message))
        val restored = dataSource.loadLastOpenChat().single()
        assertEquals(message.text, restored.text)
        assertEquals("local-session", restored.conversationId)
        dataSource.saveLastOpenChat(emptyList())
        assertEquals(null, database.settingsDao().getValue("last_open_chat_v1_origin"))
    }

    @Test
    fun `资源补传按最近消息优先且时间相同时分页顺序固定`() = runBlocking {
        val messages = listOf(1L, 3L, 3L, 2L).mapIndexed { i, time ->
            Message(id = "priority-$i", text = "正文", sender = Sender.User, timestamp = time, conversationId = "priority")
        }
        dataSource.saveLoadedHistorySession("priority", messages, false)
        val expected = database.chatDao().getMessagesForSession("priority").sortedWith(
            compareByDescending<com.android.everytalk.data.database.entities.MessageEntity> { it.timestamp }.thenBy { it.uuid })
        val first = database.chatDao().getResourceSyncMessages(2, 0)
        val second = database.chatDao().getResourceSyncMessages(2, 2)
        assertEquals(expected.map { it.uuid }, (first + second).map { it.uuid })
    }

    @Test
    fun `回退首条消息同时清空历史和最近记录并保留附件文件`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val attachment = java.io.File(context.cacheDir, "rewind-image.png").apply { writeText("image") }
        try {
            val original = Message(id = "rewind", text = "图片", sender = Sender.User,
                attachments = listOf(com.android.everytalk.models.SelectedMediaItem.ImageFromBitmap("", "photo", filePath = attachment.path)))
            dataSource.saveLoadedHistorySession("rewind", listOf(original), false)
            dataSource.saveLastOpenChat(listOf(original))

            dataSource.rewindHistorySession("rewind", emptyList(), false, true)

            assertTrue(database.chatDao().getMessagesForSession("rewind").isEmpty())
            assertTrue(dataSource.loadLastOpenChat().isEmpty())
            assertTrue(attachment.isFile)
        } finally {
            attachment.delete()
        }
    }

    @Test
    fun `回退更新最近记录失败时历史删除也整体回滚`() = runBlocking {
        val original = Message(id = "rewind", text = "原消息", sender = Sender.User)
        dataSource.saveLoadedHistorySession("rewind", listOf(original), false)
        dataSource.saveLastOpenChat(listOf(original))
        assertEquals(listOf("原消息"), database.chatDao().getMessagesForSession("rewind").map { it.text })
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_rewind BEFORE DELETE ON messages " +
                "WHEN OLD.sessionId = 'last_open_chat_v1' BEGIN SELECT RAISE(ABORT, '模拟写入失败'); END",
        )

        val result = runCatching { dataSource.rewindHistorySession("rewind", emptyList(), false, true) }

        assertTrue(result.isFailure)
        assertEquals(listOf("原消息"), database.chatDao().getMessagesForSession("rewind").map { it.text })
        assertEquals(listOf(original.id to original.text), dataSource.loadLastOpenChat().map { it.id to it.text })
    }

    @Test
    fun `回退中间消息后历史和最近快照各自保留前文且消息ID一致`() = runBlocking {
        val first = Message(id = "first", text = "保留", sender = Sender.User)
        val second = Message(id = "second", text = "编辑", sender = Sender.User)
        for (isImage in listOf(false, true)) {
            dataSource.saveLoadedHistorySession(first.id, listOf(first, second), isImage)
            dataSource.rewindHistorySession(first.id, listOf(first), isImage, true)
            assertEquals(listOf(first.id), database.chatDao().getMessagesForSession(first.id).map { it.id })
            val snapshot = if (isImage) dataSource.loadLastOpenImageGenerationChat() else dataSource.loadLastOpenChat()
            assertEquals(listOf(first.id to first.text), snapshot.map { it.id to it.text })
        }
    }

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
    fun `旧历史快照保存时保留旧正文且不能删除新到达会话`() = runBlocking {
        val original = Message(
            id = "rollback-original",
            text = "旧正文",
            sender = Sender.AI,
            reasoning = "旧思考",
        )
        dataSource.saveChatHistory(listOf(listOf(original)))
        val lightweightBackup = dataSource.loadChatHistoryPreviewResult().sessions.map { it.messages }

        val imported = Message(id = "rollback-imported", text = "导入正文", sender = Sender.User)
        dataSource.saveChatHistory(lightweightBackup + listOf(listOf(imported)))
        assertEquals("导入正文", dataSource.loadHistorySession("rollback-imported")!!.single().text)

        dataSource.saveChatHistory(lightweightBackup)

        assertEquals("旧思考", dataSource.loadHistorySession("rollback-original")!!.single().reasoning)
        assertEquals("导入正文", dataSource.loadHistorySession("rollback-imported")!!.single().text)
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

        // 编辑后的正式消息必须使用新身份，会话身份单独保持，不能改写旧 UUID 的正文。
        dataSource.saveLoadedHistorySession("renamed-session", listOf(original.copy(id = "edited-question", text = "修改后的问题")), false)

        val loaded = dataSource.loadHistorySession("renamed-session")!!
        assertEquals("新标题", loaded.first().text)
        assertTrue(loaded.first().isPlaceholderName)
        assertEquals("修改后的问题", loaded.last().text)
        assertEquals(1, database.chatDao().getMessagesForSession("renamed-session").size)
    }

    @Test
    fun `旧批量快照不能删除同会话新同步的消息`() = runBlocking {
        val first = Message(id = "stale-session", text = "原问题", sender = Sender.User, timestamp = 1)
        dataSource.saveChatHistory(listOf(listOf(first)))
        val session = database.chatDao().getStoredSession(first.id)!!
        val remote = Message(id = "remote-answer", text = "另一台设备的新回答", sender = Sender.AI, timestamp = 2)
        dataSource.saveLoadedHistorySession(session.id, listOf(first, remote), false)

        dataSource.saveChatHistory(listOf(listOf(first)))

        assertEquals(listOf(first.text, remote.text), database.chatDao().getMessagesForSession(session.id).map { it.text })
        // 用户明确删除仍生效，不能把快照保护变成无法删除。
        dataSource.saveLoadedHistorySession(session.id, listOf(first), false)
        assertEquals(listOf(first.text), database.chatDao().getMessagesForSession(session.id).map { it.text })
    }

    private fun conversation(index: Int): List<Message> = listOf(
        Message(id = "conversation-$index", text = "消息 $index", sender = Sender.User)
    )
}
