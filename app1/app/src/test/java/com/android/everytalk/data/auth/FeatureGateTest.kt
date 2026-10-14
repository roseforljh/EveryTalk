package com.android.everytalk.data.auth

import com.android.everytalk.data.account.AccountUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FeatureGateTest {
    @Test
    fun `本地和过期会话统一需要登录`() {
        val authState = MutableStateFlow<AuthState>(AuthState.LocalOnly)
        val gate = FeatureGate(authState)

        assertEquals(FeatureDecision.NeedLogin, gate.check(Feature.CLOUD_SYNC))
        authState.value = AuthState.SessionExpired
        assertEquals(FeatureDecision.NeedLogin, gate.check(Feature.MEMBERSHIP))
        authState.value = AuthState.SignedIn(AccountUser("user-1", "user@example.com"))
        assertEquals(FeatureDecision.Allowed, gate.check(Feature.QUOTA))
    }

    @Test
    fun `登录成功后待执行操作只执行一次`() = runTest {
        val authState = MutableStateFlow<AuthState>(AuthState.LocalOnly)
        val coordinator = FeatureGateCoordinator(FeatureGate(authState), authState, backgroundScope)
        runCurrent()
        var runs = 0

        assertEquals(
            FeatureDecision.NeedLogin,
            coordinator.requireLogin(Feature.CLOUD_SYNC) { runs++ },
        )
        coordinator.beginLogin()
        authState.value = AuthState.SignedIn(AccountUser("user-1", "user@example.com"))
        runCurrent()
        advanceUntilIdle()

        assertEquals(1, runs)
        assertNull(coordinator.prompt.value)
        assertTrue(!coordinator.hasPendingLogin())
    }

    @Test
    fun `取消登录不会执行待执行操作`() = runTest {
        val authState = MutableStateFlow<AuthState>(AuthState.LocalOnly)
        val coordinator = FeatureGateCoordinator(FeatureGate(authState), authState, backgroundScope)
        runCurrent()
        var runs = 0

        coordinator.requireLogin(Feature.MEMBERSHIP) { runs++ }
        coordinator.cancel()
        authState.value = AuthState.SignedIn(AccountUser("user-1", "user@example.com"))
        advanceUntilIdle()

        assertEquals(0, runs)
    }
}
