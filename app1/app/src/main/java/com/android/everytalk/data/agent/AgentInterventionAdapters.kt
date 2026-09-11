package com.android.everytalk.data.agent

import com.android.everytalk.data.computer.ComputerCredentialStore
import com.android.everytalk.data.computer.ComputerRepository
import com.android.everytalk.data.computer.ComputerWorkspaceSecretManager

/** Adapter 履行结果。UNKNOWN 永远不会被解释成 NOT_DELIVERED。 */
enum class AdapterDeliveryFact { DELIVERED, NOT_DELIVERED, UNKNOWN }

data class AdapterFulfillmentResult(
    val fact: AdapterDeliveryFact,
    val safeSummary: String? = null,
)

interface AgentInterventionAdapter {
    suspend fun validate(request: TrustedInterventionRequest): Boolean
    suspend fun present(request: TrustedInterventionRequest): String
    suspend fun fulfill(request: TrustedInterventionRequest, protectedResolution: ProtectedResolution): AdapterFulfillmentResult
    suspend fun reconcile(request: TrustedInterventionRequest): AdapterDeliveryFact
    suspend fun cleanup(request: TrustedInterventionRequest)
}

/** 无敏感材料的本地确认 Adapter。只确认当前绑定的单次操作，不扩大权限。 */
private object AcknowledgementInterventionAdapter : AgentInterventionAdapter {
    override suspend fun validate(request: TrustedInterventionRequest): Boolean =
        request.resolutionMaterialKind == ResolutionMaterialKind.NONE

    override suspend fun present(request: TrustedInterventionRequest): String = "确认继续当前操作"

    override suspend fun fulfill(
        request: TrustedInterventionRequest,
        protectedResolution: ProtectedResolution,
    ): AdapterFulfillmentResult = if (protectedResolution === ProtectedResolution.None) {
        AdapterFulfillmentResult(AdapterDeliveryFact.DELIVERED, "用户已确认当前操作")
    } else {
        AdapterFulfillmentResult(AdapterDeliveryFact.NOT_DELIVERED, "确认材料类型不匹配")
    }

    override suspend fun reconcile(request: TrustedInterventionRequest): AdapterDeliveryFact =
        AdapterDeliveryFact.UNKNOWN

    override suspend fun cleanup(request: TrustedInterventionRequest) = Unit
}

/**
 * Cloudflare 重新授权 Adapter。
 * OAuth 浏览器交互由 UI 完成，Adapter 只在用户提交“已完成”后重新读取本地授权事实，
 * 这样点击按钮本身不能绕过授权，也不会把 Token 放进 Agent 接力协议。
 */
class CloudflareReauthorizationAdapter(
    private val authorizationReady: suspend (TrustedInterventionRequest) -> Boolean,
) : AgentInterventionAdapter {
    override suspend fun validate(request: TrustedInterventionRequest): Boolean =
        request.resolutionMaterialKind == ResolutionMaterialKind.NONE &&
            request.parameters["computer_id"].orEmpty().isNotBlank()

    override suspend fun present(request: TrustedInterventionRequest): String = "重新登录 Cloudflare 后继续"

    override suspend fun fulfill(
        request: TrustedInterventionRequest,
        protectedResolution: ProtectedResolution,
    ): AdapterFulfillmentResult = if (protectedResolution === ProtectedResolution.None && authorizationReady(request)) {
        AdapterFulfillmentResult(AdapterDeliveryFact.DELIVERED, "Cloudflare 授权已恢复")
    } else {
        AdapterFulfillmentResult(AdapterDeliveryFact.NOT_DELIVERED, "Cloudflare 授权尚未恢复")
    }

    override suspend fun reconcile(request: TrustedInterventionRequest): AdapterDeliveryFact =
        if (authorizationReady(request)) AdapterDeliveryFact.DELIVERED else AdapterDeliveryFact.NOT_DELIVERED

    override suspend fun cleanup(request: TrustedInterventionRequest) = Unit
}

/** 只接受 App 选择控件已落库且仍属于原目标的选择；点击继续本身不能伪造资源。 */
class CloudflareResourceSelectionAdapter(
    private val selectionReady: suspend (TrustedInterventionRequest) -> Boolean,
) : AgentInterventionAdapter {
    override suspend fun validate(request: TrustedInterventionRequest): Boolean =
        request.capabilityId == "cloudflare.resource.select" && request.resolutionMaterialKind == ResolutionMaterialKind.NONE
    override suspend fun present(request: TrustedInterventionRequest): String = "选择 Cloudflare 资源"
    override suspend fun fulfill(request: TrustedInterventionRequest, protectedResolution: ProtectedResolution): AdapterFulfillmentResult =
        AdapterFulfillmentResult(if (protectedResolution === ProtectedResolution.None && selectionReady(request))
            AdapterDeliveryFact.DELIVERED else AdapterDeliveryFact.NOT_DELIVERED)
    override suspend fun reconcile(request: TrustedInterventionRequest): AdapterDeliveryFact =
        if (selectionReady(request)) AdapterDeliveryFact.DELIVERED else AdapterDeliveryFact.NOT_DELIVERED
    override suspend fun cleanup(request: TrustedInterventionRequest) = Unit
}

