package com.android.everytalk.service

import android.content.Context
import androidx.room.InvalidationTracker
import androidx.work.*
import com.android.everytalk.BuildConfig
import com.android.everytalk.data.auth.AuthRuntime
import com.android.everytalk.data.auth.AuthState
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.data.sync.*
import com.android.everytalk.util.AppLogger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest

/**
 * Worker 使用与 UI 相同的认证实例。LocalOnly/SessionExpired 不刷新、不上传，
 * 账号绑定、云端清空暂停与限流截止时间在每次请求前复查。
 */
class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val runtime = AuthRuntime.get(applicationContext)
        runtime.auth.startup.first { it.initialized }
        val user = (runtime.auth.state.value as? AuthState.SignedIn)?.user ?: return Result.success()
        val db = AppDatabase.getDatabase(applicationContext)
        val state = SyncStateStore(db)
        suspend fun permitted(): Boolean = runtime.auth.state.value.let {
            it is AuthState.SignedIn && it.user.id == user.id && !runtime.account.state.value.sessionExpired &&
                runtime.binding.readBoundAccountId() == user.id && state.isEnabled() && state.epochDecision() == null
        }
        if (!syncServerConfigured(BuildConfig.EVERYTALK_SYNC_BASE_URL) || !permitted()) {
            syncDiagnostic("worker skipped: configuration or account binding or cloud reset pause")
            return Result.success()
        }
        // 限流截止时间跨重启保留；到期前只安排后续任务，不能让多次 429 累积成数小时退避。
        if (state.retryAt() > System.currentTimeMillis()) {
            syncDiagnostic("worker waiting Retry-After")
            SyncScheduler.continueResources(applicationContext, state.retryAt() - System.currentTimeMillis())
            return Result.success()
        }
        val secrets = SyncSecretManager(applicationContext)
        val api = KtorSyncApi(BuildConfig.EVERYTALK_SYNC_BASE_URL, runtime.account,
            isLocalOnly = { runtime.auth.state.value !is AuthState.SignedIn },
            canRequest = { permitted() }, readSecretsEpoch = { state.readSecretsEpoch().coerceAtLeast(0L) })
        syncDiagnostic("worker start attempt=$runAttemptCount")
        val resources = SyncResourceSynchronizer(applicationContext, db, api, user.id)
        val credentials = SyncCredentialSynchronizer(applicationContext, db, api, secrets, user.id)
        return try {
            when (SyncEngine(
                api = api,
                beforePull = { credentials.captureLocal() },
                beforePullPage = { credentials.captureLocal() },
                afterPull = {
                    // 后端未升级/未配置时明确显示缺项，但普通文字同步仍可继续，不能假报完整成功。
                    recoverService(db, "credentials") { credentials.recover() }
                },
                beforeComplete = {
                    recoverService(db, "resources") { resources.synchronize() }
                },
                store = RoomSyncStore(db, secrets, user.id),
                state = state,
                canSync = ::permitted,
                onSecretsEpochChanged = { secrets.clearCachedDek() },
            ).runOnce()) {
                SyncRunResult.MoreWork -> {
                    SyncScheduler.continueResources(applicationContext)
                    Result.success()
                }
                SyncRunResult.Completed -> {
                    if (resources.moreWork) {
                        SyncScheduler.continueResources(applicationContext)
                        return Result.success()
                    }
                    if (db.settingsDao().getByPrefix("sync_rejected:services:").isEmpty()) state.markSyncCompleted()
                    state.clearRetry()
                    val rejectionCodes = db.settingsDao().getByPrefix("sync_rejected:").groupingBy {
                        it.value.takeIf { code -> code.matches(Regex("[A-Za-z_]{1,64}")) } ?: "UNKNOWN"
                    }.eachCount()
                    if (rejectionCodes.isNotEmpty()) syncDiagnostic("rejected codes=$rejectionCodes")
                    withContext(Dispatchers.IO) {
                        db.openHelper.readableDatabase.query("""
                            SELECT COUNT(*), SUM(CASE WHEN reasoning IS NOT NULL AND reasoning != '' THEN 1 ELSE 0 END)
                            FROM messages WHERE deleted = 0 AND sessionId IN
                            (SELECT id FROM chat_sessions WHERE deleted = 0 AND isImageGeneration = 0
                            AND id NOT IN ('last_open_chat_v1', 'last_open_image_generation_v1'))
                        """.trimIndent()).use { cursor ->
                            if (cursor.moveToFirst()) syncDiagnostic("complete messages=${cursor.getLong(0)} withReasoning=${cursor.getLong(1)} sessions=${db.chatDao().getAllSessions(false).size} settings=${db.settingsDao().getSyncableUserSettings().size} rejected=${db.settingsDao().syncRejectedCount()}")
                        }
                    }
                    Result.success()
                }
                else -> Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SyncRefreshRejectedException) {
            // AccountManager 已将明确无效的刷新凭据转换为 SessionExpired；不清任何本地业务数据。
            Result.success()
        } catch (error: SyncHttpException) {
            syncDiagnostic("retry http=${error.statusCode}")
            if (error.statusCode == 429) {
                state.deferRetry((error.retryAfterSeconds ?: 30).coerceAtLeast(1))
                SyncScheduler.continueResources(applicationContext, state.retryAt() - System.currentTimeMillis())
                return Result.success()
            }
            // 刷新成功后服务器仍报 401，按暂态请求失败重试，不能伪造 SessionExpired。
            Result.retry()
        } catch (_: SyncNotConfiguredException) {
            Result.success()
        } catch (error: Exception) {
            // 仅记录异常类型，不输出异常 message/stack，避免 URL 或数据内容进入 Release 日志。
            syncDiagnostic("retry error=${error.javaClass.simpleName}")
            AppLogger.warn("Sync", "同步暂未完成，等待重试")
            if (permitted()) Result.retry() else Result.success()
        } finally { resources.close(); api.close() }
    }

    private suspend fun recoverService(db: AppDatabase, name: String, block: suspend () -> Unit) {
        val marker = "sync_rejected:services:$name"
        try { block(); db.settingsDao().deleteSetting(marker) }
        catch (error: SyncHttpException) {
            if (error.statusCode != 404 && error.serverCode !in setOf("ACCOUNT_KEY_NOT_CONFIGURED", "FILE_SERVICE_NOT_CONFIGURED", "LEGACY_KEY_REQUIRED")) throw error
            db.settingsDao().insertSetting(com.android.everytalk.data.database.entities.SystemSettingEntity(marker,
                error.serverCode?.takeIf { it.matches(Regex("[A-Z_]{1,64}")) } ?: "SERVER_UPGRADE_REQUIRED"))
        }
    }
}

