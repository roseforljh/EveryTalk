package com.android.everytalk.data.agent

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.android.everytalk.data.DataClass.*
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CloudAgentStoreTest {
    private val account = "aeff1b26-d0fb-4ee9-98c7-d2cab5418671"
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()

    @Test fun `提交前身份持久固定快照与游标同事务跨实例恢复且不重复回传`() = runBlocking {
        val db = database()
        try {
            val chat = db.chatDao()
            val session = ChatSessionEntity("session", 1, 1, false)
            val user = Message(id = "user", text = "调整", sender = Sender.User, timestamp = 2).toEntity(session.id)
            val ai = Message(id = "ai", text = "", sender = Sender.AI, timestamp = 3).toEntity(session.id)
            chat.writeSession(session)
            chat.writeMessages(listOf(user))
            chat.writeDrafts(listOf(MessageDraftEntity(ai)))
            val store = CloudAgentStore(db)
            val run = store.prepare(account, session.id, user.id, ai.id, "config")
            assertEquals(run.id, store.prepare(account, session.id, user.id, ai.id, "config").id)
            assertNull(run.requestSnapshotJson)
            val tool = CloudRunTrace("tool", 5, id = "tool", name = "write_file", arguments = buildJsonObject {
                put("path", "/work/a.kt"); put("content", "新文件")
            }, finishedAt = 6, result = buildJsonObject { put("ok", true) })
            val view = CloudRunView(1, run.id, session.uuid, user.uuid, ai.uuid, "RUNNING", 2, 7,
                CloudRunSnapshot("之前之后", "思考", listOf(CloudRunTrace("content", 3, text = "之前"),
                    CloudRunTrace("user", 4, messageUuid = user.uuid), tool, CloudRunTrace("content", 7, text = "之后")), "执行中", 3))
            val message = checkNotNull(store.apply(account, view))
            assertEquals("user", (message.executionTrace[1] as ExecutionTraceEvent.UserMessageBoundary).messageId)
            assertEquals("新文件", message.executionSteps.single().fileChange!!.edits.single().newText)
            assertEquals(7L, db.agentDao().getRun(run.id)!!.cloudCursor)
            assertEquals(view, CloudAgentStore(db).read(run.id))
            assertNull(store.apply(account, view))
            assertNull(store.apply(account, view.copy(cursor = 6)))
            assertEquals("之前之后", chat.getMessage(session.id, ai.id)!!.text)
            val invalid = view.copy(cursor = 9, snapshot = view.snapshot.copy(trace = listOf(CloudRunTrace("invalid", 8))))
            assertTrue(runCatching { store.apply(account, invalid) }.isFailure)
            assertEquals(7L, db.agentDao().getRun(run.id)!!.cloudCursor)
            assertEquals(view, store.read(run.id))
            val final = view.copy(cursor = 10, status = "COMPLETED", snapshot = view.snapshot.copy(finishedAt = 11, executionStatus = null))
            store.apply(account, final)
            assertNull(chat.getDraft(session.id, ai.id))
            val formal = checkNotNull(chat.getMessageByUuid(ai.uuid))
            assertEquals(SyncStatus.SYNCED, formal.syncStatus)
            assertEquals("$account:${formal.storageFingerprint}", db.settingsDao().getValue("sync_resource_source:${ai.uuid}"))
            assertNull(store.apply(account, view.copy(cursor = 11)))
            db.agentDao().deleteTerminalRunSnapshotChunks()
            assertEquals(final, store.read(run.id))
        } finally { db.close() }
    }

    @Test fun `换号墓碑以及旧本机执行器均不能接管云端任务`() = runBlocking {
        val db = database()
        try {
            val chat = db.chatDao()
            val session = ChatSessionEntity("session", 1, 1, false)
            val user = Message(id = "user", text = "问题", sender = Sender.User).toEntity(session.id)
            chat.writeSession(session); chat.writeMessages(listOf(user))
            val store = CloudAgentStore(db)
            val run = store.prepare(account, session.id, user.id, "ai", null)
            val view = CloudRunView(1, run.id, session.uuid, user.uuid, localSyncUuid("messages", "ai"), "RUNNING", 1, 1,
                CloudRunSnapshot("回复", "", emptyList(), "执行中", run.createdAt))
            assertTrue(runCatching { store.apply("b9084d09-8992-4ba7-b8b7-835081bde351", view) }.isFailure)
            assertFalse(canExecuteAgentSnapshot(run, run))
            store.apply(account, view)
            db.agentDao().markModelRunsPendingContinuation(20)
            assertEquals(AgentRunStatus.WAITING_MODEL.name, db.agentDao().getRun(run.id)!!.status)
            chat.writeSession(session.copy(deleted = true))
            assertNull(store.apply(account, view.copy(cursor = 2)))
            assertEquals(1L, db.agentDao().getRun(run.id)!!.cloudCursor)
        } finally { db.close() }
    }
}
