package com.android.everytalk.data.agent

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.DataClass.Sender
import com.android.everytalk.data.computer.ComputerCredentialStore
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.ChatSessionEntity
import com.android.everytalk.data.database.entities.MessageDraftEntity
import com.android.everytalk.data.database.entities.toEntity
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CloudAgentCoordinatorTest {
    private val account = "aeff1b26-d0fb-4ee9-98c7-d2cab5418671"
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun database() = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private fun credentials() = ComputerCredentialStore(context, SecretKeySpec(ByteArray(32) { 7 }, "AES"))
    private suspend fun prepare(db: AppDatabase): CloudRunView {
        val session = ChatSessionEntity("session", 1, 1, false)
        val user = Message(id = "user", text = "问题", sender = Sender.User, timestamp = 2).toEntity(session.id)
        val ai = Message(id = "ai", text = "", sender = Sender.AI, timestamp = 3).toEntity(session.id)
        db.chatDao().writeSession(session)
        db.chatDao().writeMessages(listOf(user))
        db.chatDao().writeDrafts(listOf(MessageDraftEntity(ai)))
        val run = CloudAgentStore(db).prepare(account, session.id, user.id, ai.id, null)
        return CloudRunView(1, run.id, session.uuid, user.uuid, ai.uuid, "RUNNING", 1, 1,
            CloudRunSnapshot("第一帧", "", emptyList(), "执行中", 3))
    }
    private fun raw(view: CloudRunView) = cloudAgentJson.encodeToString(CloudRunView.serializer(), view)

    @Test fun `首次发送主动确认云端支持且成功后不重复探测`() = runBlocking {
        val db = database(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var probes = 0; var asked = 0
        val http = HttpClient(MockEngine { request ->
            // 能力协商与活跃列表是两次请求；都只在首次确认时各发一次。能力入口在 /v1/agent/capabilities，不是任务 ID 路径。
            if (request.url.encodedPath.endsWith("/capabilities")) {
                assertEquals("/v1/agent/capabilities", request.url.encodedPath); asked++
                respond("{\"version\":2,\"features\":[\"secret\",\"memory\"]}")
            } else {
                assertEquals("true", request.url.parameters["active"]); probes++
                respond("{\"runs\":[]}")
            }
        })
        try {
            val coordinator = CloudAgentCoordinator(account, db,
                CloudAgentClient("https://example.com", { "access" }, { true }, http), credentials(), scope, { true }, { _, _ -> })
            assertFalse(coordinator.serverSupported)
            // 后台发现先确认了支持：首次发送仍要补问能力，不能拿空集当旧后端。
            coordinator.discover()
            assertTrue(coordinator.serverSupported)
            coordinator.ensureSupported()
            coordinator.ensureSupported()
            assertEquals(setOf("secret", "memory"), coordinator.serverFeatures)
            assertEquals(1, probes); assertEquals(1, asked)
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); http.close(); db.close() }
    }

    @Test fun `探测失败区分断网和接口缺失且恢复后允许再次发送`() = runBlocking {
        for (status in listOf(null, HttpStatusCode.NotFound, HttpStatusCode.Unauthorized)) {
            val db = database(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            var recovered = false
            val http = HttpClient(MockEngine {
                if (recovered) respond("{\"runs\":[]}")
                else if (status == null) throw IOException("网络中断")
                else respond("{}", status)
            })
            try {
                val coordinator = CloudAgentCoordinator(account, db,
                    CloudAgentClient("https://example.com", { "access" }, { true }, http), credentials(), scope, { true }, { _, _ -> })
                try {
                    coordinator.ensureSupported()
                    fail("探测失败不能提交云端任务")
                } catch (error: CloudAgentIneligible) {
                    val expected = when (status) {
                        null -> "云端暂时连不上，请稍后重发"
                        HttpStatusCode.NotFound -> "后端不支持云端任务，请检查后端版本"
                        else -> "云端任务请求失败（HTTP 401）"
                    }
                    assertEquals(expected, error.message)
                }
                assertFalse(coordinator.serverSupported)
                recovered = true
                coordinator.ensureSupported()
                assertTrue(coordinator.serverSupported)
            } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); http.close(); db.close() }
        }
    }

    @Test fun `提交响应丢失先核对同一任务断流按落盘游标恢复且不重复POST`() = runBlocking {
        val db = database(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val credentials = credentials(); val initial = prepare(db)
        var exists = false; var posts = 0; var connections = 0
        val cursors = mutableListOf<String?>(); val waits = mutableListOf<Long>()
        val done = CompletableDeferred<Message>()
        val final = initial.copy(status = "COMPLETED", cursor = 3,
            snapshot = initial.snapshot.copy(text = "完整回复", finishedAt = 9, executionStatus = null))
        val http = HttpClient(MockEngine { request ->
            when {
                request.method.value == "POST" -> { exists = true; posts++; throw IOException("响应丢失") }
                request.url.encodedPath.endsWith("/events") -> {
                    connections++; cursors += request.url.parameters["after"]
                    if (connections == 1) respond("event: snapshot\ndata: ${raw(initial.copy(cursor = 2))}\n\n", headers = headersOf("Content-Type", "text/event-stream"))
                    else respond("event: snapshot\ndata: ${raw(final)}\n\n", headers = headersOf("Content-Type", "text/event-stream"))
                }
                exists -> respond(raw(initial))
                else -> respond("{\"error\":{\"code\":\"AGENT_NOT_FOUND\"}}", HttpStatusCode.NotFound)
            }
        })
        try {
            val client = CloudAgentClient("https://example.com", { "access" }, { true }, http)
            val coordinator = CloudAgentCoordinator(account, db, client, credentials, scope, { true },
                onSnapshot = { view, message -> if (view.terminal) done.complete(message) }, wait = { waits += it })
            val run = db.agentDao().getRun(initial.id)!!
            coordinator.submit(run, buildJsonObject { put("id", run.id); put("apiKey", "test-only") })
            val message = withTimeout(10_000) { done.await() }
            assertEquals("完整回复", message.text)
            assertEquals(1, posts)
            assertEquals(listOf("1", "2"), cursors)
            assertEquals(listOf(5_000L, 5_000L), waits)
            assertEquals(3L, db.agentDao().getRun(initial.id)!!.cloudCursor)
            assertEquals(final, CloudAgentStore(db).read(initial.id))
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); credentials.deleteCloudAgentInput(account, initial.id); http.close(); db.close() }
    }

    @Test fun `永久请求错误不被发现轮询重新提交且换号后不应用旧响应`() = runBlocking {
        val db = database(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val initial = prepare(db); val rejected = CompletableDeferred<Unit>()
        var gets = 0; var posts = 0; var permitted = true; var projections = 0
        val http = HttpClient(MockEngine { request ->
            when {
                request.url.parameters["active"] == "true" -> respond("{\"runs\":[]}")
                request.method.value == "POST" -> { posts++; respond("{}", HttpStatusCode.UnprocessableEntity) }
                else -> { gets++; respond("{}", HttpStatusCode.Forbidden) }
            }
        })
        try {
            val coordinator = CloudAgentCoordinator(account, db,
                CloudAgentClient("https://example.com", { "access" }, { permitted }, http), credentials(), scope,
                { permitted }, { _, _ -> projections++ }, onConnection = { _, _ -> rejected.complete(Unit) }, wait = { fail("永久错误不能退避重试") })
            coordinator.track(initial.id)
            withTimeout(10_000) { rejected.await() }
            coordinator.discover(); coordinator.discover()
            assertEquals(1, gets); assertEquals(0, posts)
            permitted = false
            coordinator.accept(initial)
            assertEquals(0, projections)
            assertNull(CloudAgentStore(db).read(initial.id))
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); http.close(); db.close() }
    }

    @Test fun `服务器确认创建前停止留下墓碑作废冻结输入且之后不会补发`() = runBlocking {
        val db = database(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val credentials = credentials(); val initial = prepare(db)
        var posts = 0; var stops = 0
        val http = HttpClient(MockEngine { request ->
            when {
                request.url.parameters["active"] == "true" -> respond("{\"runs\":[]}")
                request.url.encodedPath.endsWith("/commands") -> { stops++; respond("{\"error\":{\"code\":\"AGENT_STOPPED_BEFORE_CREATE\"}}", HttpStatusCode.NotFound) }
                request.method.value == "POST" -> { posts++; respond(raw(initial)) }
                else -> respond("{\"error\":{\"code\":\"AGENT_NOT_FOUND\"}}", HttpStatusCode.NotFound)
            }
        })
        try {
            credentials.saveCloudAgentInput(account, initial.id, buildJsonObject { put("id", initial.id) }.toString().toByteArray())
            val coordinator = CloudAgentCoordinator(account, db,
                CloudAgentClient("https://example.com", { "access" }, { true }, http), credentials, scope, { true }, { _, _ -> }, wait = {})
            assertEquals(false, coordinator.serverSupported)
            coordinator.abandonUnsubmitted(db.agentDao().getRun(initial.id)!!)
            assertEquals(1, stops)
            assertEquals(AgentRunStatus.CANCELLED.name, db.agentDao().getRun(initial.id)!!.status)
            assertNull(credentials.loadCloudAgentInput(account, initial.id))
            coordinator.discover()
            coordinator.track(initial.id)
            kotlinx.coroutines.delay(300)
            assertEquals(true, coordinator.serverSupported)
            assertEquals(0, posts)
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); http.close(); db.close() }
    }

    @Test fun `后台完成后前台主动取回终态且半开订阅或进程重建都不重跑任务`() = runBlocking {
        for (recreate in listOf(false, true)) {
            val db = database()
            val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val resumedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val initial = prepare(db)
            CloudAgentStore(db).apply(account, initial)
            val connected = CompletableDeferred<Unit>()
            val done = CompletableDeferred<Message>()
            var current = initial
            var posts = 0
            var commands = 0
            val http = HttpClient(MockEngine { request ->
                when {
                    request.url.parameters["active"] == "true" -> respond("{\"runs\":[]}")
                    request.url.encodedPath.endsWith("/events") -> { connected.complete(Unit); awaitCancellation() }
                    request.url.encodedPath.endsWith("/commands") -> { commands++; error("页面或进程销毁不能发送控制命令") }
                    request.method.value == "POST" -> { posts++; error("已确认的任务不能重复提交") }
                    else -> respond(raw(current))
                }
            })
            fun coordinator(scope: CoroutineScope) = CloudAgentCoordinator(account, db,
                CloudAgentClient("https://example.com", { "access" }, { true }, http), credentials(), scope, { true },
                onSnapshot = { view, message -> if (view.terminal) done.complete(message) })
            try {
                val first = coordinator(firstScope)
                first.track(initial.id)
                withTimeout(10_000) { connected.await() }
                if (recreate) { firstScope.cancel(); firstScope.coroutineContext[Job]!!.join() }
                // 客户端缺席期间服务器已完成；活跃列表为空，旧长连接仍挂着时也必须 GET 到终态。
                current = initial.copy(status = "COMPLETED", cursor = 8,
                    snapshot = initial.snapshot.copy(text = "后台完成的结果", finishedAt = 100, executionStatus = null))
                (if (recreate) coordinator(resumedScope) else first).refresh()
                val message = withTimeout(10_000) { done.await() }
                assertEquals("后台完成的结果", message.text)
                assertEquals(97L, message.executionFinishedAt!! - message.timestamp)
                assertEquals("COMPLETED", db.agentDao().getRun(initial.id)!!.status)
                assertEquals(8L, db.agentDao().getRun(initial.id)!!.cloudCursor)
                assertEquals(0, posts); assertEquals(0, commands)
            } finally {
                firstScope.cancel(); resumedScope.cancel()
                firstScope.coroutineContext[Job]!!.join(); resumedScope.coroutineContext[Job]!!.join()
                http.close(); db.close()
            }
        }
    }

    @Test fun `订阅半开时后台发现从详情补齐已完成任务`() = runBlocking {
        val db = database(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val initial = prepare(db)
        CloudAgentStore(db).apply(account, initial)
        val connected = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var details = initial
        val http = HttpClient(MockEngine { request ->
            when {
                request.url.parameters["active"] == "true" -> respond("{\"runs\":[]}")
                request.url.encodedPath.endsWith("/events") -> { connected.complete(Unit); awaitCancellation() }
                else -> { assertEquals("GET", request.method.value); respond(raw(details)) }
            }
        })
        try {
            val coordinator = CloudAgentCoordinator(account, db,
                CloudAgentClient("https://example.com", { "access" }, { true }, http), credentials(), scope,
                { true }, { view, _ -> if (view.terminal) finished.complete(Unit) })
            coordinator.track(initial.id)
            withTimeout(10_000) { connected.await() }
            coordinator.discover()
            assertEquals(AgentRunStatus.WAITING_MODEL.name, db.agentDao().getRun(initial.id)!!.status)
            details = initial.copy(status = "COMPLETED", cursor = 2, snapshot = initial.snapshot.copy(finishedAt = 4))
            coordinator.discover()
            withTimeout(10_000) { finished.await() }
            assertEquals(AgentRunStatus.COMPLETED.name, db.agentDao().getRun(initial.id)!!.status)
            assertTrue(db.agentDao().getActiveRuns().isEmpty())
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); http.close(); db.close() }
    }

    @Test fun `提交被不可变字段冲突拒绝时结束本地任务`() = runBlocking {
        val db = database(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val credentials = credentials(); val initial = prepare(db)
        val finished = CompletableDeferred<Message>()
        val http = HttpClient(MockEngine { request ->
            if (request.method.value == "POST") respond("{\"error\":{\"code\":\"IMMUTABLE_FIELD\"}}", HttpStatusCode.Conflict)
            else respond("{\"error\":{\"code\":\"AGENT_NOT_FOUND\"}}", HttpStatusCode.NotFound)
        })
        try {
            val coordinator = CloudAgentCoordinator(account, db,
                CloudAgentClient("https://example.com", { "access" }, { true }, http), credentials, scope,
                { true }, { view, message -> if (view.terminal) finished.complete(message) })
            coordinator.submit(db.agentDao().getRun(initial.id)!!, buildJsonObject { put("id", initial.id) })
            val message = withTimeout(10_000) { finished.await() }
            assertTrue(message.isError)
            assertTrue(message.executionStatus.orEmpty().contains("IMMUTABLE_FIELD"))
            assertEquals(AgentRunStatus.FAILED.name, db.agentDao().getRun(initial.id)!!.status)
            assertTrue(db.agentDao().getActiveRuns().isEmpty())
        } finally {
            scope.cancel(); scope.coroutineContext[Job]!!.join()
            credentials.deleteCloudAgentInput(account, initial.id); http.close(); db.close()
        }
    }

    @Test fun `已提交任务鉴权恢复后前台刷新重接订阅且不覆盖执行状态`() = runBlocking {
        val db = database(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val initial = prepare(db)
        CloudAgentStore(db).apply(account, initial)
        var authorized = false
        val blocked = CompletableDeferred<Unit>()
        val connected = CompletableDeferred<Unit>()
        var requests = 0
        val http = HttpClient(MockEngine { request ->
            requests++
            when {
                request.url.parameters["active"] == "true" -> respond("{\"runs\":[${raw(initial)}]}")
                request.url.encodedPath.endsWith("/events") -> { connected.complete(Unit); awaitCancellation() }
                authorized -> respond(raw(initial))
                else -> respond("{}", HttpStatusCode.Unauthorized)
            }
        })
        try {
            val coordinator = CloudAgentCoordinator(account, db,
                CloudAgentClient("https://example.com", { "access" }, { true }, http), credentials(), scope, { true }, { _, _ -> },
                onConnection = { _, _ -> blocked.complete(Unit) })
            coordinator.track(initial.id)
            withTimeout(10_000) { blocked.await() }
            assertEquals("执行中", CloudAgentStore(db).read(initial.id)!!.snapshot.executionStatus)
            authorized = true
            coordinator.refresh()
            withTimeout(10_000) { connected.await() }
            assertTrue(requests >= 4)
            assertEquals("执行中", CloudAgentStore(db).read(initial.id)!!.snapshot.executionStatus)
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); http.close(); db.close() }
    }

    @Test fun `重建协调器只读取冻结输入控制重试保留命令身份且取消订阅不发停止`() = runBlocking {
        val db = database(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val credentials = credentials(); val initial = prepare(db)
        val posted = mutableListOf<String>(); val commandIds = mutableListOf<String>()
        val done = CompletableDeferred<Unit>()
        var exists = false
        val http = HttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/commands") -> {
                    val body = (request.body as io.ktor.http.content.TextContent).text
                    commandIds += cloudAgentJson.parseToJsonElement(body).jsonObject.getValue("commandId").jsonPrimitive.content
                    if (commandIds.size == 1) throw IOException("响应丢失")
                    respond(raw(initial.copy(status = "PAUSED", cursor = 2)))
                }
                request.method.value == "POST" -> { exists = true; posted += (request.body as io.ktor.http.content.TextContent).text; respond(raw(initial)) }
                request.url.encodedPath.endsWith("/events") -> { done.complete(Unit); awaitCancellation() }
                exists -> respond(raw(initial))
                else -> respond("{\"error\":{\"code\":\"AGENT_NOT_FOUND\"}}", HttpStatusCode.NotFound)
            }
        })
        try {
            val frozen = buildJsonObject { put("id", initial.id); put("apiKey", "frozen-test-key") }.toString()
            credentials.saveCloudAgentInput(account, initial.id, frozen.toByteArray())
            val coordinator = CloudAgentCoordinator(account, db,
                CloudAgentClient("https://example.com", { "access" }, { true }, http), credentials(), scope, { true }, { _, _ -> }, wait = {})
            coordinator.track(initial.id)
            withTimeout(10_000) { done.await() }
            assertEquals(listOf(frozen), posted)
            coordinator.command(initial.id, "pause")
            assertEquals(2, commandIds.size)
            assertEquals(1, commandIds.toSet().size)
            scope.cancel(); scope.coroutineContext[Job]!!.join()
            assertEquals("PAUSED", CloudAgentStore(db).read(initial.id)!!.status)
            assertEquals(2, commandIds.size)
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); credentials.deleteCloudAgentInput(account, initial.id); http.close(); db.close() }
    }
}
