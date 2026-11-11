package com.android.everytalk.acceptance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证任务监听已经真正归前台服务所有，不再依赖页面 ViewModel 存活。 */
class AgentBackgroundPlanServiceContractTest {
    private val service = AgentBackgroundPlanTestFiles.source("service/ComputerConnectionService.kt")
    private val serviceCode = AgentBackgroundPlanTestFiles.code("service/ComputerConnectionService.kt")
    private val viewModel = AgentBackgroundPlanTestFiles.source("statecontroller/viewmodel/AppViewModel.kt")
    private val computerManager = AgentBackgroundPlanTestFiles.source("statecontroller/viewmodel/ComputerManager.kt")
    private val apiHandlerCode = AgentBackgroundPlanTestFiles.code("statecontroller/api/ApiHandler.kt")
    private val repository = AgentBackgroundPlanTestFiles.source("data/computer/ComputerRepository.kt")
    private val dao = AgentBackgroundPlanTestFiles.source("data/database/daos/ComputerDao.kt")
    private val agentDao = AgentBackgroundPlanTestFiles.source("data/database/daos/AgentDao.kt")
    private val worker = AgentBackgroundPlanTestFiles.source("service/AgentRecoveryWorker.kt")
    private val workerCode = AgentBackgroundPlanTestFiles.code("service/AgentRecoveryWorker.kt")
    private val coordinator = AgentBackgroundPlanTestFiles.source("data/agent/AgentRunCoordinator.kt")

    @Test
    fun `AgentLoop不能继续由ViewModel协程持有`() {
        val uiOwnedAgentLoops = AgentBackgroundPlanTestFiles
            .codeBlocksAfter(apiHandlerCode, "viewModelScope.launch")
            .filter { block -> block.contains("agentLoop.run(") }

        assertTrue(
            "AgentLoop 仍挂在 viewModelScope；切换 App 或页面重建后模型连接无法由前台服务恢复",
            uiOwnedAgentLoops.isEmpty(),
        )
    }

    @Test
    fun `前台服务必须真实调用AgentRun续写而不是只写注释`() {
        val realContinuationCalls = listOf(
            "agentLoop.run(",
            "agentRunCoordinator.resume(",
            "agentRunCoordinator.resumeRun(",
            "backgroundRuntime.resume(",
            "backgroundRuntime.resumeRun(",
            "resumePendingContinuationRuns(",
        )
        assertTrue(
            "ComputerConnectionService 没有任何真实模型续写调用；注释中的 resume 或 continueRun 不算实现",
            realContinuationCalls.any(serviceCode::contains),
        )
    }

    @Test
    fun `前台服务同时对账进程内任务和Room持久任务`() {
        assertTrue(
            "服务没有读取当前进程真实运行的 AgentRun 数量",
            serviceCode.contains("activeAgentRunCount("),
        )
        assertTrue(
            "进程死亡后必须从 Room 找回持久任务",
            serviceCode.contains("agentDao.getActiveRuns("),
        )
        assertTrue("Room 查询必须排除普通历史 INTERRUPTED", agentDao.contains("terminalReason IN"))
    }

    @Test
    fun `前台服务恢复入口必须启动真实任务监听器`() {
        assertTrue(
            "ACTION_RESUME_RECOVERY 不能只返回 START_STICKY，必须委托后台任务运行时恢复 Room 中的任务",
            service.contains("backgroundRuntime") || service.contains("taskMonitor") || service.contains("executionMonitor"),
        )
        val recoveryBranch = service.substringAfter("ACTION_RESUME_RECOVERY", missingDelimiterValue = "")
            .substringBefore("return START_STICKY", missingDelimiterValue = "")
        assertTrue(
            "恢复分支必须在返回前启动等待恢复完成的任务监听",
            listOf(".start(", ".recover(", ".resume(", "startTaskMonitoringAfterRecovery(").any(recoveryBranch::contains),
        )
    }

    @Test
    fun `系统用空Intent重建服务时仍会从持久状态恢复`() {
        val afterActionDispatch = service.substringAfter("when (intent?.action)", "")
        assertTrue("null intent 默认路径必须等待恢复后启动任务监听", afterActionDispatch.contains("startTaskMonitoringAfterRecovery()"))
        assertFalse(
            "服务不能仅凭进程内 activeTokens 判断是否立即退出",
            service.contains("if (!ComputerConnectionServiceController.hasActiveTokens()) {\n            stopForeground"),
        )
    }

