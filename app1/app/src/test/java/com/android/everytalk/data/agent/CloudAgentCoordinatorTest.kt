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
                else -> respond("{\"error\":\"AGENT_NOT_FOUND\"}", HttpStatusCode.NotFound)
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
                else -> respond("{\"error\":\"AGENT_NOT_FOUND\"}", HttpStatusCode.NotFound)
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
