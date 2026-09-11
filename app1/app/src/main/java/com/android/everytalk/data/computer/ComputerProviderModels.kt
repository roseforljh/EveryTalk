package com.android.everytalk.data.computer

import kotlinx.serialization.Serializable

/**
 * Computer 的执行提供方。
 *
 * SSH 表示现有 VPS/Container 链路；Cloudflare 表示通过 Cloudflare API 管理云资源。
 * 该枚举不复用 ComputerRunMode，因为 DIRECT/CONTAINER 描述的是 VPS 的执行位置。
 */
@Serializable
enum class ComputerProvider {
    SSH,
    CLOUDFLARE,
}

/**
 * Provider 对外暴露的能力。
 *
 * 能力名称由应用内可信代码声明，Workspace 文件和模型输出都不能新增能力。
 */
@Serializable
enum class ComputerCapability {
    LOCAL_WORKSPACE_READ,
    LOCAL_WORKSPACE_WRITE,
    LOCAL_SHELL_EXECUTE,
    WORKER_LIST,
    WORKER_READ,
    WORKER_CREATE,
    WORKER_UPDATE,
    WORKER_DEPLOY,
    WORKER_STATUS,
    WORKER_LOGS,
    WORKER_DELETE,
    D1_READ,
    D1_WRITE,
    KV_READ,
    KV_WRITE,
    R2_READ,
    R2_WRITE,
    DURABLE_OBJECTS_READ,
    QUEUES_READ,
    QUEUES_WRITE,
}

/** Cloudflare Computer 与 OAuth 授权之间的非敏感绑定信息。 */
@Serializable
data class CloudflareComputerConfig(
    val computerId: String,
    val authorizationId: String,
    val accountId: String,
    val accountName: String? = null,
    val capabilities: Set<ComputerCapability> = setOf(
        ComputerCapability.WORKER_LIST,
        ComputerCapability.WORKER_READ,
        ComputerCapability.WORKER_CREATE,
        ComputerCapability.WORKER_UPDATE,
        ComputerCapability.WORKER_DEPLOY,
        ComputerCapability.WORKER_STATUS,
        ComputerCapability.WORKER_LOGS,
    ),
)

/**
 * Cloudflare OAuth 的安全存储索引。
 *
 * Token 本身不进入这个对象，也不进入 Room；credentialReference 只指向 Android 安全存储中的密文。
 */
@Serializable
data class CloudflareAuthorizationRecord(
    val authorizationId: String,
    val credentialReference: String,
    val grantedScopes: Set<String>,
    val issuedAt: Long,
    val expiresAt: Long? = null,
    val revoked: Boolean = false,
    val generation: Long = 0L,
)

/** Account 和授权代次是请求目标的一部分，不能在重试时改成当前选中的账号。 */
@Serializable
data class CloudflareRequestBinding(
    val accountId: String,
    val authorizationId: String,
    val generation: Long,
)

/**
 * Provider 和每次 HTTP 取凭据共用的身份边界。
 * 授权失效可以进入重新登录流程；账号变化或缺少快照必须重新发起任务，不能静默换目标。
 */
internal fun ComputerRequestContext.requireCloudflareBinding(
    config: CloudflareComputerConfig,
    authorization: CloudflareAuthorizationRecord,
    now: Long = System.currentTimeMillis(),
) {
    val binding = cloudflareBinding
    if (binding == null || computerId != config.computerId || workspaceId.isBlank() ||
        binding.accountId != config.accountId || binding.authorizationId != config.authorizationId ||
        authorization.authorizationId != binding.authorizationId) {
        throw CloudflareApiException("COMPUTER_CONTEXT_MISMATCH", "Cloudflare 请求目标缺失或已变化，请重新发起任务")
    }
    if (authorization.revoked || authorization.expiresAt?.let { it <= now } == true) {
        throw CloudflareApiException("AUTHORIZATION_REQUIRED", "Cloudflare 授权已失效")
    }
    if (binding.generation != authorization.generation) {
        throw CloudflareApiException("REQUEST_CONTEXT_STALE", "Cloudflare 授权已变化，旧请求不能使用新授权")
    }
}

/** App 根据这个结构渲染 OAuth、Account 选择或确认界面。 */
@Serializable
sealed interface ComputerIntervention {
    @Serializable
    data class OAuth(
        val provider: ComputerProvider,
        val scopes: Set<String>,
    ) : ComputerIntervention

