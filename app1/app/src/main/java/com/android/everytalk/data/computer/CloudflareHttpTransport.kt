package com.android.everytalk.data.computer

import com.android.everytalk.data.network.ResponseBodyTooLargeException
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Headers
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.content.ByteArrayContent
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.plugins.websocket.webSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import com.android.everytalk.data.network.readTextAtMost
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import io.ktor.http.URLProtocol
import io.ktor.utils.io.cancel

/**
 * Cloudflare 请求的公共边界。流式读取发生在 execute 回调内，Ktor 不提前缓存整份响应。
 * 仅 GET 自动重试；写请求发出后的网络失败保留 RESULT_UNKNOWN，由部署/资源账本对账。
 * 禁止跟随重定向，防止 Bearer 凭据被带到另一个主机。
 */
internal class CloudflareHttpTransport(
    httpClient: HttpClient,
    private val tokenProvider: suspend () -> String,
    private val baseUrl: String = "https://api.cloudflare.com/client/v4",
    private val timeoutMs: Long = 30_000,
    private val maxResponseBytes: Long = 4L * 1024 * 1024,
) : AutoCloseable {
    private val client = httpClient.config { followRedirects = false; expectSuccess = false }

    init {
        val base = Url(baseUrl)
        require(base.protocol.name == "https" && base.user == null && base.password == null &&
            base.fragment.isEmpty() && base.parameters.isEmpty()) { "Cloudflare API 地址无效" }
        require(timeoutMs > 0 && maxResponseBytes in 1..Int.MAX_VALUE.toLong())
    }

    suspend fun request(
        method: HttpMethod,
        path: String,
        body: String? = null,
        contentType: ContentType = ContentType.Application.Json,
        query: Map<String, String> = emptyMap(),
    ): String {
        validatePath(path)
        val readOnly = method == HttpMethod.Get
        repeat(if (readOnly) 3 else 1) { attempt ->
            // 获取凭据失败属于未发送；不得被写请求的 UNKNOWN 分支吞掉。
            val token = tokenProvider().takeIf { it.isNotBlank() && '\r' !in it && '\n' !in it }
                ?: throw CloudflareApiException("AUTHORIZATION_REQUIRED", "缺少 Cloudflare 授权")
            try {
                val responseText = withTimeout(timeoutMs) {
                    client.prepareRequest(baseUrl.trimEnd('/') + path) {
                        this.method = method
                        header(HttpHeaders.Authorization, "Bearer $token")
                        url { query.forEach { (key, value) -> parameters.append(key, value) } }
                        if (body != null) { contentType(contentType); setBody(body) }
                    }.execute { response ->
                        val status = response.status.value
                        if (status !in 200..299) throw statusError(status, response.readTextAtMost(maxResponseBytes), readOnly)
                        response.readTextAtMost(maxResponseBytes)
                    }
                }
                return responseText
            } catch (error: TimeoutCancellationException) {
                if (!readOnly || attempt == 2) {
                    throw CloudflareApiException(if (readOnly) "REQUEST_TIMEOUT" else "RESULT_UNKNOWN", "Cloudflare 请求超时", readOnly)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ResponseBodyTooLargeException) {
                throw CloudflareApiException(if (readOnly) "RESPONSE_TOO_LARGE" else "RESULT_UNKNOWN", "Cloudflare 响应超过限制")
            } catch (error: CloudflareApiException) {
                if (!readOnly || !error.retryable || attempt == 2) throw error
            } catch (error: IOException) {
                if (!readOnly || attempt == 2) throw CloudflareApiException(
                    if (readOnly) "NETWORK_ERROR" else "RESULT_UNKNOWN", "Cloudflare 网络请求未得到完整结果", readOnly,
                )
            }
            delay(250L * (attempt + 1))
        }
        error("不可达的 Cloudflare 重试状态")
    }

    /**
     * 发送 multipart Worker 包。multipart 也复用同一 token、超时、重定向和响应大小边界；
     * 写请求不自动重试，网络结果未知时由上层部署账本负责对账。
     */
    suspend fun requestMultipart(
        method: HttpMethod,
        path: String,
        parts: List<CloudflareMultipartPart>,
    ): String {
        validatePath(path)
        require(method == HttpMethod.Put || method == HttpMethod.Post) { "multipart 方法无效" }
        val token = tokenProvider().takeIf { it.isNotBlank() && '\r' !in it && '\n' !in it }
            ?: throw CloudflareApiException("AUTHORIZATION_REQUIRED", "缺少 Cloudflare 授权")
        return try {
            withTimeout(timeoutMs) {
                client.prepareRequest(baseUrl.trimEnd('/') + path) {
                    this.method = method
                    header(HttpHeaders.Authorization, "Bearer $token")
                    setBody(MultiPartFormDataContent(formData {
                        parts.forEach { part ->
                            append(part.name, part.bytes, Headers.build {
                                append(HttpHeaders.ContentType, part.contentType)
                                part.filename?.let {
                                    require(it.isNotBlank() && '\r' !in it && '\n' !in it && '"' !in it) { "multipart 文件名无效" }
                                    append(HttpHeaders.ContentDisposition, "filename=\"$it\"")
                                }
                            })
                        }
                    }))
                }.execute { response ->
                    if (response.status.value !in 200..299) throw statusError(response.status.value, response.readTextAtMost(maxResponseBytes), readOnly = false)
                    response.readTextAtMost(maxResponseBytes)
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw CloudflareApiException("RESULT_UNKNOWN", "Cloudflare multipart 请求超时")
        } catch (error: CancellationException) {
            throw error
        } catch (error: ResponseBodyTooLargeException) {
            throw CloudflareApiException("RESULT_UNKNOWN", "Cloudflare 响应超过限制")
        } catch (error: CloudflareApiException) {
            throw error
        } catch (error: IOException) {
            throw CloudflareApiException("RESULT_UNKNOWN", "Cloudflare multipart 请求未得到完整结果")
        }
    }

    /** HEAD 只返回对象元数据，避免将 R2 二进制内容读入内存或发送给模型。 */
    /**
     * R2 的对象接口通过 GET 返回正文和元数据；这里用流式 GET 只取响应头，
     * 立即取消正文通道，避免把对象内容读入内存。不能假设 Cloudflare API 的
     * R2 路径一定支持 HEAD。
     */
    suspend fun objectHeaders(path: String): Headers {
        validatePath(path)
        val token = tokenProvider().takeIf { it.isNotBlank() && '\r' !in it && '\n' !in it }
            ?: throw CloudflareApiException("AUTHORIZATION_REQUIRED", "缺少 Cloudflare 授权")
        return try {
            withTimeout(timeoutMs) {
                client.prepareRequest(baseUrl.trimEnd('/') + path) {
                    method = HttpMethod.Get
                    header(HttpHeaders.Authorization, "Bearer $token")
                }.execute { response ->
                    if (response.status.value !in 200..299) throw statusError(response.status.value, response.readTextAtMost(maxResponseBytes), readOnly = true)
                    response.headers.also { response.bodyAsChannel().cancel() }
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw CloudflareApiException("REQUEST_TIMEOUT", "Cloudflare 请求超时", true)
        }
    }

    suspend fun requestBytes(method: HttpMethod, path: String, bytes: ByteArray, contentType: ContentType) : String {
        validatePath(path)
        val token = tokenProvider().takeIf { it.isNotBlank() && '\r' !in it && '\n' !in it }
            ?: throw CloudflareApiException("AUTHORIZATION_REQUIRED", "缺少 Cloudflare 授权")
        return try {
            withTimeout(timeoutMs) {
                client.prepareRequest(baseUrl.trimEnd('/') + path) {
                    this.method = method
                    header(HttpHeaders.Authorization, "Bearer $token")
                    setBody(ByteArrayContent(bytes, contentType))
                }.execute { response ->
                    if (response.status.value !in 200..299) throw statusError(response.status.value, response.readTextAtMost(maxResponseBytes), readOnly = false)
                    response.readTextAtMost(maxResponseBytes)
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw CloudflareApiException("RESULT_UNKNOWN", "Cloudflare 请求超时")
        }
    }

    /**
     * 读取 Cloudflare Tail 返回的 WebSocket 日志。
     * Tail URL 是 Cloudflare 生成的短期地址，因此只允许 wss/https 和 Cloudflare 域名，
     * 防止外部响应把带日志读取能力的客户端引向任意内网地址。
     */
    suspend fun readTailWebSocket(url: String, maxLines: Int, maxChars: Int, timeoutMs: Long): String {
        val parsed = Url(url)
        require(parsed.protocol == URLProtocol.WSS || parsed.protocol == URLProtocol.HTTPS) { "Tail 地址协议无效" }
        require(parsed.user == null && parsed.password == null && parsed.fragment.isEmpty() && parsed.host.isNotBlank()) { "Tail 地址无效" }
        require(parsed.host == "cloudflare.com" || parsed.host.endsWith(".cloudflare.com") ||
            parsed.host == "workers.dev" || parsed.host.endsWith(".workers.dev")) { "Tail 地址主机无效" }
        val socketUrl = if (parsed.protocol == URLProtocol.HTTPS) url.replaceFirst("https://", "wss://") else url
        return try {
            withTimeout(timeoutMs) {
                val output = StringBuilder()
                var lines = 0
                client.webSocket(urlString = socketUrl) {
                    for (frame in incoming) {
                        val text = when (frame) {
                            is Frame.Text -> frame.readText()
                            is Frame.Binary -> frame.readBytes().toString(Charsets.UTF_8)
                            else -> continue
                        }
                        if (text.isBlank()) continue
                        val remaining = maxChars - output.length
                        if (remaining <= 0) break
                        val bounded = text.take(remaining)
                        output.append(bounded)
                        if (!bounded.endsWith('\n')) output.append('\n')
                        lines += bounded.count { it == '\n' }.coerceAtLeast(1)
                        if (lines >= maxLines || output.length >= maxChars) break
                    }
                }
                output.toString().take(maxChars)
            }
        } catch (error: TimeoutCancellationException) {
            throw CloudflareApiException("REQUEST_TIMEOUT", "Worker Tail 日志读取超时", retryable = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: CloudflareApiException) {
            throw error
        } catch (_: Throwable) {
            throw CloudflareApiException("NETWORK_ERROR", "Worker Tail 日志读取失败", retryable = true)
        }
    }

    private fun statusError(status: Int, responseBody: String, readOnly: Boolean): CloudflareApiException {
        val code = when (status) {
            401 -> "AUTHORIZATION_REQUIRED"
            403 -> "PERMISSION_DENIED"
            404 -> "RESOURCE_NOT_FOUND"
            408 -> if (readOnly) "REQUEST_TIMEOUT" else "RESULT_UNKNOWN"
            429 -> "RATE_LIMITED"
            in 300..399 -> "REDIRECT_REJECTED"
            in 500..599 -> if (readOnly) "SERVICE_UNAVAILABLE" else "RESULT_UNKNOWN"
            else -> "HTTP_$status"
        }
        val detail = runCatching {
            val errors = Json.parseToJsonElement(responseBody).jsonObject["errors"] as? kotlinx.serialization.json.JsonArray
            val first = errors?.firstOrNull()?.jsonObject
            val apiCode = first?.get("code")?.jsonPrimitive?.contentOrNull
            val apiMessage = first?.get("message")?.jsonPrimitive?.contentOrNull
            listOfNotNull(apiCode, apiMessage?.take(500)).joinToString(": ").takeIf { it.isNotBlank() }
        }.getOrNull()
        val message = buildString {
            append("Cloudflare API 请求失败（$status）")
            if (detail != null) append("：").append(detail)
        }
        return CloudflareApiException(code, message, readOnly && status in setOf(408, 429, 500, 502, 503, 504))
    }

    override fun close() = client.close()

    private fun validatePath(path: String) {
        // 身份接口是固定的只读路径，不能被通用 accounts 路径规则误拦截。
        if (path == "/user") return
        val segments = path.split('/')
        require(segments.firstOrNull().isNullOrEmpty() && segments.getOrNull(1) == "accounts" &&
            path.length <= 2048 && !path.contains('?') && !path.contains('#') && !path.contains('\\') &&
            segments.none { it == "." || it == ".." || '\r' in it || '\n' in it }) { "Cloudflare API 路径无效" }
    }
}

internal data class CloudflareMultipartPart(
    val name: String,
    val bytes: ByteArray,
    val contentType: String,
    val filename: String? = null,
)

/** HTTP 成功还需检查业务 success；外部错误正文可能含 Token，不能作为异常文本透传。 */
internal fun decodeCloudflareEnvelope(raw: String, json: Json): JsonObject {
    val payload = try { json.parseToJsonElement(raw) as? JsonObject }
    catch (_: IllegalArgumentException) { null }
        ?: throw CloudflareApiException("RESPONSE_INVALID", "Cloudflare 响应格式无效")
    when ((payload["success"] as? JsonPrimitive)?.booleanOrNull) {
        true -> return payload
        false -> throw CloudflareApiException("CLOUDFLARE_API_ERROR", "Cloudflare 操作未成功")
        null -> throw CloudflareApiException("RESPONSE_INVALID", "Cloudflare 响应缺少成功状态")
    }
}
