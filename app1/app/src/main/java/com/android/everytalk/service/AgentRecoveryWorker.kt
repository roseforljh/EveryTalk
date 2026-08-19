package com.android.everytalk.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.agent.AgentRecoveryDiagnostics
import com.android.everytalk.util.AppLogger
import java.util.concurrent.TimeUnit

private const val AGENT_RECOVERY_WORK_NAME = "agent-recovery"
private const val AGENT_RECOVERY_BACKOFF_SECONDS = 30L

/**
 * 系统调度只负责恢复检查和唤醒前台服务。
 * 完整模型请求仍由 ComputerConnectionService 中的 AgentRunCoordinator 执行。
 */
class AgentRecoveryWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val database = AppDatabase.getDatabase(applicationContext)
        val agentDao = database.agentDao()
        val computerDao = database.computerDao()
        if (!ComputerConnectionServiceController.hasActiveTokens() &&
            ComputerConnectionServiceController.activeAgentRunCount() == 0
        ) {
            val interruptedRuns = agentDao.getActiveRuns()
            agentDao.recoverInterruptedAgentRuns()
            interruptedRuns.forEach { run ->
                AgentRecoveryDiagnostics.record(
                    run = run,
                    recoveryDecision = "PROCESS_DEATH_STATE_RECONCILED",
                    serviceStartReason = "WORK_MANAGER",
                    networkState = "CONNECTED",
                )
            }
        }

        val hasRecoverableWork = agentDao.getActiveRuns().isNotEmpty() ||
            computerDao.getActiveRemoteExecutions().isNotEmpty()
        if (!hasRecoverableWork) return Result.success()

        return runCatching {
            ComputerConnectionServiceController.resumeActiveTasks(applicationContext)
            AppLogger.debug("AgentRecoveryWorker", "Recovery service requested; attempt=$runAttemptCount")
            Result.retry()
        }.getOrElse { error ->
            AppLogger.warn("AgentRecoveryWorker", "Unable to request recovery service: ${error.message}")
            Result.retry()
        }
    }
}

/** 一个进程只保留一条恢复工作；有网后执行，活动任务结束时自然成功退出。 */
object AgentRecoveryScheduler {
    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<AgentRecoveryWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                AGENT_RECOVERY_BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .build()
        runCatching {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                AGENT_RECOVERY_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }.onFailure { error ->
            AppLogger.warn("AgentRecoveryScheduler", "Unable to schedule recovery: ${error.message}")
        }
    }

    fun cancel(context: Context) {
        runCatching {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(AGENT_RECOVERY_WORK_NAME)
        }.onFailure { error ->
            AppLogger.warn("AgentRecoveryScheduler", "Unable to cancel recovery: ${error.message}")
        }
    }
}
