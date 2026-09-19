package com.android.everytalk.data.mcp

import android.content.Context
import android.net.Uri
import com.android.everytalk.BuildConfig
import com.android.everytalk.data.computer.ComputerCredentialStore
import com.android.everytalk.data.network.readTextAtMost
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** 内置服务只把授权发送到固定官方端点；导入配置不能靠复用 ID 获取凭据。 */
enum class McpOAuthProvider(val key: String, val serverId: String, val displayName: String, val endpoint: String) {
    GITHUB("github", "mcp-github", "GitHub", "https://api.githubcopilot.com/mcp/x/all"),
    CLOUDFLARE("cloudflare", "mcp-cloudflare", "Cloudflare", "https://mcp.cloudflare.com/mcp");

    val redirectUri: String get() = "https://oauth.everytalk.cc/oauth/mcp/$key"
    val appRedirectUri: String get() = "everytalk://oauth/mcp/$key"
    fun defaultConfig(): McpServerConfig = McpServerConfig.StreamableHTTPServer(
        id = serverId,
        url = endpoint,
        commonOptions = McpCommonOptions(name = displayName,
            headers = if (this == GITHUB) listOf("X-MCP-Toolsets" to "all") else emptyList()),
    )

    companion object {
        fun forConfig(config: McpServerConfig): McpOAuthProvider? = entries.firstOrNull { it.serverId == config.id }?.also {
            require(config is McpServerConfig.StreamableHTTPServer && config.url == it.endpoint) {
                "内置 MCP 服务地址不能修改，请重新添加服务"
            }
        }
    }
}

/** Token 与 PKCE 草稿均复用 Keystore 信封加密；Room、备份和导出中不保存正文。 */
interface McpOAuthSecrets {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
    suspend fun remove(key: String)
}

internal class EncryptedMcpOAuthSecrets(context: Context) : McpOAuthSecrets {
    private val store = ComputerCredentialStore(context)
    override suspend fun read(key: String): String? {
        val chars = store.loadAgentAuthorization("mcp:$key") ?: return null
        return try { chars.concatToString() } finally { chars.fill('\u0000') }
    }
    override suspend fun write(key: String, value: String) =
        store.saveAgentAuthorization("mcp:$key", value.toCharArray())
    override suspend fun remove(key: String) = store.deleteAgentAuthorization("mcp:$key")
}

@Serializable
internal data class McpAuthorizationSecret(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtMillis: Long? = null,
    val clientId: String,
)

@Serializable
private data class PendingMcpOAuth(
    val state: String, val verifier: String, val clientId: String, val createdAt: Long,
)

/** 回调中不接受 fragment、重复参数、未知路径或不同服务的 issuer。 */
internal fun parseMcpCallback(raw: String, provider: McpOAuthProvider): Map<String, String> {
    require(raw.length <= 16_384) { "MCP OAuth 回调过长" }
    val uri = URI(raw)
    require(uri.rawFragment == null && uri.rawUserInfo == null &&
        raw.substringBefore('?') in setOf(provider.redirectUri, provider.appRedirectUri)) {
        "MCP OAuth 回调地址不匹配"
    }
    val pairs = uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).map {
        val parts = it.split('=', limit = 2)
        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
    }
    require(pairs.map { it.first }.toSet().size == pairs.size) { "MCP OAuth 回调参数重复" }
    return pairs.toMap().also {
        if (provider == McpOAuthProvider.CLOUDFLARE && it["iss"] != null) {
            require(it["iss"] == "https://mcp.cloudflare.com") { "MCP OAuth issuer 不匹配" }
        }
    }
}

internal fun mcpPkceChallenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

