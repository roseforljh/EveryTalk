package com.android.everytalk.acceptance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证手动停止、系统中断、服务器删除和会话删除的边界。 */
class AgentBackgroundPlanStopAndDeletionContractTest {
    private val dao = AgentBackgroundPlanTestFiles.source("data/database/daos/ComputerDao.kt")
    private val executor = AgentBackgroundPlanTestFiles.source("data/computer/ComputerToolExecutor.kt")
    private val apiHandler = AgentBackgroundPlanTestFiles.source("statecontroller/api/ApiHandler.kt")
    private val historyManager = AgentBackgroundPlanTestFiles.source("ui/screens/viewmodel/HistoryManager.kt")
    private val computerManager = AgentBackgroundPlanTestFiles.source("statecontroller/viewmodel/ComputerManager.kt")

    @Test
    fun `停止必须按当前AgentRun查询而不是按整个会话`() {
        assertTrue("ComputerToolExecutor.cancelActiveExecutions 必须接收 runId", executor.contains("runId"))
        assertTrue(
            "DAO 必须按持久 Run 关联查询",
            dao.contains("agentRunId = :agentRunId") || dao.contains("runId = :runId"),
        )
        assertFalse(
            "停止入口不能继续只把 conversationId 传给取消器",
            executor.contains("cancelActiveExecutions(conversationId: String)"),
        )
    }

    @Test
    fun `停止必须包含前台和RETURN_HANDLE后台任务`() {
        val cancelQuery = dao.substringAfter("停止按钮", "")
            .substringBefore("先记录取消意图", "")
        assertFalse(
            "取消查询不得排除 RETURN_HANDLE",
            cancelQuery.contains("completionMode != 'RETURN_HANDLE'") || cancelQuery.contains("completionMode != \"RETURN_HANDLE\""),
        )
        assertTrue("取消查询必须覆盖 STARTING", cancelQuery.contains("STARTING"))
        assertTrue("取消查询必须覆盖 RUNNING", cancelQuery.contains("RUNNING"))
    }

    @Test
    fun `用户点击停止必须先写USER_STOP再写取消意图再发SSH`() {
        val stopBlock = apiHandler.substringAfter("specificCancelReason", "")
            .substringBefore("jobToCancel?.cancel", "")
        val userStop = stopBlock.indexOf("AgentTerminalReasons.USER_STOP")
        val cancelCall = stopBlock.indexOf("cancelComputerExecutions")
        assertTrue("输入框停止没有保存 USER_STOP", userStop >= 0)
        assertTrue("必须先保存 USER_STOP，再发远端取消", cancelCall > userStop)
        assertTrue("ComputerToolExecutor 必须先标记取消意图", executor.indexOf("markRemoteExecutionCancellationRequested") < executor.indexOf("cancelRemoteExecution"))
    }

    @Test
    fun `取消和完成竞争必须以VPS返回终态为准`() {
        val cancelBlock = executor.substringAfter("markRemoteExecutionCancellationRequested", "")
            .substringBefore("在模型首轮思考期间", "")
        assertTrue("取消结果必须读取远端 snapshot/status", cancelBlock.contains("snapshot") && cancelBlock.contains("status"))
        assertFalse(
            "发出取消请求后不能无条件把本地状态覆盖成 CANCELLED",
            cancelBlock.contains("ComputerExecutionStatus.CANCELLED") && !cancelBlock.contains("snapshot.status"),
        )
    }

    @Test
    fun `系统回收和普通生命周期变化不得调用远端取消`() {
        val application = AgentBackgroundPlanTestFiles.source("application/EveryTalkApplication.kt")
        assertFalse(application.contains("cancelActiveExecutions"))
        assertFalse(application.contains("AgentTerminalReasons.USER_STOP"))
        val destroyBlock = AgentBackgroundPlanTestFiles.source("service/ComputerConnectionService.kt").substringAfter("onDestroy", "")
        assertFalse("前台服务被销毁不能取消 VPS 任务", destroyBlock.contains("cancelRemoteExecution") || destroyBlock.contains("cancelActiveExecutions"))
    }

    @Test
    fun `删除会话前必须检查活动任务并要求确认`() {
        val deleteBlock = historyManager.substringAfter("deleteConversationInternal", "")
            .substringBefore("persistHistoryListDirectly", "")
        assertTrue("删除会话前必须查询 Workspace 或活动 Execution", deleteBlock.contains("Workspace") || deleteBlock.contains("activeExecution"))
        assertTrue("存在活动任务时必须进入确认流程", deleteBlock.contains("confirm") || deleteBlock.contains("Confirmation"))
    }

    @Test
    fun `确认删除会话必须先取消任务成功后再删除Workspace和聊天`() {
        val deleteBlock = historyManager.substringAfter("deleteConversationInternal", "")
            .substringBefore("persistHistoryListDirectly", "")
        val cancelIndex = listOf(deleteBlock.indexOf("cancel"), deleteBlock.indexOf("stop"))
            .filter { it >= 0 }
            .minOrNull() ?: -1
        val workspaceIndex = deleteBlock.indexOf("deleteWorkspace")
        val sessionIndex = deleteBlock.indexOf("deleteHistorySession")
        assertTrue("删除会话必须先取消活动任务", cancelIndex >= 0)
        assertTrue("取消成功后必须删除对应 Workspace", workspaceIndex > cancelIndex)
        assertTrue("最后才能删除聊天 Session", sessionIndex > workspaceIndex)
        assertTrue("取消失败必须中止删除并返回原因", deleteBlock.contains("return") && (deleteBlock.contains("failed") || deleteBlock.contains("失败")))
    }

    @Test
    fun `删除服务器配置默认不得取消任务或删除VPS文件`() {
        val deleteBlock = computerManager.substringAfter("suspend fun deleteComputer", "")
            .substringBefore("fun respondToPublicPreview", "")
        assertFalse("删除服务器配置默认不得调用 cancelActiveExecutions", deleteBlock.contains("cancelActiveExecutions"))
        assertTrue("远端文件删除必须受用户显式参数控制", deleteBlock.contains("deleteRemoteFiles"))
    }

    @Test
    fun `删除服务器配置前必须提示仍在运行的任务将失去管理`() {
        val detailScreen = AgentBackgroundPlanTestFiles.source("ui/screens/computer/ComputerDetailScreen.kt")
        val deleteDialog = detailScreen.substringAfter("private fun ComputerDeleteDialog", "")
        assertTrue("删除服务器对话框必须接收或查询活动任务数量", deleteDialog.contains("activeTask") || deleteDialog.contains("runningTask"))
        assertTrue("存在活动任务时必须显示明确警告", deleteDialog.contains("warning") || deleteDialog.contains("Warning"))
    }

    @Test
    fun `Workspace必须保持一会话一工作空间唯一约束`() {
        val entity = AgentBackgroundPlanTestFiles.source("data/database/entities/ComputerEntities.kt")
        assertTrue(entity.contains("Index(value = [\"computerId\", \"conversationId\"], unique = true)"))
    }
}
