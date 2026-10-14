package com.android.everytalk.data.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 所有需要 EveryTalk 账号的功能必须使用这里的枚举，页面不自行判断登录状态。 */
internal enum class Feature {
    CLOUD_SYNC,
    OFFICIAL_MODELS,
    MEMBERSHIP,
    QUOTA,
}

internal sealed interface FeatureDecision {
    data object Allowed : FeatureDecision
    data object NeedLogin : FeatureDecision
    data object NeedUpgrade : FeatureDecision
}

internal class FeatureGate(private val authState: StateFlow<AuthState>) {
    fun check(@Suppress("UNUSED_PARAMETER") feature: Feature): FeatureDecision = when (authState.value) {
        is AuthState.SignedIn -> FeatureDecision.Allowed
        AuthState.LocalOnly, AuthState.SessionExpired -> FeatureDecision.NeedLogin
    }

    fun isLocked(feature: Feature): Boolean = check(feature) != FeatureDecision.Allowed
}

/**
 * 保存一次待执行动作，供登录页返回后恢复。
 * NeedLogin 只保存内存中的 UI 操作，不写入磁盘；取消、返回或执行后都会清除，避免重复执行。
 */
internal class FeatureGateCoordinator(
    private val gate: FeatureGate,
    authState: StateFlow<AuthState>,
    scope: CoroutineScope,
) {
    private val mutablePrompt = MutableStateFlow<Feature?>(null)
    val prompt: StateFlow<Feature?> = mutablePrompt.asStateFlow()

    private var pendingFeature: Feature? = null
    private var pendingAction: (() -> Unit)? = null
    private var loginStarted = false

    init {
        scope.launch {
            authState.collect { state ->
                if (state is AuthState.SignedIn && loginStarted) {
                    completeLogin()
                }
            }
        }
    }

    fun requireLogin(feature: Feature, action: () -> Unit): FeatureDecision {
        return when (val decision = gate.check(feature)) {
            FeatureDecision.Allowed -> {
                action()
                decision
            }
            FeatureDecision.NeedLogin -> {
                pendingFeature = feature
                pendingAction = action
                mutablePrompt.value = feature
                decision
            }
            FeatureDecision.NeedUpgrade -> decision
        }
    }

    fun beginLogin() {
        check(pendingFeature != null) { "没有等待中的商业功能操作" }
        loginStarted = true
        mutablePrompt.value = null
    }

    fun cancel() {
        pendingFeature = null
        pendingAction = null
        loginStarted = false
        mutablePrompt.value = null
    }

    fun hasPendingLogin(): Boolean = loginStarted && pendingAction != null

    private fun completeLogin() {
        val action = pendingAction
        pendingFeature = null
        pendingAction = null
        loginStarted = false
        mutablePrompt.value = null
        action?.invoke()
    }
}
