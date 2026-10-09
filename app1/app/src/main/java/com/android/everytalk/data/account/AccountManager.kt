package com.android.everytalk.data.account

import android.content.Context
import android.net.Uri
import com.android.everytalk.BuildConfig
import com.android.everytalk.data.computer.ComputerCredentialStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString

/** 存储边界允许测试使用内存实现；生产实现始终使用现有 Keystore 加密，不写明文配置。 */
internal interface AccountSecrets {
    suspend fun read(reference: String): String?
    suspend fun write(reference: String, value: String)
    suspend fun remove(reference: String)
}

private class EncryptedAccountSecrets(context: Context) : AccountSecrets {
    private val store = ComputerCredentialStore(context)
    override suspend fun read(reference: String): String? {
        val chars = store.loadAgentAuthorization(reference) ?: return null
        return try { chars.concatToString() } finally { chars.fill('\u0000') }
    }
    override suspend fun write(reference: String, value: String): Unit =
        store.saveAgentAuthorization(reference, value.toCharArray())
    override suspend fun remove(reference: String): Unit = store.deleteAgentAuthorization(reference)
}

/** 只接收当前变体的账号回调，不抢占 MCP 或 Cloudflare 已有的授权回调。 */
internal object AccountOAuthCallbackBus {
    private val pending = MutableStateFlow<Uri?>(null)
    val flow = pending.asStateFlow()
    fun publish(uri: Uri): Boolean {
        if (uri.scheme == BuildConfig.APP_OAUTH_SCHEME && uri.host == "oauth" && uri.path == "/account") {
            pending.value = uri
            return true
        }
        return false
    }
    fun clear(uri: Uri): Unit {
        pending.compareAndSet(uri, null)
    }
}

/**
 * 管理注册/登录、会话恢复与退出。所有操作通过同一把锁更新加密记录和页面状态，
 * 防止刷新、Google 回调和退出互相覆盖。页面从不持有 Token，也不参与账号权限判断。
 */