    @Test
    fun `服务必须先恢复持久状态再判断空闲`() {
        assertTrue("服务缺少进程恢复 Job", service.contains("recoveryJob"))
        assertTrue("空闲判断前必须等待恢复事务", service.contains("recoveryJob?.join()"))
        val recoveryBlock = service.substringAfter("recoveryJob = serviceScope.launch", "")
            .substringBefore("startTaskMonitoringAfterRecovery()", "")
        assertTrue("服务没有恢复进程死亡遗留的 AgentRun", recoveryBlock.contains("recoverInterruptedAgentRuns()"))
    }

    @Test
    fun `ViewModel不得再次执行进程死亡恢复事务`() {
        assertFalse(
            "恢复入口必须统一归 Service 和 Worker，ViewModel 重建不能把当前进程任务误标为中断",
            viewModel.contains("recoverInterruptedAgentRuns()"),
        )
    }

    @Test
    fun `冷启动无持久任务时不得直接拉起前台服务`() {
        assertTrue(
            "App 启动必须先检查 Room 是否存在可恢复任务",
            viewModel.contains("resumeActiveTasksIfNeeded"),
        )
        assertFalse(
            "AppViewModel 仍在无条件启动前台服务",
            viewModel.contains("ComputerConnectionServiceController.resumeActiveTasks(getApplication())"),
        )
        assertFalse(
            "ComputerManager 重复无条件启动前台服务",
            computerManager.contains("ComputerConnectionServiceController.resumeActiveTasks(context)"),
        )
    }

    @Test
    fun `Room变更和网络恢复必须立即触发对账`() {
        assertTrue("服务必须观察 AgentRun 的 Room Flow", service.contains("agentDao.observeServiceRuns()"))
        assertTrue("网络恢复必须立即触发对账", service.contains("onAvailable") && service.contains("network_available"))
        assertFalse("禁止恢复三秒数据库轮询", service.contains("delay(3_000L)"))
    }

    @Test
    fun `WorkManager只能修复状态并唤醒前台服务`() {
        assertTrue(worker.contains("recoverInterruptedAgentRuns()"))
        assertTrue(worker.contains("ComputerConnectionServiceController.resumeActiveTasks"))
        assertTrue(worker.contains("Result.success()"))
        assertTrue(worker.contains("BackoffPolicy.EXPONENTIAL"))
        assertFalse("Worker 禁止直接执行模型循环", workerCode.contains("AgentLoop("))
        assertFalse("Worker 禁止直接恢复协调器", workerCode.contains("AgentRunCoordinator"))
    }

    @Test
    fun `工具结果落库中断必须走账本恢复不能直接模型续写`() {
        assertFalse(
            "PERSISTING_RESULT 不能再归到模型流恢复 SQL",
            agentDao.contains("'WAITING_MODEL', 'STREAMING_MODEL', 'PERSISTING_RESULT'"),
        )
        assertTrue(
            "PERSISTING_RESULT 必须归到工具中断恢复 SQL",
            agentDao.contains("'CHECKING_PERMISSION', 'EXECUTING_TOOL', 'PERSISTING_RESULT'"),
        )
        assertTrue(service.contains("resumeInterruptedToolRuns()"))
        assertTrue(coordinator.contains("resumableApprovalRuns(computerDao)"))
    }

    @Test
    fun `服务退出前必须查询待审批任务`() {
        assertTrue(
            "WAITING_APPROVAL 也必须阻止服务自然退出",
            service.contains("WAITING_APPROVAL") || service.contains("waitingApproval"),
        )
    }

    @Test
    fun `任务监听不能继续放在AppViewModel无限轮询`() {
        val recoveryBlock = viewModel.substringAfter("远端任务可能在 App 重启后仍处于 RUNNING", "")
            .substringBefore("aiContentReportRepository.retryPendingReports", "")
        assertFalse(
            "AppViewModel 中的 while(true) 轮询必须迁移到可恢复前台服务",
            recoveryBlock.contains("while (true)"),
        )
        assertFalse(
            "AppViewModel 不应直接承担长期 reconcileRemoteExecutions 循环",
            recoveryBlock.contains("reconcileRemoteExecutions"),
        )
    }

