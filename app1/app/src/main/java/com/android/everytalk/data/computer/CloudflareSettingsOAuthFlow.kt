package com.android.everytalk.data.computer

import android.net.Uri
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** 设置页 OAuth 的结果；Token 只在内存中交给调用方，调用方必须立即写入安全存储。 */
data class CloudflareSettingsOAuthResult(
    val accessToken: CharArray,
    val refreshToken: CharArray?,
    val scopes: Set<String>,
    val expiresInSeconds: Long?,
)

/**
 * Cloudflare 设置页完整 OAuth 协调器。
 * 与 Agent OAuth 分离，防止没有 AgentRun 时伪造运行记录；state/verifier 由设置专用存储管理。
 */
class CloudflareSettingsOAuthFlow(
    private val config: CloudflareOAuthConfig,
    private val store: CloudflareSettingsOAuthStore,
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /** 回调总线分流使用；错误回调也必须先能按 state 找到所属页面。 */
    fun ownsCallback(uri: Uri, targetBinding: String): Boolean =
        runCatching { parseCloudflareOAuthParameters(uri.toString(), config.redirectUri)["state"]
            ?.let { store.owns(it, targetBinding) } == true }.getOrDefault(false)

    fun start(targetBinding: String): CloudflareOAuthStart {
        config.requireValid()
        val request = store.create(targetBinding)
        val params = mapOf(
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
            config.authorizationEndpoint + "?" + params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" },
            request,
        )
    }

    /** 先严格校验 URI 和 state，再交换 code；失败或重复回调不会重复消费授权状态。 */
    suspend fun consume(uri: Uri, targetBinding: String): CloudflareSettingsOAuthResult {
        return consumeRaw(uri.toString(), targetBinding)
    }

    internal suspend fun consumeRaw(rawUri: String, targetBinding: String): CloudflareSettingsOAuthResult {
        val parameters = parseCloudflareOAuthParameters(rawUri, config.redirectUri)
        val verifier = store.consume(parameters["state"].orEmpty(), targetBinding, parameters["nonce"])
            ?: throw CloudflareOAuthException("OAUTH_STATE_INVALID", "Cloudflare OAuth 状态无效、过期或已消费")
        try {
            // 拒绝/取消回调同样消费 state；随后重试只能使用新 PKCE 草稿。
            val callback = parseCloudflareOAuthCallback(rawUri, config.redirectUri)
            val exchanged = exchangeCloudflareToken(httpClient, config, callback.code, verifier, json)
            return CloudflareSettingsOAuthResult(
                exchanged.accessToken, exchanged.refreshToken, exchanged.scope, exchanged.expiresInSeconds,
            )
        } finally {
            verifier.fill('\u0000')
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

}
