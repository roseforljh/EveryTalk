package com.android.everytalk.acceptance

import com.android.everytalk.data.agent.AgentRunStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证远端完成后能接回原 AgentRun，并在模型失败后继续等待恢复。 */
class AgentBackgroundPlanAgentRunContractTest {
    private val dao = AgentBackgroundPlanTestFiles.source("data/database/daos/AgentDao.kt")
    private val runStore = AgentBackgroundPlanTestFiles.source("data/agent/AgentRunStore.kt")
    private val allSources = AgentBackgroundPlanTestFiles.allProductionKotlin()

    @Test
    fun `模型待续写状态必须存在且有真实写入方`() {
        assertTrue(AgentRunStatus.entries.contains(AgentRunStatus.MODEL_CONTINUATION_PENDING))
        val writers = allSources.filter { (file, text) ->
            !file.invariantSeparatorsPath.endsWith("AgentModels.kt") &&
                text.contains("AgentRunStatus.MODEL_CONTINUATION_PENDING")
        }
        assertTrue("MODEL_CONTINUATION_PENDING 只有枚举，没有写入逻辑", writers.isNotEmpty())
    }

    @Test
    fun `待续写Run查询必须有消费方`() {
        assertTrue(dao.contains("getPendingModelContinuationRuns"))
        val consumers = AgentBackgroundPlanTestFiles.occurrencesOutsideDefinition(
            "getPendingModelContinuationRuns(",
            "data/database/daos/AgentDao.kt",
        )
        assertTrue("待续写 Run 查询没有调用方", consumers.isNotEmpty())
    }

    @Test
    fun `所有生命周期和连接事件必须有真实生产方`() {
        val required = listOf(
            "AgentTerminalReasons.USER_STOP",
            "AgentTerminalReasons.APP_INTERRUPTED",
            "AgentTerminalReasons.SYSTEM_RECOVERED",
            "AgentTerminalReasons.CONNECTION_LOST",
            "AgentTerminalReasons.RECONNECTED",
            "AgentTerminalReasons.VPS_RESTARTED",
            "AgentTerminalReasons.REMOTE_TASK_MISSING",
            "AgentTerminalReasons.REMOTE_PROCESS_TERMINATED",
            "AgentTerminalReasons.CONFIG_ERROR",
        )
        val source = allSources
            .filterNot { (file, _) -> file.invariantSeparatorsPath.endsWith("AgentModels.kt") }
            .joinToString("\n") { it.second }
        required.forEach { marker -> assertTrue("事件 $marker 没有生产方", source.contains(marker)) }
    }

    @Test
    fun `远端终态必须先持久化ToolResult再调用模型`() {
        val continuationSource = allSources
            .filter { (_, text) -> text.contains("MODEL_CONTINUATION_PENDING") }
            .joinToString("\n") { it.second }
        assertTrue("缺少远端结果写入 AgentRun 的逻辑", continuationSource.contains("appendToolResult"))
        assertTrue("缺少恢复原 AgentRun 的逻辑", continuationSource.contains("resume") || continuationSource.contains("continueRun"))
        val resultIndex = continuationSource.indexOf("appendToolResult")
        val resumeIndex = listOf(continuationSource.indexOf("resume"), continuationSource.indexOf("continueRun"))
            .filter { it >= 0 }
            .minOrNull() ?: -1
        assertTrue("必须先保存 ToolResult，再进行模型续写", resultIndex >= 0 && resumeIndex > resultIndex)
    }

    @Test
    fun `模型续写失败必须保留待续写状态而不是标记Run失败`() {
        val continuationSource = allSources
            .filter { (_, text) -> text.contains("MODEL_CONTINUATION_PENDING") }
            .joinToString("\n") { it.second }
        assertTrue("续写异常必须重新保存 MODEL_CONTINUATION_PENDING", continuationSource.contains("catch") && continuationSource.contains("MODEL_CONTINUATION_PENDING"))
        assertFalse(
            "续写暂时失败不能直接把 Run 永久标为 FAILED",
            continuationSource.contains("MODEL_CONTINUATION_PENDING") && continuationSource.contains("markApprovalResumeFailure"),
        )
    }

    @Test
    fun `同一完成结果必须经过Execution原子声明后才接回`() {
        val source = allSources.joinToString("\n") { it.second }
        assertTrue(
            "接回前必须调用 claim/markResultAttached 之类的单次声明",
            source.contains("claimResult") || source.contains("markResultAttached") || source.contains("attachResultOnce"),
        )
    }

    @Test
    fun `同一会话多任务必须按完成时间排序续写`() {
        val source = allSources
            .filter { (_, text) -> text.contains("getPendingModelContinuationRuns") || text.contains("resultAttachedAt") }
            .joinToString("\n") { it.second }
        assertTrue(
            "待续写任务必须按 finishedAt/remoteFinishedAt 排序",
            source.contains("ORDER BY") && (source.contains("finishedAt") || source.contains("remoteFinishedAt")),
        )
    }

    @Test
    fun `上下文只注入活动任务摘要不注入无界完整日志`() {
        assertTrue(runStore.contains("ComputerSessionState") || allSources.any { it.second.contains("ComputerSessionState") })
        val contextManager = AgentBackgroundPlanTestFiles.source("data/agent/AgentContextManager.kt")
        assertTrue("活动任务摘要必须有长度上限", contextManager.contains("take(") || contextManager.contains("coerceAtMost"))
        assertFalse("上下文管理器不能直接读取 stdout.log", contextManager.contains("stdout.log"))
        assertFalse("上下文管理器不能直接读取 stderr.log", contextManager.contains("stderr.log"))
    }

    @Test
    fun `活动任务摘要必须让AI知道任务身份状态和所属服务器`() {
        val summarySources = AgentBackgroundPlanTestFiles.allProductionKotlin()
            .filter { (_, text) -> text.contains("ComputerSessionState") }
            .joinToString("\n") { it.second }
        assertTrue("任务摘要缺少 executionId", summarySources.contains("executionId") || summarySources.contains("execution_id"))
        assertTrue("任务摘要缺少 remoteStatus/status", summarySources.contains("remoteStatus") || summarySources.contains("status"))
        assertTrue(
            "任务摘要缺少 computerId、computerName 或可反查服务器的 workspaceId",
            summarySources.contains("computerId") || summarySources.contains("computerName") || summarySources.contains("workspaceId"),
        )
    }

    @Test
    fun `旧任务运行时不得阻塞用户发送新的无关消息`() {
        val sendFlow = AgentBackgroundPlanTestFiles.source("statecontroller/message/MessageSenderSendFlow.kt")
        assertFalse(
            "发送入口不能因为存在 WAITING_REMOTE_EXECUTION 就直接 return",
            sendFlow.contains("WAITING_REMOTE_EXECUTION") && sendFlow.contains("return@launch"),
        )
        assertFalse(
            "发送入口不能因为存在活动远端 Execution 就拒绝普通消息",
            sendFlow.contains("getActiveRemoteExecutions") && sendFlow.contains("return@launch"),
        )
    }
}
