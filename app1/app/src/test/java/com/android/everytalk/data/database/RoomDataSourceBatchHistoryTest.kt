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

        val writeQueries = queries.filter { sql ->
            val normalized = sql.trimStart().uppercase()
            normalized.startsWith("INSERT") || normalized.startsWith("UPDATE") || normalized.startsWith("DELETE")
        }
        assertTrue("未变化历史不应产生写语句：$writeQueries", writeQueries.isEmpty())
    }

    private fun conversation(index: Int): List<Message> = listOf(
        Message(id = "conversation-$index", text = "消息 $index", sender = Sender.User)
    )
}
