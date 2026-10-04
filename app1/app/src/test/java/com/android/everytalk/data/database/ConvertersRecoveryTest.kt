package com.android.everytalk.data.database

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.database.entities.toEntity
import com.android.everytalk.models.SelectedMediaItem
import com.android.everytalk.ui.components.MarkdownPart
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 覆盖空值、旧类型、坏 JSON 和真实 Room 查询，确保恢复不会修改原始数据。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class ConvertersRecoveryTest {
    private val converters = Converters()

    @Test
    fun `空值和损坏数据不会中断列表恢复`() {
        val invalidValues = listOf(null, "", " \n", "not-json", "{}", "[null]", "[{\"type\":\"removed-type\"}]")
        invalidValues.forEach { value ->
            assertTrue(converters.toSelectedMediaItemList(value).isEmpty())
            assertTrue(converters.toMarkdownPartList(value).isEmpty())
        }
    }

    @Test
    fun `有效附件和Markdown仍可完整往返`() {
        val attachments = listOf(
            SelectedMediaItem.GenericFile(Uri.parse("content://test/document"), "file", "中文.txt", "text/plain"),
            SelectedMediaItem.Audio("audio", "audio/mp3", filePath = "/private/audio.mp3"),
        )
        val parts = listOf(
            MarkdownPart.Text("text", "正文"),
            MarkdownPart.CodeBlock("code", "echo hello", "bash"),
            MarkdownPart.InlineImage("image", "image/png", "aGVsbG8="),
        )
        assertEquals(attachments, converters.toSelectedMediaItemList(converters.fromSelectedMediaItemList(attachments)))
        assertEquals(parts, converters.toMarkdownPartList(converters.fromMarkdownPartList(parts)))
    }

    @Test
    fun `Room读取坏字段后仍返回会话消息且不改写原始JSON`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val session = ChatSessionEntity("recovery-session", 1L, 2L, false)
            val messages = listOf(
                Message(id = "damaged", text = "坏字段之外的正文", sender = Sender.AI, timestamp = 1L),
                Message(id = "healthy", text = "正常消息", sender = Sender.User, timestamp = 2L),
            )
            val dao = database.chatDao()
            dao.saveSessionWithMessages(session, messages.map { it.toEntity(session.id) })
            val raw = database.openHelper.writableDatabase
            raw.execSQL("UPDATE messages SET attachments = ?, parts = ? WHERE id = ?",
                arrayOf("not-json", "[{\"type\":\"removed-type\"}]", "damaged"))

            val recovered = dao.getMessagesForSession(session.id)
            assertEquals(messages.map { it.id to it.text }, recovered.map { it.id to it.text })
            assertTrue(recovered.first().attachments.isEmpty())
            assertTrue(recovered.first().parts.isEmpty())
            raw.query("SELECT attachments, parts FROM messages WHERE id = 'damaged'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("not-json", cursor.getString(0))
                assertEquals("[{\"type\":\"removed-type\"}]", cursor.getString(1))
            }
        } finally {
            database.close()
        }
    }
}
