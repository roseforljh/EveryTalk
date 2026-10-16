package com.android.everytalk.data.sync

import android.content.Context
import com.android.everytalk.BuildConfig
import com.android.everytalk.data.auth.*
import com.android.everytalk.data.database.AppDatabase
import com.android.everytalk.service.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

internal data class SyncUiState(
    val configured: Boolean = syncServerConfigured(BuildConfig.EVERYTALK_SYNC_BASE_URL),
    val rejectedCount: Int = 0,
    val rejectedItems: List<SyncRejectedItem> = emptyList(),
    val epochDecision: Long? = null,
    val lastSyncAt: Long? = null,
    val pausedAfterCloudReset: Boolean = false,
)

internal data class SyncRejectedItem(val entity: String, val id: String, val code: String, val kind: String? = null)

/**
 * 账号数据的自动同步、云端清空后恢复与换账号选择。
 * 普通登录的自动绑定由 SyncScheduler 统一处理，不依赖页面是否打开。
 */
internal class SyncController(
    private val context: Context,
    private val auth: StateFlow<AuthState>,
    private val binding: SyncAccountBindingStore,
    private val scope: CoroutineScope,
    private val beforeClearLocal: suspend () -> Unit = {},
    private val database: AppDatabase = AppDatabase.getDatabase(context),
) {
    private val store = SyncStateStore(database)
    val state: StateFlow<SyncUiState> = database.settingsDao().observeSyncControl().map { rows ->
        val values = rows.associate { it.key to it.value }
        val rejected = rows.filter { it.key.startsWith("sync_rejected:") }.mapNotNull { row ->
            val parts = row.key.removePrefix("sync_rejected:").split(':', limit = 2)
            parts.takeIf { it.size == 2 }?.let {
                val kind = if (it[0] == "user_settings") database.settingsDao().getUserSettingByUuid(it[1])?.kind else null
                SyncRejectedItem(it[0], it[1], row.value.takeIf { code -> code.matches(Regex("[A-Za-z_]{1,64}")) } ?: "UNKNOWN_ERROR", kind)
            }
        }
        SyncUiState(rejectedCount = rejected.size, rejectedItems = rejected,
            epochDecision = values[SyncStateStore.KEY_EPOCH_DECISION]?.toLongOrNull(),
            lastSyncAt = values[SyncStateStore.KEY_LAST_SYNC_AT]?.toLongOrNull(),
            pausedAfterCloudReset = values[SyncStateStore.KEY_RESET_PAUSED] == "true")
    }.stateIn(scope, SharingStarted.Eagerly, SyncUiState())
    fun decideEpoch(upload: Boolean) = scope.launch {
        val accountId = (auth.value as? AuthState.SignedIn)?.user?.id
        if (upload && (accountId == null || binding.readBoundAccountId() != accountId)) return@launch
        SyncScheduler.cancel(context)
        SyncRuntimeLock.mutex.withLock {
            // 等待正在运行的同步结束期间，用户可能退出或切换账号。
            if (upload && (auth.value as? AuthState.SignedIn)?.user?.id != accountId) return@launch
            if (store.epochDecision() == null && !store.isPausedAfterCloudReset()) return@withLock
            if (upload) store.resumeAfterCloudReset() else store.rejectEpochUpload()
        }
        if (upload) SyncScheduler.request(context)
    }

    suspend fun handleAccountSwitch(choice: AccountSwitchChoice, accountId: String) {
        // 弹窗期间账号也可能变化，旧选择不能为新账号授权。
        if ((auth.value as? AuthState.SignedIn)?.user?.id != accountId) return
        SyncScheduler.cancel(context)
        SyncRuntimeLock.mutex.withLock {
            if ((auth.value as? AuthState.SignedIn)?.user?.id != accountId) return
            when (choice) {
                AccountSwitchChoice.KEEP_LOCAL -> Unit
                AccountSwitchChoice.UPLOAD_TO_CURRENT -> {
                    store.prepareAccount(accountId, upload = true)
                    binding.bindTo(accountId)
                }
                AccountSwitchChoice.CLEAR_LOCAL -> {
                    beforeClearLocal()
                    store.clearSyncableLocalData()
                    store.prepareAccount(accountId, upload = false)
                    binding.bindTo(accountId)
                }
            }
        }
        if (choice == AccountSwitchChoice.CLEAR_LOCAL) SyncLocalChanges.notifyChanged()
        if (choice != AccountSwitchChoice.KEEP_LOCAL) SyncScheduler.request(context)
    }
}
