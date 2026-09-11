package com.android.everytalk.data.computer

import com.android.everytalk.data.database.daos.ComputerDao
import com.android.everytalk.data.database.entities.CloudflareAuthorizationEntity
import com.android.everytalk.data.database.entities.CloudflareComputerConfigEntity
import com.android.everytalk.data.database.entities.CloudflareWorkerHealthEntity
import com.android.everytalk.data.database.entities.ComputerEntity
import com.android.everytalk.data.database.entities.toEntity
import com.android.everytalk.data.database.entities.toModel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import io.ktor.client.HttpClient
import java.util.UUID

/**
 * 每个 HTTP 请求（包括分页、重试和部署的第二步）都重新检查固定身份后取 Token。
 * 安全存储读取可能挂起，读取后再次核对 Room，防止读取期间退出或切账号。
 * 这里只返回当前 HTTP 请求必需的凭据，调用方不能用 Computer ID 自动追随新账号。
 */
internal class CloudflareRequestCredentials(
    private val dao: ComputerDao,
    private val credentials: ComputerCredentialStore,
    private val json: Json = Json,
    private val httpClient: HttpClient? = null,
    private val oauthConfig: CloudflareOAuthConfig? = null,
) {
    suspend fun token(context: ComputerRequestContext): String {
        val config = dao.getCloudflareConfig(context.computerId)
            ?: throw CloudflareApiException("PROVIDER_CONFIG_NOT_FOUND", "Cloudflare Computer 配置不存在")
        val authorization = dao.getCloudflareAuthorization(config.authorizationId)
            ?: throw CloudflareApiException("AUTHORIZATION_REQUIRED", "Cloudflare 授权不存在")
        context.requireCloudflareBinding(config.toModel(json), authorization.toModel(json))
        val computer = dao.getComputer(context.computerId)
        val workspace = dao.getWorkspaceById(context.workspaceId)
        if (computer?.provider != ComputerProvider.CLOUDFLARE.name ||
            computer.status in setOf(ComputerStatus.DELETING.name, ComputerStatus.DELETED.name) ||
            workspace?.computerId != context.computerId) {
            throw CloudflareApiException("COMPUTER_CONTEXT_MISMATCH", "Cloudflare Computer 或 Workspace 已变化")
        }
        val payload = credentials.loadAgentAuthorization(authorization.credentialReference)
            ?: throw CloudflareApiException("AUTHORIZATION_REQUIRED", "Cloudflare Token 不存在")
        try {
            if (dao.getCloudflareConfig(context.computerId) != config ||
                dao.getCloudflareAuthorization(config.authorizationId) != authorization ||
                dao.getComputer(context.computerId)?.provider != ComputerProvider.CLOUDFLARE.name ||
                dao.getWorkspaceById(context.workspaceId)?.computerId != context.computerId) {
                throw CloudflareApiException("REQUEST_CONTEXT_STALE", "读取凭据期间 Cloudflare 目标或授权已变化")
            }
            context.requireCloudflareBinding(config.toModel(json), authorization.toModel(json))
            val stored = runCatching { json.parseToJsonElement(payload.concatToString()).jsonObject }.getOrNull()
                ?: throw CloudflareApiException("AUTHORIZATION_REQUIRED", "Cloudflare Token 格式无效")
            val accessToken = stored["access_token"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() && '\r' !in it && '\n' !in it }
                ?: throw CloudflareApiException("AUTHORIZATION_REQUIRED", "Cloudflare Token 格式无效")
            return refreshIfExpiring(authorization, stored) ?: accessToken
        } finally {
            payload.fill('\u0000')
        }
    }

    /**
     * access token 快到期时用 refresh_token 续期，避免每小时把用户拉去重新登录。
     * 续期只换 Token 和到期时间，不动授权代次，否则正在等待的写审批会全部作废。
     * 拿不到续期材料时沿用旧 Token；续期失败按授权失效处理，和以前的到期行为一致。
     */
    private suspend fun refreshIfExpiring(
        authorization: CloudflareAuthorizationEntity,
        stored: JsonObject,
    ): String? {
        val expiresAt = authorization.expiresAt ?: return null
        if (expiresAt - System.currentTimeMillis() > TOKEN_REFRESH_MARGIN_MILLIS) return null
        val refreshToken = stored["refresh_token"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        val client = httpClient
        val oauth = oauthConfig
        if (refreshToken == null || client == null || oauth == null) return null
        val result = try {
            exchangeCloudflareRefreshToken(client, oauth, refreshToken.toCharArray(), json)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            throw CloudflareApiException("AUTHORIZATION_REQUIRED", "Cloudflare 授权已过期且续期失败，请重新授权")
        }
        return try {
            val newAccess = result.accessToken.concatToString()
            val storedPayload = json.encodeToString(
                mapOf("access_token" to newAccess, "refresh_token" to result.refreshToken?.concatToString()),
            ).toCharArray()
            try {
                credentials.saveAgentAuthorization(authorization.credentialReference, storedPayload)
            } finally {
                storedPayload.fill(ZERO_CHAR)
            }
            dao.upsertCloudflareAuthorization(
                authorization.copy(expiresAt = result.expiresInSeconds?.let { System.currentTimeMillis() + it * 1000L }),
            )
            newAccess
        } finally {
            result.accessToken.fill(ZERO_CHAR)
            result.refreshToken?.fill(ZERO_CHAR)
        }
    }
}

/** access token 距到期不足这个余量就先续期，避免请求刚好卡在过期点上。 */
private const val TOKEN_REFRESH_MARGIN_MILLIS = 5 * 60 * 1000L

/** 安全存储里的 CharArray 用完清零。 */
private val ZERO_CHAR = Char(0)

/** 保存 OAuth Token 的安全存储和 Cloudflare Computer 的本地配置。 */
class CloudflareComputerManager(
    private val dao: ComputerDao,
    private val credentials: ComputerCredentialStore,
    private val api: CloudflareApiClient,
    private val json: Json = Json,
) : AutoCloseable {
    data class Details(
        val config: CloudflareComputerConfigEntity,
        val authorization: CloudflareAuthorizationEntity?,
        val latestHealth: CloudflareWorkerHealthEntity? = null,
    )

    /** 返回详情页需要的非敏感绑定信息；Token 永远不会从该接口返回。 */
    suspend fun details(computerId: String): Details? = dao.getCloudflareConfig(computerId)?.let { config ->
        Details(config, dao.getCloudflareAuthorization(config.authorizationId), dao.getLatestCloudflareWorkerHealth(computerId))
    }

    /**
     * 保存 Worker 健康探测的摘要，供详情页在 App 重启后继续显示。
     * 只保存状态、HTTP 状态和耗时，绝不保存响应正文。
     */
    suspend fun recordWorkerHealth(computerId: String, workerName: String, health: CloudflareWorkerHealth) {
        require(computerId.isNotBlank() && workerName.isNotBlank()) { "Worker 健康记录目标无效" }
        dao.upsertCloudflareWorkerHealth(
            CloudflareWorkerHealthEntity(
                computerId = computerId,
                workerName = workerName,
                status = health.status.name,
                httpStatus = health.httpStatus,
                latencyMs = health.latencyMs,
                checkedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** 从安全存储读取当前身份，仅用于重新向 Cloudflare 校验 Account 列表。 */
    suspend fun listAccountsForComputer(computerId: String): List<CloudflareApiAccount> {
        val config = dao.getCloudflareConfig(computerId) ?: throw IllegalStateException("Cloudflare Computer 不存在")
        val authorization = dao.getCloudflareAuthorization(config.authorizationId)
            ?: throw IllegalStateException("Cloudflare 授权不存在")
        check(!authorization.revoked && (authorization.expiresAt == null || authorization.expiresAt > System.currentTimeMillis())) { "Cloudflare 授权已失效，请重新授权" }
        val payload = credentials.loadAgentAuthorization(authorization.credentialReference)
            ?: throw IllegalStateException("Cloudflare Token 不存在")
        return try {
            val accessToken = runCatching { json.parseToJsonElement(payload.concatToString()).jsonObject["access_token"]?.jsonPrimitive?.content }
                .getOrNull().orEmpty()
            check(accessToken.isNotBlank()) { "Cloudflare Token 格式无效" }
            api.withToken(accessToken).use { scopedApi -> scopedApi.listAccounts() }
        } finally { payload.fill('\u0000') }
    }
    suspend fun listAccounts(): List<CloudflareApiAccount> = api.listAccounts()

    /** 切换本地绑定的 Account；不会删除或修改原 Account 下的云资源。 */
    suspend fun switchAccount(computerId: String, account: CloudflareApiAccount) {
        switchAccount(computerId, account.id)
    }

    /** 只允许切换到当前 OAuth 身份真实返回的 Account。 */
    suspend fun switchAccount(computerId: String, accountId: String): CloudflareApiAccount {
        val previous = details(computerId) ?: throw IllegalStateException("Cloudflare Computer 不存在")
        val auth = previous.authorization ?: throw IllegalStateException("Cloudflare 授权不存在")
        val account = listAccountsForComputer(computerId).firstOrNull { it.id == accountId }
            ?: throw IllegalArgumentException("该 Account 不属于当前 Cloudflare 身份")
        check(dao.switchCloudflareAccountIfCurrent(
            computerId, previous.config.accountId, auth.authorizationId, auth.generation,
            account.id, account.name, System.currentTimeMillis(),
        ) == 1) { "Cloudflare 授权或 Account 已变化，请刷新后重试" }
        return account
    }

    /**
     * 替换当前授权的 Token。新身份必须仍能访问当前绑定的 Account，
     * 否则拒绝写入，避免重新授权后悄悄改变云端目标。
     */
    suspend fun reauthorize(computerId: String, tokenResult: CloudflareTokenExchangeResult) {
        val current = details(computerId) ?: throw IllegalStateException("Cloudflare Computer 不存在")
        val auth = current.authorization ?: throw IllegalStateException("Cloudflare 授权不存在")
        require(tokenResult.accessToken.isNotEmpty()) { "Cloudflare Token 为空" }
        val newReference = "cloudflare-token-${auth.authorizationId}-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val payload = buildTokenPayload(tokenResult)
        var committed = false
        try {
            credentials.saveAgentAuthorization(newReference, payload)
            val available = api.withToken(tokenResult.accessToken.concatToString()).use { scopedApi -> scopedApi.listAccounts() }
            check(available.any { it.id == current.config.accountId }) { "新授权无法访问当前 Account" }
            val identity = runCatching {
                api.withToken(tokenResult.accessToken.concatToString()).use { scopedApi -> scopedApi.currentUser() }
            }.getOrNull()
            val changed = dao.replaceCloudflareAuthorization(
                auth.authorizationId, auth.generation, newReference,
                json.encodeToString(tokenResult.scope), now,
                tokenResult.expiresInSeconds?.let { now + it * 1000L },
                identity?.displayName ?: identity?.email,
            )
            check(changed == 1) { "Cloudflare 授权已变化，请重新读取后再试" }
            committed = true
            dao.markCloudflareComputersAuthorized(auth.authorizationId, auth.generation + 1, now)
            credentials.deleteAgentAuthorization(auth.credentialReference)
        } catch (error: Throwable) {
            if (!committed) runCatching { credentials.deleteAgentAuthorization(newReference) }
            throw error
        } finally {
            payload.fill('\u0000')
            tokenResult.accessToken.fill('\u0000')
            tokenResult.refreshToken?.fill('\u0000')
        }
    }

    /** 退出登录只撤销本地授权；generation 递增后所有旧请求均失效。 */
    suspend fun logout(computerId: String) {
        val config = dao.getCloudflareConfig(computerId) ?: return
        val authorization = dao.getCloudflareAuthorization(config.authorizationId) ?: return
        dao.revokeCloudflareAuthorization(authorization.authorizationId)
        dao.markCloudflareComputersUnauthorized(authorization.authorizationId)
        credentials.deleteAgentAuthorization(authorization.credentialReference)
    }

    /** 删除本地 Computer 及引用，绝不删除 Cloudflare 云端资源。 */
    suspend fun deleteLocalComputer(computerId: String) {
        val config = dao.getCloudflareConfig(computerId)
        if (config != null) {
            val authorization = dao.getCloudflareAuthorization(config.authorizationId)
            // 一个 OAuth 身份可以被多个 Computer 复用；删除其中一个不能撤销其他目标的授权。
            val isLastReference = dao.countCloudflareComputerReferences(config.authorizationId) <= 1
            dao.deleteCloudflareConfig(computerId)
            if (isLastReference) authorization?.let {
                dao.deleteCloudflareAuthorization(authorization.authorizationId)
                credentials.deleteAgentAuthorization(authorization.credentialReference)
            }
        }
        dao.deleteComputer(computerId)
    }

    /**
     * 用已完成的 Token Exchange 创建 Computer。
     * accountId 必须来自本次授权返回的 Account 列表，禁止由模型自由填写未知账号。
     */
    suspend fun createComputer(
        displayName: String,
        tokenResult: CloudflareTokenExchangeResult,
        account: CloudflareApiAccount,
    ): Computer {
        require(displayName.trim().isNotEmpty() && displayName.length <= 80) { "Cloudflare 名称无效" }
        require(account.id.isNotBlank() && account.name.isNotBlank()) { "Cloudflare Account 无效" }
        require(tokenResult.accessToken.isNotEmpty()) { "Cloudflare Token 为空" }

        val computerId = "cloudflare_${UUID.randomUUID()}"
        val authorizationId = "cf-auth-${UUID.randomUUID()}"
        val credentialReference = "cloudflare-token-$authorizationId"
        val now = System.currentTimeMillis()
        val tokenPayload = buildTokenPayload(tokenResult)
        try {
            credentials.saveAgentAuthorization(credentialReference, tokenPayload)
            // UI 传入的 Account 只是候选值；持久化前必须用本次 Token 再从 Cloudflare
            // 读取一次列表，防止调用方或恢复数据伪造一个当前身份不可访问的 Account。
            val availableAccounts = api.withToken(tokenResult.accessToken.concatToString()).use { scopedApi ->
                scopedApi.listAccounts()
            }
            val identity = runCatching {
                api.withToken(tokenResult.accessToken.concatToString()).use { scopedApi -> scopedApi.currentUser() }
            }.getOrNull()
            check(availableAccounts.any { it.id == account.id && it.name == account.name }) {
                "所选 Cloudflare Account 不属于当前授权身份"
            }
            val authorization = CloudflareAuthorizationEntity(
                    authorizationId = authorizationId,
                    credentialReference = credentialReference,
                    grantedScopesJson = json.encodeToString(tokenResult.scope),
                    issuedAt = now,
                    expiresAt = tokenResult.expiresInSeconds?.let { now + it * 1000L },
                    revoked = false,
                    generation = 0L,
                    identityDisplayName = identity?.displayName ?: identity?.email,
                )
            val computer = Computer(
                id = computerId,
                displayName = displayName.trim(),
                provider = ComputerProvider.CLOUDFLARE,
                providerConfigRef = computerId,
                host = "cloudflare",
                port = 443,
                username = "cloudflare",
                authKind = ComputerAuthKind.PASSWORD,
                runMode = ComputerRunMode.DIRECT,
                status = ComputerStatus.READY,
            )
            val config = CloudflareComputerConfigEntity(
                    computerId = computerId,
                    authorizationId = authorizationId,
                    accountId = account.id,
                    accountName = account.name,
                    capabilitiesJson = json.encodeToString(
                        CloudflareComputerProviderContract.let { contract ->
                            ComputerCapability.entries.filter(contract::supports).toSet()
                        },
                    ),
                )
            dao.saveCloudflareComputer(authorization, computer.toEntity(json), config)
            return computer
        } catch (error: Throwable) {
            runCatching { credentials.deleteAgentAuthorization(credentialReference) }
            throw error
        } finally {
            tokenPayload.fill('\u0000')
            tokenResult.accessToken.fill('\u0000')
            tokenResult.refreshToken?.fill('\u0000')
        }
    }

    private fun buildTokenPayload(result: CloudflareTokenExchangeResult): CharArray {
        val refresh = result.refreshToken?.concatToString()
        val access = result.accessToken.concatToString()
        return json.encodeToString(mapOf("access_token" to access, "refresh_token" to refresh)).toCharArray()
    }

    override fun close() = api.close()
}
