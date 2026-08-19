package com.android.everytalk.data.agent

import com.android.everytalk.data.database.entities.AgentRunEntity
import com.android.everytalk.util.AppLogger
import java.util.UUID

/** 恢复日志只记录关联 ID 和决策，禁止写入 Prompt、密钥、工具参数或 continuation 内容。 */
object AgentRecoveryDiagnostics {
    val processInstanceId: String = UUID.randomUUID().toString()

    fun record(
        run: AgentRunEntity,
        recoveryDecision: String,
        serviceStartReason: String,
        requestId: String? = null,
        providerProtocol: String? = null,
        networkState: String? = null,
    ) {
        AppLogger.debug(
            "AgentRecovery",
            "processInstanceId=$processInstanceId runId=${run.id} requestId=${requestId.orEmpty()} " +
                "requestOrdinal=${run.currentRequestOrdinal} serviceStartReason=$serviceStartReason " +
                "previousRunStatus=${run.status} recoveryDecision=$recoveryDecision " +
                "providerProtocol=${providerProtocol.orEmpty()} networkState=${networkState.orEmpty()}",
        )
    }
}
