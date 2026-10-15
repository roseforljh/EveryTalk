package com.android.everytalk.data.sync

import com.android.everytalk.data.account.AccountManager
import com.android.everytalk.data.account.AccountAuthException
import com.android.everytalk.data.network.OwnedServerRequestPurpose
import com.android.everytalk.data.network.ownedServerRequestGuard
import com.android.everytalk.data.network.readTextAtMost
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal interface SyncApi {
    suspend fun push(changes: List<PushChange>): PushResponse
    suspend fun pull(since: Long, limit: Int = 500): PullResponse
    suspend fun deleteAll()
    suspend fun deleteSecrets() = Unit
}

/** 地址缺失或无效时关闭同步；禁止用户信息、查询参数和片段进入服务器根地址。 */
internal fun syncServerConfigured(raw: String): Boolean = runCatching {
    val uri = URI(raw.trim())
    uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
}.getOrDefault(false)

/**
 * 只发送合同 JSON 和当前账号 token；禁用重定向以防凭据发往别的服务器。
 * 每次取 token 和发送请求前复查登录态、账号绑定和云端清空暂停状态。
 * 401 仅强制刷新一次，刷新网络故障继续向外抛出供 WorkManager 重试。
 */
internal class KtorSyncApi(
    baseUrl: String,
    private val accountManager: AccountManager,
    private val isLocalOnly: () -> Boolean,
    private val json: Json = syncJson,
    httpClient: HttpClient? = null,
    private val canRequest: suspend () -> Boolean = { !isLocalOnly() },
    private val readSecretsEpoch: suspend () -> Long = { 0L },
) : SyncApi, AutoCloseable {
    private val base = baseUrl.trim().trimEnd('/')
    private val ownsClient = httpClient == null
    private val client = httpClient ?: HttpClient(OkHttp) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 15_000 }
        install(ownedServerRequestGuard(isLocalOnly, { true }, { OwnedServerRequestPurpose.SYNC }))
    }

    override suspend fun push(changes: List<PushChange>): PushResponse =
        json.decodeFromString(
            request(
                "/sync/push",
                syncJson.encodeToString(PushRequest.serializer(), PushRequest(changes)),
                includeSecretsEpoch = encryptedPush(changes),
            ),
        )

    override suspend fun pull(since: Long, limit: Int): PullResponse {
        require(since >= 0 && limit in 1..500)
        return json.decodeFromString(request("/sync/pull", since = since, limit = limit))
    }

    override suspend fun deleteAll() {
        request("/sync/all", method = HttpMethod.Delete)
    }

    override suspend fun deleteSecrets() {
        request("/sync/secrets", method = HttpMethod.Delete, includeSecretsEpoch = false)
    }

    private suspend fun token(force: Boolean): String = try {
        accountManager.accessTokenForOwnedServer(force) ?: throw SyncRefreshRejectedException("缺少登录会话")
    } catch (error: AccountAuthException) {
        if (error.invalidSession) throw SyncRefreshRejectedException("刷新登录会话被拒绝")
        throw error
    }

    private suspend fun request(
        path: String,
        body: String? = null,
        since: Long = 0,
        limit: Int = 500,
        method: HttpMethod? = null,
        includeSecretsEpoch: Boolean = false,
    ): String {
        if (!syncServerConfigured(base)) throw SyncNotConfiguredException()
        check(canRequest() && !isLocalOnly()) { "同步权限已撤回" }
        var access = token(false)
        repeat(2) { attempt ->
            check(canRequest() && !isLocalOnly()) { "同步权限已撤回" }
            val response = client.prepareRequest(base + path) {
                this.method = method ?: if (body == null) HttpMethod.Get else HttpMethod.Post
                bearerAuth(access)
                header("X-EveryTalk-Sync-Version", SYNC_VERSION)
                if (includeSecretsEpoch) {
                    header("X-EveryTalk-Secrets-Epoch", readSecretsEpoch().toString())
                }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                } else if (this.method == HttpMethod.Get) {
                    parameter("since", since); parameter("limit", limit)
                }
            }.execute { response ->
                Triple(response.status.value, response.headers["Retry-After"]?.toLongOrNull(),
                    response.readTextAtMost(40L * 1024 * 1024))
            }
            if (response.first == 401 && attempt == 0) {
                check(canRequest()) { "同步权限已撤回" }
                access = token(true)
            } else {
                if (response.first in 200..299) return response.third
                val code = runCatching {
                    json.parseToJsonElement(response.third).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
                }.getOrNull()
                throw SyncHttpException(response.first, response.second?.coerceAtLeast(0), code,
                    "同步服务返回 HTTP ${response.first}")
            }
        }
        error("同步请求未完成")
    }

    override fun close() { if (ownsClient) client.close() }

    private fun encryptedPush(changes: List<PushChange>): Boolean = changes.any { change ->
        change.entity == "user_settings" && (change.payload["kind"]?.jsonPrimitive?.content == "crypto_keys" || containsEnvelope(change.payload))
    }

    private fun containsEnvelope(element: JsonElement): Boolean = when (element) {
        is kotlinx.serialization.json.JsonObject -> element.containsKey("\$enc") || element.values.any(::containsEnvelope)
        is kotlinx.serialization.json.JsonArray -> element.any(::containsEnvelope)
        else -> false
    }
}

internal val syncJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
internal const val SYNC_VERSION = "0.2.0"

