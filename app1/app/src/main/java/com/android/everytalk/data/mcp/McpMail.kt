package com.android.everytalk.data.mcp

import java.net.URI
import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** QQ/网易使用邮箱授权码，不冒充 OAuth；网关只允许固定的官方 IMAP/SMTP 主机。 */
enum class McpMailProvider(val key: String, val displayName: String, val domains: Set<String>) {
    QQ("qq", "QQ 邮箱", setOf("qq.com", "foxmail.com")),
    NETEASE("netease", "网易邮箱", setOf("163.com", "126.com", "yeah.net"));

    val serverId: String get() = "mcp-mail-$key"
    fun config(endpoint: String): McpServerConfig = McpServerConfig.StreamableHTTPServer(
        id = serverId, url = endpoint, commonOptions = McpCommonOptions(name = displayName),
    )
}

/** 仅在配置弹窗与凭据管理器之间传递，不进入 Room、导出或日志。 */
data class McpMailInput(val gateway: String, val gatewayKey: String, val email: String, val authorizationCode: String)

@Serializable
private data class MailSecret(val endpoint: String, val token: String)

@Serializable
private data class MailAuthorization(val provider: String, val gatewayKey: String, val email: String, val authorizationCode: String)

/** 凭据绑定用户确认的 HTTPS 地址，导入配置不能把已保存的授权码转发到另一网关。 */
class McpMailManager(private val secrets: McpOAuthSecrets) {
    private val lock = Mutex()

    suspend fun save(provider: McpMailProvider, input: McpMailInput): McpServerConfig = lock.withLock {
        val endpoint = mailEndpoint(input.gateway)
        val email = input.email.trim().lowercase(java.util.Locale.ROOT)
        require(email.length <= 254 && Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.-]+").matches(email) &&
            email.substringAfter('@') in provider.domains) { "请输入该服务支持的完整邮箱地址" }
        require(input.gatewayKey.length in 32..256 && input.gatewayKey.none(Char::isISOControl)) { "网关密钥须为 32–256 个字符" }
        require(input.authorizationCode.length in 1..256 && input.authorizationCode.none(Char::isWhitespace) &&
            input.authorizationCode.none(Char::isISOControl)) { "请输入邮箱授权码（不是登录密码）" }
        // Base64 只是 HTTP 安全编码；落盘由 Keystore 加密，网络由 HTTPS 保护。
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(Json.encodeToString(
            MailAuthorization(provider.key, input.gatewayKey, email, input.authorizationCode),
        ).toByteArray(Charsets.UTF_8))
        secrets.write("mail:${provider.key}", Json.encodeToString(MailSecret(endpoint, token)))
        provider.config(endpoint)
    }

    suspend fun accessTokenFor(config: McpServerConfig): String? = lock.withLock {
        val provider = McpMailProvider.entries.firstOrNull { it.serverId == config.id } ?: return@withLock null
        val secret = secrets.read("mail:${provider.key}")?.let { Json.decodeFromString<MailSecret>(it) }
            ?: error("请在设置 → MCP 配置 ${provider.displayName} 授权码")
        require(config is McpServerConfig.StreamableHTTPServer && config.url == secret.endpoint) { "邮箱网关地址已变化，请重新配置授权码" }
        secret.token
    }

    suspend fun remove(provider: McpMailProvider): Unit = lock.withLock { secrets.remove("mail:${provider.key}") }
}

/** 只接受 HTTPS 网关根地址；路径由应用固定，禁止 userinfo、查询串和片段夹带凭据。 */
internal fun mailEndpoint(raw: String): String {
    val uri = try { URI(raw.trim()) } catch (_: Exception) { error("网关地址无效") }
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in listOf("", "/") &&
        (uri.port == -1 || uri.port in 1..65535)) { "请输入 HTTPS 网关根地址，例如 https://mail.example.com" }
    return uri.toString().trimEnd('/') + "/mcp/mail"
}
