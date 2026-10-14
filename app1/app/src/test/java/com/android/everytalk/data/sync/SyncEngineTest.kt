package com.android.everytalk.data.sync

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

/** 通过真实 Room 事务验证两设备合并、游标持久化、删除和上传中的版本竞争。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SyncEngineTest {
    private val databases = mutableListOf<AppDatabase>()
    private fun device(): AppDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
    ).build().also(databases::add)
    @After fun close() { databases.forEach { it.close() } }
    private fun session(id: String = "local", version: Long = 100) = ChatSessionEntity(id, 1, version, false, "标题", updatedAt = version)
    private fun message(id: String, session: ChatSessionEntity, text: String = id) =
        Message(id = id, text = text, sender = Sender.User, timestamp = 50, reasoning = "思考").toEntity(session.id)
            .copy(updatedAt = 100, syncStatus = SyncStatus.PENDING)
    private suspend fun seed(db: AppDatabase, id: String = "local", count: Int = 1): ChatSessionEntity {
        val session = session(id)
        db.chatDao().writeSession(session)
        db.chatDao().writeMessages((0 until count).map { message("$id-$it", session) })
        return session
    }
    private suspend fun enable(db: AppDatabase) = SyncStateStore(db).setEnabled(true)
    private suspend fun run(db: AppDatabase, api: SyncApi, allowed: Boolean = true) =
        SyncEngine(api, RoomSyncStore(db), SyncStateStore(db), { allowed }).runOnce()
    private fun page(vararg changes: PushChange, since: Long = 0) = PullResponse(changes.mapIndexed { index, change ->
        SyncRecord(change.entity, change.id, change.payload, updatedAt = "2026-10-14T18:30:01Z", deleted = change.deleted, serverSeq = since + index + 1)
    }, since + changes.size, false, 0)

    @Test fun `两设备保留各自历史且拉取不产生回传`() = runBlocking {
        val a = device(); val b = device(); val api = FakeSyncApi()
        seed(a, "a"); seed(b, "b")
        enable(a); enable(b)
        assertEquals(SyncRunResult.Completed, run(a, api))
        assertEquals(SyncRunResult.Completed, run(b, api))
        run(a, api)
        assertEquals(2, a.chatDao().getAllSessions(false).size)
        assertEquals(2, b.chatDao().getAllSessions(false).size)
        assertTrue(RoomSyncStore(a).pendingBatch().isEmpty())
        assertTrue(RoomSyncStore(b).pendingBatch().isEmpty())
        val pushes = api.pushes; run(b, api); run(a, api)
        assertEquals(pushes, api.pushes)
        assertEquals(4, api.rows.size)
    }

    @Test fun `500条分批断网后新引擎续传不重复正文不跳序号`() = runBlocking {
        val db = device(); seed(db, count = 1001); enable(db)
        val api = FakeSyncApi().apply { failPushNumber = 2 }
        assertTrue(runCatching { run(db, api) }.isFailure)
        val cursor = SyncStateStore(db).readCursor().cursor
        assertEquals(500L, cursor)
        assertTrue(RoomSyncStore(db).pendingBatch().isNotEmpty())
        api.failPushNumber = null
        assertEquals(SyncRunResult.Completed, run(db, api))
        assertEquals(1002, api.rows.size)
        assertEquals(1002L, SyncStateStore(db).readCursor().cursor)
        assertTrue(RoomSyncStore(db).pendingBatch().isEmpty())
        assertEquals(4, api.pushes)
    }

    @Test fun `上传期间改标题旧ACK与pull都不能覆盖新变更`() = runBlocking {
        val db = device(); val s = seed(db); val store = RoomSyncStore(db)
        val pending = store.pendingBatch(); val api = FakeSyncApi()
        api.beforePushReply = { db.chatDao().writeSession(s.copy(title = "新标题", updatedAt = 101)) }
        store.applyPush(api.push(pending.map { it.change }), pending)
        store.applyPullPage(api.pull(0))
        assertEquals("新标题", db.chatDao().getStoredSession(s.id)!!.title)
        assertEquals(SyncStatus.PENDING, db.chatDao().getStoredSession(s.id)!!.syncStatus)
        assertEquals(1, store.pendingBatch().size)
        enable(db); run(db, api)
        assertEquals("新标题", api.rows.getValue("sessions" to s.uuid).payload["title"]!!.jsonPrimitive.content)
    }

    @Test fun `同毫秒修改也不被旧确认吞掉且本地标题版本单调`() = runBlocking {
        val db = device(); val s = seed(db); val store = RoomSyncStore(db); val pending = store.pendingBatch()
        val response = FakeSyncApi().push(pending.map { it.change })
        db.chatDao().writeSession(s.copy(title = "相同版本新内容"))
        store.applyPush(response, pending)
        assertEquals(SyncStatus.PENDING, db.chatDao().getStoredSession(s.id)!!.syncStatus)
        db.chatDao().updateSessionTitle(s.id, "再改", now = 100)
        assertEquals(101, db.chatDao().getStoredSession(s.id)!!.updatedAt.toInt())
    }

    @Test fun `上传中删除消息保留墓碑且旧响应不能复活`() = runBlocking {
        val db = device(); val s = seed(db); val store = RoomSyncStore(db)
        val pending = store.pendingBatch(); store.reservePush(pending)
        db.chatDao().deleteMessagesByIds(s.id, listOf("local-0"))
        store.applyPush(FakeSyncApi().push(pending.map { it.change }), pending)
        val tombstone = store.pendingBatch().single().change
        assertTrue(tombstone.deleted)
        assertTrue(tombstone.payload.isEmpty())
    }

    @Test fun `消息先到设置先到后续会话到达正确投影且同UUID不覆盖`() = runBlocking {
        val db = device(); val s = session(); val store = RoomSyncStore(db)
        val msg = SyncPayloadMapper.message(message("m", s), s.uuid)
        val setting = UserSettingEntity("conversation_groups", "Work", "[\"${s.uuid}\"]", updatedAt = 100)
        store.applyPullPage(page(SyncPayloadMapper.userSetting(setting), msg))
        val local = db.chatDao().getSessionByUuid(s.uuid)!!
        assertEquals(listOf(local.id), db.settingsDao().getConversationGroups().single().conversationIds)
        store.applyPullPage(page(SyncPayloadMapper.session(s), msg.copy(payload = JsonObject(msg.payload + ("text" to JsonPrimitive("不能覆盖")))), since = 2))
        assertEquals("m", db.chatDao().getMessageByUuid(msg.id)!!.text)
        assertEquals("标题", db.chatDao().getSessionByUuid(s.uuid)!!.title)
        assertTrue(store.pendingBatch().isEmpty())
    }

    @Test fun `会话墓碑删除子消息及草稿消息墓碑防乱序复活`() = runBlocking {
        val db = device(); val s = seed(db); val store = RoomSyncStore(db)
        db.chatDao().writeDrafts(listOf(MessageDraftEntity(message("draft", s))))
        store.applyPullPage(page(SyncPayloadMapper.session(s.copy(deleted = true))))
        assertTrue(db.chatDao().getMessagesForSession(s.id).isEmpty())
        assertTrue(db.chatDao().getDrafts(s.id).isEmpty())
        assertTrue(store.pendingBatch().isEmpty())
        val other = session("other"); val msg = SyncPayloadMapper.message(message("orphan", other), other.uuid)
        store.applyPullPage(page(msg.copy(deleted = true, payload = JsonObject(emptyMap())), since = 1))
        store.applyPullPage(page(msg, since = 2))
        assertNull(db.chatDao().getMessageByUuid(msg.id))
    }

    @Test fun `rejected和session_deleted不会无限重试`() = runBlocking {
        val db = device(); seed(db); val store = RoomSyncStore(db); val pending = store.pendingBatch()
        store.applyPush(PushResponse(pending.mapIndexed { i, s -> PushResult(i, s.change.entity, s.change.id, "rejected",
            error = SyncError(if (s.entity == SyncEntity.MESSAGES) "session_deleted" else "INVALID_PAYLOAD", "测试")) }), pending)
        assertTrue(store.pendingBatch().isEmpty())
        assertEquals(1, db.settingsDao().syncRejectedCount())
        assertNull(db.chatDao().getStoredMessage("local-0", ""))
    }

    @Test fun `epoch变更先暂停绝不push同意才重新上传否则关闭保留本地`() = runBlocking {
        val db = device(); seed(db); enable(db); val api = FakeSyncApi(); run(db, api)
        api.clearCloud()
        db.chatDao().updateSessionTitle("local", "本地新标题")
        val pushes = api.pushes
        assertEquals(SyncRunResult.PausedForEpoch(1), run(db, api))
        assertEquals(pushes, api.pushes)
        assertFalse(SyncStateStore(db).isEnabled())
        assertEquals(1L, SyncStateStore(db).epochDecision())
        SyncStateStore(db).markAllLocalDataPending()
        run(db, api)
        assertEquals(2, api.rows.size)
        api.clearCloud(); run(db, api)
        SyncStateStore(db).rejectEpochUpload()
        assertEquals(SyncRunResult.Disabled, run(db, api))
        assertEquals(1, db.chatDao().getMessagesForSession("local").size)
    }

    @Test fun `默认关闭本地模式不请求不确认不清业务数据`() = runBlocking {
        val db = device(); seed(db); val api = FakeSyncApi()
        assertEquals(SyncRunResult.Disabled, run(db, api))
        enable(db)
        assertEquals(SyncRunResult.Disabled, run(db, api, allowed = false))
        assertEquals(0, api.pulls + api.pushes)
        assertEquals(2, RoomSyncStore(db).pendingBatch().size)
    }

    @Test fun `首次开启不上传旧历史换账号重置快照旧记录仍本地`() = runBlocking {
        val db = device(); seed(db); val state = SyncStateStore(db)
        state.prepareAccount("a", false)
        assertTrue(RoomSyncStore(db).pendingBatch().isEmpty())
        seed(db, "new"); assertEquals(2, RoomSyncStore(db).pendingBatch().size)
        state.saveCursor(99, 5); state.prepareAccount("b", false)
        assertEquals(SyncCursorState(0, -1), state.readCursor())
        assertTrue(RoomSyncStore(db).pendingBatch().isEmpty())
        assertEquals(2, db.chatDao().getAllSessions(false).size)
    }

    @Test fun `批量UTF8限制生图草稿密钥工具附件永不上传`() = runBlocking {
        val db = device(); val s = seed(db, count = 40)
        val image = s.copy(id = "image", uuid = UUID.randomUUID().toString(), isImageGeneration = true)
        db.chatDao().writeSession(image); db.chatDao().writeMessages(listOf(message("image-message", image)))
        db.chatDao().writeDrafts(listOf(MessageDraftEntity(message("draft", s, "草稿秘密"))))
        db.settingsDao().insertSetting(SystemSettingEntity("api_key", "本地密钥"))
        val msgs = db.chatDao().getPendingSyncMessages().map { it.copy(text = "字".repeat(15000), executionStatus = "工具秘密", imageUrls = listOf("https://private.example/picture")) }
        db.chatDao().writeMessages(msgs)
        val batch = RoomSyncStore(db).pendingBatch()
        val body = syncJson.encodeToString(PushRequest.serializer(), PushRequest(batch.map { it.change }))
        assertTrue(body.toByteArray().size <= 1024 * 1024)
        assertTrue(batch.size < 41)
        listOf("工具秘密", "private.example", "本地密钥", "草稿秘密", "image-message").forEach { assertFalse(body.contains(it)) }
        val value = SyncPayloadMapper.settingValue("""{"conversationIds":["${s.uuid}"],"apiKey":"秘密","attachments":["图片"]}""")
        assertEquals(setOf("conversationIds"), (value as JsonObject).keys)
    }

    @Test fun `单条超限停止重试并报告失败数量`() = runBlocking {
        val db = device(); val s = seed(db)
        db.chatDao().writeMessages(listOf(message("large", s, "字".repeat(30000))))
        val store = RoomSyncStore(db)
        assertEquals(2, store.pendingBatch().size)
        assertEquals(1, db.settingsDao().syncRejectedCount())
        assertEquals(SyncStatus.LOCAL_ONLY, db.chatDao().getStoredMessage("large", "")!!.syncStatus)
    }

    @Test fun `canonical采用但上传期间新设置仍待同步`() = runBlocking {
        val db = device(); val s = seed(db)
        val setting = UserSettingEntity("conversation_groups", "Work", "[\"${s.uuid}\"]", updatedAt = 100)
        db.settingsDao().upsertUserSettings(listOf(setting))
        val store = RoomSyncStore(db); val batch = store.pendingBatch(); val canonical = UUID.randomUUID().toString()
        val api = FakeSyncApi()
        val results = api.push(batch.map { it.change }).results.map { if (it.entity == "user_settings") it.copy(canonicalId = canonical) else it }
        db.settingsDao().upsertUserSettings(listOf(setting.copy(value = "[]", updatedAt = 101)))
        store.applyPush(PushResponse(results), batch)
        val local = db.settingsDao().getUserSetting("conversation_groups", "Work")!!
        assertEquals(canonical, local.uuid); assertEquals("[]", local.value)
        assertEquals(SyncStatus.PENDING, local.syncStatus)
    }

    @Test fun `失败整页不推进游标RetryAfter跨实例持久化`() = runBlocking {
        val db = device(); val store = RoomSyncStore(db); val s = session()
        val bad = PushChange("unknown", UUID.randomUUID().toString(), false)
        assertTrue(runCatching { store.applyPullPage(page(SyncPayloadMapper.session(s), bad)) }.isFailure)
        assertNull(db.chatDao().getSessionByUuid(s.uuid))
        assertEquals(0L, SyncStateStore(db).readCursor().cursor)
        SyncStateStore(db).deferRetry(120)
        assertTrue(SyncStateStore(db).retryAt() >= System.currentTimeMillis() + 119000)
    }

    @Test fun `两设备同逻辑设置采用canonical记忆最后修改赢且不回传`() = runBlocking {
        val a = device(); val b = device(); val s = seed(a); val api = FakeSyncApi()
        val memory = MemoryEntity("memory", "OTHER", "旧记忆", "MANUAL", s.id, "local-0", 10, 100)
        a.memoryDao().insert(memory)
        val setting = UserSettingEntity("conversation_groups", "Work", "[\"${s.uuid}\"]", updatedAt = 100)
        a.settingsDao().upsertUserSettings(listOf(setting)); enable(a); run(a, api)
        b.settingsDao().upsertUserSettings(listOf(setting.copy(uuid = UUID.randomUUID().toString(), value = "[]", updatedAt = 101)))
        enable(b); run(b, api); run(a, api)
        assertEquals(setting.uuid, b.settingsDao().getUserSetting("conversation_groups", "Work")!!.uuid)
        assertEquals("[]", a.settingsDao().getUserSetting("conversation_groups", "Work")!!.value)
        val remoteMemory = b.memoryDao().getByUuid(memory.uuid)!!
        b.memoryDao().update(remoteMemory.copy(content = "新记忆", updatedAt = 102, syncUpdatedAt = 102, syncStatus = SyncStatus.PENDING))
        run(b, api); run(a, api)
        assertEquals("新记忆", a.memoryDao().getByUuid(memory.uuid)!!.content)
        assertTrue(RoomSyncStore(a).pendingBatch().isEmpty()); assertTrue(RoomSyncStore(b).pendingBatch().isEmpty())
    }

    @Test fun `记忆先于来源到达不丢UUID未来修改时间裁剪到服务器时间`() = runBlocking {
        val db = device(); val s = session(); val msg = message("m", s)
        val memory = MemoryEntity("memory", "OTHER", "长期记忆", "MANUAL", null, null, 10, Long.MAX_VALUE)
        val change = SyncPayloadMapper.memory(memory.copy(syncUpdatedAt = 9007199254740991L), s.uuid, msg.uuid)
        RoomSyncStore(db).applyPullPage(page(change))
        val received = db.memoryDao().getByUuid(memory.uuid)!!
        assertEquals(java.time.Instant.parse("2026-10-14T18:30:01Z").toEpochMilli(), received.clientModifiedAt)
        db.memoryDao().update(received.copy(content = "修改", syncStatus = SyncStatus.PENDING))
        val payload = RoomSyncStore(db).pendingBatch().single().change.payload
        assertEquals(s.uuid, payload["sourceConversationId"]!!.jsonPrimitive.content)
        assertEquals(msg.uuid, payload["sourceMessageId"]!!.jsonPrimitive.content)
    }

    @Test fun `WorkRequest联网约束退避且LocalOnly不创建任何同步工作`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val request = com.android.everytalk.service.SyncScheduler.immediateWork(120000)
        assertEquals(androidx.work.NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(androidx.work.BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(120000L, request.workSpec.initialDelay)
        runCatching { androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build()) }
        com.android.everytalk.service.SyncScheduler.scheduleIfEnabled(context)
        val manager = androidx.work.WorkManager.getInstance(context)
        assertTrue(manager.getWorkInfosForUniqueWork("everytalk-cloud-sync").get().isEmpty())
        assertTrue(manager.getWorkInfosForUniqueWork("everytalk-cloud-sync-periodic").get().isEmpty())
    }

    @Test fun `单运行锁并发引擎不会重复push`() = runBlocking {
        val db = device(); seed(db); enable(db); val api = FakeSyncApi()
        kotlinx.coroutines.coroutineScope {
            val a = async { run(db, api) }
            val b = async { run(db, api) }
            a.await(); b.await()
        }
        assertEquals(1, api.pushes)
        assertTrue(RoomSyncStore(db).pendingBatch().isEmpty())
    }
}
