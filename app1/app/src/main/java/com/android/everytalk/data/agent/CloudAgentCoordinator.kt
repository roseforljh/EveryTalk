package com.android.everytalk.data.agent

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.computer.ComputerCredentialStore
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.AgentRunEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 一个账号只有一份云端订阅协调器。服务器执行任务，本类只提交同一份冻结输入、续接快照和发送控制。
 * 页面退订、进程消失、网络重连都不会发送 stop，也绝不会退回本机 AgentLoop。
 */
internal class CloudAgentCoordinator(
    private val accountId: String,
    private val database: AppDatabase,
    private val client: CloudAgentClient,
    private val credentials: ComputerCredentialStore,
    private val scope: CoroutineScope,
    private val permitted: suspend () -> Boolean,
    private val onSnapshot: suspend (CloudRunView, Message) -> Unit,
    private val onConnection: suspend (AgentRunEntity, String) -> Unit = { _, _ -> },
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private val store = CloudAgentStore(database)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val submitMutex = Mutex()

    /** POST 之前先原子封存输入。相同 run 的重复调用只能使用完全相同的字节。 */
    suspend fun submit(run: AgentRunEntity, input: JsonObject) {
        requireRun(run)
        check(input["id"]?.jsonPrimitive?.content == run.id) { "云端提交身份不匹配" }
        submitMutex.withLock {
            val bytes = input.toString().toByteArray(Charsets.UTF_8)
            try { credentials.saveCloudAgentInput(accountId, run.id, bytes) }
            finally { bytes.fill(0) }
        }
        track(run.id)
    }

    /** 发现其它设备创建的任务；本机未收到 POST 响应的记录也按原 run 恢复。 */
    suspend fun discover() {
        if (!permitted()) return
        for (run in database.agentDao().getActiveRuns()) {
            if (run.executionOwner == "CLOUD" && run.cloudAccountId == accountId) track(run.id)
        }
        val remote = client.activeRuns()
        for (view in remote) {
            accept(view)
            if (!view.terminal) track(view.id)
        }
    }

    /** 同一 run 同时至多一个连接；先登记再启动，快速终态不会留下已经结束的 Job。 */
    fun track(runId: String) = synchronized(jobs) {
        if (jobs[runId]?.isCompleted == false) return@synchronized
        val job = scope.launch(start = CoroutineStart.LAZY) { follow(runId) }
        jobs[runId] = job
        job.invokeOnCompletion { jobs.remove(runId, job) }
        job.start()
    }

    /** 断线后首先 GET；只有服务器明确确认未创建，才 POST 原来的输入与幂等键。 */
    private suspend fun lookup(run: AgentRunEntity): CloudRunView = try {
        client.get(run.id)
    } catch (error: CloudAgentHttpException) {
        if (error.status != 404 || error.code != "AGENT_NOT_FOUND" || run.cloudCursor > 0) throw error
        val bytes = credentials.loadCloudAgentInput(accountId, run.id)
            ?: throw IllegalStateException("任务尚未提交，请重新连接后重试")
        val input = try { cloudAgentJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }
            finally { bytes.fill(0) }
        check(input["id"]?.jsonPrimitive?.content == run.id) { "冻结输入身份不匹配" }
        client.create(input)
    }

    private suspend fun follow(runId: String) {
        var failures = 0
        while (currentCoroutineContext().isActive && permitted()) {
            val run = database.agentDao().getRun(runId) ?: return
            requireRun(run)
            try {
                val view = lookup(run)
                accept(view)
                if (view.terminal) return
                client.events(runId, database.agentDao().getRun(runId)?.cloudCursor ?: 0L) { next ->
                    accept(next)
                    failures = 0
                }
                if (store.read(runId)?.terminal == true) return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!permitted()) return
                // 鉴权/合同/资源撤销不反复 POST；保留冻结输入与最后快照，重新登录或手动重试再核对。
                if (error is CloudAgentHttpException && (error.status in setOf(400, 401, 403, 404, 409, 413, 422))) {
                    onConnection(run, error.message.orEmpty())
                    return
                }
                onConnection(run, "连接中断，正在重新连接云端任务")
            }
            // 这里只重连订阅，不重试模型。模型的 120 秒与三次重试由后端独立计时。
            failures++
            wait(agentResumeRetryDelayMillis(failures))
        }
    }

    /** 快照和游标先落盘，再通知 UI；相同游标也可因本机 UUID 映射新到而重新投影。 */
    suspend fun accept(view: CloudRunView) {
        if (!permitted()) return
        val applied = store.apply(accountId, view)
        val current = store.read(view.id) ?: return
        val message = applied ?: store.project(accountId, current) ?: return
        if (!permitted()) return
        onSnapshot(current, message)
    }

    /** 命令在整个重试期间保留同一个 commandId 和 generation，审批不能误作用到新一代工具。 */
    suspend fun command(runId: String, type: String, fields: JsonObject = JsonObject(emptyMap()), generation: Long? = null): CloudRunView {
        check(permitted()) { "云端任务账号授权已变更" }
        val latest = client.get(runId)
        val command = buildJsonObject {
            fields.forEach { (name, value) -> put(name, value) }
            put("type", type)
            put("commandId", UUID.randomUUID().toString())
            put("generation", generation ?: latest.generation)
        }
        var failures = 0
        while (true) {
            try {
                val view = client.command(runId, command)
                accept(view)
                if (!view.terminal) track(runId)
                return view
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!permitted() || failures >= 3 || error is CloudAgentHttpException && error.status in setOf(400, 401, 403, 404, 409, 413, 422)) throw error
                wait(agentResumeRetryDelayMillis(++failures))
            }
        }
    }

    private fun requireRun(run: AgentRunEntity) {
        check(run.executionOwner == "CLOUD" && run.cloudAccountId == accountId && run.cloudRunId == run.id) { "云端任务归属不匹配" }
    }
}
