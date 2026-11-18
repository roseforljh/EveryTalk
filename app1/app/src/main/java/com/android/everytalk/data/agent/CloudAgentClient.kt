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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
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
    /** 模型已经答完、只剩后台存档：界面按已完成显示，不再转圈。 */
    val answered: Boolean get() = terminal || (snapshot.finishedAt != null && snapshot.waiting == null)

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
    val modelName: String? = null,
    val providerName: String? = null,
    val usage: CloudRunUsage? = null,
    /** 首发设备冻结的上下文用量；其他设备没有本机快照时用它建立圆环。 */
    val contextUsage: com.android.everytalk.data.DataClass.ContextUsageSnapshot? = null,
    /** Gemini 原生搜索（grounding）的来源。 */
    val sources: List<CloudRunSource> = emptyList(),
)

@kotlinx.serialization.Serializable
internal data class CloudRunSource(val title: String, val url: String, val snippet: String = "")

/** 最近一次模型请求的真实用量；input 不含缓存，和 Pi 的计数口径一致。 */
@Serializable
internal data class CloudRunUsage(
    val api: String,
    val input: Long,
    val output: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val totalTokens: Long,
)

@Serializable
internal data class CloudRunTrace(
    val kind: String,
    val startedAt: Long,
    val text: String = "",
    val id: String = "",
    val name: String = "",
    val arguments: JsonObject? = null,
    /** 缺失为旧服务端已开始；新版工具声明占位明确为 false。 */
    val started: Boolean = true,
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

/** 只认本同步服务器、路径为 /v1/agent/previews/<UUID>/… 的链接，取出预览 token。 */
internal fun previewToken(base: String, url: String): String? {
    val prefix = base.trimEnd('/') + "/v1/agent/previews/"
    if (!url.startsWith(prefix)) return null
    val token = url.removePrefix(prefix).substringBefore('/').substringBefore('?').substringBefore('#')
    return token.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
}

/** 不保留或展示服务端原始错误正文，它可能含上游凭据；仅带受控状态码和错误标识。 */
internal class CloudAgentHttpException(val status: Int, val code: String?) : IOException("云端任务请求失败（HTTP $status${code?.let { ", $it" }.orEmpty()}）")

/**
 * 当前登录账号的云端客户端，退出或换号时清空。给打开私有预览、登录后的 OAuth 续期这类需要账号鉴权的小请求用；
 * 为 null 就是未登录，调用方照旧走本机逻辑。
 */
internal object CloudAccountClient {
    @Volatile var current: CloudAgentClient? = null
}

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

    /** 服务器声明支持的能力；只有旧后端没有这个入口（404）时返回空集按旧绑定回退，断网等错误照常抛出、下次再问。 */
    suspend fun capabilities(): Set<String> = try {
        val root = cloudAgentJson.parseToJsonElement(call("/v1/agent/capabilities")).jsonObject
        root["features"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty().toSet()
    } catch (error: CloudAgentHttpException) {
        if (error.status == 404) emptySet() else throw error
    }

    /**
     * 私有预览链接不是凭据：用账号换一个一次性进入码，返回带码的完整地址交给浏览器。
     * 不是本服务器的预览链接返回 null，调用方按普通链接打开。
     */
    suspend fun previewEntry(url: String): String? {
        val token = previewToken(base, url) ?: return null
        val path = cloudAgentJson.parseToJsonElement(call("/v1/agent/control/previews/$token/session", HttpMethod.Post, "{}"))
            .jsonObject["url"]?.jsonPrimitive?.content ?: return null
        require(path.startsWith("/v1/agent/previews/$token/")) { "预览地址无效" }
        return base + path
    }

    /** 登录后 MCP OAuth 续期交给服务器：同一把锁、写回账号同步，返回与本机格式相同的授权 JSON。 */
    suspend fun refreshMcp(provider: String, current: String, force: Boolean): String =
        cloudAgentJson.parseToJsonElement(call("/v1/agent/credentials/refresh", HttpMethod.Post, buildJsonObject {
            put("kind", "mcp"); put("provider", provider); put("current", current); put("force", force)
        }.toString())).jsonObject.getValue("secret").jsonPrimitive.content

    /** 登录后 Cloudflare 续期交给服务器，返回新的令牌材料（access/refresh JSON）与到期时间。 */
    suspend fun refreshCloudflare(computerId: String, clientId: String, current: String, force: Boolean): Pair<String, Long?> {
        val root = cloudAgentJson.parseToJsonElement(call("/v1/agent/credentials/refresh", HttpMethod.Post, buildJsonObject {
            put("kind", "cloudflare"); put("computerId", computerId); put("clientId", clientId); put("current", current); put("force", force)
        }.toString())).jsonObject
        return root.getValue("material").jsonPrimitive.content to root["expiresAt"]?.jsonPrimitive?.longOrNull
    }

    /** 受保护 Secret 走独立入口：正文只在这一次请求里出现，不进命令回放，也不进历史。 */
    suspend fun secret(id: String, command: JsonObject): CloudRunView {
        requireCloudUuid(id)
        requireCloudUuid(command.getValue("commandId").jsonPrimitive.content)
        return decode(request("/$id/secret", HttpMethod.Post, command.toString())).also { check(it.id == id) { "云端任务响应身份不匹配" } }
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
        return token(force) ?: throw IOException("登录已失效，请重新登录以查看云端进度")
    }

    private suspend fun request(path: String, method: HttpMethod = HttpMethod.Get, body: String? = null): String =
        call("/v1/agent/runs$path", method, body)

    private suspend fun call(path: String, method: HttpMethod = HttpMethod.Get, body: String? = null): String {
        var access = access(false)
        repeat(2) { attempt ->
            check(permitted()) { "云端任务账号授权已变更" }
            val (status, text) = client.prepareRequest("$base$path") {
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
        // 后端错误体是 {"error":{"code":"..."}}；读不到 code 会把“未创建”误判成其它 404，任务永远提交不上去。
        val code = runCatching { cloudAgentJson.parseToJsonElement(raw).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content }
            .getOrNull()?.takeIf { it.matches(Regex("[A-Z_]{1,64}")) }
        return CloudAgentHttpException(status, code)
    }
    override fun close() { if (ownsClient) client.close() }

    @Serializable private data class CloudRunList(val runs: List<CloudRunView>)
}
