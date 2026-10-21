package com.android.everytalk.data.sync

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.auth.SyncAccountBindingStore
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
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
    private class Binding(var accountId: String? = null) : SyncAccountBindingStore {
        override suspend fun readBoundAccountId(): String? = accountId
        override suspend fun bindTo(accountId: String) { this.accountId = accountId }
        override suspend fun clearBinding() { accountId = null }
    }
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
    private suspend fun run(db: AppDatabase, api: SyncApi, allowed: Boolean = true) =
        SyncEngine(api, RoomSyncStore(db), SyncStateStore(db), { allowed }).runOnce()
    private fun page(vararg changes: PushChange, since: Long = 0) = PullResponse(changes.mapIndexed { index, change ->
        SyncRecord(change.entity, change.id, change.payload, updatedAt = "2026-10-14T18:30:01Z", deleted = change.deleted, serverSeq = since + index + 1)
    }, since + changes.size, false, 0)

    @Test fun `同毫秒问题和回复在两台反向写入的设备上始终按问题回复显示`() = runBlocking {
        val a = device(); val b = device(); val api = FakeSyncApi(); val s = seed(a, count = 0)
        val question = Message(id = "user_question", text = "参考这个项目", sender = Sender.User, timestamp = 100)
            .toEntity(s.id).copy(syncStatus = SyncStatus.PENDING)
        val answer = Message(id = "ai_response", text = "完整回复没有丢失", sender = Sender.AI, timestamp = 100)
            .toEntity(s.id).copy(syncStatus = SyncStatus.PENDING)
        a.chatDao().writeMessages(listOf(question, answer))
        val cloudSession = s.copy(id = "cloud:${s.uuid}", syncStatus = SyncStatus.SYNCED)
        b.chatDao().writeSession(cloudSession)
        // 模拟旧手机已经先收到回复、后收到问题；修复不能依靠清库或重新写入消息。
        b.chatDao().writeMessages(listOf(answer, question).map {
            it.copy(id = "cloud:${it.uuid}", sessionId = cloudSession.id, syncStatus = SyncStatus.SYNCED, everSynced = true)
        })
        run(a, api); run(b, api)
        for ((db, localId) in listOf(a to s.id, b to cloudSession.id)) {
            val dao = db.chatDao()
            assertEquals(listOf(Sender.User, Sender.AI), dao.getMessagesForSession(localId).map { it.sender })
            assertEquals(listOf("User", "AI"), dao.getRawMessagesForMode(false).map { it.sender })
            assertEquals(listOf("User", "AI"), dao.getLightweightMessagesForMode(false).map { it.sender })
            val source = com.android.everytalk.data.database.RoomDataSource(ApplicationProvider.getApplicationContext(), db)
            assertEquals("完整回复没有丢失", source.loadHistorySession(localId)!!.last().text)
            assertEquals(2, dao.getMessagesForSession(localId).size)
        }
        assertTrue(RoomSyncStore(b).pendingBatch().isEmpty())
    }

    @Test fun `补齐旧版云端空白消息但不覆盖本机原件和已定稿正文`() = runBlocking {
        val db = device(); val s = seed(db, count = 0)
        val blank = Message(id = "cloud:${UUID.randomUUID()}", text = "", sender = Sender.AI).toEntity(s.id)
            .copy(syncStatus = SyncStatus.SYNCED, everSynced = true)
        val original = blank.copy(id = "local-original", uuid = UUID.randomUUID().toString())
        db.chatDao().writeMessages(listOf(blank, original))
        val store = RoomSyncStore(db)
        val state = SyncStateStore(db)
        state.saveCursor(99, 0)
        db.settingsDao().insertSetting(SystemSettingEntity("sync_projection_recovery_version", "3"))
        store.prepareForSync()
        assertEquals(0L, state.readCursor().cursor)
        fun change(id: String, text: String) = PushChange("messages", id, deleted = false, payload = buildJsonObject {
            put("sessionId", s.uuid); put("sender", "AI"); put("text", text)
        })
        store.applyPullPage(page(change(blank.uuid, "服务器完整正文"), change(original.uuid, "不得覆盖")))
        assertEquals("服务器完整正文", db.chatDao().getMessageByUuid(blank.uuid)!!.text)
        assertEquals("", db.chatDao().getMessageByUuid(original.uuid)!!.text)
        store.applyPullPage(page(change(blank.uuid, "不同正文"), since = 2))
        assertEquals("服务器完整正文", db.chatDao().getMessageByUuid(blank.uuid)!!.text)
        val cursor = state.readCursor()
        store.prepareForSync()
        assertEquals(cursor, state.readCursor())
        assertTrue(store.pendingBatch().none { it.change.entity == "messages" })
    }

    @Test fun `升级重放补齐游标以前缺行且保留主动本地数据不回传`() = runBlocking {
        val a = device(); val b = device(); val api = FakeSyncApi(); val s = seed(a, count = 12)
        run(a, api); run(b, api)
        val messages = b.chatDao().getMessagesForSession("cloud:${s.uuid}")
        messages.take(9).forEach { b.chatDao().discardMessageByUuid(it.uuid) }
        val local = message("keep-local", s).copy(sessionId = "cloud:${s.uuid}", syncStatus = SyncStatus.LOCAL_ONLY)
        b.chatDao().writeMessages(listOf(local))
        val pushes = api.pushes
        b.settingsDao().insertSetting(SystemSettingEntity("sync_projection_recovery_version", "4"))
        run(b, api)
        assertEquals(messages.map { it.uuid }.toSet() + local.uuid,
            b.chatDao().getMessagesForSession("cloud:${s.uuid}").map { it.uuid }.toSet())
        assertEquals(SyncStatus.LOCAL_ONLY, b.chatDao().getMessageByUuid(local.uuid)!!.syncStatus)
        assertEquals(pushes, api.pushes)
    }

    @Test fun `待上传读取忽略大型本地JSON且保留全部同步字段`() = runBlocking {
        val db = device(); val s = seed(db, count = 0)
        val item = message("large-local", s).copy(everSynced = true,
            executionStatus = "local-only", executionFinishedAt = 1234)
        db.chatDao().writeMessages(listOf(item))
        // 本地非同步字段超过常见 CursorWindow 单行容量，白名单查询不应读取这些列。
        db.openHelper.writableDatabase.execSQL("UPDATE messages SET parts = ?, executionSteps = ? WHERE uuid = ?",
            arrayOf("x".repeat(3 * 1024 * 1024), "y".repeat(3 * 1024 * 1024), item.uuid))
        val projected = db.chatDao().getPendingSyncMessages().single()
        assertEquals(SyncPayloadMapper.message(item, s.uuid), SyncPayloadMapper.message(projected, s.uuid))
        assertTrue(projected.everSynced)
        assertEquals(1234L, projected.executionFinishedAt)
        assertTrue(projected.parts.isEmpty()); assertTrue(projected.executionSteps.isEmpty())
    }

    @Test fun `远端消息插入被主键冲突忽略时整页回滚且不推进游标`() = runBlocking {
        val db = device(); val s = seed(db, count = 0)
        val remoteUuid = UUID.randomUUID().toString()
        val existing = message("cloud:$remoteUuid", s).copy(uuid = UUID.randomUUID().toString())
        db.chatDao().writeMessages(listOf(existing))
        val state = SyncStateStore(db)
        state.saveCursor(100, 0)
        val change = SyncPayloadMapper.message(existing.copy(uuid = remoteUuid), s.uuid)
        val error = runCatching { RoomSyncStore(db).applyPullPage(page(change, since = 100)) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("SYNC_MESSAGE_IDENTITY_CONFLICT", error?.message)
        assertEquals(100L, state.readCursor().cursor)
        assertNull(db.chatDao().getSyncMessageByUuid(remoteUuid))
        assertEquals(existing.uuid, db.chatDao().getStoredMessage(existing.id, "")!!.uuid)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `两设备修改标签后刷新当前输入框且取消立即生效`() = runBlocking {
        val a = device(); val b = device(); val api = FakeSyncApi(); val s = seed(a)
        val source = com.android.everytalk.data.database.RoomDataSource(ApplicationProvider.getApplicationContext(), a)
        run(a, api); run(b, api)
        val state = com.android.everytalk.statecontroller.ViewModelStateHolder()
        val restoredId = b.chatDao().getSessionByUuid(s.uuid)!!.id
        state.setApiHandler(io.mockk.mockk(relaxed = true))
        val restoredSource = com.android.everytalk.data.database.RoomDataSource(ApplicationProvider.getApplicationContext(), b)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher())
        try {
            val persistence = io.mockk.spyk(com.android.everytalk.ui.screens.viewmodel.DataPersistenceManager(
                ApplicationProvider.getApplicationContext(), state, scope, io.mockk.mockk(relaxed = true)))
            io.mockk.every { persistence.roomDataSource } returns com.android.everytalk.data.database.RoomDataSource(ApplicationProvider.getApplicationContext(), b)
            for (enabled in listOf(true, false)) {
                source.setSetting("conversation_function_toggle_states", buildJsonObject {
                    put(s.id, buildJsonObject {
                        put("webSearchEnabled", enabled); put("mcpEnabled", enabled)
                        put("agentEnabled", enabled); put("codeExecutionEnabled", enabled)
                    })
                }.toString())
                run(a, api); run(b, api)
                if (enabled) {
                    persistence.refreshSyncedConfigurations { true }
                    assertFalse("文字同步不能把标签套到当前生图页", state._isMcpEnabledForNextRequest.value)
                }
                persistence.refreshSyncedConfigurations { false }
                // 必须经过实际的历史打开入口，不能预先手动填入正确会话 ID 掩盖消息/会话身份混淆。
                state._historicalConversations.value = listOf(restoredSource.loadHistorySession(restoredId)!!)
                com.android.everytalk.statecontroller.SimpleModeManager(state, io.mockk.mockk(relaxed = true), scope,
                    loadHistorySession = restoredSource::loadHistorySession).loadTextHistory(0)
                assertEquals(restoredId, state._currentConversationId.value)
                assertEquals(enabled, state._isMcpEnabledForNextRequest.value)
                assertEquals(enabled, state._isAgentEnabled.value)
                assertEquals(enabled, state._isWebSearchEnabled.value)
                assertEquals(enabled, state._isCodeExecutionEnabled.value)
                assertTrue(RoomSyncStore(b).pendingBatch().isEmpty())
            }
            // 兼容旧版省略默认 false 的存储格式；空对象也必须清掉之前已同步的开启状态。
            source.setSetting("conversation_function_toggle_states", """{"${s.id}":{"mcpEnabled":true}}""")
            run(a, api); run(b, api)
            persistence.refreshSyncedConfigurations { false }
            assertTrue(state._isMcpEnabledForNextRequest.value)
            source.setSetting("conversation_function_toggle_states", """{"${s.id}":{}}""")
            run(a, api); run(b, api)
            persistence.refreshSyncedConfigurations { false }
            assertFalse(state._isMcpEnabledForNextRequest.value)
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            kotlinx.coroutines.Dispatchers.resetMain()
            io.mockk.unmockkAll()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `界面读取期间用户改变模型和标签不能被旧快照覆盖`() = runBlocking {
        val state = com.android.everytalk.statecontroller.ViewModelStateHolder()
        state.setCurrentConversationId("active")
        val config = com.android.everytalk.data.DataClass.ApiConfig("https://example.org/v1", "", "old", "custom", name = "旧模型")
        state._apiConfigs.value = listOf(config)
        state._selectedApiConfig.value = config
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher())
        try {
            val persistence = io.mockk.spyk(com.android.everytalk.ui.screens.viewmodel.DataPersistenceManager(
                ApplicationProvider.getApplicationContext(), state, scope, io.mockk.mockk(relaxed = true)))
            val source = io.mockk.mockk<com.android.everytalk.data.database.RoomDataSource>(relaxed = true)
            io.mockk.every { persistence.roomDataSource } returns source
            io.mockk.coEvery { source.loadApiConfigs() } coAnswers {
                // 模拟 IO 读取悬挂期间用户在主线程改了选择；不能再写回 IO 开始前的状态。
                state.conversationApiConfigIds.value = mapOf("active" to "new-model")
                state._selectedApiConfig.value = config.copy(id = "new-model", model = "new")
                state.updateCurrentConversationFunctionToggleState { it.copy(mcpEnabled = true) }
                state.applyCurrentConversationFunctionToggleState()
                listOf(config)
            }
            persistence.refreshSyncedConfigurations { false }
            assertEquals("new-model", state._selectedApiConfig.value!!.id)
            assertEquals("new-model", state.conversationApiConfigIds.value["active"])
            assertTrue(state._isMcpEnabledForNextRequest.value)
            assertTrue(state.getCurrentConversationFunctionToggleState().mcpEnabled)
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            kotlinx.coroutines.Dispatchers.resetMain()
            io.mockk.unmockkAll()
        }
    }

    @Test fun `Skill启停在操作时记录版本删除和再次安装跨设备不复活旧值`() = runBlocking {
        val a = device(); val b = device(); val api = FakeSyncApi()
        val skill = SkillInstallationEntity("remote:owner/repo#demo", "Demo", "用途说明", "REMOTE", "owner/repo", "skills/demo", "a".repeat(64),
            true, "AUTO", null, 100, 100, null, 0)
        a.skillDao().upsertInstallation(skill)
        run(a, api); run(b, api)
        b.skillDao().setEnabled(skill.skillId, false)
        val snapshot = b.settingsDao().getUserSetting("skills", skill.skillId)!!
        assertEquals(SyncStatus.PENDING, snapshot.syncStatus)
        assertFalse(syncJson.parseToJsonElement(snapshot.value).jsonObject["enabled"]!!.jsonPrimitive.boolean)
        run(b, api); run(a, api)
        assertFalse(a.skillDao().getInstallation(skill.skillId)!!.enabled)
        a.skillDao().deletePackageLocally(skill.packageId)
        assertTrue(a.settingsDao().getUserSetting("skills", skill.skillId)!!.deleted)
        run(a, api); run(b, api)
        assertNull(b.skillDao().getInstallation(skill.skillId))
        val deletedVersion = a.settingsDao().getUserSetting("skills", skill.skillId)!!.updatedAt
        a.skillDao().upsertInstallation(skill.copy(updatedAt = deletedVersion + 1))
        a.skillDao().captureSyncSetting(skill.skillId)
        run(a, api); run(b, api)
        assertTrue(b.skillDao().getInstallation(skill.skillId)!!.enabled)
        assertTrue(RoomSyncStore(a).pendingBatch().isEmpty())
        assertTrue(RoomSyncStore(b).pendingBatch().isEmpty())
    }

    @Test fun `MCP两设备改名停用删除后不会保留旧配置或回传`() = runBlocking {
        val a = device(); val b = device(); val api = FakeSyncApi()
        a.mcpConfigDao().insertConfig(McpServerConfigEntity("mcp-demo", "旧名称", "https://mcp.example/mcp", "HTTP"))
        run(a, api); run(b, api)
        val changed = b.mcpConfigDao().getConfigById("mcp-demo")!!.copy(name = "新名称", enabled = false)
        b.mcpConfigDao().updateConfig(changed)
        SyncSettingsProjector(b).refresh()
        run(b, api); run(a, api)
        assertEquals(changed, a.mcpConfigDao().getConfigById("mcp-demo"))
        b.mcpConfigDao().deleteConfigById("mcp-demo")
        SyncSettingsProjector(b).markDeleted("mcp_servers", syncStableSettingKey("mcp_servers", "mcp-demo"))
        run(b, api); run(a, api)
        assertNull(a.mcpConfigDao().getConfigById("mcp-demo"))
        assertTrue(RoomSyncStore(a).pendingBatch().isEmpty())
    }

    @Test fun `两设备保留各自历史且拉取不产生回传`() = runBlocking {
        val a = device(); val b = device(); val api = FakeSyncApi()
        seed(a, "a"); seed(b, "b")
        assertTrue(SyncStateStore(a).prepareAutomaticAccount("same-account", Binding()))
        assertTrue(SyncStateStore(b).prepareAutomaticAccount("same-account", Binding()))
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
        val db = device(); seed(db, count = 1001)
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
        assertEquals(3, api.pushes)
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
        run(db, api)
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
        val db = device(); seed(db); val api = FakeSyncApi(); run(db, api)
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

    @Test fun `本地模式不请求不确认不清业务数据`() = runBlocking {
        val db = device(); seed(db); val api = FakeSyncApi()
        // AuthState.LocalOnly 由 Worker 通过 canSync=false 传入；数据库不再保存“同步开关”。
        assertEquals(SyncRunResult.Disabled, run(db, api, allowed = false))
        assertEquals(0, api.pulls + api.pushes)
        assertEquals(2, RoomSyncStore(db).pendingBatch().size)
    }

    @Test fun `游客转账号自动接管旧本地历史无需开启开关`() = runBlocking {
        val db = device(); seed(db); val state = SyncStateStore(db); val binding = Binding()
        db.chatDao().keepAllTextLocal()
        assertTrue(RoomSyncStore(db).pendingBatch().isEmpty())
        assertTrue(state.prepareAutomaticAccount("account-a", binding))
        assertEquals("account-a", binding.accountId)
        assertTrue(state.isEnabled())
        assertEquals(2, RoomSyncStore(db).pendingBatch().size)
        val api = FakeSyncApi(); run(db, api)
        assertEquals(2, api.rows.size)
        assertTrue(RoomSyncStore(db).pendingBatch().isEmpty())
    }

    @Test fun `重启和资料刷新保留游标且不重复上传拒绝记录`() = runBlocking {
        val db = device(); val session = seed(db); val binding = Binding()
        val state = SyncStateStore(db)
        state.prepareAutomaticAccount("a", binding)
        val api = FakeSyncApi(); run(db, api)
        val cursor = state.readCursor()
        db.chatDao().markSessionRejectedIfVersion(session.uuid, session.updatedAt)
        db.settingsDao().insertSetting(SystemSettingEntity("sync_rejected:sessions:${session.uuid}", "INVALID_PAYLOAD"))
        assertTrue(SyncStateStore(db).prepareAutomaticAccount("a", binding))
        assertEquals(cursor, state.readCursor())
        assertEquals(1, db.settingsDao().syncRejectedCount())
        assertTrue(RoomSyncStore(db).pendingBatch().isEmpty())
        val pushes = api.pushes; run(db, api)
        assertEquals(pushes, api.pushes)
    }

    @Test fun `退出后游客不发请求重新登录同账号同步游客期间新数据`() = runBlocking {
        val db = device(); seed(db); val state = SyncStateStore(db); val binding = Binding()
        state.prepareAutomaticAccount("a", binding)
        val api = FakeSyncApi(); run(db, api)
        seed(db, "guest-new")
        val requests = api.pushes + api.pulls
        assertEquals(SyncRunResult.Disabled, run(db, api, allowed = false))
        assertEquals(requests, api.pushes + api.pulls)
        assertEquals(2, db.chatDao().getAllSessions(false).size)
        assertTrue(SyncStateStore(db).prepareAutomaticAccount("a", binding))
        assertEquals(2, RoomSyncStore(db).pendingBatch().size)
        run(db, api)
        assertEquals(4, api.rows.size)
    }

    @Test fun `登录另一个账号等待三选一不能自动改绑定或上传`() = runBlocking {
        val db = device(); seed(db); val state = SyncStateStore(db); val binding = Binding()
        state.prepareAutomaticAccount("a", binding)
        assertFalse(state.prepareAutomaticAccount("b", binding))
        assertEquals("a", binding.accountId)
        assertEquals("a", db.settingsDao().getValue(SyncStateStore.KEY_ACCOUNT))
        // 明确选择“上传到当前账号”后，后续重启不再重复询问或重置队列。
        state.prepareAccount("b", upload = true); binding.bindTo("b")
        assertTrue(state.prepareAutomaticAccount("b", binding))
        assertEquals("b", binding.accountId)
    }

    @Test fun `登录准备途中已退出不改绑定或启用同步`() = runBlocking {
        val db = device(); seed(db); val state = SyncStateStore(db); val binding = Binding()
        assertFalse(state.prepareAutomaticAccount("a", binding) { false })
        assertNull(binding.accountId)
        assertNull(db.settingsDao().getValue(SyncStateStore.KEY_ACCOUNT))
    }

    @Test fun `删除云端或epoch拒绝上传后重启登录不能重新上传`() = runBlocking {
        val db = device(); seed(db); val state = SyncStateStore(db); val binding = Binding()
        state.prepareAutomaticAccount("a", binding)
        val api = FakeSyncApi(); run(db, api)
        state.pauseAfterCloudReset(); api.clearCloud()
        assertFalse(SyncStateStore(db).prepareAutomaticAccount("a", binding))
        assertEquals(SyncRunResult.Disabled, run(db, api))
        assertTrue(api.rows.isEmpty())
        assertEquals(1, db.chatDao().getMessagesForSession("local").size)
        state.requireEpochDecision(api.epoch); state.rejectEpochUpload()
        assertNull(state.epochDecision())
        assertFalse(SyncStateStore(db).prepareAutomaticAccount("a", binding))
        // 只有再次明确选择上传，才重新协商 epoch 并上传本地副本。
        state.resumeAfterCloudReset()
        assertFalse(state.isPausedAfterCloudReset())
        assertEquals(SyncRunResult.Completed, run(db, api))
        assertEquals(2, api.rows.size)
    }

    @Test fun `升级旧版未开启同步的账号自动接管一次历史`() = runBlocking {
        val db = device(); seed(db); val state = SyncStateStore(db); val binding = Binding("a")
        db.chatDao().keepAllTextLocal()
        db.settingsDao().insertSetting(SystemSettingEntity(SyncStateStore.KEY_ACCOUNT, "a"))
        assertTrue(state.prepareAutomaticAccount("a", binding))
        val api = FakeSyncApi(); run(db, api)
        assertTrue(SyncStateStore(db).prepareAutomaticAccount("a", binding))
        assertTrue(RoomSyncStore(db).pendingBatch().isEmpty())
        assertEquals(2, api.rows.size)
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

    @Test fun `旧版思考过程超限失败在修复后重新入队且保留本地完整内容`() = runBlocking {
        val db = device(); val s = seed(db); val state = SyncStateStore(db); val binding = Binding("same-account")
        db.settingsDao().insertSetting(SystemSettingEntity(SyncStateStore.KEY_ACCOUNT, "same-account"))
        db.settingsDao().insertSetting(SystemSettingEntity(SyncStateStore.KEY_AUTOMATIC_ACCOUNT, "same-account"))
        val large = message("large-reasoning", s, "正文").copy(
            reasoning = "思考".repeat(40000), syncStatus = SyncStatus.PENDING,
        )
        db.chatDao().writeMessages(listOf(large))
        db.chatDao().markMessageRejectedIfVersion(large.uuid, large.updatedAt)
        db.settingsDao().insertSetting(SystemSettingEntity(
            "sync_rejected:messages:${large.uuid}", "MESSAGE_PAYLOAD_TOO_LARGE",
        ))
        state.prepareAutomaticAccount("same-account", binding)
        val change = RoomSyncStore(db).pendingBatch().first { it.change.id == large.uuid }.change
        assertTrue(change.payload.toString().toByteArray().size <= 65536)
        assertEquals("正文", db.chatDao().getStoredMessage("large-reasoning", "")!!.text)
        assertEquals("思考".repeat(40000), db.chatDao().getStoredMessage("large-reasoning", "")!!.reasoning)
        assertEquals(0, db.settingsDao().syncRejectedCount())
        db.chatDao().markMessageRejectedIfVersion(large.uuid, large.updatedAt)
        db.settingsDao().insertSetting(SystemSettingEntity("sync_rejected:messages:${large.uuid}", "MESSAGE_PAYLOAD_TOO_LARGE"))
        state.prepareAutomaticAccount("same-account", binding)
        assertEquals(1, db.settingsDao().syncRejectedCount())
        assertEquals(SyncStatus.LOCAL_ONLY, db.chatDao().getMessageByUuid(large.uuid)!!.syncStatus)
    }

    @Test fun `消息接近服务器上限时本地先删可选大字段而不是发送后失败`() = runBlocking {
        val db = device(); val s = seed(db)
        val large = message("near-limit", s, "正文".repeat(1800)).copy(
            reasoning = "思考".repeat(30000), syncStatus = SyncStatus.PENDING,
        )
        db.chatDao().writeMessages(listOf(large))
        val change = RoomSyncStore(db).pendingBatch().single { it.change.id == large.uuid }.change
        assertTrue(change.payload.toString().toByteArray().size <= 60 * 1024)
        assertFalse(change.payload.containsKey("reasoning"))
        assertEquals(0, db.settingsDao().syncRejectedCount())
    }

    @Test fun `旧三条MCP和九条工作区失败修复后全量重传也不再发送非法key`() = runBlocking {
        val db = device(); val settings = db.settingsDao()
        val legacyMcp = listOf("mcp-cloudflare", "mcp-github", "mcp-gmail").map { id ->
            db.mcpConfigDao().insertConfig(McpServerConfigEntity(id, id, "https://mcp.example/mcp", "HTTP"))
            UserSettingEntity("mcp_servers", id, """{"name":"$id","endpoint":"https://mcp.example/mcp","transport":"HTTP","enabled":true}""", updatedAt = 100, syncStatus = SyncStatus.LOCAL_ONLY)
        }
        val legacyWorkspaces = (1..9).map { index ->
            UserSettingEntity("computer_workspaces", "ws_$index", """{"computerId":"00000000-0000-4000-8000-000000000001","remotePath":"/private/device/path","containerName":"device-container","runMode":"DIRECT"}""", updatedAt = 100, syncStatus = SyncStatus.LOCAL_ONLY)
        }
        val legacy = legacyMcp + legacyWorkspaces
        settings.upsertUserSettings(legacy)
        legacy.forEach { settings.insertSetting(SystemSettingEntity("sync_rejected:user_settings:${it.uuid}", "INVALID_USER_SETTING")) }
        val api = FakeSyncApi()
        // 先证明假服务器真的会拒绝原始 12 条记录，而非无条件接受测试数据。
        assertTrue(api.push(legacy.map(SyncPayloadMapper::userSetting)).results.all { it.error?.code == "INVALID_USER_SETTING" })
        assertEquals(SyncRunResult.Completed, run(db, api))
        assertEquals(0, settings.syncRejectedCount())
        val fixed = settings.getAllUserSettings()
        assertEquals(12, fixed.size)
        assertEquals(legacy.map { it.uuid }.toSet(), fixed.map { it.uuid }.toSet())
        assertTrue(fixed.all { isCanonicalUuid(it.key) && it.syncStatus == SyncStatus.SYNCED })
        assertTrue(fixed.none { it.value.contains("/private/device/path") || it.value.contains("device-container") })
        assertEquals(3, db.mcpConfigDao().getAllSnapshot().size)
        SyncStateStore(db).markAllLocalDataPending()
        run(db, api)
        assertEquals(12, api.rows.size)
        assertEquals(0, settings.syncRejectedCount())
        assertTrue(RoomSyncStore(db).pendingBatch().isEmpty())
        val pushes = api.pushes
        run(db, api)
        assertEquals(pushes, api.pushes)
    }

    @Test fun `工作区真实投影只改同步身份保留本机路径且引用会话UUID`() = runBlocking {
        val db = device(); val s = seed(db)
        val computer = com.android.everytalk.data.computer.Computer(
            id = UUID.randomUUID().toString(), displayName = "电脑", host = "example.org", port = 22, username = "user",
            authKind = com.android.everytalk.data.computer.ComputerAuthKind.PASSWORD,
            runMode = com.android.everytalk.data.computer.ComputerRunMode.DIRECT,
        ).toEntity(syncJson)
        db.computerDao().upsertComputer(computer)
        val workspace = ComputerWorkspaceEntity("ws_local", computer.id, s.id, "DIRECT", "/private/work", "local-container", null, "READY", 1, 1)
        db.computerDao().upsertWorkspace(workspace)
        val change = RoomSyncStore(db).pendingBatch().single { it.change.payload["kind"]?.jsonPrimitive?.content == "computer_workspaces" }.change
        assertTrue(isCanonicalUuid(change.payload.getValue("key").jsonPrimitive.content))
        val value = change.payload.getValue("value").jsonObject
        assertEquals(s.uuid, value.getValue("sessionId").jsonPrimitive.content)
        assertFalse(value.containsKey("remotePath")); assertFalse(value.containsKey("containerName"))
        assertEquals(workspace, db.computerDao().getWorkspaceById(workspace.id))
    }

    @Test fun `两设备MCP及模型配置拉取即生效且属性排序和等价数字不会回传`() = runBlocking {
        val a = device(); val b = device(); val api = FakeSyncApi()
        a.mcpConfigDao().insertConfig(McpServerConfigEntity("mcp-github", "GitHub", "https://mcp.example/mcp", "HTTP"))
        val modelId = UUID.randomUUID().toString()
        val config = com.android.everytalk.data.DataClass.ApiConfig(
            id = modelId, name = "模型", address = "https://model.example/v1", key = "本机密钥", model = "model", provider = "provider",
            channel = "Gemini", temperature = 0f,
            modelParameters = com.android.everytalk.data.DataClass.ModelParameters(
                apiProtocolOverride = com.android.everytalk.data.DataClass.ModelParameterProtocol.GEMINI,
            ),
        ).toEntity()
        a.apiConfigDao().insertConfigs(listOf(config))
        run(a, api); run(b, api)
        assertEquals("https://mcp.example/mcp", b.mcpConfigDao().getConfigById("mcp-github")!!.url)
        assertEquals(1, b.mcpConfigDao().getAllSnapshot().size)
        val received = b.apiConfigDao().getConfigs(false).single()
        assertEquals(modelId, received.id); assertEquals("", received.key)
        assertEquals(config.modelParameters.apiProtocolOverride, received.modelParameters.apiProtocolOverride)
        val pushes = api.pushes
        repeat(3) { run(a, api); run(b, api) }
        assertEquals(pushes, api.pushes)
        assertTrue(RoomSyncStore(a).pendingBatch().isEmpty()); assertTrue(RoomSyncStore(b).pendingBatch().isEmpty())
        assertEquals("本机密钥", a.apiConfigDao().getConfigs(false).single().key)
    }

    @Test fun `canonical采用但上传期间新设置仍待同步`() = runBlocking {
        val db = device(); val s = seed(db)
        val setting = UserSettingEntity("group_members", "V29yaw.${s.uuid}", """{"groupName":"Work","conversationId":"${s.uuid}"}""", updatedAt = 100)
        db.settingsDao().upsertUserSettings(listOf(setting))
        val store = RoomSyncStore(db); val batch = store.pendingBatch(); val canonical = UUID.randomUUID().toString()
        val api = FakeSyncApi()
        val results = api.push(batch.map { it.change }).results.map { if (it.entity == "user_settings") it.copy(canonicalId = canonical) else it }
        db.settingsDao().upsertUserSettings(listOf(setting.copy(deleted = true, updatedAt = 101)))
        store.applyPush(PushResponse(results), batch)
        val local = db.settingsDao().getUserSetting(setting.kind, setting.key)!!
        assertEquals(canonical, local.uuid); assertTrue(local.deleted)
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
        val setting = UserSettingEntity("pinned_items", s.uuid, "{}", updatedAt = 100)
        a.settingsDao().upsertUserSettings(listOf(setting)); run(a, api)
        b.settingsDao().upsertUserSettings(listOf(setting.copy(uuid = UUID.randomUUID().toString(), updatedAt = 101)))
        run(b, api); run(a, api)
        val receivedGroup = b.settingsDao().getUserSetting(setting.kind, setting.key)
        requireNotNull(receivedGroup) { "rows=" + b.settingsDao().getAllUserSettings() }
        val serverCanonical = api.rows.values.first { it.entity == "user_settings" && it.payload["kind"]?.jsonPrimitive?.content == "pinned_items" }.id
        assertEquals(serverCanonical, receivedGroup.uuid)
        val localGroup = a.settingsDao().getUserSetting(setting.kind, setting.key)
        requireNotNull(localGroup) { "a-rows=" + a.settingsDao().getAllUserSettings() }
        assertEquals(setting.value, localGroup.value)
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

    @Test fun `文件限流不阻塞普通配置和文字且资源变更下一轮继续同步`() = runBlocking {
        val a = device(); val b = device(); val session = seed(a); val api = FakeSyncApi()
        val setting = UserSettingEntity("memory_switches", "default", """{"memoryEnabled":true,"autoCaptureEnabled":false}""", updatedAt = 100)
        a.settingsDao().upsertUserSettings(listOf(setting))
        try {
            SyncEngine(api, RoomSyncStore(a), SyncStateStore(a), beforeComplete = {
                assertNotNull(api.rows["sessions" to session.uuid])
                assertTrue(api.rows.values.any { it.entity == "messages" })
                assertTrue(api.rows.values.any { it.entity == "user_settings" && it.payload["kind"]?.jsonPrimitive?.content == "memory_switches" })
                throw SyncHttpException(429, 60, "RATE_LIMITED", "模拟文件预约限流")
            }).runOnce()
            fail("应保留文件限流供 Worker 调度续传")
        } catch (error: SyncHttpException) { assertEquals(429, error.statusCode) }
        run(b, api)
        assertNotNull(b.chatDao().getSessionByUuid(session.uuid))
        assertEquals(1, b.chatDao().getMessagesForSession(b.chatDao().getSessionByUuid(session.uuid)!!.id).size)
        assertTrue(RoomSyncStore(a).pendingBatch().isEmpty())
        val result = SyncEngine(api, RoomSyncStore(a), SyncStateStore(a), beforeComplete = {
            a.settingsDao().insertSetting(SystemSettingEntity("memory_enabled", "false"))
        }).runOnce()
        assertEquals(SyncRunResult.MoreWork, result)
        assertEquals(SyncRunResult.Completed, run(a, api))
        run(b, api)
        assertEquals("false", b.settingsDao().getValue("memory_enabled"))
    }

    @Test fun `限流续传保留截止时间且创建新尝试不累计指数退避`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        runCatching { androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build()) }
        val manager = androidx.work.WorkManager.getInstance(context)
        val state = SyncStateStore(device())
        state.deferRetry(120)
        val deadline = state.retryAt()
        com.android.everytalk.service.SyncScheduler.continueResources(context, deadline - System.currentTimeMillis()).result.get()
        val first = manager.getWorkInfosForUniqueWork("everytalk-cloud-sync").get().single()
        assertEquals(0, first.runAttemptCount)
        assertTrue(first.nextScheduleTimeMillis >= deadline - 1000)
        assertEquals(androidx.work.NetworkType.CONNECTED, first.constraints.requiredNetworkType)
        com.android.everytalk.service.SyncScheduler.continueResources(context, 120000).result.get()
        val jobs = manager.getWorkInfosForUniqueWork("everytalk-cloud-sync").get()
        assertEquals(2, jobs.size)
        assertTrue(jobs.all { it.runAttemptCount == 0 })
        assertEquals(deadline, state.retryAt())
        manager.cancelUniqueWork("everytalk-cloud-sync").result.get()
        Unit
    }

    @Test fun `保存分组置顶不能删除其他配置且两设备取消再加入不复活旧快照`() = runBlocking {
        val a = device(); val b = device(); val s = seed(a); val api = FakeSyncApi()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = com.android.everytalk.data.database.RoomDataSource(context, a)
        val config = UserSettingEntity("selected_models", "text", """{"configId":"model"}""", updatedAt = 100)
        a.settingsDao().upsertUserSettings(listOf(config))
        source.saveConversationGroups(mapOf("Work" to listOf(s.id)))
        source.savePinnedTextIds(setOf(s.id))
        assertFalse(a.settingsDao().getUserSetting(config.kind, config.key)!!.deleted)
        run(a, api); run(b, api)
        val bs = b.chatDao().getSessionByUuid(s.uuid)!!
        assertEquals(listOf(bs.id), b.settingsDao().getConversationGroups().single().conversationIds)
        assertEquals(listOf(bs.id), b.settingsDao().getPinnedIds(false))
        source.saveConversationGroups(mapOf("Work" to emptyList())); source.savePinnedTextIds(emptySet())
        run(a, api); run(b, api)
        assertTrue(b.settingsDao().getConversationGroups().flatMap { it.conversationIds }.isEmpty())
        assertTrue(b.settingsDao().getPinnedIds(false).isEmpty())
        source.saveConversationGroups(mapOf("Work" to listOf(s.id))); source.savePinnedTextIds(setOf(s.id))
        run(a, api); run(b, api)
        assertEquals(listOf(bs.id), b.settingsDao().getConversationGroups().single().conversationIds)
        assertEquals(listOf(bs.id), b.settingsDao().getPinnedIds(false))
        val pushes = api.pushes; run(b, api); run(a, api)
        assertEquals(pushes, api.pushes)
    }

    @Test fun `首次pull不能抹掉尚未投影的本地分组置顶且混合分组合并成员`() = runBlocking {
        val a = device(); val b = device(); val sa = seed(a, "a"); val sb = seed(b, "b"); val api = FakeSyncApi()
        a.settingsDao().insertConversationGroups(listOf(ConversationGroupEntity("Work", listOf(sa.id))))
        a.settingsDao().insertPinnedItems(listOf(PinnedItemEntity(sa.id, false)))
        b.settingsDao().insertConversationGroups(listOf(ConversationGroupEntity("Work", listOf(sb.id))))
        b.settingsDao().insertPinnedItems(listOf(PinnedItemEntity(sb.id, false)))
        run(a, api); run(b, api); run(a, api)
        assertEquals(2, a.settingsDao().getConversationGroups().single().conversationIds.size)
        assertEquals(2, b.settingsDao().getConversationGroups().single().conversationIds.size)
        assertEquals(2, a.settingsDao().getPinnedIds(false).size)
        assertEquals(2, b.settingsDao().getPinnedIds(false).size)
    }

    @Test fun `同步不能删掉分组里尚未落库的新建会话`() = runBlocking {
        val a = device(); val s = seed(a); val api = FakeSyncApi()
        a.settingsDao().insertConversationGroups(listOf(ConversationGroupEntity("Work", listOf(s.id, "chat_draft"))))
        run(a, api)
        assertTrue("chat_draft" in a.settingsDao().getConversationGroups().single().conversationIds)
    }

    @Test fun `已有消息补充思考和统计不覆盖正文且文字片段不带工具命令`() = runBlocking {
        val a = device(); val b = device(); val s = seed(a); val api = FakeSyncApi()
        val old = a.chatDao().getMessageByUuid(message("local-0", s).uuid)!!
        a.chatDao().writeMessages(listOf(old.copy(reasoning = null)))
        run(a, api); run(b, api)
        val trace = listOf(
            com.android.everytalk.data.DataClass.ExecutionTraceEvent.Content("先查一下"),
            com.android.everytalk.data.DataClass.ExecutionTraceEvent.Reasoning("中途思考"),
            com.android.everytalk.data.DataClass.ExecutionTraceEvent.Tool(com.android.everytalk.data.DataClass.ExecutionStep("tool", com.android.everytalk.data.DataClass.ExecutionStepType.Tool, "不可上传的工具命令")),
            com.android.everytalk.data.DataClass.ExecutionTraceEvent.Content("最后结论"),
        )
        val updated = old.copy(reasoning = "补充思考", executionTrace = trace, executionFinishedAt = 900,
            enabledToolIds = listOf("mcp"), tokenUsage = com.android.everytalk.data.network.TokenUsage(
                inputTokens = 10, outputTokens = 20, isFinal = true, source = com.android.everytalk.data.network.TokenUsageSource.OPENAI_CHAT),
            syncStatus = SyncStatus.PENDING)
        a.chatDao().writeMessages(listOf(updated))
        val body = RoomSyncStore(a).pendingBatch().single().change.payload.toString()
        assertTrue(body.contains("先查一下")); assertFalse(body.contains("不可上传的工具命令"))
        run(a, api); run(b, api)
        val restored = b.chatDao().getMessageByUuid(old.uuid)!!
        assertEquals(old.text, restored.text); assertEquals("补充思考", restored.reasoning)
        assertEquals(updated.tokenUsage, restored.tokenUsage); assertEquals(listOf("mcp"), restored.enabledToolIds)
        assertEquals(3, restored.executionTrace.size)
        assertTrue(RoomSyncStore(b).pendingBatch().isEmpty())
    }

    @Test fun `配置和会话设置先于引用到达后自动生效且不回传`() = runBlocking {
        val db = device(); val s = session(); val store = RoomSyncStore(db)
        val modelId = UUID.randomUUID().toString()
        val setting = UserSettingEntity("conversation_settings", s.uuid,
            """{"modelId":"$modelId","systemPrompt":"系统提示词","promptEnabled":true,"mcpEnabled":true}""", updatedAt = 100)
        store.applyPullPage(page(SyncPayloadMapper.userSetting(setting)))
        store.applyPullPage(page(SyncPayloadMapper.session(s), since = 1))
        val local = db.chatDao().getSessionByUuid(s.uuid)!!
        val source = com.android.everytalk.data.database.RoomDataSource(ApplicationProvider.getApplicationContext(), db)
        assertEquals(modelId, source.loadConversationApiConfigIds()[local.id])
        assertEquals("系统提示词", source.loadConversationSettings()[s.uuid]!!.first)
        assertTrue(store.pendingBatch().isEmpty())
    }

    @Test fun `电脑工作区与自定义语音模型跨设备恢复且重复同步不回传`() = runBlocking {
        val a = device(); val b = device(); val s = seed(a); val api = FakeSyncApi()
        val computer = com.android.everytalk.data.computer.Computer(
            id = UUID.randomUUID().toString(), displayName = "工作电脑", host = "example.org", port = 2222, username = "user",
            authKind = com.android.everytalk.data.computer.ComputerAuthKind.PASSWORD,
            runMode = com.android.everytalk.data.computer.ComputerRunMode.DIRECT,
        ).toEntity(syncJson)
        a.computerDao().upsertComputer(computer)
        a.computerDao().upsertWorkspace(ComputerWorkspaceEntity("ws_test", computer.id, s.id, "DIRECT", "/local/path", "local-container", null, "READY", 1, 1))
        a.settingsDao().insertSetting(SystemSettingEntity("custom_models_stt_custom_platform", "first,second"))
        run(a, api); run(b, api)
        val received = b.computerDao().getAllSnapshot().single()
        assertEquals(computer.host, received.host); assertEquals(2222, received.port)
        assertEquals("MISSING", received.credentialState)
        val workspace = b.computerDao().getAllWorkspacesSnapshot().single()
        assertEquals(b.chatDao().getSessionByUuid(s.uuid)!!.id, workspace.conversationId)
        assertEquals("", workspace.hostPath); assertEquals("STOPPED", workspace.status)
        assertEquals("first,second", b.settingsDao().getValue("custom_models_stt_custom_platform"))
        val pushes = api.pushes
        repeat(2) { run(a, api); run(b, api) }
        assertEquals(pushes, api.pushes)
    }

    @Test fun `升级重放补传旧版漏传文字但不重传冲突内容或无限回传`() = runBlocking {
        val a = device(); val b = device(); val s = seed(a); val api = FakeSyncApi()
        val original = a.chatDao().getMessageByUuid(message("local-0", s).uuid)!!
        run(a, api); run(b, api)
        // 模拟旧设备存有完整文字，但旧上传协议遗漏了这一补充字段。
        a.chatDao().writeMessages(listOf(original.copy(
            syncStatus = SyncStatus.SYNCED, everSynced = true,
            executionTrace = listOf(com.android.everytalk.data.DataClass.ExecutionTraceEvent.Content("调用工具前的说明")),
        )))
        a.settingsDao().insertSetting(SystemSettingEntity("sync_projection_recovery_version", "2"))
        run(a, api); run(b, api)
        assertEquals("调用工具前的说明", (b.chatDao().getMessageByUuid(original.uuid)!!.executionTrace.single() as com.android.everytalk.data.DataClass.ExecutionTraceEvent.Content).text)
        val pushes = api.pushes
        repeat(2) { run(a, api); run(b, api) }
        assertEquals(pushes, api.pushes)
        assertEquals(0, a.settingsDao().syncRejectedCount())
    }

    @Test fun `真实模型自定义参数思考档位及清空跨设备保留且无回传`() = runBlocking {
        val a = device(); val b = device(); val api = FakeSyncApi()
        val source = com.android.everytalk.data.database.RoomDataSource(ApplicationProvider.getApplicationContext(), a)
        val config = com.android.everytalk.data.DataClass.ApiConfig(
            address = "https://example.org/v1", key = "sk-local-private-123456", model = "model", provider = "自定义", name = "测试配置",
            topP = 0.9f, maxTokens = 8192,
            toolsJson = """[{"type":"function","function":{"name":"weather","description":"天气"}}]""",
            modelParameters = com.android.everytalk.data.DataClass.ModelParameters(
                customReasoningEfforts = listOf("custom"),
                customParameters = listOf(com.android.everytalk.data.DataClass.CustomModelParameter("temperature", "0.5", com.android.everytalk.data.DataClass.CustomParameterType.NUMBER)),
            ),
        )
        source.saveApiConfigs(listOf(config)); source.saveSelectedConfigId(config.id)
        val projected = a.settingsDao().getUserSetting("api_configs", config.id)!!
        SyncContractSanitizer.validate(syncJson.parseToJsonElement(projected.value))
        assertFalse(projected.value.contains(config.key))
        run(a, api); run(b, api)
        val received = b.apiConfigDao().getConfigs(false).single()
        assertEquals(config.modelParameters.customParameters, received.modelParameters.customParameters)
        assertEquals(config.modelParameters.customReasoningEfforts, received.modelParameters.customReasoningEfforts)
        assertEquals(syncJson.parseToJsonElement(config.toolsJson!!), syncJson.parseToJsonElement(received.toolsJson!!))
        assertEquals(8192, received.maxTokens); assertEquals("", received.key)
        source.saveApiConfigs(listOf(config.copy(topP = null, maxTokens = null, toolsJson = null,
            modelParameters = config.modelParameters.copy(customParameters = emptyList(), customReasoningEfforts = emptyList()))))
        run(a, api); run(b, api)
        val cleared = b.apiConfigDao().getConfigs(false).single()
        assertNull(cleared.topP); assertNull(cleared.maxTokens)
        assertNull(cleared.toolsJson)
        assertTrue(cleared.modelParameters.customParameters!!.isEmpty())
        val pushes = api.pushes; run(a, api); run(b, api)
        assertEquals(pushes, api.pushes)
    }

    @Test fun `离线修改在保存时封存时间较早修改晚同步也不会抢赢`() = runBlocking {
        val a = device(); val b = device(); val api = FakeSyncApi()
        val config = com.android.everytalk.data.DataClass.ApiConfig("https://example.org/v1", "", "model", "custom", name = "早修改")
        val sourceA = com.android.everytalk.data.database.RoomDataSource(ApplicationProvider.getApplicationContext(), a)
        val sourceB = com.android.everytalk.data.database.RoomDataSource(ApplicationProvider.getApplicationContext(), b)
        sourceA.saveApiConfigs(listOf(config)); sourceB.saveApiConfigs(listOf(config.copy(name = "晚修改")))
        val early = a.settingsDao().getUserSetting("api_configs", config.id)!!
        val late = b.settingsDao().getUserSetting("api_configs", config.id)!!
        a.settingsDao().upsertUserSettings(listOf(early.copy(updatedAt = 100)))
        b.settingsDao().upsertUserSettings(listOf(late.copy(updatedAt = 200)))
        RoomSyncStore(a).prepareForSync()
        assertEquals(100L, a.settingsDao().getUserSetting("api_configs", config.id)!!.updatedAt)
        run(b, api); run(a, api); run(b, api)
        assertEquals("晚修改", a.apiConfigDao().getConfigs(false).single().name)
        assertEquals("晚修改", b.apiConfigDao().getConfigs(false).single().name)
        assertTrue(RoomSyncStore(a).pendingBatch().isEmpty())
    }

    @Test fun `删除配置和取消选中跨设备生效不会被投影复活`() = runBlocking {
        val a = device(); val b = device(); val api = FakeSyncApi()
        val source = com.android.everytalk.data.database.RoomDataSource(ApplicationProvider.getApplicationContext(), a)
        val config = com.android.everytalk.data.DataClass.ApiConfig("https://example.org/v1", "", "model", "custom", name = "删除测试")
        source.saveApiConfigs(listOf(config)); source.saveSelectedConfigId(config.id)
        run(a, api); run(b, api)
        source.saveApiConfigs(emptyList()); source.saveSelectedConfigId(null)
        run(a, api); run(b, api)
        assertTrue(b.apiConfigDao().getConfigs(false).isEmpty())
        assertNull(b.settingsDao().getValue("selected_api_config_id"))
        assertTrue(b.settingsDao().getUserSetting("api_configs", config.id)!!.deleted)
        assertTrue(RoomSyncStore(b).pendingBatch().isEmpty())
    }

    @Test fun `已同步删除墓碑不会被旧配置复活`() = runBlocking {
        val db = device()
        val serverId = UUID.randomUUID().toString()
        val oldValue = buildJsonObject {
            put("localId", serverId); put("displayName", "旧服务器"); put("provider", "SSH")
            put("host", "old.example"); put("port", 22); put("username", "user")
            put("authKind", "PASSWORD"); put("runMode", "DIRECT"); put("createdAt", 1)
        }
        db.settingsDao().upsertUserSettings(listOf(UserSettingEntity(
            "computers", serverId, "{}", updatedAt = 200, deleted = true, syncStatus = SyncStatus.SYNCED,
        )))

        val old = SyncRecord("user_settings", UUID.randomUUID().toString(), buildJsonObject {
            put("kind", "computers"); put("key", serverId); put("value", oldValue); put("clientModifiedAt", 100)
        }, updatedAt = "2026-10-14T18:30:01Z", deleted = false, serverSeq = 1)
        RoomSyncStore(db).applyPullPage(PullResponse(listOf(old), 1, false, 0))

        val tombstone = requireNotNull(db.settingsDao().getUserSetting("computers", serverId))
        assertTrue(tombstone.deleted)
        assertEquals(SyncStatus.SYNCED, tombstone.syncStatus)
        assertNull(db.computerDao().getComputer(serverId))
    }

    @Test fun `删除墓碑和业务行删除之间的同步刷新不会重新入队活动服务器`() = runBlocking {
        val db = device()
        val serverId = UUID.randomUUID().toString()
        val computer = com.android.everytalk.data.computer.Computer(
            id = serverId, displayName = "612", host = "old.example", port = 22,
            username = "user", authKind = com.android.everytalk.data.computer.ComputerAuthKind.PASSWORD,
            runMode = com.android.everytalk.data.computer.ComputerRunMode.DIRECT,
        ).toEntity(syncJson).copy(updatedAt = 100)
        db.computerDao().upsertComputer(computer)
        SyncSettingsProjector(db).markDeleted("computers", serverId)

        // 模拟删除流程尚未执行 dao.deleteComputer 时，同步 Worker 先进入刷新。
        SyncSettingsProjector(db).refresh()

        val setting = requireNotNull(db.settingsDao().getUserSetting("computers", serverId))
        assertTrue(setting.deleted)
        assertEquals(SyncStatus.PENDING, setting.syncStatus)
        assertTrue(RoomSyncStore(db).pendingBatch().any { it.change.deleted && it.change.id == setting.uuid })
    }

    @Test fun `删除服务器先写墓碑旧云端记录不会复活`() = runBlocking {
        val db = device()
        val serverId = UUID.randomUUID().toString()
        db.computerDao().upsertComputer(com.android.everytalk.data.computer.Computer(
            id = serverId, displayName = "612", host = "old.example", port = 22,
            username = "user", authKind = com.android.everytalk.data.computer.ComputerAuthKind.PASSWORD,
            runMode = com.android.everytalk.data.computer.ComputerRunMode.DIRECT,
        ).toEntity(syncJson))
        val serverValue = buildJsonObject {
            put("localId", serverId); put("displayName", "旧服务器"); put("provider", "SSH")
            put("host", "old.example"); put("port", 22); put("username", "user")
            put("authKind", "PASSWORD"); put("runMode", "DIRECT"); put("createdAt", 1)
        }
        db.settingsDao().upsertUserSettings(listOf(UserSettingEntity(
            "computers", serverId, serverValue.toString(), updatedAt = 100, syncStatus = SyncStatus.SYNCED,
        )))
        SyncSettingsProjector(db).markDeleted("computers", serverId)

        val tombstone = db.settingsDao().getUserSetting("computers", serverId)!!
        assertTrue(tombstone.deleted)
        assertEquals(SyncStatus.PENDING, tombstone.syncStatus)
        assertTrue(RoomSyncStore(db).pendingBatch().any { it.change.id == tombstone.uuid && it.change.deleted })

        // 旧快照晚到时，本地墓碑版本更高，不能重新创建服务器实体。
        val old = SyncRecord("user_settings", tombstone.uuid, buildJsonObject {
            put("kind", "computers"); put("key", serverId); put("value", serverValue); put("clientModifiedAt", 100)
        }, updatedAt = "2026-10-14T18:30:01Z", deleted = false, serverSeq = 1)
        RoomSyncStore(db).applyPullPage(PullResponse(listOf(old), 1, false, 0))
        assertNull(db.computerDao().getComputer(serverId))
        assertTrue(db.settingsDao().getUserSetting("computers", serverId)!!.deleted)
    }

    @Test fun `删除时本地尚无设置投影也会创建服务器墓碑`() = runBlocking {
        val db = device()
        val serverId = UUID.randomUUID().toString()
        SyncSettingsProjector(db).markDeleted("computers", serverId)

        val tombstone = db.settingsDao().getUserSetting("computers", serverId)
        requireNotNull(tombstone)
        assertTrue(tombstone.deleted)
        assertEquals(SyncStatus.PENDING, tombstone.syncStatus)
        assertEquals("{}", tombstone.value)
        assertTrue(RoomSyncStore(db).pendingBatch().any { it.change.deleted && it.change.id == tombstone.uuid })
    }

    @Test fun `删除旧版非 UUID 服务器使用稳定同步 key`() = runBlocking {
        val db = device()
        val legacyId = "cloudflare_legacy-server"
        val syncKey = syncStableSettingKey("computers", legacyId)
        db.computerDao().upsertComputer(com.android.everytalk.data.computer.Computer(
            id = legacyId, displayName = "旧服务器", host = "old.example", port = 22,
            username = "user", authKind = com.android.everytalk.data.computer.ComputerAuthKind.PASSWORD,
            runMode = com.android.everytalk.data.computer.ComputerRunMode.DIRECT,
        ).toEntity(syncJson))
        db.settingsDao().upsertUserSettings(listOf(UserSettingEntity(
            "computers", syncKey, buildJsonObject { put("localId", legacyId) }.toString(),
            updatedAt = 100, deleted = false, syncStatus = SyncStatus.SYNCED,
        )))

        SyncSettingsProjector(db).markDeleted("computers", legacyId)

        val tombstone = requireNotNull(db.settingsDao().getUserSetting("computers", syncKey))
        assertTrue(tombstone.deleted)
        assertEquals(SyncStatus.PENDING, tombstone.syncStatus)
        assertNull(db.settingsDao().getUserSetting("computers", legacyId))
        assertTrue(RoomSyncStore(db).pendingBatch().any { it.change.deleted && it.change.id == tombstone.uuid })
    }

    @Test fun `网络等待期间修改服务器应用旧拉取页不会覆盖本地新值`() = runBlocking {
        val db = device()
        val api = FakeSyncApi()
        val computer = com.android.everytalk.data.computer.Computer(
            id = UUID.randomUUID().toString(), displayName = "旧名称", host = "old.example", port = 22,
            username = "user", authKind = com.android.everytalk.data.computer.ComputerAuthKind.PASSWORD,
            runMode = com.android.everytalk.data.computer.ComputerRunMode.DIRECT,
        ).toEntity(syncJson).copy(updatedAt = 100)
        db.computerDao().upsertComputer(computer)
        run(db, api)

        // 模拟网络请求进行中，用户已在本机输入新地址/密钥并落盘。
        db.computerDao().upsertComputer(computer.copy(displayName = "新名称", host = "new.example", updatedAt = 200))
        val page = api.pull(0)
        RoomSyncStore(db).applyPullPage(page)

        val local = db.computerDao().getComputer(computer.id)!!
        assertEquals("新名称", local.displayName)
        assertEquals("new.example", local.host)
        assertEquals(SyncStatus.PENDING,
            db.settingsDao().getUserSetting("computers", computer.id)!!.syncStatus)
    }

    @Test fun `已发送Skill标签与上下文统计恢复且不上传设备路径或草稿`() = runBlocking {
        val a = device(); val b = device(); val s = seed(a); val api = FakeSyncApi()
        val original = a.chatDao().getMessageByUuid(message("local-0", s).uuid)!!
        val parts = listOf(
            com.android.everytalk.data.DataClass.MessageContentPart.Text("请用"),
            com.android.everytalk.data.DataClass.MessageContentPart.SkillReference(com.android.everytalk.data.skill.MessageSkillReference(
                "skill-stable", "测试技能", com.android.everytalk.data.skill.SkillSourceType.LOCAL_IMPORT,
                sourcePath = "/private/device/path", contentHash = "frozen-version")),
        )
        val snapshot = com.android.everytalk.data.DataClass.ContextUsageSnapshot(original.id, "config-stable", 10, 20, 0, 30, 5, 4096, 128000,
            measuredInputTokens = 65, measuredTotalTokens = 100, activeContextTokensOverride = 120)
        a.chatDao().writeMessages(listOf(original.copy(contentParts = parts, contextUsageSnapshot = snapshot)))
        val payload = RoomSyncStore(a).pendingBatch().single { it.entity == SyncEntity.MESSAGES }.change.payload
        assertFalse(payload.toString().contains("/private/device/path"))
        run(a, api); run(b, api)
        val restored = b.chatDao().getMessageByUuid(original.uuid)!!
        assertEquals(2, restored.contentParts.size)
        assertEquals("skill-stable", (restored.contentParts.last() as com.android.everytalk.data.DataClass.MessageContentPart.SkillReference).reference.skillId)
        assertEquals(snapshot.copy(messageId = restored.id), restored.contextUsageSnapshot)
        assertTrue(RoomSyncStore(b).pendingBatch().isEmpty())
    }

    @Test fun `超限思考不能伪装为完整同步且其他消息照常同步`() = runBlocking {
        val a = device(); val b = device(); val s = seed(a); val api = FakeSyncApi()
        val large = message("large-thought", s, "正文完整").copy(reasoning = "思考".repeat(40000))
        a.chatDao().writeMessages(listOf(large))
        run(a, api); run(b, api)
        assertEquals("正文完整", b.chatDao().getMessageByUuid(large.uuid)!!.text)
        assertEquals(large.reasoning, a.chatDao().getMessageByUuid(large.uuid)!!.reasoning)
        assertEquals("MESSAGE_PAYLOAD_TOO_LARGE", a.settingsDao().getValue("sync_rejected:messages:${large.uuid}"))
        val pushes = api.pushes; run(a, api)
        assertEquals(pushes, api.pushes)
    }

    @Test fun `单运行锁并发引擎不会重复push`() = runBlocking {
        val db = device(); seed(db); val api = FakeSyncApi()
        kotlinx.coroutines.coroutineScope {
            val a = async { run(db, api) }
            val b = async { run(db, api) }
            a.await(); b.await()
        }
        assertEquals(1, api.pushes)
        assertTrue(RoomSyncStore(db).pendingBatch().isEmpty())
    }

    @Test fun `旧提示词回填后编辑或清空跨设备保留且功能标签不回传`() = runBlocking {
        val a = device(); val b = device(); val api = FakeSyncApi(); val s = seed(a)
        a.chatDao().writeMessages(listOf(Message(id = "old-system", text = "旧提示词", sender = Sender.System, timestamp = 1).toEntity(s.id)))
        val source = com.android.everytalk.data.database.RoomDataSource(ApplicationProvider.getApplicationContext(), a)
        source.setSetting("conversation_function_toggle_states", buildJsonObject {
            put(s.id, buildJsonObject { put("webSearchEnabled", true); put("mcpEnabled", true); put("agentEnabled", false); put("codeExecutionEnabled", false) })
        }.toString())
        run(a, api); run(b, api)
        assertEquals("旧提示词", b.settingsDao().getUserSetting("conversation_settings", s.uuid)!!.value.let { syncJson.parseToJsonElement(it).jsonObject["systemPrompt"]!!.jsonPrimitive.content })
        source.saveConversationSettings(s.id, "新的提示词", true)
        run(a, api); run(b, api)
        val sourceB = com.android.everytalk.data.database.RoomDataSource(ApplicationProvider.getApplicationContext(), b)
        assertEquals("新的提示词" to true, sourceB.loadConversationSettings()[s.uuid])
        source.saveConversationSettings(s.id, "", false)
        run(a, api); run(b, api)
        assertEquals("" to false, sourceB.loadConversationSettings()[s.uuid])
        val toggles = syncJson.parseToJsonElement(b.settingsDao().getValue("conversation_function_toggle_states")!!).jsonObject
        assertTrue(toggles.values.single().jsonObject["mcpEnabled"]!!.jsonPrimitive.boolean)
        assertTrue(RoomSyncStore(b).pendingBatch().isEmpty())
    }

    @Test fun `Skill来源版本恢复但缺文件不会进入可执行能力`() = runBlocking {
        val a = device(); val b = device(); val api = FakeSyncApi()
        val skill = SkillInstallationEntity("remote:owner/repo#demo", "Demo", "用途说明", "REMOTE", "owner/repo", "skills/demo", "a".repeat(64),
            true, "AUTO", null, 100, 100, null, 20)
        a.skillDao().upsertInstallation(skill)
        run(a, api); run(b, api)
        val received = b.skillDao().getInstallation(skill.skillId)!!
        assertEquals(skill.description, received.description)
        assertEquals(skill.sourceRepository, received.sourceRepository)
        assertEquals(skill.sourcePath, received.sourcePath)
        assertEquals(skill.currentHash, received.currentHash)
        assertEquals(0L, received.useCount)
        val repository = com.android.everytalk.data.skill.SkillRepository(ApplicationProvider.getApplicationContext(), b.skillDao())
        assertFalse(repository.hasVersionFiles(skill.skillId, skill.currentHash))
        assertTrue(repository.createSnapshot().automaticCatalog.isEmpty())
        val manual = com.android.everytalk.data.skill.MessageSkillReference(skill.skillId, skill.name,
            com.android.everytalk.data.skill.SkillSourceType.REMOTE, contentHash = skill.currentHash)
        assertTrue(repository.createSnapshot(listOf(manual)).manualReferences.isEmpty())
        assertTrue(RoomSyncStore(b).pendingBatch().isEmpty())
    }

    @Test fun `客户端不额外拒绝60到64KiB正文且假服务器限制消息合并总量`() = runBlocking {
        val a = device(); val b = device(); val api = FakeSyncApi(); val s = seed(a)
        val large = message("large-but-valid", s, "a".repeat(62 * 1024)).copy(reasoning = null)
        a.chatDao().writeMessages(listOf(large))
        run(a, api); run(b, api)
        assertEquals(large.text, b.chatDao().getMessageByUuid(large.uuid)!!.text)
        assertEquals(0, a.settingsDao().syncRejectedCount())
        val supplement = PushChange("messages", large.uuid, false, buildJsonObject { put("sessionId", s.uuid); put("reasoning", "x".repeat(4096)) })
        assertEquals("MESSAGE_PAYLOAD_TOO_LARGE", api.push(listOf(supplement)).results.single().error!!.code)
        assertFalse(api.rows.getValue("messages" to large.uuid).payload.containsKey("reasoning"))
    }

    @Test fun `旧补充请求缺会话ID失败修复后只恢复一次且携带合法父会话`() = runBlocking {
        val a = device(); val b = device(); val api = FakeSyncApi(); val s = seed(a)
        run(a, api); run(b, api)
        val original = a.chatDao().getMessageByUuid(message("local-0", s).uuid)!!
        a.chatDao().writeMessages(listOf(original.copy(executionTrace = listOf(com.android.everytalk.data.DataClass.ExecutionTraceEvent.Content("工具前的说明")))))
        a.chatDao().markMessagePendingByUuid(original.uuid)
        val store = RoomSyncStore(a)
        val batch = store.pendingBatch()
        val supplement = batch.single().change
        assertEquals(setOf("sessionId", "displaySegments"), supplement.payload.keys)
        assertEquals(s.uuid, supplement.payload["sessionId"]!!.jsonPrimitive.content)
        val legacy = supplement.copy(payload = JsonObject(supplement.payload - "sessionId"))
        val rejection = api.push(listOf(legacy))
        assertEquals("INVALID_SESSION_ID", rejection.results.single().error!!.code)
        store.applyPush(rejection, batch)
        val binding = Binding("same-account"); val state = SyncStateStore(a)
        a.settingsDao().insertSetting(SystemSettingEntity(SyncStateStore.KEY_ACCOUNT, "same-account"))
        a.settingsDao().insertSetting(SystemSettingEntity(SyncStateStore.KEY_AUTOMATIC_ACCOUNT, "same-account"))
        state.prepareAutomaticAccount("same-account", binding)
        run(a, api); run(b, api)
        assertEquals(0, a.settingsDao().syncRejectedCount())
        assertEquals("工具前的说明", (b.chatDao().getMessageByUuid(original.uuid)!!.executionTrace.single() as com.android.everytalk.data.DataClass.ExecutionTraceEvent.Content).text)
        a.chatDao().markMessageRejectedIfVersion(original.uuid, original.updatedAt)
        a.settingsDao().insertSetting(SystemSettingEntity("sync_rejected:messages:${original.uuid}", "INVALID_SESSION_ID"))
        state.prepareAutomaticAccount("same-account", binding)
        assertEquals(1, a.settingsDao().syncRejectedCount())
        assertEquals(SyncStatus.LOCAL_ONLY, a.chatDao().getMessageByUuid(original.uuid)!!.syncStatus)
    }

    @Test fun `长思考显示片段不重复正文且UTF16引用跨设备完整恢复`() = runBlocking {
        val a = device(); val b = device(); val api = FakeSyncApi(); val s = seed(a)
        val thought = "😀" + "a".repeat(40000)
        val trace = listOf(
            com.android.everytalk.data.DataClass.ExecutionTraceEvent.Reasoning("😀"),
            com.android.everytalk.data.DataClass.ExecutionTraceEvent.Reasoning(thought.drop(2)),
            com.android.everytalk.data.DataClass.ExecutionTraceEvent.Content("调用工具前的说明"),
            com.android.everytalk.data.DataClass.ExecutionTraceEvent.Content("最终答案"),
        )
        val msg = message("deduplicated", s, "最终答案").copy(reasoning = thought, executionTrace = trace)
        a.chatDao().writeMessages(listOf(msg))
        val payload = RoomSyncStore(a).pendingBatch().single { it.change.id == msg.uuid }.change.payload
        assertTrue(payload.toString().toByteArray(Charsets.UTF_8).size < 45000)
        assertFalse(payload["displaySegments"]!!.toString().contains("aaaa"))
        run(a, api); run(b, api)
        val restored = b.chatDao().getMessageByUuid(msg.uuid)!!
        assertEquals(thought, restored.reasoning); assertEquals(trace, restored.executionTrace)
        assertEquals(0, a.settingsDao().syncRejectedCount())
        assertTrue(RoomSyncStore(b).pendingBatch().isEmpty())
    }

    @Test fun `旧版完整显示片段不被改写且不阻塞其他缺失统计补传`() = runBlocking {
        val a = device(); val b = device(); val api = FakeSyncApi(); val s = seed(a)
        val original = a.chatDao().getMessageByUuid(message("local-0", s).uuid)!!.copy(executionTrace = listOf(
            com.android.everytalk.data.DataClass.ExecutionTraceEvent.Reasoning("思考")))
        a.chatDao().writeMessages(listOf(original)); run(a, api); run(b, api)
        val old = api.rows.getValue("messages" to original.uuid)
        val literal = buildJsonArray { add(buildJsonObject { put("type", "reasoning"); put("text", "思考") }) }
        api.rows["messages" to original.uuid] = old.copy(payload = JsonObject(old.payload + ("displaySegments" to literal)), serverSeq = ++api.seq)
        a.chatDao().writeMessages(listOf(a.chatDao().getMessageByUuid(original.uuid)!!.copy(enabledToolIds = listOf("tool-name"))))
        a.settingsDao().deleteSetting(SyncStateStore.KEY_CURSOR)
        run(a, api); run(b, api)
        assertEquals(listOf("tool-name"), b.chatDao().getMessageByUuid(original.uuid)!!.enabledToolIds)
        assertEquals(literal, api.rows.getValue("messages" to original.uuid).payload["displaySegments"])
        assertTrue(RoomSyncStore(a).pendingBatch().isEmpty())
    }
}
