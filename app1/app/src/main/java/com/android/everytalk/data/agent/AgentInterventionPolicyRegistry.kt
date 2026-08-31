package com.android.everytalk.data.agent

/**
 * 本地可信 Policy Registry。
 * 模型只提交 capability 名称，字段、Adapter、投递方式和 Continuation 由这里固定决定。
 */
class AgentInterventionPolicyRegistry {
    enum class Compatibility { COMPATIBLE, POLICY_STALE, ADAPTER_CONTRACT_STALE, CAPABILITY_REMOVED }

    data class Policy(
        val capability: String,
        val policyVersion: String,
        val adapterContractVersion: String,
        val continuation: AgentContinuationKind,
        val materialKind: ResolutionMaterialKind,
        val audience: String,
        val minimumSource: InterventionRequestSource = InterventionRequestSource.MODEL_HINT,
    )

    private val policies = mapOf(
        "git.push" to Policy("git.push", "1", "1", AgentContinuationKind.RETRY_TOOL, ResolutionMaterialKind.DURABLE_REFERENCE, "git-adapter"),
        "ssh.connect" to Policy("ssh.connect", "1", "1", AgentContinuationKind.VERIFY_THEN_RESUME, ResolutionMaterialKind.EPHEMERAL, "ssh-adapter"),
        "privilege.sudo.execute" to Policy(
            "privilege.sudo.execute",
            "1",
            "1",
            AgentContinuationKind.CONTINUE_PTY,
            ResolutionMaterialKind.EPHEMERAL,
            "attested-privilege-adapter",
            InterventionRequestSource.EXECUTOR_PROVEN,
        ),
        "terminal.interaction" to Policy("terminal.interaction", "1", "1", AgentContinuationKind.CONTINUE_PTY, ResolutionMaterialKind.EPHEMERAL, "pty-adapter"),
        "server.restart.confirm" to Policy("server.restart.confirm", "1", "1", AgentContinuationKind.VERIFY_THEN_RESUME, ResolutionMaterialKind.NONE, "acknowledgement-adapter"),
        "skill.openai_api_access" to Policy("skill.openai_api_access", "1", "1", AgentContinuationKind.RESUME_AGENT_LOOP, ResolutionMaterialKind.DURABLE_REFERENCE, "skill-capability-proxy"),
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
