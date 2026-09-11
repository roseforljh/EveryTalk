package com.android.everytalk.data.computer

import com.android.everytalk.data.agent.OAuthAuthorizationRequest
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 设置页专用 OAuth 草稿。
 * AgentRun 不存在时也能启动授权；状态短期保存、只能消费一次，verifier 使用后立即清零。
 * Token 不存入这里，交换完成后必须交给 Android 安全存储。
 */
class CloudflareSettingsOAuthStore(
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(
        val state: String,
        val verifier: CharArray,
        val nonce: String,
        val targetBinding: String,
        val expiresAt: Long,
        var consumed: Boolean = false,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    @Synchronized
    fun create(targetBinding: String, ttlMillis: Long = 10 * 60 * 1000L): OAuthAuthorizationRequest {
        require(targetBinding.isNotBlank()) { "OAuth 目标不能为空" }
        require(ttlMillis in 1..15 * 60 * 1000L) { "OAuth TTL 无效" }
        clearExpired()
        cancel(targetBinding)
        val state = token(32)
        val verifierText = token(48)
        val verifier = verifierText.toCharArray()
        val nonce = token(32)
        entries[state] = Entry(state, verifier, nonce, targetBinding, clock() + ttlMillis)
        return OAuthAuthorizationRequest(state, challenge(verifierText), nonce = nonce)
    }

    @Synchronized
    fun consume(state: String, targetBinding: String, returnedNonce: String? = null): CharArray? {
        val entry = entries[state] ?: return null
        if (entry.targetBinding != targetBinding) return null
        // Cloudflare 当前授权码回调可能不回传 nonce；回传时必须严格匹配，state+PKCE 仍是必需校验。
        if (returnedNonce != null && returnedNonce != entry.nonce) return null
        if (entry.consumed || entry.expiresAt <= clock()) {
            entries.remove(state)?.verifier?.fill('\u0000')
            return null
        }
        entry.consumed = true
        entries.remove(state)
        return entry.verifier.copyOf().also { entry.verifier.fill('\u0000') }
    }

    /**
     * 只判断某个回调是否属于当前页面创建的授权请求，不消费 verifier。
     * 多个 Compose 页面可能同时监听全局回调总线，先做归属判断才能避免
     * 一个页面把另一个页面的 OAuth 回调提前清掉。
     */
    @Synchronized
    fun owns(state: String, targetBinding: String): Boolean =
        entries[state]?.let { it.targetBinding == targetBinding && !it.consumed && it.expiresAt > clock() } == true

    @Synchronized
    fun clearExpired() {
        entries.values.filter { it.expiresAt <= clock() }.forEach { entry ->
            if (entries.remove(entry.state, entry)) entry.verifier.fill('\u0000')
        }
    }

    /** 关闭表单或重新开始登录时销毁原 verifier，不留下可重放的授权草稿。 */
    @Synchronized
    fun cancel(targetBinding: String) {
        entries.values.filter { it.targetBinding == targetBinding }.forEach { entry ->
            if (entries.remove(entry.state, entry)) entry.verifier.fill('\u0000')
        }
    }

    @Synchronized
    fun clear() {
        entries.values.forEach { it.verifier.fill('\u0000') }
        entries.clear()
    }

    private fun token(size: Int): String = ByteArray(size).also(random::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }

    private fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
    )
}
