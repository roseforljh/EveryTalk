package com.android.everytalk.data.account

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale

internal const val ACCOUNT_OAUTH_TTL_MILLIS = 10 * 60 * 1000L
internal const val ACCOUNT_CODE_COOLDOWN_MILLIS = 60_000L
internal val accountJson = Json { ignoreUnknownKeys = true }

/** 只开放已支持的身份提供方；GitHub 仅申请身份和邮箱，不申请仓库权限。 */
internal enum class AccountOAuthProvider(val apiName: String, val scopes: String) {
    Google("google", "openid email profile"),
    GitHub("github", "read:user user:email"),
}

/**
 * 只接受 HTTPS 项目地址和公开客户端 Key。管理员密钥不得进入 APK；
 * redirectUri 由构建变体决定，Debug 和 Release 使用不同的回调协议。
 */
internal data class AccountAuthConfig(val projectUrl: String, val publicKey: String, val redirectUri: String) {
    val authUrl: String
    val storageReference: String

    init {
        val uri = URI(projectUrl)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty() in setOf("", "/")) {
            "Supabase 项目地址必须是 HTTPS 根地址"
        }
        require(publicKey.length in 20..4096 && publicKey.none(Char::isWhitespace)) { "Supabase 公开 Key 无效" }
        val isPublishable = publicKey.startsWith("sb_publishable_")
        val isAnon = if (!isPublishable) runCatching {
            val parts = publicKey.split('.')
            parts.size == 3 && accountJson.parseToJsonElement(
                Base64.getUrlDecoder().decode(parts[1]).toString(Charsets.UTF_8),
            ).jsonObject["role"]?.jsonPrimitive?.content == "anon"
        }.getOrDefault(false) else false
        require(isPublishable || isAnon) { "只能配置 publishable key 或 anon key，不能使用管理员密钥" }
        require(redirectUri in setOf("everytalk://oauth/account", "everytalk-debug://oauth/account")) {
            "账号回调地址无效"
        }
        authUrl = projectUrl.trimEnd('/') + "/auth/v1"
        // 项目切换后不读取旧项目的 Token，也不把旧凭据发送到新的认证地址。
        storageReference = "account:" + MessageDigest.getInstance("SHA-256")
            .digest((authUrl + "\n" + publicKey).toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** 页面只接触用户资料；access/refresh token 始终留在认证层和加密存储中。 */
@Serializable
internal data class AccountUser(
    val id: String,
    val email: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("user_metadata") val metadata: JsonObject = JsonObject(emptyMap()),
) {
    /** 用户编辑字段优先于提供方资料；这些字段只用于显示，不参与任何权限判断。 */
    val displayName: String get() = listOf("everytalk_name", "full_name", "name", "user_name")
        .firstNotNullOfOrNull { key -> (metadata[key] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) } }
        ?.take(50) ?: email?.substringBefore('@').orEmpty()

    /** Google/GitHub 的头像随用户资料返回；只加载 HTTPS 图片，不将凭据发送到图片服务器。 */
    val avatarUrl: String? get() = listOf("everytalk_avatar_url", "avatar_url", "picture")
        .firstNotNullOfOrNull { key -> (metadata[key] as? JsonPrimitive)?.contentOrNull?.takeIf { value ->
            value.length <= 2048 && runCatching {
                val uri = URI(value)
                uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
            }.getOrDefault(false)
        } }
}

@Serializable
internal data class AccountSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val user: AccountUser,
)

internal data class AccountUiState(
    val configured: Boolean = false,
    /** 本地会话恢复结束后才决定启动页面，避免已登录用户短暂看到登录表单。 */
    val initialized: Boolean = false,
    val user: AccountUser? = null,
    val busy: Boolean = false,
    val pendingEmail: String? = null,
    val resendAtMillis: Long = 0,
    val awaitingOAuth: Boolean = false,
    val message: String? = null,
)

/** 浏览器授权草稿短期加密保存，保证切到浏览器后进程被回收仍能完成登录。 */
@Serializable
internal data class PendingAccountOAuth(val state: String, val verifier: String, val createdAtMillis: Long) {
    companion object {
        fun create(nowMillis: Long): PendingAccountOAuth {
            val random = SecureRandom()
            fun token(): String = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ByteArray(32).also(random::nextBytes))
            return PendingAccountOAuth(token(), token(), nowMillis)
        }
    }

    val challenge: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
    )

    /**
     * 校验完整回调地址、一次登录的随机标识和有效期；只返回授权码，不接受 URL 中的 Token。
     * Supabase 会在回调中保留 redirect_to 的 login_state 参数，PKCE 另外约束授权码交换。
     */
    fun consumeCode(raw: String, redirectUri: String, nowMillis: Long): String {
        require(raw.length <= 8192) { "登录回调过长" }
        val uri = URI(raw)
        require(uri.rawFragment == null && raw.substringBefore('?') == redirectUri) { "登录回调地址不匹配" }
        val pairs = uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).map {
            val parts = it.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }
        require(pairs.map { it.first }.distinct().size == pairs.size) { "登录回调参数重复" }
        val values = pairs.toMap()
        require(values["login_state"] == state) { "登录回调不属于本次授权" }
        require(nowMillis >= createdAtMillis && nowMillis - createdAtMillis < ACCOUNT_OAUTH_TTL_MILLIS) {
            "第三方登录已过期，请重新开始"
        }
        require(values.keys.none { it in setOf("access_token", "refresh_token", "id_token") }) {
            "登录回调不能包含会话凭据"
        }
        if (values["error"] != null) throw AccountAuthException("第三方登录未完成，请重试")
        return requireNotNull(values["code"]?.takeIf { it.length in 1..2048 && it.none(Char::isISOControl) }) {
            "登录回调缺少有效授权码"
        }
    }
}

/** 只规范大小写和空白，不移除加号后缀或 Gmail 的点号，避免把不同地址错误合并。 */
internal fun normalizeAccountEmail(raw: String): String {
    val email = raw.trim().lowercase(Locale.ROOT)
    require(email.length <= 254 && email.matches(Regex("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+$"))) {
        "请输入有效的邮箱地址"
    }
    return email
}

internal class AccountAuthException(message: String, val invalidSession: Boolean = false) : Exception(message)