/**
 * 一条即时唯一任务用于启动/变更，一条 15 分钟唯一周期任务用于其他设备的变更。
 * 二者共享 SyncRuntimeLock；本地模式下两者均取消。数据变更防抖 1 秒且只在有 PENDING 时唤醒。
 */
object SyncScheduler {
    private const val NOW = "everytalk-cloud-sync"
    private const val PERIODIC = "everytalk-cloud-sync-periodic"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var debounceJob: Job? = null
    private var observer: InvalidationTracker.Observer? = null
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** 正常分批和 429 都用新任务续传；延迟遵守服务端截止时间，保留联网约束且重新从第零次尝试开始。 */
    internal fun continueResources(context: Context, delayMillis: Long = 1_000): Operation =
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE,
            immediateWork(delayMillis.coerceAtLeast(1_000)))

    fun start(context: Context) {
        val app = context.applicationContext
        if (observer != null) return
        val db = AppDatabase.getDatabase(app)
        observer = object : InvalidationTracker.Observer(
            "chat_sessions", "messages", "memories", "user_settings", "api_configs", "voice_backend_configs",
            "mcp_server_configs", "computers", "computer_workspaces", "system_settings", "skill_installations",
        ) {
            override fun onInvalidated(tables: Set<String>) { request(app, dataChanged = true) }
        }.also { db.invalidationTracker.addObserver(it) }
        scope.launch {
            val runtime = AuthRuntime.get(app)
            runtime.auth.startup.first { it.initialized }
            runtime.auth.state.map { (it as? AuthState.SignedIn)?.user?.id }.distinctUntilChanged().collectLatest { accountId ->
                if (accountId == null) {
                    cancel(app)
                } else if (syncServerConfigured(BuildConfig.EVERYTALK_SYNC_BASE_URL)) {
                    // 在防抖任务之外准备历史数据，防止 Room 变更回调取消正在准备的事务。
                    val ready = SyncStateStore(db).prepareAutomaticAccount(accountId, runtime.binding) {
                        (runtime.auth.state.value as? AuthState.SignedIn)?.user?.id == accountId
                    }
                    if (ready) request(app)
                }
            }
        }
    }

    @Synchronized
    fun request(context: Context, dataChanged: Boolean = false) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(1000)
            val app = context.applicationContext
            scheduleIfEnabled(app, dataChanged)
        }
    }

    suspend fun scheduleIfEnabled(context: Context, dataChanged: Boolean = false) {
        val runtime = AuthRuntime.get(context)
        val user = (runtime.auth.state.value as? AuthState.SignedIn)?.user ?: return
        val state = SyncStateStore(AppDatabase.getDatabase(context))
        if (!syncServerConfigured(BuildConfig.EVERYTALK_SYNC_BASE_URL) ||
            !state.isEnabled() || state.epochDecision() != null ||
            runtime.binding.readBoundAccountId() != user.id) return
        if (dataChanged && RoomSyncStore(AppDatabase.getDatabase(context)).pendingBatch(1).isEmpty()) return
        val manager = WorkManager.getInstance(context)
        val immediate = immediateWork((state.retryAt() - System.currentTimeMillis()).coerceAtLeast(0))
        // 用户重新打开应用时接管旧版积累的限流退避；正在传输的任务继续运行。
        // 已持久化的 Retry-After 仍作为 initialDelay，不能通过重启绕过服务端限流。
        val resumeRateLimited = !dataChanged && state.retryAt() > 0 && withContext(Dispatchers.IO) {
            manager.getWorkInfosForUniqueWork(NOW).get().none { it.state == WorkInfo.State.RUNNING }
        }
        manager.enqueueUniqueWork(NOW, if (resumeRateLimited) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, immediate)
        val periodic = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
    }

    /** 调度与测试共用真实 WorkRequest，明确验证联网约束和指数退避。 */
    internal fun immediateWork(delayMillis: Long): OneTimeWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()
        .setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS).build()

    @Synchronized
    fun cancel(context: Context) {
        debounceJob?.cancel()
        val manager = WorkManager.getInstance(context.applicationContext)
        manager.cancelUniqueWork(NOW)
        manager.cancelUniqueWork(PERIODIC)
    }
}

