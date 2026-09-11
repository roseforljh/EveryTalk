package com.android.everytalk.data.computer

import android.net.Uri
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.prepareRequest
import io.ktor.http.HttpMethod
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.client.request.setBody
import com.android.everytalk.data.network.readTextAtMost
import kotlinx.serialization.json.Json
import com.android.everytalk.data.agent.AgentOAuthStore
import com.android.everytalk.data.agent.ClaimedOAuthCallback
import com.android.everytalk.data.agent.OAuthAuthorizationRequest
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.withTimeout

/** Cloudflare OAuth 的固定配置，不包含 Client Secret。 */
data class CloudflareOAuthConfig(
    val clientId: String,
    val redirectUri: String,
    val authorizationEndpoint: String = "https://dash.cloudflare.com/oauth2/auth",
    val tokenEndpoint: String = "https://dash.cloudflare.com/oauth2/token",
    val scopes: Set<String> = setOf("account:read", "workers:read", "workers:write"),
)

data class CloudflareTokenExchangeResult(
    val accessToken: CharArray,
    val refreshToken: CharArray?,
    val scope: Set<String>,
    val expiresInSeconds: Long?,
)

data class CloudflareOAuthStart(
    val authorizationUrl: String,
    val request: OAuthAuthorizationRequest,
)

/**
 * Cloudflare OAuth 适配层。
 * state、PKCE verifier 和一次性消费由现有 AgentOAuthStore 管理，本类只组装协议参数。
 */
