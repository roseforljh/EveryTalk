package com.android.everytalk.data.database

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.android.everytalk.data.database.entities.PendingMessageEntity
import com.android.everytalk.statecontroller.nextDispatchablePending
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PendingMessageDaoTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `FIFO编辑屏障和立即抢占都保留稳定身份`() = runTest {
        val dao = database.chatDao()
        fun pending(id: String, content: String, createdAt: Long) = PendingMessageEntity(
            id = id,
            conversationId = "conversation",
            content = content,
            composerText = content,
            createdAt = createdAt,
            updatedAt = createdAt,
            status = "PENDING",
            queuePosition = -1,
        )

        dao.enqueuePendingMessage(pending("one", "一", 1))
        dao.enqueuePendingMessage(pending("two", "二", 2))
        dao.enqueuePendingMessage(pending("three", "三", 3))
        assertEquals(listOf("one", "two", "three"), dao.observePendingMessages("conversation").first().map { it.id })

        val originalSecond = dao.observePendingMessages("conversation").first()[1]
        assertEquals(1, dao.detachPendingMessageForEdit("two"))
        val whileEditing = dao.observePendingMessages("conversation").first()
        assertEquals(listOf("one", "three"), whileEditing.filter { it.status == "PENDING" }.map { it.id })
        dao.recoverPendingDispatches()
        assertEquals(
            "EDITING",
            dao.observePendingMessages("conversation").first { rows -> rows.any { it.id == "two" } }
                .first { it.id == "two" }
                .status,
        )

        assertEquals(1, dao.claimPendingMessage("one"))
        assertEquals(1, dao.finishPendingDispatch("one"))
        assertEquals(
            null,
            nextDispatchablePending(
                dao.observePendingMessages("conversation").first(),
                editingPosition = 1L,
            ),
        )

        assertEquals(
            1,
            dao.updatePendingMessage("two", "二改", "二改", emptyList(), emptyList(), 20),
        )
        val editedSecond = dao.observePendingMessages("conversation").first().first { it.id == "two" }
        assertEquals(originalSecond.id, editedSecond.id)
        assertEquals(originalSecond.createdAt, editedSecond.createdAt)
        assertEquals(originalSecond.queuePosition, editedSecond.queuePosition)
        assertEquals("二改", editedSecond.content)
        assertEquals(listOf("two", "three"), dao.observePendingMessages("conversation").first().map { it.id })

        assertEquals(1, dao.detachPendingMessageForEdit("two"))
        assertEquals(1, dao.cancelPendingMessageEdit("two"))
        assertEquals(listOf("two", "three"), dao.observePendingMessages("conversation").first().map { it.id })

        assertEquals(1, dao.claimPendingMessage("three"))
        assertEquals(0, dao.claimPendingMessage("three"))
        assertEquals(0, dao.deletePendingMessage("three"))
        assertEquals(1, dao.finishPendingDispatch("three"))
        assertEquals(listOf("two"), dao.observePendingMessages("conversation").first().map { it.id })
    }
}
