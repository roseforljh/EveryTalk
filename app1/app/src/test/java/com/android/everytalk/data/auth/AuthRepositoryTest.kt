package com.android.everytalk.data.auth

import com.android.everytalk.data.account.AccountManager
import com.android.everytalk.data.account.AccountUiState
import com.android.everytalk.data.account.AccountUser
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemoryAuthModeStore(initial: AuthMode = AuthMode.UNSET) : AuthModeStore {
    var mode = initial
    override suspend fun read(): AuthMode = mode
    override suspend fun write(mode: AuthMode) {
        this.mode = mode
    }
}

private fun completedJob(): Job = SupervisorJob().also { it.complete() }

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuthRepositoryTest {
    @Test
    fun `用户先选本地模式之后登录并重启最终保持 SignedIn`() = runTest {
        val firstState = MutableStateFlow(AccountUiState(configured = true, initialized = true))
        val firstManager = mockk<AccountManager>(relaxed = true)
        every { firstManager.state } returns firstState.asStateFlow()
        every { firstManager.restoreStoredSession() } returns completedJob()

        val store = MemoryAuthModeStore()
        val firstRepository = AuthRepository(firstManager, store, backgroundScope)
        runCurrent()
        advanceUntilIdle()
        firstRepository.chooseLocalOnly().join()
        advanceUntilIdle()

        firstState.value = firstState.value.copy(
            user = AccountUser(id = "user-1", email = "user@example.com"),
        )
        runCurrent()
        advanceUntilIdle()
        assertEquals(AuthMode.SIGNED_IN, store.mode)

        val restartedState = MutableStateFlow(
            AccountUiState(
                configured = true,
                initialized = true,
                user = AccountUser(id = "user-1", email = "user@example.com"),
            ),
        )
        val restartedManager = mockk<AccountManager>(relaxed = true)
        every { restartedManager.state } returns restartedState.asStateFlow()
        every { restartedManager.restoreStoredSession() } returns completedJob()

        val restartedRepository = AuthRepository(restartedManager, store, backgroundScope)
        runCurrent()
        advanceUntilIdle()

        assertEquals(
            AuthState.SignedIn(AccountUser(id = "user-1", email = "user@example.com")),
            restartedRepository.state.value,
        )
        assertTrue(restartedRepository.startup.value.onboardingCompleted)
        verify(exactly = 1) { restartedManager.restoreStoredSession() }
    }

    @Test
    fun `LocalOnly 启动不恢复会话`() = runTest {
        val state = MutableStateFlow(AccountUiState(configured = true, initialized = true))
        val manager = mockk<AccountManager>(relaxed = true)
        every { manager.state } returns state.asStateFlow()
        val store = MemoryAuthModeStore(AuthMode.LOCAL_ONLY)

        val repository = AuthRepository(manager, store, backgroundScope)
        runCurrent()
        advanceUntilIdle()

        assertEquals(AuthState.LocalOnly, repository.state.value)
        assertTrue(repository.startup.value.onboardingCompleted)
        verify(exactly = 0) { manager.restoreStoredSession() }
    }
}
