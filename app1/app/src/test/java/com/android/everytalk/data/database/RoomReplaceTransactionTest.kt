package com.android.everytalk.data.database

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.database.entities.ConversationGroupEntity
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.database.entities.toEntity
import com.android.everytalk.models.SelectedMediaItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class RoomReplaceTransactionTest {

    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `替换写入失败时保留原分组数据`() = runBlocking {
        val dao = database.settingsDao()
        val original = ConversationGroupEntity("original", listOf("conversation-1"))
        dao.insertConversationGroups(listOf(original))
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER reject_failed_group
            BEFORE INSERT ON conversation_groups
            WHEN NEW.groupName = 'fail'
            BEGIN
                SELECT RAISE(ABORT, 'reject replacement');
            END
            """.trimIndent()
        )

        runCatching {
            dao.replaceConversationGroups(
                listOf(ConversationGroupEntity("fail", listOf("conversation-2")))
            )
        }

        assertEquals(listOf(original), dao.getConversationGroups())
    }

    @Test
    fun `保存会话只更新变化消息并删除已移除消息`() = runBlocking {
        val dao = database.chatDao()
        val session = ChatSessionEntity("session-1", 1L, 2L, false)
        val original = listOf(
            Message(id = "message-1", text = "保持不变", sender = Sender.User, timestamp = 1L),
            Message(id = "message-2", text = "旧内容", sender = Sender.AI, timestamp = 2L),
            Message(id = "message-3", text = "将被删除", sender = Sender.User, timestamp = 3L),
        )
        dao.saveSessionWithMessages(session, original.map { it.toEntity(session.id) })
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER reject_unchanged_message_update
            BEFORE UPDATE ON messages
            WHEN OLD.id = 'message-1'
            BEGIN
                SELECT RAISE(ABORT, 'unchanged message must not be updated');
            END
            """.trimIndent(),
        )

        val updated = listOf(original[0], original[1].copy(text = "新内容"))
        dao.saveSessionWithMessages(
            session.copy(lastModifiedTimestamp = 4L),
            updated.map { it.toEntity(session.id) },
        )

        assertEquals(
            updated.map { it.id to it.text },
            dao.getMessagesForSession(session.id).map { it.id to it.text },
        )
    }

    @Test
    fun `后台下载附件只绑定原消息并保持持久化指纹一致`() = runBlocking {
        val dao = database.chatDao()
        val session = ChatSessionEntity("download-session", 1L, 2L, false)
        val first = Message(id = "assistant-1", text = "第一条", sender = Sender.AI, timestamp = 1L)
        val second = Message(id = "assistant-2", text = "第二条", sender = Sender.AI, timestamp = 2L)
        dao.saveSessionWithMessages(session, listOf(first, second).map { it.toEntity(session.id) })
        val attachment = SelectedMediaItem.GenericFile(
            uri = Uri.parse("content://test/download"),
            id = "download-1",
            displayName = "result.txt",
            mimeType = "text/plain",
        )

        assertEquals(true, dao.appendDownloadedAttachment(session.id, second.id, attachment))
        assertEquals(false, dao.appendDownloadedAttachment(session.id, "missing", attachment))
        assertEquals(true, dao.appendDownloadedAttachment(session.id, second.id, attachment))
        val saved = dao.getMessagesForSession(session.id)
        assertEquals(emptyList<SelectedMediaItem>(), saved.first { it.id == first.id }.attachments)
        assertEquals(listOf(attachment), saved.first { it.id == second.id }.attachments)
        assertEquals(second.copy(attachments = listOf(attachment)).toEntity(session.id).storageFingerprint,
            saved.first { it.id == second.id }.storageFingerprint)
    }
}
