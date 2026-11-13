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

    @Test fun `云工具绑定冻结搜索和MCP并保留延迟加载且不静默丢未知工具`() = runBlocking {
        val db = database()
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val skills = com.android.everytalk.data.skill.SkillRepository(context, db.skillDao())
            fun tool(name: String, description: String = name): Map<String, Any> = mapOf("type" to "function", "function" to mapOf(
                "name" to name, "description" to description, "parameters" to mapOf("type" to "object", "properties" to emptyMap<String, Any>())))
            val mcp = (1..25).map { tool("mcp_example_$it", "[Server] read file $it") }
            val bindings = mcp.associate { McpToolDiscovery.name(it) to buildJsonObject {
                put("kind", "mcp"); put("url", "https://example.com/mcp"); put("transport", "streamable_http")
                put("headers", buildJsonObject { put("Authorization", "test-only") }); put("remoteName", "read")
            } }
            val request = ChatRequest(listOf(SimpleTextApiMessage(role = "user", content = "问题")), "provider", "OpenAI兼容", "https://example.com", "test-only", "model",
                tools = mcp + tool("web_search"), localCloudMcpBindings = bindings,
                localExternalWebSearchConfig = com.android.everytalk.data.network.ExternalWebSearchProviderConfig("exa", "search-only"))
            val frozen = cloudAgentTools(context, request, emptyList(), skills).map { it as kotlinx.serialization.json.JsonObject }
            fun value(name: String) = frozen.single { it.getValue("name").toString() == "\"$name\"" }
            assertEquals(28, frozen.size)
            assertTrue(mcp.all { value(McpToolDiscovery.name(it))["deferred"].toString() == "true" })
            assertEquals("\"search-only\"", (value("web_search")["binding"] as kotlinx.serialization.json.JsonObject)["apiKey"].toString())
            assertFalse(value("mcp_example_1")["parameters"].toString().contains("test-only"))
            assertTrue(runCatching { cloudAgentTools(context, request.copy(tools = listOf(tool("unknown"))), emptyList(), skills) }.isFailure)
            // 提问进入云端等待，保存到手机等设备执行，其他控制工具立即返回说明；原生供应商工具和记忆工具让请求留在本机。
            val controls = AgentControlToolNames.all.map { tool(it) } + tool(com.android.everytalk.data.computer.ComputerToolNames.LOCAL_FILE_SAVE)
            val withControls = cloudAgentTools(context, request.copy(tools = request.tools!! + controls), emptyList(), skills)
                .map { it as kotlinx.serialization.json.JsonObject }
            fun kind(name: String) = (withControls.single { it.getValue("name").toString() == "\"$name\"" }["binding"] as kotlinx.serialization.json.JsonObject)["kind"].toString()
            assertEquals(28 + controls.size, withControls.size)
            assertEquals("\"question\"", kind(AgentControlToolNames.REQUEST_USER_INPUT))
            assertEquals("\"device\"", kind(com.android.everytalk.data.computer.ComputerToolNames.LOCAL_FILE_SAVE))
            assertEquals("\"unsupported\"", kind(AgentControlToolNames.REQUEST_AGENT))
            assertTrue(runCatching { cloudAgentTools(context, request.copy(tools = listOf(mapOf("googleSearch" to emptyMap<String, Any>()))), emptyList(), skills) }.isFailure)
            assertTrue(runCatching { cloudAgentTools(context, request.copy(tools = listOf(tool("everytalk_memory"))), emptyList(), skills) }.isFailure)
        } finally { db.close() }
    }

    @Test fun `云端快照带出模型名真实用量和搜索来源`() = runBlocking {
        val search = kotlinx.serialization.json.buildJsonObject {
            put("content", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.buildJsonObject {
                put("type", "text"); put("text", "{\"provider\":\"exa\",\"results\":[{\"title\":\"标题\",\"url\":\"https://a.example\",\"snippet\":\"摘要\"}]}")
            }) })
        }
        val view = CloudRunView(1, "aeff1b26-d0fb-4ee9-98c7-d2cab5418671", "aeff1b26-d0fb-4ee9-98c7-d2cab5418672",
            "aeff1b26-d0fb-4ee9-98c7-d2cab5418673", "aeff1b26-d0fb-4ee9-98c7-d2cab5418674", "COMPLETED", 1, 3,
            CloudRunSnapshot("答", "", listOf(CloudRunTrace("tool", 1, id = "c1", name = "web_search", finishedAt = 2, result = search)),
                null, 1, 3, modelName = "gpt-x", providerName = "网关",
                usage = CloudRunUsage("openai-completions", input = 100, output = 20, cacheRead = 900, cacheWrite = 0, totalTokens = 1020)))
        val message = view.toMessage(com.android.everytalk.data.DataClass.Message(id = "ai", text = "", sender = Sender.AI, timestamp = 1)) { it }
        assertEquals("gpt-x", message.modelName)
        assertEquals("网关", message.providerName)
        assertEquals(1000L, message.tokenUsage?.inputTokens)
        assertEquals(900L, message.tokenUsage?.cachedInputTokens)
        assertEquals(listOf("https://a.example"), message.webSearchResults?.map { it.href })
    }

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

    @Test fun `冷启动恢复和本机取消不能封存云端草稿或回收快照`() = runBlocking {
        val db = database()
        try {
            val chat = db.chatDao(); val dao = db.agentDao()
            val session = ChatSessionEntity("cloud-recovery", 1, 1, false)
            val user = Message(id = "cloud-user", text = "问题", sender = Sender.User, timestamp = 2).toEntity(session.id)
            val ai = Message(id = "cloud-ai", text = "", sender = Sender.AI, timestamp = 3).toEntity(session.id)
            chat.writeSession(session); chat.writeMessages(listOf(user)); chat.writeDrafts(listOf(MessageDraftEntity(ai)))
            val store = CloudAgentStore(db)
            val run = store.prepare(account, session.id, user.id, ai.id, null)
            val view = CloudRunView(1, run.id, session.uuid, user.uuid, ai.uuid, "WAITING_APPROVAL", 2, 7,
                CloudRunSnapshot("仍在执行", "", emptyList(), "等待确认", 3))
            store.apply(account, view)
            val local = AgentRunStore(dao)
            assertNull(local.decodeRequestSnapshot(dao.getRun(run.id)!!))
            assertNull(local.cancelActiveRunByVisibleMessage(ai.id, "USER_STOP"))
            local.updateRunStatus(run, AgentRunStatus.CANCELLED)
            dao.cancelSupersededApprovalRunsForSession(session.id, "SUPERSEDED", 20)
            assertEquals(0, dao.cancelActiveRunById(run.id, "USER_STOP", 20))
            com.android.everytalk.data.database.RoomDataSource(ApplicationProvider.getApplicationContext(), db).recoverInterruptedDrafts()
            dao.recoverInterruptedAgentRuns()
            assertEquals(view, store.read(run.id))
            assertEquals("WAITING_APPROVAL", dao.getRun(run.id)!!.status)
            assertEquals("仍在执行", chat.getDraft(session.id, ai.id)!!.message.text)
            assertNull(chat.getMessageByUuid(ai.uuid))
            assertTrue(dao.getLocalActiveRuns().isEmpty())
            assertTrue(dao.getWaitingApprovalRuns().isEmpty())
            assertEquals(listOf(run.id), dao.getActiveRuns().map { it.id })
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
