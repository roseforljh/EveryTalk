package com.android.everytalk.data.agent

import com.android.everytalk.data.network.BoundedSseLineReader
import com.android.everytalk.data.network.OwnedServerRequestPurpose
import com.android.everytalk.data.network.ownedServerRequestGuard
import com.android.everytalk.data.network.readTextAtMost
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.http.contentType
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.UUID

/** 后端 RunView 的原始合同；未知新增字段可忽略，但身份、状态和序号必须校验。 */
@Serializable
internal data class CloudRunView(
    val version: Int,
    val id: String,
    val sessionUuid: String,
    val userMessageUuid: String,
    val assistantMessageUuid: String,
    val status: String,
    val generation: Long,
    val cursor: Long,
    val snapshot: CloudRunSnapshot,
) {
    val terminal: Boolean get() = status in setOf("COMPLETED", "FAILED", "CANCELLED")

    fun validate(): CloudRunView = apply {
        require(version == 1 && generation >= 0 && cursor >= 0) { "云端任务版本或游标无效" }
        listOf(id, sessionUuid, userMessageUuid, assistantMessageUuid).forEach(::requireCloudUuid)
        require(status in setOf("QUEUED", "RUNNING", "PAUSED", "WAITING_APPROVAL", "WAITING_USER", "WAITING_DEVICE", "COMPLETED", "FAILED", "CANCELLED")) { "云端任务状态无效" }
        require(snapshot.startedAt >= 0 && (snapshot.finishedAt == null || snapshot.finishedAt >= snapshot.startedAt)) { "云端任务时间无效" }
    }
}

@Serializable
internal data class CloudRunSnapshot(
    val text: String,
    val reasoning: String,
    val trace: List<CloudRunTrace>,
    val executionStatus: String?,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val error: String? = null,
    val waiting: CloudRunWaiting? = null,
)

@Serializable
internal data class CloudRunTrace(
    val kind: String,
    val startedAt: Long,
    val text: String = "",
    val id: String = "",
    val name: String = "",
    val arguments: JsonObject? = null,
    val finishedAt: Long? = null,
    val result: JsonElement? = null,
    val isError: Boolean = false,
    val messageUuid: String = "",
)

@Serializable
internal data class CloudRunWaiting(val kind: String, val toolCallId: String, val toolName: String, val arguments: JsonElement)

internal val cloudAgentJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
internal fun requireCloudUuid(value: String) {
    require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) { "云端任务身份无效" }
}

/** 不保留或展示服务端原始错误正文，它可能含上游凭据；仅带受控状态码和错误标识。 */
internal class CloudAgentHttpException(val status: Int, val code: String?) : IOException("云端任务请求失败（HTTP $status${code?.let { ", $it" }.orEmpty()}）")

/**
 * 只负责传输，不启动本地 AgentLoop，也不把退订当停止。权限闭包绑定创建时账号，
 * 每次网络往返后再检查，避免退出/换号后将旧响应写入当前账号。
 */
