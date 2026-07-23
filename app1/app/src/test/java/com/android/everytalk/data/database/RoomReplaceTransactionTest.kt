package com.android.everytalk.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.database.entities.ConversationGroupEntity
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
}
