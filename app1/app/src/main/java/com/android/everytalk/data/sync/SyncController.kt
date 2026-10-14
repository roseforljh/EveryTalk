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
    val enabled: Boolean = false,
    val configured: Boolean = syncServerConfigured(BuildConfig.EVERYTALK_SYNC_BASE_URL),
    val rejectedCount: Int = 0,
    val epochDecision: Long? = null,
)

/**
 * 用户主动开启/关闭、首次上传与换账号选择的唯一入口。
 * 先取消工作再取得同步锁；从未绑定或绑定不同账号时，Worker 永远不能自动上传。
 */
internal class SyncController(
    private val context: Context,
    private val auth: StateFlow<AuthState>,
    private val binding: SyncAccountBindingStore,
    private val scope: CoroutineScope,
    private val onDifferentAccount: () -> Unit = {},
    private val beforeClearLocal: suspend () -> Unit = {},
    private val database: AppDatabase = AppDatabase.getDatabase(context),
) {
    private val store = SyncStateStore(database)
    val state: StateFlow<SyncUiState> = database.settingsDao().observeSyncControl().map { rows ->
        val values = rows.associate { it.key to it.value }
        SyncUiState(enabled = values[SyncStateStore.KEY_ENABLED] == "true",
            rejectedCount = rows.count { it.key.startsWith("sync_rejected:") },
            epochDecision = values[SyncStateStore.KEY_EPOCH_DECISION]?.toLongOrNull())
    }.stateIn(scope, SharingStarted.Eagerly, SyncUiState())
    private val mutableUploadPrompt = MutableStateFlow(false)
    val uploadPrompt = mutableUploadPrompt.asStateFlow()
    private var promptAccount: String? = null

    /** UI 已通过 FeatureGate 认证；这里再限制账号绑定，不替代公共登录门禁。 */
    fun requestEnable() = scope.launch {
        if (!state.value.configured) return@launch
        val user = (auth.value as? AuthState.SignedIn)?.user ?: return@launch
        val previous = binding.readBoundAccountId()
        if (previous != null && previous != user.id) {
            onDifferentAccount()
            return@launch
        }
        promptAccount = user.id
        mutableUploadPrompt.value = true
    }

    fun dismissUploadPrompt() { mutableUploadPrompt.value = false; promptAccount = null }

    /** 不上传旧对话：保留为 LOCAL_ONLY；随后新建/修改的文字数据才进入 PENDING。 */
    fun confirmEnable(upload: Boolean) = scope.launch {
        val user = (auth.value as? AuthState.SignedIn)?.user ?: return@launch
        if (promptAccount != user.id) return@launch
        dismissUploadPrompt()
        SyncScheduler.cancel(context)
        SyncRuntimeLock.mutex.withLock {
            store.prepareAccount(user.id, upload)
            binding.bindTo(user.id)
        }
        SyncScheduler.request(context)
    }

    fun disable() = scope.launch {
        SyncScheduler.cancel(context)
        store.setEnabled(false)
    }

    fun decideEpoch(upload: Boolean) = scope.launch {
        val accountId = (auth.value as? AuthState.SignedIn)?.user?.id
        if (upload && (accountId == null || binding.readBoundAccountId() != accountId)) return@launch
        SyncScheduler.cancel(context)
        SyncRuntimeLock.mutex.withLock {
            if (store.epochDecision() == null) return@withLock
            if (upload) store.markAllLocalDataPending() else store.rejectEpochUpload()
        }
        if (upload) SyncScheduler.request(context)
    }

    suspend fun handleAccountSwitch(choice: AccountSwitchChoice, accountId: String) {
        // 弹窗期间账号也可能变化，旧选择不能为新账号授权。
        if ((auth.value as? AuthState.SignedIn)?.user?.id != accountId) return
        SyncScheduler.cancel(context)
        SyncRuntimeLock.mutex.withLock {
            when (choice) {
                AccountSwitchChoice.KEEP_LOCAL -> store.setEnabled(false)
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
