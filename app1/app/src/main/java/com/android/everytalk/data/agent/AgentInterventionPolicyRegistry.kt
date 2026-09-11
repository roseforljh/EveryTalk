package com.android.everytalk.data.agent

/**
 * 本地可信 Policy Registry。
 * 模型只提交 capability 名称，字段、Adapter、投递方式和 Continuation 由这里固定决定。
 */
class AgentInterventionPolicyRegistry {
    enum class Compatibility { COMPATIBLE, POLICY_STALE, ADAPTER_CONTRACT_STALE, CAPABILITY_REMOVED }
    enum class FieldKind { CONFIRMATION, SENSITIVE_TEXT, AUTHORIZATION_SECRET }

    data class Field(
        val id: String,
        val label: String,
        val kind: FieldKind,
        val required: Boolean = true,
    )

    data class Policy(
        val capability: String,
        val policyVersion: String,
        val adapterContractVersion: String,
        val continuation: AgentContinuationKind,
        val materialKind: ResolutionMaterialKind,
        val audience: String,
        val fields: List<Field>,
        val leaseKind: String? = null,
        val minimumSource: InterventionRequestSource = InterventionRequestSource.MODEL_HINT,
    )

    private val policies = mapOf(
        "git.push" to Policy(
            "git.push", "1", "1", AgentContinuationKind.RETRY_TOOL,
            ResolutionMaterialKind.DURABLE_REFERENCE, "git-adapter",
            listOf(Field("authorization", "GitHub Token", FieldKind.AUTHORIZATION_SECRET)),
            "ADAPTER_TARGET",
        ),
        "ssh.connect" to Policy(
            "ssh.connect", "1", "1", AgentContinuationKind.VERIFY_THEN_RESUME,
            ResolutionMaterialKind.EPHEMERAL, "ssh-adapter",
            listOf(Field("password", "SSH 密码", FieldKind.SENSITIVE_TEXT)),
            "SSH_CONNECTION",
        ),
        "privilege.sudo.execute" to Policy(
            "privilege.sudo.execute",
            "1",
            "1",
            AgentContinuationKind.CONTINUE_PTY,
            ResolutionMaterialKind.EPHEMERAL,
            "attested-privilege-adapter",
            listOf(Field("password", "sudo 密码", FieldKind.SENSITIVE_TEXT)),
            "PRIVILEGE_CHALLENGE",
            InterventionRequestSource.EXECUTOR_PROVEN,
        ),
        "terminal.interaction" to Policy(
            "terminal.interaction", "1", "1", AgentContinuationKind.CONTINUE_PTY,
            ResolutionMaterialKind.EPHEMERAL, "pty-adapter",
            listOf(Field("input", "终端输入", FieldKind.SENSITIVE_TEXT)),
            "PTY",
        ),
        "server.restart.confirm" to Policy(
            "server.restart.confirm", "1", "1", AgentContinuationKind.VERIFY_THEN_RESUME,
            ResolutionMaterialKind.NONE, "acknowledgement-adapter",
            listOf(Field("confirmed", "确认当前操作", FieldKind.CONFIRMATION)),
        ),
        "skill.openai_api_access" to Policy(
            "skill.openai_api_access", "1", "1", AgentContinuationKind.RESUME_AGENT_LOOP,
            ResolutionMaterialKind.DURABLE_REFERENCE, "skill-capability-proxy",
            listOf(Field("authorization", "OpenAI API Key", FieldKind.AUTHORIZATION_SECRET)),
            "ADAPTER_TARGET",
        ),
        "server.env.secret" to Policy(
            "server.env.secret", "1", "1", AgentContinuationKind.RESUME_AGENT_LOOP,
            ResolutionMaterialKind.EPHEMERAL, "workspace-secret-adapter",
            listOf(Field("secret", "服务器环境变量值", FieldKind.SENSITIVE_TEXT)),
            "ADAPTER_TARGET",
        ),
        "cloudflare.reauthorize" to Policy(
            "cloudflare.reauthorize", "1", "1", AgentContinuationKind.RETRY_TOOL,
            ResolutionMaterialKind.NONE, "cloudflare-reauthorization-adapter",
            listOf(Field("confirmed", "完成 Cloudflare 重新授权", FieldKind.CONFIRMATION)),
            "CLOUDFLARE_AUTHORIZATION",
            InterventionRequestSource.EXECUTOR_PROVEN,
        ),
        "cloudflare.resource.select" to Policy(
            "cloudflare.resource.select", "1", "1", AgentContinuationKind.RETRY_TOOL,
            ResolutionMaterialKind.NONE, "cloudflare-resource-selection-adapter",
            emptyList(), minimumSource = InterventionRequestSource.EXECUTOR_PROVEN,
        ),
    )

    fun resolve(capability: String): Policy? = policies[capability]

    /** 恢复只能沿用创建 Suspension 时固定的安全合约，禁止用新版策略静默重解释。 */
    fun compatibility(
        capability: String,
        policyVersion: String,
        adapterContractVersion: String,
    ): Compatibility {
        val policy = policies[capability] ?: return Compatibility.CAPABILITY_REMOVED
        if (policy.policyVersion != policyVersion) return Compatibility.POLICY_STALE
        if (policy.adapterContractVersion != adapterContractVersion) return Compatibility.ADAPTER_CONTRACT_STALE
        return Compatibility.COMPATIBLE
    }
}
