package com.android.everytalk.data.database

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.database.daos.acceptFormalMessageUpdate
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.database.entities.AgentRunEntity
import com.android.everytalk.data.database.entities.SyncStatus
import com.android.everytalk.data.database.entities.toEntity
import com.android.everytalk.data.network.TokenUsage
import com.android.everytalk.data.network.TokenUsageSource
import com.android.everytalk.statecontroller.addOrReplaceRegeneratedUserMessage
import com.android.everytalk.statecontroller.controller.conversation.MessageEditSession
import com.android.everytalk.statecontroller.controller.conversation.messagesForEditRestore
import com.android.everytalk.util.ConversationNameHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 真实 Room 事务测试，直接检查正式表、草稿表和待同步记录，避免只验证界面列表。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ChatDaoSyncChangesTest {
    private lateinit var db: AppDatabase
    private val dao get() = db.chatDao()
    private val session = ChatSessionEntity("session", 1, 2, false)
    private val question = Message(id = "q", text = "问题", sender = Sender.User, timestamp = 1)
    private val answer = Message(id = "a", text = "回答", reasoning = "思考", sender = Sender.AI, timestamp = 2)

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
    }
    @After fun close() = db.close()

    private suspend fun save(messages: List<Message>, drafts: Set<String> = emptySet(), debug: Boolean = true) =
        dao.saveSessionWithMessages(session, messages.map { it.toEntity(session.id) }, draftIds = drafts, debug = debug)

    private suspend fun syncAll() = dao.markMessagesSynced(dao.getPendingSyncMessages().map { it.uuid })

    private fun count(table: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
        assertTrue(it.moveToFirst()); it.getInt(0)
    }

    @Test fun `补写token工具状态与附件元数据不改UUID且不重新上传`() = runBlocking {
        save(listOf(question, answer)); syncAll()
        val old = dao.getMessage(session.id, answer.id)!!
        val usage = TokenUsage(inputTokens = 10, outputTokens = 20, isFinal = true, source = TokenUsageSource.OPENAI_CHAT)
        save(listOf(question, answer.copy(tokenUsage = usage, executionStatus = "COMPLETED")))
        val saved = dao.getMessage(session.id, answer.id)!!
        assertEquals(old.uuid, saved.uuid)
        assertEquals(usage, saved.tokenUsage)
        assertEquals("COMPLETED", saved.executionStatus)
        assertEquals(SyncStatus.SYNCED, saved.syncStatus)
        assertTrue(dao.getPendingSyncMessages().isEmpty())
    }

    @Test fun `debug改同步字段报错release跳过该条仍保存其它合法变更`() = runBlocking {
        save(listOf(question, answer)); syncAll()
        val changed = answer.copy(text = "禁止覆盖")
        val newQuestion = question.copy(id = "q2", text = "新增", timestamp = 3)
        assertTrue(runCatching { save(listOf(question, changed, newQuestion)) }.exceptionOrNull() is IllegalStateException)
        assertNull(dao.getMessage(session.id, newQuestion.id))
        save(listOf(question, changed, newQuestion), debug = false)
        assertEquals(answer.text, dao.getMessage(session.id, answer.id)!!.text)
        assertEquals(listOf(newQuestion.id), dao.getPendingSyncMessages().map { it.id })
        val log = mutableListOf<String>()
        assertFalse(acceptFormalMessageUpdate(answer.toEntity(session.id), changed.toEntity(session.id), false, log::add))
        assertEquals(1, log.size)
        assertFalse(log.single().contains(changed.text))
    }

    @Test fun `所有同步白名单字段都不可改写本地字段不受限制`() {
        val old = answer.toEntity(session.id)
        listOf(
            answer.copy(text = "不同正文"), answer.copy(sender = Sender.User), answer.copy(name = "新名称"),
            answer.copy(timestamp = 100), answer.copy(isError = true), answer.copy(outputType = "changed"),
            answer.copy(modelName = "model"), answer.copy(providerName = "provider"), answer.copy(reasoning = "不同思考"),
        ).forEach { changed ->
            assertTrue(runCatching { acceptFormalMessageUpdate(old, changed.toEntity(session.id), true) }.exceptionOrNull() is IllegalStateException)
        }
        assertTrue(acceptFormalMessageUpdate(old, answer.copy(currentWebSearchStage = "DONE", executionStatus = "DONE").toEntity(session.id), true))
    }

    @Test fun `编辑撤回加重发只新增新UUID已同步原消息保留删除变更`() = runBlocking {
        save(listOf(question, answer)); syncAll()
        val oldUuids = dao.getMessagesForSession(session.id).map { it.uuid }.toSet()
        save(emptyList())
        val resent = question.copy(id = "edited", text = "修改后的问题", timestamp = 3)
        save(listOf(resent))
        val changes = dao.getPendingSyncMessages()
        assertEquals(3, changes.size)
        assertEquals(oldUuids, changes.filter { it.deleted }.map { it.uuid }.toSet())
        assertFalse(changes.single { !it.deleted }.uuid in oldUuids)
        assertEquals(listOf(resent.id), dao.getMessagesForSession(session.id).map { it.id })
        assertTrue(dao.getRawMessagesForMode(false).all { it.id == resent.id })
        assertTrue(dao.getLightweightMessagesForMode(false).all { it.id == resent.id })
    }

    @Test fun `较早问题重答旧问题时间不变新问题移末尾并产生新UUID`() = runBlocking {
        val later = question.copy(id = "later", text = "后续问题", timestamp = 3)
        save(listOf(question, answer, later)); syncAll()
        val old = dao.getMessage(session.id, question.id)!!
        val messages = mutableListOf(question, later)
        addOrReplaceRegeneratedUserMessage(messages, question.copy(id = "regenerated", timestamp = 4), true, question.id)
        assertEquals(session.id, ConversationNameHelper.resolveStableId(messages.map { it.copy(conversationId = session.id) }))
        save(messages + answer.copy(id = "new-answer", timestamp = 5))
        val changes = dao.getPendingSyncMessages()
        assertEquals(setOf(question.id, answer.id), changes.filter { it.deleted }.map { it.id }.toSet())
        assertEquals(old.timestamp, changes.single { it.id == question.id }.timestamp)
        assertEquals(old.uuid, changes.single { it.id == question.id }.uuid)
        assertNotEquals(old.uuid, changes.single { it.id == "regenerated" }.uuid)
        assertEquals(listOf(later.id, "regenerated", "new-answer"), dao.getMessagesForSession(session.id).map { it.id })
        assertEquals(4, changes.size)
    }

    @Test fun `复原用新身份复制快照不取消旧墓碑未撤回前缀不变`() = runBlocking {
        val prefix = question.copy(id = "prefix", timestamp = 0)
        save(listOf(prefix, question, answer)); syncAll()
        val before = dao.getMessagesForSession(session.id).associateBy { it.id }
        save(listOf(prefix))
        val edit = MessageEditSession(session.id, false, question, listOf(prefix, question, answer), emptyList(), listOf(prefix.id))
        val restored = messagesForEditRestore(edit)
        save(restored)
        assertEquals(prefix.id, restored.first().id)
        assertEquals(session.id, ConversationNameHelper.resolveStableId(restored))
        assertEquals(listOf(question.text, answer.text), restored.drop(1).map { it.text })
        val changes = dao.getPendingSyncMessages()
        assertEquals(4, changes.size)
        assertEquals(setOf(before.getValue(question.id).uuid, before.getValue(answer.id).uuid), changes.filter { it.deleted }.map { it.uuid }.toSet())
        assertTrue(changes.filterNot { it.deleted }.none { it.uuid in before.values.map { old -> old.uuid } })
        assertTrue(dao.getStoredMessage(question.id, before.getValue(question.id).uuid)!!.deleted)
    }

    @Test fun `删除对话仅产生会话墓碑不为已同步消息逐条标删除`() = runBlocking {
        save(listOf(question, answer)); syncAll()
        dao.deleteSession(session.id)
        assertTrue(dao.getPendingSyncSessions().single().deleted)
        assertTrue(dao.getPendingSyncMessages().isEmpty())
        assertEquals(2, count("messages"))
        assertTrue(dao.getMessageStorageStates(session.id).none { it.deleted })
        assertNull(dao.getSession(session.id))
        assertTrue(dao.getMessagesForSession(session.id).isEmpty())
        save(listOf(question, answer))
        assertTrue(dao.getStoredSession(session.id)!!.deleted)
    }

    @Test fun `本地未同步消息删除直接清理不留消息行草稿或待同步墓碑`() = runBlocking {
        save(listOf(question, answer), drafts = setOf(answer.id))
        save(emptyList())
        assertEquals(0, count("messages"))
        assertEquals(0, count("message_drafts"))
        assertTrue(dao.getPendingSyncMessages().isEmpty())
        save(listOf(question, answer)); dao.deleteSession(session.id)
        assertEquals(0, count("messages"))
        assertEquals(0, count("message_drafts"))
    }

    @Test fun `曾同步状态即使变成PENDING删除仍保留软删除标记`() = runBlocking {
        save(listOf(question)); syncAll()
        db.openHelper.writableDatabase.execSQL("UPDATE messages SET sync_status = 'PENDING'")
        dao.deleteMessagesByIds(session.id, listOf(question.id))
        assertTrue(dao.getPendingSyncMessages().single().deleted)
        assertEquals(1, count("messages"))
    }

    @Test fun `Agent追加入口同样拒绝改写正式内容且删除会话后不能产生新消息`() = runBlocking {
        save(listOf(question)); syncAll()
        assertTrue(runCatching {
            db.agentDao().upsertAgentUserMessage(question.copy(text = "改写").toEntity(session.id))
        }.exceptionOrNull() is IllegalStateException)
        dao.deleteSession(session.id)
        db.agentDao().upsertAgentUserMessage(question.copy(id = "late").toEntity(session.id))
        assertEquals(1, count("messages"))
        assertNull(db.agentDao().getStoredAgentUserMessage("late", "missing"))
    }

    @Test fun `流式草稿更新不入同步停止时部分内容一次定稿并清理草稿`() = runBlocking {
        save(listOf(question, answer.copy(text = "部分")), drafts = setOf(answer.id))
        val uuid = dao.getAllDrafts().single().message.uuid
        assertEquals(listOf(question.id), dao.getPendingSyncMessages().map { it.id })
        save(listOf(question, answer.copy(text = "更多部分")), drafts = setOf(answer.id))
        save(listOf(question, answer.copy(text = "更多部分", isIncomplete = true, executionFinishedAt = 4)))
        val partial = dao.getPendingSyncMessages().single { it.id == answer.id }
        assertEquals(uuid, partial.uuid)
        assertEquals("更多部分", partial.text)
        assertTrue(partial.isIncomplete)
        assertEquals(0, count("message_drafts"))
    }

    @Test fun `后台下载给草稿补附件不能提前将其转成正式消息`() = runBlocking {
        save(listOf(question, answer), drafts = setOf(answer.id))
        val attachment = com.android.everytalk.models.SelectedMediaItem.GenericFile(
            uri = android.net.Uri.parse("content://test/result"), id = "download", displayName = "结果.txt", mimeType = "text/plain",
        )
        assertTrue(dao.appendDownloadedAttachment(session.id, answer.id, attachment))
        assertEquals(listOf(attachment), dao.getAllDrafts().single().message.attachments)
        assertEquals(listOf(question.id), dao.getPendingSyncMessages().map { it.id })
        assertEquals(1, count("messages"))
    }

    @Test fun `Agent对账识别运行草稿重启封存时旧审批不能继续修改同一消息`() = runBlocking {
        save(listOf(question, answer), drafts = setOf(answer.id))
        val run = AgentRunEntity("run", session.id, question.id, answer.id, null, null, "STREAMING_MODEL", 1, null, 1, 2)
        db.agentDao().upsertRun(run)
        assertEquals(0, db.agentDao().markStaleVisibleMessageRunsCancelled("stale", 3))
        db.agentDao().upsertRun(run.copy(status = "WAITING_APPROVAL"))
        RoomDataSource(ApplicationProvider.getApplicationContext(), db).recoverInterruptedDrafts()
        assertEquals("CANCELLED", db.agentDao().getRun(run.id)!!.status)
        assertEquals("DRAFT_RECOVERED_AS_INCOMPLETE", db.agentDao().getRun(run.id)!!.terminalReason)
        assertTrue(db.agentDao().getRun(run.id)!!.runGeneration > run.runGeneration)
        assertTrue(dao.getMessage(session.id, answer.id)!!.isIncomplete)
        assertEquals(0, count("message_drafts"))
    }

    @Test fun `崩溃重开数据库恢复残留草稿为未完成正式消息空草稿清理且可重复调用`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "draft-recovery-test"
        context.deleteDatabase(name)
        try {
            db.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            val empty = answer.copy(id = "empty", text = "", reasoning = null)
            save(listOf(question, answer, empty), drafts = setOf(answer.id, empty.id))
            val uuid = dao.getAllDrafts().first { it.message.id == answer.id }.message.uuid
            db.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            dao.recoverInterruptedDrafts(); dao.recoverInterruptedDrafts()
            assertEquals(0, count("message_drafts"))
            assertEquals(2, count("messages"))
            assertEquals(uuid, dao.getPendingSyncMessages().single { it.id == answer.id }.uuid)
            assertTrue(dao.getMessage(session.id, answer.id)!!.isIncomplete)
            assertEquals(answer.text, dao.getMessage(session.id, answer.id)!!.text)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun `生图和lastopen快照不进入待同步变更`() = runBlocking {
        val image = ChatSessionEntity("image", 1, 2, true)
        val snapshot = ChatSessionEntity("last_open_chat_v1", 1, 2, false)
        dao.saveSessionWithMessages(image, listOf(answer.toEntity(image.id)))
        dao.saveSessionWithMessages(snapshot, listOf(question.toEntity(snapshot.id)))
        assertTrue(dao.getPendingSyncMessages().isEmpty())
        assertTrue(dao.getPendingSyncSessions().isEmpty())
    }
}
