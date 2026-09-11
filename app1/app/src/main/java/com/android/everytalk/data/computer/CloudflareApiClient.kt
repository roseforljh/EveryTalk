package com.android.everytalk.data.computer

import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import io.ktor.http.HttpMethod
import io.ktor.client.request.get
import io.ktor.http.Url
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add

/** Cloudflare Account 的最小展示模型。 */
@Serializable
data class CloudflareApiAccount(
    val id: String,
    val name: String,
)

@Serializable
data class CloudflareUserIdentity(
    val id: String? = null,
    val email: String? = null,
    val displayName: String? = null,
)

@Serializable
private data class CloudflareEnvelope<T>(
    val success: Boolean = false,
    val result: T? = null,
    val errors: List<CloudflareErrorItem> = emptyList(),
)

@Serializable
private data class CloudflareErrorItem(val code: Int = 0, val message: String = "")

@Serializable
private data class CloudflareAccountResult(val id: String, val name: String)

/**
 * Cloudflare REST API 的最小客户端。
 * Token 通过 provider 临时传入，客户端自身不缓存凭据；GET 可重试，写操作默认不自动重试。
 */
class CloudflareApiClient(
    private val httpClient: HttpClient,
    private val tokenProvider: suspend () -> String,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val baseUrl: String = "https://api.cloudflare.com/client/v4",
) : AutoCloseable {
    private val transport = CloudflareHttpTransport(httpClient, tokenProvider, baseUrl = baseUrl)
    /** 临时查询沿用相同 HTTP 边界；不可将这个带凭据的短期客户端存入 UI 或持久状态。 */
    internal fun withToken(token: String): CloudflareApiClient =
        CloudflareApiClient(httpClient, { token }, json, baseUrl)

    /** 只返回身份展示所需的白名单字段，不把完整用户响应暴露给模型。 */
    suspend fun currentUser(): CloudflareUserIdentity? {
        val envelope = json.decodeFromString<CloudflareUserEnvelope>(transport.request(HttpMethod.Get, "/user"))
        checkSuccess(envelope.success, envelope.errors)
        val result = envelope.result ?: return null
        val name = listOfNotNull(result.firstName, result.lastName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }
        return CloudflareUserIdentity(result.id, result.email, name)
    }

    /**
     * 探测 Worker 运行时。URL 是工具参数，必须限制为 HTTPS 的公开 Cloudflare 主机；
     * 不发送 Bearer，也不读取响应正文，避免把外部内容引入模型上下文。
     */
    suspend fun probeWorker(url: String): CloudflareWorkerHealth {
        val parsed = Url(url)
        require(parsed.protocol.name == "https" && parsed.user == null && parsed.password == null && parsed.fragment.isEmpty()) { "Worker URL 必须是 HTTPS" }
        require(parsed.host == "workers.dev" || parsed.host.endsWith(".workers.dev") || parsed.host == "cloudflare.com" || parsed.host.endsWith(".cloudflare.com")) { "Worker URL 主机不受信任" }
        val started = System.nanoTime()
        val status = withTimeout(15_000L) {
            httpClient.config { followRedirects = false; expectSuccess = false }.use { safeClient ->
                safeClient.get(url).status.value
            }
        }
        return CloudflareWorkerHealth(
            status = if (status in 200..399) CloudflareWorkerRuntimeStatus.HEALTHY else CloudflareWorkerRuntimeStatus.UNHEALTHY,
            httpStatus = status,
            latencyMs = (System.nanoTime() - started) / 1_000_000L,
        )
    }

    suspend fun listAccounts(): List<CloudflareApiAccount> {
        val accounts = mutableListOf<CloudflareApiAccount>()
        var page = 1
        while (page <= 100) {
            val envelope = json.decodeFromString<CloudflareApiAccountEnvelope>(
                transport.request(HttpMethod.Get, "/accounts", query = mapOf("page" to page.toString(), "per_page" to "100")),
            )
            checkSuccess(envelope.success, envelope.errors)
            accounts += envelope.result.map { CloudflareApiAccount(it.id, it.name) }
            val totalPages = envelope.resultInfo?.totalPages ?: if (envelope.result.size < 100) page else page + 1
            if (page >= totalPages || envelope.result.isEmpty()) break
            page++
        }
        return accounts
    }

    suspend fun listWorkers(accountId: String, page: Int = 1, perPage: Int = 100): CloudflareWorkerListResult {
        requireValidId(accountId, "Account ID")
        require(page in 1..10000 && perPage in 1..1000) { "分页参数无效" }
        val response = transport.request(HttpMethod.Get, "/accounts/$accountId/workers/scripts", query = mapOf("page" to page.toString(), "per_page" to perPage.toString()))
        val envelope = json.decodeFromString<CloudflareWorkerListEnvelope>(response)
        checkSuccess(envelope.success, envelope.errors)
        return CloudflareWorkerListResult(envelope.result, page, perPage, envelope.result.size)
    }

    /** 查询 Account 的 workers.dev 子域名和目标 Worker 是否已启用公开地址。 */
    suspend fun workerUrl(accountId: String, workerName: String): String? {
        requireValidId(accountId, "Account ID")
        requireValidId(workerName, "Worker 名称")
        val account = decodeCloudflareEnvelope(transport.request(HttpMethod.Get, "/accounts/$accountId/workers/subdomain"), json)
        val subdomain = (account["result"] as? kotlinx.serialization.json.JsonObject)
            ?.get("subdomain")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.matches(Regex("[a-z0-9][a-z0-9-]{0,62}[a-z0-9]")) } ?: return null
        val worker = decodeCloudflareEnvelope(transport.request(HttpMethod.Get, "/accounts/$accountId/workers/scripts/$workerName/subdomain"), json)
        val enabled = (worker["result"] as? kotlinx.serialization.json.JsonObject)
            ?.get("enabled")?.jsonPrimitive?.booleanOrNull == true
        return if (enabled) "https://$workerName.$subdomain.workers.dev" else null
    }

    suspend fun downloadWorker(accountId: String, workerName: String): String {
        requireValidId(accountId, "Account ID")
        requireValidId(workerName, "Worker 名称")
        return transport.request(HttpMethod.Get, "/accounts/$accountId/workers/scripts/$workerName")
    }

    suspend fun uploadWorker(accountId: String, workerName: String, script: String): CloudflareDeploymentResult {
        requireValidId(accountId, "Account ID")
        requireValidId(workerName, "Worker 名称")
        // PUT /scripts 返回的 id 是脚本名称，不能作为 deployment ID。
        return deployModuleWorker(accountId, workerName, script)
    }

    /** 模块 Worker 的标准 multipart 上传；metadata 明确声明入口，完整上传所有模块依赖。 */
    suspend fun deployModuleWorker(
        accountId: String,
        workerName: String,
        pack: WorkerPackage,
        onVersionUploaded: suspend (String) -> Unit = {},
    ): CloudflareDeploymentResult {
        requireValidId(accountId, "Account ID"); requireValidId(workerName, "Worker 名称")
        require(pack.files.any { it.relativePath == pack.entryPoint }) { "Worker 入口不能为空" }
        val parts = buildList {
            val metadata = buildJsonObject {
                put("main_module", pack.entryPoint)
                putJsonArray("bindings") {
                    pack.bindings.forEach { binding ->
                        add(buildJsonObject {
                            put("name", binding.name)
                            put("type", binding.type)
                            when (binding.type) {
                                "d1" -> put("database_id", binding.resourceId)
                                "kv_namespace" -> put("namespace_id", binding.resourceId)
                                "r2_bucket" -> put("bucket_name", binding.resourceId)
                                "queue" -> put("queue_name", binding.resourceId)
                                "durable_object_namespace" -> put("namespace_id", binding.resourceId)
                            }
                        })
                    }
                }
            }
            add(CloudflareMultipartPart("metadata", metadata.toString().toByteArray(), "application/json"))
            pack.files.forEach { file ->
                val contentType = when (file.relativePath.substringAfterLast('.')) {
                    "js", "mjs" -> "application/javascript+module"
                    "wasm" -> "application/wasm"
                    "txt", "html", "css", "json" -> "text/plain"
                    else -> "application/octet-stream"
                }
                add(CloudflareMultipartPart(file.relativePath, file.bytes, contentType, file.relativePath))
            }
        }
        // versions 接口只对已存在的脚本有效：新名字直接 POST 会 404，建不出 Worker。
        // 所以先确认脚本存在，不存在就用 PUT /scripts 建出来，再走后面的版本与部署流程。
        if (!workerScriptExists(accountId, workerName)) {
            transport.requestMultipart(
                HttpMethod.Put,
                "/accounts/$accountId/workers/scripts/$workerName",
                parts,
            )
        }
        // 先上传不可见版本，再创建正式 deployment，才能获得可恢复查询的 UUID。
        val uploadResponse = transport.requestMultipart(
            HttpMethod.Post,
            "/accounts/$accountId/workers/scripts/$workerName/versions",
            parts,
        )
        val uploadEnvelope = json.decodeFromString<CloudflareWorkerVersionUploadEnvelope>(uploadResponse)
        checkSuccess(uploadEnvelope.success, uploadEnvelope.errors)
        val versionId = uploadEnvelope.result?.id?.takeIf { it.isNotBlank() }
            ?: throw CloudflareApiException("RESPONSE_INVALID", "Cloudflare 未返回 Worker 版本 ID")
        // 创建 deployment 之前先让账本保存 versionId。保存失败就停止后续写请求，
        // 这样进程被回收时仍可通过已知版本查询，而不用再上传一次。
        onVersionUploaded(versionId)

        val deploymentBody = buildJsonObject {
            put("strategy", "percentage")
            putJsonArray("versions") {
                add(buildJsonObject {
                    put("percentage", 100)
                    put("version_id", versionId)
                })
            }
            putJsonObject("annotations") {
                put("workers/message", "EveryTalk deployment")
                put("workers/triggered_by", "everytalk")
            }
        }.toString()
        val deploymentResponse = try {
            transport.request(
                HttpMethod.Post,
                "/accounts/$accountId/workers/scripts/$workerName/deployments",
                deploymentBody,
                ContentType.Application.Json,
            )
        } catch (error: CloudflareApiException) {
            // 上传成功而部署结果未知时，把已知 versionId 带给上层账本。
            throw error.withVersion(versionId)
        }
        val deploymentEnvelope = try { json.decodeFromString<CloudflareDeploymentEnvelope>(deploymentResponse) }
        catch (_: IllegalArgumentException) {
            throw CloudflareApiException("RESULT_UNKNOWN", "部署响应未完整解析，需要查询确认", versionId = versionId)
        }
        checkSuccess(deploymentEnvelope.success, deploymentEnvelope.errors)
        val deploymentId = deploymentEnvelope.result?.id?.takeIf { it.isNotBlank() }
            ?: throw CloudflareApiException("RESPONSE_INVALID", "Cloudflare 未返回 deployment ID", versionId = versionId)
        return CloudflareDeploymentResult(workerName, deploymentId, CloudflareDeploymentStatus.REQUEST_ACCEPTED, versionId, null)
    }

    /** 脚本是否已存在；只把 404 当成“不存在”，其它错误照常抛出。 */
    private suspend fun workerScriptExists(accountId: String, workerName: String): Boolean = try {
        transport.request(HttpMethod.Get, "/accounts/$accountId/workers/scripts/$workerName")
        true
    } catch (error: CloudflareApiException) {
        if (error.code == "RESOURCE_NOT_FOUND") false else throw error
    }

    /** 兼容 Agent 直接传入单文件源码的调用；正式 Workspace 部署走上面的完整包接口。 */
    suspend fun deployModuleWorker(accountId: String, workerName: String, script: String): CloudflareDeploymentResult =
        deployModuleWorker(accountId, workerName, WorkerPackage(
            files = listOf(WorkerPackageFile("worker.js", script.toByteArray(Charsets.UTF_8), "")),
            requestHash = "direct",
            totalBytes = script.toByteArray(Charsets.UTF_8).size.toLong(),
        ))

    suspend fun workerSettings(accountId: String, workerName: String): String =
        transport.request(HttpMethod.Get, "/accounts/${accountId.also { requireValidId(it, "Account ID") }}/workers/scripts/${workerName.also { requireValidId(it, "Worker 名称") }}/settings")

    /** 返回部署历史的安全 JSON；调用方只展示有限长度摘要，不把原始响应直接交给模型。 */
    suspend fun workerDeployments(accountId: String, workerName: String): kotlinx.serialization.json.JsonObject {
        requireValidId(accountId, "Account ID")
        requireValidId(workerName, "Worker 名称")
        return decodeCloudflareEnvelope(
            transport.request(HttpMethod.Get, "/accounts/$accountId/workers/scripts/$workerName/deployments"),
            json,
        )
    }

    /**
     * deployment 创建结果未知时，使用已知 versionId 查询历史。
     * 版本上传成功后不会因为网络异常再次上传；找到匹配版本即可证明远端 deployment 已存在。
     */
    suspend fun findDeploymentByVersion(
        accountId: String,
        workerName: String,
        versionId: String,
    ): CloudflareDeploymentLookup? {
        require(versionId.isNotBlank()) { "版本 ID 不能为空" }
        val envelope = workerDeployments(accountId, workerName)
        val deployments = envelope["result"] as? kotlinx.serialization.json.JsonArray ?: return null
        return deployments.asSequence().mapNotNull { item ->
            val deployment = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val versions = deployment["versions"] as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
            val matches = versions.any { version ->
                (version as? kotlinx.serialization.json.JsonObject)?.get("version_id")?.jsonPrimitive?.contentOrNull == versionId
            }
            if (!matches) return@mapNotNull null
            val deploymentId = deployment["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            CloudflareDeploymentLookup(deploymentId, CloudflareDeploymentStatus.DEPLOYMENT_SUCCEEDED)
        }.firstOrNull()
    }

    /**
     * 启动一次 Tail 会话并读取限定数量的实时日志。
     * GET /tails 只会列出会话，不能当作日志正文，因此这里走 POST + WebSocket。
     */
    suspend fun workerTailLogs(
        accountId: String,
        workerName: String,
        maxLines: Int = 200,
        maxChars: Int = 32_000,
        timeoutMs: Long = 10_000,
    ): String {
        requireValidId(accountId, "Account ID")
        requireValidId(workerName, "Worker 名称")
        require(maxLines in 1..2_000 && maxChars in 1..200_000 && timeoutMs in 500..60_000) { "日志限制无效" }
        val path = "/accounts/$accountId/workers/scripts/$workerName/tails"
        val startRaw = transport.request(HttpMethod.Post, path)
        val start = json.decodeFromString<CloudflareTailEnvelope>(startRaw)
        checkSuccess(start.success, start.errors)
        val tail = start.result ?: throw CloudflareApiException("RESPONSE_INVALID", "Cloudflare 未返回 Tail 会话")
        return try {
            sanitizeTailLogs(transport.readTailWebSocket(tail.url, maxLines, maxChars, timeoutMs), maxChars)
        } finally {
            runCatching { transport.request(HttpMethod.Delete, "$path/${tail.id}") }
        }
    }

    /** 查询真实 deployment；成功响应代表该 deployment 已被 Cloudflare 接受并可对账。 */
    suspend fun workerDeploymentStatus(accountId: String, workerName: String, deploymentId: String): CloudflareDeploymentStatus {
        requireValidId(accountId, "Account ID"); requireValidId(workerName, "Worker 名称"); requireValidId(deploymentId, "部署 ID")
        val raw = transport.request(HttpMethod.Get, "/accounts/$accountId/workers/scripts/$workerName/deployments/$deploymentId")
        val envelope = json.decodeFromString<CloudflareDeploymentEnvelope>(raw)
        checkSuccess(envelope.success, envelope.errors)
        val remoteId = envelope.result?.id
        if (remoteId == null) return CloudflareDeploymentStatus.DEPLOYMENT_PENDING
        if (remoteId != deploymentId) throw CloudflareApiException("RESPONSE_INVALID", "部署查询返回的 ID 不一致")
        return CloudflareDeploymentStatus.DEPLOYMENT_SUCCEEDED
    }

    suspend fun deleteWorker(accountId: String, workerName: String) {
        requireValidId(accountId, "Account ID")
        requireValidId(workerName, "Worker 名称")
        decodeCloudflareEnvelope(transport.request(HttpMethod.Delete, "/accounts/$accountId/workers/scripts/$workerName"), json)
    }

    private fun requireValidId(value: String, label: String) {
        require(value.isNotBlank() && value.length <= 128 && value.all { it.isLetterOrDigit() || it in "-_" }) {
            "$label 无效"
        }
    }

    private fun checkSuccess(success: Boolean, errors: List<CloudflareErrorItem> = emptyList()) {
        if (!success) {
            val detail = errors.firstOrNull()?.let { "（${it.code}: ${it.message.take(500)}）" }.orEmpty()
            throw CloudflareApiException("CLOUDFLARE_API_ERROR", "Cloudflare API 操作失败$detail")
        }
    }

    override fun close() = transport.close()

    /** Tail 日志是外部不可信输入，先做统一脱敏再交给 Provider 二次限长。 */
    private fun sanitizeTailLogs(value: String, maxChars: Int): String = value
        .replace(Regex("-----BEGIN [^-]+-----[\\s\\S]*?-----END [^-]+-----"), "[REDACTED_KEY]")
        .replace(Regex("(?i)(authorization|cookie|token|secret|password|api[_ -]?key)\\s*[:=]\\s*[^\\s,;}]+"), "$1=[REDACTED]")
        .take(maxChars)
}

