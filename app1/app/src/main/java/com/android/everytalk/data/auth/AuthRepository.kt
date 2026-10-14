package com.android.everytalk.data.auth

import com.android.everytalk.data.account.AccountManager
import com.android.everytalk.data.account.AccountUiState
import com.android.everytalk.data.account.AccountUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 认证领域的唯一入口。
 *
 * AccountManager 继续负责已有的 Supabase/邮箱/OAuth 流程；本类只负责把它转换成产品需要的
 * LocalOnly、SignedIn、SessionExpired 三态，并持久化用户选择。LocalOnly 分支不会调用恢复会话，
 * 因此不会因为启动应用而刷新 token 或访问认证服务。
 */
internal class AuthRepository(
    private val accountManager: AccountManager,
    private val modeStore: AuthModeStore,
    private val scope: CoroutineScope,
    private val onLogout: suspend () -> Unit = {},
) {
    private var loggingOut = false
    private val mutableState = MutableStateFlow<AuthState>(AuthState.LocalOnly)
    val state: StateFlow<AuthState> = mutableState.asStateFlow()

    private val mutableStartup = MutableStateFlow(AuthStartupState())
    val startup: StateFlow<AuthStartupState> = mutableStartup.asStateFlow()

    init {
        scope.launch {
            accountManager.state.collect(::observeAccountState)
        }
        scope.launch { initialize() }
    }

    /** 首次启动的“先本地使用”按钮只改变模式，不触碰账号凭据和本地数据。 */
    fun chooseLocalOnly(): Job = scope.launch {
        modeStore.write(AuthMode.LOCAL_ONLY)
        mutableState.value = AuthState.LocalOnly
        mutableStartup.value = AuthStartupState(initialized = true, onboardingCompleted = true)
    }

    /** 账号页退出后回到本地模式；AccountManager 仍负责清理加密会话。 */
    fun logout(): Job = scope.launch {
        loggingOut = true
        mutableState.value = AuthState.LocalOnly
        try {
            onLogout()
            modeStore.write(AuthMode.LOCAL_ONLY)
            accountManager.logout().join()
        } finally {
            mutableState.value = AuthState.LocalOnly
            mutableStartup.update { it.copy(onboardingCompleted = true) }
            loggingOut = false
        }
    }

    private suspend fun initialize() {
        when (modeStore.read()) {
            AuthMode.LOCAL_ONLY -> {
                mutableState.value = AuthState.LocalOnly
                mutableStartup.value = AuthStartupState(initialized = true, onboardingCompleted = true)
            }
            AuthMode.SIGNED_IN -> {
                accountManager.restoreStoredSession().join()
                completeSignedInStartup()
            }
            AuthMode.UNSET -> {
                // 兼容升级前已经登录的用户；没有旧凭据时只展示首次欢迎页。
                accountManager.restoreStoredSession().join()
                if (accountManager.state.value.user != null) {
                    modeStore.write(AuthMode.SIGNED_IN)
                    mutableStartup.value = AuthStartupState(initialized = true, onboardingCompleted = true)
                } else {
                    mutableState.value = if (accountManager.state.value.sessionExpired) {
                        AuthState.SessionExpired
                    } else {
                        AuthState.LocalOnly
                    }
                    mutableStartup.value = AuthStartupState(initialized = true, onboardingCompleted = false)
                }
            }
        }
    }

    private suspend fun completeSignedInStartup() {
        mutableState.value = when {
            accountManager.state.value.user != null -> AuthState.SignedIn(
                requireNotNull(accountManager.state.value.user),
            )
            accountManager.state.value.sessionExpired -> AuthState.SessionExpired
            else -> AuthState.SessionExpired
        }
        mutableStartup.value = AuthStartupState(initialized = true, onboardingCompleted = true)
    }

    private suspend fun observeAccountState(accountState: AccountUiState) {
        if (loggingOut) return
        val user = accountState.user
        if (user != null) {
            modeStore.write(AuthMode.SIGNED_IN)
            mutableState.value = AuthState.SignedIn(user)
            mutableStartup.update { it.copy(onboardingCompleted = true) }
        } else if (accountState.sessionExpired) {
            mutableState.value = AuthState.SessionExpired
        }
    }
}
