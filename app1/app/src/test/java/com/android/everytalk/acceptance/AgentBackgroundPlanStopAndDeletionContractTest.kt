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
    private val computerManagerCode = AgentBackgroundPlanTestFiles.code("statecontroller/viewmodel/ComputerManager.kt")

    @Test
    fun `用户点击停止必须持久化USER_STOP`() {
        val stopBlock = apiHandler.substringAfter("private fun launchStopConfirmation", "")
            .substringBefore("private fun completeConfirmedAgentStop", "")
        assertTrue(
            "输入框停止没有保存 USER_STOP",
            stopBlock.contains("agentRunStore.cancelActiveRunByVisibleMessage(operation.messageId, AgentTerminalReasons.USER_STOP)"),
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
        val deleteUi = AgentBackgroundPlanTestFiles.source("ui/screens/MainScreen/drawer/AppDrawerContent.kt") +
            AgentBackgroundPlanTestFiles.source("ui/components/appchrome/AppTopBar.kt")
        assertTrue("删除入口必须先经过确认对话框", deleteUi.contains("showDeleteConfirm") || deleteUi.contains("showDeleteDialog"))
    }

    @Test
    fun `确认删除会话先清本地Workspace再删除聊天`() {
        val commandEntry = historyManager.substringAfter("executeDeleteConversationInternal", "")
            .substringBefore("private suspend fun deleteConversationInternal", "")
        assertTrue("删除命令入口必须调用真实删除流程", commandEntry.contains("deleteConversationInternal("))
        val deleteBlock = computerManagerCode.substringAfter("suspend fun deleteWorkspacesForConversation", "")
            .substringBefore("suspend fun disconnect", "")
        val historyDeleteBlock = historyManager.substringAfter("deleteConversationWorkspaces(sessionId)", "")
            .substringBefore("historicalConversations.value", "")
        assertTrue("删除会话必须清掉对应 Workspace", deleteBlock.contains("removeLocalWorkspace"))
        assertTrue("Workspace 清理成功后才能删除聊天 Session", historyDeleteBlock.contains("deleteHistorySession"))
    }

    @Test
    fun `删除服务器配置不得取消任务或删除VPS文件`() {
        val deleteBlock = computerManager.substringAfter("suspend fun deleteComputer", "")
            .substringBefore("fun respondToPublicPreview", "")
        assertFalse("删除服务器配置默认不得调用 cancelActiveExecutions", deleteBlock.contains("cancelActiveExecutions"))
        assertFalse("删除服务器配置不得删除 VPS 文件", deleteBlock.contains("deleteRemote"))
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
