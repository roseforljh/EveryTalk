package com.android.everytalk.data.auth

import com.android.everytalk.data.account.AccountUser

/**
 * EveryTalk 自身账号功能的统一状态。
 *
 * LocalOnly 允许所有本地功能继续工作；SessionExpired 只表示账号会话需要重新登录，
 * 不影响本地会话、模型配置或聊天历史。
 */
internal sealed interface AuthState {
    data object LocalOnly : AuthState

    data class SignedIn(val user: AccountUser) : AuthState

    data object SessionExpired : AuthState
}

/** 启动入口的辅助状态，避免把“还没选择本地/登录”伪装成已完成的 LocalOnly。 */
internal data class AuthStartupState(
    val initialized: Boolean = false,
    val onboardingCompleted: Boolean = false,
)