internal class AccountManager(
    private val client: AccountAuthClient?,
    private val secrets: AccountSecrets,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    configurationError: String? = null,
    private val ownedHttp: HttpClient? = null,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(AccountUiState(
        configured = client != null,
        message = configurationError,
    ))
    val state = mutableState.asStateFlow()
    private var session: AccountSession? = null
    private val reference: String get() = requireNotNull(client).config.storageReference
    private val pendingReference: String get() = "$reference:pending"

    init {
        if (client != null) scope.launch { perform { restoreSession() } }
        scope.launch {
            AccountOAuthCallbackBus.flow.filterNotNull().collect { uri ->
                try { handleGoogleCallback(uri.toString()).join() }
                finally { AccountOAuthCallbackBus.clear(uri) }
            }
        }
    }

    fun sendEmailCode(rawEmail: String): Job = scope.launch {
        perform {
            val email = normalizeAccountEmail(rawEmail)
            require(clock() >= mutableState.value.resendAtMillis) { "请稍后再获取验证码" }
            requireNotNull(client).sendEmailCode(email)
            // 开始邮箱登录时取消此前的 Google 草稿，避免迟到回调把账号切回去。
            secrets.remove(pendingReference)
            mutableState.update { it.copy(pendingEmail = email, awaitingGoogle = false,
                resendAtMillis = clock() + ACCOUNT_CODE_COOLDOWN_MILLIS,
                message = "验证码已发送，请查看邮箱") }
        }
    }

    fun verifyEmailCode(code: String): Job = scope.launch {
        perform {
            val email = requireNotNull(mutableState.value.pendingEmail) { "请先获取验证码" }
            saveSession(requireNotNull(client).verifyEmailCode(email, code.trim()))
        }
    }

    fun changeEmail(): Unit {
        if (!mutableState.value.busy) mutableState.update { it.copy(pendingEmail = null, message = null) }
    }

    /** 先保存 PKCE 草稿，再打开外部浏览器；浏览器打开失败时立即撤销草稿。 */
    fun startGoogleLogin(openBrowser: (String) -> Unit): Job = scope.launch {
        perform {
            val pending = PendingAccountOAuth.create(clock())
            secrets.write(pendingReference, accountJson.encodeToString(pending))
            try {
                openBrowser(requireNotNull(client).googleAuthorizationUrl(pending))
            } catch (error: Exception) {
                secrets.remove(pendingReference)
                throw AccountAuthException("无法打开浏览器，请检查设备是否安装浏览器")
            }
            mutableState.update { it.copy(awaitingGoogle = true, pendingEmail = null,
                message = "请在浏览器中完成 Google 登录") }
        }
    }

    /** 无效地址或随机标识不会消费合法草稿；匹配后的授权码只允许交换一次。 */
    fun handleGoogleCallback(raw: String): Job = scope.launch {
        perform {
            val api = requireNotNull(client)
            val saved = secrets.read(pendingReference)
            if (saved == null) {
                // Activity 重建可能再次带入旧 Intent；已完成的登录不重复交换，也不显示错误。
                if (session != null) return@perform
                throw AccountAuthException("没有等待中的 Google 登录，请重新开始")
            }
            val pending = accountJson.decodeFromString<PendingAccountOAuth>(saved)
            val code = try {
                pending.consumeCode(raw, api.config.redirectUri, clock())
            } catch (error: AccountAuthException) {
                secrets.remove(pendingReference)
                mutableState.update { it.copy(awaitingGoogle = false) }
                throw error
            }
            secrets.remove(pendingReference)
            mutableState.update { it.copy(awaitingGoogle = false) }
            saveSession(api.exchangeGoogleCode(code, pending.verifier))
        }
    }

    fun cancelGoogleLogin(): Job = scope.launch {
        perform {
            secrets.remove(pendingReference)
            mutableState.update { it.copy(awaitingGoogle = false, message = null) }
        }
    }

    fun refreshAccount(): Job = scope.launch { perform { restoreSession() } }

    /**
     * 退出只影响此设备，聊天和自带 API 配置继续保留。
     * 网络撤销失败时仍清除本地凭据，并明确提示服务端会话未确认撤销。
     */
    fun logout(): Job = scope.launch {
        perform {
            var revoked = true
            try {
                session?.let { requireNotNull(client).logout(it.accessToken) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                revoked = false
            } finally {
                withContext(NonCancellable) {
                    secrets.remove(reference)
                    secrets.remove(pendingReference)
                    session = null
                    mutableState.update { it.copy(user = null, pendingEmail = null, awaitingGoogle = false,
                        message = if (revoked) "已退出登录" else "已退出此设备；服务端会话撤销失败，请稍后检查网络") }
                }
            }
        }
    }

    private suspend fun restoreSession(): Unit {
        val api = requireNotNull(client)
        val pending = secrets.read(pendingReference)?.let { accountJson.decodeFromString<PendingAccountOAuth>(it) }
        val awaitingGoogle = pending != null && clock() >= pending.createdAtMillis &&
            clock() - pending.createdAtMillis < ACCOUNT_OAUTH_TTL_MILLIS
        if (pending != null && !awaitingGoogle) secrets.remove(pendingReference)
        mutableState.update { it.copy(awaitingGoogle = awaitingGoogle) }
        var stored = session ?: secrets.read(reference)?.let { accountJson.decodeFromString<AccountSession>(it) }
            ?: return
        try {
            if (stored.expiresAtMillis <= clock() + 60_000) {
                val renewed = api.refreshSession(stored.refreshToken)
                if (renewed.user.id != stored.user.id) throw AccountAuthException("刷新会话的账号不匹配，请重新登录", true)
                // 刷新 Token 会轮换，先持久化新值，避免随后读取资料失败导致下次使用旧 Token。
                secrets.write(reference, accountJson.encodeToString(renewed))
                session = renewed
                stored = renewed
            }
            val user = api.getUser(stored.accessToken)
            if (user.id != stored.user.id) throw AccountAuthException("会话的账号不匹配，请重新登录", true)
            session = stored.copy(user = user)
            mutableState.update { it.copy(user = user, message = null) }
        } catch (error: AccountAuthException) {
            if (error.invalidSession) {
                secrets.remove(reference)
                session = null
                mutableState.update { it.copy(user = null) }
            }
            throw error
        }
    }

    private suspend fun saveSession(value: AccountSession): Unit {
        secrets.write(reference, accountJson.encodeToString(value))
        secrets.remove(pendingReference)
        session = value
        mutableState.update { it.copy(user = value.user, pendingEmail = null, awaitingGoogle = false,
            message = "登录成功") }
    }

    private suspend fun perform(action: suspend () -> Unit): Unit = mutex.withLock {
        if (client == null) return@withLock
        mutableState.update { it.copy(busy = true, message = null) }
        try {
            action()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val message = when (error) {
                is SerializationException -> "登录信息无效，请重新登录或稍后重试"
                is AccountAuthException, is IllegalArgumentException -> error.message
                else -> "无法读取或保存登录信息，请稍后重试"
            }
            mutableState.update { it.copy(message = message) }
        } finally {
            mutableState.update { it.copy(busy = false) }
        }
    }

    fun close(): Unit { ownedHttp?.close() }

    companion object {
        /** 未配置时仍可打开账户页和使用原有功能；绝不使用伪造会话替代真实认证。 */
        fun create(context: Context, scope: CoroutineScope): AccountManager {
            val configured = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()
            val result = if (configured) runCatching {
                AccountAuthConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY,
                    "${BuildConfig.APP_OAUTH_SCHEME}://oauth/account")
            } else null
            val config = result?.getOrNull()
            val http = config?.let { HttpClient(OkHttp) { followRedirects = false; expectSuccess = false } }
            return AccountManager(config?.let { AccountAuthClient(it, requireNotNull(http)) },
                EncryptedAccountSecrets(context), scope,
                configurationError = when {
                    !configured -> "账户登录服务尚未配置"
                    result?.isFailure == true -> "账户登录配置无效，请联系维护者"
                    else -> null
                }, ownedHttp = http)
        }
    }
}