class CloudflareOAuthClient(
    private val store: AgentOAuthStore,
    private val config: CloudflareOAuthConfig,
    private val httpClient: HttpClient? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun start(
        runId: String,
        runGeneration: Long,
        targetBinding: String,
        verifierGeneration: Long,
        ttlMillis: Long = 10 * 60 * 1000L,
    ): CloudflareOAuthStart {
        config.requireValid()
        val request = store.create(
            runId = runId,
            runGeneration = runGeneration,
            capability = "cloudflare.oauth",
            targetBinding = targetBinding,
            clientId = config.clientId,
            redirectUri = config.redirectUri,
            verifierGeneration = verifierGeneration,
            ttlMillis = ttlMillis,
        )
        val params = linkedMapOf(
            "client_id" to config.clientId,
            "redirect_uri" to config.redirectUri,
            "response_type" to "code",
            "scope" to config.scopes.sorted().joinToString(" "),
            "state" to request.state,
            "nonce" to (request.nonce ?: request.state),
            "code_challenge" to request.codeChallenge,
            "code_challenge_method" to request.codeChallengeMethod,
        )
        return CloudflareOAuthStart(
            authorizationUrl = config.authorizationEndpoint + "?" + params.entries.joinToString("&") {
                "${encode(it.key)}=${encode(it.value)}"
            },
            request = request,
        )
    }

    /** 只提取和校验回调中的 code/state；Token exchange 由后续安全适配器执行。 */
    fun parseCallback(uri: Uri): CloudflareOAuthCallback =
        parseCloudflareOAuthCallback(uri.toString(), config.redirectUri)

    suspend fun claimCallback(
        callback: CloudflareOAuthCallback,
        targetBinding: String,
        verifierGeneration: Long,
    ): ClaimedOAuthCallback? = store.claimCallback(
        state = callback.state,
        expectedCapability = "cloudflare.oauth",
        expectedTargetBinding = targetBinding,
        clientId = config.clientId,
        redirectUri = config.redirectUri,
        verifierGeneration = verifierGeneration,
    )

    /** 用一次性授权码换取 Token；返回的 CharArray 由调用方写入安全存储后清零。 */
    suspend fun exchangeCode(callback: CloudflareOAuthCallback, codeVerifier: CharArray): CloudflareTokenExchangeResult {
        val client = httpClient ?: throw CloudflareOAuthException("OAUTH_CLIENT_UNAVAILABLE", "Cloudflare OAuth 网络客户端未配置")
        require(callback.code.isNotBlank() && codeVerifier.isNotEmpty()) { "Cloudflare OAuth 参数为空" }
        return exchangeCloudflareToken(client, config, callback.code, codeVerifier, json)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

data class CloudflareOAuthCallback(val code: String, val state: String, val nonce: String? = null)

@kotlinx.serialization.Serializable
private data class CloudflareTokenResponse(
    val access_token: String? = null,
    val refresh_token: String? = null,
    val scope: String? = null,
    val expires_in: Long? = null,
    val error: String? = null,
    val error_description: String? = null,
) {
    val accessToken get() = access_token
    val refreshToken get() = refresh_token
    val expiresIn get() = expires_in
    val errorDescription get() = error_description ?: error
}

class CloudflareOAuthException(val code: String, override val message: String) : Exception(message)

private const val MAX_OAUTH_RESPONSE_CHARS = 128 * 1024

/** 纯函数便于在没有数据库和 OAuth 状态的情况下测试回调边界。 */
internal fun parseCloudflareOAuthCallback(rawUri: String, redirectUri: String): CloudflareOAuthCallback {
    val query = parseCloudflareOAuthParameters(rawUri, redirectUri)
    require(query["error"] == null) { "Cloudflare OAuth 被取消或拒绝，请重试" }
    val state = query["state"]?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Cloudflare OAuth 缺少 state")
    val code = query["code"]?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Cloudflare OAuth 缺少 code")
    return CloudflareOAuthCallback(code, state, query["nonce"]?.takeIf { it.isNotBlank() })
}

/** 拒绝重复参数、片段和额外路径，授权错误回调也必须通过相同的 URL 校验。 */
internal fun parseCloudflareOAuthParameters(rawUri: String, redirectUri: String): Map<String, String> {
    require(rawUri.length <= 16_384) { "Cloudflare OAuth 回调过长" }
    val uri = URI(rawUri)
    require(uri.rawFragment == null && uri.rawUserInfo == null && uri.toString().substringBefore('?') == redirectUri) { "Cloudflare OAuth 回调地址不匹配" }
    val pairs = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.map {
        val parts = it.split('=', limit = 2)
        URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name()) to
            URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8.name())
    }
    require(pairs.map { it.first }.toSet().size == pairs.size) { "Cloudflare OAuth 回调参数重复" }
    return pairs.toMap()
}
/** 设置和 Agent 共用一次交换协议，避免其中一条链路先无界缓存 Token 响应。 */
internal suspend fun exchangeCloudflareToken(
    client: HttpClient,
    config: CloudflareOAuthConfig,
    code: String,
    verifier: CharArray,
    json: Json,
): CloudflareTokenExchangeResult {
    config.requireValid()
    val (status, raw) = withTimeout(30_000L) {
        client.config { followRedirects = false; expectSuccess = false }.use { safe ->
            safe.prepareRequest(config.tokenEndpoint) {
                method = HttpMethod.Post
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(FormDataContent(Parameters.build {
                    append("grant_type", "authorization_code")
                    append("client_id", config.clientId)
                    append("code", code)
                    append("redirect_uri", config.redirectUri)
                    append("code_verifier", verifier.concatToString())
                }))
            }.execute { response -> response.status.value to response.readTextAtMost(128L * 1024) }
        }
    }
    if (status !in 200..299) throw CloudflareOAuthException("TOKEN_EXCHANGE_FAILED", "Cloudflare Token 交换失败")
    val payload = try { json.decodeFromString<CloudflareTokenResponse>(raw) }
    catch (_: IllegalArgumentException) { throw CloudflareOAuthException("TOKEN_RESPONSE_INVALID", "Cloudflare Token 响应无效") }
    val token = payload.accessToken?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
        ?: throw CloudflareOAuthException("TOKEN_RESPONSE_INVALID", "Cloudflare Token 缺失或无效")
    if (payload.expiresIn != null && payload.expiresIn!! !in 1..31_536_000L) {
        throw CloudflareOAuthException("TOKEN_RESPONSE_INVALID", "Cloudflare Token 有效期无效")
    }
    return CloudflareTokenExchangeResult(
        token.toCharArray(), payload.refreshToken?.toCharArray(),
        payload.scope.orEmpty().split(' ').filter(String::isNotBlank).toSet(), payload.expiresIn,
    )
}

/** OAuth 端点来自可信构建配置；拒绝明文传输、URL 内凭据和歧义回调。 */
internal fun CloudflareOAuthConfig.requireValid() {
    require(clientId.isNotBlank()) { "Cloudflare OAuth Client ID 未配置" }
    listOf(authorizationEndpoint, tokenEndpoint).forEach { endpoint ->
        val uri = URI(endpoint)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawFragment == null && uri.rawQuery == null) { "Cloudflare OAuth 端点必须是无凭据 HTTPS 地址" }
    }
    val redirect = URI(redirectUri)
    require(redirect.scheme in setOf("https", "everytalk") && !redirect.host.isNullOrBlank() &&
        redirect.rawQuery == null && redirect.rawFragment == null && redirect.rawUserInfo == null) { "Cloudflare OAuth 回调地址无效" }
}