@Serializable
private data class CloudflareApiAccountEnvelope(
    val success: Boolean = false,
    val result: List<CloudflareAccountResult> = emptyList(),
    val errors: List<CloudflareErrorItem> = emptyList(),
    @kotlinx.serialization.SerialName("result_info") val resultInfo: CloudflareResultInfo? = null,
)

@Serializable
private data class CloudflareUserEnvelope(
    val success: Boolean = false,
    val result: CloudflareUserResult? = null,
    val errors: List<CloudflareErrorItem> = emptyList(),
)

@Serializable
private data class CloudflareUserResult(
    val id: String? = null,
    val email: String? = null,
    @kotlinx.serialization.SerialName("first_name") val firstName: String? = null,
    @kotlinx.serialization.SerialName("last_name") val lastName: String? = null,
)

@Serializable
private data class CloudflareResultInfo(@kotlinx.serialization.SerialName("total_pages") val totalPages: Int = 1)

@Serializable
private data class CloudflareWorkerListEnvelope(
    val success: Boolean = false,
    val result: List<CloudflareWorkerSummary> = emptyList(),
    val errors: List<CloudflareErrorItem> = emptyList(),
)

@Serializable
private data class CloudflareWorkerVersionUploadResult(val id: String? = null)

@Serializable
private data class CloudflareWorkerVersionUploadEnvelope(
    val success: Boolean = false,
    val result: CloudflareWorkerVersionUploadResult? = null,
    val errors: List<CloudflareErrorItem> = emptyList(),
)

@Serializable
private data class CloudflareDeploymentEnvelope(
    val success: Boolean = false,
    val result: CloudflareDeploymentApiResult? = null,
    val errors: List<CloudflareErrorItem> = emptyList(),
)

@Serializable
private data class CloudflareDeploymentApiResult(
    val id: String? = null,
    val versions: List<CloudflareDeploymentVersion> = emptyList(),
)

@Serializable
private data class CloudflareDeploymentVersion(
    @kotlinx.serialization.SerialName("version_id") val versionId: String? = null,
)

@Serializable
private data class CloudflareTailEnvelope(
    val success: Boolean = false,
    val result: CloudflareTailResult? = null,
    val errors: List<CloudflareErrorItem> = emptyList(),
)

@Serializable
private data class CloudflareTailResult(
    val id: String,
    val url: String,
    @kotlinx.serialization.SerialName("expires_at") val expiresAt: String,
)

class CloudflareApiException(
    val code: String,
    override val message: String,
    val retryable: Boolean = false,
    val versionId: String? = null,
) : Exception(message)

private fun CloudflareApiException.withVersion(versionId: String): CloudflareApiException =
    CloudflareApiException(code, message, retryable, versionId)