object McpOAuthCallbackBus {
    private val callbacks = MutableStateFlow<Uri?>(null)
    val flow = callbacks.asStateFlow()
    fun publish(uri: Uri) {
        if (providerFor(uri.toString()) != null) callbacks.value = uri
    }
    fun consume(uri: Uri) { callbacks.compareAndSet(uri, null) }
    fun providerFor(raw: String): McpOAuthProvider? = McpOAuthProvider.entries.firstOrNull {
        raw.substringBefore('?') in setOf(it.redirectUri, it.appRedirectUri)
    }
}

/**
 * GitHub 经 Worker 交换授权码（Secret 永不进入 APK）；Cloudflare MCP 动态注册公开客户端。
 * 每个提供方的一把锁覆盖登录、回调、续期和删除，避免刷新令牌轮换及退出登录之间竞争。
 * PKCE 草稿只保留最新一份，10 分钟失效，进程重启后仍能安全接收回调。
 */
class McpOAuthManager(
    private val secrets: McpOAuthSecrets,
    private val http: HttpClient,
    private val githubClientId: String = BuildConfig.GITHUB_MCP_OAUTH_CLIENT_ID,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val locks = McpOAuthProvider.entries.associateWith { Mutex() }
    private val random = SecureRandom()

    suspend fun start(provider: McpOAuthProvider): String = locks.getValue(provider).withLock {
        val clientId = when (provider) {
            McpOAuthProvider.GITHUB -> githubClientId.also {
                require(it.isNotBlank()) { "GitHub MCP OAuth Client ID 未配置" }
            }
            McpOAuthProvider.CLOUDFLARE -> registerCloudflareClient(provider.redirectUri)
        }
        val pending = PendingMcpOAuth(randomToken(), randomToken(), clientId, now())
        secrets.write("pending:${provider.key}", json.encodeToString(pending))
        val params = linkedMapOf(
            "client_id" to clientId, "redirect_uri" to provider.redirectUri,
            "response_type" to "code", "state" to pending.state,
            "code_challenge" to mcpPkceChallenge(pending.verifier), "code_challenge_method" to "S256",
        )
        if (provider == McpOAuthProvider.GITHUB) {
            params["scope"] = "repo read:org read:user user:email read:packages write:packages project gist notifications workflow offline_access"
        } else {
            params["resource"] = provider.endpoint
        }
        val endpoint = if (provider == McpOAuthProvider.GITHUB) "https://github.com/login/oauth/authorize"
            else "https://mcp.cloudflare.com/authorize"
        endpoint + "?" + params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
    }

    /** 成功时立即加密保存；错误回调也消费本次草稿，伪造 state 不会破坏真实登录。 */
    suspend fun consume(raw: String): McpOAuthProvider {
        val provider = McpOAuthCallbackBus.providerFor(raw) ?: error("MCP OAuth 回调地址无效")
        return locks.getValue(provider).withLock {
            val params = parseMcpCallback(raw, provider)
            val key = "pending:${provider.key}"
            val pending = secrets.read(key)?.let { json.decodeFromString<PendingMcpOAuth>(it) }
                ?: error("MCP OAuth 登录已失效，请重新登录")
            require(params["state"] == pending.state) { "MCP OAuth state 不匹配" }
            secrets.remove(key)
            require(now() - pending.createdAt in 0..600_000L) { "MCP OAuth 登录已超时" }
            require(params["error"] == null) { "MCP OAuth 授权被取消或拒绝" }
            val code = params["code"]?.takeIf { it.isNotBlank() } ?: error("MCP OAuth 缺少 code")
            val token = exchange(provider, pending.clientId, Parameters.build {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", provider.redirectUri)
                append("code_verifier", pending.verifier)
            })
            secrets.write("token:${provider.key}", json.encodeToString(token))
            provider
        }
    }

    /** 每次 HTTP 请求前检查到期时间；刷新只在临界区中做一次，保留服务返回的新 refresh token。 */
    suspend fun accessTokenFor(config: McpServerConfig): String? {
        val provider = McpOAuthProvider.forConfig(config) ?: return null
        return locks.getValue(provider).withLock {
            val previous = secrets.read("token:${provider.key}")?.let { json.decodeFromString<McpAuthorizationSecret>(it) }
                ?: error("请先登录 ${provider.displayName}")
            if (previous.expiresAtMillis == null || previous.expiresAtMillis > now() + 60_000L) {
                return@withLock previous.accessToken
            }
            val refresh = previous.refreshToken ?: error("授权已过期，请重新登录 ${provider.displayName}")
            val refreshed = exchange(provider, previous.clientId, Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", refresh)
            }).let { it.copy(refreshToken = it.refreshToken ?: refresh) }
            secrets.write("token:${provider.key}", json.encodeToString(refreshed))
            refreshed.accessToken
        }
    }

    suspend fun logout(provider: McpOAuthProvider) = locks.getValue(provider).withLock {
        secrets.remove("pending:${provider.key}")
        secrets.remove("token:${provider.key}")
    }

    private suspend fun registerCloudflareClient(redirectUri: String): String {
        val body = buildJsonObject {
            put("client_name", "EveryTalk")
            put("redirect_uris", JsonArray(listOf(JsonPrimitive(redirectUri))))
            put("grant_types", JsonArray(listOf(JsonPrimitive("authorization_code"), JsonPrimitive("refresh_token"))))
            put("response_types", JsonArray(listOf(JsonPrimitive("code"))))
            put("token_endpoint_auth_method", "none")
        }
        val payload = post("https://mcp.cloudflare.com/register", body.toString(), ContentType.Application.Json)
        return payload["client_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("Cloudflare MCP 客户端注册失败")
    }

    private suspend fun exchange(provider: McpOAuthProvider, clientId: String, parameters: Parameters): McpAuthorizationSecret {
        val endpoint = if (provider == McpOAuthProvider.GITHUB)
            "https://oauth.everytalk.cc/oauth/mcp/github/token" else "https://mcp.cloudflare.com/token"
        val form = FormDataContent(Parameters.build {
            appendAll(parameters)
            append("client_id", clientId)
            if (provider == McpOAuthProvider.CLOUDFLARE) append("resource", provider.endpoint)
        })
        val payload = post(endpoint, form, ContentType.Application.FormUrlEncoded)
        require(payload["error"] == null) { "MCP OAuth 授权无效，请重新登录" }
        val token = payload["access_token"]?.jsonPrimitive?.contentOrNull
        require(!token.isNullOrBlank() && token.length <= 32_768 && token.none(Char::isISOControl)) { "MCP OAuth token 响应无效" }
        val type = payload["token_type"]?.jsonPrimitive?.contentOrNull
        require(type == null || type.equals("bearer", true)) { "MCP OAuth token 类型不支持" }
        val expiry = payload["expires_in"]?.jsonPrimitive?.longOrNull
        require(payload["expires_in"] == null || expiry != null && expiry in 1..31_536_000L) { "MCP OAuth 有效期无效" }
        val refresh = payload["refresh_token"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        return McpAuthorizationSecret(token, refresh, expiry?.let { now() + it * 1_000 }, clientId)
    }

    /** 限制响应大小、时间和重定向；不在错误消息里暴露授权码、token 或上游正文。 */
    private suspend fun post(endpoint: String, body: Any, type: ContentType): JsonObject = withTimeout(30_000L) {
        http.config { followRedirects = false; expectSuccess = false }.use { safe ->
            safe.preparePost(endpoint) {
                contentType(type)
                headers.append("Accept", "application/json")
                setBody(body)
            }.execute { response ->
                if (response.status.value !in 200..299) error("MCP OAuth 请求失败（HTTP ${response.status.value}）")
                val raw = response.readTextAtMost(128L * 1024)
                try { json.parseToJsonElement(raw).jsonObject }
                catch (_: IllegalArgumentException) { error("MCP OAuth 响应格式无效") }
            }
        }
    }

    private fun randomToken(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also(random::nextBytes))
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
