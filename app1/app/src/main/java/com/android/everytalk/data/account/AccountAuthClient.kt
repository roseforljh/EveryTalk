package com.android.everytalk.data.account

import com.android.everytalk.data.network.readTextAtMost
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

/**
 * 使用现有 Ktor 调用 Supabase Auth 的公开接口，不引入新的认证 SDK。
 * 只发送公开 Key 和当前操作必需的凭据；不记录响应正文，不把上游错误正文展示给用户。
 */
internal class AccountAuthClient(
    val config: AccountAuthConfig,
    private val http: HttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun googleAuthorizationUrl(pending: PendingAccountOAuth): String {
        val redirect = config.redirectUri + "?login_state=" + pending.state
        return config.authUrl + "/authorize?" + listOf(
            "provider" to "google", "redirect_to" to redirect,
            "code_challenge" to pending.challenge, "code_challenge_method" to "s256",
            "scopes" to "openid email profile",
        ).joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
    }

    /** 新邮箱允许建立待验证记录；验证码验证成功才完成注册并建立会话。 */
    suspend fun sendEmailCode(email: String): Unit {
        request("/otp", body = buildJsonObject {
            put("email", JsonPrimitive(normalizeAccountEmail(email)))
            put("create_user", JsonPrimitive(true))
        })
    }

    /** 校验 6 位验证码并交换会话；参数错误时不发送请求，失败时不返回部分凭据。 */
    suspend fun verifyEmailCode(email: String, code: String): AccountSession {
        require(code.matches(Regex("[0-9]{6}"))) { "请输入 6 位数字验证码" }
        return parseSession(request("/verify", body = buildJsonObject {
            put("email", JsonPrimitive(normalizeAccountEmail(email)))
            put("token", JsonPrimitive(code))
            put("type", JsonPrimitive("email"))
        }))
    }

    /** 授权码与本机草稿的 verifier 必须同时交给认证服务，不能用回调中的 Token 登录。 */
    suspend fun exchangeGoogleCode(code: String, verifier: String): AccountSession = parseSession(
        request("/token?grant_type=pkce", body = buildJsonObject {
            put("auth_code", JsonPrimitive(code))
            put("code_verifier", JsonPrimitive(verifier))
        }),
    )

    /** 刷新会话可能轮换 refresh token，调用方必须先保存返回值，再进行后续请求。 */
    suspend fun refreshSession(refreshToken: String): AccountSession = parseSession(
        request("/token?grant_type=refresh_token", body = buildJsonObject {
            put("refresh_token", JsonPrimitive(refreshToken))
        }, refresh = true),
    )

    /** 用服务端返回的资料验证会话，不把本地缓存或未经校验的 JWT 当成登录证明。 */
    suspend fun getUser(accessToken: String): AccountUser = parseUser(
        request("/user", method = HttpMethod.Get, accessToken = accessToken),
    )

    suspend fun logout(accessToken: String): Unit {
        request("/logout?scope=local", accessToken = accessToken)
    }

    private suspend fun request(
        path: String,
        method: HttpMethod = HttpMethod.Post,
        body: JsonObject? = null,
        accessToken: String? = null,
        refresh: Boolean = false,
    ): JsonObject = try {
        withTimeout(15_000L) {
            http.prepareRequest(config.authUrl + path) {
                this.method = method
                header("apikey", config.publicKey)
                if (accessToken != null) header(HttpHeaders.Authorization, "Bearer $accessToken")
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
            }.execute { response ->
                val text = response.readTextAtMost(128 * 1024L)
                if (response.status.value !in 200..299) {
                    val code = runCatching {
                        accountJson.parseToJsonElement(text).jsonObject["error_code"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull()
                    val invalid = response.status.value in setOf(401, 403) ||
                        (refresh && response.status.value == 400)
                    val message = when {
                        response.status.value == 429 -> "操作过于频繁，请稍后再试"
                        code in setOf("otp_expired", "otp_disabled") -> "验证码无效或已过期，请重新获取"
                        code == "email_address_invalid" -> "请输入有效的邮箱地址"
                        code == "email_not_allowed" -> "邮件服务尚未开放此邮箱，请联系维护者"
                        invalid -> "登录已失效，请重新登录"
                        else -> "登录服务暂时不可用，请稍后重试"
                    }
                    throw AccountAuthException(message, invalid)
                }
                if (text.isBlank()) JsonObject(emptyMap()) else accountJson.parseToJsonElement(text).jsonObject
            }
        }
    } catch (_: TimeoutCancellationException) {
        throw AccountAuthException("登录服务响应超时，请稍后重试")
    } catch (error: CancellationException) {
        throw error
    } catch (error: AccountAuthException) {
        throw error
    } catch (_: Exception) {
        throw AccountAuthException("无法连接登录服务，请检查网络后重试")
    }

    private fun parseSession(body: JsonObject): AccountSession {
        val access = body["access_token"]?.jsonPrimitive?.contentOrNull
        val refresh = body["refresh_token"]?.jsonPrimitive?.contentOrNull
        val expiresIn = body["expires_in"]?.jsonPrimitive?.longOrNull
        require(!access.isNullOrBlank() && access.length <= 16_384 && !refresh.isNullOrBlank() &&
            refresh.length <= 4096 && expiresIn != null && expiresIn in 1..86_400 &&
            access.none(Char::isWhitespace) && refresh.none(Char::isWhitespace) &&
            body["token_type"]?.jsonPrimitive?.contentOrNull == "bearer") { "登录服务返回的会话无效" }
        return AccountSession(access, refresh, clock() + expiresIn * 1000,
            parseUser(requireNotNull(body["user"]).jsonObject))
    }

    private fun parseUser(body: JsonObject): AccountUser {
        val user = accountJson.decodeFromString<AccountUser>(body.toString())
        require(user.id.length in 1..128 && !user.email.isNullOrBlank() && user.email.length <= 254) {
            "登录服务返回的用户资料无效"
        }
        return user
    }
}
