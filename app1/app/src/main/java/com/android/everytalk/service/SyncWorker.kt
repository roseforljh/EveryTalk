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
        if (!syncServerConfigured(BuildConfig.EVERYTALK_SYNC_BASE_URL) || !permitted()) return Result.success()
        // 不占用 Worker 睡眠；截止时间跨重启持久化，WorkManager 指数退避后仍不得提前请求。
        if (state.retryAt() > System.currentTimeMillis()) return Result.retry()
        val secrets = SyncSecretManager(applicationContext)
        val api = KtorSyncApi(BuildConfig.EVERYTALK_SYNC_BASE_URL, runtime.account,
            isLocalOnly = { runtime.auth.state.value !is AuthState.SignedIn },
            canRequest = { permitted() })
        return try {
            when (SyncEngine(
                api = api,
                store = RoomSyncStore(db, secrets, user.id),
                state = state,
                canSync = ::permitted,
                onSecretsEpochChanged = { secrets.clearCachedDek() },
            ).runOnce()) {
                SyncRunResult.MoreWork -> Result.retry()
                SyncRunResult.Completed -> { state.markSyncCompleted(); state.clearRetry(); Result.success() }
                else -> Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SyncRefreshRejectedException) {
            // AccountManager 已将明确无效的刷新凭据转换为 SessionExpired；不清任何本地业务数据。
            Result.success()
        } catch (error: SyncHttpException) {
            if (error.statusCode == 429) state.deferRetry(error.retryAfterSeconds ?: 30)
            // 刷新成功后服务器仍报 401，按暂态请求失败重试，不能伪造 SessionExpired。
            Result.retry()
        } catch (_: SyncNotConfiguredException) {
            Result.success()
        } catch (_: Exception) {
            AppLogger.warn("Sync", "同步暂未完成，等待重试")
            if (permitted()) Result.retry() else Result.success()
        } finally { api.close() }
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
        manager.enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, immediate)
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

