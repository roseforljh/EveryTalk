package com.android.everytalk.data.auth

import com.android.everytalk.data.account.AccountUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 换账号时用户必须明确选择的数据处理方式。 */
internal enum class AccountSwitchChoice {
    KEEP_LOCAL,
    UPLOAD_TO_CURRENT,
    CLEAR_LOCAL,
}

internal data class AccountSwitchRequest(
    val previousAccountId: String,
    val currentUser: AccountUser,
)

/**
 * 只在“已有同步绑定账号”且新登录账号不同的时候暂停流程。
 * 阶段 1 没有同步服务器，UPLOAD_TO_CURRENT 只更新绑定目标；阶段 3 的同步引擎会读取它并执行上传。
 */
internal class AccountSwitchProtection(
    authState: StateFlow<AuthState>,
    private val bindingStore: SyncAccountBindingStore,
    private val scope: CoroutineScope,
    private val clearLocalData: () -> Unit,
) {
    private val mutableRequest = MutableStateFlow<AccountSwitchRequest?>(null)
    val request: StateFlow<AccountSwitchRequest?> = mutableRequest.asStateFlow()

    init {
        scope.launch {
            authState.collectLatest { state ->
                val user = (state as? AuthState.SignedIn)?.user ?: return@collectLatest
                val previousAccountId = bindingStore.readBoundAccountId()
                if (!previousAccountId.isNullOrBlank() && previousAccountId != user.id) {
                    mutableRequest.value = AccountSwitchRequest(previousAccountId, user)
                }
            }
        }
    }

    /**
     * 处理三选一结果并关闭弹窗。
     * 清空本地只清理当前同步范围内的文字对话和长期记忆，不碰 API Key 与其他本地设置。
     */
    fun choose(choice: AccountSwitchChoice): Job = scope.launch {
        val current = mutableRequest.value ?: return@launch
        when (choice) {
            AccountSwitchChoice.KEEP_LOCAL -> bindingStore.clearBinding()
            AccountSwitchChoice.UPLOAD_TO_CURRENT -> bindingStore.bindTo(current.currentUser.id)
            AccountSwitchChoice.CLEAR_LOCAL -> {
                bindingStore.clearBinding()
                clearLocalData()
            }
        }
        mutableRequest.value = null
    }
}