/** 长期授权 capability proxy。只验证授权存在和绑定，不把凭据交给模型或任意命令。 */
class StoredAuthorizationCapabilityAdapter(
    private val provider: String,
    private val authorizations: AgentStoredAuthorizationStore,
    private val credentials: ComputerCredentialStore,
) : AgentInterventionAdapter {
    override suspend fun validate(request: TrustedInterventionRequest): Boolean =
        request.resolutionMaterialKind == ResolutionMaterialKind.DURABLE_REFERENCE

    override suspend fun present(request: TrustedInterventionRequest): String = "提供 $provider 授权"

    override suspend fun fulfill(
        request: TrustedInterventionRequest,
        protectedResolution: ProtectedResolution,
    ): AdapterFulfillmentResult {
        val reference = (protectedResolution as? ProtectedResolution.DurableReference)?.reference
            ?: return AdapterFulfillmentResult(AdapterDeliveryFact.NOT_DELIVERED)
        return AdapterFulfillmentResult(if (authorizationAvailable(request, reference)) {
            AdapterDeliveryFact.DELIVERED
        } else {
            AdapterDeliveryFact.NOT_DELIVERED
        })
    }

    override suspend fun reconcile(request: TrustedInterventionRequest): AdapterDeliveryFact {
        val reference = request.resolutionReference ?: return AdapterDeliveryFact.NOT_DELIVERED
        return if (authorizationAvailable(request, reference)) {
            AdapterDeliveryFact.DELIVERED
        } else {
            AdapterDeliveryFact.NOT_DELIVERED
        }
    }

    override suspend fun cleanup(request: TrustedInterventionRequest) = Unit

    private suspend fun authorizationAvailable(request: TrustedInterventionRequest, publicReference: String): Boolean {
        val authorizationId = publicReference.removePrefix("stored-authorization:")
        if (authorizationId == publicReference || authorizationId.isBlank()) return false
        val authorization = authorizations.get(authorizationId) ?: return false
        val workspaceId = request.targetBindingRef.substringAfter(":workspace:", "")
        if (authorization.provider != provider || authorization.userConsentScope != "WORKSPACE" ||
            authorization.workspaceId != workspaceId || authorization.revoked ||
            authorization.expiresAt?.let { it <= System.currentTimeMillis() } == true
        ) return false
        val secret = credentials.loadAgentAuthorization(authorization.credentialReference) ?: return false
        secret.fill('\u0000')
        return true
    }
}

/** 将一次性输入保存为 Workspace Secret；Secret 正文不进入 Agent 结果。 */
class WorkspaceSecretCapabilityAdapter(
    private val secrets: ComputerWorkspaceSecretManager,
    private val repository: ComputerRepository,
) : AgentInterventionAdapter {
    override suspend fun validate(request: TrustedInterventionRequest): Boolean =
        request.resolutionMaterialKind == ResolutionMaterialKind.EPHEMERAL &&
            request.targetBindingRef.contains(":workspace:")

    override suspend fun present(request: TrustedInterventionRequest): String = "提供服务器环境变量 Secret"

    override suspend fun fulfill(
        request: TrustedInterventionRequest,
        protectedResolution: ProtectedResolution,
    ): AdapterFulfillmentResult {
        val material = protectedResolution as? ProtectedResolution.Ephemeral
            ?: return AdapterFulfillmentResult(AdapterDeliveryFact.NOT_DELIVERED)
        val workspaceId = request.targetBindingRef.substringAfter(":workspace:").substringBefore(":")
        val name = request.parameters["name"]?.takeIf { it.isNotBlank() }
            ?: return AdapterFulfillmentResult(AdapterDeliveryFact.NOT_DELIVERED, "缺少 Secret 名称")
        return try {
            secrets.save(workspaceId, name, material.borrow())
            val path = request.parameters["path"]
                ?: return AdapterFulfillmentResult(AdapterDeliveryFact.NOT_DELIVERED, "缺少 .env 路径")
            repository.writeWorkspaceSecretToEnv(workspaceId, name, path)
            AdapterFulfillmentResult(AdapterDeliveryFact.DELIVERED, "Secret 已保存到当前 Workspace")
        } catch (error: Throwable) {
            AdapterFulfillmentResult(AdapterDeliveryFact.NOT_DELIVERED, error.message ?: "Secret 保存失败")
        } finally {
            material.clear()
        }
    }

    override suspend fun reconcile(request: TrustedInterventionRequest): AdapterDeliveryFact =
        AdapterDeliveryFact.UNKNOWN

    override suspend fun cleanup(request: TrustedInterventionRequest) = Unit
}

/** Adapter 注册表只由本地代码维护，模型不能选择 Adapter。 */
class AgentInterventionAdapterRegistry(
    private val adapters: Map<String, AgentInterventionAdapter> = mapOf(
        "acknowledgement-adapter" to AcknowledgementInterventionAdapter,
    ),
) {
    private val all = mapOf("acknowledgement-adapter" to AcknowledgementInterventionAdapter) + adapters
    fun get(audience: String): AgentInterventionAdapter? = all[audience]
    fun contains(audience: String): Boolean = audience in all
}
