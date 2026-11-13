package com.android.everytalk.data.agent

import com.android.everytalk.data.DataClass.Message
import com.android.everytalk.data.computer.ComputerCredentialStore
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.database.entities.AgentRunEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
    private val blockedRuns = ConcurrentHashMap.newKeySet<String>()
    private val submitMutex = Mutex()
    private val projectionMutex = Mutex()
    /** 服务器已响应过任务列表才算支持整轮云执行；旧版服务器上新请求留在本机，不提交。 */
    @Volatile var serverSupported = false
        private set

    /** POST 之前先原子封存输入。相同 run 的重复调用只能使用完全相同的字节。 */
    suspend fun submit(run: AgentRunEntity, input: JsonObject) {
        check(permitted()) { "云端任务账号授权已变更" }
        requireRun(run)
        check(input["id"]?.jsonPrimitive?.content == run.id) { "云端提交身份不匹配" }
        submitMutex.withLock {
            val bytes = input.toString().toByteArray(Charsets.UTF_8)
            try { credentials.saveCloudAgentInput(accountId, run.id, bytes) }
            finally { bytes.fill(0) }
        }
        blockedRuns.remove(run.id)
        track(run.id)
    }

    /** 发现其它设备创建的任务；本机未收到 POST 响应的记录也按原 run 恢复。 */
    suspend fun discover() {
        if (!permitted()) return
        for (run in database.agentDao().getActiveRuns()) {
            if (run.executionOwner == "CLOUD" && run.cloudAccountId == accountId) track(run.id)
        }
        val remote = try { client.activeRuns() } catch (error: CloudAgentHttpException) {
            if (error.status == 404) serverSupported = false
            throw error
        }
        serverSupported = true
        for (view in remote) {
            accept(view)
            if (!view.terminal) track(view.id)
        }
    }

    /** 向服务器查任务的权威快照并落盘；断网或无权限直接抛错，调用方不能把它当成“没有”。 */
    suspend fun fetch(runId: String): CloudRunView {
        check(permitted()) { "云端任务账号授权已变更" }
        return client.get(runId).also { accept(it) }
    }

    /** 用户明确重试只解除订阅阻断；仍先 GET 同一 run，绝不重新生成任务或输入。 */
    fun retry(runId: String) {
        blockedRuns.remove(runId)
        track(runId)
    }

    /** 同一 run 同时至多一个连接；先登记再启动，快速终态不会留下已经结束的 Job。 */
    fun track(runId: String) = synchronized(jobs) {
        if (runId in blockedRuns || jobs[runId]?.isCompleted == false) return@synchronized
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
        // 用户在服务器确认创建前停止的任务，永远不能再被发现扫描补发。
        check(run.status != AgentRunStatus.CANCELLED.name) { "任务已停止" }
        val bytes = credentials.loadCloudAgentInput(accountId, run.id)
            ?: return localTerminal(run, "FAILED", "任务未能提交到云端，请重新发送")
        val input = try { cloudAgentJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }
            finally { bytes.fill(0) }
        check(input["id"]?.jsonPrimitive?.content == run.id) { "冻结输入身份不匹配" }
        try { client.create(input) } catch (stopped: CloudAgentHttpException) {
            if (stopped.code != "AGENT_STOPPED_BEFORE_CREATE") throw stopped
            localTerminal(run, "CANCELLED", null)
        }
    }

    /**
     * 服务器没有这个任务、本机冻结输入也没了（清数据、换密钥）：再怎么重试都提交不了。
     * 生成一份失败终态走正常落盘与投影，气泡、运行记录和输入框一起结束，不无限重连。
     */
    private suspend fun localTerminal(run: AgentRunEntity, status: String, error: String?): CloudRunView {
        val chat = database.chatDao()
        val session = checkNotNull(chat.getSession(run.sessionId)) { "会话不存在" }
        val user = checkNotNull(chat.getMessage(run.sessionId, run.userMessageId)) { "用户问题不存在" }
        val assistant = checkNotNull(chat.getMessage(run.sessionId, run.visibleAssistantMessageId)) { "回复不存在" }
        return CloudRunView(1, run.id, session.uuid, user.uuid, assistant.uuid, status, run.cloudGeneration, run.cloudCursor + 1,
            CloudRunSnapshot("", "", emptyList(), null, run.createdAt, System.currentTimeMillis(), error))
    }

    private suspend fun follow(runId: String) {
        var failures = 0
        while (currentCoroutineContext().isActive && permitted()) {
            val run = database.agentDao().getRun(runId) ?: return
            requireRun(run)
            // 已落下终态快照（含提交前停止、本机定稿的失败）就没有可跟随的东西。
            if (run.status in setOf(AgentRunStatus.COMPLETED.name, AgentRunStatus.FAILED.name, AgentRunStatus.CANCELLED.name)) return
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
                    blockedRuns.add(runId)
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
    suspend fun accept(view: CloudRunView) = projectionMutex.withLock {
        if (!permitted()) return@withLock
        store.apply(accountId, view)
        val current = store.read(view.id) ?: return@withLock
        // 命令、发现和 SSE 可以并发返回，消息必须与本次读取的持久快照成对，不能混入较旧 apply 的返回值。
        val message = store.project(accountId, current) ?: return@withLock
        if (!permitted()) return@withLock
        onSnapshot(current, message)
    }

    /**
     * 停止还没被服务器确认创建的提交。客户端取消在途 POST 不能证明服务器不会再创建，
     * 所以直接对同一 run 发 stop：服务器已有任务就正常停止；还没有就在同一把账号锁里留下墓碑，
     * 迟到的 POST 会被拒绝，这时才在本机定稿为已停止。
     */
    suspend fun abandonUnsubmitted(run: AgentRunEntity) {
        requireRun(run)
        synchronized(jobs) { jobs[run.id] }?.cancelAndJoin()
        try {
            command(run.id, "stop", generation = run.cloudGeneration)
        } catch (stopped: CloudAgentHttpException) {
            if (stopped.code != "AGENT_STOPPED_BEFORE_CREATE") throw stopped
            accept(localTerminal(run, "CANCELLED", null))
            credentials.deleteCloudAgentInput(accountId, run.id)
        }
    }

    /** 命令在整个重试期间保留同一个 commandId 和 generation，审批不能误作用到新一代工具。 */
    suspend fun command(runId: String, type: String, fields: JsonObject = JsonObject(emptyMap()), generation: Long? = null): CloudRunView {
        check(permitted()) { "云端任务账号授权已变更" }
        val commandId = UUID.randomUUID().toString()
        var target = generation
        var refreshedGeneration = false
        var failures = 0
        while (true) {
            try {
                // 读取当前代次也在有界重试里：一次网络抖动不能让停止/暂停直接失败。
                val resolved = target ?: client.get(runId).generation.also { target = it }
                val command = buildJsonObject {
                    fields.forEach { (name, value) -> put(name, value) }
                    put("type", type)
                    put("commandId", commandId)
                    put("generation", resolved)
                }
                val view = client.command(runId, command)
                accept(view)
                if (!view.terminal) track(runId)
                return view
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                // 停止针对整个任务：本机记录的代次可能落后（创建确认前为 0），取一次最新代次再停，不能因此停不下来。
                if (type == "stop" && error is CloudAgentHttpException && error.code == "AGENT_STALE_GENERATION" && !refreshedGeneration) {
                    refreshedGeneration = true; target = null; continue
                }
                if (!permitted() || failures >= 3 || error is CloudAgentHttpException && error.status in setOf(400, 401, 403, 404, 409, 413, 422)) throw error
                wait(agentResumeRetryDelayMillis(++failures))
            }
        }
    }

    private fun requireRun(run: AgentRunEntity) {
        check(run.executionOwner == "CLOUD" && run.cloudAccountId == accountId && run.cloudRunId == run.id) { "云端任务归属不匹配" }
    }
}
