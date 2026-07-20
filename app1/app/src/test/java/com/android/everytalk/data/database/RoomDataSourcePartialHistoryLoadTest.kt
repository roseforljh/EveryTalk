package com.android.everytalk.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class RoomDataSourcePartialHistoryLoadTest {

    private lateinit var database: AppDatabase
    private lateinit var dataSource: RoomDataSource

    @Before
    fun setUp() {
        stopKoin()
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
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
    fun `单个坏会话不阻断其他历史并返回失败会话 ID`() = runBlocking {
        dataSource.saveChatHistory(listOf(conversation("good"), conversation("bad")))
        database.openHelper.writableDatabase.execSQL(
            "UPDATE messages SET parts = 'not-json' WHERE sessionId = 'bad'"
        )

        val result = dataSource.loadChatHistoryResult()

        assertEquals(setOf("good"), result.sessions.map { it.sessionId }.toSet())
        assertEquals(setOf("bad"), result.failedSessionIds)
    }

    @Test
    fun `全量保存跳过受保护的失败会话`() = runBlocking {
        dataSource.saveChatHistory(listOf(conversation("good"), conversation("protected")))

        dataSource.saveChatHistory(
            history = listOf(
                conversation("good"),
                listOf(Message(id = "protected", text = "错误覆盖", sender = Sender.User)),
            ),
            protectedSessionIds = setOf("protected"),
        )
        val result = dataSource.loadChatHistoryResult()

        assertEquals(setOf("good", "protected"), result.sessions.map { it.sessionId }.toSet())
        assertEquals(
            "消息 protected",
            result.sessions.single { it.sessionId == "protected" }.messages.single().text,
        )
    }

    @Test
    fun `全量保存缺少受保护会话时也不会删除原记录`() = runBlocking {
        dataSource.saveChatHistory(listOf(conversation("good"), conversation("protected")))

        dataSource.saveChatHistory(
            history = listOf(conversation("good")),
            protectedSessionIds = setOf("protected"),
        )
        val result = dataSource.loadChatHistoryResult()

        assertEquals(setOf("good", "protected"), result.sessions.map { it.sessionId }.toSet())
        assertEquals(
            "消息 protected",
            result.sessions.single { it.sessionId == "protected" }.messages.single().text,
        )
    }

    @Test
    fun `历史全量同步不会混入或删除最后打开会话`() = runBlocking {
        val lastOpen = conversation("last-open")
        dataSource.saveLastOpenChat(lastOpen)

        dataSource.saveChatHistory(listOf(conversation("history")))

        assertEquals(setOf("history"), dataSource.loadChatHistoryResult().sessions.map { it.sessionId }.toSet())
        assertEquals("last-open", dataSource.loadLastOpenChat().single().id)
        assertEquals("消息 last-open", dataSource.loadLastOpenChat().single().text)
    }

    private fun conversation(id: String): List<Message> = listOf(
        Message(id = id, text = "消息 $id", sender = Sender.User)
    )
}