    /** 授权已过期、退出或 scope 不足时，UI 应显示“重新授权”入口。 */
    @Serializable
    data class Reauthorization(
        val provider: ComputerProvider,
        val computerId: String? = null,
        val missingScopes: Set<String> = emptySet(),
        val reason: String? = null,
    ) : ComputerIntervention

    @Serializable
    data class AccountSelection(
        val authorizationId: String,
        val accounts: List<CloudflareAccountOption>,
    ) : ComputerIntervention

    /** 资源参数不明确时由 App 展示可信资源列表，模型不能自行猜测资源 ID。 */
    @Serializable
    data class ResourceSelection(
        val provider: ComputerProvider,
        val resourceType: String,
        val resources: List<ResourceOption>,
        val computerId: String? = null,
    ) : ComputerIntervention

    @Serializable
    data class Confirmation(
        val capability: ComputerCapability,
        val summary: String,
    ) : ComputerIntervention
}

/** Account 选择器需要的最小信息，不包含 Token 或完整 API 响应。 */
@Serializable
data class CloudflareAccountOption(
    val id: String,
    val name: String,
)

@Serializable
data class ResourceOption(
    val id: String,
    val displayName: String,
)

/** Provider 工具统一返回的安全结果。 */
@Serializable
data class ComputerProviderResult<T>(
    val ok: Boolean,
    val executionId: String,
    val data: T? = null,
    val errorCode: String? = null,
    val message: String? = null,
    val retryable: Boolean = false,
    val intervention: ComputerIntervention? = null,
)

/**
 * Provider 路由前的最小契约。
 * 具体执行器后续实现该接口；先把能力和错误边界固定下来，避免 UI 或模型直接依赖 API Client。
 */
interface ComputerProviderContract {
    val provider: ComputerProvider

    fun supports(capability: ComputerCapability): Boolean
}

/** SSH 的默认能力集合，保证 Provider 抽象不会改变现有服务器行为。 */
object SshComputerProviderContract : ComputerProviderContract {
    override val provider: ComputerProvider = ComputerProvider.SSH

    override fun supports(capability: ComputerCapability): Boolean = when (capability) {
        ComputerCapability.LOCAL_WORKSPACE_READ,
        ComputerCapability.LOCAL_WORKSPACE_WRITE,
        ComputerCapability.LOCAL_SHELL_EXECUTE -> true
        ComputerCapability.WORKER_LIST,
        ComputerCapability.WORKER_READ,
        ComputerCapability.WORKER_CREATE,
        ComputerCapability.WORKER_UPDATE,
        ComputerCapability.WORKER_DEPLOY,
        ComputerCapability.WORKER_STATUS,
        ComputerCapability.WORKER_LOGS,
        ComputerCapability.WORKER_DELETE,
        ComputerCapability.D1_READ,
        ComputerCapability.D1_WRITE,
        ComputerCapability.KV_READ,
        ComputerCapability.KV_WRITE,
        ComputerCapability.R2_READ,
        ComputerCapability.R2_WRITE,
        ComputerCapability.DURABLE_OBJECTS_READ,
        ComputerCapability.QUEUES_READ,
        ComputerCapability.QUEUES_WRITE -> false
    }
}

/** Cloudflare 只声明云资源能力，不伪装成完整 Linux。 */
object CloudflareComputerProviderContract : ComputerProviderContract {
    override val provider: ComputerProvider = ComputerProvider.CLOUDFLARE

    override fun supports(capability: ComputerCapability): Boolean = when (capability) {
        ComputerCapability.WORKER_LIST,
        ComputerCapability.WORKER_READ,
        ComputerCapability.WORKER_CREATE,
        ComputerCapability.WORKER_UPDATE,
        ComputerCapability.WORKER_DEPLOY,
        ComputerCapability.WORKER_STATUS,
        ComputerCapability.WORKER_LOGS,
        ComputerCapability.WORKER_DELETE,
        ComputerCapability.D1_READ,
        ComputerCapability.D1_WRITE,
        ComputerCapability.KV_READ,
        ComputerCapability.KV_WRITE,
        ComputerCapability.R2_READ,
        ComputerCapability.R2_WRITE,
        ComputerCapability.DURABLE_OBJECTS_READ,
        ComputerCapability.QUEUES_READ,
        ComputerCapability.QUEUES_WRITE -> true
        ComputerCapability.LOCAL_WORKSPACE_READ,
        ComputerCapability.LOCAL_WORKSPACE_WRITE,
        ComputerCapability.LOCAL_SHELL_EXECUTE -> false
    }
}