    @Test
    fun `服务必须按VPS复用Transport`() {
        val monitorAndPool = AgentBackgroundPlanTestFiles.allProductionKotlin()
            .filter { (file, text) -> file.name.contains("ConnectionPool") || file.name.contains("Service") || text.contains("taskMonitor") }
            .joinToString("\n") { it.second }
        assertTrue("必须按 computerId 复用 Transport", monitorAndPool.contains("computerId"))
        val repositorySource = AgentBackgroundPlanTestFiles.source("data/computer/ComputerRepository.kt")
        assertTrue("Service 与 ComputerManager 的 Repository 必须共用进程级连接池", repositorySource.contains("ComputerConnectionPoolRegistry.get"))
    }

    @Test
    fun `断线重连必须递增退避且上限约一分钟`() {
        val monitorSources = AgentBackgroundPlanTestFiles.allProductionKotlin()
            .filter { (_, text) -> text.contains("CONNECTION_LOST") || text.contains("reconnect") || text.contains("backoffMillis") }
            .joinToString("\n") { it.second }
        assertTrue("断线重连必须使用递增退避", monitorSources.contains("backoff") || monitorSources.contains("coerceAtMost"))
        assertTrue(
            "断线重连退避上限必须约为 60 秒",
            monitorSources.contains("60_000") || monitorSources.contains("60.seconds"),
        )
    }

    @Test
    fun `WakeLock必须有界续期并在全部退出路径释放`() {
        assertTrue("WakeLock 必须使用有界 acquire", service.contains("wakeLock.acquire(WAKE_LOCK_TIMEOUT_MILLIS)"))
        assertTrue("WakeLock 必须在任务持续时续期", service.contains("WAKE_LOCK_RENEW_MILLIS"))
        assertTrue("空闲和销毁路径必须统一释放 WakeLock", service.contains("updateWakeLock(false)"))
        assertTrue("服务销毁必须释放 WakeLock", service.substringAfter("override fun onDestroy").contains("updateWakeLock(false)"))
        assertFalse("任务生命周期禁止用 withTimeout 硬切断", service.contains("withTimeout("))
    }

    @Test
    fun `用户停止必须等待AgentJob并封存请求再停服务`() {
        val stopBranch = service.substringAfter("ACTION_STOP ->").substringBefore("ACTION_RESUME_RECOVERY")
        assertTrue(stopBranch.contains("cancelAllActiveRuns()"))
        assertTrue(stopBranch.indexOf("cancelAllActiveRuns()") < stopBranch.indexOf("stopSelf(startId)"))
        assertTrue("协调器必须等待所有 Job 结束", coordinator.contains("jobs.forEach { job -> job.join() }"))
        assertTrue("停止时必须封存开放请求", coordinator.contains("cancelOpenRequests(run.id, reason)"))
        assertTrue("停止时必须把 Run 写成 CANCELLED", coordinator.contains("AgentRunStatus.CANCELLED"))
    }

    @Test
    fun `用户离开页面不能向服务发送停止任务动作`() {
        val lifecycleBlocks = AgentBackgroundPlanTestFiles.allProductionKotlin()
            .flatMap { (_, source) ->
                listOf("onStop", "onPause", "onDestroy").mapNotNull { callback ->
                    source.takeIf { it.contains("fun $callback") }
                        ?.substringAfter("fun $callback")
                        ?.substringBefore("override fun", missingDelimiterValue = source.takeLast(2_000))
                }
            }
        lifecycleBlocks.forEach { block ->
            assertFalse("页面生命周期不能取消 VPS Execution", block.contains("cancelActiveExecutions"))
            assertFalse("页面生命周期不能写 USER_STOP", block.contains("AgentTerminalReasons.USER_STOP"))
        }
    }

    @Test
    fun `手机开机期间不得通过BootReceiver主动连接VPS`() {
        val manifest = AgentBackgroundPlanTestFiles.appFile("app/src/main/AndroidManifest.xml")
            .readText(Charsets.UTF_8)
        assertFalse("计划要求用户打开 App 后恢复，禁止 BOOT_COMPLETED 自动连 VPS", manifest.contains("BOOT_COMPLETED"))
        assertFalse("无需申请 RECEIVE_BOOT_COMPLETED", manifest.contains("RECEIVE_BOOT_COMPLETED"))
    }
}
