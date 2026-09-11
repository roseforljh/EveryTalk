package com.android.everytalk.data.computer

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.contentType
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64
import com.android.everytalk.data.network.readTextAtMost

/**
 * 可选的 Temporary Worker 托管网关。
 * Cloudflare 没有未登录、无 Account 直接创建 Worker 的公开 API，因此临时 Worker
 * 必须由外部托管网关完成匿名临时部署和 Claim。此客户端只实现协议边界，不保存
 * Cloudflare Token，也不把网关响应原文交给模型。
 */
class TemporaryWorkerHttpGateway(
    private val httpClient: HttpClient,
    endpoint: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val timeoutMs: Long = 30_000L,
) : TemporaryWorkerGateway {
    private val endpoint = validateEndpoint(endpoint)

    override suspend fun create(sourceWorkspaceId: String, packageData: WorkerPackage): TemporaryWorkerGatewayResult {
        require(sourceWorkspaceId.isNotBlank()) { "Workspace 无效" }
        require(packageData.files.isNotEmpty() && packageData.totalBytes <= MAX_PACKAGE_BYTES) { "临时 Worker 部署包无效" }
        val payload = CreateRequest(
            sourceWorkspaceId = sourceWorkspaceId,
            requestHash = packageData.requestHash,
            entryPoint = packageData.entryPoint,
            bindings = packageData.bindings.map { BindingPart(it.name, it.type, it.resourceId) },
            files = packageData.files.map { file ->
                FilePart(file.relativePath, Base64.getEncoder().encodeToString(file.bytes), file.sha256)
            },
        )
        val result = decodeResult(request("/temporary-workers", json.encodeToString(payload)))
        return TemporaryWorkerGatewayResult(result.temporaryDeploymentId, result.workerUrl, result.claimUrl, result.expiresAt)
    }

    override suspend fun claim(temporaryDeploymentId: String): Boolean {
        requireId(temporaryDeploymentId)
        return decodeClaim(request(
            "/temporary-workers/$temporaryDeploymentId/claim",
            json.encodeToString(ClaimRequest(temporaryDeploymentId)),
        )).claimed
    }

    private suspend fun request(path: String, body: String): String = withTimeout(timeoutMs) {
        httpClient.config { followRedirects = false; expectSuccess = false }.use { client ->
            client.prepareRequest(endpoint + path) {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                header(HttpHeaders.CacheControl, "no-store")
                setBody(body)
            }.execute { response ->
                if (response.status.value !in 200..299) {
                    throw CloudflareApiException("TEMPORARY_GATEWAY_HTTP_${response.status.value}", "Temporary Worker 网关请求失败")
                }
                // 在流式 execute 中限额读取，不能让 Ktor 先缓存整个响应。
                response.readTextAtMost(MAX_RESPONSE_BYTES)
            }
        }
    }

    private fun decodeResult(raw: String): GatewayResult {
        val result = runCatching { json.decodeFromString<GatewayEnvelope<GatewayResult>>(raw) }
            .getOrNull()?.takeIf { it.ok }?.result
            ?: throw CloudflareApiException("TEMPORARY_GATEWAY_RESPONSE_INVALID", "Temporary Worker 创建响应无效")
        requireId(result.temporaryDeploymentId)
        requireHttpsUrl(result.workerUrl)
        requireHttpsUrl(result.claimUrl)
        require(result.expiresAt > System.currentTimeMillis()) { "Temporary Worker 已在返回前过期" }
        return result
    }

    private fun decodeClaim(raw: String): ClaimResult {
        return runCatching { json.decodeFromString<GatewayEnvelope<ClaimResult>>(raw) }
            .getOrNull()?.takeIf { it.ok }?.result
            ?: throw CloudflareApiException("TEMPORARY_GATEWAY_RESPONSE_INVALID", "Temporary Worker Claim 响应无效")
    }

    private fun validateEndpoint(value: String): String {
        val normalized = value.trim().trimEnd('/')
        val url = Url(normalized)
        require(url.protocol.name == "https" && url.user == null && url.password == null && url.fragment.isEmpty() && url.parameters.isEmpty()) {
            "Temporary Worker 网关必须使用无凭据 HTTPS 地址"
        }
        require(url.host.isNotBlank() && url.segments.none { it == ".." }) { "Temporary Worker 网关地址无效" }
        return normalized
    }

    private fun requireHttpsUrl(value: String) {
        val url = Url(value)
        require(url.protocol.name == "https" && url.user == null && url.password == null && url.fragment.isEmpty()) {
            "Temporary Worker 返回地址必须是 HTTPS"
        }
    }

    private fun requireId(value: String) {
        require(value.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Temporary Worker ID 无效" }
    }

    @Serializable
    private data class CreateRequest(
        val sourceWorkspaceId: String,
        val requestHash: String,
        val entryPoint: String,
        val bindings: List<BindingPart>,
        val files: List<FilePart>,
    )

    @Serializable
    private data class BindingPart(val name: String, val type: String, val resourceId: String)

    @Serializable
    /** contentBase64 保证 wasm、图片或其他二进制模块不会被 UTF-8 解码破坏。 */
    private data class FilePart(val path: String, val contentBase64: String, val sha256: String)

    @Serializable
    private data class ClaimRequest(val temporaryDeploymentId: String)

    @Serializable
    private data class GatewayEnvelope<T>(val ok: Boolean = false, val result: T? = null)

    @Serializable
    private data class GatewayResult(val temporaryDeploymentId: String, val workerUrl: String, val claimUrl: String, val expiresAt: Long)

    @Serializable
    private data class ClaimResult(val claimed: Boolean = false)

    private companion object {
        const val MAX_PACKAGE_BYTES = 25L * 1024 * 1024
        const val MAX_RESPONSE_BYTES = 128L * 1024
    }
}