internal class CloudAgentClient(
    baseUrl: String,
    private val token: suspend (forceRefresh: Boolean) -> String?,
    private val permitted: suspend () -> Boolean,
    httpClient: HttpClient? = null,
) : AutoCloseable {
    private val base = baseUrl.trimEnd('/')
    private val ownsClient = httpClient == null
    private val client = httpClient ?: HttpClient(OkHttp) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) { connectTimeoutMillis = 15_000; requestTimeoutMillis = 30_000 }
        install(ownedServerRequestGuard({ false }, { true }, { OwnedServerRequestPurpose.SYNC }))
    }

    suspend fun create(input: JsonObject): CloudRunView {
        val id = input.getValue("id").jsonPrimitive.content
        requireCloudUuid(id)
        return decode(request("", HttpMethod.Post, input.toString())).also { check(it.id == id) { "云端任务响应身份不匹配" } }
    }

    suspend fun get(id: String): CloudRunView {
        requireCloudUuid(id)
        return decode(request("/$id")).also { check(it.id == id) { "云端任务响应身份不匹配" } }
    }

    suspend fun activeRuns(): List<CloudRunView> = cloudAgentJson.decodeFromString<CloudRunList>(request("?active=true")).runs.map { it.validate() }

    suspend fun command(id: String, command: JsonObject): CloudRunView {
        requireCloudUuid(id)
        requireCloudUuid(command.getValue("commandId").jsonPrimitive.content)
        return decode(request("/$id/commands", HttpMethod.Post, command.toString())).also { check(it.id == id) { "云端任务响应身份不匹配" } }
    }

    /**
     * 一个连接先收完整快照，再接递增事件；断线交给调用方带持久游标重连。
     * SSE 与等待工具/审批均无 120 秒限制；90 秒只用于发现半开连接。
     */
    suspend fun events(id: String, after: Long, apply: suspend (CloudRunView) -> Unit) {
        requireCloudUuid(id)
        require(after >= 0)
        var access = access(false)
        repeat(2) { attempt ->
            check(permitted()) { "云端任务账号授权已变更" }
            val retryAuth = client.prepareGet("$base/v1/agent/runs/$id/events") {
                bearerAuth(access)
                parameter("after", after)
                timeout { requestTimeoutMillis = Long.MAX_VALUE; socketTimeoutMillis = 90_000; connectTimeoutMillis = 15_000 }
            }.execute { response ->
                check(permitted()) { "云端任务账号授权已变更" }
                if (response.status.value == 401 && attempt == 0) return@execute true
                if (response.status.value !in 200..299) throw httpError(response.status.value, response.readTextAtMost(64 * 1024L))
                val lines = BoundedSseLineReader(response.bodyAsChannel(), 16 * 1024 * 1024L, Long.MAX_VALUE)
                var event = ""
                val data = StringBuilder()
                while (true) {
                    val line = lines.readLine() ?: break
                    when {
                        line.isEmpty() -> {
                            if ((event == "snapshot" || event == "run") && data.isNotEmpty()) {
                                val view = decode(data.toString())
                                check(view.id == id) { "云端任务事件身份不匹配" }
                                check(permitted()) { "云端任务账号授权已变更" }
                                apply(view)
                                if (view.terminal) return@execute false
                            }
                            if (event == "reconnect") return@execute false
                            event = ""
                            data.clear()
                        }
                        line.startsWith("event:") -> event = line.substringAfter(':').trimStart()
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.substringAfter(':').removePrefix(" "))
                        }
                    }
                }
                false
            }
            if (!retryAuth) return
            access = access(true)
        }
    }

    private suspend fun access(force: Boolean): String {
        check(permitted()) { "云端任务账号授权已变更" }
        return token(force) ?: throw IOException("登录已失效，云端任务等待重新连接")
    }

    private suspend fun request(path: String, method: HttpMethod = HttpMethod.Get, body: String? = null): String {
        var access = access(false)
        repeat(2) { attempt ->
            check(permitted()) { "云端任务账号授权已变更" }
            val (status, text) = client.prepareRequest("$base/v1/agent/runs$path") {
                this.method = method
                bearerAuth(access)
                if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
            }.execute { it.status.value to it.readTextAtMost(40 * 1024 * 1024L) }
            check(permitted()) { "云端任务账号授权已变更" }
            if (status == 401 && attempt == 0) access = access(true)
            else {
                if (status !in 200..299) throw httpError(status, text)
                return text
            }
        }
        error("云端任务认证失败")
    }

    private fun decode(raw: String): CloudRunView = cloudAgentJson.decodeFromString<CloudRunView>(raw).validate()
    private fun httpError(status: Int, raw: String): CloudAgentHttpException {
        val code = runCatching { cloudAgentJson.parseToJsonElement(raw).jsonObject["error"]?.jsonPrimitive?.content }
            .getOrNull()?.takeIf { it.matches(Regex("[A-Z_]{1,64}")) }
        return CloudAgentHttpException(status, code)
    }
    override fun close() { if (ownsClient) client.close() }

    @Serializable private data class CloudRunList(val runs: List<CloudRunView>)
}
