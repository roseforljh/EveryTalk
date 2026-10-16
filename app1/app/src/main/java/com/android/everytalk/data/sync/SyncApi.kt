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
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.readAvailable
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal interface SyncApi {
    suspend fun push(changes: List<PushChange>): PushResponse
    suspend fun pull(since: Long, limit: Int = 500): PullResponse
    suspend fun deleteSecrets() = Unit
    suspend fun recoverAccountKey(epoch: Long, existingDek: String? = null): AccountSyncKey = throw SyncNotConfiguredException()
    suspend fun recoverHistoryKey(): AccountSyncKey = throw SyncNotConfiguredException()
    suspend fun fileChunkBytes(): Long = 4L * 1024 * 1024
    suspend fun uploadFile(file: File, mime: String): SyncFileInfo = throw SyncNotConfiguredException()
    suspend fun downloadFile(info: SyncFileInfo, target: File) { throw SyncNotConfiguredException() }
}

@Serializable internal data class AccountSyncKey(val version: Long, @SerialName("secrets_epoch") val secretsEpoch: Long, val dek: String)
@Serializable internal data class SyncFileInfo(@SerialName("file_id") val fileId: String, val size: Long, val sha256: String, val mime: String)
@Serializable private data class SyncUploadTicket(val status: String, @SerialName("file_id") val fileId: String,
    @SerialName("upload_id") val uploadId: String? = null)

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

    override suspend fun deleteSecrets() {
        request("/sync/secrets", method = HttpMethod.Delete, includeSecretsEpoch = false)
    }

    override suspend fun recoverAccountKey(epoch: Long, existingDek: String?): AccountSyncKey = json.decodeFromString(request(
        "/sync/account-key", body = kotlinx.serialization.json.buildJsonObject {
            put("secrets_epoch", kotlinx.serialization.json.JsonPrimitive(epoch))
            existingDek?.let { put("existing_dek", kotlinx.serialization.json.JsonPrimitive(it)) }
        }.toString()))
    override suspend fun recoverHistoryKey(): AccountSyncKey = json.decodeFromString(request("/sync/history-key", body = "{}"))
    override suspend fun fileChunkBytes(): Long = json.parseToJsonElement(request("/files/quota")).jsonObject
        .getValue("max_file_bytes").jsonPrimitive.content.toLong().coerceAtMost(4L * 1024 * 1024).also { require(it > 0) }

    override suspend fun uploadFile(file: File, mime: String): SyncFileInfo {
        val size = file.length()
        require(size > 0)
        val hash = syncFileHash(file)
        // 幂等标识由内容稳定导出；杀进程后重新申请能找到同一上传，不重复占配额。
        val idempotency = UUID.nameUUIDFromBytes("$hash:$size:$mime".toByteArray()).toString()
        val ticket: SyncUploadTicket = json.decodeFromString(request("/files/uploads", body = kotlinx.serialization.json.buildJsonObject {
            put("size", kotlinx.serialization.json.JsonPrimitive(size)); put("sha256", kotlinx.serialization.json.JsonPrimitive(hash))
            put("mime", kotlinx.serialization.json.JsonPrimitive(mime))
        }.toString(), idempotency = idempotency))
        require(UUID.fromString(ticket.fileId).toString() == ticket.fileId)
        if (ticket.status == "ready") return SyncFileInfo(ticket.fileId, size, hash, mime)
        require(ticket.status == "upload_required")
        val uploadId = requireNotNull(ticket.uploadId)
        require(UUID.fromString(uploadId).toString() == uploadId)
        val result: SyncFileInfo = json.decodeFromString(request("/files/uploads/$uploadId", method = HttpMethod.Put, upload = file))
        require(result == SyncFileInfo(ticket.fileId, size, hash, mime)) { "文件上传校验失败" }
        return result
    }

    override suspend fun downloadFile(info: SyncFileInfo, target: File) {
        require(UUID.fromString(info.fileId).toString() == info.fileId && info.size > 0 && info.sha256.matches(Regex("[0-9a-f]{64}")))
        val temporary = File(target.parentFile, target.name + ".part")
        target.parentFile?.mkdirs()
        try {
            request("/files/${info.fileId}", download = temporary, expectedSize = info.size)
            require(temporary.length() == info.size && syncFileHash(temporary) == info.sha256) { "文件下载校验失败" }
            check(temporary.renameTo(target)) { "文件缓存写入失败" }
        } finally { temporary.delete() }
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
        idempotency: String? = null,
        upload: File? = null,
        download: File? = null,
        expectedSize: Long = 0,
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
                idempotency?.let { header("Idempotency-Key", it) }
                if (upload != null) {
                    setBody(object : OutgoingContent.WriteChannelContent() {
                        override val contentType = ContentType.Application.OctetStream
                        override val contentLength = upload.length()
                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            upload.inputStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) { val count = input.read(buffer); if (count < 0) break; channel.writeFully(buffer, 0, count) }
                            }
                        }
                    })
                } else if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                } else if (path == "/sync/pull" && this.method == HttpMethod.Get) {
                    parameter("since", since); parameter("limit", limit)
                }
            }.execute { response ->
                if (download != null && response.status.value in 200..299) {
                    val channel = response.bodyAsChannel()
                    download.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024); var total = 0L
                        while (true) {
                            val count = channel.readAvailable(buffer)
                            if (count < 0) break
                            total += count
                            check(total <= expectedSize) { "文件响应超过声明长度" }
                            output.write(buffer, 0, count)
                        }
                        check(total == expectedSize) { "文件下载中断" }
                    }
                    Triple(response.status.value, null, "")
                } else
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

internal fun syncFileHash(file: File): String = java.security.MessageDigest.getInstance("SHA-256").let { digest ->
    file.inputStream().use { input -> val bytes = ByteArray(64 * 1024); while (true) {
        val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count)
    } }
    digest.digest().joinToString("") { "%02x".format(it) }
}

internal val syncJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
internal const val SYNC_VERSION = "0.2.0"

